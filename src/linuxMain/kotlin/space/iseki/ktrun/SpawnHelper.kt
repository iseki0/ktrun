package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.linux.posix_spawn
import platform.linux.posix_spawn_file_actions_adddup2
import platform.linux.posix_spawn_file_actions_destroy
import platform.linux.posix_spawn_file_actions_init
import platform.linux.posix_spawn_file_actions_t
import platform.posix.AF_UNIX
import platform.posix.ECONNRESET
import platform.posix.ENOSYS
import platform.posix.EPIPE
import platform.posix.MAP_FAILED
import platform.posix.MAP_SHARED
import platform.posix.O_CLOEXEC
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.SOCK_CLOEXEC
import platform.posix.SOCK_SEQPACKET
import platform.posix.__environ
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.ftruncate
import platform.posix.getenv
import platform.posix.memcpy
import platform.posix.mkstemp
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.pid_tVar
import platform.posix.socketpair
import platform.posix.syscall
import platform.posix.unlink
import space.iseki.ktrun.native.COMM_FD_UDS
import space.iseki.ktrun.native._binary_linux_spawn_helper_bin_end
import space.iseki.ktrun.native._binary_linux_spawn_helper_bin_start
import kotlin.experimental.ExperimentalNativeApi
import kotlin.uuid.ExperimentalUuidApi
import space.iseki.ktrun.native.space_iseki_spawnhelper_sendSpawnRequest as nativeSendSpawnRequest

internal data class SpawnHelperShimExecutable(
    val fd: Int,
    val execPath: String,
    val unlinkPath: String?,
)

internal data class SpawnHelperInitResult(
    val pid: Int,
    val clientSocketFd: Int,
)

internal interface SpawnHelperInitOps {
    fun createHelperSocketPair(): Pair<Int, Int>
    fun createShimExecutable(): SpawnHelperShimExecutable
    fun spawnShimProcess(shimPath: String, helperSocketFd: Int): Int
    fun closeFd(fd: Int)
    fun unlinkPath(path: String)
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
internal object DefaultSpawnHelperInitOps : SpawnHelperInitOps {
    const val MFD_CLOEXEC = 0x0001

    // linux x86_64 syscall number; current target is linuxX64.
    const val SYS_MEMFD_CREATE = 319

    override fun createHelperSocketPair(): Pair<Int, Int> = memScoped {
        val socketFds = allocArray<IntVar>(2)
        if (socketpair(AF_UNIX, SOCK_SEQPACKET or SOCK_CLOEXEC, 0, socketFds) == -1) {
            failWithErrno("socketpair", errno)
        }
        socketFds[0] to socketFds[1]
    }

    override fun createShimExecutable(): SpawnHelperShimExecutable = memScoped {
        createShimExecutableFromMemfd() ?: createShimExecutableFromTempShim()
    }

    /**
     * Preferred path: keep helper binary in an anonymous memfd and execute via /proc/self/fd/<fd>.
     * Returns null only when memfd is not supported by the kernel (ENOSYS).
     */
    private fun MemScope.createShimExecutableFromMemfd(): SpawnHelperShimExecutable? {
        val memfd = syscall(SYS_MEMFD_CREATE.toLong(), "ktrun-spawnhelper".cstr.ptr, MFD_CLOEXEC.toLong()).toInt()
        if (memfd == -1) {
            if (errno == ENOSYS) return null
            failWithErrno("memfd_create", errno, file = "ktrun-spawnhelper")
        }
        try {
            if (ftruncate(memfd, SpawnHelper.binSize) == -1) {
                failWithErrno("ftruncate", errno, file = "memfd:ktrun-spawnhelper")
            }
            copyEmbeddedBinaryToFd(memfd, "memfd:ktrun-spawnhelper")
            return SpawnHelperShimExecutable(
                fd = memfd,
                execPath = "/proc/self/fd/$memfd",
                unlinkPath = null,
            )
        } catch (th: Throwable) {
            close(memfd)
            throw th
        }
    }

    /**
     * Fallback path when memfd is unavailable: materialize helper as executable temp file.
     */
    private fun MemScope.createShimExecutableFromTempShim(): SpawnHelperShimExecutable {
        val tmpdir = getenv("TMPDIR")?.toKString() ?: "/tmp"
        val shimPathTemplate = "$tmpdir/ktrun-shim-XXXXXX".cstr.ptr
        val shimFd = mkstemp(shimPathTemplate)
        if (shimFd == -1) {
            failWithErrno("mkstemp", errno)
        }
        val shimPath = shimPathTemplate.toKString()
        try {
            if (fchmod(shimFd, 0b111_000_000.convert()) == -1) {
                failWithErrno("fchmod", errno, file = shimPath)
            }
            if (ftruncate(shimFd, SpawnHelper.binSize) == -1) {
                failWithErrno("ftruncate", errno, file = shimPath)
            }
            copyEmbeddedBinaryToFd(shimFd, shimPath)
            return SpawnHelperShimExecutable(
                fd = shimFd,
                execPath = shimPath,
                unlinkPath = shimPath,
            )
        } catch (th: Throwable) {
            close(shimFd)
            unlink(shimPath)
            throw th
        }
    }

    override fun spawnShimProcess(shimPath: String, helperSocketFd: Int): Int = memScoped {
        val pidVar = alloc<pid_tVar>()
        val fileAction = alloc<posix_spawn_file_actions_t>()
        var errorCode = posix_spawn_file_actions_init(fileAction.ptr)
        if (errorCode != 0) {
            failWithErrno("posix_spawn_file_actions_init", errorCode)
        }
        try {
            errorCode = posix_spawn_file_actions_adddup2(fileAction.ptr, helperSocketFd, COMM_FD_UDS)
            if (errorCode != 0) {
                failWithErrno("posix_spawn_file_actions_adddup2", errorCode)
            }
            errorCode = posix_spawn(
                __pid = pidVar.ptr,
                __path = shimPath,
                __file_actions = fileAction.ptr,
                __attrp = null,
                __argv = allocArray<CPointerVar<ByteVar>>(2).also { it[0] = "shim".cstr.ptr },
                __envp = __environ,
            )
            if (errorCode != 0) {
                failWithErrno("posix_spawn", errorCode)
            }
            pidVar.value
        } finally {
            posix_spawn_file_actions_destroy(fileAction.ptr)
        }
    }

    override fun closeFd(fd: Int) {
        close(fd)
    }

    override fun unlinkPath(path: String) {
        unlink(path)
    }

    private fun copyEmbeddedBinaryToFd(shimFd: Int, shimPath: String) {
        val addr = mmap(
            null,
            SpawnHelper.binSize.convert(),
            PROT_WRITE or PROT_READ,
            MAP_SHARED,
            shimFd,
            0,
        )
        if (addr == MAP_FAILED) {
            failWithErrno("mmap", errno, file = shimPath)
        }
        try {
            memcpy(addr, _binary_linux_spawn_helper_bin_start, SpawnHelper.binSize.convert())
        } finally {
            munmap(addr, SpawnHelper.binSize.convert())
        }
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalUuidApi::class)
internal class SpawnHelper {
    companion object {
        val binSize =
            _binary_linux_spawn_helper_bin_end.rawValue.toLong() - _binary_linux_spawn_helper_bin_start.rawValue.toLong()
    }

    internal val pid: Int
    private val fd: LinuxFd
    private val spawnMutex = ReentrantLock()

    constructor() : this(DefaultSpawnHelperInitOps)

    internal constructor(initOps: SpawnHelperInitOps) {
        val initResult = initializeSpawnHelper(initOps)
        pid = initResult.pid
        fd = LinuxFd(initResult.clientSocketFd)
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
    ): Pair<Int, LinuxFd> {
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
                    fd.unsafeFd,
                    debugName.cstr.ptr,
                    file.cstr.ptr,
                    argvPtr,
                    envpPtr,
                    cwdPtr,
                    stdinFd.unsafeFd,
                    stdoutFd.unsafeFd,
                    stderrFd.unsafeFd,
                    chdirFailed.ptr,
                )
                if (pid == -1) {
                    if (errno == EPIPE || errno == ECONNRESET) {
                        throw SpawnHelperDead()
                    }
                    failWithErrno("sendSpawnRequest", errno, file = if (chdirFailed.value) cwd.orEmpty() else file)
                }

                // Current ProcessImpl waiter logic still expects a fd per process.
                // Provide a closed read end placeholder until wait protocol is finalized.
                val (readFd, writeFd) = pipe2(O_CLOEXEC)
                writeFd.close()
                pid to readFd
            }
        } finally {
            spawnMutex.unlock()
        }
    }

    class SpawnHelperDead : RuntimeException()
}
