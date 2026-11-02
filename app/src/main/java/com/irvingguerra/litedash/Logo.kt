package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MorseMark
import com.irvingguerra.litedash.core.ui.Typography
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El logo de tu marca, que eliges de tu galería: LiteDash no trae logos de marcas (son
 * suyos). Se guarda una copia reducida en la app; sin imagen se muestra el nombre.
 */
private const val LOGO_FILE = "logo.png"
private const val LOGO_MAX_PX = 1024

private fun logoFile(context: Context) = File(context.filesDir, LOGO_FILE)

/** Copia la imagen elegida, reducida a [LOGO_MAX_PX] (un logo no necesita más). */
private suspend fun saveLogo(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LOGO_MAX_PX) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching false
        logoFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)
}

/** La imagen del logo; [loaded] = ya se sabe si hay o no (para no mostrar el nombre de paso). */
private class LoadedLogo(val image: ImageBitmap?, val loaded: Boolean)

@Composable
private fun rememberLogo(revision: Int): LoadedLogo {
    val context = LocalContext.current
    val logo by produceState(LoadedLogo(null, false), revision) {
        value = LoadedLogo(
            withContext(Dispatchers.IO) {
                logoFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            },
            loaded = true,
        )
    }
    return logo
}

/** El logo (o el nombre de la marca) centrado en su pieza; un toque hace [onClick]. */
@Composable
fun BrandLogo(d: Dependencies, brand: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val loaded = rememberLogo(d.prefs.logoRevision)
    val logo = loaded.image
    BoxWithConstraints(
        modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight) * 0.7f
        if (logo != null) {
            Image(logo, null, Modifier.size(side))
        } else if (loaded.loaded) {
            Label(
                brand.uppercase(),
                Typography.clock.copy(fontSize = (side.value * 0.22f).coerceIn(28f, 110f).sp, letterSpacing = 6.sp, textAlign = TextAlign.Center),
                maxLines = 2,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * Animación de entrada al abrir LiteDash: el logo aparece desde el fondo, crece un
 * poco y se desvanece hacia el inicio. Un toque la salta.
 */
@Composable
fun LogoIntro(d: Dependencies, brand: String, onDone: () -> Unit) {
    val c = LocalColors.current
    // El fondo tapa el inicio desde el primer cuadro; el logo aparece encima y al final
    // todo se desvanece hacia el inicio.
    val curtain = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) {
        // Empieza cuando la app ya se dibuja: mientras Android muestra su pantalla de
        // arranque (y el inicio carga el auto) la animación se iba sin que se viera.
        repeat(3) { withFrameNanos { } }
        delay(250)
        launch { scale.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        alpha.animateTo(1f, tween(600))
        delay(1100)
        curtain.animateTo(0f, tween(500))
        onDone()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = curtain.value }
            .background(c.background)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        val logo = rememberLogo(d.prefs.logoRevision)
        Box(
            Modifier.fillMaxSize(0.55f).graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
            contentAlignment = Alignment.Center,
        ) {
            // Con tu imagen, tu logo; sin ella, el de LiteDash (no el nombre de la marca).
            when {
                logo.image != null -> BrandLogo(d, brand, onClick = onDone)
                logo.loaded -> MorseMark(Modifier.fillMaxWidth(0.6f).aspectRatio(184f / 36f))
            }
        }
    }
}

/** Ajustes del logo: auto o logo en el inicio, la imagen y la animación de entrada. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogoSettings(d: Dependencies, brand: String) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = d.prefs
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (saveLogo(context, uri)) {
                prefs.logoRevision++
                prefs.showLogo = true
            }
        }
    }
    val hasImage = rememberLogo(prefs.logoRevision).image != null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.show_car), { prefs.showLogo = false }, primary = !prefs.showLogo)
        Button(stringResource(R.string.show_logo), { prefs.showLogo = true }, primary = prefs.showLogo)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(if (hasImage) R.string.logo_change else R.string.logo_pick), {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        if (hasImage) Button(stringResource(R.string.logo_remove), {
            logoFile(context).delete()
            prefs.logoRevision++
        })
        Button(stringResource(R.string.logo_intro), { prefs.logoIntro = !prefs.logoIntro }, primary = prefs.logoIntro)
    }
    if (!hasImage) Label(stringResource(R.string.logo_no_image, brand.uppercase()), Typography.small, c.textMuted)
}
