package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.feature.music.MusicExpanded
import com.irvingguerra.litedash.feature.music.RadioExpanded
import com.irvingguerra.litedash.feature.player.PlayerBrowser
import com.irvingguerra.litedash.feature.player.PlayerSource

/** Qué se ve en la pantalla Música: lo que suena (con la biblioteca de Archivos) o la radio. */
enum class MediaTab { MUSIC, RADIO }

/**
 * La pantalla Música (como la de Tesla): se abre con su botón de la barra, el del volante o tocando el widget
 * Música o Radio (en su pestaña), y llena el tablero. Arriba Música | Radio y cerrar; abajo el reproductor en
 * grande de esa pestaña (en Música, ahí mismo se elige Archivos, Bluetooth o App). La biblioteca de Archivos y las estaciones por internet viven aquí (los
 * widgets ya no las traen). [onRadioSettings] lleva a enlazar la radio de la pantalla.
 */
@Composable
fun MediaScreen(
    d: Dependencies,
    tab: MediaTab,
    onTab: (MediaTab) -> Unit,
    close: () -> Unit,
    onRadioSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Abrir Música ya en la biblioteca de Archivos (se llegó por play sin nada que sonar). */
    browse: Boolean = false,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(
                listOf(MediaTab.MUSIC to stringResource(R.string.tile_music), MediaTab.RADIO to stringResource(R.string.tile_radio)),
                tab, onTab,
            )
            Spacer(Modifier.weight(1f))
            IconButton(Icons.close, close, size = 48.dp, background = LocalColors.current.surfaceHigh)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                MediaTab.MUSIC -> MusicExpanded(d.music, startBrowsing = browse, browser = { picked -> PlayerBrowser(PlayerSource.USB, picked, Modifier.fillMaxSize()) })
                MediaTab.RADIO -> RadioExpanded(
                    d.music, onSettings = onRadioSettings,
                    browser = if (d.music.radioInternet) { picked -> PlayerBrowser(PlayerSource.RADIO, picked, Modifier.fillMaxSize()) } else null,
                )
            }
        }
    }
}
