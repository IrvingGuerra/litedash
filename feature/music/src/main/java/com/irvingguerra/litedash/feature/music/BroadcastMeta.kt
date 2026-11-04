package com.irvingguerra.litedash.feature.music

/**
 * Lo que suena según un aviso (broadcast) de otra app: las de Bluetooth de muchas pantallas
 * chinas no publican sesión de medios pero sí avisan la canción así. Campos null = el aviso no
 * lo dijo (los de solo estado o solo tiempo se juntan con el anterior en [over]).
 */
data class BroadcastMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val playing: Boolean?,
    /** La app que avisa, si lo manda en los extras. */
    val pkg: String?,
    /** El aviso es de la música por Bluetooth (AVRCP o la app BT de la pantalla). */
    val bluetooth: Boolean,
) {
    /** Este aviso encima de [prev] (de la misma app): lo que no trae se toma del anterior. */
    fun over(prev: BroadcastMeta?): BroadcastMeta {
        if (prev == null || prev.pkg != pkg || prev.bluetooth != bluetooth) return this
        // Canción nueva sin artista: no heredar el de la anterior.
        val sameSong = title == null || title == prev.title
        return copy(
            title = title ?: prev.title,
            artist = artist ?: prev.artist.takeIf { sameSong },
            album = album ?: prev.album.takeIf { sameSong },
            playing = playing ?: prev.playing,
        )
    }

    companion object {
        // Bluetooth Autochips (AC8227L y otras) y el widget multimedia Clinkway ("com.clw").
        private const val AVRCP = "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility."
        private const val AVRCP_MEDIA = AVRCP + "action.ACTION_MEDIA_DATA_UPDATE"
        private const val AVRCP_PLAYBACK = AVRCP + "action.ACTION_PLAYBACK_DATA_UPDATE"
        private const val CLW_INFO = "com.clw.intent.MEDIA_INFO"
        private const val CLW_STATE = "com.clw.intent.MEDIA_PLAY_STATE"
        /** Clinkway: `Media_Type` 3 = Bluetooth. */
        private const val CLW_BLUETOOTH = 3

        /** Avisos conocidos (lista de StradaOS, GPL-3.0): reproductores comunes y apps BT de pantallas. */
        val ACTIONS = listOf(
            // Reproductores
            "com.android.music.metachanged", "com.android.music.playstatechanged",
            "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged",
            "com.tbig.playerpro.metachanged", "com.tbig.playerpro.playstatechanged",
            "com.maxmpz.audioplayer.TRACK_CHANGED", "com.maxmpz.audioplayer.STATUS_CHANGED",
            "com.htc.music.metachanged", "com.htc.music.playstatechanged",
            "com.sec.android.app.music.metachanged", "com.miui.player.metachanged",
            "com.andrew.apollo.metachanged", "com.andrew.apollo.playstatechanged",
            "com.jrtstudio.AnotherMusicPlayer.metachanged", "com.jrtstudio.music.metachanged",
            "com.doubleTwist.androidPlayer.metachanged", "com.real.IMP.metachanged",
            "com.amazon.mp3.metachanged", "fm.last.android.metachanged",
            "deezer.android.app.metachanged", "com.lge.music.metachanged",
            "com.sonyericsson.music.metachanged", "com.rdio.android.metachanged",
            "com.samsung.sec.android.MusicPlayer.metachanged", "com.nullsoft.winamp.metachanged",
            "org.videolan.vlc.metachanged",
            // Bluetooth (AVRCP) de Android y de pantallas chinas
            "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
            "com.android.bluetooth.a2dpsink.action.METADATA_CHANGED",
            "com.android.music.bluetooth.metachanged",
            "com.microntek.music.report", "com.microntek.bt.report",
            "com.syu.bt.music.id3", "com.syu.ms.bt.id3",
            "com.goodocom.gocsdk.music.info", "com.goodocom.gocsdk.action.MUSIC_INFO",
            "com.ts.bt.music.info", "com.fyt.bt.music.id3",
            "com.autochips.bluetooth.MUSIC_INFO", "com.autochips.bt.music.id3",
            "com.nwd.action.ACTION_BT_MUSIC_ID3",
            AVRCP_MEDIA, AVRCP_PLAYBACK, CLW_INFO, CLW_STATE,
        )

        /** Lee un aviso; null si no dice nada útil (ni canción ni estado). */
        fun parse(action: String, extras: Map<String, Any?>): BroadcastMeta? {
            val meta = when (action) {
                AVRCP_MEDIA -> BroadcastMeta(
                    title = extras.text(AVRCP + "extra.MEDIA_TITLE"),
                    artist = extras.text(AVRCP + "extra.MEDIA_ARTIST"),
                    // "MEIDA" con la errata de la app de Autochips.
                    album = extras.text(AVRCP + "extra.MEIDA_ALBUM", AVRCP + "extra.MEDIA_ALBUM"),
                    playing = null, pkg = null, bluetooth = true,
                )
                AVRCP_PLAYBACK -> BroadcastMeta(
                    title = null, artist = null, album = null,
                    playing = (extras[AVRCP + "extra.PLAYBACK_STATUS"] as? Number)?.let { it.toInt() == 1 },
                    pkg = null, bluetooth = true,
                )
                CLW_INFO, CLW_STATE -> {
                    val info = action == CLW_INFO
                    BroadcastMeta(
                        title = if (info) extras.text("Media_Title") else null,
                        artist = if (info) extras.text("Media_Artist") else null,
                        album = if (info) extras.text("Media_Album") else null,
                        playing = if (info) null else flag(extras["Play_State"]),
                        pkg = null,
                        bluetooth = (extras["Media_Type"] as? Number)?.toInt() == CLW_BLUETOOTH,
                    )
                }
                else -> BroadcastMeta(
                    title = extras.text("track", "title", "song", "name", "com.maxmpz.audioplayer.TRACK", "id3_title", "music_title", "TITLE"),
                    artist = extras.text("artist", "singer", "id3_artist", "music_artist", "ARTIST"),
                    album = extras.text("album", "id3_album", "music_album", "ALBUM"),
                    playing = sequenceOf("playing", "isPlaying", "playstate").firstNotNullOfOrNull { flag(extras[it]) },
                    pkg = extras.text("package", "packageName", "pkg"),
                    bluetooth = isBluetooth(action),
                )
            }
            return meta.takeIf { it.title != null || it.artist != null || it.playing != null }
        }

        // "gocsdk" es la pila Bluetooth de Goodocom.
        private val BLUETOOTH_WORDS = listOf("bluetooth", ".bt.", "_bt_", "avrcp", "a2dp", "gocsdk")

        private fun isBluetooth(action: String): Boolean {
            val a = action.lowercase()
            return BLUETOOTH_WORDS.any { it in a }
        }

        // Las apps mandan "Not Provided" o "unknown" cuando no saben.
        private fun Map<String, Any?>.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (this[k] as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Not Provided", true) && !it.equals("unknown", true) }
        }

        /** Sonando: true/false, 1 (o 3 = PlaybackState.STATE_PLAYING) o "playing"/"true"/"1". */
        private fun flag(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            is Number -> v.toInt().let { it == 1 || it == 3 }
            is String -> v.equals("true", true) || v == "1" || v.equals("playing", true)
            else -> null
        }
    }
}
