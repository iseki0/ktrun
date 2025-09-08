package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.errno
import space.iseki.ktrun.native.pipe2

@OptIn(ExperimentalForeignApi::class)
internal class LinuxReadable(val fd: OsFd) : Readable {
    private val mutex = ReentrantLock()
    private var closed = false
    override fun close() {
        if (closed) return
        mutex.withLock { fd.close();closed = true }
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        Readable.checkBounds(buf, offset, length)
        if (length == 0) return 0
        while (true) {
            mutex.withLock {
                val r = buf.usePinned {
                    platform.posix.read(fd.fd, it.addressOf(offset), length.convert()).toInt()
                }
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

internal class LinuxWritable(val fd: OsFd) : Writable {
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
                val n = platform.posix.write(
                    fd.fd,
                    buf.usePinned { it.addressOf(offset + written) },
                    (length - written).convert()
                ).toInt()
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

@OptIn(ExperimentalForeignApi::class)
internal class OSPipe : AutoCloseable {
    val r: OsFd
    val w: OsFd

    init {
        memScoped {
            val fds = allocArray<IntVar>(2)
            if (pipe2(fds.reinterpret(), O_CLOEXEC) == -1) failWithErrno("pipe2", errno)
            r = OsFd(fds[0])
            w = OsFd(fds[1])
        }
    }

    override fun close() {
        r.close()
        w.close()
    }

    @Suppress("NAME_SHADOWING")
    fun closeAddSuppressed(th: Throwable?): Throwable? {
        var th = th
        th = r.closeAddSuppressed(th)
        th = w.closeAddSuppressed(th)
        return th
    }
}
