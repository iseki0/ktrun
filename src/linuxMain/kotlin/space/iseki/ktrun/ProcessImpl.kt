package space.iseki.ktrun

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCValues
import platform.posix.O_CLOEXEC
import platform.posix.SIGKILL
import platform.posix.SIGRTMAX
import platform.posix.SIGSTOP
import platform.posix.SIG_IGN
import platform.posix.SIG_SETMASK
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.closelog
import platform.posix.dlsym
import platform.posix.dup2
import platform.posix.errno
import platform.posix.fork
import platform.posix.open
import platform.posix.pthread_sigmask
import platform.posix.sigaction
import platform.posix.sigfillset
import platform.posix.sigset_t
import platform.posix.write
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration


@OptIn(ExperimentalForeignApi::class)
private typealias ExecvpFn = CPointer<CFunction<(CPointer<ByteVar>, CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>) -> Int>>

@OptIn(ExperimentalForeignApi::class)
private typealias ExecvpeFn = CPointer<CFunction<(CPointer<ByteVar>, CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>, CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>) -> Int>>

@OptIn(ExperimentalForeignApi::class)
private typealias ChdirFn = CPointer<CFunction<(CPointer<ByteVar>) -> Int>>

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private val execvpFn: ExecvpFn =
    runCatching { dlsym(null, "execvp")!! }.getOrElse(::terminateWithUnhandledException).reinterpret()

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private val execvpeFn: ExecvpeFn =
    runCatching { dlsym(null, "execvpe")!! }.getOrElse(::terminateWithUnhandledException).reinterpret()

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private val chdirFn: ChdirFn =
    runCatching { dlsym(null, "chdir")!! }.getOrElse(::terminateWithUnhandledException).reinterpret()

@OptIn(ExperimentalForeignApi::class)
internal class ProcessImpl(pb: ProcessBuilderScopeImpl) : Process {
    override val stdinPipe: Writable?
    override val stdoutPipe: Readable?
    override val stderrPipe: Readable?
    override val pid: Long

    init {
        memScoped {
            var stdinPipePair: OSPipe? = null
            var stdoutPipePair: OSPipe? = null
            var stderrPipePair: OSPipe? = null
            var subStdinFd = 0
            var subStdinFdSet = false
            var subStdoutFd = 0
            var subStdoutFdSet = false
            var subStderrFd = 0
            var subStderrFdSet = false
            val workingDirectoryC = pb.workingDirectory?.cstr?.getPointer(memScope)
            val execRPipe = OSPipe()
            val pathC = pb.cmdline[0].cstr.getPointer(memScope)
            val cmdlineC = pb.cmdline.toList().map { it.cstr.ptr.getPointer(memScope) }.let { it + null }.toCValues()
            val envC = pb.environment?.toList()
                ?.map { (k, v) -> "$k=$v".cstr.getPointer(memScope) }
                ?.let { it + null }
                ?.toCValues()
            try {
                stdinPipe = when (val stdin = pb.stdin) {
                    ProcessIOHandler.INHERIT -> null
                    ProcessIOHandler.NULL -> {
                        subStdinFdSet = true
                        subStdinFd = NUL_DEV
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stdinPipePair = OSPipe()
                        subStdinFdSet = true
                        subStdinFd = stdinPipePair.r.fd
                        LinuxWritable(stdinPipePair.w)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                stdoutPipe = when (val stdout = pb.stdout) {
                    ProcessIOHandler.INHERIT -> null
                    ProcessIOHandler.NULL -> {
                        subStdoutFdSet = true
                        subStdoutFd = NUL_DEV
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stdoutPipePair = OSPipe()
                        subStdoutFdSet = true
                        subStdoutFd = stdoutPipePair.w.fd
                        LinuxReadable(stdoutPipePair.r)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                stderrPipe = if (pb.mergeStderrToStdout) {
                    subStderrFdSet = true
                    subStderrFd = subStdoutFd
                    null
                } else {
                    when (val stderr = pb.stderr) {
                        ProcessIOHandler.INHERIT -> null
                        ProcessIOHandler.NULL -> {
                            subStderrFdSet = true
                            subStderrFd = NUL_DEV
                            null
                        }

                        ProcessIOHandler.PIPE -> {
                            stderrPipePair = OSPipe()
                            subStderrFdSet = true
                            subStderrFd = stderrPipePair.w.fd
                            LinuxReadable(stderrPipePair.r)
                        }

                        is ProcessIOHandler.Path -> TODO()
                    }
                }
                this@ProcessImpl.pid = doForkAndExec(
                    subStdinFd = subStdinFd,
                    subStdinFdSet = subStdinFdSet,
                    subStdoutFd = subStdoutFd,
                    subStdoutFdSet = subStdoutFdSet,
                    subStderrFd = subStderrFd,
                    subStderrFdSet = subStderrFdSet,
                    workingDirectoryC = workingDirectoryC,
                    pathC = pathC,
                    cmdlineC = cmdlineC,
                    envC = envC,
                    execRPipe = execRPipe.w.fd,
                ).toLong()
                execRPipe.w.close()
                stdinPipePair?.r?.close()
                stdoutPipePair?.w?.close()
                stderrPipePair?.w?.close()
                val buf = ByteArray(8)
                val n = LinuxReadable(execRPipe.r).readNBytes(buf)
                if (n == 4) {
                    val n = buf.toCValues().ptr.getPointer(memScope).reinterpret<IntVar>()[0]
                    throw SyscallException("sub_process", n)
                }
            } catch (th: Throwable) {
                execRPipe.closeAddSuppressed(th)
                stdinPipePair?.closeAddSuppressed(th)
                stdoutPipePair?.closeAddSuppressed(th)
                stderrPipePair?.closeAddSuppressed(th)
                throw th
            }
        }
    }

    companion object {
        private val NUL_DEV: Int = open("/dev/null", O_CLOEXEC).also {
            if (it == 0) throw SyscallException("open", errno)
        }

    }

    override fun kill() {
        if (platform.posix.kill(pid.toInt(), SIGKILL) == -1) throw SyscallException("kill", errno)
    }

    override fun waitForExit(dur: Duration): Int? {
        TODO("Not yet implemented")
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private fun doForkAndExec(
    subStdinFd: Int,
    subStdinFdSet: Boolean,
    subStdoutFd: Int,
    subStdoutFdSet: Boolean,
    subStderrFd: Int,
    subStderrFdSet: Boolean,
    workingDirectoryC: CPointer<ByteVar>?,
    pathC: CPointer<ByteVar>,
    cmdlineC: CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>,
    envC: CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>?,
    execRPipe: Int,
): Int {
    memScoped {
        val sigsetVar = alloc<sigset_t>()
        val oldSigsetVar = alloc<sigset_t>()
        val sigactionVar = alloc<sigaction>()
        val execRBuf = alloc<IntVar>().ptr.getPointer(memScope)
        val execRBufPtr: CPointer<ByteVar> = execRBuf.reinterpret()
        if (sigfillset(__set = sigsetVar.ptr) == -1) failWithErrno("sigfillset", errno)
        if (pthread_sigmask(
                __how = SIG_SETMASK,
                __newmask = sigsetVar.ptr,
                __oldmask = oldSigsetVar.ptr,
            ) == -1
        ) failWithErrno("pthread_sigmask", errno)
        val pid = fork()
        val forkErrno = errno
        if (pid == 0) {
            // !!! subprocess !!!
            if (subStdinFdSet) {
                if (dup2(subStdinFd, STDIN_FILENO) == -1) {
                    writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
                }
            }
            if (subStdoutFdSet) {
                if (dup2(subStdoutFd, STDOUT_FILENO) == -1) {
                    writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
                }
            }
            if (subStderrFdSet) {
                if (dup2(subStderrFd, STDERR_FILENO) == -1) {
                    writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
                }
            }
            if (workingDirectoryC != null) {
                if (chdirFn(workingDirectoryC) == -1) {
                    writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
                }
            }
            for (i in 1..SIGRTMAX) {
                sigactionVar.__sigaction_handler.sa_handler = SIG_IGN
                // ignore sigaction error, not only for SIGKILL and SIGSTOP.
                // https://bugzilla.redhat.com/show_bug.cgi?id=53394
                sigaction(i, sigactionVar.ptr, null)
            }
            if (pthread_sigmask(SIG_SETMASK, oldSigsetVar.ptr, null) == -1) {
                writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
            }
            if (envC == null) {
                if (execvpFn(pathC, cmdlineC) == -1) {
                    writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
                }
            } else {
                if (execvpeFn(pathC, cmdlineC, envC) == -1) {
                    writeCodeAndExit(execRPipe, execRBuf, execRBufPtr, errno)
                }
            }
        }
        if (pthread_sigmask(SIG_SETMASK, oldSigsetVar.ptr, null) == -1) {
            terminateWithUnhandledException(SyscallException("pthread_sigmask", errno))
        }
        if (forkErrno == -1) failWithErrno("fork", forkErrno)
        return pid
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeCodeAndExit(
    execRPipe: Int,
    execRBuf: CPointer<IntVar>,
    execRBufPtr: CPointer<ByteVar /* = ByteVarOf<Byte> */>,
    no: Int,
) {
    val errno = errno
    execRBuf[0] = errno
    write(execRPipe, execRBufPtr, 4u)
    _exit(errno)
}
