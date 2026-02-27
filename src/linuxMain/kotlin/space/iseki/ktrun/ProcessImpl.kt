package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.errno
import platform.posix.open
import platform.posix.read
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
        private val mutex = ReentrantLock()

    }

    override val stdinPipe: Writable?
    override val stdoutPipe: Readable?
    override val stderrPipe: Readable?
    override val pid: Long

    val waiter: Waiter

    init {
        memScoped {
            val stdin: LinuxFd
            val stdout: LinuxFd
            val stderr: LinuxFd
            val normalCloseList = mutableListOf<AutoCloseable>()
            val badCloseList = mutableListOf<AutoCloseable>()
            val (errR, errW) = pipe2(O_CLOEXEC)

            @Suppress("NOTHING_TO_INLINE")
            inline fun <T : AutoCloseable> T.normalClose() = also { normalCloseList.add(it) }

            @Suppress("NOTHING_TO_INLINE")
            inline fun <T : AutoCloseable> T.badClose() = also { badCloseList.add(it) }
            try {
                when (val p = pb.stdin) {
                    ProcessIOHandler.NULL -> {
                        stdinPipe = null
                        stdin = NUL_DEV
                    }

                    ProcessIOHandler.PIPE -> {
                        val (r, w) = pipe2(O_CLOEXEC)
                        stdinPipe = LinuxWritable(w).badClose()
                        stdin = r.normalClose()
                    }

                    ProcessIOHandler.INHERIT -> {
                        stdinPipe = null
                        stdin = INHERITED_STDIN
                    }

                    is ProcessIOHandler.Path -> {
                        stdinPipe = null
                        stdin = openFileRead(p.path).normalClose()
                    }
                }

                when (val p = pb.stdout) {
                    ProcessIOHandler.NULL -> {
                        stdoutPipe = null
                        stdout = NUL_DEV
                    }

                    ProcessIOHandler.PIPE -> {
                        val (r, w) = pipe2(O_CLOEXEC)
                        stdoutPipe = LinuxReadable(r).badClose()
                        stdout = w.normalClose()
                    }

                    ProcessIOHandler.INHERIT -> {
                        stdoutPipe = null
                        stdout = INHERITED_STDOUT
                    }

                    is ProcessIOHandler.Path -> {
                        stdoutPipe = null
                        stdout = openFileWrite(p.path).normalClose()
                    }
                }
                when (val p = pb.stderr) {
                    ProcessIOHandler.NULL -> {
                        stderrPipe = null
                        stderr = NUL_DEV
                    }

                    ProcessIOHandler.PIPE -> {
                        val (r, w) = pipe2(O_CLOEXEC)
                        stderrPipe = LinuxReadable(r).badClose()
                        stderr = w.normalClose()
                    }

                    ProcessIOHandler.INHERIT -> {
                        stderrPipe = null
                        stderr = INHERITED_STDERR
                    }

                    is ProcessIOHandler.Path -> {
                        stderrPipe = null
                        stderr = openFileWrite(p.path).normalClose()
                    }
                }

                val debugName = Uuid.random().toString()

                fun doSpawn() = helper.sendSpawnRequest(
                    debugName = debugName,
                    file = pb.cmdline.first(),
                    argv = pb.cmdline.toTypedArray(),
                    envp = pb.environment?.map { (k, v) -> "$k=$v" }?.toTypedArray(),
                    cwd = pb.workingDirectory,
                    stdinFd = stdin,
                    stdoutFd = stdout,
                    stderrFd = stderr,
                )

                var waitFd: LinuxFd? = null
                var processPid: Int = -1
                mutex.withLock {
                    try {
                        doSpawn().let { (pid, fd) ->
                            waitFd = fd
                            processPid = pid
                        }
                    } catch (_: SpawnHelper.SpawnHelperDead) {
                        helper = SpawnHelper()
                        doSpawn().let { (pid, fd) ->
                            waitFd = fd
                            processPid = pid
                        }
                    }
                }
                this@ProcessImpl.pid = processPid.toLong()
                this@ProcessImpl.waiter = Waiter(waitFd!!.badClose())
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
        TODO()
        platform.posix.kill(pid.toInt(), SIGKILL)
    }

    override fun waitForExit(dur: Duration): Int {
        TODO()
    }

    class Waiter(private val fd: LinuxFd) {
        private val lock = ReentrantLock()
        private var readStatus = 0
        private var readLen = 0

        private fun doRead(): Boolean {
            check(readLen in 0..4)
            if (readLen >= 4) {
                return true
            }
            memScoped {
                val data = alloc(readStatus)
                while (true) {
                    val n: Int = read(
                        __fd = fd.unsafeFd,
                        __buf = data.ptr.reinterpret<ByteVar>().plus(readLen),
                        __nbytes = (4 - readLen).convert(),
                    ).convert()
                    if (n == -1 && errno == EINTR) continue
                    if (n == -1) failWithErrno("read", errno)
                    if (n == 0) {
                        // EOF
                        readStatus = -1
                    }
                    break
                }
                readStatus = data.value

            }

            return readLen == 4
        }

        private class Context

        private tailrec fun waitUntilExit(context: Context) {
        }

        fun waitForExit(dur: Duration): Int {
            require(dur.isPositive())
            TODO()
        }

    }
}


