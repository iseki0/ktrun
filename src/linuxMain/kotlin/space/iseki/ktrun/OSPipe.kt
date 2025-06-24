package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.internal.CCall
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import platform.posix.O_CLOEXEC
import kotlin.experimental.ExperimentalNativeApi


internal class LinuxReadable(val fd: OsFd) : Readable {
    private val mutex = ReentrantLock()
    private var closed = false
    override fun close() {
        if (closed) return
        mutex.withLock { fd.close();closed = true }
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        TODO("Not yet implemented")
    }
}

internal class LinuxWritable(val fd: OsFd) : Writable {
    private val mutex = ReentrantLock()
    private var closed = false
    override fun close() {
        if (closed) return
        mutex.withLock { fd.close();closed = true }
    }

    override fun write(buf: ByteArray, offset: Int, length: Int): Int {
        TODO("Not yet implemented")
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class OSPipe: AutoCloseable {
    val r: OsFd
    val w: OsFd

    init {
        memScoped {
            val fds = allocArray<IntVar>(2)
            pipe2(fds.reinterpret(), O_CLOEXEC).checkCallResult("pipe2")
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

    companion object {
        @OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
        @CCall("pipe2")
        private external fun pipe2(fds: CPointer<IntVar>, flags: Int): Int
    }
}
