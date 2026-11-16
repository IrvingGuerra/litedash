package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Lo que suena, para el modo estacionado: carátula chica, título y artista (o la estación). Si no suena nada, no
 * dibuja nada. La música manda; la radio solo si es lo único que suena.
 */
@Composable
fun ParkedSong(repo: MusicRepository, modifier: Modifier = Modifier) {
    // Sin widget de Música en el inicio nadie escucha las sesiones: mientras está estacionado, esto las escucha.
    UseRepo(repo, SoundPiece.MUSIC)
    val music = repo.song?.takeIf { it.playing }
    val song = music ?: repo.radio.song?.takeIf { it.playing } ?: return
    val c = LocalColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(song, Modifier.size(52.dp), 10.dp, if (music == null) Icons.radio else Icons.music)
        Column(Modifier.widthIn(max = 460.dp)) {
            Label(song.title, Typography.subtitle, maxLines = 1)
            subtitleFor(repo, song).takeIf { it.isNotBlank() }?.let { Label(it, Typography.small, c.textMuted, maxLines = 1) }
        }
    }
}
