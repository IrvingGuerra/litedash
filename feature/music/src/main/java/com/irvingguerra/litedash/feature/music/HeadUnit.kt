package com.irvingguerra.litedash.feature.music

/**
 * La familia de la pantalla (quién hizo su firmware), con lo de audio que es de esa marca: sus apps de
 * Bluetooth y, en orden, las recetas para que el Bluetooth suene ([bluetoothRecipes]; la calibración las
 * prueba de la primera a la última). Se reconoce por los paquetes del sistema instalados, nunca por el
 * nombre del modelo. [GENERIC] = Bluetooth de Android sin chip propio (la mayoría): no lleva recetas.
 * Lo de la radio de fábrica de cada marca vive en su perfil ([FactoryRadio]); el de las listas de avisos
 * de la canción, en [BroadcastMeta].
 */
internal enum class HeadUnit(
    val id: String,
    /**
     * Su Bluetooth pasa por el mezclador de Android: si Android no tiene audio, de verdad no suena (Lancer de
     * Irving, 2026-11-13). La calibración no pregunta en cada receta, solo en la última.
     */
    val trustsSignal: Boolean = false,
    /** Recetas propias, entre "sin hacer nada" y abrir la app de fábrica (que van en todas). */
    own: List<String> = emptyList(),
) {
    TOPWAY("topway", trustsSignal = true, own = listOf(Recipes.TW_SOURCE8)),
    FYT("fyt", trustsSignal = true, own = listOf(Recipes.FYT_TOGGLE)),
    MICRONTEK("microntek"),
    NWD("nwd"),
    HCN("hcn"),
    AUTOCHIPS("autochips"),
    JANCAR("jancar"),
    REGLINK("reglink"),
    CLINKWAY("clinkway"),
    GENERIC("generic"),
    ;

    /**
     * Primero lo de la marca (inocuo si no hace falta y es lo que se hacía antes de calibrar: así "sin hacer nada"
     * no queda guardado por casualidad cuando otra app ya había pedido la fuente), luego no hacer nada y al final
     * abrir su app.
     */
    val bluetoothRecipes: List<String> = own + Recipes.PLAIN + Recipes.OPEN_FACTORY

    companion object {
        /**
         * La familia por sus paquetes: gana la del Bluetooth de fábrica (una TopWay con ZLink sigue siendo
         * TopWay) y, sin Bluetooth de marca, la de la radio.
         */
        fun detect(installed: (String) -> Boolean): HeadUnit =
            BRAND_BLUETOOTH.firstOrNull { it.family != GENERIC && installed(it.pkg) }?.family
                ?: FACTORY_RADIOS.firstOrNull { installed(it.first) }?.second
                ?: GENERIC

        /** Todas las apps de Bluetooth de marca, en orden de prioridad (para la familia en reportes y estadísticas). */
        val bluetoothApps: List<String> = BRAND_BLUETOOTH.map { it.pkg }

        /** Las que tocan la música del celular (las demás son servicios): la fuente Bluetooth sin app enlazada. */
        val bluetoothMusicApps: List<String> = BRAND_BLUETOOTH.filter { it.music }.map { it.pkg }

        /** Radios de fábrica que LiteDash controla con su perfil. */
        val radioApps: List<String> = FACTORY_RADIOS.map { it.first }
    }
}

/** Una app de Bluetooth de marca (chip propio, no el de Android): [music] = la que toca la música del celular. */
private class BrandBluetooth(val pkg: String, val family: HeadUnit, val music: Boolean = true)

/**
 * Una sola lista (antes `KNOWN_BT` y `BRAND_BT_APPS`), en el orden de prioridad de siempre; las nuevas (NWD,
 * Jancar, Reglink) al final, según las estadísticas de 315 pantallas (2026-11-13).
 */
private val BRAND_BLUETOOTH = listOf(
    BrandBluetooth("com.syu.bt", HeadUnit.FYT),
    BrandBluetooth("com.syu.ms", HeadUnit.FYT, music = false),
    BrandBluetooth("com.fyt.bt", HeadUnit.FYT, music = false),
    BrandBluetooth("com.tw.bt", HeadUnit.TOPWAY),
    BrandBluetooth("com.tw.service", HeadUnit.TOPWAY, music = false),
    BrandBluetooth("com.ts.bt", HeadUnit.TOPWAY, music = false),
    BrandBluetooth("com.microntek.bt", HeadUnit.MICRONTEK),
    BrandBluetooth("com.clw.btmusic", HeadUnit.CLINKWAY),
    // Goodocom (gocsdk) y YF Tech son módulos que usan varias marcas: dicen que hay chip propio, no la familia.
    BrandBluetooth("com.goodocom.gocsdk", HeadUnit.GENERIC, music = false),
    BrandBluetooth("com.autochips.bluetooth", HeadUnit.AUTOCHIPS),
    BrandBluetooth("com.yftech.service.bluetooth", HeadUnit.GENERIC, music = false),
    BrandBluetooth("com.nwd.bt.music", HeadUnit.NWD),
    BrandBluetooth("com.jancar.bluetooth", HeadUnit.JANCAR),
    BrandBluetooth("com.reglink.apps.bluetooth", HeadUnit.REGLINK),
)

/** Radios de fábrica con perfil y su familia; si hay varias, gana la primera. */
private val FACTORY_RADIOS = listOf(
    HcnRadio.PACKAGE to HeadUnit.HCN,
    NwdRadio.PACKAGE to HeadUnit.NWD,
    TopWayRadio.PACKAGE to HeadUnit.TOPWAY,
)
