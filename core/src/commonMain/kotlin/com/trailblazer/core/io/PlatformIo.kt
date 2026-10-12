package com.trailblazer.core.io

expect abstract class Reader protected constructor() {
    open fun read(cbuf: CharArray): Int
    abstract fun close()
}

expect abstract class Writer protected constructor() {
    abstract fun write(cbuf: CharArray, off: Int, len: Int)
    open fun write(str: String)
    abstract fun flush()
    abstract fun close()
}

expect class StringReader(s: String) : Reader {
    override fun read(cbuf: CharArray): Int
    override fun close()
}

expect class StringWriter() : Writer {
    override fun write(cbuf: CharArray, off: Int, len: Int)
    override fun write(str: String)
    override fun flush()
    override fun close()
    override fun toString(): String
}

/** Thrown by [decompressGzip] when the output would exceed the caller's limit (a gzip bomb). */
class DecompressionLimitException(message: String) : IllegalStateException(message)

/** Thrown by [decompressGzip] on a platform that cannot inflate gzip (today: Apple) when given gzip data. */
class GzipUnsupportedException(message: String) : UnsupportedOperationException(message)

/**
 * Inflates [bytes] when they start with the gzip magic and returns them unchanged otherwise. Apple targets have no
 * inflater here: gzip input throws [GzipUnsupportedException] rather than being passed on still compressed.
 * Output is bounded: more than [maxOutputBytes] inflated bytes throws [DecompressionLimitException] before the rest is
 * produced, so a tiny hostile archive cannot exhaust memory. Corrupt streams throw an IO exception.
 */
expect fun decompressGzip(bytes: ByteArray, maxOutputBytes: Int): ByteArray
