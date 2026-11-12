package com.rdelbene.nivelhdr

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Botón "Nivel HDR" del panel de notificaciones (Ajustes rápidos): cada toque pasa al nivel siguiente
 * (Sin HDR ×1 → Suave ×1.5 → Medio ×2.5 → Alto ×3.5 → Stock ×4.9). Así se elige antes de sacar la foto.
 * Si el automático está apagado, abre la app.
 */
class NivelTile : TileService() {
    companion object {
        val PRESETS = listOf(1.0 to "Sin HDR", 1.5 to "Suave", 2.5 to "Medio", 3.5 to "Alto", 4.93 to "Stock")

        fun label(v: Double): String {
            val p = PRESETS.minByOrNull { kotlin.math.abs(it.first - v) }!!
            return if (kotlin.math.abs(p.first - v) < 0.05) "${p.second} ×%.1f".format(v) else "×%.1f".format(v)
        }

        /** Pide al sistema que actualice el botón (después de cambiar el nivel en la app). */
        fun refresh(ctx: Context) {
            try { requestListeningState(ctx, ComponentName(ctx, NivelTile::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() { update() }

    override fun onClick() {
        if (!AutoHdr.isOn(this) || !AutoHdr.canWrite()) {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, HdrTunerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
            return
        }
        val cur = HdrEngine.baseLevel(this)
        val idx = PRESETS.indexOfFirst { it.first > cur + 0.05 }
        val next = if (idx < 0) PRESETS[0].first else PRESETS[idx].first
        HdrEngine.setBaseLevel(this, next)
        AutoHdrService.refresh(this)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = AutoHdr.isOn(this) && AutoHdr.canWrite()
        val v = HdrEngine.baseLevel(this)
        t.label = "Nivel HDR"
        t.subtitle = if (on) label(v) else "Automático apagado"
        t.state = if (on && v > 1.05) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
