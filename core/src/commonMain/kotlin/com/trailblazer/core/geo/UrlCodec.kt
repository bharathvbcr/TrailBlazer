package com.trailblazer.core.geo

/**
 * [s] as UTF-8 with each lone surrogate (which has no UTF-8 form) written as U+FFFD. `encodeToByteArray` alone does not
 * agree across platforms on those: the JVM writes `?`, others write U+FFFD.
 */
private fun utf8(s: String): ByteArray {
    var clean: StringBuilder? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            clean?.append(c)?.append(s[i + 1])
            i += 2
            continue
        }
        if (c.isSurrogate()) {
            val sb = clean ?: StringBuilder(s.substring(0, i)).also { clean = it }
            sb.append('�')
        } else {
            clean?.append(c)
        }
        i++
    }
    return (clean?.toString() ?: s).encodeToByteArray()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/**
 * Decodes `application/x-www-form-urlencoded` text: `+` is a space and `%XX` is one UTF-8 byte. The whole string is
 * decoded as UTF-8, so a multi-byte sequence split over several `%XX` escapes (an emoji is four) comes back whole;
 * bytes that are not valid UTF-8 become U+FFFD. Throws [IllegalArgumentException] for a `%` not followed by two ASCII
 * hex digits.
 */
internal fun urlDecode(s: String): String {
    if (!s.contains('%') && !s.contains('+')) return s
    // Literal text is re-encoded one chunk at a time, so surrogate pairs reach the encoder whole.
    val bytes = ArrayList<Byte>(s.length)
    var literalStart = 0
    var i = 0
    fun flushLiteral(end: Int) {
        if (end > literalStart) for (b in utf8(s.substring(literalStart, end))) bytes.add(b)
    }
    while (i < s.length) {
        when (s[i]) {
            '+' -> {
                flushLiteral(i)
                bytes.add(' '.code.toByte())
                i++
                literalStart = i
            }
            '%' -> {
                if (i + 2 >= s.length) throw IllegalArgumentException("Incomplete % sequence at $i")
                val h1 = hexValue(s[i + 1])
                val h2 = hexValue(s[i + 2])
                if (h1 < 0) throw IllegalArgumentException("Illegal hex char at ${i + 1}")
                if (h2 < 0) throw IllegalArgumentException("Illegal hex char at ${i + 2}")
                flushLiteral(i)
                bytes.add(((h1 shl 4) or h2).toByte())
                i += 3
                literalStart = i
            }
            else -> i++
        }
    }
    flushLiteral(s.length)
    return bytes.toByteArray().decodeToString()
}

/**
 * Encodes [s] as `application/x-www-form-urlencoded`: the whole string as UTF-8 (so an emoji is one four-byte
 * sequence, `%F0%9F%98%80`, never two half-surrogates), unreserved `A-Za-z0-9.-*_` kept, space as `+`, every other
 * byte as `%XX`. A lone surrogate, which has no UTF-8 form, becomes U+FFFD (`%EF%BF%BD`). Callers that need `%20`
 * for a space replace `+`, which is safe because a literal `+` is always escaped to `%2B`.
 */
internal fun urlEncode(s: String): String {
    val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_"
    val unreservedBytes = unreserved.encodeToByteArray().toSet()
    val sb = StringBuilder()
    for (b in utf8(s)) {
        if (b in unreservedBytes) {
            sb.append(b.toInt().toChar())
        } else if (b == ' '.code.toByte()) {
            sb.append('+')
        } else {
            sb.append('%')
            val hex = (b.toInt() and 0xFF).toString(16).uppercase()
            if (hex.length == 1) sb.append('0')
            sb.append(hex)
        }
    }
    return sb.toString()
}
