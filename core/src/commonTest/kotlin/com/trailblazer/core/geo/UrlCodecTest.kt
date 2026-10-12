package com.trailblazer.core.geo

import com.trailblazer.core.intents.MapLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UrlCodecTest {
    @Test
    fun emojiEncodesAsOneFourByteUtf8SequenceAndRoundTrips() {
        assertEquals("%F0%9F%98%80", urlEncode("😀"))
        assertEquals("😀", urlDecode("%F0%9F%98%80"))
        for (s in listOf("😀", "a😀b", "👨‍👩‍👧", "日本語", "é", "𝄞 clef", "😀😀😀", "x+y z", "100%", "")) {
            assertEquals(s, urlDecode(urlEncode(s)), "round trip of '$s'")
        }
        // Two- and three-byte characters, to pin the exact bytes.
        assertEquals("%C3%A9", urlEncode("é"))
        assertEquals("%E6%97%A5", urlEncode("日"))
    }

    @Test
    fun surrogatePairsInsideLiteralTextSurviveDecoding() {
        // Literal (unescaped) emoji next to an escape and a '+': the pair must reach the encoder whole.
        assertEquals("😀 é😀", urlDecode("😀+%C3%A9😀"))
        assertEquals("a😀b", urlDecode("a%F0%9F%98%80b"))
    }

    @Test
    fun loneSurrogatesBecomeTheReplacementCharacterInsteadOfCorruptingTheRest() {
        assertEquals("%EF%BF%BD", urlEncode("\uD83D"))
        assertEquals("%EF%BF%BD", urlEncode("\uDE00"))
        assertEquals("a%EF%BF%BDb", urlEncode("a\uD83Db"))
        // A reversed pair is two lone surrogates, not an emoji.
        assertEquals("%EF%BF%BD%EF%BF%BD", urlEncode("\uDE00\uD83D"))
        // Decoding: a lone surrogate among literal text, and bytes that are not UTF-8, also give U+FFFD.
        assertEquals("� x", urlDecode("\uD83D+x"))
        assertTrue(urlDecode("%F0%9F").contains('�')) // a truncated four-byte sequence
        assertEquals("�", urlDecode("%FF"))
    }

    @Test
    fun plusIsASpaceButPercentTwentyIsToo_andALiteralPlusIsEscaped() {
        assertEquals("a+b", urlEncode("a b"))
        assertEquals("a%2Bb", urlEncode("a+b"))
        assertEquals("a b", urlDecode("a+b"))
        assertEquals("a b", urlDecode("a%20b"))
        assertEquals("a+b", urlDecode("a%2Bb"))
        assertEquals("a%2Bb+c", urlEncode("a+b c"))
        // MapLinks turns the encoder's '+' into %20 for a geo: label; a literal '+' stays %2B.
        val link = MapLinks.geo(LatLon(46.5, 7.8), "Camp 4 + 😀")
        assertTrue(link.contains("(Camp%204%20%2B%20%F0%9F%98%80)"), link)
    }

    @Test
    fun unreservedCharactersPassThroughUnescaped() {
        assertEquals("AZaz09.-*_", urlEncode("AZaz09.-*_"))
        assertEquals("%2F%3F%23%26%3D%25", urlEncode("/?#&=%"))
    }

    @Test
    fun invalidPercentSequencesAreRejected() {
        assertFailsWith<IllegalArgumentException> { urlDecode("%") }
        assertFailsWith<IllegalArgumentException> { urlDecode("abc%") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%4") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%4G") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%G4") }
        assertFailsWith<IllegalArgumentException> { urlDecode("a%zz") }
        // Only ASCII hex digits count: fullwidth and Arabic-Indic digits are not hex escapes.
        assertFailsWith<IllegalArgumentException> { urlDecode("%４１") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%٤١") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%+1") }
        assertFailsWith<IllegalArgumentException> { urlDecode("%-1") }
        assertEquals("A", urlDecode("%41"))
        assertEquals("a", urlDecode("%61"))
        assertEquals("ÿ", urlDecode("%C3%BF"))
    }

    @Test
    fun hugeInputStaysLinear() {
        val s = "😀 a+b".repeat(100_000)
        assertEquals(s, urlDecode(urlEncode(s)))
    }
}
