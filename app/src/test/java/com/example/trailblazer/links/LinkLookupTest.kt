package com.example.trailblazer.links

import com.trailblazer.core.geo.CoordinateParse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LinkLookupTest {
    private val place = "https://www.google.com/maps/place/Jungfraujoch/@46.54,7.98,15z/data=!3d46.547497!4d7.985275"

    @Test
    fun aSharedShortLinkBecomesAPlace() = runTest {
        val asked = mutableListOf<String>()
        val r = LinkLookup { url -> asked += url; if (url == "https://maps.app.goo.gl/j") place else null }
            .resolve("Jungfraujoch\nhttps://maps.app.goo.gl/j") as LinkLookupResult.Parsed
        val found = r.parse as CoordinateParse.Found
        assertEquals(46.547497, found.places.single().position.lat, 1e-9)
        assertEquals("Jungfraujoch", found.places.single().label)
        assertEquals(listOf("https://maps.app.goo.gl/j"), asked)
    }

    @Test
    fun nothingIsSentWithoutAShortLink() = runTest {
        var calls = 0
        val r = LinkLookup { calls++; null }.resolve("46.5, 7.8 and https://example.com/x")
        assertTrue(r is LinkLookupResult.Failed)
        assertEquals(0, calls)
    }

    @Test
    fun failuresBecomeMessagesNotCrashes() = runTest {
        val offline = LinkLookup { throw IOException("Unable to resolve host") }.resolve("https://maps.app.goo.gl/x") as LinkLookupResult.Failed
        assertTrue(offline.message, offline.message.contains("offline"))
        val dead = LinkLookup { null }.resolve("https://maps.app.goo.gl/x") as LinkLookupResult.Failed
        assertTrue(dead.message, dead.message.contains("expired"))
        val loop = LinkLookup { url -> if (url.endsWith("a")) "https://goo.gl/b" else "https://goo.gl/a" }.resolve("https://goo.gl/a") as LinkLookupResult.Failed
        assertTrue(loop.message, loop.message.contains("redirecting"))
        val refused = LinkLookup { throw IllegalArgumentException("not a short link") }.resolve("https://goo.gl/a")
        assertTrue(refused is LinkLookupResult.Failed)
    }

    @Test
    fun theRealProbeRefusesAnythingButAShortLinkOverHttps() {
        val probe = UrlConnectionRedirectProbe()
        for (bad in listOf("http://maps.app.goo.gl/x", "https://example.com/x", "https://maps.app.goo.gl.evil.example/x")) {
            try {
                probe.location(bad)
                throw AssertionError("connected to $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
