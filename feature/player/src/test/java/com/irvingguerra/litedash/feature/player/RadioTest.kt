package com.irvingguerra.litedash.feature.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    private fun entry(name: String, url: String, resolved: String = "", favicon: String = "", tags: String = "") = JSONObject()
        .put("stationuuid", "id-$name").put("name", name).put("url", url).put("url_resolved", resolved)
        .put("favicon", favicon).put("tags", tags).put("country", "Mexico").put("state", "Jalisco")
        .put("codec", "MP3").put("bitrate", 128).put("geo_lat", 20.6).put("geo_long", -103.3)

    private fun parse(vararg entries: JSONObject, cleartext: Boolean = false) =
        Radio.parse(JSONArray(entries.toList()).toString(), cleartext)

    @Test
    fun oneStationPerName() {
        val list = parse(entry("Los 40", "https://a/1"), entry("los 40 ", "https://a/2"), entry("Exa", "https://b"))
        assertEquals(listOf("Los 40", "Exa"), list.map { it.name })
        assertEquals("https://a/1", list[0].url)
    }

    @Test
    fun noPlainHttpUnlessAllowed() {
        val http = entry("Vieja", "http://c")
        assertTrue(parse(http).isEmpty())
        assertEquals(listOf("Vieja"), parse(http, cleartext = true).map { it.name })
    }

    @Test
    fun resolvedUrlWinsAndFieldsAreRead() {
        val s = parse(entry("Exa", "https://lista.pls", resolved = "https://flujo", favicon = "logo.png", tags = "pop, rock, baladas")).single()
        assertEquals("https://flujo", s.url)
        assertNull(s.logoUrl)
        assertEquals("pop, rock", s.tags)
        assertEquals("pop, rock · Jalisco", s.subtitle)
        assertEquals(20.6, s.latitude!!, 0.0)
    }

    @Test
    fun skipsWithoutNameOrUrlAndBadJson() {
        assertTrue(parse(entry(" ", "https://a"), entry("Sin flujo", "")).isEmpty())
        assertTrue(Radio.parse("<html>", cleartext = true).isEmpty())
    }

    private fun station(lat: Double? = 19.4, logo: String? = "https://logo") =
        Station("u1", "Exa FM", "https://flujo/exa.aac", logo, "pop", "Mexico", "", "AAC+", 64, lat, if (lat == null) null else -99.1)

    @Test
    fun jsonRoundTrip() {
        for (s in listOf(station(), station(lat = null, logo = null))) {
            val back = Station.fromJson(JSONObject(s.toJson().toString()))!!
            assertEquals(s.toJson().toString(), back.toJson().toString())
            assertEquals(s.key, back.key)
        }
        assertNull(Station.fromJson(JSONObject().put("name", "sin flujo")))
    }

    @Test
    fun storedListsRoundTrip() {
        val list = listOf(station(), station(lat = null))
        assertEquals(list.map { it.toJson().toString() }, stationsFromJson(stationsToJson(list)).map { it.toJson().toString() })
        assertTrue(stationsFromJson(null).isEmpty())
        assertTrue(stationsFromJson("dañado").isEmpty())
    }

    @Test
    fun subtitleWithoutData() {
        assertEquals("", Station("u", "X", "https://x", null, "", "", "", "MP3", 0, null, null).subtitle)
        assertEquals("Mexico", Station("u", "X", "https://x", null, "", "Mexico", "", "MP3", 0, null, null).subtitle)
    }

    @Test
    fun aacGoesByItsOwnDecoder() {
        assertTrue(station().isAac)
        assertFalse(Station("u", "X", "https://x/live.m3u8", null, "", "", "", "AAC", 0, null, null).isAac)
        assertFalse(Station("u", "X", "https://x", null, "", "", "", "MP3", 0, null, null).isAac)
    }
}
