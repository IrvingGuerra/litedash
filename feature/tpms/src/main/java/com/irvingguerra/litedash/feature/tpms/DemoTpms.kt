package com.irvingguerra.litedash.feature.tpms

import kotlin.random.Random

/**
 * Cuatro sensores de mentira para ver la pantalla sin receptor: ~220 kPa, 25–35 °C, un
 * poco de ruido y una fuga lenta en la trasera derecha (−1 kPa/min) que vuelve a 220 al
 * llegar a 150. El repositorio llama [tick] cada [INTERVAL_MS].
 */
class DemoTpms(private val random: Random = Random.Default) {
    private val base = mapOf(
        Wheel.FRONT_LEFT to 222f, Wheel.FRONT_RIGHT to 219f, Wheel.REAR_LEFT to 221f, Wheel.REAR_RIGHT to 220f,
    )
    private val temps = mapOf(Wheel.FRONT_LEFT to 31f, Wheel.FRONT_RIGHT to 33f, Wheel.REAR_LEFT to 27f, Wheel.REAR_RIGHT to 28f)
    private var leakStart: Long? = null

    fun tick(now: Long): List<TireReading> {
        val start = leakStart ?: now.also { leakStart = it }
        var leak = base.getValue(Wheel.REAR_RIGHT) - (now - start) / 60_000f
        if (leak <= 150f) {
            leakStart = now
            leak = base.getValue(Wheel.REAR_RIGHT)
        }
        return Wheel.ROAD.map { w ->
            val kpa = if (w == Wheel.REAR_RIGHT) leak else base.getValue(w)
            TireReading(
                sensorId = id(w),
                pressureKpa = kpa + noise(0.7f),
                tempC = (temps.getValue(w) + noise(1.5f)).coerceIn(25f, 35f),
                batteryPct = if (w == Wheel.REAR_LEFT) 35 else 90,
                batteryVolts = null,
                lowBattery = false,
                leak = false,
                lost = false,
                family = FAMILY,
                reportedWheel = w,
                rssi = null,
                atMillis = now,
            )
        }
    }

    private fun noise(amp: Float) = (random.nextFloat() * 2f - 1f) * amp

    companion object {
        const val INTERVAL_MS = 2_000L
        const val FAMILY = "Demo"
        fun id(w: Wheel) = "demo-${w.key}"
    }
}
