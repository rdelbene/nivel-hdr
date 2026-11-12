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
    private const val JOB_PERIODIC = 4932
    private const val PREFS = "cfg"

    /** Agregar Ultra HDR a las fotos que llegan sin él (x3, x0.5, frontal). Prendido por defecto. */
    fun addOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoAdd", true)
    fun setAdd(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoAdd", on).apply()

    fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoOn", false)
    fun level(ctx: Context) = HdrEngine.baseLevel(ctx)
    fun canWrite() = Environment.isExternalStorageManager()

    /** Prende o apaga. Al prender, solo cuentan las fotos que se saquen desde ahora. */
    fun setOn(ctx: Context, on: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoOn", on)
        if (on) p.putLong("autoLastId", maxId(ctx))
        p.apply()
        if (on) { schedule(ctx); AutoHdrService.start(ctx) }
        else {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.cancel(JOB_ID); js.cancel(JOB_PERIODIC)
            AutoHdrService.stop(ctx)
        }
    }

    /** Se llama al abrir la app, al reiniciar y en cada revisión: si está prendido, levanta todo de nuevo. */
    fun ensure(ctx: Context) { if (isOn(ctx)) { schedule(ctx); AutoHdrService.start(ctx) } }

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoHdrJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(3000)
            .build()
        js.schedule(job)
        // respaldo: cada 15 minutos revisa si quedó alguna foto sin ajustar (y vuelve a levantar el servicio)
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, AutoHdrJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build())
        }
    }

    private fun maxId(ctx: Context): Long {
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, "${MediaStore.Images.Media._ID} DESC")?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }

    /** Ajusta las fotos nuevas de DCIM/Camera. Devuelve cuántas cambió. */
    @Synchronized
    fun processNew(ctx: Context): Int {
        if (!isOn(ctx) || !canWrite()) return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var lastId = prefs.getLong("autoLastId", Long.MAX_VALUE)
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
                        val src = f.readBytes()
                        val r = HdrEngine.render(ctx, src, addOn(ctx) && rel.startsWith("DCIM/Camera"))
                        var newPath: String? = null
                        if (r != null) {
                            if (r.kind != "igual") { writeBytes(f, src, r.bytes); changed++ }
                            newPath = renameWithLevel(ctx, id, f, r.boost) // el nivel queda en el nombre
                        }
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

    /** Escribe el resultado: si quedó del mismo tamaño, solo los bytes que cambian; si no, el archivo entero. */
    fun writeBytes(f: File, src: ByteArray, out: ByteArray) {
        if (out.size == src.size) {
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
        } else {
            java.io.FileOutputStream(f).use { it.write(out); it.fd.sync() }
        }
    }

    /** Volver todo atrás: deja como las guardó la cámara las fotos que tocó esta app. Devuelve cuántas. */
    fun revertAll(ctx: Context, progress: (Int, Int) -> Unit): Int {
        val list = mutableListOf<Pair<Long, String>>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.OWNER_PACKAGE_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%_HDRx%", "DCIM/%"), null)?.use { c ->
            // las copias que guardó esta app quedan como están (son copias, no originales)
            while (c.moveToNext()) if (c.getString(2) != ctx.packageName) c.getString(1)?.let { list += c.getLong(0) to it }
        }
        var done = 0
        val paths = mutableListOf<String>()
        for ((k, e) in list.withIndex()) {
            progress(k + 1, list.size)
            try {
                val f = File(e.second)
                val src = f.readBytes()
                HdrEngine.revert(src)?.let { writeBytes(f, src, it) }
                // sacar el sufijo del nombre
                val clean = SUFFIX.replace(f.nameWithoutExtension, "") + "." + f.extension
                var p = f.path
                if (clean != f.name) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, e.first)
                    val ok = try {
                        ctx.contentResolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, clean) }, null, null) > 0
                    } catch (_: Exception) { false }
                    if (ok) p = File(f.parentFile, clean).path
                    else { val dst = File(f.parentFile, clean); if (!dst.exists() && f.renameTo(dst)) { paths += f.path; p = dst.path } }
                }
                paths += p
                done++
            } catch (_: Exception) {}
        }
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        return done
    }
}

class AutoHdrJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { if (AutoHdr.processNew(applicationContext) > 0) AutoHdrService.refresh(applicationContext) } catch (_: Throwable) {}
            if (params.jobId == 4932) AutoHdr.ensure(applicationContext) // la revisión periódica levanta el servicio si se cayó
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
