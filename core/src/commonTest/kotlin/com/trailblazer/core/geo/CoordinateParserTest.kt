package com.trailblazer.core.geo

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinateParserTest {
    private fun one(s: String): ParsedPlace {
        val r = CoordinateParser.parse(s)
        assertIs<CoordinateParse.Found>(r, "'$s' → $r")
        return r.places.single()
    }

    private fun assertAt(s: String, lat: Double, lon: Double, label: String? = null) {
        val p = one(s)
        assertEquals(lat, p.position.lat, 1e-4, "$s lat")
        assertEquals(lon, p.position.lon, 1e-4, "$s lon")
        if (label != null) assertEquals(label, p.label)
    }

    @Test
    fun decimalAndDmsText() {
        assertAt("39.7392, -104.9903", 39.7392, -104.9903)
        assertAt("39.7392 -104.9903", 39.7392, -104.9903)
        assertAt("-33.8688;151.2093", -33.8688, 151.2093)
        assertAt("39.7392N 104.9903W", 39.7392, -104.9903)
        assertAt("N 39.7392 W 104.9903", 39.7392, -104.9903)
        assertAt("40°26'46\"N 79°58'56\"W", 40.446111, -79.982222)
        assertAt("40° 26′ 46″ N, 79° 58′ 56″ W", 40.446111, -79.982222)
        assertAt("40 26 46 N 79 58 56 W", 40.446111, -79.982222)
        assertAt("40°26.767'N 79°58.933'W", 40.446117, -79.982217)
        assertAt("79°58'56\"W 40°26'46\"N", 40.446111, -79.982222)
    }

    @Test
    fun geoUris() {
        assertAt("geo:37.786971,-122.399677", 37.786971, -122.399677)
        assertAt("geo:37.786971,-122.399677;u=35", 37.786971, -122.399677)
        assertAt("geo:0,0?q=37.786971,-122.399677(Trail%20Head)", 37.786971, -122.399677, "Trail Head")
        assertEquals(CoordinateParse.Refused(RefusalReason.NoCoordinatesInLink), CoordinateParser.parse("geo:0,0?q=Coffee+near+me"))
    }

    @Test
    fun googleLinks() {
        assertAt("https://www.google.com/maps/@46.5582,7.8352,14z", 46.5582, 7.8352)
        assertAt("https://maps.google.com/?q=46.5582,7.8352", 46.5582, 7.8352)
        assertAt("https://www.google.com/maps/search/?api=1&query=46.5582%2C7.8352", 46.5582, 7.8352)
        // Place link: the pin (!3d!4d) wins over the viewport (@).
        assertAt(
            "https://www.google.com/maps/place/Lauterbrunnen/@46.60,7.90,13z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d46.5935!4d7.9091",
            46.5935, 7.9091, "Lauterbrunnen",
        )
        val dir = CoordinateParser.parse("https://www.google.com/maps/dir/46.1,7.1/46.2,7.2/46.3,7.3/@46.2,7.2,10z") as CoordinateParse.Found
        assertEquals(listOf(46.1, 46.2, 46.3), dir.places.map { it.position.lat })
        val api = CoordinateParser.parse(
            "https://www.google.com/maps/dir/?api=1&origin=46.1,7.1&destination=46.3,7.3&waypoints=46.2,7.2%7C46.25,7.25",
        ) as CoordinateParse.Found
        assertEquals(listOf(46.1, 46.2, 46.25, 46.3), api.places.map { it.position.lat })
    }

    @Test
    fun osmAndAppleLinks() {
        assertAt("https://www.openstreetmap.org/?mlat=51.4779&mlon=-0.0015#map=15/51.4779/-0.0015", 51.4779, -0.0015)
        assertAt("https://www.openstreetmap.org/#map=12/51.4779/-0.0015", 51.4779, -0.0015)
        val route = CoordinateParser.parse("https://www.openstreetmap.org/directions?engine=fossgis_osrm_car&route=51.1%2C-0.1%3B51.2%2C-0.2") as CoordinateParse.Found
        assertEquals(2, route.places.size)
        assertAt("https://maps.apple.com/?ll=50.894967,4.341626&q=Atomium", 50.894967, 4.341626, "Atomium")
    }

    @Test
    fun shortLinksAreRefusedNotResolved() {
        for (s in listOf("https://maps.app.goo.gl/abcdEF123", "https://goo.gl/maps/xyz", "https://osm.org/go/0EEQjE--")) {
            assertEquals(CoordinateParse.Refused(RefusalReason.ShortLinkNeedsNetwork), CoordinateParser.parse(s), s)
        }
    }

    @Test
    fun rejectsGarbageAndOutOfRange() {
        for (s in listOf("", "hello", "91, 0", "0, 181", "40 N 50 N", "12.3", "1e5, 2", "a".repeat(5000), "-40°26'46\"S 10 E")) {
            assertEquals(CoordinateParse.NotRecognized, CoordinateParser.parse(s), s.take(20))
        }
        assertEquals(CoordinateParse.Refused(RefusalReason.NoCoordinatesInLink), CoordinateParser.parse("https://www.google.com/maps/search/pizza"))
    }
}

