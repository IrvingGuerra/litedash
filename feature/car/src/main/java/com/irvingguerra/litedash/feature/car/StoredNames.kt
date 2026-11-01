package com.irvingguerra.litedash.feature.car

// Nombres que vienen del catálogo publicado (catalogo.json), de los paquetes de
// autos (auto.json y sus imágenes) y de las carpetas que la app ya creó en el
// dispositivo. Van en español porque así están publicados y guardados:
// traducirlos rompería el catálogo y los autos ya descargados. Los nombres en
// inglés son solo para el código.

/** Carpetas y archivos dentro de files/ en el dispositivo. */
internal object CarFiles {
    const val ROOT = "autos"
    const val THUMBNAILS = ".miniaturas"
    const val PACKAGE_INFO = "auto.json"
    const val SPIN = "giro"
    const val DOWNLOAD = ".bajando.zip"
    const val STAGING_SUFFIX = ".nueva"
    const val PARTIAL_SUFFIX = ".parcial"
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
    const val VIEWS = "vistas"
    const val SPIN = "giro"
    const val FLOOR = "piso"
}

/** Capas que acompañan a cada imagen del paquete (vista o cuadro del giro). */
internal object CarViews {
    const val PAINT_MASK_SUFFIX = "-pintura"
    const val SHADOW_SUFFIX = "-sombra"
    const val REFLECTION_SUFFIX = "-reflejo"
}
