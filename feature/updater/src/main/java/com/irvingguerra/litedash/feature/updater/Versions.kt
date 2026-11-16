package com.irvingguerra.litedash.feature.updater

/**
 * El mismo cálculo que app/build.gradle.kts para una versión normal (X.Y.Z → versionCode), para comparar una
 * etiqueta de GitHub con la versión instalada. Null si la etiqueta no es una versión normal de LiteDash (otra
 * cosa, o una de prueba -beta/-rc, que nunca se publica ni se ofrece).
 */
fun versionCode(tag: String): Long? {
    val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag.trim()) ?: return null
    val (major, minor, patch) = m.destructured
    if (patch.toInt() > MAX_PATCH) return null
    // Desde la 1.4.100: el parche llega a 999 (antes 99: la 1.4.100 daba lo mismo que la 1.5.0). El 99 final es
    // el lugar que Gradle deja para las de prueba.
    return major.toLong() * 10_000_000 + minor.toLong() * 100_000 + patch.toLong() * 100 + 99
}

/** El parche más alto que cabe en el versionCode. */
const val MAX_PATCH = 999
