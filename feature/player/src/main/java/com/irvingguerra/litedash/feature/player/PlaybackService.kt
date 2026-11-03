package com.irvingguerra.litedash.feature.player

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
import androidx.core.content.ContextCompat

private const val CHANNEL = "player"
private const val NOTIFICATION_ID = 7
private const val ACTION_TOGGLE = "toggle"
private const val ACTION_NEXT = "next"
private const val ACTION_PREVIOUS = "previous"
private const val ACTION_STOP = "stop"
private const val ACTION_REFRESH = "refresh"

/**
 * Mantiene al [Player] en primer plano mientras suena, con el aviso de medios de Android
 * (carátula, título y botones). No reproduce nada él mismo: el reproductor es [Player];
 * este servicio existe para que Android no lo mate con otra app encima y para el aviso.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Player.toggle()
            ACTION_NEXT -> Player.next()
            ACTION_PREVIOUS -> Player.previous()
            ACTION_STOP -> { Player.stop(); return START_NOT_STICKY }
        }
        if (Player.state.current == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
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
        /** Al empezar a sonar: pasa a primer plano con su aviso. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) }
        }

        /** Cambió lo que suena o si está en pausa: el aviso se vuelve a armar. */
        fun refresh(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).setAction(ACTION_REFRESH)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
