package com.trailblazer.core.io

actual typealias Reader = java.io.Reader
actual typealias Writer = java.io.Writer
actual typealias StringReader = java.io.StringReader
actual typealias StringWriter = java.io.StringWriter

actual fun decompressGzip(bytes: ByteArray, maxOutputBytes: Int): ByteArray {
    if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return bytes
    val out = java.io.ByteArrayOutputStream(minOf(maxOf(bytes.size * 4, 1024), maxOutputBytes))
    val buf = ByteArray(8192)
    java.util.zip.GZIPInputStream(bytes.inputStream()).use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size().toLong() + n > maxOutputBytes) {
                throw DecompressionLimitException("gzip output exceeds $maxOutputBytes bytes")
            }
            out.write(buf, 0, n)
        }
    }
    return out.toByteArray()
}
