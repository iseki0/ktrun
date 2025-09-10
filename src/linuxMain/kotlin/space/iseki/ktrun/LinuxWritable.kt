package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.errno

internal class LinuxWritable(val fd: LinuxFd) : Writable {
    private val mutex = ReentrantLock()
    private var closed = false

    override fun close() {
        if (closed) return
        mutex.withLock {
            fd.close()
            closed = true
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun write(buf: ByteArray, offset: Int, length: Int): Int {
        Writable.checkBounds(buf, offset, length)
        var written = 0
        while (written < length) {
            mutex.withLock {
                val n = buf.usePinned {
                    posixWrite(
                        fd = fd,
                        buf = it.addressOf(offset + written),
                        n = (length - written).convert(),
                    )
                }.toInt()
                if (n < 1) {
                    when (errno) {
                        EINTR -> {} // Retry on interrupt
                        else -> throw translateErrnoNoThrow("write", errno)
                    }
                }
                written += n
            }
        }
        return written
    }
}
