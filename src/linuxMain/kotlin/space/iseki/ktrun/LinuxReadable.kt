package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.errno
import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalForeignApi::class)
internal class LinuxReadable(private val fd: LinuxFd) : Readable {
    private val mutex = ReentrantLock()
    private var closed = false
    override fun close() {
        if (closed) return
        mutex.withLock {
            if (closed) return
            fd.close()
            closed = true
        }
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        Readable.checkBounds(buf, offset, length)
        if (length == 0) return 0
        while (true) {
            mutex.withLock {
                if (closed) {
                    throw IOException("Already closed")
                }
                val r = buf.usePinned {
                    posixRead(fd, it.addressOf(offset), length.convert())
                }.toInt()
                if (r == 0) {
                    return -1 // EOF
                }
                if (r < 0) {
                    when (errno) {
                        EINTR -> return@withLock // continue
                        else -> throw IOException(translateErrnoNoThrow("read", errno))
                    }
                }
                return r
            }
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal fun Readable.readInt(): Int {
    val buf = ByteArray(4)
    val readBytes = this.read(buf, 0, 4)
    if (readBytes != 4) {
        throw IOException("Failed to read Int: expected 4 bytes, got $readBytes bytes")
    }
    return buf.getIntAt(0)
}

@OptIn(ExperimentalNativeApi::class)
internal fun Readable.readLong(): Long {
    val buf = ByteArray(8)
    val readBytes = this.read(buf, 0, 8)
    if (readBytes != 8) {
        throw IOException("Failed to read Long: expected 8 bytes, got $readBytes bytes")
    }
    return buf.getLongAt(0)
}
