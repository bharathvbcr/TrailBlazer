package com.trailblazer.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Streaming limits that need a document too big to hold in a test: the reader below generates it on demand, so the
 * test itself uses almost no memory. It lives in the JVM tests because a Reader subclass is platform API.
 */
class GeoFormatsStreamingJvmTest {
    private fun lazyReader(parts: Sequence<String>): java.io.Reader = object : java.io.Reader() {
        private val iterator = parts.iterator()
        private var current = ""
        private var pos = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            while (pos >= current.length) {
                if (!iterator.hasNext()) return -1
                current = iterator.next()
                pos = 0
            }
            val n = minOf(len, current.length - pos)
            current.toCharArray(cbuf, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() {}
    }

    private fun lineStringKml(tuples: Int, perTuple: (Int) -> String): Sequence<String> = sequence {
        yield("<kml><Placemark><name>Line</name><LineString><coordinates>\n")
        for (i in 0 until tuples) yield(perTuple(i))
        yield("</coordinates></LineString></Placemark></kml>")
    }

    @Test
    fun coordinateListOfSixtyMillionCharactersIsStreamed() {
        // 1_000 points, each followed by 60_000 spaces: about 60 million characters in one <coordinates> element,
        // past the 40-million-character cap the reader had before it streamed coordinates.
        val padding = " ".repeat(60_000)
        var chars = 0L
        val parts = lineStringKml(1_000) { "7.0,46.0,1.0$padding" }.map { chars += it.length; it }
        val doc = KmlReader.read(lazyReader(parts))
        assertTrue(chars > 60_000_000L, "document was $chars chars")
        assertEquals(1_000, doc.routes.single().points.size)
    }

    @Test
    fun anEndlessCoordinateListStopsAtThePointLimit() {
        val endless = sequenceOf("<kml><Placemark><LineString><coordinates>") + generateSequence { "7.0,46.0\n" }
        val e = assertFailsWith<XmlFormatException> { KmlReader.read(lazyReader(endless), maxPoints = 1_000) }
        assertTrue(e.message!!.contains("more than 1000 points"), e.message)
    }

    @Test
    fun aMillionPointDocumentIsAcceptedAndOneMoreIsRefused() {
        val tuple = "7.123456,46.123456,1234.5\n"
        // Only the count is kept, so the first document can be collected before the second is read.
        assertEquals(1_000_000, KmlReader.read(lazyReader(lineStringKml(1_000_000) { tuple })).routes.single().points.size)
        val e = assertFailsWith<XmlFormatException> { KmlReader.read(lazyReader(lineStringKml(1_000_001) { tuple })) }
        assertTrue(e.message!!.contains("more than 1000000 points"), e.message)
    }
}
