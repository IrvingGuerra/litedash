package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchTest {
    // Recorte de respuestas reales de Photon: una calle, una ciudad, una gasolinera y dos sin nombre.
    private val sample = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-99.1677,19.4268]},
           "properties":{"osm_key":"highway","osm_value":"primary","type":"street","name":"Avenida de los Insurgentes Sur",
             "district":"Roma Norte","city":"Ciudad de México","state":"Ciudad de México","country":"México"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-104.8955,21.5085]},
           "properties":{"osm_key":"place","osm_value":"city","type":"city","name":"Tepic","state":"Nayarit","country":"México"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-105.4741,20.8146]},
           "properties":{"osm_key":"amenity","osm_value":"fuel","type":"house","name":"Pemex","street":"Carretera 200",
             "housenumber":"12","locality":"Higuera Blanca","city":"Bahía de Banderas","state":"Nayarit"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[-105.47,20.81]},
           "properties":{"osm_key":"place","osm_value":"house","type":"house","street":"Calle Delfín","housenumber":"5",
             "city":"Higuera Blanca","state":"Nayarit"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[1.0,2.0]},"properties":{"osm_key":"place"}}
        ]}
    """.trimIndent()

    @Test
    fun parsesStreetCityAndPlace() {
        val hits = parsePhoton(sample, null, null)!!
        assertEquals(4, hits.size)

        val street = hits[0]
        assertEquals("Avenida de los Insurgentes Sur", street.name)
        assertEquals(PlaceKind.STREET, street.kind)
        assertEquals("Roma Norte", street.locality)
        assertEquals("Ciudad de México", street.address)
        assertEquals(19.4268, street.lat, 1e-9)
        assertEquals(-99.1677, street.lon, 1e-9)
        assertNull(street.distanceKm)
        assertTrue(street.online)

        val city = hits[1]
        assertEquals(PlaceKind.CITY, city.kind)
        assertEquals(PlaceCategory.OTHER, city.category)
        assertNull(city.locality)
        assertEquals("Nayarit", city.address)

        val fuel = hits[2]
        assertEquals(PlaceKind.PLACE, fuel.kind)
        assertEquals(PlaceCategory.FUEL, fuel.category)
        assertEquals("Higuera Blanca", fuel.locality)
        assertEquals("Carretera 200 12, Bahía de Banderas, Nayarit", fuel.address)
    }

    @Test
    fun missingNameUsesStreetAndNumber() {
        val hits = parsePhoton(sample, null, null)!!
        val house = hits[3]
        assertEquals("Calle Delfín 5", house.name)
        assertEquals(PlaceKind.PLACE, house.kind)
        // Sin repetir el nombre en la dirección.
        assertEquals("Higuera Blanca, Nayarit", house.address)
    }

    @Test
    fun distanceFromWhereYouAre() {
        // Desde el Zócalo de la CDMX: Insurgentes ~3.7 km, Tepic ~600 km.
        val hits = parsePhoton(sample, 19.4326, -99.1332)!!
        assertEquals(3.7, hits[0].distanceKm!!, 0.5)
        assertTrue(hits[1].distanceKm!! in 550.0..650.0)
    }

    @Test
    fun badAnswerDoesNotCrash() {
        assertNull(parsePhoton("<html>504 Gateway Time-out</html>", null, null))
        assertNull(parsePhoton("{\"lang\":[{\"message\":\"Language is not supported\"}]}", null, null))
        assertEquals(emptyList<SearchHit>(), parsePhoton("{\"type\":\"FeatureCollection\",\"features\":[]}", null, null))
    }
}
