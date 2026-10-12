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
    fun dmsWithUnitLetters() {
        assertAt("40d26m46s, -74.0", 40.446111, -74.0)
        assertAt("40d26m46s, 74d00m20s", 40.446111, 74.005555)
        assertAt("40d26m46s N, 74d00m20s W", 40.446111, -74.005555)
        assertAt("40d26m46s S, 74d00m20s W", -40.446111, -74.005555)
        assertAt("40D26M46S, -74.0", 40.446111, -74.0)
    }

    @Test
    fun trailingSIsSecondsAfterDegreesOrMinutesAndSouthOtherwise() {
        // Seconds marker, no hemisphere: the latitude stays north (this was read as south).
        assertAt("40d26m46s 74d0m0s", 40.446111, 74.0)
        assertAt("40d26m46s, 74d0m0s", 40.446111, 74.0)
        assertAt("-40d26m46s, 74d0m0s", -40.446111, 74.0)
        assertAt("40d26m46s,74d0m0s", 40.446111, 74.0)
        // Seconds marker followed by a hemisphere letter, with and without a gap, either axis order.
        assertAt("40d26m46sN 74d0m0sW", 40.446111, -74.0)
        assertAt("40d26m46sN, 74d0m0sW", 40.446111, -74.0)
        assertAt("40d26m46s S 74d0m0s E", -40.446111, 74.0)
        assertAt("40d26m46sS 74d0m0sE", -40.446111, 74.0)
        assertAt("74d0m0sW 40d26m46sN", 40.446111, -74.0)
        assertAt("40d26m46s S, 74d0m0s W", -40.446111, -74.0)
        // Case does not matter.
        assertAt("40d26m46sn 74d0m0sw", 40.446111, -74.0)
        assertAt("40D26M46S 74D0M0S", 40.446111, 74.0)
        // 'S' as a hemisphere stays south: leading, trailing, after decimals, after minutes without seconds.
        assertAt("S 33 52, E 151 12", -33.866667, 151.2)
        assertAt("S 33 52 E 151 12", -33.866667, 151.2)
        assertAt("33.5S, 151.2E", -33.5, 151.2)
        assertAt("33.5S 151.2E", -33.5, 151.2)
        assertAt("33d52mS 151d12mE", -33.866667, 151.2)
        assertAt("33 52 S 151 12 E", -33.866667, 151.2)
        // 'S' after degrees only (no minutes marker before it) is south, never seconds.
        assertAt("33d52S, 151d12E", -33.866667, 151.2)
        assertAt("33d 52S, 151d 12E", -33.866667, 151.2)
        assertAt("33d52S 151d12E", -33.866667, 151.2)
        assertAt("33d52.5S 151d12E", -33.875, 151.2)
        // After both degrees and minutes are marked, a trailing S is the seconds marker (the documented reading).
        assertAt("33d52m10S, 151d12m30E", 33.869444, 151.208333)
        assertAt("40d26m46s, -74.0", 40.446111, -74.0)
        // A single axis is not a position, whichever way 'S' would be read.
        assertEquals(CoordinateParse.NotRecognized, CoordinateParser.parse("46S"))
        assertEquals(CoordinateParse.NotRecognized, CoordinateParser.parse("40d26m46s"))
        assertEquals(CoordinateParse.NotRecognized, CoordinateParser.parse("S 46"))
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

