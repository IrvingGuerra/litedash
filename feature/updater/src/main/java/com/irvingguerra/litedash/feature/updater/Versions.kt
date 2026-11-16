package com.irvingguerra.litedash.feature.updater

/**
 * La versión de una etiqueta (`v1.4.162` o `1.4.162`) como un número que solo sirve para ordenar (no es el versionCode:
 * ese lo calcula únicamente app/build.gradle.kts). Null si no es una versión normal de LiteDash: otra etiqueta
 * (`catalogo`) o una de prueba (-beta/-rc), que nunca se publica ni se ofrece.
 */
internal fun releaseOf(tag: String): Long? {
    val (major, minor, patch) = VERSION.matchEntire(tag.trim())?.destructured ?: return null
    return (major.toLong() * PART + minor.toLong()) * PART + patch.toLong()
}

/** Cada parte de X.Y.Z cabe en 6 cifras (lo que acepta [VERSION]). */
private const val PART = 1_000_000L

private val VERSION = Regex("""^v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})$""")
