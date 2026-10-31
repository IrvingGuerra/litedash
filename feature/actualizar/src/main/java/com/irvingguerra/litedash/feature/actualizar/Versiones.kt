package com.irvingguerra.litedash.feature.actualizar

/**
 * El mismo cálculo que app/build.gradle.kts: X.Y.Z-beta.N → versionCode.
 * Así se compara una etiqueta de GitHub con la versión instalada.
 * Devuelve null si la etiqueta no es una versión de LiteDash.
 */
fun codigoDeVersion(etiqueta: String): Long? {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?$""").matchEntire(etiqueta.trim()) ?: return null
    val (mayor, menor, parche, tipo, n) = m.destructured
    val sufijo = when (tipo) {
        "beta" -> n.toInt().takeIf { it in 1..49 } ?: return null
        "rc" -> n.toInt().takeIf { it in 1..49 }?.plus(49) ?: return null
        else -> 99
    }
    return mayor.toLong() * 1_000_000 + menor.toLong() * 10_000 + parche.toLong() * 100 + sufijo
}

/** ¿Es versión de prueba (beta o rc)? */
fun esPrueba(etiqueta: String) = etiqueta.contains("-beta.") || etiqueta.contains("-rc.")
