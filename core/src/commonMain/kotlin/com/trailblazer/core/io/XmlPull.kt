package com.trailblazer.core.io

class XmlFormatException(message: String) : Exception(message)

/**
 * A small streaming XML tokenizer for GPX/KML. It never resolves DTDs or external entities
 * (so XXE and billion-laughs payloads are inert), and it bounds name and total-event sizes.
 * Only the five predefined entities and numeric character references are decoded.
 *
 * Character data arrives as a run of [Event.Text] events of about [TEXT_CHUNK] chars each (an entity or surrogate
 * pair is never split), so a consumer that streams a very long value, such as a KML `<coordinates>` list, needs no
 * memory proportional to its length. A consumer that buffers a value bounds the buffer with [appendBounded]. The
 * whole document is bounded too: reading more than [maxChars] characters fails with an [XmlFormatException].
 */
class XmlPull(
    private val reader: Reader,
    private val maxEvents: Long = 5_000_000L,
    private val maxChars: Long = DEFAULT_MAX_CHARS,
) {
    sealed interface Event {
        data class Start(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Event
        data class End(val name: String) : Event
        data class Text(val text: String) : Event
        data object EndDocument : Event
    }

    private val buf = CharArray(8192)
    private var len = 0
    private var pos = 0
    private var events = 0L
    private var pendingEnd: String? = null
    private var inCdata = false
    private var cdataCarry = ""
    private var charsRead = 0L

    private fun peek(): Int {
        if (pos >= len) {
            len = reader.read(buf)
            pos = 0
            if (len <= 0) { len = 0; return -1 }
            charsRead += len
            if (charsRead > maxChars) throw XmlFormatException("document larger than $maxChars characters")
        }
        return buf[pos].code
    }

    private fun read(): Int = peek().also { if (it >= 0) pos++ }

    private fun expect(s: String) {
        for (c in s) if (read() != c.code) throw XmlFormatException("expected '$s'")
    }

    private fun skipUntil(terminator: String, limit: Int = 10_000_000) {
        val window = CharArray(terminator.length)
        var n = 0
        while (true) {
            val c = read()
            if (c < 0) throw XmlFormatException("unterminated construct")
            if (++n > limit) throw XmlFormatException("construct too long")
            window.copyInto(window, 0, 1, window.size)
            window[window.size - 1] = c.toChar()
            if (n >= window.size && window.concatToString() == terminator) return
        }
    }

    private fun readProcessingInstructionAttrs(): Map<String, String> {
        val attrs = HashMap<String, String>()
        while (true) {
            skipWs()
            val c = peek()
            if (c < 0) throw XmlFormatException("unterminated processing instruction")
            if (c == '?'.code) {
                read()
                if (read() != '>'.code) throw XmlFormatException("expected '?>'")
                break
            }
            val an = readName()
            skipWs()
            if (read() != '='.code) throw XmlFormatException("attribute without value")
            skipWs()
            val q = read()
            if (q != '"'.code && q != '\''.code) throw XmlFormatException("unquoted attribute")
            val sb = StringBuilder()
            while (true) {
                val ch = read()
                if (ch < 0) throw XmlFormatException("unterminated attribute")
                if (ch == q) break
                sb.append(ch.toChar())
                if (sb.length > MAX_NAME) throw XmlFormatException("attribute too long")
            }
            attrs[an] = sb.toString()
        }
        return attrs
    }

    fun next(): Event {
        if (++events > maxEvents) throw XmlFormatException("document too large")
        pendingEnd?.let { pendingEnd = null; return Event.End(it) }
        if (inCdata) return Event.Text(readCdataChunk())
        while (true) {
            val c = peek()
            if (c < 0) return Event.EndDocument
            if (c == 0xFEFF) { read(); continue }
            if (c != '<'.code) return Event.Text(readText())
            read()
            when (peek()) {
                '?'.code -> {
                    read()
                    val target = readName()
                    if (target.equals("xml", ignoreCase = true)) {
                        // Read only to check the declaration is well formed: the bytes are decoded before they get
                        // here, so its encoding is XmlEncoding's business, not ours.
                        readProcessingInstructionAttrs()
                    } else {
                        skipUntil("?>")
                    }
                }
                '!'.code -> {
                    read()
                    when (peek()) {
                        '-'.code -> { expect("--"); skipUntil("-->") }
                        '['.code -> { expect("[CDATA["); inCdata = true; return Event.Text(readCdataChunk()) }
                        // DOCTYPE and any internal subset are skipped, never interpreted.
                        else -> skipDoctype()
                    }
                }
                '/'.code -> {
                    read()
                    val name = readName()
                    skipWs()
                    if (read() != '>'.code) throw XmlFormatException("malformed end tag")
                    return Event.End(name)
                }
                else -> return readStartTag()
            }
        }
    }

    private fun skipDoctype() {
        var depth = 0
        var n = 0
        while (true) {
            val c = read()
            if (c < 0) throw XmlFormatException("unterminated doctype")
            if (++n > 1_000_000) throw XmlFormatException("doctype too long")
            when (c) {
                '['.code -> depth++
                ']'.code -> depth--
                '>'.code -> if (depth <= 0) return
            }
        }
    }

    /** The next piece of the open CDATA section; closes the section when it reaches `]]>`. */
    private fun readCdataChunk(): String {
        val sb = StringBuilder(cdataCarry)
        cdataCarry = ""
        while (true) {
            val c = read()
            if (c < 0) throw XmlFormatException("unterminated CDATA")
            sb.append(c.toChar())
            if (sb.endsWith("]]>")) {
                inCdata = false
                return sb.substring(0, sb.length - 3)
            }
            // Hold back the last two chars, which may be the start of the terminator; and one more when the cut
            // would fall between the halves of a surrogate pair.
            if (sb.length >= TEXT_CHUNK + 2) {
                val keep = if (sb[sb.length - 3].isHighSurrogate()) 3 else 2
                cdataCarry = sb.substring(sb.length - keep)
                return sb.substring(0, sb.length - keep)
            }
        }
    }

    private fun skipWs() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) read() else return
        }
    }

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == ' '.code || c == '>'.code || c == '/'.code || c == '='.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
            sb.append(read().toChar())
            if (sb.length > MAX_NAME) throw XmlFormatException("name too long")
        }
        if (sb.isEmpty()) throw XmlFormatException("empty name")
        return sb.toString()
    }

    private fun readStartTag(): Event.Start {
        val name = readName()
        val attrs = HashMap<String, String>()
        while (true) {
            skipWs()
            when (peek()) {
                '/'.code -> {
                    read()
                    if (read() != '>'.code) throw XmlFormatException("malformed empty tag")
                    pendingEnd = name
                    return Event.Start(name, attrs, true)
                }
                '>'.code -> { read(); return Event.Start(name, attrs, false) }
                -1 -> throw XmlFormatException("unterminated tag")
                else -> {
                    val an = readName()
                    skipWs()
                    if (read() != '='.code) throw XmlFormatException("attribute without value")
                    skipWs()
                    val q = read()
                    if (q != '"'.code && q != '\''.code) throw XmlFormatException("unquoted attribute")
                    val sb = StringBuilder()
                    while (true) {
                        val c = read()
                        if (c < 0) throw XmlFormatException("unterminated attribute")
                        if (c == q) break
                        sb.append(c.toChar())
                        if (sb.length > MAX_TEXT) throw XmlFormatException("attribute too long")
                    }
                    if (attrs.size > 64) throw XmlFormatException("too many attributes")
                    attrs[an] = decodeEntities(sb.toString())
                }
            }
        }
    }

    /** Character data up to the next tag, or [TEXT_CHUNK] chars of it, whichever comes first. */
    private fun readText(): String {
        val sb = StringBuilder()
        var amp = -1
        while (true) {
            val c = peek()
            if (c < 0 || c == '<'.code) break
            if (sb.length >= TEXT_CHUNK) {
                // Never cut inside an entity (unless it is already too long to be one) or a surrogate pair.
                val openEntity = amp >= 0 && sb.length - amp <= MAX_ENTITY
                if (!openEntity && !sb.last().isHighSurrogate()) break
            }
            val ch = read().toChar()
            if (ch == '&') amp = sb.length else if (ch == ';') amp = -1
            sb.append(ch)
        }
        return decodeEntities(sb.toString())
    }

    companion object {
        const val MAX_NAME = 256

        /** Characters per [Event.Text]; longer character data is delivered as several events. */
        const val TEXT_CHUNK = 32_768

        /** Longest free text (a name or description) a consumer buffers; longer text is cut here. */
        const val MAX_TEXT = 1_000_000

        /** Longest value (a number, timestamp or coordinate tuple) a consumer accepts; longer is an error. */
        const val MAX_VALUE = 256

        /** Characters one document may contain before it is refused, whatever its structure. */
        const val DEFAULT_MAX_CHARS = 400_000_000L

        private const val MAX_ENTITY = 12

        /**
         * Appends [chunk] to [sb] without letting it grow past [limit] chars, never ending on the first half of a
         * surrogate pair. Returns true when the whole chunk fit, false when some of it was cut.
         */
        fun appendBounded(sb: StringBuilder, chunk: String, limit: Int = MAX_TEXT): Boolean {
            val room = limit - sb.length
            if (chunk.length <= room) {
                sb.append(chunk)
                return true
            }
            var take = room.coerceAtLeast(0)
            if (take > 0 && chunk[take - 1].isHighSurrogate()) take--
            sb.append(chunk.substring(0, take))
            return false
        }

        fun decodeEntities(s: String): String {
            if (s.indexOf('&') < 0) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '&') { out.append(c); i++; continue }
                val semi = s.indexOf(';', i)
                if (semi < 0 || semi - i > MAX_ENTITY) throw XmlFormatException("bad entity")
                val ent = s.substring(i + 1, semi)
                when {
                    ent == "lt" -> out.append('<')
                    ent == "gt" -> out.append('>')
                    ent == "amp" -> out.append('&')
                    ent == "quot" -> out.append('"')
                    ent == "apos" -> out.append('\'')
                    ent.startsWith("#x") || ent.startsWith("#X") -> out.appendCodePoint(codePoint(ent.substring(2), 16))
                    ent.startsWith("#") -> out.appendCodePoint(codePoint(ent.substring(1), 10))
                    else -> throw XmlFormatException("undeclared entity &$ent;")
                }
                i = semi + 1
            }
            return out.toString()
        }

        private fun codePoint(digits: String, radix: Int): Int {
            val v = digits.toIntOrNull(radix) ?: throw XmlFormatException("bad character reference")
            if (v !in 1..0x10FFFF || v in 0xD800..0xDFFF) throw XmlFormatException("bad character reference")
            return v
        }

        private fun StringBuilder.appendCodePoint(cp: Int) {
            if (cp < 0x10000) {
                append(cp.toChar())
            } else {
                append(((cp - 0x10000) shr 10 or 0xD800).toChar())
                append(((cp - 0x10000) and 0x3FF or 0xDC00).toChar())
            }
        }

        /** Escapes text for element content or a quoted attribute; drops characters XML 1.0 cannot carry. */
        fun escape(s: String): String {
            val out = StringBuilder(s.length + 8)
            for (ch in s) {
                when {
                    ch == '<' -> out.append("&lt;")
                    ch == '>' -> out.append("&gt;")
                    ch == '&' -> out.append("&amp;")
                    ch == '"' -> out.append("&quot;")
                    ch == '\'' -> out.append("&apos;")
                    ch == '\t' || ch == '\n' || ch == '\r' -> out.append(ch)
                    ch.code < 0x20 || ch == '￾' || ch == '￿' -> Unit
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }
    }
}
