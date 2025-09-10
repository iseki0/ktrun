package space.iseki.ktrun

import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
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

@OptIn(ExperimentalForeignApi::class)
internal fun sendSpawnRequest(
    helperFd: LinuxFd,
    debugName: CValuesRef<ByteVarOf<Byte>>?,
    file: CValuesRef<ByteVarOf<Byte>>?,
    argv: CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>?,
    envp: CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>?,
    envpSet: Int,
    cwd: CValuesRef<ByteVarOf<Byte>>?,
    errFd: LinuxFd,
    stdinFd: LinuxFd,
    stdoutFd: LinuxFd,
    stderrFd: LinuxFd,
): Int {
    return space.iseki.ktrun.native.sendSpawnRequest(
        helperFd = helperFd.unsafeFd,
        debugName = debugName,
        file = file,
        argv = argv,
        envp = envp,
        envpSet = envpSet,
        cwd = cwd,
        errFd = errFd.unsafeFd,
        stdinFd = stdinFd.unsafeFd,
        stdoutFd = stdoutFd.unsafeFd,
        stderrFd = stderrFd.unsafeFd,
    )
}