package com.irvingguerra.litedash.feature.tpms

/** Claves guardadas del ajuste de calibración (archivo de preferencias de Llantas). */
internal object OffsetKeys {
    /** Ajuste de cada sensor: "offset_kpa_id_<id>" (desde la auditoría 1.4.162; también los de la Demo, "demo-*"). */
    const val BY_ID = "offset_kpa_id_"
    /** El ajuste de antes de una llanta que no tenía sensor: lo estrena el primer sensor que se le ponga. */
    const val PENDING = "offset_kpa_pending_"
    /** La migración de abajo ya corrió. */
    const val MIGRATED = "offset_by_sensor"
    /** Antes: "offset_kpa_<llanta>" (1.4.131–1.4.162) y, sin sufijo, uno para todas (antes de la 1.4.131). */
    const val OLD = "offset_kpa"
    const val ASSIGN = "assign_"
}

/** Lo que escribe la migración ([put], sin pisar lo que ya exista) y lo que quita después ([remove]). */
internal data class OffsetMigration(val put: Map<String, Float>, val remove: Set<String>)

/**
 * Del ajuste por llanta al ajuste por sensor, sobre lo guardado ([saved] = todas las preferencias).
 * Por llanta, el ajuste viejo (el suyo o, si no tiene, el global) pasa a cada sensor que puede estar
 * en ella: el de Bluetooth asignado, "obd-<llanta>" y "usb-<llanta>" (el receptor USB las llama así
 * hasta decir su id real). Si por Bluetooth no tiene sensor, queda esperando al primero ([OffsetKeys.PENDING]).
 * Nunca pisa una clave nueva que ya exista: correrla otra vez da lo mismo.
 */
internal fun offsetMigration(saved: Map<String, *>): OffsetMigration {
    val global = saved[OffsetKeys.OLD] as? Float
    val put = LinkedHashMap<String, Float>()
    fun write(key: String, kpa: Float) {
        if (key !in saved && key !in put) put[key] = kpa
    }
    for (w in Wheel.entries) {
        val kpa = saved[OffsetKeys.OLD + "_" + w.key] as? Float ?: global ?: continue
        if (kpa == 0f) continue
        val ble = saved[OffsetKeys.ASSIGN + w.key] as? String
        if (ble != null) write(OffsetKeys.BY_ID + ble, kpa) else write(OffsetKeys.PENDING + w.key, kpa)
        if (w in Wheel.ROAD) write(OffsetKeys.BY_ID + obdSensorId(w), kpa)
        write(OffsetKeys.BY_ID + usbSensorId(w), kpa)
    }
    val remove = (Wheel.entries.map { OffsetKeys.OLD + "_" + it.key } + OffsetKeys.OLD).filter { it in saved }.toSet()
    return OffsetMigration(put, remove)
}
