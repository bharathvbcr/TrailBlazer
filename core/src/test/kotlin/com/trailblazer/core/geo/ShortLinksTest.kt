package com.trailblazer.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortLinksTest {
    private val placeUrl = "https://www.google.com/maps/place/Eiffel+Tower/@48.8583701,2.2919064,17z/data=!3m1!4b1!4m6!3m5!1s0x47e66e2964e34e2d:0x8ddca9ee380ef7e0!8m2!3d48.8583701!4d2.2944813!16zL20vMDJqODE?entry=ttu"

    /** Records every URL the resolver asks about, so the test can prove only short links are ever contacted. */
    private class Hops(private val table: Map<String, String?>) : (String) -> String? {
        val asked = mutableListOf<String>()
        override fun invoke(url: String): String? {
            asked += url
            return table[url]
        }
    }

    @Test
    fun aSharedGoogleMapsLinkIsFollowedToItsPlace() {
        val hops = Hops(mapOf("https://maps.app.goo.gl/abc123" to placeUrl))
        assertEquals(ShortLinks.Resolution.Resolved(placeUrl), ShortLinks.resolve("https://maps.app.goo.gl/abc123", hops))
        assertEquals(listOf("https://maps.app.goo.gl/abc123"), hops.asked)
    }

    @Test
    fun onlyShortLinkHostsAreEverAskedAndOnlyOverHttps() {
        val hops = Hops(mapOf("https://goo.gl/maps/x" to "http://maps.app.goo.gl/y", "https://maps.app.goo.gl/y" to placeUrl))
        assertTrue(ShortLinks.resolve("http://goo.gl/maps/x", hops) is ShortLinks.Resolution.Resolved)
        assertEquals(listOf("https://goo.gl/maps/x", "https://maps.app.goo.gl/y"), hops.asked)
        hops.asked.forEach { assertTrue(it, it.startsWith("https://") && ShortLinks.isShort(it)) }
    }

    @Test
    fun consentAndRedirectInterstitialsAreUnwrappedWithoutLoadingThem() {
        val consent = "https://consent.google.com/ml?continue=" + java.net.URLEncoder.encode(placeUrl, "UTF-8") + "&gl=DE"
        val hops = Hops(mapOf("https://maps.app.goo.gl/eu" to consent))
        assertEquals(ShortLinks.Resolution.Resolved(placeUrl), ShortLinks.resolve("https://maps.app.goo.gl/eu", hops))
        assertEquals(1, hops.asked.size)
    }

    @Test
    fun relativeRedirectsResolveAgainstTheShortener() {
        val hops = Hops(mapOf("https://goo.gl/a" to "/b", "https://goo.gl/b" to placeUrl))
        assertEquals(ShortLinks.Resolution.Resolved(placeUrl), ShortLinks.resolve("https://goo.gl/a", hops))
    }

    @Test
    fun loopsDeadLinksAndNonShortLinksStop() {
        val loop = Hops(mapOf("https://goo.gl/a" to "https://goo.gl/b", "https://goo.gl/b" to "https://goo.gl/a"))
        assertEquals(ShortLinks.Resolution.Failed(ShortLinks.Failure.TooManyHops), ShortLinks.resolve("https://goo.gl/a", loop))
        assertEquals(ShortLinks.MAX_HOPS, loop.asked.size)

        assertEquals(ShortLinks.Resolution.Failed(ShortLinks.Failure.NoRedirect), ShortLinks.resolve("https://maps.app.goo.gl/gone", Hops(emptyMap())))

        val never = Hops(emptyMap())
        for (u in listOf(placeUrl, "ftp://goo.gl/a", "", "geo:1,2")) {
            assertEquals(u, ShortLinks.Resolution.Failed(ShortLinks.Failure.NotAShortLink), ShortLinks.resolve(u, never))
        }
        assertTrue(never.asked.isEmpty())
    }

    @Test
    fun theLinkIsFoundInsideWhatAMapsAppShares() {
        val shared = "Eiffel Tower\nAv. Gustave Eiffel, 75007 Paris, France\nhttps://maps.app.goo.gl/abc123."
        assertEquals("https://maps.app.goo.gl/abc123", ShortLinks.find(shared))
        assertNull(ShortLinks.find("Look at https://example.com/x"))
        assertNull(ShortLinks.find(""))
    }

    @Test
    fun aResolvedLinkIsParsedOfflineAndNamedFromTheSharedText() {
        val shared = "Eiffel Tower\nAv. Gustave Eiffel, 75007 Paris\nhttps://maps.app.goo.gl/abc123"
        assertEquals(CoordinateParse.Refused(RefusalReason.ShortLinkNeedsNetwork), CoordinateParser.parse(shared))
        val parsed = CoordinateParser.parseResolved(shared, placeUrl) as CoordinateParse.Found
        assertEquals(48.8583701, parsed.places.single().position.lat, 1e-9)
        assertEquals(2.2944813, parsed.places.single().position.lon, 1e-9)
        // The link names the place itself here, so its name wins over the shared text's first line.
        assertEquals("Eiffel Tower", parsed.places.single().label)

        val pin = CoordinateParser.parseResolved("Dropped pin\nhttps://maps.app.goo.gl/p", "https://maps.google.com/?q=46.5582,7.8352&entry=gps") as CoordinateParse.Found
        assertEquals("Dropped pin", pin.places.single().label)
        // A named place with no coordinates in the link stays an honest refusal.
        assertEquals(
            CoordinateParse.Refused(RefusalReason.NoCoordinatesInLink),
            CoordinateParser.parseResolved("Cafe\nhttps://maps.app.goo.gl/c", "https://maps.google.com/maps?q=Cafe&ftid=0x1:0x2&entry=gps"),
        )
        assertEquals(CoordinateParse.NotRecognized, CoordinateParser.parseResolved("x", "javascript:alert(1)"))
    }

    @Test
    fun sharedTextWithAFullLinkIsReadWithoutAnyLookup() {
        // Before: text that did not start with the link was "Not recognised".
        val shared = "Jungfraujoch\nhttps://www.google.com/maps/place/Jungfraujoch/@46.5475,7.9827,15z/data=!3d46.547497!4d7.985275"
        val found = CoordinateParser.parse(shared) as CoordinateParse.Found
        assertEquals(46.547497, found.places.single().position.lat, 1e-9)
        val osm = CoordinateParser.parse("Summit\nhttps://www.openstreetmap.org/?mlat=46.5582&mlon=7.8352#map=15/46.5582/7.8352") as CoordinateParse.Found
        assertEquals("Summit", osm.places.single().label)
        // A link followed by more words on the same line still parses.
        assertTrue(CoordinateParser.parse("https://www.openstreetmap.org/?mlat=46.5&mlon=7.8 see you there") is CoordinateParse.Found)
    }

    @Test
    fun aLinkThatOnlyNamesAPlaceGivesTheNameToSearchFor() {
        assertEquals("Eiffel Tower", CoordinateParser.placeQuery("https://maps.google.com/maps?q=Eiffel+Tower&ftid=0x47e66e2964e34e2d:0x8ddca9ee380ef7e0&entry=gps"))
        assertEquals("Café de Flore, Paris", CoordinateParser.placeQuery("https://www.google.com/maps/search/Caf%C3%A9+de+Flore,+Paris"))
        assertNull("coordinates are used, not searched", CoordinateParser.placeQuery(placeUrl))
        assertNull(CoordinateParser.placeQuery("https://maps.google.com/?q=46.5582,7.8352"))
        assertNull(CoordinateParser.placeQuery("https://maps.google.com/maps?q=X"))
        assertNull(CoordinateParser.placeQuery("geo:0,0?q=Cafe"))
        assertNull(CoordinateParser.placeQuery("not a link"))
    }
}
