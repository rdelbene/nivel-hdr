package com.rdelbene.nivelhdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * "Ajustar toda la galería": recorre todas las fotos de DCIM y les aplica el nivel calculado con los ajustes
 * actuales. Corre en primer plano con una notificación de avance (sigue aunque cierres la app) y al terminar
 * avisa con otra notificación.
 */
class BatchService : Service() {
    companion object {
        private const val CH_RUN = "lote"
        private const val CH_DONE = "lote_fin"
        private const val ID_RUN = 3
        private const val ID_DONE = 4
        private const val ACTION_STOP = "stop"

        @Volatile var running = false
            private set
        @Volatile private var cancel = false
        /** La pantalla principal se anota para mostrar el avance y el resultado. */
        @Volatile var listener: ((String, Boolean) -> Unit)? = null

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, BatchService::class.java)) }

        private fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_RUN) == null)
                nm.createNotificationChannel(NotificationChannel(CH_RUN, "Ajuste de toda la galería", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel(CH_DONE) == null)
                nm.createNotificationChannel(NotificationChannel(CH_DONE, "Ajuste de la galería terminado", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private val exec = Executors.newSingleThreadExecutor()

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, HdrTunerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun progressNotif(k: Int, total: Int): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, BatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Ajustando toda la galería")
            .setContentText(if (total == 0) "Buscando fotos…" else "$k de $total fotos")
            .setProgress(total, k, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        if (intent?.action == ACTION_STOP) { cancel = true; return START_NOT_STICKY }
        startForeground(ID_RUN, progressNotif(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (running) return START_NOT_STICKY
        running = true; cancel = false
        val nm = getSystemService(NotificationManager::class.java)
        exec.execute {
            var last = 0L
            val st = try {
                AutoHdr.processAll(applicationContext, { cancel }) { k, total, _ ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 700 || k == total) {
                        last = now
                        nm.notify(ID_RUN, progressNotif(k, total))
                        listener?.invoke("Ajustando toda la galería… $k de $total", false)
                    }
                }
            } catch (e: Throwable) { null }
            val text = when {
                st == null -> "No se pudo terminar el ajuste de la galería."
                else -> {
                    val parts = mutableListOf("${st.changed} ajustada" + (if (st.changed == 1) "" else "s"))
                    if (st.same > 0) parts += "${st.same} ya estaban bien"
                    if (st.skipped > 0) parts += "${st.skipped} sin cambios posibles"
                    if (st.errors > 0) parts += "${st.errors} con error"
                    (if (cancel) "Ajuste detenido: " else "Listo, toda la galería revisada: ") + parts.joinToString(", ") + "."
                }
            }
            nm.notify(ID_DONE, Notification.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(if (cancel) "Ajuste de la galería detenido" else "Galería ajustada")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp()).setAutoCancel(true)
                .build())
            running = false
            listener?.invoke(text, true)
            AutoHdrService.refresh(applicationContext)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { cancel = true; exec.shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
