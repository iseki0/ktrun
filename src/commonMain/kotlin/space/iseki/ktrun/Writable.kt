package space.iseki.ktrun

interface Writable : AutoCloseable {
    fun write(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int
    fun flush() {}

    companion object {
        internal fun checkBounds(buf: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > buf.size || offset + length > buf.size) {
                throw IndexOutOfBoundsException("buf size=${buf.size}, offset=$offset, length=$length")
            }
        }
    }
}

