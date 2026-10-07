package com.trailblazer.core.io

class XmlFormatException(message: String) : Exception(message)

/**
 * A small streaming XML tokenizer for GPX/KML. It never resolves DTDs or external entities
 * (so XXE and billion-laughs payloads are inert), and it bounds name, text and total-event sizes.
 * Only the five predefined entities and numeric character references are decoded.
 */
class XmlPull(private val reader: Reader, private val maxEvents: Long = 5_000_000L) {
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

    private fun peek(): Int {
        if (pos >= len) {
            len = reader.read(buf)
            pos = 0
            if (len <= 0) { len = 0; return -1 }
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

    fun next(): Event {
        if (++events > maxEvents) throw XmlFormatException("document too large")
        pendingEnd?.let { pendingEnd = null; return Event.End(it) }
        while (true) {
            val c = peek()
            if (c < 0) return Event.EndDocument
            if (c != '<'.code) return Event.Text(readText())
            read()
            when (peek()) {
                '?'.code -> skipUntil("?>")
                '!'.code -> {
                    read()
                    when (peek()) {
                        '-'.code -> { expect("--"); skipUntil("-->") }
                        '['.code -> { expect("[CDATA["); return Event.Text(readCdata()) }
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

    private fun readCdata(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) throw XmlFormatException("unterminated CDATA")
            sb.append(c.toChar())
            if (sb.length > MAX_TEXT) throw XmlFormatException("text too long")
            if (sb.endsWith("]]>")) return sb.substring(0, sb.length - 3)
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

    private fun readText(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == '<'.code) break
            sb.append(read().toChar())
            if (sb.length > MAX_TEXT) throw XmlFormatException("text too long")
        }
        return decodeEntities(sb.toString())
    }

    companion object {
        const val MAX_NAME = 256
        const val MAX_TEXT = 1_000_000

        fun decodeEntities(s: String): String {
            if (s.indexOf('&') < 0) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '&') { out.append(c); i++; continue }
                val semi = s.indexOf(';', i)
                if (semi < 0 || semi - i > 12) throw XmlFormatException("bad entity")
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
