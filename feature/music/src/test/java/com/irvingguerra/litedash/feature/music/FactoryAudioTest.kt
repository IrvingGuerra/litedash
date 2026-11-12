package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Test

class FactoryAudioTest {
    @Test
    fun knownRadioWinsOverGenericFm() {
        assertEquals("com.hcn.autoradio", FactoryAudio.pickRadio(setOf("com.android.fmradio", "com.hcn.autoradio", "com.android.settings")))
    }

    @Test
    fun unknownBrandRadioBeatsGenericFm() {
        assertEquals("com.imotor.fmam", FactoryAudio.pickRadio(setOf("com.android.fmradio", "com.imotor.fmam")))
    }

    @Test
    fun genericFmWhenAlone() {
        assertEquals("com.android.fmradio", FactoryAudio.pickRadio(setOf("com.android.fmradio", "com.android.chrome")))
    }

    @Test
    fun noRadio() {
        assertEquals(FactoryAudio.NONE, FactoryAudio.pickRadio(setOf("com.android.chrome", "com.android.settings")))
        // Las palabras van dentro de cada parte, no en el prefijo ("fm.foo" no es radio).
        assertEquals(FactoryAudio.NONE, FactoryAudio.pickRadio(setOf("fm.android.launcher")))
    }

    @Test
    fun factoryRadiosFromReports() {
        assertEquals("com.tw.radio", FactoryAudio.pickRadio(setOf("com.tw.radio", "com.tw.bt")))
        assertEquals("com.nwd.radio", FactoryAudio.pickRadio(setOf("com.nwd.radio", "com.nwd.kernel")))
    }

    @Test
    fun bluetoothBrandThenNameThenAndroid() {
        assertEquals("com.tw.bt", FactoryAudio.pickBluetooth(listOf("com.tw.bt"), setOf("com.foo.bt"), hasBluetooth = true))
        assertEquals("com.foo.bt", FactoryAudio.pickBluetooth(emptyList(), setOf("com.foo.bt", "com.android.chrome"), hasBluetooth = true))
        assertEquals(FactoryAudio.ANDROID, FactoryAudio.pickBluetooth(emptyList(), setOf("com.android.chrome"), hasBluetooth = true))
        assertEquals(FactoryAudio.NONE, FactoryAudio.pickBluetooth(emptyList(), emptySet(), hasBluetooth = false))
    }
}
