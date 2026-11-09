package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NwdRadioTest {
    @Test
    fun decodesRdsName() {
        // "MIX 106 " en hexadecimal, como lo deja la NWD en nwd_radio_current_ps_data.
        assertEquals("MIX 106", NwdRadio.decodePs("4D49582031303620"))
        assertEquals("", NwdRadio.decodePs(null))
        assertEquals("", NwdRadio.decodePs("zz"))
        assertEquals("", NwdRadio.decodePs("0000000000000000"))
    }
}
