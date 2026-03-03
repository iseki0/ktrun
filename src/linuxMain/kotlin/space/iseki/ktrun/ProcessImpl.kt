package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import platform.posix.O_CLOEXEC
import platform.posix.ESRCH
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.errno
import platform.posix.kill
import platform.posix.open
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)
internal class ProcessImpl(pb: ProcessBuilderScopeImpl) : Process {
    @OptIn(ExperimentalNativeApi::class)
    companion object {
        private val NUL_DEV = LinuxFd(
            open("/dev/null", O_CLOEXEC).also {
                if (it == -1) failWithErrno("open", errno)
            },
            shouldBeClosed = false,
        )
        private val INHERITED_STDIN = LinuxFd(STDIN_FILENO, shouldBeClosed = false)
        private val INHERITED_STDOUT = LinuxFd(STDOUT_FILENO, shouldBeClosed = false)
        private val INHERITED_STDERR = LinuxFd(STDERR_FILENO, shouldBeClosed = false)

        internal var helper = SpawnHelper()
        private val helperSpawnMutex = ReentrantLock()

        internal fun terminateHelperForTest() {
            val helperPid = helper.pid
            if (kill(helperPid, SIGKILL) == -1 && errno != ESRCH) {
                failWithErrno("kill", errno)
            }
        }
    }

    override val stdinPipe: Writable?
    override val stdoutPipe: Readable?
    override val stderrPipe: Readable?
    override val pid: Long

    private val exitLock = ReentrantLock()
    private var exited = false
    private var cachedExitCode: Int? = null

    init {
        memScoped {
            val normalCloseList = mutableListOf<AutoCloseable>()
            val badCloseList = mutableListOf<AutoCloseable>()

            @Suppress("NOTHING_TO_INLINE")
            inline fun <T : AutoCloseable> T.normalClose() = also { normalCloseList.add(it) }

            @Suppress("NOTHING_TO_INLINE")
            inline fun <T : AutoCloseable> T.badClose() = also { badCloseList.add(it) }

            try {
                val stdinFd: LinuxFd
                val stdoutFd: LinuxFd
                val stderrFd: LinuxFd

                when (val p = pb.stdin) {
                    ProcessIOHandler.NULL -> {
                        stdinPipe = null
                        stdinFd = NUL_DEV
                    }

                    ProcessIOHandler.PIPE -> {
                        val (r, w) = pipe2(O_CLOEXEC)
                        stdinPipe = LinuxWritable(w).badClose()
                        stdinFd = r.normalClose()
                    }

                    ProcessIOHandler.INHERIT -> {
                        stdinPipe = null
                        stdinFd = INHERITED_STDIN
                    }

                    is ProcessIOHandler.Path -> {
                        stdinPipe = null
                        stdinFd = openFileRead(p.path).normalClose()
                    }
                }

                when (val p = pb.stdout) {
                    ProcessIOHandler.NULL -> {
                        stdoutPipe = null
                        stdoutFd = NUL_DEV
                    }

                    ProcessIOHandler.PIPE -> {
                        val (r, w) = pipe2(O_CLOEXEC)
                        stdoutPipe = LinuxReadable(r).badClose()
                        stdoutFd = w.normalClose()
                    }

                    ProcessIOHandler.INHERIT -> {
                        stdoutPipe = null
                        stdoutFd = INHERITED_STDOUT
                    }

                    is ProcessIOHandler.Path -> {
                        stdoutPipe = null
                        stdoutFd = openFileWrite(p.path).normalClose()
                    }
                }

                if (pb.mergeStderrToStdout) {
                    stderrPipe = null
                    stderrFd = stdoutFd
                } else {
                    when (val p = pb.stderr) {
                        ProcessIOHandler.NULL -> {
                            stderrPipe = null
                            stderrFd = NUL_DEV
                        }

                        ProcessIOHandler.PIPE -> {
                            val (r, w) = pipe2(O_CLOEXEC)
                            stderrPipe = LinuxReadable(r).badClose()
                            stderrFd = w.normalClose()
                        }

                        ProcessIOHandler.INHERIT -> {
                            stderrPipe = null
                            stderrFd = INHERITED_STDERR
                        }

                        is ProcessIOHandler.Path -> {
                            stderrPipe = null
                            stderrFd = openFileWrite(p.path).normalClose()
                        }
                    }
                }

                val debugName = Uuid.random().toString()
                fun doSpawn() = helper.sendSpawnRequest(
                    debugName = debugName,
                    file = pb.cmdline.first(),
                    argv = pb.cmdline.toTypedArray(),
                    envp = pb.environment?.map { (k, v) -> "$k=$v" }?.toTypedArray(),
                    cwd = pb.workingDirectory,
                    stdinFd = stdinFd,
                    stdoutFd = stdoutFd,
                    stderrFd = stderrFd,
                )

                val processPid = helperSpawnMutex.withLock {
                    try {
                        doSpawn()
                    } catch (_: SpawnHelper.SpawnHelperDead) {
                        helper = SpawnHelper()
                        doSpawn()
                    }
                }

                this@ProcessImpl.pid = processPid.toLong()
                normalCloseList.forEach { it.close() }
            } catch (th: Throwable) {
                for (it in normalCloseList) {
                    try {
                        it.close()
                    } catch (th0: Throwable) {
                        th.addSuppressed(th0)
                    }
                }
                for (it in badCloseList) {
                    try {
                        it.close()
                    } catch (th0: Throwable) {
                        th.addSuppressed(th0)
                    }
                }
                throw th
            }
        }
    }

    override fun kill() {
        exitLock.withLock {
            if (exited) return
        }
        if (kill(pid.toInt(), SIGKILL) == -1 && errno != ESRCH) {
            failWithErrno("kill", errno)
        }
    }

    override fun waitForExit(dur: Duration): Int? {
        exitLock.withLock {
            if (exited) return cachedExitCode
        }
        val code = helper.waitForProcessExit(pid.toInt(), dur) ?: return null
        exitLock.withLock {
            exited = true
            cachedExitCode = code
        }
        return code
    }
}
