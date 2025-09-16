package space.iseki.ktrun

import platform.posix.close
import platform.posix.errno

private val f: (Int) -> Unit = { if (close(it) == -1) translateErrnoNoThrow("close", errno) }

private val f0: (Int) -> Unit = {}

internal class LinuxFd(fd: Int, shouldBeClosed: Boolean = true) : OsResource<Int>(fd, if (shouldBeClosed) f else f0) {
    val unsafeFd: Int get() = unsafeResource
    fun closeAddSuppressed(th: Throwable?): Throwable? {
        try {
            close()
        } catch (th1: Throwable) {
            if (th == null) return th1
            th.addSuppressed(th1)
        }
        return th
    }
}

