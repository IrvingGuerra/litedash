package com.irvingguerra.litedash.feature.music

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat

/** Sonando o cargando. */
internal fun MediaController.busy(): Boolean = when (playbackState?.state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
    else -> false
}

/**
 * Los extras de un aviso como mapa (un extra que no se puede leer deja el mapa vacío). Los objetos del AVRCP de
 * Android se pasan a valores simples para que [BroadcastMeta.parse] no dependa de Android: [MediaMetadata] → mapa de
 * sus textos ([BroadcastMeta.METADATA_KEYS]) y [PlaybackState] → su estado (Int).
 */
@Suppress("DEPRECATION") // Bundle.get: los avisos traen tipos variados
internal fun Bundle?.toMap(): Map<String, Any?> {
    val b = this ?: return emptyMap()
    return runCatching {
        b.keySet().associateWith {
            when (val v = b.get(it)) {
                is MediaMetadata -> BroadcastMeta.METADATA_KEYS.associateWith { k -> v.getString(k) }
                is PlaybackState -> v.state
                else -> v
            }
        }
    }.getOrDefault(emptyMap())
}

/** El primer texto no vacío de [keys] (algunas apps mandan el título vacío y el bueno en otro campo). */
internal fun MediaMetadata?.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { this?.getString(it)?.takeIf(String::isNotBlank) }.orEmpty()

/** La sesión que comparte un aviso de música; null si no trae. */
internal fun StatusBarNotification.sessionToken(): MediaSession.Token? =
    BundleCompat.getParcelable(notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)

/** Aviso de música: con su sesión o con el estilo multimedia ("android.template"; la constante es de la API 24). */
internal fun StatusBarNotification.isMedia(): Boolean {
    val extras = notification.extras
    return extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || extras.getString("android.template").orEmpty().endsWith("MediaStyle")
}
