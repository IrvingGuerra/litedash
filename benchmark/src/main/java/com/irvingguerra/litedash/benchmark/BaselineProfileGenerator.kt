package com.irvingguerra.litedash.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genera el Baseline Profile (app/src/main/baseline-prof.txt): las clases y métodos que
 * se usan al arrancar y en el inicio, para que Android los compile al instalar y el
 * arranque en frío no interprete código. Correr en un emulador API 33+ (sin root):
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.irvingguerra.litedash.benchmark.BaselineProfileGenerator
 * y luego copiar el .txt que deja en benchmark/build/outputs/connected_android_test_additional_output/.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // El inicio quieto unos instantes (el auto y las tarjetas se pintan) y un toque
        // en el auto para verlo en grande, que es lo primero que hace la gente.
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
        device.waitForIdle()
        device.click(device.displayWidth / 2, device.displayHeight * 2 / 5)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}
