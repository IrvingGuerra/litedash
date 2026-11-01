package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.car.CAR_ASPECT
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.car.CarTile
import com.irvingguerra.litedash.feature.car.CarTurntable
import com.irvingguerra.litedash.feature.car.CatalogEntry

/**
 * El auto del inicio ([pkg] del catálogo, o el sedán dibujado si es null): gira
 * con el dedo y se queda donde lo dejas; un toque hace [onClick]. Su nombre,
 * color y modelo se cambian en Ajustes → Tu auto.
 */
@Composable
fun HomeCar(d: Dependencies, pkg: CarPackage?, onClick: () -> Unit, modifier: Modifier = Modifier, onReady: (() -> Unit)? = null) {
    val color = d.prefs.carColor
    // El sedán dibujado no carga nada: listo de una vez.
    if (pkg == null && onReady != null) {
        val ready by rememberUpdatedState(onReady)
        LaunchedEffect(Unit) { ready() }
    }
    CarTile(
        name = d.prefs.carName ?: pkg?.name ?: stringResource(R.string.my_car),
        color = color,
        onClick = onClick,
        modifier = modifier,
        drawing = pkg?.let { p ->
            { m -> CarTurntable(d.cars, p, color, m, d.prefs.carAngle, { d.prefs.carAngle = it }, onReady) }
        },
        hasFloor = pkg?.hasFloor == true,
    )
}

/**
 * Solo el auto: crece desde su pieza ([from], en coordenadas de la pantalla)
 * hasta casi toda la pantalla mientras el fondo tapa el inicio. [progress] va de
 * 0 (en su pieza) a 1 (completo) y se lee solo al dibujar: la animación no
 * vuelve a armar nada. El auto se pinta una vez al tamaño final y la GPU lo escala.
 */
@Composable
fun CarOnly(
    d: Dependencies,
    pkg: CarPackage?,
    from: Rect,
    progress: () -> Float,
    backdrop: Brush,
    onTap: () -> Unit,
    onReady: () -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val to = with(density) {
            val pad = 24.dp.toPx()
            Rect(
                insets.getLeft(this, direction) + pad,
                insets.getTop(this) + pad,
                maxWidth.toPx() - insets.getRight(this, direction) - pad,
                maxHeight.toPx() - insets.getBottom(this) - pad,
            )
        }
        // La escala va del auto dibujado en su pieza al auto dibujado en grande (no de caja a
        // caja): así crece desde el tamaño que se ve y al regresar termina justo en su lugar.
        val start = if (from.isEmpty) 0.6f else carWidth(from, density) / carWidth(to, density)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() }
                .background(backdrop)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        )
        Box(
            Modifier
                .offset { IntOffset(to.left.toInt(), to.top.toInt()) }
                .size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() })
                .graphicsLayer {
                    val t = progress()
                    val scale = start + (1f - start) * t
                    scaleX = scale
                    scaleY = scale
                    if (!from.isEmpty) {
                        translationX = (from.center.x - to.center.x) * (1f - t)
                        translationY = (from.center.y - to.center.y) * (1f - t)
                    }
                },
        ) {
            HomeCar(d, pkg, onClick = onTap, onReady = onReady)
        }
    }
}

/**
 * Ancho con que [CarTile] dibuja el auto en una caja: lo limita el alto (proporción
 * 1.9) y lleva 8 dp a cada lado. Para que la animación empiece y termine en el auto real.
 */
private fun carWidth(box: Rect, density: Density) =
    minOf(box.width, box.height * CAR_ASPECT) - with(density) { 16.dp.toPx() }

/**
 * El auto tiene un modelo 3D nuevo en el catálogo: un toque lo baja con la calidad
 * que tenía. El mismo aviso en el inicio y en Ajustes, con la descarga del repositorio.
 */
@Composable
fun CarUpdateNotice(cars: CarRepository, car: CarPackage, entry: CatalogEntry) {
    if (!cars.isOutdated(car, entry)) return
    Notice(stringResource(R.string.car_update_notice, car.name), Icons.car) {
        val progress = cars.downloading?.takeIf { it.first == car.id || it.first == CarRepository.ALL }?.second
        if (progress != null) {
            Label(stringResource(R.string.downloading, (progress * 100).toInt()), Typography.body, LocalColors.current.accent)
        } else {
            Button(stringResource(R.string.car_update, cars.updateSize(car, entry) / 1e6f), { cars.update(car, entry) }, primary = true)
        }
    }
}
