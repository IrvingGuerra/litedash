package com.irvingguerra.litedash.feature.obd

/**
 * Las partes del auto que se señalan en la vista aérea del Taller. [x] y [y] son dónde va
 * el punto, como fracción del ancho y del alto de la caja del auto visto desde arriba
 * (el frente arriba); valen igual para la silueta dibujada y para un render.
 */
enum class Zone(val x: Float, val y: Float) {
    /** Encendido, cilindros, computadora. */
    ENGINE(0.5f, 0.24f),
    /** Admisión, mezcla, inyección (lado derecho del motor). */
    INTAKE(0.7f, 0.19f),
    /** Radiador, termostato (al frente). */
    COOLING(0.5f, 0.1f),
    /** Batería y alternador (lado izquierdo del motor). */
    BATTERY(0.3f, 0.19f),
    /** Caja y embrague (centro). */
    TRANSMISSION(0.5f, 0.42f),
    /** Escape, catalizador, sensores de oxígeno, EVAP (atrás). */
    EXHAUST(0.5f, 0.9f),
    /** Frenos, ABS, suspensión (el punto va en la rueda de adelante a la izquierda). */
    WHEELS(0.14f, 0.3f),
    /** Carrocería y comunicación entre computadoras (sin lugar fijo). */
    BODY(0.5f, 0.62f),
}

/** Qué tan bien está una parte. */
enum class HealthLevel { GOOD, WARN, FAULT, UNKNOWN }

/**
 * El estado de una parte del auto: códigos de falla que la señalan (guardados o permanentes
 * = [HealthLevel.FAULT]; solo pendientes = [HealthLevel.WARN]), una alerta en vivo y el
 * dato que la define ([value], en la unidad de su señal; null si el auto no lo da).
 */
class SystemHealth(
    val zone: Zone,
    val level: HealthLevel,
    val codes: List<String>,
    val pendingCodes: List<String>,
    val alert: EngineAlert?,
    val value: Float?,
)

/** La parte del auto que señala un código de falla (por la letra y el tercer carácter). */
fun zoneOf(code: String): Zone = when (code.firstOrNull()) {
    'C' -> Zone.WHEELS
    'B', 'U' -> Zone.BODY
    else -> when (code.getOrNull(2)) {
        '1', '2' -> Zone.INTAKE
        '3', '6' -> Zone.ENGINE
        '4' -> Zone.EXHAUST
        '7', '8', '9' -> Zone.TRANSMISSION
        else -> if (code in COOLING_CODES) Zone.COOLING else Zone.ENGINE
    }
}.let { zone ->
    // Unos cuantos de la familia 0xx/1xx son del enfriamiento o la batería aunque caigan en "aire".
    when {
        code in COOLING_CODES -> Zone.COOLING
        code in BATTERY_CODES -> Zone.BATTERY
        else -> zone
    }
}

/** Sensor y termostato del refrigerante. */
private val COOLING_CODES = setOf("P0115", "P0116", "P0117", "P0118", "P0119", "P0125", "P0126", "P0128", "P0217")

/** Voltaje del sistema y alternador. */
private val BATTERY_CODES = setOf("P0560", "P0561", "P0562", "P0563", "P0620", "P0621", "P0622", "P0625", "P0626")

/** La parte a la que apunta cada alerta en vivo. */
fun zoneOf(alert: EngineAlert): Zone = if (alert == EngineAlert.HOT) Zone.COOLING else Zone.BATTERY

/**
 * La salud de cada parte con lo que se sabe: el reporte del motor (códigos y luz de falla),
 * la alerta en vivo y los datos que la definen. Sin reporte ni datos, todo queda
 * [HealthLevel.UNKNOWN]; con reporte y sin nada, [HealthLevel.GOOD].
 */
fun healthOf(report: EngineReport?, alert: EngineAlert?, coolantC: Float?, voltage: Float?, rpm: Float?): List<SystemHealth> {
    val stored = (report?.stored.orEmpty() + report?.permanent.orEmpty()).distinct()
    val pending = report?.pending.orEmpty().filter { it !in stored }
    return Zone.entries.map { zone ->
        val codes = stored.filter { zoneOf(it) == zone }
        val pendingHere = pending.filter { zoneOf(it) == zone }
        val alertHere = alert?.takeIf { zoneOf(it) == zone }
        val value = when (zone) {
            Zone.COOLING -> coolantC
            Zone.BATTERY -> voltage ?: report?.voltage
            Zone.ENGINE -> rpm
            else -> null
        }
        val level = when {
            codes.isNotEmpty() || alertHere == EngineAlert.HOT -> HealthLevel.FAULT
            pendingHere.isNotEmpty() || alertHere != null -> HealthLevel.WARN
            // La luz de falla sin códigos legibles: algo hay, y el motor es el sospechoso.
            zone == Zone.ENGINE && report?.readiness?.mil == true && stored.isEmpty() -> HealthLevel.WARN
            report != null || value != null -> HealthLevel.GOOD
            else -> HealthLevel.UNKNOWN
        }
        SystemHealth(zone, level, codes, pendingHere, alertHere, value)
    }
}

/** Cuántas partes piden atención o tienen falla. */
fun List<SystemHealth>.issues(): Int = count { it.level == HealthLevel.WARN || it.level == HealthLevel.FAULT }
