package com.rdelbene.nivelhdr

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Efectos opcionales del gain map (solo cambian la capa de brillo HDR, nunca la imagen):
 *  - HDR a color: un gain map por canal (R, G, B). En las zonas que brillan, el color dominante recibe un poco más
 *    de ganancia que los otros, así atardeceres, luces cálidas y neones brillan con su color en vez de ir al blanco.
 *  - HDR por zonas: más brillo en el cielo, menos en las caras y, con ISO alto, menos en sombras y medios (grano).
 *  - Contraste HDR: las sombras bajan un poco en HDR (gain map con mínimo negativo), proporcional al nivel.
 *
 * El gain map se guarda normalizado: v = (L/max + k) / (1 + k), con L en pasos y k = CONTRAST_K si hay contraste.
 * Así, cambiar después solo el máximo (UltraHdrTuner.setMaxBoost, que escala también el mínimo) mantiene la forma.
 */
object HdrFx {
    class Opts(val color: Boolean, val zones: Boolean, val contrast: Boolean) {
        val any get() = color || zones || contrast
        /** Marca que se guarda en el XMP (nivelhdr:Fx) para saber con qué efectos se armó el gain map. */
        val tag get() = listOfNotNull(if (color) "color" else null, if (zones) "zonas" else null, if (contrast) "contraste" else null).joinToString("+")
    }

    /** Contraste: el mínimo del gain map es −15 % del máximo (a ×4.93, las sombras bajan ~0.35 pasos). */
    const val CONTRAST_K = 0.15
    /** Cuánto se satura el brillo extra en las zonas que más brillan (0 = nada). */
    private const val SAT = 0.6
    /** Escala de referencia (máximo de la stock, en pasos) para pasar las diferencias de color a valores normalizados. */
    private const val M0 = 2.3

    private val LIN = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /**
     * Gain map ARGB del mismo tamaño que [small] (la foto a 1/4, como sale del archivo, sin rotar).
     * [orientation]: orientación EXIF (para buscar cielo arriba y caras derechas). [iso]: ISO del EXIF (0 si no hay).
     */
    fun gainBitmap(small: Bitmap, lightsOnly: Boolean, o: Opts, orientation: Int, iso: Int): Bitmap {
        val deg = when (orientation) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val up = if (deg != 0f) Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(deg) }, true) else small
        val w = up.width; val h = up.height; val n = w * h
        val px = IntArray(n); up.getPixels(px, 0, w, 0, 0, w, h)

        val luma = IntArray(n) { val p = px[it]; ((77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8).coerceIn(0, 255) }
        val lut = FloatArray(256) { (if (lightsOnly) GainMapAdder.lightsGainCurve(it.toFloat()) else GainMapAdder.stockGainCurve(it.toFloat())) / 255f }
        // f: ganancia normalizada (0 = nada, 1 = máximo), en pasos de luz
        val f = FloatArray(n) { lut[luma[it]] }

        if (o.zones) {
            sky(px, luma, w, h)?.let { m -> for (i in 0 until n) f[i] = min(1f, f[i] + 0.25f * m[i] * (1f - 0.5f * f[i])) }
            faces(up, w, h)?.let { m -> for (i in 0 until n) f[i] *= 1f - 0.5f * m[i] }
            if (iso > 1600) {
                val s = ((ln(iso / 1600.0) / ln(4.0)).coerceIn(0.0, 1.0) * 0.4).toFloat()
                for (i in 0 until n) f[i] *= 1f - s * smooth(170f, 100f, luma[i].toFloat())
            }
        }

        val k = if (o.contrast) CONTRAST_K.toFloat() else 0f
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            var l = f[i]
            if (o.contrast) l -= k * smooth(110f, 30f, luma[i].toFloat()) * (1f - f[i])
            if (o.color && l > 0f) {
                val p = px[i]
                val cr = LIN[(p shr 16) and 255]; val cg = LIN[(p shr 8) and 255]; val cb = LIN[p and 255]
                val y = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
                val s = 1f + SAT * l.coerceIn(0f, 1f)
                fun ch(c: Float): Float {
                    val den: Float = if (c > 1e-3f) c else 1e-3f
                    val ratio: Double = ((y + s * (c - y)) / den).toDouble().coerceIn(0.5, 2.0)
                    return l + (ln(ratio) / ln(2.0) / M0).toFloat()
                }
                r[i] = ch(cr); g[i] = ch(cg); b[i] = ch(cb)
            } else { r[i] = l; g[i] = l; b[i] = l }
        }
        // normalizar a 0..1 con el mínimo negativo y suavizar 3x3 como la stock
        fun norm(a: FloatArray): FloatArray {
            val v = FloatArray(n) { ((a[it] + k) / (1f + k)).coerceIn(0f, 1f) }
            fun at(x: Int, yy: Int) = v[yy.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
            return FloatArray(n) { i ->
                val x = i % w; val yy = i / w
                (4f * at(x, yy) + 2f * (at(x - 1, yy) + at(x + 1, yy) + at(x, yy - 1) + at(x, yy + 1)) +
                    at(x - 1, yy - 1) + at(x + 1, yy - 1) + at(x - 1, yy + 1) + at(x + 1, yy + 1)) / 16f
            }
        }
        val nr = norm(r); val ng = if (o.color) norm(g) else nr; val nb = if (o.color) norm(b) else nr
        val out = IntArray(n) { i ->
            fun q(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
            (0xff shl 24) or (q(nr[i]) shl 16) or (q(ng[i]) shl 8) or q(nb[i])
        }
        val gm = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (up !== small) up.recycle()
        if (deg == 0f) return gm
        val back = Bitmap.createBitmap(gm, 0, 0, w, h, Matrix().apply { postRotate(-deg) }, true)
        gm.recycle()
        return back
    }

    /**
     * Cielo: zonas claras y lisas, celestes o blancas, conectadas con el borde de arriba (foto ya derecha).
     * Devuelve una máscara suave 0..1, o null si casi no hay cielo.
     */
    private fun sky(px: IntArray, l: IntArray, w: Int, h: Int): FloatArray? {
        val n = w * h
        val cand = BooleanArray(n)
        val limit = (h * 0.7f).toInt()
        for (y in 1 until limit) for (x in 1 until w - 1) {
            val i = y * w + x
            if (l[i] < 110) continue
            val grad = kotlin.math.abs(l[i + 1] - l[i - 1]) + kotlin.math.abs(l[i + w] - l[i - w])
            if (grad > 18) continue
            val p = px[i]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val sat = max(r, max(g, b)) - min(r, min(g, b))
            val blue = b > r + 8 && b >= g - 12
            val white = l[i] > 185 && sat < 30
            cand[i] = blue || white
        }
        // inundar desde arriba
        val m = BooleanArray(n)
        val q = IntArray(n); var qh = 0; var qt = 0
        for (x in 0 until w) { val i = w + x; if (cand[i]) { m[i] = true; q[qt++] = i } }
        while (qh < qt) {
            val i = q[qh++]; val x = i % w; val y = i / w
            fun push(j: Int) { if (!m[j] && cand[j]) { m[j] = true; q[qt++] = j } }
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        if (qt < n / 50) return null
        val mf = FloatArray(n) { if (m[it]) 1f else 0f }
        return blur(blur(mf, w, h, 3), w, h, 3)
    }

    /** Caras con el detector de Android (sin internet ni IA externa). Máscara suave 0..1, o null si no hay. */
    private fun faces(up: Bitmap, w: Int, h: Int): FloatArray? {
        val ew = w and 1.inv()
        if (ew < 32 || h < 32) return null
        val bmp = up.copy(Bitmap.Config.RGB_565, false).let { if (it.width != ew) Bitmap.createScaledBitmap(it, ew, h, true).also { s -> if (s !== it) it.recycle() } else it }
        val found = arrayOfNulls<android.media.FaceDetector.Face>(8)
        val count = try { android.media.FaceDetector(ew, h, found.size).findFaces(bmp, found) } catch (_: Exception) { 0 }
        bmp.recycle()
        if (count <= 0) return null
        val m = FloatArray(w * h)
        val mid = android.graphics.PointF()
        for (k in 0 until count) {
            val fc = found[k] ?: continue
            if (fc.confidence() < 0.4f) continue
            fc.getMidPoint(mid)
            val d = fc.eyesDistance()
            val cx = mid.x; val cy = mid.y + 0.4f * d; val rad = 1.7f * d
            val x0 = max(0, (cx - rad).toInt()); val x1 = min(w - 1, (cx + rad).toInt())
            val y0 = max(0, (cy - rad).toInt()); val y1 = min(h - 1, (cy + rad).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dd = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / rad
                val v = smooth(1f, 0.6f, dd)
                val i = y * w + x
                if (v > m[i]) m[i] = v
            }
        }
        return m
    }

    private fun blur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(a.size); val o = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (dx in -r..r) { val xx = x + dx; if (xx in 0 until w) { s += a[y * w + xx]; c++ } }
            t[y * w + x] = s / c
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (dy in -r..r) { val yy = y + dy; if (yy in 0 until h) { s += t[yy * w + x]; c++ } }
            o[y * w + x] = s / c
        }
        return o
    }
}
