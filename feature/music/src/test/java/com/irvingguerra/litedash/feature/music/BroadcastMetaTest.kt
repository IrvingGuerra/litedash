package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastMetaTest {
    private val avrcp = "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility."

    @Test
    fun androidMusicStyle() {
        val m = BroadcastMeta.parse(
            "com.android.music.metachanged",
            mapOf("track" to "Bésame Mucho", "artist" to "Luis Miguel", "album" to "Romance", "playing" to true, "id" to 42L),
        )!!
        assertEquals("Bésame Mucho", m.title)
        assertEquals("Luis Miguel", m.artist)
        assertEquals("Romance", m.album)
        assertEquals(true, m.playing)
        assertFalse(m.bluetooth)
        assertNull(m.pkg)
    }

    @Test
    fun spotifyPlaybackStateOnly() {
        val m = BroadcastMeta.parse("com.spotify.music.playbackstatechanged", mapOf("playing" to false, "playbackPosition" to 1200))!!
        assertNull(m.title)
        assertEquals(false, m.playing)
    }

    @Test
    fun autochipsAvrcpMetadataAndStatus() {
        val media = BroadcastMeta.parse(
            avrcp + "action.ACTION_MEDIA_DATA_UPDATE",
            mapOf(avrcp + "extra.MEDIA_TITLE" to "Ojitos Lindos", avrcp + "extra.MEDIA_ARTIST" to "Bad Bunny", avrcp + "extra.MEIDA_ALBUM" to "Un Verano Sin Ti"),
        )!!
        assertTrue(media.bluetooth)
        assertEquals("Un Verano Sin Ti", media.album)
        assertNull(media.playing)
        // El aviso de tiempo trae solo el estado (1 = sonando) y se junta con el anterior.
        val status = BroadcastMeta.parse(
            avrcp + "action.ACTION_PLAYBACK_DATA_UPDATE",
            mapOf(avrcp + "extra.PLAYBACK_STATUS" to 1, avrcp + "extra.MEDIA_POSITION" to 30_000L, avrcp + "extra.MEDIA_LENGTH" to 250_000L),
        )!!
        val merged = status.over(media)
        assertEquals("Ojitos Lindos", merged.title)
        assertEquals("Bad Bunny", merged.artist)
        assertEquals(true, merged.playing)
        val paused = BroadcastMeta.parse(avrcp + "action.ACTION_PLAYBACK_DATA_UPDATE", mapOf(avrcp + "extra.PLAYBACK_STATUS" to 2))!!.over(merged)
        assertEquals(false, paused.playing)
        assertEquals("Ojitos Lindos", paused.title)
    }

    @Test
    fun clinkwayBluetoothAndUnit() {
        val bt = BroadcastMeta.parse("com.clw.intent.MEDIA_INFO", mapOf("Media_Type" to 3, "Media_Title" to "La Bikina", "Media_Artist" to "Mariachi Vargas"))!!
        assertTrue(bt.bluetooth)
        assertEquals("La Bikina", bt.title)
        val usb = BroadcastMeta.parse("com.clw.intent.MEDIA_INFO", mapOf("Media_Type" to 1, "Media_Title" to "Track01"))!!
        assertFalse(usb.bluetooth)
        val state = BroadcastMeta.parse("com.clw.intent.MEDIA_PLAY_STATE", mapOf("Media_Type" to 3, "Play_State" to "playing"))!!
        assertEquals(true, state.playing)
        assertEquals("La Bikina", state.over(bt).title)
    }

    @Test
    fun chineseBtAppsAreBluetooth() {
        for (a in listOf("com.syu.bt.music.id3", "com.microntek.bt.report", "com.nwd.action.ACTION_BT_MUSIC_ID3", "com.goodocom.gocsdk.music.info", "com.fyt.bt.music.id3")) {
            val m = BroadcastMeta.parse(a, mapOf("title" to "Canción", "singer" to "Alguien"))
            assertTrue(a, m!!.bluetooth)
            assertEquals("Alguien", m.artist)
        }
        assertFalse(BroadcastMeta.parse("com.microntek.music.report", mapOf("title" to "x"))!!.bluetooth)
    }

    @Test
    fun poweramp() {
        val m = BroadcastMeta.parse("com.maxmpz.audioplayer.TRACK_CHANGED", mapOf("com.maxmpz.audioplayer.TRACK" to "Cielito Lindo", "package" to "com.maxmpz.audioplayer"))!!
        assertEquals("Cielito Lindo", m.title)
        assertEquals("com.maxmpz.audioplayer", m.pkg)
    }

    @Test
    fun playingFlagsInManyShapes() {
        fun p(v: Any?) = BroadcastMeta.parse("com.android.music.playstatechanged", mapOf("playing" to v))?.playing
        assertEquals(true, p(true))
        assertEquals(true, p(1))
        assertEquals(true, p(3))
        assertEquals(false, p(2))
        assertEquals(true, p("true"))
        assertEquals(false, p("paused"))
        assertNull(p(null))
        assertEquals(true, BroadcastMeta.parse("com.android.music.playstatechanged", mapOf("isPlaying" to true))!!.playing)
        assertEquals(false, BroadcastMeta.parse("com.android.music.playstatechanged", mapOf("playstate" to 0))!!.playing)
    }

    @Test
    fun placeholdersAndEmptyAreIgnored() {
        assertNull(BroadcastMeta.parse("com.android.music.metachanged", mapOf("track" to "  ", "artist" to "Not Provided", "album" to "unknown")))
        assertNull(BroadcastMeta.parse("com.android.music.metachanged", emptyMap()))
        val m = BroadcastMeta.parse("com.android.music.metachanged", mapOf("track" to "Hola", "artist" to "unknown"))!!
        assertNull(m.artist)
    }

    @Test
    fun newSongDoesNotKeepOldArtist() {
        val a = BroadcastMeta.parse("com.android.music.metachanged", mapOf("track" to "Uno", "artist" to "A", "playing" to true))!!
        val b = BroadcastMeta.parse("com.android.music.metachanged", mapOf("track" to "Dos"))!!.over(a)
        assertEquals("Dos", b.title)
        assertNull(b.artist)
        assertEquals(true, b.playing)
    }

    @Test
    fun otherAppDoesNotMerge() {
        val a = BroadcastMeta.parse("com.android.music.metachanged", mapOf("track" to "Uno", "package" to "x.one"))!!
        val b = BroadcastMeta.parse("com.android.music.playstatechanged", mapOf("playing" to true, "package" to "x.two"))!!
        assertNull(b.over(a).title)
    }

    @Test
    fun everyActionIsUnique() {
        assertEquals(BroadcastMeta.ACTIONS.size, BroadcastMeta.ACTIONS.toSet().size)
    }
}
