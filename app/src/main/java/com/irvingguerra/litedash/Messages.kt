package com.irvingguerra.litedash

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.delay

data class Message(val key: String, val app: String, val sender: String, val text: String, val open: PendingIntent?, val time: Long)

// Apps de mensajes que se avisan en el inicio (WhatsApp, Telegram, SMS de Google y Samsung, Messenger, Signal).
private val MESSAGE_PACKAGES = setOf(
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
    "com.samsung.android.messaging", "com.facebook.orca", "org.thoughtcrime.securesms",
)

/** El último mensaje llegado, para la tarjeta de arriba. Usa el mismo acceso a notificaciones que la música. */
class MessageRepository(private val extraPackages: Set<String> = emptySet()) {
    var latest by mutableStateOf<Message?>(null)
        private set

    fun posted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MESSAGE_PACKAGES && sbn.packageName !in extraPackages) return
        val n = sbn.notification
        // Los resúmenes de grupo ("3 mensajes nuevos") y los fijos no son mensajes.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val sender = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        latest = Message(sbn.key, sbn.packageName, sender, text, n.contentIntent, System.currentTimeMillis())
    }

    fun removed(sbn: StatusBarNotification) {
        if (latest?.key == sbn.key) latest = null
    }

    fun dismiss() { latest = null }
}

/**
 * Tarjeta del mensaje nuevo arriba al centro, 10 segundos. En movimiento no se
 * ve el texto (solo quién escribe): leer manejando distrae.
 */
@Composable
fun MessageAlert(repo: MessageRepository, moving: Boolean, enabled: Boolean) {
    val m = repo.latest
    LaunchedEffect(m) {
        if (m != null) {
            delay(10_000)
            repo.dismiss()
        }
    }
    val c = LocalColors.current
    Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(enabled && m != null, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
            val msg = m ?: return@AnimatedVisibility
            Row(
                Modifier
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.surfaceHigh)
                    .clickable {
                        runCatching { msg.open?.send() }
                        repo.dismiss()
                    }
                    .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.message, c.accent, 24.dp)
                Column(Modifier.weight(1f)) {
                    Label(msg.sender, Typography.subtitle, maxLines = 1)
                    Label(if (moving || msg.text.isBlank()) stringResource(R.string.message_new) else msg.text, Typography.body, c.textMuted, maxLines = 2)
                }
                IconButton(Icons.close, { repo.dismiss() })
            }
        }
    }
}
