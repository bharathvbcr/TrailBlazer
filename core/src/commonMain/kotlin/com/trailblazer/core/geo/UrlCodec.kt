package com.trailblazer.core.geo

internal fun urlDecode(s: String): String {
    if (!s.contains('%') && !s.contains('+')) return s
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when (c) {
            '+' -> {
                bytes.add(' '.code.toByte())
                i++
            }
            '%' -> {
                if (i + 2 >= s.length) throw IllegalArgumentException("Incomplete % sequence at $i")
                val h1 = s[i + 1].digitToIntOrNull(16) ?: throw IllegalArgumentException("Illegal hex char at ${i + 1}")
                val h2 = s[i + 2].digitToIntOrNull(16) ?: throw IllegalArgumentException("Illegal hex char at ${i + 2}")
                bytes.add(((h1 shl 4) or h2).toByte())
                i += 3
            }
            else -> {
                if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    val b = s.substring(i, i + 2).encodeToByteArray()
                    for (byte in b) bytes.add(byte)
                    i += 2
                } else {
                    val b = c.toString().encodeToByteArray()
                    for (byte in b) bytes.add(byte)
                    i++
                }
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

internal fun urlEncode(s: String): String {
    val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_"
    val unreservedBytes = unreserved.encodeToByteArray().toSet()
    val sb = StringBuilder()
    val utf8 = s.encodeToByteArray()
    for (b in utf8) {
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
