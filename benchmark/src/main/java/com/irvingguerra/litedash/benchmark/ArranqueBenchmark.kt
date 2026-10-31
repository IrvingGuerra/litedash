package com.irvingguerra.litedash.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PAQUETE = "com.irvingguerra.litedash"

/** Arranque y fluidez. Correr: ./gradlew :benchmark:connectedBenchmarkAndroidTest */
@RunWith(AndroidJUnit4::class)
class ArranqueBenchmark {
    @get:Rule val regla = MacrobenchmarkRule()

    @Test
    fun arranqueEnFrio() = regla.measureRepeated(
        packageName = PAQUETE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun desplazarCajon() = regla.measureRepeated(
        packageName = PAQUETE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
    ) {
        startActivityAndWait()
        val cuadricula = device.findObject(By.scrollable(true)) ?: return@measureRepeated
        cuadricula.setGestureMargin(device.displayWidth / 10)
        cuadricula.fling(Direction.DOWN)
        device.waitForIdle()
    }
}
