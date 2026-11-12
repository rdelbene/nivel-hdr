package com.rdelbene.nivelhdr

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * "Nivel HDR": ajusta la intensidad del Ultra HDR de fotos ya sacadas con la cámara stock,
 * sin tocar la imagen (solo los números del gain map, ver UltraHdrTuner).
 */
class HdrTunerActivity : Activity() {

    private val STOCK_BOOST = 4.93
    private val REQ_PERM = 10
    private val REQ_WRITE = 11

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<Long, Bitmap>(240)

    private val items = mutableListOf<Uri>()
    private val selected = LinkedHashSet<Uri>()
    private var sharedMode = false
    private var target = 2.5
    private var pendingReplace: List<Uri> = emptyList()

    private lateinit var levelLabel: TextView
    private lateinit var autoBtn: TextView
    private lateinit var summary: TextView
    private lateinit var filterBtn: TextView
    private lateinit var levelBar: SeekBar
    private var onlyPending = false
    private val allItems = mutableListOf<Uri>()
    /** Datos de cada foto para la grilla: nombre y nivel actual (null = sin Ultra HDR). */
    private class Meta(val name: String, val level: Double?, val pending: Boolean)
    private val metas = java.util.concurrent.ConcurrentHashMap<Uri, Meta>()
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private lateinit var btnReplace: TextView
    private lateinit var btnCopy: TextView
    private val adapter = ThumbAdapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        target = LEVELS[levelIndex(prefs.getFloat("tuneBoost", 2.5f).toDouble())]
        buildUi()

        // ¿Vino desde "Compartir" en la galería?
        val shared = sharedUris(intent)
        if (shared.isNotEmpty()) {
            sharedMode = true
            items += shared
            selected += shared
            btnReplace.visibility = View.GONE
            filterBtn.visibility = View.GONE
            status.text = "${shared.size} foto(s) compartida(s). Las fotos compartidas se guardan como copia."
            adapter.notifyDataSetChanged()
            updateButtons()
        } else if (hasReadPermission()) {
            loadRecent()
        } else {
            requestPermissions(readPermissions(), REQ_PERM)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaz

    private fun pillButton(text: String, fill: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(fill) }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(36), dp(12), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Nivel HDR"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Elegí cuánta luz extra agrega el Ultra HDR. La imagen no se toca y se puede volver a cambiar. " +
                "También se puede cambiar desde el panel de notificaciones (botón \"Nivel HDR\")."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(8))
        })
        levelLabel = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        root.addView(levelLabel)
        // 100..493 -> ×1.00..×4.93
        // niveles rápidos (los mismos del panel de notificaciones)
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(2)) }
        for ((v, name) in NivelTile.PRESETS) {
            val b = TextView(this).apply {
                text = "$name\n×%.1f".format(v); gravity = Gravity.CENTER; textSize = 12f
                setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(255, 50, 50, 50)) }
                setOnClickListener { setLevel(levelIndex(v), true) }
            }
            presetBtns += b to v
            presets.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        root.addView(presets)

        // barra con escalones de 0.1 y botones − / + (mantener apretado para avanzar seguido)
        levelBar = SeekBar(this).apply {
            max = LEVELS.size - 1
            progress = levelIndex(target)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { target = LEVELS[p]; updateLevel() } }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { saveLevel() }
            })
        }
        root.addView(stepperRow(levelBar, { setLevel(levelBar.progress - 1, false) }, { setLevel(levelBar.progress + 1, false) }, ::saveLevel))
        updateLevel()

        // automático: el nivel elegido se aplica solo a cada foto nueva de la cámara
        autoBtn = pillButton("", Color.argb(255, 50, 50, 50), Color.WHITE) { toggleAuto() }
        root.addView(autoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val btnSettings = pillButton("Ajustes", Color.argb(255, 50, 50, 50), Color.WHITE) { showSettings() }
        val btnRevert = pillButton("Volver todo atrás", Color.argb(255, 50, 50, 50), Color.WHITE) { confirmRevert() }
        tools.addView(btnSettings, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        tools.addView(btnRevert, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(tools)
        summary = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, 0) }
        root.addView(summary)
        updateAuto()

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0, 224, 150))
            setPadding(0, dp(6), 0, dp(6))
            text = "Tocá las fotos para elegirlas."
        }
        root.addView(status)

        filterBtn = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, dp(6))
            setOnClickListener { onlyPending = !onlyPending; applyFilter() }
        }
        root.addView(filterBtn)

        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            adapter = this@HdrTunerActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val u = items[pos]
                if (!selected.remove(u)) selected += u
                this@HdrTunerActivity.adapter.notifyDataSetChanged()
                updateButtons()
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        btnCopy = pillButton("Guardar copia", Color.argb(255, 50, 50, 50), Color.WHITE) { apply(false) }
        btnReplace = pillButton("Reemplazar", Color.rgb(0, 224, 150), Color.BLACK) { apply(true) }
        row.addView(btnCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(btnReplace, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(row)
        setContentView(root)
        updateButtons()
    }

    private fun toggleAuto() {
        if (AutoHdr.isOn(this) && AutoHdr.canWrite() &&
            !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) { askBattery(); return }
        if (AutoHdr.isOn(this)) { AutoHdr.setOn(this, false); updateAuto(); return }
        if (!AutoHdr.canWrite()) {
            status.text = "Activá \"Permitir acceso para administrar todos los archivos\" para Nivel HDR y volvé."
            wantAuto = true
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putFloat("tuneBoost", target.toFloat()).apply()
        // notificación fija del servicio y "sin restricciones" de batería, para que Android no lo cierre
        AutoHdr.setOn(this, true)
        // primero notificaciones y, cuando se contesta, batería (las dos ventanas juntas se pisan)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        else askBattery()
        updateAuto()
        status.text = "Listo: cada foto nueva de la cámara se ajusta sola a ×%.2f.".format(target)
    }

    private var wantAuto = false
    private var askedBattery = false

    override fun onResume() {
        super.onResume()
        if (wantAuto && AutoHdr.canWrite()) { wantAuto = false; toggleAuto() }
        // automático ya prendido (de una versión anterior) sin "sin restricciones": se pide una vez por apertura
        if (!askedBattery && AutoHdr.isOn(this) && AutoHdr.canWrite() &&
            !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            askedBattery = true; askBattery()
        }
        if (::autoBtn.isInitialized) updateAuto()
        AutoHdr.ensure(this)
        // el nivel pudo cambiar desde el panel de notificaciones
        val b = HdrEngine.baseLevel(this)
        if (::levelBar.isInitialized && kotlin.math.abs(b - target) > 0.005) {
            setLevel(levelIndex(b), false)
        }
    }

    /** Pide que Android no le aplique ahorro de batería (si no, puede demorar o cortar el automático). */
    private fun askBattery() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun updateAuto() {
        val on = AutoHdr.isOn(this) && AutoHdr.canWrite()
        val n = getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("autoCount", 0)
        val batOk = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        autoBtn.text = if (on && !batOk) "Automático: PRENDIDO pero con ahorro de batería · tocá para corregir"
            else if (on) "Automático: PRENDIDO · fotos nuevas a ×%.2f (%d ajustadas)".format(AutoHdr.level(this), n)
            else "Automático: apagado · tocá para aplicar este nivel a cada foto nueva"
        (autoBtn.background as GradientDrawable).setColor(if (on) Color.rgb(0, 224, 150) else Color.argb(255, 50, 50, 50))
        autoBtn.setTextColor(if (on) Color.BLACK else Color.WHITE)
        if (::summary.isInitialized) {
            summary.text = buildString {
                append("Estilo: " + (if (HdrEngine.style(this@HdrTunerActivity) == "luces") "solo las luces" else "como la stock"))
                append(" · x0.5/x3/frontal: " + (if (AutoHdr.addOn(this@HdrTunerActivity)) "se les agrega" else "no"))
                if (HdrEngine.perLens(this@HdrTunerActivity)) append(" · por lente: " + HdrEngine.Lens.values().joinToString(" ") {
                    it.label + " " + HdrEngine.lensPct(this@HdrTunerActivity, it) + "%" })
                if (HdrEngine.sceneOn(this@HdrTunerActivity)) append(" · según escena")
            }
        }
    }

    private fun updateLevel() {
        val stops = Math.log(target) / Math.log(2.0)
        levelLabel.text = "Nivel: ×%.1f  (%.1f pasos de luz)".format(target, stops) +
            when {
                target < 1.05 -> " · sin HDR"
                target >= STOCK_BOOST - 0.01 -> " · igual que la stock"
                else -> ""
            }
        for ((b, v) in presetBtns) {
            val on = kotlin.math.abs(v - target) < 0.02
            (b.background as GradientDrawable).setColor(if (on) Color.rgb(0, 224, 150) else Color.argb(255, 50, 50, 50))
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    private val presetBtns = mutableListOf<Pair<TextView, Double>>()

    private fun levelIndex(v: Double): Int = LEVELS.indices.minByOrNull { kotlin.math.abs(LEVELS[it] - v) } ?: 0

    /** Mueve el nivel a un escalón; [save] guarda enseguida (los botones − / + guardan al soltar). */
    private fun setLevel(idx: Int, save: Boolean) {
        val i = idx.coerceIn(0, LEVELS.size - 1)
        target = LEVELS[i]
        levelBar.progress = i
        updateLevel()
        if (save) saveLevel()
    }

    private fun saveLevel() {
        HdrEngine.setBaseLevel(this, target)
        if (::autoBtn.isInitialized) updateAuto()
        NivelTile.refresh(this)
        AutoHdrService.refresh(this)
    }

    /**
     * Fila [−] barra [+]. Un toque mueve un escalón; mantener apretado repite (cada vez más rápido).
     * [onRelease] se llama al soltar, para guardar una sola vez.
     */
    private fun stepperRow(bar: SeekBar, minus: () -> Unit, plus: () -> Unit, onRelease: () -> Unit): LinearLayout {
        fun btn(label: String, act: () -> Unit) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(255, 50, 50, 50)) }
            var delay = 350L
            val repeat = object : Runnable {
                override fun run() { act(); delay = maxOf(60L, delay - 40L); postDelayed(this, delay) }
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { act(); delay = 350L; v.postDelayed(repeat, 450L); v.isPressed = true }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(repeat); v.isPressed = false; onRelease()
                        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                true
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btn("−", minus), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("+", plus), LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun updateButtons() {
        val n = selected.size
        btnCopy.alpha = if (n > 0) 1f else 0.4f
        btnReplace.alpha = if (n > 0) 1f else 0.4f
        btnReplace.text = if (n > 0) "Reemplazar ($n)" else "Reemplazar"
        btnCopy.text = if (n > 0) "Guardar copia ($n)" else "Guardar copia"
    }

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val size = (grid.width - dp(8)) / 3
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@HdrTunerActivity).apply {
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "img" },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(TextView(context).apply {
                    tag = "chk"; text = "✓"; gravity = Gravity.CENTER; textSize = 16f
                    setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0, 224, 150)) }
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                addView(TextView(context).apply {
                    tag = "lvl"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            cell.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (size > 0) size else dp(110))
            val uri = items[position]
            val img = cell.findViewWithTag<ImageView>("img")
            val chk = cell.findViewWithTag<TextView>("chk")
            val lvl = cell.findViewWithTag<TextView>("lvl")
            val meta = metas[uri]
            if (meta == null) lvl.visibility = View.GONE else {
                lvl.visibility = View.VISIBLE
                lvl.text = when {
                    meta.level == null -> "sin HDR"
                    meta.pending -> "×%.1f · sin ajustar".format(meta.level)
                    else -> "×%.1f".format(meta.level)
                }
                lvl.setTextColor(if (meta.pending) Color.BLACK else Color.WHITE)
                lvl.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (meta.pending) Color.rgb(255, 170, 0) else Color.argb(170, 0, 0, 0))
                }
            }
            val sel = uri in selected
            chk.visibility = if (sel) View.VISIBLE else View.GONE
            img.alpha = if (sel) 0.7f else 1f
            val key = uri.hashCode().toLong()
            img.tag = "img"
            img.setImageBitmap(thumbs.get(key))
            if (thumbs.get(key) == null) {
                img.setTag(R_TAG_URI, uri)
                io.execute {
                    val bmp = try { contentResolver.loadThumbnail(uri, Size(300, 300), null) } catch (_: Exception) { null }
                    if (bmp != null) {
                        thumbs.put(key, bmp)
                        main.post { if (img.getTag(R_TAG_URI) == uri) img.setImageBitmap(bmp) }
                    }
                }
            }
            return cell
        }
    }

    // ------------------------------------------------------------------ fotos

    private fun readPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 34)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else arrayOf(Manifest.permission.READ_MEDIA_IMAGES)

    private fun hasReadPermission() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        // notificaciones del automático contestado: ahora sí, batería
        if (code == 7) { AutoHdrService.refresh(this); askBattery(); return }
        if (hasReadPermission()) loadRecent() else status.text = "Sin permiso para ver las fotos."
    }

    /** Últimas fotos JPEG de DCIM/Camera (las de la cámara stock y las nuestras). */
    private fun loadRecent() {
        io.execute {
            val list = mutableListOf<Uri>()
            val names = HashMap<Uri, String>()
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
                    arrayOf("DCIM/Camera%", "image/jpeg"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext() && list.size < 150) {
                        val u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        list += u; names[u] = c.getString(1) ?: ""
                    }
                }
            } catch (e: Exception) {
                main.post { status.text = "No pude leer la galería: ${e.message}" }
            }
            main.post {
                allItems.clear(); allItems += list
                applyFilter()
                status.text = if (list.isEmpty()) "No encontré fotos en DCIM/Camera." else "Tocá las fotos para elegirlas (${list.size} recientes)."
            }
            // nivel de cada foto (lectura parcial del archivo: encabezado + comienzo del gain map)
            for (u in list) {
                val lv = try { readLevel(u) } catch (_: Exception) { null }
                val name = names[u] ?: ""
                metas[u] = Meta(name, lv, !name.contains("_HDRx"))
            }
            main.post { applyFilter() }
        }
    }

    private fun sharedUris(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ aplicar

    private fun apply(replace: Boolean) {
        val list = selected.toList()
        if (list.isEmpty()) { status.text = "Primero elegí una o más fotos."; return }
        if (replace) {
            // Android pide confirmación para modificar fotos de otra app
            try {
                val pi = MediaStore.createWriteRequest(contentResolver, list)
                pendingReplace = list
                startIntentSenderForResult(pi.intentSender, REQ_WRITE, null, 0, 0, 0)
            } catch (e: Exception) {
                status.text = "No se pudo pedir permiso para reemplazar: ${e.message}"
            }
        } else {
            process(list, false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WRITE) {
            if (resultCode == RESULT_OK) process(pendingReplace, true)
            else status.text = "No se reemplazó nada (permiso cancelado)."
        }
    }

    private fun process(list: List<Uri>, replace: Boolean) {
        val t = target
        status.text = "Procesando ${list.size} foto(s)..."
        io.execute {
            var ok = 0; var noHdr = 0; var fail = 0; var added = 0
            var lastBoost = t
            var before = 0.0
            for (u in list) {
                try {
                    val src = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IllegalStateException("sin datos")
                    val info = UltraHdrTuner.analyze(src)
                    if (info != null) before = info.maxBoost
                    // mismo motor que el automático: lente, escena, estilo; a las que no tienen se les agrega
                    val r = HdrEngine.render(this, src, true, baseOverride = t) ?: run { noHdr++; null } ?: continue
                    if (r.kind == "agregada") added++
                    lastBoost = r.boost
                    val out = r.bytes
                    if (replace) {
                        contentResolver.openOutputStream(u, "wt")?.use { it.write(out) } ?: throw IllegalStateException("no se pudo escribir")
                        // el nivel también queda en el nombre de la foto
                        try {
                            val name = contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                            if (name != null) {
                                val nn = AutoHdr.nameWithLevel(name, lastBoost)
                                if (nn != name) contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, nn) }, null, null)
                            }
                        } catch (_: Exception) {}
                    } else {
                        saveCopy(u, out, lastBoost)
                    }
                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
            main.post {
                status.text = buildString {
                    append(if (replace) "Reemplazadas: $ok" else "Copias guardadas: $ok")
                    if (ok > 0 && before > 0) append(" (antes ×%.2f, ahora ×%.2f)".format(before, lastBoost))
                    if (added > 0) append(" · con Ultra HDR agregado: $added")
                    if (noHdr > 0) append(" · no se pudieron tocar: $noHdr")
                    if (fail > 0) append(" · con error: $fail")
                    if (replace && ok > 0) append("\nSi la galería muestra la versión vieja, cerrala y volvé a abrirla.")
                }
                if (!sharedMode) { selected.clear(); adapter.notifyDataSetChanged(); updateButtons() }
                if (ok > 0 && !sharedMode) loadRecent()
            }
        }
    }

    private fun saveCopy(src: Uri, bytes: ByteArray, t: Double) {
        var name = "IMG_HDR"
        var taken = System.currentTimeMillis()
        try {
            contentResolver.query(src, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it.substringBeforeLast('.') }
                    if (!c.isNull(1)) taken = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, AutoHdr.nameWithLevel("$name.jpg", t))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("no se pudo crear la copia")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    // ------------------------------------------------------------------ grilla: nivel y filtro

    private fun applyFilter() {
        items.clear()
        items += if (onlyPending) allItems.filter { metas[it]?.pending != false } else allItems
        val pend = allItems.count { metas[it]?.pending == true }
        filterBtn.text = if (onlyPending) "Mostrando: sin ajustar ($pend) · tocá para ver todas" else "Mostrando: todas · tocá para ver solo las sin ajustar ($pend)"
        adapter.notifyDataSetChanged()
    }

    /** Nivel actual leyendo solo los encabezados y el comienzo del gain map (rápido aunque la foto pese mucho). */
    private fun readLevel(u: Uri): Double? {
        contentResolver.openFileDescriptor(u, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                val read: (Long, Int) -> ByteArray = { p, n ->
                    val buf = java.nio.ByteBuffer.allocate(minOf(n.toLong(), (size - p).coerceAtLeast(0)).toInt())
                    while (buf.hasRemaining() && ch.read(buf, p + buf.position()) > 0) {}
                    buf.array().copyOf(buf.position())
                }
                val off = GainMapAdder.primaryLength(size, read) ?: return null
                return UltraHdrTuner.analyze(read(off.toLong(), 16384))?.maxBoost
            }
        }
        return null
    }

    // ------------------------------------------------------------------ ajustes

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun sw(text: String, on: Boolean, f: (Boolean) -> Unit) = android.widget.Switch(this).apply {
            this.text = text; isChecked = on; textSize = 15f; setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> f(v); updateAuto() }
        }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 0, 0, dp(6)) }

        box.addView(sw("Agregar Ultra HDR a x0.5, x3 y frontal", AutoHdr.addOn(this)) { AutoHdr.setAdd(this, it) })
        box.addView(note("Con la misma curva que usa Motorola en la x1. La foto no se recomprime."))

        box.addView(sw("Estilo: solo las luces", HdrEngine.style(this) == "luces") { HdrEngine.setStyle(this, if (it) "luces" else "stock") })
        box.addView(note("Apagado: como la stock (ilumina casi toda la foto). Prendido: la foto queda normal y brillan solo cielo, lámparas y reflejos."))

        box.addView(sw("Nivel según la escena", HdrEngine.sceneOn(this)) { HdrEngine.setScene(this, it) })
        box.addView(note("Noche: menos (×0.6 en pasos) · contraluz o muy clara: ×0.8 · escena apagada: ×1.2. El nombre muestra el nivel final."))

        val lensBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (l in HdrEngine.Lens.values()) {
            val lab = TextView(this).apply { textSize = 13f }
            fun upd(v: Int) { lab.text = "${l.label}: $v % del nivel" }
            upd(HdrEngine.lensPct(this, l))
            lensBox.addView(lab)
            val bar = SeekBar(this).apply {
                max = 30 // 0 a 150 % en escalones de 5 %
                progress = (HdrEngine.lensPct(this@HdrTunerActivity, l) + 2) / 5
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { upd(p * 5) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) { HdrEngine.setLensPct(this@HdrTunerActivity, l, s.progress * 5); updateAuto() }
                })
            }
            val save = { HdrEngine.setLensPct(this, l, bar.progress * 5); updateAuto() }
            lensBox.addView(stepperRow(bar, { bar.progress = (bar.progress - 1).coerceAtLeast(0) }, { bar.progress = (bar.progress + 1).coerceAtMost(30) }, save))
        }
        lensBox.visibility = if (HdrEngine.perLens(this)) View.VISIBLE else View.GONE
        box.addView(sw("Nivel distinto por lente", HdrEngine.perLens(this)) { HdrEngine.setPerLens(this, it); lensBox.visibility = if (it) View.VISIBLE else View.GONE })
        box.addView(note("Porcentaje del nivel elegido arriba (en pasos de luz). 100 % = igual; la frontal arranca en 60 %."))
        box.addView(lensBox)

        android.app.AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Listo", null)
            .show()
    }

    // ------------------------------------------------------------------ volver todo atrás

    private fun confirmRevert() {
        android.app.AlertDialog.Builder(this)
            .setTitle("¿Volver todo atrás?")
            .setMessage("Las fotos de la cámara que ajustó Nivel HDR (las que tienen _HDRx en el nombre) quedan como las guardó la cámara: " +
                "las de la x1 vuelven a ×4.93 y a las de x0.5, x3 y frontal se les saca el Ultra HDR. También se saca el nivel del nombre. " +
                "Las copias guardadas no se tocan. El automático sigue como está.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Volver atrás") { _, _ -> doRevert() }
            .show()
    }

    private fun doRevert() {
        if (!AutoHdr.canWrite()) {
            status.text = "Para esto hace falta \"Acceso a todos los archivos\". Activalo y volvé a tocar \"Volver todo atrás\"."
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        status.text = "Volviendo atrás..."
        io.execute {
            val n = AutoHdr.revertAll(this) { k, total -> main.post { status.text = "Volviendo atrás... $k de $total" } }
            main.post { status.text = "Listo: $n foto(s) quedaron como las guardó la cámara."; loadRecent() }
        }
    }

    companion object {
        /** Escalones del nivel: ×1.0 a ×4.8 de a 0.1, y ×4.93 (stock). */
        val LEVELS: List<Double> = (10..48).map { it / 10.0 } + 4.93

        /** Clave para guardar en la vista la foto que está cargando (cualquier id de recurso único sirve). */
        private const val R_TAG_URI = 0x7f0f0001
    }
}
