package com.irvingguerra.litedash.feature.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

private const val CHANNEL = "player"
private const val NOTIFICATION_ID = 7
private const val ACTION_TOGGLE = "toggle"
private const val ACTION_NEXT = "next"
private const val ACTION_PREVIOUS = "previous"
private const val ACTION_STOP = "stop"

/**
 * Mantiene al [Player] en primer plano mientras suena, con el aviso de medios de Android
 * (carátula, título y botones). No reproduce nada él mismo: el reproductor es [Player];
 * este servicio existe para que Android no lo mate con otra app encima y para el aviso.
 * En pausa deja el primer plano (el aviso se queda, se puede quitar) y Android puede pararlo.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** Está en primer plano ahora. */
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        running = null
        // El aviso que quedó suelto en pausa se va con el servicio.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Player.toggle()
            ACTION_NEXT -> Player.next()
            ACTION_PREVIOUS -> Player.previous()
            ACTION_STOP -> Player.stop()
        }
        // Arrancado con startForegroundService: Android exige startForeground aunque ya no haya
        // nada que tocar (si no, cierra la app); en ese caso se quita enseguida.
        runCatching {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        }
        val s = Player.state
        when {
            s.current == null -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            !s.playing && !s.buffering -> paused()
        }
        return START_NOT_STICKY
    }

    /** En pausa: sale del primer plano pero deja el aviso (con play) al día. */
    private fun paused() {
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        foreground = false
        post()
    }

    /** Vuelve a pintar el aviso con lo que suena. */
    private fun post() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si cierran LiteDash desde recientes, la música se queda (como cualquier reproductor).
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.player_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val s = Player.state
        val item = s.current
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        builder
            .setSmallIcon(R.drawable.ic_player_notification)
            .setContentTitle(item?.title ?: getString(R.string.player_name))
            .setContentText(item?.subtitle ?: "")
            .setOngoing(s.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setDeleteIntent(action(ACTION_STOP))
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.player_previous), action(ACTION_PREVIOUS))
            .addAction(
                if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (s.playing) R.string.player_pause else R.string.player_play),
                action(ACTION_TOGGLE),
            )
            .addAction(android.R.drawable.ic_media_next, getString(R.string.player_next), action(ACTION_NEXT))
        Player.sessionToken?.let { token ->
            builder.setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let { open ->
            builder.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun action(name: String): PendingIntent =
        PendingIntent.getService(this, name.hashCode(), Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        // El servicio vivo (lo pone y quita él mismo); solo se usa en el hilo principal.
        @SuppressLint("StaticFieldLeak")
        private var running: PlaybackService? = null

        /**
         * Cambió lo que suena o si está en pausa. Sonando o cargando: primer plano con su aviso
         * (si ya lo está, solo se repinta el aviso). En pausa: deja el primer plano. Sin nada: se va.
         */
        fun update(context: Context) {
            val s = Player.state
            val service = running
            when {
                s.current == null -> stop(context)
                s.playing || s.buffering -> if (service?.foreground == true) service.post() else runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
                }
                else -> service?.paused()
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
