package com.irvingguerra.litedash.feature.music

/**
 * Un perfil de Bluetooth de fábrica: lo que LiteDash sabe de una familia de apps de Bluetooth (chip propio, no el de
 * Android), gemelo de [FactoryRadioKind]. El motor le pregunta lo mismo a todos: quién es ([apps]), cómo se hace sonar
 * ([recipes]) y, con las etapas siguientes del plan (`PLAN_BLUETOOTH_PERFILES.md`), qué suena, por dónde van las órdenes
 * y su renglón del reporte. Sin perfil de marca, [GenericBluetooth] (el Bluetooth de Android): hace lo de siempre.
 *
 * P1: solo mueve la lista de apps y las recetas aquí, sin cambiar nada. [all] aplanado da EXACTAMENTE la lista de antes,
 * en el mismo orden (lo fija `FactoryBluetoothTest`).
 */
internal abstract class FactoryBluetoothKind(
    val id: String,
    val family: HeadUnit,
    vararg apps: BrandBluetooth,
    /** Recetas propias, entre «sin hacer nada» y abrir la app de fábrica (que van en todas). */
    own: List<String> = emptyList(),
) {
    /** Sus apps, en el orden de prioridad de siempre. */
    val apps: List<BrandBluetooth> = apps.toList()

    /**
     * Sus recetas para que suene, de la primera a la última (la calibración las prueba en orden). Primero lo de la marca
     * (inocuo si no hace falta y es lo que se hacía antes de calibrar: así «sin hacer nada» no queda guardado por
     * casualidad cuando otra app ya había pedido la fuente), luego no hacer nada y al final abrir su app.
     */
    val recipes: List<String> = own + Recipes.PLAIN + Recipes.OPEN_FACTORY

    /**
     * Cuando el celular empieza a sonar por su Bluetooth, eso cuenta como el ▶ y se pide el sonido con la receta ya
     * guardada ([phoneStartAsks]). Solo los perfiles con un reporte que lo pidió (regla 3: no se generaliza).
     */
    open val asksSoundWhenPhoneStarts: Boolean = false

    /** Los avisos de qué suena que lee con sus propias claves ([readBroadcast]); van en [BroadcastMeta.ACTIONS]. */
    open val broadcasts: List<String> = emptyList()

    /**
     * Lee uno de sus [broadcasts]; null = no trae nada suyo y [BroadcastMeta.parse] lo lee como el aviso de cualquier app.
     * Lo que devuelve sin canción ni estado se descarta igual que siempre.
     */
    open fun readBroadcast(action: String, extras: Map<String, Any?>): BroadcastMeta? = null

    companion object {
        /**
         * Los perfiles de marca en el orden de prioridad de siempre (antes una sola lista `BRAND_BLUETOOTH`; las nuevas,
         * NWD, Jancar y Reglink, al final según las estadísticas de 315 pantallas, 2026-11-13). Perezosa: si se armara al
         * iniciarse esta clase, el perfil que se tocara primero quedaría como null en la lista (ProfileInitOrderTest).
         */
        val all: List<FactoryBluetoothKind> by lazy {
            listOf(
                FytBluetooth, TopWayBluetooth, MicrontekBluetooth, ClinkwayBluetooth, GoodocomBluetooth,
                AutochipsBluetooth, YfTechBluetooth, NwdBluetooth, JancarBluetooth, ReglinkBluetooth,
            )
        }

        /** El perfil de la familia de la pantalla ([HeadUnit.of]); sin uno de marca, el genérico. */
        fun forFamily(family: HeadUnit): FactoryBluetoothKind =
            all.firstOrNull { it.family == family && it.family != HeadUnit.GENERIC } ?: GenericBluetooth

        /** Quién lee cada aviso con claves propias ([broadcasts]). */
        val readers: Map<String, FactoryBluetoothKind> by lazy { all.flatMap { k -> k.broadcasts.map { it to k } }.toMap() }

        /** El perfil al que pertenece la app [pkg]; null si no es de marca. */
        fun ofPackage(pkg: String): FactoryBluetoothKind? = all.firstOrNull { k -> k.apps.any { it.pkg == pkg } }
    }
}

/** Una app de Bluetooth de marca: [music] = la que toca la música del celular (las demás son servicios). */
internal class BrandBluetooth(val pkg: String, val family: HeadUnit, val music: Boolean = true)

/** Todas las apps de marca en orden de prioridad, aplanando los perfiles (lo que leen [HeadUnit.detect] y las listas). */
internal val BRAND_BLUETOOTH: List<BrandBluetooth> = FactoryBluetoothKind.all.flatMap { it.apps }

/** El Bluetooth de Android, sin chip propio (la mayoría): sin recetas propias. */
internal object GenericBluetooth : FactoryBluetoothKind("generic", HeadUnit.GENERIC)

internal object FytBluetooth : FactoryBluetoothKind(
    "fyt", HeadUnit.FYT,
    BrandBluetooth(HeadUnit.FYT_BLUETOOTH, HeadUnit.FYT),
    BrandBluetooth("com.syu.ms", HeadUnit.FYT, music = false),
    BrandBluetooth("com.fyt.bt", HeadUnit.FYT, music = false),
    own = listOf(Recipes.FYT_TOGGLE),
)

internal object TopWayBluetooth : FactoryBluetoothKind(
    "topway", HeadUnit.TOPWAY,
    BrandBluetooth(HeadUnit.TOPWAY_BLUETOOTH, HeadUnit.TOPWAY),
    BrandBluetooth("com.tw.service", HeadUnit.TOPWAY, music = false),
    BrandBluetooth("com.ts.bt", HeadUnit.TOPWAY, music = false),
    own = listOf(Recipes.TW_SOURCE8),
)

internal object MicrontekBluetooth : FactoryBluetoothKind("microntek", HeadUnit.MICRONTEK, BrandBluetooth("com.microntek.bt", HeadUnit.MICRONTEK))

/** Clinkway: su widget multimedia avisa canción y estado de cualquier fuente; `Media_Type` 3 = Bluetooth. */
internal object ClinkwayBluetooth : FactoryBluetoothKind("clinkway", HeadUnit.CLINKWAY, BrandBluetooth("com.clw.btmusic", HeadUnit.CLINKWAY)) {
    const val INFO = "com.clw.intent.MEDIA_INFO"
    const val STATE = "com.clw.intent.MEDIA_PLAY_STATE"
    private const val BLUETOOTH = 3

    override val broadcasts = listOf(INFO, STATE)

    override fun readBroadcast(action: String, extras: Map<String, Any?>): BroadcastMeta {
        val info = action == INFO
        return BroadcastMeta(
            title = if (info) extras.text("Media_Title") else null,
            artist = if (info) extras.text("Media_Artist") else null,
            playing = if (info) null else playingFlag(extras["Play_State"]),
            pkg = null,
            bluetooth = (extras["Media_Type"] as? Number)?.toInt() == BLUETOOTH,
        )
    }
}

/** Goodocom (gocsdk): módulo que usan varias marcas; dice que hay chip propio, no la familia. */
internal object GoodocomBluetooth : FactoryBluetoothKind(
    "goodocom", HeadUnit.GENERIC, BrandBluetooth("com.goodocom.gocsdk", HeadUnit.GENERIC, music = false),
)

/** Autochips (AC8227L, Essgoo): avisa la canción y el estado por `AvrcpCtPlayerUtility`, cada uno en su aviso. */
internal object AutochipsBluetooth : FactoryBluetoothKind("autochips", HeadUnit.AUTOCHIPS, BrandBluetooth("com.autochips.bluetooth", HeadUnit.AUTOCHIPS)) {
    /** #13 (Essgoo, 3FE18D y 664682): el celular ya sonaba y nadie pedía la entrada de audio. */
    override val asksSoundWhenPhoneStarts = true

    private const val AVRCP = "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility."
    const val MEDIA = AVRCP + "action.ACTION_MEDIA_DATA_UPDATE"
    const val PLAYBACK = AVRCP + "action.ACTION_PLAYBACK_DATA_UPDATE"

    /** Canción y estado llegan sin paquete: [BroadcastMeta.over] los junta. */
    override val broadcasts = listOf(MEDIA, PLAYBACK)

    override fun readBroadcast(action: String, extras: Map<String, Any?>): BroadcastMeta? = when (action) {
        MEDIA -> BroadcastMeta(
            title = extras.text(AVRCP + "extra.MEDIA_TITLE"),
            artist = extras.text(AVRCP + "extra.MEDIA_ARTIST"),
            // El álbum viene como "MEIDA" (errata de la app de Autochips) y no se lee.
            playing = null, pkg = null, bluetooth = true,
        )
        PLAYBACK -> BroadcastMeta(
            title = null, artist = null,
            playing = (extras[AVRCP + "extra.PLAYBACK_STATUS"] as? Number)?.let { it.toInt() == 1 },
            pkg = null, bluetooth = true,
        )
        else -> null
    }
}

/** YF Tech: módulo como Goodocom (chip propio, familia no). */
internal object YfTechBluetooth : FactoryBluetoothKind(
    "yftech", HeadUnit.GENERIC, BrandBluetooth("com.yftech.service.bluetooth", HeadUnit.GENERIC, music = false),
)

internal object NwdBluetooth : FactoryBluetoothKind("nwd", HeadUnit.NWD, BrandBluetooth("com.nwd.bt.music", HeadUnit.NWD))

internal object JancarBluetooth : FactoryBluetoothKind("jancar", HeadUnit.JANCAR, BrandBluetooth("com.jancar.bluetooth", HeadUnit.JANCAR))

internal object ReglinkBluetooth : FactoryBluetoothKind("reglink", HeadUnit.REGLINK, BrandBluetooth("com.reglink.apps.bluetooth", HeadUnit.REGLINK))
