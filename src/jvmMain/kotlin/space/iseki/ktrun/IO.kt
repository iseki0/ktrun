package space.iseki.ktrun

internal class JvmReadable(val inputStream: java.io.InputStream) : Readable {

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        Readable.checkBounds(buf, offset, length)
        return inputStream.read(buf, offset, length)
    }

    override fun close() {
        inputStream.close()
    }

    override fun toString(): String {
        return "JvmReadable(inputStream=$inputStream)"
    }
}

fun Readable.inputStream(): java.io.InputStream {
    return when (this) {
        is JvmReadable -> this.inputStream
        else -> throw UnsupportedOperationException("This Readable does not support inputStream()")
    }
}

internal class JvmWritable(val outputStream: java.io.OutputStream) : Writable {

    override fun write(buf: ByteArray, offset: Int, length: Int): Int {
        Writable.checkBounds(buf, offset, length)
        outputStream.write(buf, offset, length)
        return length
    }

    override fun flush() {
        outputStream.flush()
    }

    override fun close() {
        outputStream.close()
    }

    override fun toString(): String {
        return "JvmWritable(outputStream=$outputStream)"
    }
}

fun Writable.outputStream(): java.io.OutputStream {
    return when (this) {
        is JvmWritable -> this.outputStream
        else -> throw UnsupportedOperationException("This Writable does not support outputStream()")
    }
}
