package com.irvingguerra.litedash.feature.music

import android.media.MediaMetadata
import android.media.session.PlaybackState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Perfiles de Bluetooth, antes de P2 (`PLAN_BLUETOOTH_PERFILES.md`, riesgo 5 del inventario): [BroadcastMeta.parse] mezcla
 * las marcas. Esta prueba fija su salida para TODOS los avisos de [BroadcastMeta.ACTIONS] con varios juegos de extras, contra
 * `broadcast-meta-golden.txt` (sacado de la 1.5.26). Al partir `parse` por perfil la salida debe quedar idéntica.
 * Para rehacer el archivo (solo si el cambio de comportamiento lo aprobó Irving): `GOLDEN_WRITE=1`.
 */
class BroadcastMetaGoldenTest {
    private val avrcp = "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility."
    private val trackEvent = "android.bluetooth.avrcp-controller.profile."

    private val extrasSets: List<Pair<String, Map<String, Any?>>> = listOf(
        "vacío" to emptyMap(),
        "genérico" to mapOf("track" to "Canción", "artist" to "Artista", "playing" to true, "package" to "com.x.player"),
        "genérico-2" to mapOf("title" to "T2", "singer" to "S2", "isPlaying" to 0, "paused" to true),
        "sin-dato" to mapOf("track" to "Not Provided", "artist" to "unknown", "playstate" to "playing"),
        "id3" to mapOf("id3_title" to "Id3", "id3_artist" to "Id3A", "playstate" to 3),
        "solo-pausa" to mapOf("paused" to false),
        "autochips-media" to mapOf(avrcp + "extra.MEDIA_TITLE" to "Ojitos", avrcp + "extra.MEDIA_ARTIST" to "BB"),
        "autochips-estado" to mapOf(avrcp + "extra.PLAYBACK_STATUS" to 1),
        "autochips-pausa" to mapOf(avrcp + "extra.PLAYBACK_STATUS" to 2),
        "clw-bt" to mapOf("Media_Title" to "Clw", "Media_Artist" to "ClwA", "Play_State" to 1, "Media_Type" to 3),
        "clw-usb" to mapOf("Media_Title" to "Clw", "Play_State" to "true", "Media_Type" to 1),
        "track-event" to mapOf(
            trackEvent + "extra.METADATA" to mapOf(MediaMetadata.METADATA_KEY_TITLE to "TE", MediaMetadata.METADATA_KEY_ARTIST to "TEA"),
            trackEvent + "extra.PLAYBACK" to PlaybackState.STATE_PLAYING,
        ),
        "track-event-pausa" to mapOf(trackEvent + "extra.PLAYBACK" to PlaybackState.STATE_PAUSED),
    )

    private fun render(): String = buildString {
        for (action in BroadcastMeta.ACTIONS + listOf("com.x.unknown.metachanged", "com.maxmpz.audioplayer.OTHER")) {
            for ((name, extras) in extrasSets) appendLine("$action | $name | ${BroadcastMeta.parse(action, extras)}")
        }
    }

    @Test
    fun parseOutputIsUnchangedForEveryKnownAction() {
        val golden = File("src/test/resources/broadcast-meta-golden.txt")
        val now = render()
        if (System.getenv("GOLDEN_WRITE") == "1") golden.writeText(now)
        assertEquals(golden.readText(), now)
    }
}
