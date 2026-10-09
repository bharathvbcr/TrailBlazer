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

expect fun decompressGzip(bytes: ByteArray): ByteArray
