package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.ECONNRESET
import platform.posix.EINTR
import platform.posix.EPIPE
import platform.posix.errno
import platform.posix.free
import platform.posix.recv
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_tVar
import platform.posix.usleep
import space.iseki.ktrun.native._binary_linux_spawn_helper_bin_end
import space.iseki.ktrun.native._binary_linux_spawn_helper_bin_start
import kotlin.experimental.ExperimentalNativeApi
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import space.iseki.ktrun.native.space_iseki_spawnhelper_sendSpawnRequest as nativeSendSpawnRequest

@OptIn(ExperimentalForeignApi::class)
private fun spawnHelperReceiverEntry(arg: COpaquePointer?): COpaquePointer? {
    if (arg == null) return null
    val idPtr = arg.reinterpret<IntVar>()
    val helperId = idPtr.pointed.value
    free(arg)
    val helper = SpawnHelper.takeById(helperId) ?: return null
    try {
        helper.runExitReceiverLoop()
    } finally {
        SpawnHelper.removeById(helperId)
    }
    return null
}

internal fun initializeSpawnHelper(ops: SpawnHelperInitOps): SpawnHelperInitResult {
    val (clientSocketFd, helperSocketFd) = ops.createHelperSocketPair()
    var shim: SpawnHelperShimExecutable? = null
    try {
        shim = ops.createShimExecutable()
        val pid = ops.spawnShimProcess(shim.execPath, helperSocketFd)
        return SpawnHelperInitResult(
            pid = pid,
            clientSocketFd = clientSocketFd,
        )
    } catch (th: Throwable) {
        ops.closeFd(clientSocketFd)
        throw th
    } finally {
        ops.closeFd(helperSocketFd)
        if (shim != null) {
            ops.closeFd(shim.fd)
            shim.unlinkPath?.let(ops::unlinkPath)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalUuidApi::class)
internal class SpawnHelper {
    companion object {
        val binSize =
            _binary_linux_spawn_helper_bin_end.rawValue.toLong() - _binary_linux_spawn_helper_bin_start.rawValue.toLong()
        private val helperRegistryLock = ReentrantLock()
        private var nextHelperId: Int = 1
        private val helperRegistry = mutableMapOf<Int, SpawnHelper>()

        private fun registerHelper(helper: SpawnHelper): Int = helperRegistryLock.withLock {
            val id = nextHelperId++
            helperRegistry[id] = helper
            id
        }
        internal fun takeById(id: Int): SpawnHelper? = helperRegistryLock.withLock { helperRegistry[id] }
        internal fun removeById(id: Int) {
            helperRegistryLock.withLock { helperRegistry.remove(id) }
        }
    }

    internal val pid: Int
    private val fd: LinuxFd
    private val spawnMutex = ReentrantLock()
    private val waitMutex = ReentrantLock()
    private val exitCodes = mutableMapOf<Int, Int>()
    @Volatile
    private var receiverDead = false
    private val helperId: Int

    constructor() : this(DefaultSpawnHelperInitOps)

    internal constructor(initOps: SpawnHelperInitOps) {
        val initResult = initializeSpawnHelper(initOps)
        pid = initResult.pid
        fd = LinuxFd(initResult.clientSocketFd)
        helperId = registerHelper(this)
        memScoped {
            val tid = alloc<pthread_tVar>()
            val arg = nativeHeap.alloc<IntVar>()
            arg.value = helperId
            val r = pthread_create(
                tid.ptr,
                null,
                staticCFunction(::spawnHelperReceiverEntry),
                arg.ptr.reinterpret<ByteVar>(),
            )
            if (r != 0) {
                free(arg.ptr)
                removeById(helperId)
                failWithErrno("pthread_create", r)
            }
            pthread_detach(tid.value)
        }
    }

    internal fun sendSpawnRequest(
        debugName: String,
        file: String,
        argv: Array<String>,
        envp: Array<String>?,
        cwd: String?,
        stdinFd: LinuxFd,
        stdoutFd: LinuxFd,
        stderrFd: LinuxFd,
    ): Int {
        if (receiverDead) throw SpawnHelperDead()
        spawnMutex.lock()
        try {
            return memScoped {
                val cArgv = argv.map { it.cstr }
                val argvPtr = allocArray<CPointerVar<ByteVar>>(cArgv.size + 1)
                for ((index, arg) in cArgv.withIndex()) {
                    argvPtr[index] = arg.ptr
                }
                argvPtr[cArgv.size] = null

                val envpPtr = envp?.map { it.cstr }?.let { values ->
                    allocArray<CPointerVar<ByteVar>>(values.size + 1).also { ptr ->
                        for ((index, value) in values.withIndex()) {
                            ptr[index] = value.ptr
                        }
                        ptr[values.size] = null
                    }
                }

                val cwdPtr = cwd?.cstr?.ptr
                val chdirFailed = alloc<BooleanVar>()
                chdirFailed.value = false
                val pid = nativeSendSpawnRequest(
                    helperFd = fd.unsafeFd,
                    debugName = debugName.cstr.ptr,
                    file = file.cstr.ptr,
                    argv = argvPtr,
                    envp = envpPtr,
                    cwd = cwdPtr,
                    stdinFd = stdinFd.unsafeFd,
                    stdoutFd = stdoutFd.unsafeFd,
                    stderrFd = stderrFd.unsafeFd,
                    chdirFailed = chdirFailed.ptr,
                )
                if (pid == -1) {
                    if (errno == EPIPE || errno == ECONNRESET) {
                        throw SpawnHelperDead()
                    }
                    failWithErrno("sendSpawnRequest", errno, file = if (chdirFailed.value) cwd.orEmpty() else file)
                }
                pid
            }
        } finally {
            spawnMutex.unlock()
        }
    }

    internal fun waitForProcessExit(processPid: Int, dur: Duration): Int? {
        require(dur.isPositive() || dur.isInfinite()) { "Duration must be positive or infinite" }
        val ready = waitMutex.withLock { exitCodes.remove(processPid) }
        if (ready != null) return ready
        if (receiverDead) throw SpawnHelperDead()
        if (dur.isInfinite()) {
            while (true) {
                val code = waitMutex.withLock { exitCodes.remove(processPid) }
                if (code != null) return code
                if (receiverDead) throw SpawnHelperDead()
                usleep(10_000u)
            }
        }
        val begin = TimeSource.Monotonic.markNow()
        while (begin.elapsedNow() < dur) {
            val code = waitMutex.withLock { exitCodes.remove(processPid) }
            if (code != null) return code
            if (receiverDead) throw SpawnHelperDead()
            usleep(10_000u)
        }
        return null
    }

    private fun recvExitMsgBlocking(): Pair<Int, Int> {
        memScoped {
            val buf = allocArray<IntVar>(2)
            val msgSize = (2 * sizeOf<IntVar>()).toInt()
            while (true) {
                val n = recv(
                    __fd = fd.unsafeFd,
                    __buf = buf,
                    __n = msgSize.convert(),
                    __flags = 0,
                ).toInt()
                if (n == -1 && errno == EINTR) continue
                if (n == -1 && (errno == EPIPE || errno == ECONNRESET)) throw SpawnHelperDead()
                if (n == -1) failWithErrno("recv", errno)
                if (n == 0) throw SpawnHelperDead()
                if (n != msgSize) {
                    throw IOException("invalid ProcessExitMsg size: $n")
                }
                return buf[0] to buf[1]
            }
        }
        error("unreachable")
    }

    internal fun runExitReceiverLoop() {
        try {
            while (true) {
                val (pid0, code0) = recvExitMsgBlocking()
                waitMutex.withLock {
                    exitCodes[pid0] = code0
                }
            }
        } catch (_: SpawnHelperDead) {
            receiverDead = true
        } catch (_: Throwable) {
            receiverDead = true
        }
    }

    class SpawnHelperDead : RuntimeException()
}
