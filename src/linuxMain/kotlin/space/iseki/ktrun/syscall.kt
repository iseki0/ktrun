package space.iseki.ktrun

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import space.iseki.ktrun.native.pipe2

@OptIn(ExperimentalForeignApi::class)
internal fun posixRead(
    fd: LinuxFd,
    buf: CValuesRef<*>?,
    nbytes: ULong,
): Long = fd.useResource { fd -> platform.posix.read(fd, buf, nbytes) }

@OptIn(ExperimentalForeignApi::class)
internal fun posixWrite(
    fd: LinuxFd,
    buf: CValuesRef<*>?,
    n: ULong,
): Long = fd.useResource { fd -> platform.posix.write(fd, buf, n) }

@OptIn(ExperimentalForeignApi::class)
internal fun pipe2(flag: Int): Pair<LinuxFd, LinuxFd> {
    memScoped {
        val fds = allocArray<IntVar>(2)
        val res = pipe2(fds, flag)
        if (res == -1) failWithErrno("pipe2", platform.posix.errno)
        return Pair(LinuxFd(fds[0]), LinuxFd(fds[1]))
    }
}

internal fun openFileRead(path: String): LinuxFd {
    val fd = platform.posix.open(path, platform.posix.O_RDONLY)
    if (fd == -1) failWithErrno("open", platform.posix.errno, path)
    return LinuxFd(fd)
}

internal fun openFileWrite(path: String): LinuxFd {
    val fd = platform.posix.open(path, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC)
    if (fd == -1) failWithErrno("open", platform.posix.errno, path)
    return LinuxFd(fd)
}

