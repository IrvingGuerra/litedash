package com.irvingguerra.litedash.feature.obd

/*
 * Las recetas de esta tabla son derivadas de OBDb (https://github.com/OBDb), CC BY-SA 4.0,
 * adaptadas (compatible en un solo sentido con la GPL-3.0 de LiteDash). Solo las confirmadas con
 * capturas reales y en CAN de 11 bits; la investigación completa está en lo interno
 * (docs/tpms/OBD_TPMS.md).
 */

/**
 * La presión de llantas que ya sabe la computadora del auto (TPMS de fábrica). No hay un PID
 * estándar: cada marca la guarda en otro módulo y con otra petición. Cada [TireProfile] es una
 * de esas recetas. El orden de las llantas en todo esto: delantera izq., der., trasera izq., der.
 */
internal class TireProfile(
    /** Se guarda (el que contestó) y sale en Ajustes → Llantas. */
    val id: String,
    /** Primeras letras del VIN de los autos donde se prueba primero ("JA3" = Mitsubishi). */
    val vinPrefixes: List<String>,
    /** Comandos antes de las peticiones: la dirección del módulo, quién contesta y lo que pida. */
    val setup: List<String>,
    /** Las peticiones al módulo ("2123"); todas deben contestar que sí para que la receta sea de este auto. */
    val requests: List<String>,
    /** De las respuestas (en el orden de [requests]) a las presiones y temperaturas. */
    val decode: (List<IntArray>) -> TireValues,
)

/** Presión manométrica (kPa) y temperatura (°C) de las cuatro llantas; null = el sensor aún no dijo. */
internal class TireValues(val kpa: List<Float?>, val tempC: List<Float?> = emptyList())

/** Lo que contestó el auto (o por qué no). */
sealed interface FactoryTires {
    data object NotConnected : FactoryTires
    data object Unsupported : FactoryTires
    /** Las cuatro llantas: kPa manométrica y °C. [profile] = la receta que contestó. */
    data class Values(val kpa: List<Float?>, val tempC: List<Float?>, val profile: String) : FactoryTires
}

/** Lo que se le regresa al adaptador después: el motor otra vez (CAN de 11 bits) y lo demás de fábrica. */
internal val RESTORE_ENGINE = listOf("ATSH7DF", "ATCRA", "ATFCSM0", "ATCEA", "ATTAF1", "ATST32")

/** La dirección del módulo [header], quién contesta ([reply]) y el control de flujo hacia él (respuestas largas). */
private fun module(header: String, reply: String, flow: Boolean = true) =
    listOf("ATSH$header", "ATCRA$reply") + if (flow) listOf("ATFCSH$header", "ATFCSD300000", "ATFCSM1") else emptyList()

private const val PSI_KPA = 6.89476f

/** Un byte que no es dato (0, FE, FF: el sensor aún no dijo) → null. */
private fun byteOf(d: IntArray, i: Int): Int? = d.getOrNull(i)?.takeIf { it != 0 && it < 0xFE }

/** Fuera de 0–600 kPa no es una llanta: se toma como "no dijo". */
private fun Float.orNullIfOdd(): Float? = takeIf { it in 0f..600f }

private fun psiByte(d: IntArray, i: Int, divisor: Float) = byteOf(d, i)?.let { (it / divisor * PSI_KPA).orNullIfOdd() }
private fun tempByte(d: IntArray, i: Int, offset: Int) = byteOf(d, i)?.let { (it - offset).toFloat() }
private fun word(d: IntArray, i: Int): Int? {
    val hi = d.getOrNull(i) ?: return null
    val lo = d.getOrNull(i + 1) ?: return null
    return (hi * 256 + lo).takeIf { it != 0 && it != 0xFFFF }
}

/** Hyundai/Kia "22 C0 0B": presión y temperatura de cada llanta cada [step] bytes desde el 4. */
private fun hyundai(d: IntArray, step: Int) = TireValues(
    List(4) { psiByte(d, 4 + it * step, 5f) },
    List(4) { tempByte(d, 5 + it * step, 50) },
)

/** Las recetas que se prueban, en orden; las de la marca del VIN van primero ([ordered]). */
internal val TireProfiles: List<TireProfile> = listOf(
    // ASX, RVR, Outlander Sport (plataforma GS; el Lancer la comparte, sin captura que lo confirme).
    TireProfile(
        "Mitsubishi", listOf("JA3", "JA4", "4A3", "4A4"), module("600", "500"),
        listOf("2123", "2126", "2124", "2125"),
    ) { r -> TireValues(r.map { psiByte(it, 8, 5f) }, r.map { tempByte(it, 9, 50) }) },
    // Nissan reciente (≈2018+) y Mitsubishi con base Nissan (Outlander 2021+).
    TireProfile(
        "Nissan / Mitsubishi", listOf("JN1", "JN8", "1N4", "1N6", "3N1", "3N6", "3N8", "5N1", "JA4"), module("743", "763"),
        listOf("220E26", "220E25", "220E28", "220E27"),
    ) { r -> TireValues(r.map { psiByte(it, 0, 4f) }) },
    // Nissan anterior (≈2014–2022).
    TireProfile(
        "Nissan", listOf("JN1", "JN8", "1N4", "1N6", "3N1", "3N6", "3N8", "5N1"), module("758", "778", flow = false),
        listOf("220201", "220202", "220204", "220203"),
    ) { r -> TireValues(r.map { psiByte(it, 0, 4f) }) },
    // Toyota y Lexus (≈2012–2023): direccionamiento extendido 2A (adaptadores ELM327 v1.4b o mejores).
    TireProfile(
        "Toyota / Lexus", listOf("JT", "2T1", "2T2", "2T3", "4T1", "4T3", "4T4", "5TD", "5TF"),
        listOf("ATSH750", "ATCRA758", "ATCEA2A", "ATTA2A", "ATFCSH750", "ATFCSD2A300000", "ATFCSM1"),
        listOf("2130"),
    ) { r -> TireValues(List(4) { i -> byteOf(r[0], i)?.let { ((it / 58f - 0.5f) * 100f).orNullIfOdd() } }) },
    // Hyundai y Kia: dos acomodos según el modelo; el de 5 bytes por llanta si la respuesta alcanza y da presiones.
    TireProfile(
        "Hyundai / Kia", listOf("KMH", "KM8", "KNA", "KND", "5NP", "5NM", "5XX", "5XY", "3KP"), module("7A0", "7A8"),
        listOf("22C00B"),
    ) { r ->
        val five = hyundai(r[0], 5)
        if (r[0].size >= 21 && five.kpa.all { it == null || it in 100f..450f } && five.kpa.any { it != null }) five else hyundai(r[0], 4)
    },
    // GM Global A (≈2013–2023): Chevrolet, GMC, Buick, Cadillac.
    TireProfile(
        "GM", listOf("1G", "2G", "3G", "KL"), module("241", "641", flow = false),
        listOf("225005", "225006", "225008", "225007"),
    ) { r -> TireValues(r.map { d -> word(d, 0)?.let { (it / 16f).orNullIfOdd() } }) },
    // Ford y Lincoln (casi toda la gama 2011+).
    TireProfile(
        "Ford", listOf("1F", "2F", "3F", "1L", "5L", "MAJ", "NM0", "WF0"), module("726", "72E", flow = false),
        listOf("222813", "222814", "222816", "222815"),
    ) { r -> TireValues(r.map { d -> word(d, 0)?.let { (it / 20f * PSI_KPA).orNullIfOdd() } }) },
    // Mazda Skyactiv de 2.ª gen. (Mazda3 2019+, CX-30, CX-50…): presión y luego temperatura.
    TireProfile(
        "Mazda", listOf("JM1", "JM3", "3MZ", "3MV", "3MD"), module("726", "72E", flow = false),
        listOf("22D922", "22D923", "22D924", "22D925", "22D926", "22D927", "22D928", "22D929"),
    ) { r -> TireValues(r.take(4).map { psiByte(it, 0, 5f) }, r.drop(4).map { tempByte(it, 0, 50) }) },
    // Subaru: las cuatro juntas, en el orden trasera izq., trasera der., delantera der., delantera izq.
    TireProfile(
        "Subaru", listOf("JF1", "JF2", "4S3", "4S4"), listOf("ATST64") + module("753", "75B"),
        listOf("221022"),
    ) { r -> TireValues(listOf(3, 2, 0, 1).map { psiByte(r[0], it, 5f) }) },
)

/** [TireProfiles] con primero las que corresponden al VIN (si se sabe). */
internal fun ordered(vin: String?): List<TireProfile> {
    if (vin.isNullOrEmpty()) return TireProfiles
    val (mine, rest) = TireProfiles.partition { p -> p.vinPrefixes.any { vin.startsWith(it) } }
    return mine + rest
}
