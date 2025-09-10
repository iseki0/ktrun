package space.iseki.ktrun

import platform.posix.close
import platform.posix.errno

private val f: (Int) -> Unit = {
    if (close(it) == -1) translateErrnoNoThrow("close", errno)
}

internal class LinuxFd(fd: Int) : OsResource<Int>(fd, f) {
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

