package space.iseki.ktrun

import platform.posix.O_CLOEXEC
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LinuxIOTest {

    @Test
    fun testReadAndWrite() {
        val (r, w) = pipe2(O_CLOEXEC)
        val reader = LinuxReadable(r)
        val writer = LinuxWritable(w)
        val buf = "otanjoubi omedetou".encodeToByteArray()
        assertEquals(buf.size, writer.write(buf, 0, buf.size))
        writer.close()
        val readBuf = ByteArray(1024)
        val n = reader.readNBytes(readBuf)
        assertEquals(buf.size, n)
        assertContentEquals(buf, readBuf.sliceArray(0 until n))
    }
}
