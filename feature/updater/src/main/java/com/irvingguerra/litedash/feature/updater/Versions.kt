package com.irvingguerra.litedash.feature.updater

/**
 * El mismo cálculo que app/build.gradle.kts: X.Y.Z-beta.N → versionCode.
 * Así se compara una etiqueta de GitHub con la versión instalada.
 * Devuelve null si la etiqueta no es una versión de LiteDash.
 */
fun versionCode(tag: String): Long? {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?$""").matchEntire(tag.trim()) ?: return null
    val (major, minor, patch, type, n) = m.destructured
    val suffix = when (type) {
        "beta" -> n.toInt().takeIf { it in 1..49 } ?: return null
        "rc" -> n.toInt().takeIf { it in 1..49 }?.plus(49) ?: return null
        else -> 99
    }
    return major.toLong() * 1_000_000 + minor.toLong() * 10_000 + patch.toLong() * 100 + suffix
}

/** ¿Es versión de prueba (beta o rc)? */
fun isPrerelease(tag: String) = tag.contains("-beta.") || tag.contains("-rc.")
