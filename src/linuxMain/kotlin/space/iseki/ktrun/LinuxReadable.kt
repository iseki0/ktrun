package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.errno

@OptIn(ExperimentalForeignApi::class)
internal class LinuxReadable(private val fd: LinuxFd) : Readable {
    private val mutex = ReentrantLock()
    private var closed = false
    override fun close() {
        if (closed) return
        mutex.withLock { fd.close(); closed = true }
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        Readable.checkBounds(buf, offset, length)
        if (length == 0) return 0
        while (true) {
            mutex.withLock {
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
