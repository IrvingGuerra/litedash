package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.core.ui.skinSurface
import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.Https

/**
 * Aviso arriba del tablero (inicio, versión nueva, recordatorio): texto y sus
 * botones. En angosto (teléfono parado) el texto no cabe junto a los botones y va arriba. El ícono va
 * de [iconColor] (p. ej. rojo o ámbar de advertencia) o, sin él, del color de la interfaz.
 */
@Composable
fun Notice(text: String, icon: ImageVector? = null, iconColor: Color? = null, buttons: @Composable () -> Unit) {
    val c = LocalColors.current
    val box = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(Radius.card))
        // Con la piel de las tarjetas, pero siempre bien visible: es un aviso.
        .skinSurface(LocalCardLook.current, c, RoundedCornerShape(Radius.card), floor = 0.8f)
        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    val label = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, iconColor ?: c.accent, 22.dp)
            Label(text, Typography.body, maxLines = 2)
        }
    }
    BoxWithConstraints {
        if (maxWidth < 560.dp) {
            Column(box, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                label(Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
            }
        } else {
            Row(box, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                label(Modifier.weight(1f))
                buttons()
            }
        }
    }
}

/**
 * El texto de una descarga que falló ([res], p. ej. «revisa tu internet») o, si la hora de la pantalla quedó atrás (sin
 * pila y sin red: 1970, 2015…), que la fecha está mal: ahí todo HTTPS falla y no es culpa del internet.
 */
@Composable
fun downloadError(@StringRes res: Int): String = stringResource(if (Https.clockLooksWrong()) R.string.clock_wrong else res)
