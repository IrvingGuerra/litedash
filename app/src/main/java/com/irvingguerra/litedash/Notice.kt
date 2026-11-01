package com.irvingguerra.litedash

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Aviso arriba del tablero (inicio, versión nueva, recordatorio): texto y sus
 * botones. En angosto (teléfono parado) el texto no cabe junto a los botones y va arriba.
 */
@Composable
fun Notice(text: String, icon: ImageVector? = null, buttons: @Composable () -> Unit) {
    val c = LocalColors.current
    val box = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(c.surface)
        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    val label = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Icon(icon, c.accent, 22.dp)
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
