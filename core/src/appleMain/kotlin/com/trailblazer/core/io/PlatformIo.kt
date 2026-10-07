package com.trailblazer.core.io

actual abstract class Reader protected actual constructor() {
    actual open fun read(cbuf: CharArray): Int = -1
    actual abstract fun close()
}

actual abstract class Writer protected actual constructor() {
    actual abstract fun write(cbuf: CharArray, off: Int, len: Int)
    actual open fun write(str: String) {
        val chars = str.toCharArray()
        write(chars, 0, chars.size)
    }
    actual abstract fun flush()
    actual abstract fun close()
}

actual class StringReader actual constructor(private val s: String) : Reader() {
    private var pos = 0

    actual override fun read(cbuf: CharArray): Int {
        if (pos >= s.length) return -1
        val count = minOf(cbuf.size, s.length - pos)
        for (i in 0 until count) {
            cbuf[i] = s[pos + i]
        }
        pos += count
        return count
    }

    actual override fun close() {}
}

actual class StringWriter actual constructor() : Writer() {
    private val sb = StringBuilder()

    actual override fun write(cbuf: CharArray, off: Int, len: Int) {
        for (i in 0 until len) {
            sb.append(cbuf[off + i])
        }
    }

    actual override fun write(str: String) {
        sb.append(str)
    }

    actual override fun flush() {}

    actual override fun close() {}

    actual override fun toString(): String = sb.toString()
}
