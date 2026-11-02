package com.irvingguerra.litedash.feature.apps

import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import java.text.Normalizer

private val ICON_SIZE = 56.dp

/** Lista de apps cargada una vez fuera del hilo principal. */
@Composable
fun rememberApps(repo: AppRepository): List<App>? {
    val apps by produceState<List<App>?>(null, repo) { value = repo.load() }
    return apps
}

// Acentos y demás marcas: compilada una sola vez, no en cada llamada.
private val MARKS = Regex("\\p{Mn}+")

/** Sin acentos ni mayúsculas: "musica" encuentra "Música". */
fun normalize(text: String) =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGrid(
    repo: AppRepository,
    apps: List<App>,
    query: String,
    onClick: (App) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((App) -> Unit)? = null,
    marked: Set<String> = emptySet(),
) {
    val filter = remember(query) { normalize(query.trim()) }
    // Los nombres se normalizan una vez por lista, no en cada tecla.
    val names = remember(apps) { apps.map { normalize(it.name) } }
    val list = remember(apps, names, filter) { if (filter.isEmpty()) apps else apps.filterIndexed { i, _ -> filter in names[i] } }
    val c = LocalColors.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.key }) { app ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(Radius.card))
                    .background(if (app.key in marked) c.surfaceHigh else c.background.copy(alpha = 0f))
                    .combinedClickable(onClick = { onClick(app) }, onLongClick = onLongPress?.let { { it(app) } })
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(repo, app, ICON_SIZE)
                Spacer(Modifier.height(6.dp))
                Label(app.name, Typography.small.copy(textAlign = TextAlign.Center), c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
fun AppIcon(repo: AppRepository, app: App, side: Dp, modifier: Modifier = Modifier) {
    val sidePx = with(LocalDensity.current) { side.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app, sidePx) {
        value = repo.icon(app, sidePx)?.asImageBitmap()
    }
    Box(modifier.size(side)) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, contentDescription = app.name, Modifier.size(side))
        } else {
            Box(Modifier.size(side).clip(RoundedCornerShape(side / 4)).background(LocalColors.current.surfaceHigh))
        }
    }
}
