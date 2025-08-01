package space.iseki.ktrun

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.value
import platform.posix.O_CLOEXEC
import platform.posix.SIGKILL
import platform.posix.errno
import platform.posix.open
import space.iseki.ktrun.native.do_fork_and_exec
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration

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
            val workingDirectory = pb.workingDirectory
            val workingDirectoryC = workingDirectory?.cstr?.getPointer(memScope)
            val execRPipe = OSPipe()
            val pathC = pb.cmdline[0].cstr.getPointer(memScope)
            val cmdline = pb.cmdline.toList()
            val cmdlineC = (cmdline.map { it.cstr.ptr.getPointer(memScope) } + null).toCValues()
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
                run {
                    val buf = ByteArray(8)
                    LinuxReadable(execRPipe.r).readNBytes(buf)
                    val iv = buf.toCValues().ptr.getPointer(memScope).reinterpret<IntVar>()
                    val errno = iv[0]
                    if (errno != 0) {
                        val location = iv[1]
                        val locFile = when (location) {
                            1 -> cmdline[0]
                            2 -> workingDirectory ?: "<CURRENT_WORKDIR>"
                            else -> null
                        }
                        if (locFile != null) throw translateFsError("subprocess", errno, locFile)
                        throw translateErrno("fork_and_exec", errno)
                    }
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
    val r: Int
    memScoped {
        val sPtr = alloc<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>()
        r = do_fork_and_exec(
            sub_stdin_fd = subStdinFd,
            use_sub_stdin = subStdinFdSet,
            sub_stdout_fd = subStdoutFd,
            use_sub_stdout = subStdoutFdSet,
            sub_stderr_fd = subStderrFd,
            use_sub_stderr = subStderrFdSet,
            working_dir = workingDirectoryC,
            path = pathC,
            argv = cmdlineC,
            envp = envC,
            exec_error_pipe = execRPipe,
            err_step = sPtr.ptr,
        )
        val errnoValue = errno
        val errStep = sPtr.value?.toKStringFromUtf8()
        if (errStep != null) {
            failWithErrno(errStep, errnoValue)
        }
    }
    return r
}

