package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.O_CLOEXEC

@OptIn(ExperimentalForeignApi::class)
internal class OSPipe : AutoCloseable {
    val r: LinuxFd
    val w: LinuxFd

    init {
        val (r, w) = pipe2(O_CLOEXEC)
        this@OSPipe.r = r
        this@OSPipe.w = w
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
