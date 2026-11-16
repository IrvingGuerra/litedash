package com.irvingguerra.litedash.feature.obd

import android.content.res.Resources

/**
 * Qué significa un código de falla, en palabras de todos los días y en el idioma de [resources]. Solo los
 * genéricos más comunes; de los demás se dice de qué sistema son.
 */
fun describe(code: String, resources: Resources): String {
    val m = meaning(code)
    val text = if (m.cylinder != null) resources.getString(m.text, m.cylinder) else resources.getString(m.text)
    return if (m.maker) resources.getString(R.string.code_maker, text) else text
}

/** El texto de un código: su recurso, el cilindro si es una falla de encendido y si es un código de la marca. */
internal class CodeMeaning(val text: Int, val cylinder: Int? = null, val maker: Boolean = false)

internal fun meaning(code: String): CodeMeaning {
    COMMON[code]?.let { return CodeMeaning(it) }
    MISFIRE.matchEntire(code)?.let { return CodeMeaning(R.string.code_misfire, cylinder = it.groupValues[1].toInt()) }
    return CodeMeaning(system(code), maker = isMakerCode(code))
}

/**
 * De la marca (SAE J2012): P1xxx y P30xx–P33xx; en B, C y U, los que empiezan con 1 o 2. Los
 * P2xxx y P34xx–P39xx son genéricos aunque no empiecen con 0.
 */
internal fun isMakerCode(code: String): Boolean = when (code.firstOrNull()) {
    'P' -> code.getOrNull(1) == '1' || code.getOrNull(1) == '3' && code.getOrNull(2) in '0'..'3'
    'B', 'C', 'U' -> code.getOrNull(1) == '1' || code.getOrNull(1) == '2'
    else -> false
}

/** El sistema por la primera letra y el tercer carácter (P0 1xx = aire y combustible…). */
private fun system(code: String): Int = when (code.firstOrNull()) {
    'B' -> R.string.code_system_body
    'C' -> R.string.code_system_chassis
    'U' -> R.string.code_system_network
    else -> when (code.getOrNull(2)) {
        '1', '2' -> R.string.code_system_fuel_air
        '3' -> R.string.code_system_ignition
        '4' -> R.string.code_system_emissions
        '5' -> R.string.code_system_idle
        '6' -> R.string.code_system_computer
        '7', '8', '9' -> R.string.code_system_transmission
        else -> R.string.code_system_engine
    }
}

private val MISFIRE = Regex("P030([1-8])")

private val COMMON = mapOf(
    "P0011" to R.string.code_p0011,
    "P0016" to R.string.code_p0016,
    "P0030" to R.string.code_p0030,
    "P0036" to R.string.code_p0036,
    "P0101" to R.string.code_p0101,
    "P0102" to R.string.code_p0102,
    "P0113" to R.string.code_p0113,
    "P0117" to R.string.code_p0117,
    "P0118" to R.string.code_p0118,
    "P0121" to R.string.code_p0121,
    "P0128" to R.string.code_p0128,
    "P0131" to R.string.code_p0131,
    "P0133" to R.string.code_p0133,
    "P0135" to R.string.code_p0135,
    "P0141" to R.string.code_p0141,
    "P0171" to R.string.code_p0171,
    "P0172" to R.string.code_p0172,
    "P0174" to R.string.code_p0174,
    "P0175" to R.string.code_p0175,
    "P0300" to R.string.code_p0300,
    "P0325" to R.string.code_p0325,
    "P0335" to R.string.code_p0335,
    "P0340" to R.string.code_p0340,
    "P0401" to R.string.code_p0401,
    "P0420" to R.string.code_p0420,
    "P0430" to R.string.code_p0430,
    "P0440" to R.string.code_p0440,
    "P0442" to R.string.code_p0442,
    "P0446" to R.string.code_p0446,
    "P0455" to R.string.code_p0455,
    "P0456" to R.string.code_p0456,
    "P0500" to R.string.code_p0500,
    "P0505" to R.string.code_p0505,
    "P0506" to R.string.code_p0506,
    "P0507" to R.string.code_p0507,
    "P0562" to R.string.code_p0562,
    "P0563" to R.string.code_p0563,
    "P0605" to R.string.code_p0605,
    "P0700" to R.string.code_p0700,
    "P0715" to R.string.code_p0715,
    "P0740" to R.string.code_p0740,
    "U0100" to R.string.code_u0100,
)
