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
    /** Datos de cada foto para la grilla (se leen a medida que aparecen en pantalla). */
    private class Meta(val level: Double?, val lens: String?, val zoom: Double?, val crop: Double)
    private val names = java.util.concurrent.ConcurrentHashMap<Uri, String>()
    private val metaLoading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Uri, Boolean>())
    private val metaExec = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var refreshPosted = false
    private fun isPending(u: Uri) = !(names[u] ?: "").contains("_HDRx")
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
        // encabezado: título, ⓘ (explicaciones) y ⋮ (Ajustes, Volver todo atrás)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Nivel HDR"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun iconBtn(t: String, act: (View) -> Unit) = TextView(this).apply {
            text = t; gravity = Gravity.CENTER; textSize = 22f; setTextColor(Color.WHITE)
            setOnClickListener { act(it) }
        }
        header.addView(iconBtn("ⓘ") { showInfo() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(iconBtn("⋮") { showMenu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
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
        autoBtn.setPadding(dp(16), dp(7), dp(16), dp(7))
        root.addView(autoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        summary = TextView(this) // el resumen de ajustes ahora va en ⓘ
        updateAuto()

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0, 224, 150))
            setPadding(0, dp(6), 0, dp(6))
            text = ""
        }
        root.addView(status)

        filterBtn = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(255, 50, 50, 50)) }
            setOnClickListener { onlyPending = !onlyPending; applyFilter() }
        }
        root.addView(filterBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

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
        val batOk = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        // verde = prendido, gris = apagado; solo se avisa si el ahorro de batería lo puede frenar
        autoBtn.text = if (on && !batOk) "Automático · ⚠ ahorro de batería" else "Automático"
        (autoBtn.background as GradientDrawable).setColor(if (on) Color.rgb(0, 224, 150) else Color.argb(255, 50, 50, 50))
        autoBtn.setTextColor(if (on) Color.BLACK else Color.WHITE)
        if (::summary.isInitialized) {
            summary.text = buildString {
                append("Estilo: " + (if (HdrEngine.style(this@HdrTunerActivity) == "luces") "solo las luces" else "como la stock"))
                val off = HdrEngine.Lens.values().filter { !HdrEngine.lensOn(this@HdrTunerActivity, it) }
                if (off.isNotEmpty()) append(" · sin Ultra HDR: " + off.joinToString(" ") { it.label })
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
            if (meta == null) { lvl.visibility = View.GONE; loadMeta(uri) } else {
                val pending = isPending(uri)
                lvl.visibility = View.VISIBLE
                // lente · zoom (si hay recorte) · nivel
                val parts = mutableListOf<String>()
                meta.lens?.let { parts += it }
                meta.zoom?.let { z -> if (meta.crop > 1.05) parts += if (z >= 10) "zoom %.0f".format(z) else "zoom %.1f".format(z) }
                parts += if (meta.level == null) "sin HDR" else "HDR %.1f".format(meta.level)
                if (pending && meta.level != null) parts += "sin ajustar"
                lvl.text = parts.joinToString(" · ")
                lvl.setTextColor(if (pending) Color.BLACK else Color.WHITE)
                lvl.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (pending) Color.rgb(255, 170, 0) else Color.argb(170, 0, 0, 0))
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
            val nm = HashMap<Uri, String>()
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
                    arrayOf("DCIM/Camera%", "image/jpeg"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    // todas las fotos de la cámara (el nivel, el lente y el zoom se leen después, al verlas)
                    while (c.moveToNext()) {
                        val u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        list += u; nm[u] = c.getString(1) ?: ""
                    }
                }
            } catch (e: Exception) {
                main.post { status.text = "No pude leer la galería: ${e.message}" }
            }
            main.post {
                names.clear(); names.putAll(nm)
                metas.clear(); metaLoading.clear()
                allItems.clear(); allItems += list
                applyFilter()
                status.text = if (list.isEmpty()) "No encontré fotos en DCIM/Camera." else ""
            }
        }
    }

    /** Lee nivel, lente y zoom de una foto (en segundo plano) y refresca la grilla. */
    private fun loadMeta(u: Uri) {
        if (!metaLoading.add(u)) return
        metaExec.execute {
            val lv = try { readLevel(u) } catch (_: Exception) { null }
            var lens: String? = null; var zoom: Double? = null; var crop = 1.0
            try {
                contentResolver.openInputStream(u)?.use { st ->
                    val ex = android.media.ExifInterface(st)
                    lens = HdrEngine.lensOf(ex).label
                    HdrEngine.zoomOf(ex)?.let { zoom = it.total; crop = it.crop }
                }
            } catch (_: Exception) {}
            metas[u] = Meta(lv, lens, zoom, crop)
            main.post {
                if (!refreshPosted) { refreshPosted = true; main.postDelayed({ refreshPosted = false; adapter.notifyDataSetChanged() }, 250) }
            }
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
        items += if (onlyPending) allItems.filter { isPending(it) } else allItems
        val pend = allItems.count { isPending(it) }
        filterBtn.text = if (onlyPending) "Viendo: sin ajustar ($pend) ▾" else "Viendo: todas (${allItems.size}) ▾"
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

    // ------------------------------------------------------------------ menú e información

    private fun showMenu(anchor: View) {
        val pm = android.widget.PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Ajustes")
        pm.menu.add(0, 2, 1, "Volver todo atrás")
        pm.setOnMenuItemClickListener { when (it.itemId) { 1 -> showSettings(); 2 -> confirmRevert() }; true }
        pm.show()
    }

    private fun showInfo() {
        val c = this
        val txt = buildString {
            append("Elegí cuánta luz extra agrega el Ultra HDR. La imagen no se toca: solo cambia la intensidad, y se puede volver a cambiar.\n\n")
            append("• Nivel: ×1 es sin Ultra HDR y ×4.9 es lo que pone la cámara de Motorola. Usá los botones rápidos, la barra (de a 0.1) o − y + (mantener apretado para avanzar seguido).\n\n")
            append("• Automático: cada foto nueva de la cámara se ajusta sola a los pocos segundos. Queda corriendo con una notificación fija.\n\n")
            append("• Panel de notificaciones: el botón \"Nivel HDR\" cambia el nivel sin abrir la app.\n\n")
            append("• Nombre: cada foto ajustada termina en _HDRx2.5 (el nivel final que quedó).\n\n")
            append("• Grilla: lente · zoom · nivel, por ejemplo \"0.5x · zoom 1.8 · HDR 2.4\". El zoom aparece solo si la cámara recortó. En naranja, las que todavía no ajustó la app. Arriba de la grilla podés ver todas o solo las sin ajustar.\n\n")
            append("• Para ajustar fotos a mano: tocalas y usá \"Guardar copia\" o \"Reemplazar\".\n\n")
            append("• Menú ⋮: Ajustes y Volver todo atrás.\n\n")
            append("Ajustes actuales:\n")
            append("• Estilo: " + (if (HdrEngine.style(c) == "luces") "solo las luces" else "como la stock") + "\n")
            append("• Ultra HDR por lente: " + HdrEngine.Lens.values().joinToString(", ") { it.label + " " + (if (HdrEngine.lensOn(c, it)) "sí" else "no") } + "\n")
            append("• Nivel según el zoom: " + (if (HdrEngine.zoomOn(c)) HdrEngine.ZoomBand.values().joinToString(", ") { it.label.removePrefix("Recorte ") + " " + HdrEngine.zoomPct(c, it) + " %" } else "no") + "\n")
            append("• Nivel según la escena: " + (if (HdrEngine.sceneOn(c)) HdrEngine.SceneKind.values().joinToString(", ") { it.why + " " + HdrEngine.scenePct(c, it) + " %" } else "no") + "\n")
            append("• Nivel por lente: " + (if (HdrEngine.perLens(c)) HdrEngine.Lens.values().joinToString(", ") { it.label + " " + HdrEngine.lensPct(c, it) + " %" } else "no"))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Cómo funciona")
            .setView(android.widget.ScrollView(this).apply {
                addView(TextView(c).apply { text = txt; textSize = 14f; setPadding(dp(20), dp(8), dp(20), dp(8)) })
            })
            .setPositiveButton("Listo", null)
            .show()
    }

    // ------------------------------------------------------------------ ajustes

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun sw(text: String, on: Boolean, f: (Boolean) -> Unit) = android.widget.Switch(this).apply {
            this.text = text; isChecked = on; textSize = 15f; setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> f(v); updateAuto() }
        }
        fun note(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 0, 0, dp(6)) }

        // ---- Ultra HDR por lente
        box.addView(TextView(this).apply { text = "Ultra HDR por lente"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, dp(2)) })
        val lensOrder = listOf(HdrEngine.Lens.UW, HdrEngine.Lens.MAIN, HdrEngine.Lens.TELE, HdrEngine.Lens.FRONT)
        for (l in lensOrder) {
            val name = when (l) { HdrEngine.Lens.UW -> "x0.5"; HdrEngine.Lens.MAIN -> "x1"; HdrEngine.Lens.TELE -> "x3"; HdrEngine.Lens.FRONT -> "Frontal" }
            box.addView(sw(name, HdrEngine.lensOn(this, l)) { HdrEngine.setLensOn(this, l, it) })
        }
        box.addView(note("Para el automático. Apagado en la x1: la foto queda en ×1 (sin luz extra, se puede volver a subir). " +
            "Apagado en x0.5, x3 o frontal: no se les agrega. Prendido en esas: se agrega con la misma curva de la x1, sin recomprimir."))

        box.addView(sw("Estilo: solo las luces", HdrEngine.style(this) == "luces") { HdrEngine.setStyle(this, if (it) "luces" else "stock") })
        box.addView(note("Apagado: como la stock (ilumina casi toda la foto). Prendido: la foto queda normal y brillan solo cielo, lámparas y reflejos."))

        // fila de porcentaje: etiqueta + − barra + (0 a 200 % de a 5 %)
        fun pctRow(target: LinearLayout, label: String, get: () -> Int, set: (Int) -> Unit) {
            val lab = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, 0) }
            fun upd(v: Int) { lab.text = "$label: $v % del nivel" }
            upd(get())
            target.addView(lab)
            val bar = SeekBar(this).apply {
                max = 40
                progress = (get() + 2) / 5
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { upd(p * 5) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) { set(s.progress * 5); updateAuto() }
                })
            }
            val save = { set(bar.progress * 5); updateAuto() }
            target.addView(stepperRow(bar, { bar.progress = (bar.progress - 1).coerceAtLeast(0) }, { bar.progress = (bar.progress + 1).coerceAtMost(40) }, save))
        }
        fun group(title: String, on: Boolean, setOn: (Boolean) -> Unit, help: String, fill: (LinearLayout) -> Unit) {
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (on) View.VISIBLE else View.GONE }
            fill(inner)
            box.addView(sw(title, on) { setOn(it); inner.visibility = if (it) View.VISIBLE else View.GONE })
            box.addView(note(help))
            box.addView(inner)
        }

        group("Nivel según el zoom", HdrEngine.zoomOn(this), { HdrEngine.setZoomOn(this, it) },
            "Con zoom digital (recorte) se usa este porcentaje del nivel, en pasos de luz. Ejemplo: x3 a 25x = recorte 8x → tramo 5x a 15x.") { g ->
            for (b in HdrEngine.ZoomBand.values()) pctRow(g, b.label, { HdrEngine.zoomPct(this, b) }, { HdrEngine.setZoomPct(this, b, it) })
        }

        group("Nivel según la escena", HdrEngine.sceneOn(this), { HdrEngine.setScene(this, it) },
            "Mira el brillo de la foto y usa este porcentaje del nivel. El nombre muestra el nivel final.") { g ->
            for (k in HdrEngine.SceneKind.values()) pctRow(g, k.label, { HdrEngine.scenePct(this, k) }, { HdrEngine.setScenePct(this, k, it) })
        }

        group("Nivel distinto por lente", HdrEngine.perLens(this), { HdrEngine.setPerLens(this, it) },
            "Porcentaje del nivel elegido arriba (en pasos de luz). 100 % = igual; la frontal arranca en 60 %.") { g ->
            for (l in lensOrder) pctRow(g, l.label, { HdrEngine.lensPct(this, l) }, { HdrEngine.setLensPct(this, l, it) })
        }

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
