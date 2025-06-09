package space.iseki.ktrun

interface Readable : AutoCloseable {
    /**
     * Reads data into the specified byte array.
     *
     * @param buf the byte array to read data into
     * @param offset the starting index in the byte array to write data to
     * @param length the number of bytes to read
     * @return the number of bytes read, or -1 if the end of the stream has been reached
     */
    fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int

    /**
     * Reads up to `length` bytes into the specified byte array, starting at the given offset.
     * Continues reading until either `length` bytes are read or the end of the stream is reached.
     *
     * @param buf the byte array to read data into
     * @param offset the starting index in the byte array to write data to
     * @param length the maximum number of bytes to read
     * @return the total number of bytes read, which may be less than `length` if the end of the stream is reached
     */
    fun readNBytes(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        checkBounds(buf, offset, length)
        var totalRead = 0
        while (totalRead < length) {
            val bytesRead = read(buf, offset + totalRead, length - totalRead)
            if (bytesRead == -1) break // End of stream
            totalRead += bytesRead
        }
        return totalRead
    }

    companion object {
        internal fun checkBounds(buf: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > buf.size || offset + length > buf.size) {
                throw IndexOutOfBoundsException("buf size=${buf.size}, offset=$offset, length=$length")
            }
        }
    }
}


