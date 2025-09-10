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
import kotlinx.cinterop.value
import platform.posix.O_CLOEXEC
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.errno
import platform.posix.open
import space.iseki.ktrun.native.initHelper
import space.iseki.ktrun.native.sendSpawnRequest
import space.iseki.ktrun.native.startHelper
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class)
internal class ProcessImpl(pb: ProcessBuilderScopeImpl) : Process {
    override val stdinPipe: Writable?
    override val stdoutPipe: Readable?
    override val stderrPipe: Readable?
    override val pid: Long
    val pidfd: OsFd

    init {
        memScoped {
            var stdinPipePair: OSPipe? = null
            var stdoutPipePair: OSPipe? = null
            var stderrPipePair: OSPipe? = null
            var subStdinFd = STDIN_FILENO
            var subStdoutFd = STDOUT_FILENO
            var subStderrFd = STDERR_FILENO
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
                        subStdinFd = NUL_DEV
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stdinPipePair = OSPipe()
                        subStdinFd = stdinPipePair.r.fd
                        LinuxWritable(stdinPipePair.w)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                stdoutPipe = when (val stdout = pb.stdout) {
                    ProcessIOHandler.INHERIT -> null
                    ProcessIOHandler.NULL -> {
                        subStdoutFd = NUL_DEV
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stdoutPipePair = OSPipe()
                        subStdoutFd = stdoutPipePair.w.fd
                        LinuxReadable(stdoutPipePair.r)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                stderrPipe = if (pb.mergeStderrToStdout) {
                    subStderrFd = subStdoutFd
                    null
                } else {
                    when (val stderr = pb.stderr) {
                        ProcessIOHandler.INHERIT -> null
                        ProcessIOHandler.NULL -> {
                            subStderrFd = NUL_DEV
                            null
                        }

                        ProcessIOHandler.PIPE -> {
                            stderrPipePair = OSPipe()
                            subStderrFd = stderrPipePair.w.fd
                            LinuxReadable(stderrPipePair.r)
                        }

                        is ProcessIOHandler.Path -> TODO()
                    }
                }
                val r = doForkAndExec(
                    subStdinFd = subStdinFd,
                    subStdoutFd = subStdoutFd,
                    subStderrFd = subStderrFd,
                    workingDirectoryC = workingDirectoryC,
                    pathC = pathC,
                    cmdlineC = cmdlineC,
                    envC = envC,
                    execRPipe = execRPipe.w.fd,
                )
                this@ProcessImpl.pid = r.pid.toLong()
                this@ProcessImpl.pidfd = r.pidfd
                execRPipe.w.close()
                stdinPipePair?.r?.close()
                stdoutPipePair?.w?.close()
                stderrPipePair?.w?.close()
                throwSubprocessErrno(execRPipe.r, cmdline[0], workingDirectory)
            } catch (th: Throwable) {
                execRPipe.closeAddSuppressed(th)
                stdinPipePair?.closeAddSuppressed(th)
                stdoutPipePair?.closeAddSuppressed(th)
                stderrPipePair?.closeAddSuppressed(th)
                throw th
            }
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    companion object {
        private val NUL_DEV: Int = open("/dev/null", O_CLOEXEC).also {
            if (it == 0) failWithErrno("open", errno)
        }

        init {
            memScoped {
                if (initHelper() == -1) {
                    failWithErrno("initHelper", errno)
                }
            }
        }
    }

    override fun kill() {
        if (platform.posix.kill(pid.toInt(), SIGKILL) == -1) throw SyscallException("kill", errno)
    }

    override fun waitForExit(dur: Duration): Int {
        TODO()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun throwSubprocessErrno(subProcessPipe: OsFd, exe: String, wd: String?) {
    val buf = ByteArray(8)
    LinuxReadable(subProcessPipe).readNBytes(buf)
    memScoped {
        val iv = buf.toCValues().ptr.getPointer(memScope).reinterpret<IntVar>()
        val errno = iv[0]
        if (errno == 0) return
        val location = iv[1]
        val locFile = when (location) {
            1 -> exe
            2 -> wd ?: "<CURRENT_WORKDIR>"
            else -> null
        }
        if (locFile != null) throw translateFsErrorNoThrow("subprocess", errno, locFile)
        throw translateErrnoNoThrow("subprocess", errno)
    }
}

private class ForkAndExecResult(
    val pid: Int,
    val pidfd: OsFd,
)

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalUuidApi::class)
private fun doForkAndExec(
    subStdinFd: Int,
    subStdoutFd: Int,
    subStderrFd: Int,
    workingDirectoryC: CPointer<ByteVar>?,
    pathC: CPointer<ByteVar>,
    cmdlineC: CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>,
    envC: CValuesRef<CPointerVarOf<CPointer<ByteVarOf<Byte>>>>?,
    execRPipe: Int,
): ForkAndExecResult {
    memScoped {
        // TODO: helper leak
        val helperPid = alloc<IntVar>()
        val helperFd = alloc<IntVar>()
        if (startHelper(helperFd.ptr, helperPid.ptr) == -1) failWithErrno("startHelper", errno)
        if (helperFd.value == -1) failWithErrno("initHelper", errno)
        sendSpawnRequest(
            helperFd = helperFd.value,
            debugName = Uuid.random().toString().cstr,
            file = pathC,
            cwd = workingDirectoryC,
            argv = cmdlineC,
            envp = envC,
            envpSet = if (envC != null) 1 else 0,
            errFd = execRPipe,
            stdinFd = subStdinFd,
            stdoutFd = subStdoutFd,
            stderrFd = subStderrFd,
        )
        TODO()
    }
}

