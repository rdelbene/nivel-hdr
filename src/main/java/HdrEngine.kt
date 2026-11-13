package com.rdelbene.nivelhdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.ln
import kotlin.math.pow

/*
 * Motor común del automático y del modo manual: decide el nivel de cada foto (lente, escena) y arma el archivo
 * (ajusta el gain map que ya trae, lo rehace con otro estilo, o se lo agrega a las que no tienen).
 */
object HdrEngine {
    const val STOCK_BOOST = 4.93
    val STOCK_STOPS = ln(STOCK_BOOST) / ln(2.0)
    private const val PREFS = "cfg"

    enum class Lens(val label: String, val key: String, val defPct: Int) {
        MAIN("x1", "pctMain", 100), UW("x0.5", "pctUw", 100), TELE("x3", "pctTele", 100), FRONT("frontal", "pctFront", 60)
    }

    // ------------------------------------------------------------------ ajustes

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun baseLevel(ctx: Context) = p(ctx).getFloat("tuneBoost", 2.5f).toDouble()
    fun setBaseLevel(ctx: Context, v: Double) = p(ctx).edit().putFloat("tuneBoost", v.toFloat()).apply()
    fun perLens(ctx: Context) = p(ctx).getBoolean("perLens", false)
    fun setPerLens(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("perLens", on).apply()
    fun lensPct(ctx: Context, l: Lens) = p(ctx).getInt(l.key, l.defPct)
    fun setLensPct(ctx: Context, l: Lens, v: Int) = p(ctx).edit().putInt(l.key, v).apply()
    fun zoomOn(ctx: Context) = p(ctx).getBoolean("zoomLevel", true)
    fun setZoomOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("zoomLevel", on).apply()
    fun sceneOn(ctx: Context) = p(ctx).getBoolean("scene", false)
    fun setScene(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("scene", on).apply()
    /** "stock" (ilumina casi toda la foto, como Motorola) o "luces" (solo cielo, lámparas, reflejos). */
    fun style(ctx: Context) = p(ctx).getString("style", "stock") ?: "stock"
    fun setStyle(ctx: Context, s: String) = p(ctx).edit().putString("style", s).apply()

    // ------------------------------------------------------------------ lente

    /**
     * Lente según el EXIF. Las fotos con Ultra HDR de la stock son siempre de la x1.
     * x0.5: equivalente 35 mm < 18; x3: > 50; frontal: focal real < 4 mm (sensor chico); el resto, x1.
     */
    fun detectLens(src: ByteArray, nativeUhdr: Boolean): Lens {
        if (nativeUhdr) return Lens.MAIN
        return try { lensOf(ExifInterface(ByteArrayInputStream(src))) } catch (_: Exception) { Lens.MAIN }
    }

    /** Lente según el EXIF (verificado con fotos reales: x0.5 = 12 mm eq / 1.83 mm, x1 = 23 / 6.57, x3 = 71 / 13.3). */
    fun lensOf(ex: ExifInterface): Lens {
        val eq = ex.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        val f = ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        return when {
            eq in 1..17 -> Lens.UW
            eq > 50 -> Lens.TELE
            f > 0.0 && f < 4.0 && eq in 18..40 -> Lens.FRONT
            f > 0.0 && f < 4.0 && eq == 0 -> Lens.FRONT
            else -> Lens.MAIN
        }
    }

    // ------------------------------------------------------------------ zoom

    /**
     * Zoom de la foto según el EXIF. Motorola guarda en DigitalZoomRatio el zoom TOTAL que muestra la cámara
     * (0.5 con la x0.5, 2.0 con la x1 a 2x, 25.4 con la x3 a ~25x). El lente sale del equivalente 35 mm ÷ 23 mm
     * (el de la x1), y el recorte real es zoom total ÷ lente.
     */
    class Zoom(val total: Double, val lens: Double, val crop: Double)

    fun zoomOf(ex: ExifInterface): Zoom? {
        val eq = ex.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        if (eq <= 0) return null
        val lensF = eq / 23.0
        val dz = ex.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, 0.0)
        val total = if (dz > 0.0) dz else lensF
        return Zoom(total, lensF, (total / lensF).coerceAtLeast(1.0))
    }

    /** Cuánto se baja el Ultra HDR (en pasos) según el recorte: hasta 2x nada, 2-5x 80 %, 5-15x 60 %, más 40 %. */
    fun zoomFactor(crop: Double) = when {
        crop <= 2.05 -> 1.0
        crop <= 5.0 -> 0.8
        crop <= 15.0 -> 0.6
        else -> 0.4
    }

    // ------------------------------------------------------------------ escena

    class Scene(val factor: Double, val why: String)

    private fun decodeSmall(src: ByteArray, sample: Int): Bitmap? =
        BitmapFactory.decodeByteArray(src, 0, src.size, BitmapFactory.Options().apply { inSampleSize = sample })

    /**
     * Nivel según la escena (multiplica los pasos de luz):
     *  noche (muy oscura) ×0.6 · contraluz / luces fuertes ×0.8 · escena muy clara ×0.8 · apagada, sin luces ×1.2.
     */
    fun scene(src: ByteArray): Scene {
        val bmp = decodeSmall(src, 16) ?: return Scene(1.0, "")
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
        val hist = IntArray(256)
        var sum = 0L
        for (c in px) {
            val y = ((77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8).coerceIn(0, 255)
            hist[y]++; sum += y
        }
        val n = px.size.coerceAtLeast(1)
        val mean = sum.toDouble() / n
        var acc = 0; var p5 = 0; var p95 = 255
        for (k in 0..255) { acc += hist[k]; if (acc < n * 0.05) p5 = k; if (acc < n * 0.95) p95 = k }
        var clip = 0; for (k in 250..255) clip += hist[k]
        val clipF = clip.toDouble() / n
        return when {
            mean < 50 -> Scene(0.6, "noche")
            clipF > 0.06 -> Scene(0.8, "luces fuertes")
            mean > 170 -> Scene(0.8, "muy clara")
            p95 - p5 < 120 && mean in 60.0..150.0 -> Scene(1.2, "apagada")
            else -> Scene(1.0, "normal")
        }
    }

    // ------------------------------------------------------------------ nivel

    class Plan(val boost: Double, val lens: Lens, val scene: String)

    fun levelFor(ctx: Context, src: ByteArray, nativeUhdr: Boolean, baseOverride: Double? = null): Plan {
        val base = baseOverride ?: baseLevel(ctx)
        var stops = ln(base.coerceAtLeast(1.0)) / ln(2.0)
        val ex = try { ExifInterface(ByteArrayInputStream(src)) } catch (_: Exception) { null }
        val lens = if (nativeUhdr || ex == null) Lens.MAIN else lensOf(ex)
        if (perLens(ctx)) stops *= lensPct(ctx, lens) / 100.0
        var why = ""
        if (zoomOn(ctx) && ex != null) {
            val z = zoomOf(ex)
            if (z != null && z.crop > 2.05) { stops *= zoomFactor(z.crop); why = "zoom %.0fx".format(z.total) }
        }
        if (sceneOn(ctx) && stops > 0.0) { val sc = scene(src); stops *= sc.factor; if (sc.why.isNotEmpty()) why = if (why.isEmpty()) sc.why else "$why, ${sc.why}" }
        stops = stops.coerceIn(0.0, STOCK_STOPS)
        return Plan(2.0.pow(stops), lens, why)
    }

    // ------------------------------------------------------------------ armado

    class Result(val bytes: ByteArray, val boost: Double, val lens: Lens, val scene: String, val kind: String)

    private fun gainJpeg(primary: ByteArray, lightsOnly: Boolean): ByteArray? {
        val small = decodeSmall(primary, 4) ?: return null // gain map a 1/4, como la stock
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h); small.recycle()
        val gm = GainMapAdder.gainMapFromSmall(px, w, h, lightsOnly)
        for (i in px.indices) { val v = gm[i].toInt() and 255; px[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val g = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        g.compress(Bitmap.CompressFormat.JPEG, 90, bos); g.recycle()
        return bos.toByteArray()
    }

    /**
     * Arma la foto con el nivel que corresponde. [allowAdd]: agregar Ultra HDR si no tiene.
     * Devuelve null si no hay nada que hacer (ya está igual o no se puede).
     */
    fun render(ctx: Context, src: ByteArray, allowAdd: Boolean, baseOverride: Double? = null, styleOverride: String? = null): Result? {
        val info = UltraHdrTuner.analyze(src)
        val marks = GainMapAdder.marks(src)
        val native = info != null && (marks == null || !marks.added)
        val plan = levelFor(ctx, src, native, baseOverride)
        val st = styleOverride ?: style(ctx)
        val curStyle = marks?.style ?: "stock"
        val stops = ln(plan.boost) / ln(2.0)
        // curva vieja de "solo las luces": se rehace con la nueva
        val oldCurve = marks != null && marks.style == "luces" && marks.curve < GainMapAdder.CURVE_VERSION
        if (info != null && st == curStyle && !oldCurve) {
            // mismo estilo: solo cambia la intensidad, en el lugar
            if (kotlin.math.abs(info.maxBoost - plan.boost) < 0.02) return Result(src, plan.boost, plan.lens, plan.scene, "igual")
            val out = UltraHdrTuner.setMaxBoost(src, plan.boost) ?: return null
            return Result(out, plan.boost, plan.lens, plan.scene, "ajustada")
        }
        val plain = if (info != null) GainMapAdder.strip(src) ?: return null else src
        if (info == null && !allowAdd) return null
        val g = gainJpeg(plain, st == "luces") ?: return null
        val out = GainMapAdder.build(plain, g, stops, added = !native, style = st) ?: return null
        return Result(out, plan.boost, plan.lens, plan.scene, if (info == null) "agregada" else "estilo $st")
    }

    /**
     * Volver a como la guardó la cámara: las que tenían Ultra HDR original vuelven a ×4.93 con la curva de la
     * stock; a las que se lo agregó esta app se les saca. Null si ya está así.
     */
    fun revert(src: ByteArray): ByteArray? {
        val info = UltraHdrTuner.analyze(src) ?: return null
        val marks = GainMapAdder.marks(src)
        if (marks == null) {
            if (kotlin.math.abs(info.maxBoost - STOCK_BOOST) < 0.02) return null
            return UltraHdrTuner.setMaxBoost(src, STOCK_BOOST)
        }
        val plain = GainMapAdder.strip(src) ?: return null
        if (marks.added) return plain
        // original de la stock al que se le cambió el estilo: se rehace con la curva de la stock (igual al 98 %)
        val g = gainJpeg(plain, false) ?: return null
        return GainMapAdder.build(plain, g, STOCK_STOPS, added = false, style = "stock")
    }
}
