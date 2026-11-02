package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Segmented
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.irvingguerra.litedash.core.ui.Radius
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
import androidx.core.graphics.scale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Una imagen que eliges de tu galería: el logo de tu marca (LiteDash no trae logos
 * de marcas, son suyos) o el fondo de la pieza del auto. Se guarda una copia
 * reducida en la app y se decodifica una sola vez por revisión (la usan la pieza,
 * la intro y Ajustes).
 */
private class SavedImage(val file: String, val maxPx: Int, val png: Boolean) {
    var revision = -1
    var image: ImageBitmap? = null

    fun file(context: Context) = File(context.filesDir, file)
}

/** El logo: con transparencia, y no necesita más de 1024 px. */
private val LOGO = SavedImage("logo.png", 1024, png = true)

/** El fondo: una foto, que llena la pieza (y la pantalla en grande). */
private val WALLPAPER = SavedImage("fondo.jpg", 1920, png = false)

/** Copia la imagen elegida, reducida a [SavedImage.maxPx]. */
private suspend fun save(context: Context, uri: Uri, target: SavedImage): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target.maxPx) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching false
        // El muestreo es en potencias de 2: se termina de ajustar a maxPx exactos.
        val k = target.maxPx.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (k < 1f) decoded.scale((decoded.width * k).toInt(), (decoded.height * k).toInt()) else decoded
        // Primero a un temporal: si algo falla a la mitad, la imagen de antes queda intacta.
        val temp = File(context.filesDir, "${target.file}.tmp")
        temp.outputStream().use {
            if (target.png) bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        temp.renameTo(target.file(context))
        target.revision = -1
        true
    }.getOrDefault(false)
}

/** La imagen; [loaded] = ya se sabe si hay o no (para no mostrar el nombre de paso). */
private class LoadedImage(val image: ImageBitmap?, val loaded: Boolean)

@Composable
private fun rememberImage(target: SavedImage, revision: Int): LoadedImage {
    val context = LocalContext.current
    val cached = target.revision == revision
    val loaded by produceState(if (cached) LoadedImage(target.image, true) else LoadedImage(null, false), target, revision) {
        if (target.revision != revision) {
            target.image = withContext(Dispatchers.IO) {
                target.file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
            target.revision = revision
        }
        value = LoadedImage(target.image, loaded = true)
    }
    return loaded
}

/** El logo (o el nombre de la marca) centrado en su pieza; un toque hace [onClick]. */
@Composable
fun BrandLogo(d: Dependencies, brand: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val loaded = rememberImage(LOGO, d.prefs.logoRevision)
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
    val done by rememberUpdatedState(onDone)
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
        done()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = curtain.value }
            .background(c.background)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        val logo = rememberImage(LOGO, d.prefs.logoRevision)
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

/**
 * La imagen de fondo a todo lo que mide la pieza, con sus esquinas; un toque hace
 * [onClick]. Sin imagen todavía, dice dónde elegirla.
 */
@Composable
fun WallpaperView(d: Dependencies, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val loaded = rememberImage(WALLPAPER, d.prefs.wallpaperRevision)
    val image = loaded.image
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(Radius.card))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (loaded.loaded) {
            Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
                Label(stringResource(R.string.wallpaper_missing), Typography.body, c.textMuted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/**
 * Ajustes de la pieza del auto: qué muestra (auto 3D, logo, imagen o carretera),
 * las imágenes que necesita y la animación de entrada.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarViewSettings(d: Dependencies, brand: String) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = d.prefs
    fun picker(target: SavedImage, onSaved: () -> Unit) = { uri: Uri? ->
        if (uri != null) scope.launch { if (save(context, uri, target)) onSaved() }
        Unit
    }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picker(LOGO) {
        prefs.logoRevision++
        prefs.carView = CarView.LOGO
    })
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picker(WALLPAPER) {
        prefs.wallpaperRevision++
        prefs.carView = CarView.WALLPAPER
    })
    val views = listOf(
        CarView.CAR to stringResource(R.string.show_car),
        CarView.LOGO to stringResource(R.string.show_logo),
        CarView.WALLPAPER to stringResource(R.string.show_wallpaper),
        CarView.ROAD to stringResource(R.string.show_road),
    )
    Segmented(views, prefs.carView, { prefs.carView = it })
    val view = prefs.carView
    // Los botones de la imagen que usa esta vista (el logo o el fondo).
    val target = when (view) {
        CarView.LOGO -> LOGO
        CarView.WALLPAPER -> WALLPAPER
        else -> null
    }
    if (target != null) {
        val revision = if (target === LOGO) prefs.logoRevision else prefs.wallpaperRevision
        val hasImage = rememberImage(target, revision).image != null
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(if (hasImage) R.string.logo_change else R.string.logo_pick), {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (target === LOGO) pickLogo.launch(request) else pickWallpaper.launch(request)
            })
            if (hasImage) Button(stringResource(R.string.logo_remove), {
                target.file(context).delete()
                if (target === LOGO) prefs.logoRevision++ else prefs.wallpaperRevision++
            })
        }
        if (!hasImage && target === LOGO) Label(stringResource(R.string.logo_no_image, brand.uppercase()), Typography.small, c.textMuted)
    }
    if (view == CarView.ROAD) Label(stringResource(R.string.road_help), Typography.small, c.textMuted)
    // Encender o apagar: interruptor en su propio renglón.
    SwitchRow(stringResource(R.string.logo_intro), prefs.logoIntro, { prefs.logoIntro = it }, stringResource(R.string.logo_intro_help))
}
