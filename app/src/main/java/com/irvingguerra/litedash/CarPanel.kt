package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Personalizar el auto 3D ([tileId]): si lleva la presión de las llantas (se guarda en la pieza,
 * [TileTires]) y dónde van (en las ruedas o en las esquinas, como en Ajustes → Llantas). Sin vista
 * previa: un segundo auto 3D pesaría de más en las pantallas sencillas, y el de atrás ya se ve.
 */
@Composable
fun CustomizeCarPanel(d: Dependencies, tileId: String, close: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val shown = showsTires(d, tile)
    PanelScaffold(stringResource(R.string.car_customize), close) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Label(stringResource(R.string.tile_tires), Typography.label, c.textMuted)
            SwitchRow(stringResource(R.string.tires_show_pressure), shown, { d.dashboard.setData(tileId, TileTires.encode(it)) })
            if (shown) {
                Label(stringResource(R.string.tires_on_car), Typography.label, c.textMuted)
                Segmented(
                    listOf(
                        TiresOnCar.WHEELS to stringResource(R.string.tires_on_car_wheels),
                        TiresOnCar.CORNERS to stringResource(R.string.tires_on_car_corners),
                    ),
                    if (d.prefs.tiresOnCar == TiresOnCar.CORNERS) TiresOnCar.CORNERS else TiresOnCar.WHEELS,
                    { d.prefs.tiresOnCar = it },
                )
            }
        }
    }
}
