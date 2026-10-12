package com.trailblazer.core.io

/**
 * How to turn an XML document's bytes into characters: the [charset] name and how many leading [bomBytes] to skip.
 *
 * Detection lives here, in common code, so every platform agrees on which encodings are accepted; the platform only
 * maps [charset] to its own decoder (on Android, `Charset.forName`). It follows the XML spec's order: a byte-order
 * mark decides first, then the `encoding` of the `<?xml ?>` declaration, then UTF-8. An encoding outside the
 * supported set is refused with an [XmlFormatException] rather than decoded into garbage.
 */
data class XmlEncoding(val charset: String, val bomBytes: Int = 0) {
    companion object {
        const val UTF_8 = "UTF-8"
        const val UTF_16LE = "UTF-16LE"
        const val UTF_16BE = "UTF-16BE"
        const val UTF_32LE = "UTF-32LE"
        const val UTF_32BE = "UTF-32BE"
        const val ISO_8859_1 = "ISO-8859-1"
        const val ISO_8859_15 = "ISO-8859-15"
        const val US_ASCII = "US-ASCII"
        const val WINDOWS_1252 = "windows-1252"

        /** How many leading bytes [detect] needs to see the byte-order mark and the XML declaration. */
        const val SNIFF_BYTES = 4096

        private val DECLARATION = Regex("""^\s*<\?xml\s[^>]*?\bencoding\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        private val SINGLE_BYTE = mapOf(
            "utf-8" to UTF_8, "utf8" to UTF_8,
            "iso-8859-1" to ISO_8859_1, "iso_8859-1" to ISO_8859_1, "iso8859-1" to ISO_8859_1, "latin1" to ISO_8859_1,
            "latin-1" to ISO_8859_1, "l1" to ISO_8859_1, "cp819" to ISO_8859_1, "ibm819" to ISO_8859_1,
            "iso-8859-15" to ISO_8859_15, "iso_8859-15" to ISO_8859_15, "latin9" to ISO_8859_15,
            "us-ascii" to US_ASCII, "ascii" to US_ASCII, "ansi_x3.4-1968" to US_ASCII,
            "windows-1252" to WINDOWS_1252, "cp1252" to WINDOWS_1252,
        )

        private val WIDE = setOf("utf-16", "utf-16le", "utf-16be", "ucs-2", "utf-32", "utf-32le", "utf-32be", "ucs-4")

        /**
         * The encoding of a document that starts with the first [length] bytes of [head].
         *
         * @throws XmlFormatException when the declared encoding is unsupported, or is a 16/32-bit one that the bytes
         * (plain ASCII markup, no byte-order mark) contradict.
         */
        fun detect(head: ByteArray, length: Int = head.size): XmlEncoding {
            val n = minOf(length, head.size)
            fun b(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1

            if (b(0) == 0x00 && b(1) == 0x00 && b(2) == 0xFE && b(3) == 0xFF) return XmlEncoding(UTF_32BE, 4)
            if (b(0) == 0xFF && b(1) == 0xFE && b(2) == 0x00 && b(3) == 0x00) return XmlEncoding(UTF_32LE, 4)
            if (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return XmlEncoding(UTF_8, 3)
            if (b(0) == 0xFE && b(1) == 0xFF) return XmlEncoding(UTF_16BE, 2)
            if (b(0) == 0xFF && b(1) == 0xFE) return XmlEncoding(UTF_16LE, 2)
            // No mark, but "<?" in 16-bit form is itself a signature.
            if (b(0) == 0x00 && b(1) == 0x3C && b(2) == 0x00 && b(3) == 0x3F) return XmlEncoding(UTF_16BE)
            if (b(0) == 0x3C && b(1) == 0x00 && b(2) == 0x3F && b(3) == 0x00) return XmlEncoding(UTF_16LE)

            val header = CharArray(minOf(n, SNIFF_BYTES)) { (head[it].toInt() and 0xFF).toChar() }.concatToString()
            val declared = DECLARATION.find(header)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.trim()
                ?: return XmlEncoding(UTF_8)
            val key = declared.lowercase()
            SINGLE_BYTE[key]?.let { return XmlEncoding(it) }
            if (key in WIDE) throw XmlFormatException("the file declares encoding '$declared' but is not stored in it")
            throw XmlFormatException("unsupported encoding '${declared.take(40)}'; use UTF-8, UTF-16, ISO-8859-1 or windows-1252")
        }
    }
}
