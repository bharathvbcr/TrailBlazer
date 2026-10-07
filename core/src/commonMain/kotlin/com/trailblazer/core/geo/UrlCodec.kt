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
                val b = c.toString().encodeToByteArray()
                for (byte in b) bytes.add(byte)
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

internal fun urlEncode(s: String): String {
    val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-*_"
    val sb = StringBuilder()
    for (ch in s) {
        if (ch in unreserved) {
            sb.append(ch)
        } else if (ch == ' ') {
            sb.append('+')
        } else {
            val bytes = ch.toString().encodeToByteArray()
            for (b in bytes) {
                sb.append('%')
                val hex = (b.toInt() and 0xFF).toString(16).uppercase()
                if (hex.length == 1) sb.append('0')
                sb.append(hex)
            }
        }
    }
    return sb.toString()
}
