package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.SharedPreferences

/**
 * La familia de la pantalla (quién hizo su firmware), con lo de audio que es de esa marca: sus apps de
 * Bluetooth y, en orden, las recetas para que el Bluetooth suene ([bluetoothRecipes]; la calibración las
 * prueba de la primera a la última). Se reconoce por los paquetes del sistema instalados, nunca por el
 * nombre del modelo. [GENERIC] = Bluetooth de Android sin chip propio (la mayoría): no lleva recetas.
 * Lo de la radio de fábrica de cada marca vive en su perfil ([FactoryRadio]) y cómo se reconoce y se abre, en
 * [FactoryRadioKind] ([factoryRadios]); las listas de avisos de la canción, en [BroadcastMeta]. El motor de la música no
 * nombra marcas: pregunta aquí.
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
        /** Las apps de Bluetooth de TopWay y de FYT que tocan la música (sus recetas hablan con ellas). */
        const val TOPWAY_BLUETOOTH = "com.tw.bt"
        const val FYT_BLUETOOTH = "com.syu.bt"

        /**
         * La familia por sus paquetes: gana la del Bluetooth de fábrica (una TopWay con ZLink sigue siendo
         * TopWay) y, sin Bluetooth de marca, la de la radio; sin ninguna, el kernel de NWD (el que lleva su volumen).
         */
        fun detect(installed: (String) -> Boolean): HeadUnit =
            BRAND_BLUETOOTH.firstOrNull { it.family != GENERIC && installed(it.pkg) }?.family
                ?: factoryRadios.firstOrNull { installed(it.pkg) }?.family
                ?: NWD.takeIf { installed(NwdRadio.KERNEL_PACKAGE) }
                ?: GENERIC

        /**
         * La familia de ESTA pantalla para el motor (sus recetas y su app de Bluetooth): basta con que la app esté
         * instalada, como hasta la 1.5.15 (hay pantallas chinas que traen `com.tw.bt`/`com.syu.bt` en /data/app, sin la
         * bandera de sistema). Bloquea: consultas al instalador.
         */
        fun of(context: Context): HeadUnit = detect(context::hasPackage)

        /**
         * La familia para las estadísticas y para contar recetas ([FactoryAudio.family]): solo apps del sistema (una de
         * marca instalada a mano no hace a la pantalla de esa marca). Bloquea.
         */
        fun ofSystem(context: Context): HeadUnit = detect { isSystemApp(context, it) }

        /** La app de Bluetooth de marca instalada que toca la música (criterio de [of]); null si no hay. Bloquea. */
        fun bluetoothMusicApp(context: Context): String? = bluetoothMusicApps.firstOrNull(context::hasPackage)

        /** Todas las apps de Bluetooth de marca, en orden de prioridad (para la familia en reportes y estadísticas). */
        val bluetoothApps: List<String> = BRAND_BLUETOOTH.map { it.pkg }

        /** Las que tocan la música del celular (las demás son servicios): la fuente Bluetooth sin app enlazada. */
        val bluetoothMusicApps: List<String> = BRAND_BLUETOOTH.filter { it.music }.map { it.pkg }

        /** Las radios de fábrica con perfil, en el orden en que se prueban; si hay varias, gana la primera. */
        val factoryRadios: List<FactoryRadioKind> = listOf(HcnKind, NwdKind, TopWayKind)

        /** Radios de fábrica que LiteDash controla con su perfil. */
        val radioApps: List<String> = factoryRadios.map { it.pkg }
    }
}

/**
 * Una radio de fábrica con perfil: su app ([pkg]) y familia, cuándo la FM enlazada es suya, cómo se arma su perfil y su
 * renglón del reporte. El motor solo pregunta esto (no sabe de marcas).
 */
internal abstract class FactoryRadioKind(val pkg: String, val family: HeadUnit) {
    /** ¿La FM enlazada [linked] se maneja con este perfil? */
    abstract fun matches(linked: String): Boolean

    /** Solo si la FM no tiene receptor ni servicio de música (si los tiene, se le habla como a cualquier app). */
    open val needsSilent: Boolean = true

    /**
     * Arma su perfil en el hilo de los perfiles (reflexión, el PackageManager, el disco); el motor lo abre en el principal
     * ([FactoryRadio.open]). Null = esta pantalla no lo deja. [onState] recibe la estación; [log] va al registro.
     */
    abstract fun create(context: Context, store: SharedPreferences, onState: (BroadcastMeta) -> Unit, log: (String) -> Unit): FactoryRadio?

    /** Su renglón del reporte si esta pantalla es de la marca ([linked] = la FM enlazada, [profile] = el perfil abierto o el último). */
    abstract fun describe(context: Context, store: SharedPreferences, linked: String?, profile: FactoryRadio?, bluetoothRecipe: String?): String?
}

/** HCN (`com.hcn.autoradio`): tiene receptor, pero no dice la estación ni arranca con él: lleva perfil aunque no esté "callada". */
private object HcnKind : FactoryRadioKind(HcnRadio.PACKAGE, HeadUnit.HCN) {
    override fun matches(linked: String) = linked == pkg
    override val needsSilent = false
    override fun create(context: Context, store: SharedPreferences, onState: (BroadcastMeta) -> Unit, log: (String) -> Unit) =
        HcnRadio(context, onState, log)
    override fun describe(context: Context, store: SharedPreferences, linked: String?, profile: FactoryRadio?, bluetoothRecipe: String?) =
        HcnRadio.describe(context, profile as? HcnRadio).takeIf { linked == pkg }
}

/** NWD (`com.nwd.radio…`): por su sintonizador, sus ajustes y sus avisos del sistema. */
private object NwdKind : FactoryRadioKind(NwdRadio.PACKAGE, HeadUnit.NWD) {
    override fun matches(linked: String) = linked.startsWith(pkg)
    override fun create(context: Context, store: SharedPreferences, onState: (BroadcastMeta) -> Unit, log: (String) -> Unit) =
        NwdRadio(context, onState, log)
    override fun describe(context: Context, store: SharedPreferences, linked: String?, profile: FactoryRadio?, bluetoothRecipe: String?) =
        NwdRadio.describe(context, profile as? NwdRadio).takeIf { linked?.startsWith(pkg) == true }
}

/**
 * TopWay (`com.tw.radio`): cualquier FM "callada" en una pantalla con su enlace TWUtil; se abre con seguro (si la app de
 * la pantalla se cerró alguna vez al abrirlo, ya no). Su renglón va siempre que la pantalla traiga el enlace.
 */
private object TopWayKind : FactoryRadioKind(TopWayRadio.PACKAGE, HeadUnit.TOPWAY) {
    override fun matches(linked: String) = TopWayRadio.available
    override fun create(context: Context, store: SharedPreferences, onState: (BroadcastMeta) -> Unit, log: (String) -> Unit) =
        TopWayRadio.openGuarded(store, onState, log)
    override fun describe(context: Context, store: SharedPreferences, linked: String?, profile: FactoryRadio?, bluetoothRecipe: String?) =
        TopWayRadio.describe(store, profile as? TopWayRadio, bluetoothRecipe).takeIf { TopWayRadio.available }
}

/** Una app de Bluetooth de marca (chip propio, no el de Android): [music] = la que toca la música del celular. */
private class BrandBluetooth(val pkg: String, val family: HeadUnit, val music: Boolean = true)

/**
 * Una sola lista (antes `KNOWN_BT` y `BRAND_BT_APPS`), en el orden de prioridad de siempre; las nuevas (NWD,
 * Jancar, Reglink) al final, según las estadísticas de 315 pantallas (2026-11-13).
 */
private val BRAND_BLUETOOTH = listOf(
    BrandBluetooth(HeadUnit.FYT_BLUETOOTH, HeadUnit.FYT),
    BrandBluetooth("com.syu.ms", HeadUnit.FYT, music = false),
    BrandBluetooth("com.fyt.bt", HeadUnit.FYT, music = false),
    BrandBluetooth(HeadUnit.TOPWAY_BLUETOOTH, HeadUnit.TOPWAY),
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
