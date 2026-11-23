package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.obd.AccelState
import com.irvingguerra.litedash.feature.obd.AccelTimer
import org.junit.Assert.assertEquals
import org.junit.Test

class AccelFeedTest {
    @Test
    fun startAfterStandingStillIsNotInterpolatedAgainstTheFirstZero() {
        val timer = AccelTimer(100f)
        val feed = AccelFeed(timer)
        feed.update(0, 0f)
        // Un minuto parado sin lecturas nuevas (el bus no repite el 0) y arranca.
        feed.update(60_000, 5f)
        assertEquals(AccelState.Running(60_000), timer.state)
        feed.update(66_000, 100f)
        assertEquals(6f, (timer.state as AccelState.Done).seconds, 0.6f)
    }

    @Test
    fun freshReadingsStillInterpolateTheCrossing() {
        val timer = AccelTimer(100f)
        val feed = AccelFeed(timer)
        feed.update(0, 0f)
        feed.update(500, 5f)
        // Pasó por 1 km/h a la quinta parte del medio segundo.
        assertEquals(AccelState.Running(100), timer.state)
    }
}
