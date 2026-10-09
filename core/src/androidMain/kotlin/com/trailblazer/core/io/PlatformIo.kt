package com.trailblazer.core.io

actual typealias Reader = java.io.Reader
actual typealias Writer = java.io.Writer
actual typealias StringReader = java.io.StringReader
actual typealias StringWriter = java.io.StringWriter

actual fun decompressGzip(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return bytes
    return java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
