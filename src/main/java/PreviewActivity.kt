package com.rdelbene.nivelhdr

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Vista previa: la foto en pantalla completa con el Ultra HDR real (ventana en modo HDR), una barra para
 * probar el nivel en vivo, "Mantené para ver como está ahora" para comparar, y el detalle de por qué queda
 * ese nivel (lente, zoom, escena). Recién al tocar Guardar copia o Reemplazar se cambia algo.
 */
class PreviewActivity : Activity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_REPLACE = "replace"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var uri: Uri
    private var src: ByteArray? = null
    private var original: Bitmap? = null
    private var preview: Bitmap? = null
    private var level = 2.5
    private var gen = 0

    private lateinit var img: ImageView
    private lateinit var levelLabel: TextView
    private lateinit var detail: TextView
    private lateinit var state: TextView
    private lateinit var bar: SeekBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // la ventana en modo HDR: así se ve el brillo extra de verdad
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        uri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java) ?: run { finish(); return }
        level = intent.getDoubleExtra(EXTRA_LEVEL, 2.5)
        buildUi()
        io.execute {
            try {
                val b = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException()
                src = b
                val bmp = decode(b)
                main.post { original = bmp; if (preview == null) img.setImageBitmap(bmp) }
                render()
            } catch (e: Exception) {
                main.post { state.text = "No pude abrir la foto." }
            }
        }
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun decode(b: ByteArray): Bitmap? = try {
        // ImageDecoder respeta la orientación y trae el gain map (Ultra HDR) con la imagen
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(b))) { dec, info, _ ->
            val w = info.size.width
            dec.setTargetSampleSize(if (w > 3000) 2 else 1)
        }
    } catch (_: Exception) { null }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // mantener apretada la foto = ver cómo está ahora el archivo
        img.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { original?.let { img.setImageBitmap(it) }; state.text = "Así está ahora"; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { (preview ?: original)?.let { img.setImageBitmap(it) }; state.text = stateText(); true }
                else -> true
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(8)); setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        top.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); setPadding(dp(4), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        state = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); text = "Cargando…" }
        top.addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(this).apply {
            text = "ⓘ Detalle"; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18)); setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        detail = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); visibility = View.GONE; setPadding(0, 0, 0, dp(8))
        }
        bottom.addView(ScrollView(this).apply { addView(detail) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        levelLabel = TextView(this).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        bottom.addView(levelLabel)
        bar = SeekBar(this).apply {
            max = 39 // ×1.0 a ×4.9 de a 0.1
            progress = ((level - 1.0) * 10).roundToInt().coerceIn(0, 39)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { level = 1.0 + p / 10.0; showLevel() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { render() }
            })
        }
        bottom.addView(bar)
        bottom.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.LTGRAY)
            text = "Mantené apretada la foto para ver cómo está ahora. El nivel final tiene en cuenta lente, zoom y escena, igual que el automático."
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun pill(t: String, fill: Int, fg: Int, act: () -> Unit) = TextView(this).apply {
            text = t; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(fg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(fill) }
            setOnClickListener { act() }
        }
        row.addView(pill("Guardar copia", Color.argb(255, 50, 50, 50), Color.WHITE) { done(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(pill("Reemplazar", Color.rgb(0, 224, 150), Color.BLACK) { done(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        bottom.addView(row)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        showLevel()
    }

    private fun showLevel() { levelLabel.text = "Probar con nivel ×%.1f".format(level) }

    private var lastFinal: Double? = null
    private var lastKind = ""
    private fun stateText() = when {
        lastFinal == null -> "Preparando la vista previa…"
        lastKind == "igual" -> "Vista previa ×%.2f (igual que ahora)".format(lastFinal)
        else -> "Vista previa ×%.2f · todavía no se guardó".format(lastFinal)
    }

    /** Arma la foto con el nivel elegido (en memoria) y la muestra. */
    private fun render() {
        val b = src ?: return
        val my = ++gen
        val lv = level
        main.post { state.text = "Preparando ×%.1f…".format(lv) }
        io.execute {
            val r = try { HdrEngine.render(this, b, true, baseOverride = lv) } catch (_: Exception) { null }
            if (my != gen) return@execute
            val bmp = if (r != null && r.kind != "igual") decode(r.bytes) else null
            main.post {
                if (my != gen) return@post
                if (r == null) {
                    preview = null; lastFinal = null
                    state.text = "Esta foto no se puede ajustar (por ejemplo, foto en movimiento)."
                    detail.text = ""
                    return@post
                }
                preview = bmp ?: original
                img.setImageBitmap(preview)
                lastFinal = r.boost; lastKind = r.kind
                state.text = stateText()
                val now = UltraHdrTuner.analyze(b)?.maxBoost
                detail.text = (listOf("Ahora: " + (now?.let { "×%.2f".format(it) } ?: "sin Ultra HDR")) + r.steps)
                    .joinToString("\n") { "• $it" }
            }
        }
    }

    private fun done(replace: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URI, uri).putExtra(EXTRA_LEVEL, level).putExtra(EXTRA_REPLACE, replace))
        finish()
    }
}
