package com.irvingguerra.litedash.feature.tpms

/**
 * Un anuncio Bluetooth LE tal como lo da Android: [manufacturerData] SIN los 2 bytes de
 * compañía ([companyId]); [serviceUuids] de 16 bits como "27a5" (minúsculas), los demás completos.
 */
class Advert(
    val name: String?,
    val address: String,
    val companyId: Int?,
    val manufacturerData: ByteArray?,
    val serviceUuids: List<String>,
    val rssi: Int,
) {
    /**
     * Los datos de fabricante completos, con la compañía al principio (little-endian):
     * varias familias meten sus datos en esos dos bytes.
     */
    val fullPayload: ByteArray?
        get() {
            val data = manufacturerData ?: return null
            val c = companyId ?: return data
            return byteArrayOf((c and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte()) + data
        }
}

/**
 * Tapones de llanta Bluetooth LE que solo anuncian (sin conectarse). Fuentes y confianza en
 * docs/tpms/FORMATOS.md. Fuera a propósito (poco fiables solo con anuncios): Tesla/Autel
 * (compañía 0x022B, necesita GATT), Michelin y FOBO.
 */
object BleTpmsFormats {
    private const val PSI_ATM_BR = 14.5f
    private const val PSI_ATM_KY = 14.6f
    private const val PSI_ATM_AI = 14.696f
    private const val PSI_ATM_TC = 14.6f
    /** Abajo de esto (V) una pila de botón ya está por acabarse. */
    private const val LOW_CELL_V = 2.5f

    /** Prueba las familias en orden; null = no es un tapón conocido. */
    fun decode(adv: Advert, now: Long): TireReading? =
        br(adv, now) ?: sysgration(adv, now) ?: bekubee(adv, now) ?: wodhmiey(adv, now) ?: ai(adv, now) ?: tc(adv, now)

    private fun reading(
        adv: Advert, now: Long, family: String, kpa: Float?, tempC: Float?,
        volts: Float? = null, pct: Int? = null, wheel: Wheel? = null, leak: Boolean = false,
    ) = TireReading(
        sensorId = adv.address.uppercase(),
        pressureKpa = kpa?.coerceAtLeast(0f),
        tempC = tempC,
        batteryPct = pct,
        batteryVolts = volts,
        lowBattery = (volts != null && volts < LOW_CELL_V) || (pct != null && pct <= 10),
        leak = leak,
        lost = false,
        family = family,
        reportedWheel = wheel,
        rssi = adv.rssi,
        atMillis = now,
    )

    /** `SS BB TT PPPP` sobre los datos completos: estado, V×10, °C con signo, psi×10 absoluta BE. */
    private fun statusLayout(adv: Advert, now: Long, family: String, p: ByteArray, atmPsi: Float): TireReading {
        val psiAbs = ((p.u8(3) shl 8) or p.u8(4)) / 10f
        return reading(adv, now, family, (psiAbs - atmPsi) * KPA_PER_PSI, p[2].toFloat(), volts = p.u8(1) / 10f)
    }

    /** BR / Pecham / SYTPMS (y la variante KY): 7 bytes contando los de compañía. */
    private fun br(adv: Advert, now: Long): TireReading? {
        val ky = adv.name == "KY"
        if (!(adv.name == "BR" || ky || "27a5" in adv.serviceUuids)) return null
        val p = adv.fullPayload?.takeIf { it.size == 7 } ?: return null
        return statusLayout(adv, now, if (ky) "KY" else "BR", p, if (ky) PSI_ATM_KY else PSI_ATM_BR)
    }

    /**
     * Sysgration / ZEEPIN / TP630: compañía 0x0100 y 16 bytes: [0] rueda 80–83, [0..5] MAC,
     * [6..9] Pa int32 LE, [10..13] °C×100 int32 LE, [14] batería %, [15] alarma (bits sin
     * documentar: no se interpretan).
     */
    private fun sysgration(adv: Advert, now: Long): TireReading? {
        if (adv.companyId != 0x0100) return null
        val d = adv.manufacturerData?.takeIf { it.size == 16 } ?: return null
        val pa = d.int32le(6)
        val centi = d.int32le(10)
        val wheel = (d.u8(0) - 0x80).takeIf { it in 0..3 }?.let { Wheel.entries[it] }
        return reading(adv, now, "Sysgration", pa / 1000f, centi / 100f, pct = d.u8(14).coerceAtMost(100), wheel = wheel)
    }

    /**
     * Bekubee HRTPMS: nombre "TPMS" o UUID A827, 9 bytes contando los de compañía (que
     * varían): [0] V = x·0.01 + 1.22, [1] °C + 50, [2..3] kPa + 100 BE, [4..6] ID.
     */
    private fun bekubee(adv: Advert, now: Long): TireReading? {
        if (!("a827" in adv.serviceUuids || adv.name == "TPMS")) return null
        val p = adv.fullPayload?.takeIf { it.size == 9 } ?: return null
        val kpa = ((p.u8(2) shl 8) or p.u8(3)) - 100f
        return reading(adv, now, "Bekubee", kpa, p.u8(1) - 50f, volts = p.u8(0) * 0.01f + 1.22f)
    }

    /**
     * WODHMIEY / ITPMS / Wicarlink: compañía 0x00AC y 15 bytes (después de la compañía):
     * [0] V×10 (escala supuesta), [1] presión (+256 si [6] == 1) × 3.144 kPa, [2] °C + 55,
     * [3] alarma, [9..14] MAC. Confianza media y sin ejemplo real.
     */
    private fun wodhmiey(adv: Advert, now: Long): TireReading? {
        if (adv.companyId != 0x00AC) return null
        val d = adv.manufacturerData?.takeIf { it.size == 15 } ?: return null
        val raw = d.u8(1) + if (d.u8(6) == 1) 256 else 0
        return reading(adv, now, "WODHMIEY", raw * 3.144f, d.u8(2) - 55f, volts = d.u8(0) / 10f, leak = d.u8(3) != 0)
    }

    /** Ai TPMS: nombre "AI-…" o MAC 12:30:AF; mismo acomodo que BR con 14.696 psi de atmósfera. */
    private fun ai(adv: Advert, now: Long): TireReading? {
        if (!(adv.name?.startsWith("AI-", ignoreCase = true) == true || adv.address.uppercase().startsWith("12:30:AF"))) return null
        val p = adv.fullPayload?.takeIf { it.size >= 5 } ?: return null
        return statusLayout(adv, now, "AiTPMS", p, PSI_ATM_AI)
    }

    /** TC.TPMS: UUID A828; acomodo de BR + 6 bytes de MAC, 14.6 psi de atmósfera. */
    private fun tc(adv: Advert, now: Long): TireReading? {
        if ("a828" !in adv.serviceUuids) return null
        val p = adv.fullPayload?.takeIf { it.size >= 5 } ?: return null
        return statusLayout(adv, now, "TC.TPMS", p, PSI_ATM_TC)
    }

    private fun ByteArray.int32le(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
}
