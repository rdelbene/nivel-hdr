package com.rdelbene.nivelhdr

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile

/*
 * Nivel HDR automático: cada foto nueva de la cámara (la stock de Motorola u otra) se ajusta sola al nivel
 * elegido, segundos después de guardarse. Android avisa cuando cambia la galería (JobScheduler con disparador
 * de contenido); el ajuste reescribe en el mismo archivo solo los ~20 bytes de la intensidad del Ultra HDR,
 * así que la imagen queda idéntica. Necesita el permiso "Acceso a todos los archivos" para escribir en
 * fotos que guardó otra app.
 */
object AutoHdr {
    private const val JOB_ID = 4931
    private const val PREFS = "cfg"

    /** Agregar Ultra HDR a las fotos que llegan sin él (x3, x0.5, frontal). Prendido por defecto. */
    fun addOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoAdd", true)
    fun setAdd(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoAdd", on).apply()

    fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoOn", false)
    fun level(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("tuneBoost", 2.5f).toDouble()
    fun canWrite() = Environment.isExternalStorageManager()

    /** Prende o apaga. Al prender, solo cuentan las fotos que se saquen desde ahora. */
    fun setOn(ctx: Context, on: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoOn", on)
        if (on) p.putLong("autoLastId", maxId(ctx))
        p.apply()
        if (on) schedule(ctx) else ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    /** Se llama al abrir la app: si estaba prendido, se asegura de que el aviso siga programado. */
    fun ensure(ctx: Context) { if (isOn(ctx)) schedule(ctx) }

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoHdrJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(3000)
            .build()
        js.schedule(job)
    }

    private fun maxId(ctx: Context): Long {
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, "${MediaStore.Images.Media._ID} DESC")?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }

    /** Ajusta las fotos nuevas de DCIM/Camera. Devuelve cuántas cambió. */
    fun processNew(ctx: Context): Int {
        if (!isOn(ctx) || !canWrite()) return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var lastId = prefs.getLong("autoLastId", Long.MAX_VALUE)
        val target = level(ctx)
        var changed = 0
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.RELATIVE_PATH)
        val paths = mutableListOf<String>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()), "${MediaStore.Images.Media._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(2) != 0) break // la cámara todavía lo está guardando: se vuelve a mirar en el próximo aviso
                val path = c.getString(1)
                val owner = c.getString(3)
                val mime = c.getString(4)
                val rel = c.getString(5) ?: ""
                val ours = owner == ctx.packageName // las copias que guarda esta app ya tienen su nivel
                if (!ours && mime == "image/jpeg" && rel.startsWith("DCIM/") && path != null) {
                    try {
                        val f = File(path)
                        waitStable(f)
                        var adjusted = adjustFile(f, target)
                        // sin Ultra HDR (x3, x0.5, frontal): se le agrega con la curva de la stock
                        if (!adjusted && addOn(ctx) && rel.startsWith("DCIM/Camera")) adjusted = addToFile(f, target)
                        if (adjusted) changed++
                        // el nivel queda en el nombre (solo si la foto es Ultra HDR)
                        val newPath = if (adjusted || UltraHdrTuner.analyze(f.readBytes()) != null) renameWithLevel(ctx, id, f, target) else null
                        paths += newPath ?: path
                    } catch (_: Exception) {}
                }
                lastId = id
            }
        }
        prefs.edit().putLong("autoLastId", lastId).apply()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        if (changed > 0) prefs.edit().putInt("autoCount", prefs.getInt("autoCount", 0) + changed).apply()
        return changed
    }

    private val SUFFIX = Regex("_HDRx\\d+(\\.\\d+)?$")

    /** Nombre con el nivel al final: IMG_20261112_0941.jpg -> IMG_20261112_0941_HDRx2.5.jpg (reemplaza uno viejo). */
    fun nameWithLevel(name: String, boost: Double): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ".jpg"
        return SUFFIX.replace(base, "") + "_HDRx" + String.format(java.util.Locale.US, "%.1f", boost) + ext
    }

    /**
     * Renombra la foto para que lleve el nivel. Primero por MediaStore (mantiene la misma foto en la galería);
     * si no deja, renombra el archivo y avisa a la galería. Devuelve la ruta nueva o null si no cambió.
     */
    fun renameWithLevel(ctx: Context, id: Long, f: File, boost: Double): String? {
        val newName = nameWithLevel(f.name, boost)
        if (newName == f.name) return null
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        try {
            val cv = android.content.ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, newName) }
            if (ctx.contentResolver.update(uri, cv, null, null) > 0) return File(f.parentFile, newName).path
        } catch (_: Exception) {}
        val dst = File(f.parentFile, newName)
        if (dst.exists() || !f.renameTo(dst)) return null
        MediaScannerConnection.scanFile(ctx, arrayOf(f.path, dst.path), null, null)
        return null // el escaneo de arriba ya avisa de las dos rutas
    }

    /** Espera a que el archivo deje de crecer (la cámara puede seguir escribiendo). */
    fun waitStable(f: File) {
        var size = f.length()
        for (k in 0 until 10) {
            Thread.sleep(300)
            val s2 = f.length()
            if (s2 == size && s2 > 0) break
            size = s2
        }
    }

    /**
     * Arma el archivo con Ultra HDR para una foto que no lo tiene (la imagen principal no se recomprime).
     * Null si ya tiene, si es foto en movimiento o si no se pudo.
     */
    fun addGainMapBytes(src: ByteArray, target: Double): ByteArray? {
        if (UltraHdrTuner.analyze(src) != null) return null
        val opt = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 } // gain map a 1/4, como la stock
        val small = android.graphics.BitmapFactory.decodeByteArray(src, 0, src.size, opt) ?: return null
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val gm = GainMapAdder.gainMapFromSmall(px, w, h)
        for (i in px.indices) { val v = gm[i].toInt() and 255; px[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val gbmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val bos = java.io.ByteArrayOutputStream()
        gbmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos)
        gbmp.recycle()
        val stops = Math.log(target.coerceAtLeast(1.0)) / Math.log(2.0)
        return GainMapAdder.build(src, bos.toByteArray(), stops)
    }

    /** Agrega Ultra HDR al archivo (cambia de tamaño: se reescribe entero). */
    fun addToFile(f: File, target: Double): Boolean {
        val src = f.readBytes()
        val out = addGainMapBytes(src, target) ?: return false
        java.io.FileOutputStream(f).use { it.write(out); it.fd.sync() }
        return true
    }

    /** Reescribe en el lugar solo los bytes que cambian. */
    fun adjustFile(f: File, target: Double): Boolean {
        val src = f.readBytes()
        val info = UltraHdrTuner.analyze(src) ?: return false
        if (kotlin.math.abs(info.maxBoost - target) < 0.02) return false
        val out = UltraHdrTuner.setMaxBoost(src, target) ?: return false
        if (out.size != src.size) return false
        RandomAccessFile(f, "rw").use { raf ->
            var i = 0
            while (i < src.size) {
                if (src[i] != out[i]) {
                    var j = i
                    while (j < src.size && src[j] != out[j]) j++
                    raf.seek(i.toLong()); raf.write(out, i, j - i)
                    i = j
                } else i++
            }
        }
        return true
    }
}

class AutoHdrJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { AutoHdr.processNew(applicationContext) } catch (_: Throwable) {}
            // el disparador de contenido sirve una sola vez: se vuelve a programar
            if (AutoHdr.isOn(applicationContext)) AutoHdr.schedule(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/** Después de reiniciar, vuelve a prender el aviso de fotos nuevas. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AutoHdr.ensure(ctx)
    }
}
