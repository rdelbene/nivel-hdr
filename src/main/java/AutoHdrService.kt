package com.rdelbene.nivelhdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import java.util.concurrent.Executors

/**
 * Servicio permanente del automático (con notificación fija, como exige Android): vigila la galería todo el
 * tiempo y ajusta cada foto nueva apenas se guarda. Android no lo cierra por ahorro de batería como a una app
 * común; si igual lo cierra, se vuelve a levantar solo (START_STICKY), y además quedan de respaldo el aviso de
 * JobScheduler y una revisión cada 15 minutos que ajusta lo que haya quedado pendiente.
 */
class AutoHdrService : Service() {
    companion object {
        private const val CHANNEL = "auto"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, AutoHdrService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AutoHdrService::class.java)) }

        /** Actualiza el texto de la notificación (nivel, cantidad de fotos ajustadas). */
        fun refresh(ctx: Context) {
            if (!AutoHdr.isOn(ctx)) return
            try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(ctx)) } catch (_: Exception) {}
        }

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Automático", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Aviso fijo mientras el automático está prendido"
                    setShowBadge(false)
                })
            }
            val n = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("autoCount", 0)
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, HdrTunerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Nivel HDR automático · " + NivelTile.label(HdrEngine.baseLevel(ctx)))
                .setContentText("Las fotos nuevas se ajustan solas · $n ajustadas")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val exec = Executors.newSingleThreadExecutor()
    private var observer: ContentObserver? = null
    private val run = Runnable {
        exec.execute {
            try { if (AutoHdr.processNew(applicationContext) > 0) refresh(applicationContext) } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("nivelhdr-obs").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!AutoHdr.isOn(this)) { stopSelf(); return START_NOT_STICKY }
        if (observer == null) {
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // se espera un momento: la cámara avisa varias veces mientras guarda
                    handler.removeCallbacks(run)
                    handler.postDelayed(run, 700)
                }
            }
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!)
        }
        handler.post(run) // revisa si quedó algo pendiente mientras no corría
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        thread.quitSafely()
        exec.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
