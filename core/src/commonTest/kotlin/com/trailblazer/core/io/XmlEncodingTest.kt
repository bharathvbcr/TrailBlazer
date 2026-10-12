package com.trailblazer.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XmlEncodingTest {
    private fun ascii(s: String) = s.encodeToByteArray()
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun decl(encoding: String?) = ascii("<?xml version=\"1.0\"${encoding?.let { " encoding=\"$it\"" } ?: ""}?><gpx/>")

    @Test
    fun byteOrderMarksDecideFirst() {
        assertEquals(XmlEncoding("UTF-8", 3), XmlEncoding.detect(bytes(0xEF, 0xBB, 0xBF) + decl("UTF-8")))
        assertEquals(XmlEncoding("UTF-16LE", 2), XmlEncoding.detect(bytes(0xFF, 0xFE, 0x3C, 0x00)))
        assertEquals(XmlEncoding("UTF-16BE", 2), XmlEncoding.detect(bytes(0xFE, 0xFF, 0x00, 0x3C)))
        assertEquals(XmlEncoding("UTF-32BE", 4), XmlEncoding.detect(bytes(0x00, 0x00, 0xFE, 0xFF, 0, 0, 0, 0x3C)))
        assertEquals(XmlEncoding("UTF-32LE", 4), XmlEncoding.detect(bytes(0xFF, 0xFE, 0x00, 0x00, 0x3C, 0, 0, 0)))
        // A mark beats a contradicting declaration.
        assertEquals(XmlEncoding("UTF-16LE", 2), XmlEncoding.detect(bytes(0xFF, 0xFE) + decl("ISO-8859-1")))
    }

    @Test
    fun sixteenBitMarkupWithoutAMarkIsRecognised() {
        assertEquals(XmlEncoding("UTF-16LE"), XmlEncoding.detect(bytes(0x3C, 0x00, 0x3F, 0x00, 0x78, 0x00)))
        assertEquals(XmlEncoding("UTF-16BE"), XmlEncoding.detect(bytes(0x00, 0x3C, 0x00, 0x3F, 0x00, 0x78)))
    }

    @Test
    fun declarationNamesTheEncodingCaseInsensitivelyWithAliases() {
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(decl(null)))
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(decl("utf-8")))
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(decl("UTF8")))
        assertEquals(XmlEncoding("ISO-8859-1"), XmlEncoding.detect(decl("ISO-8859-1")))
        assertEquals(XmlEncoding("ISO-8859-1"), XmlEncoding.detect(decl("Latin1")))
        assertEquals(XmlEncoding("ISO-8859-15"), XmlEncoding.detect(decl("iso-8859-15")))
        assertEquals(XmlEncoding("US-ASCII"), XmlEncoding.detect(decl("us-ascii")))
        assertEquals(XmlEncoding("windows-1252"), XmlEncoding.detect(decl("Windows-1252")))
        assertEquals(XmlEncoding("windows-1252"), XmlEncoding.detect(decl("cp1252")))
        assertEquals(XmlEncoding("ISO-8859-1"), XmlEncoding.detect(ascii("<?xml version='1.0' encoding='ISO-8859-1' standalone='yes'?><gpx/>")))
        assertEquals(XmlEncoding("ISO-8859-1"), XmlEncoding.detect(ascii("\n<?xml version=\"1.0\"\n   encoding = \"ISO-8859-1\" ?><gpx/>")))
    }

    @Test
    fun noDeclarationMeansUtf8() {
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(ascii("<gpx/>")))
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(ByteArray(0)))
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(bytes(0x3C)))
        // An "encoding" that is not in the declaration (text further in) is not read.
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(ascii("<gpx><name>encoding=\"EBCDIC-WEIRD\"</name></gpx>")))
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(ascii("<?xml version=\"1.0\"?><!-- <?xml encoding=\"koi8-r\"?> --><gpx/>")))
    }

    @Test
    fun unsupportedEncodingsAreRefusedWithAClearMessage() {
        for (name in listOf("EBCDIC", "KOI8-R", "Shift_JIS", "x-unknown", "")) {
            val e = assertFailsWith<XmlFormatException>(name) { XmlEncoding.detect(decl(name)) }
            assertTrue(e.message!!.contains("unsupported encoding"), e.message)
        }
        assertTrue(assertFailsWith<XmlFormatException> { XmlEncoding.detect(decl("A".repeat(3_000))) }.message!!.length < 200)
    }

    @Test
    fun aSixteenOrThirtyTwoBitDeclarationThatTheBytesContradictIsRefused() {
        for (name in listOf("UTF-16", "utf-16le", "UTF-16BE", "UTF-32", "UCS-2")) {
            val e = assertFailsWith<XmlFormatException>(name) { XmlEncoding.detect(decl(name)) }
            assertTrue(e.message!!.contains("not stored in it"), e.message)
        }
    }

    @Test
    fun onlyTheFirstBytesAreExamined() {
        val padded = ascii("<gpx/>") + ByteArray(10_000) + decl("EBCDIC")
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(padded))
        // [length] limits the view even when the array is longer.
        assertEquals(XmlEncoding("UTF-8"), XmlEncoding.detect(decl("EBCDIC"), length = 3))
    }
}
