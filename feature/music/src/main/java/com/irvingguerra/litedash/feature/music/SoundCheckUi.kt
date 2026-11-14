package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/** Lo que la calibración pide o avisa en el widget de [piece]; null si no le toca. */
internal fun MusicRepository.soundCheckFor(piece: SoundPiece): SoundCheck? = soundCheck?.takeIf { it.piece == piece }

/**
 * Un renglón en el widget, sin ventanas (D1): "¿Se escucha?" con Sí / No, o que no se encontró cómo hacerlo
 * sonar con "Enviar un reporte" (y quitar el aviso). Angosto, los botones van abajo del texto.
 */
@Composable
internal fun SoundCheckPrompt(repo: MusicRepository, check: SoundCheck, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val text = stringResource(if (check.failed) R.string.sound_failed else R.string.sound_question)
        val buttons = @Composable {
            if (check.failed) {
                Button(stringResource(R.string.sound_report), { repo.reportSound() }, primary = true)
                IconButton(Icons.close, { repo.dismissSoundCheck() }, size = 44.dp, background = LocalColors.current.surfaceHigh)
            } else {
                Button(stringResource(R.string.sound_yes), { repo.answerSound(true) }, primary = true)
                Button(stringResource(R.string.sound_no), { repo.answerSound(false) })
            }
        }
        if (maxWidth < STACKED_MAX_WIDTH) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(text, Typography.subtitle, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(text, Typography.subtitle, maxLines = 2, modifier = Modifier.weight(1f))
                buttons()
            }
        }
    }
}

/** Desde este ancho, el texto y los botones en un renglón; más angosto, los botones abajo. */
private val STACKED_MAX_WIDTH = 320.dp

/** La receta de [piece] en palabras (Ajustes → Apps → Música → Calibrar sonido); [id] null = aún sin calibrar. */
@Composable
fun recipeLabel(piece: SoundPiece, id: String?): String = stringResource(
    when (id) {
        null -> R.string.recipe_none
        Recipes.PLAIN -> R.string.recipe_plain
        Recipes.TW_SOURCE8 -> R.string.recipe_tw_source8
        Recipes.FYT_TOGGLE -> R.string.recipe_fyt_toggle
        Recipes.PROFILE -> R.string.recipe_profile
        Recipes.OPEN_FACTORY -> if (piece == SoundPiece.MUSIC) R.string.recipe_open_bluetooth else R.string.recipe_open_radio
        else -> R.string.recipe_failed
    },
)
