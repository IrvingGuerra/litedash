package com.irvingguerra.litedash.feature.car

// Nombres que vienen del catálogo publicado (catalogo.json) y de los paquetes de
// autos (auto.json, giro/ y sus capas) van en español porque así están publicados:
// las versiones viejas los leen, traducirlos rompería el catálogo. Lo que la app
// crea por su cuenta en el dispositivo (carpetas, temporales, preferencias) va en
// inglés desde la 1.4.47; la migración movió lo viejo.

/** Carpetas y archivos dentro de files/ en el dispositivo (y el archivo de preferencias). */
internal object CarFiles {
    const val ROOT = "cars"
    const val THUMBNAILS = ".thumbnails"
    const val PACKAGE_INFO = "auto.json"
    /** SHA-256 del paquete instalado: si el catálogo trae otro, hay imágenes nuevas. */
    const val PACKAGE_HASH = ".package.sha256"
    /** Carpeta del giro: viene así dentro del paquete (formato remoto, no se traduce). */
    const val SPIN = "giro"
    const val DOWNLOAD_PREFIX = ".downloading-"
    const val STAGING_SUFFIX = ".staging"
    const val PARTIAL_SUFFIX = ".partial"
}

/** Campos de catalogo.json y de auto.json. */
internal object CarJson {
    const val CARS = "autos"
    const val NAME = "nombre"
    const val MAKE = "marca"
    const val YEAR = "anio"
    const val AUTHOR = "autor"
    const val LICENSE = "licencia"
    const val SOURCE = "fuente"
    const val THUMBNAIL = "miniatura"
    const val EXTRAS = "extras"
    const val LEVEL = "nivel"
    const val GRAY = "gris"
    const val SPIN = "giro"
    const val FLOOR = "piso"
}

/** Capas que acompañan a cada imagen del paquete (vista o cuadro del giro). */
internal object CarViews {
    const val PAINT_MASK_SUFFIX = "-pintura"
    const val SHADOW_SUFFIX = "-sombra"
    const val REFLECTION_SUFFIX = "-reflejo"
}
