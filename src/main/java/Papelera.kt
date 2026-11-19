package com.rdelbene.nivelhdr

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/*
 * Papelera: antes de que la app modifique una foto original (automático, "Ajustar toda la galería" o
 * "Reemplazar"), guarda lo necesario para dejarla exactamente como estaba, byte a byte.
 *
 * No es una copia de la foto: se guarda solo lo que cambia (ver Delta): ~20-80 bytes al cambiar el nivel,
 * casi nada al agregar Ultra HDR, ~300 KB al cambiar de estilo. Queda en la carpeta privada de la app
 * (no aparece en la galería) y se borra sola a los N días del último cambio.
 *
 * Si la foto se vuelve a modificar, se sigue guardando la versión de la cámara (la primera), no la intermedia.
 * Antes de restaurar se comprueba (SHA-256) que la foto no haya cambiado por otro lado; si cambió, no se toca.
 */
object Papelera {
    private const val PREFS = "cfg"
    private const val MAGIC = 0x4E485031 // "NHP1"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Opciones de días (0 = apagada). */
    val DAY_OPTIONS = intArrayOf(0, 7, 30, 90)

    fun days(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("trashDays", 30)
    fun setDays(ctx: Context, d: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("trashDays", d).apply()

    private fun dir(ctx: Context) = File(ctx.filesDir, "papelera").apply { mkdirs() }
    private fun fileOf(ctx: Context, key: String) = File(dir(ctx), "$key.bin")

    /** Clave de una foto de la galería: su id de MediaStore. */
    fun keyOf(id: Long) = "m$id"

    /** Clave de un uri: el id si es de la galería; si no, un resumen del uri. */
    fun keyOf(uri: Uri): String {
        if (uri.authority == MediaStore.AUTHORITY) {
            try { val id = ContentUris.parseId(uri); if (id >= 0) return keyOf(id) } catch (_: Exception) {}
        }
        return "u" + hex(Delta.sha256(uri.toString().toByteArray())).take(32)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    class Entry(
        val key: String, val created: Long, val updated: Long, val name: String, val uri: String,
        val origLen: Int, val origSha: ByteArray, val curLen: Int, val curSha: ByteArray,
        val ops: List<Delta.Op>?, val fileSize: Long
    )

    private fun read(f: File, withOps: Boolean): Entry? {
        if (!f.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { inp ->
                if (inp.readInt() != MAGIC) return null
                val created = inp.readLong(); val updated = inp.readLong()
                val name = inp.readUTF(); val uri = inp.readUTF()
                val origLen = inp.readInt(); val origSha = ByteArray(32).also { inp.readFully(it) }
                val curLen = inp.readInt(); val curSha = ByteArray(32).also { inp.readFully(it) }
                val ops = if (withOps) Delta.read(inp) else null
                Entry(f.name.removeSuffix(".bin"), created, updated, name, uri, origLen, origSha, curLen, curSha, ops, f.length())
            }
        } catch (_: Exception) { null }
    }

    private fun write(ctx: Context, e: Entry) {
        val f = fileOf(ctx, e.key)
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(BufferedOutputStream(fos))
            out.writeInt(MAGIC)
            out.writeLong(e.created); out.writeLong(e.updated)
            out.writeUTF(e.name); out.writeUTF(e.uri)
            out.writeInt(e.origLen); out.write(e.origSha)
            out.writeInt(e.curLen); out.write(e.curSha)
            Delta.write(out, e.ops ?: emptyList())
            out.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** El original de la foto, si lo que hay en la papelera corresponde a esta versión ([cur]). */
    private fun originalFrom(e: Entry, cur: ByteArray): ByteArray? {
        if (e.ops == null || e.curLen != cur.size || !Delta.sha256(cur).contentEquals(e.curSha)) return null
        val o = Delta.apply(e.ops, cur) ?: return null
        if (o.size != e.origLen || !Delta.sha256(o).contentEquals(e.origSha)) return null
        return o
    }

    /**
     * Se llama justo antes de escribir [after] sobre la foto, que hoy tiene [before]. Nunca frena el ajuste:
     * si algo falla, la foto se ajusta igual (solo que sin poder restaurarla desde acá).
     */
    @Synchronized
    fun record(ctx: Context, key: String, before: ByteArray, after: ByteArray, name: String, uri: String) {
        if (days(ctx) <= 0) return
        try {
            val now = System.currentTimeMillis()
            val old = read(fileOf(ctx, key), true)
            var orig = before
            var origName = name
            var created = now
            if (old != null) {
                val o = originalFrom(old, before)
                if (o != null) { orig = o; origName = old.name; created = old.created }
            }
            if (orig.contentEquals(after)) { fileOf(ctx, key).delete(); return } // volvió a estar como la cámara
            val ops = Delta.diff(orig, after)
            write(ctx, Entry(key, created, now, origName, uri, orig.size, Delta.sha256(orig), after.size, Delta.sha256(after), ops, 0))
        } catch (_: Throwable) {}
    }

    /** Original de una foto de la galería según su versión actual, con el nombre que tenía. Null si no hay o no coincide. */
    fun original(ctx: Context, key: String, cur: ByteArray): Pair<ByteArray, String>? {
        val e = read(fileOf(ctx, key), true) ?: return null
        val o = originalFrom(e, cur) ?: return null
        return o to e.name
    }

    fun has(ctx: Context, key: String) = fileOf(ctx, key).exists()

    fun remove(ctx: Context, key: String) { fileOf(ctx, key).delete() }

    /** Lo guardado, del cambio más nuevo al más viejo (sin cargar las diferencias). */
    fun list(ctx: Context): List<Entry> =
        (dir(ctx).listFiles()?.toList() ?: emptyList())
            .filter { it.name.endsWith(".bin") }
            .mapNotNull { read(it, false) }
            .sortedByDescending { it.updated }

    fun totalBytes(ctx: Context): Long = dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    /** Borra lo que venció (y los restos de escrituras cortadas). */
    fun cleanup(ctx: Context) {
        val d = days(ctx)
        if (d <= 0) return
        val limit = System.currentTimeMillis() - d * DAY_MS
        dir(ctx).listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".tmp") -> if (f.lastModified() < System.currentTimeMillis() - DAY_MS) f.delete()
                f.name.endsWith(".bin") -> { val e = read(f, false); if (e == null || e.updated < limit) f.delete() }
            }
        }
    }

    /** Uri de la foto en la galería (para la miniatura), o null si no es de la galería. */
    fun mediaUri(e: Entry): Uri? =
        if (e.key.startsWith("m")) e.key.drop(1).toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        else null

    /**
     * Deja la foto exactamente como estaba y le devuelve su nombre. Devuelve null si salió bien, o el motivo
     * si no se pudo (y en ese caso no se toca nada).
     */
    fun restore(ctx: Context, e: Entry): String? {
        val mu = mediaUri(e)
        if (mu != null) {
            if (!AutoHdr.canWrite()) return "falta el permiso \"Acceso a todos los archivos\""
            var path: String? = null
            var curName: String? = null
            ctx.contentResolver.query(mu, arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { path = c.getString(0); curName = c.getString(1) }
            }
            val p = path ?: run { remove(ctx, e.key); return "la foto ya no está en la galería" }
            val f = File(p)
            val cur = f.readBytes()
            val full = read(fileOf(ctx, e.key), true) ?: return "no se pudo leer lo guardado"
            val orig = originalFrom(full, cur) ?: return "la foto cambió después (otra app o un editor): no se toca"
            AutoHdr.writeBytes(f, cur, orig)
            val paths = mutableListOf(f.path)
            if (curName != null && curName != e.name) {
                val ok = try {
                    ctx.contentResolver.update(mu, ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, e.name) }, null, null) > 0
                } catch (_: Exception) { false }
                if (ok) paths += File(f.parentFile, e.name).path
                else { val dst = File(f.parentFile, e.name); if (!dst.exists() && f.renameTo(dst)) paths += dst.path }
            }
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
            remove(ctx, e.key)
            return null
        }
        // foto que llegó desde otra app (compartida): se usa el uri, si todavía hay permiso
        return try {
            val u = Uri.parse(e.uri)
            val cur = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return "no se pudo abrir la foto"
            val full = read(fileOf(ctx, e.key), true) ?: return "no se pudo leer lo guardado"
            val orig = originalFrom(full, cur) ?: return "la foto cambió después (otra app o un editor): no se toca"
            ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(orig) } ?: return "no se pudo escribir"
            remove(ctx, e.key)
            null
        } catch (ex: Exception) { "sin permiso para escribir esa foto (${ex.message})" }
    }
}
