package com.irvingguerra.litedash.feature.car

import com.irvingguerra.litedash.core.ui.isRecent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentCheckTest {
    private val hour = 60 * 60 * 1000L

    @Test fun withinTheHourIsRecent() = assertTrue(isRecent(since = 1_000L, now = 1_000L + hour - 1, every = hour))

    @Test fun afterTheHourIsOld() = assertFalse(isRecent(since = 1_000L, now = 1_000L + hour, every = hour))

    @Test fun neverCheckedIsOld() = assertFalse(isRecent(since = 0L, now = 1_700_000_000_000L, every = hour))

    // Pantalla sin pila: arrancó en 1970/2015 y lo guardado es «del futuro». Antes se saltaba la revisión.
    @Test fun clockThatWentBackIsOld() = assertFalse(isRecent(since = 1_700_000_000_000L, now = 1_420_000_000_000L, every = hour))
}
