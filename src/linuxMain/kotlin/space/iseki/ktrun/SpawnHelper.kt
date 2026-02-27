package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
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
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.linux.posix_spawn
import platform.linux.posix_spawn_file_actions_adddup2
import platform.linux.posix_spawn_file_actions_destroy
import platform.linux.posix_spawn_file_actions_init
import platform.linux.posix_spawn_file_actions_t
import platform.posix.AF_UNIX
import platform.posix.EBADMSG
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import platform.posix.MAP_FAILED
import platform.posix.MAP_SHARED
import platform.posix.O_CLOEXEC
import platform.posix.O_RDWR
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.SOCK_CLOEXEC
import platform.posix.SOCK_SEQPACKET
import platform.posix.__environ
import platform.posix.close
import platform.posix.cmsghdr
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.ftruncate
import platform.posix.getenv
import platform.posix.iovec
import platform.posix.memcpy
import platform.posix.mkstemp
import platform.posix.mmap
import platform.posix.msghdr
import platform.posix.munmap
import platform.posix.pid_tVar
import platform.posix.sendmsg
import platform.posix.shm_open
import platform.posix.shm_unlink
import platform.posix.size_tVar
import platform.posix.socketpair
import platform.posix.unlink
import space.iseki.ktrun.native.COMM_CHILD_ERR_REPORT
import space.iseki.ktrun.native.COMM_FD_COUNT
import space.iseki.ktrun.native.COMM_FD_UDS
import space.iseki.ktrun.native.COMM_REQ_MEMFD
import space.iseki.ktrun.native.COMM_STATUS_REPORT
import space.iseki.ktrun.native.COMM_STDERR
import space.iseki.ktrun.native.COMM_STDIN
import space.iseki.ktrun.native.COMM_STDOUT
import space.iseki.ktrun.native.HelperCommHeader
import space.iseki.ktrun.native._binary_spawnhelper_process_end
import space.iseki.ktrun.native._binary_spawnhelper_process_start
import kotlin.experimental.ExperimentalNativeApi
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalUuidApi::class)
internal class SpawnHelper {
    companion object {
        val binSize =
            _binary_spawnhelper_process_end.rawValue.toLong() - _binary_spawnhelper_process_start.rawValue.toLong()
    }

    internal val pid: Int
    private val fd: LinuxFd
    private val spawnMutex = ReentrantLock()

    init {
        memScoped {
            val sockfd = allocArray<IntVar>(2)
            try {
                if (socketpair(AF_UNIX, SOCK_SEQPACKET or SOCK_CLOEXEC, 0, sockfd) == -1) {
                    failWithErrno("socketpair", errno)
                }
                defer { close(sockfd[1]) }
                val tmpdir = getenv("TMPDIR")?.toKString() ?: "/tmp"
                val shimPathTemplate = "$tmpdir/ktrun-shim-XXXXXX".cstr.ptr
                val shimFd = mkstemp(shimPathTemplate)
                if (shimFd == -1) {
                    failWithErrno("mkstemp", errno)
                }
                defer { close(shimFd) }
                val shimPath = shimPathTemplate.toKString()
                defer { unlink(shimPath) }
                if (fchmod(shimFd, 0b111_000_000.convert()) == -1) {
                    failWithErrno("fchmod", errno, file = shimPath)
                }
                if (ftruncate(shimFd, binSize) == -1) {
                    failWithErrno("ftruncate", errno, file = shimPath)
                }
                val addr = mmap(null, binSize.convert(), PROT_WRITE or PROT_READ, MAP_SHARED, shimFd, 0)
                if (addr == MAP_FAILED) {
                    failWithErrno("mmap", errno, file = shimPath)
                }
                defer { munmap(addr, binSize.convert()) }
                memcpy(addr, _binary_spawnhelper_process_start, binSize.convert())
                val pidVar = alloc<pid_tVar>()
                val fileAction = alloc<posix_spawn_file_actions_t>()
                var e = posix_spawn_file_actions_init(fileAction.ptr)
                if (e != 0) {
                    failWithErrno("posix_spawn_file_actions_init", e)
                }
                defer { posix_spawn_file_actions_destroy(fileAction.ptr) }
                e = posix_spawn_file_actions_adddup2(fileAction.ptr, sockfd[1], COMM_FD_UDS)
                if (e != 0) {
                    failWithErrno("posix_spawn_file_actions_adddup2", e)
                }
                e = posix_spawn(
                    __pid = pidVar.ptr,
                    __path = shimPath,
                    __file_actions = fileAction.ptr,
                    __attrp = null,
                    __argv = allocArray<CPointerVar<ByteVar>>(2).also { it[0] = "shim".cstr.ptr },
                    __envp = __environ,
                )
                if (e != 0) {
                    failWithErrno("posix_spawn", e)
                }
                pid = pidVar.value
                fd = LinuxFd(sockfd[0])
            } catch (th: Throwable) {
                close(sockfd[0])
                throw th
            }
        }
    }

    private fun createMemoryFile(
        debugName: String,
        file: String,
        argv: Array<String>,
        envp: Array<String>?,
        cwd: String?,
    ): Pair<Int, () -> Unit> {
        memScoped {
            val header = alloc<HelperCommHeader> {
                argc = argv.size
                envpc = envp?.size ?: 0
                chdir = cwd != null
            }
            val headerSize = sizeOf<HelperCommHeader>()
            val file = file.cstr
            val cwd = cwd?.cstr
            val argv = argv.map { it.cstr }
            val envp = envp?.map { it.cstr }
            val bufferSize =
                file.size + (cwd?.size ?: 0) + argv.sumOf { it.size } + (envp?.sumOf { it.size } ?: 0) + headerSize
            var addr = MAP_FAILED
            val memfd = shm_open(debugName, O_CLOEXEC or O_RDWR, 0b110_000_000.convert())
            if (memfd == -1) {
                failWithErrno("shm_open", errno, file = debugName)
            }
            try {
                if (shm_unlink(debugName) == -1) {
                    failWithErrno("shm_unlink", errno, file = debugName)
                }
                if (ftruncate(memfd, bufferSize.convert()) == -1) {
                    failWithErrno("ftruncate", errno, file = debugName)
                }
                addr = mmap(null, bufferSize.convert(), PROT_WRITE or PROT_READ, MAP_SHARED, memfd, 0)
                if (addr == MAP_FAILED) {
                    failWithErrno("mmap", errno, file = debugName)
                }
                var copyPointer: CPointer<ByteVar>? = addr?.reinterpret()
                memcpy(copyPointer, header.ptr, headerSize.convert())
                copyPointer += headerSize
                memcpy(copyPointer, file.ptr, file.size.convert())
                copyPointer += file.size
                if (cwd != null) {
                    memcpy(copyPointer, cwd.ptr, cwd.size.convert())
                    copyPointer += cwd.size
                }
                for (string in argv) {
                    memcpy(copyPointer, string.ptr, string.size.convert())
                    copyPointer += string.size
                }
                if (envp != null) {
                    for (string in envp) {
                        memcpy(copyPointer, string.ptr, string.size.convert())
                        copyPointer += string.size
                    }
                }
                return memfd to { shm_unlink(debugName) }
            } catch (th: Throwable) {
                close(memfd)
                throw th
            } finally {
                if (addr != MAP_FAILED) {
                    munmap(addr, bufferSize.convert())
                }
            }
        }
    }

    private fun MemScope.buildMessage(
        stdinFd: LinuxFd,
        stdoutFd: LinuxFd,
        stderrFd: LinuxFd,
        memfd: Int,
        childErrorSend: LinuxFd,
        statusSend: LinuxFd,
    ): msghdr {
        fun cmsgAlign(len: Int): Int {
            val align = sizeOf<size_tVar>().toInt()
            return (len + align - 1) and (align - 1).inv()
        }

        val payload = allocArray<IntVar>(COMM_FD_COUNT)
        payload[COMM_STDIN] = stdinFd.unsafeFd
        payload[COMM_STDOUT] = stdoutFd.unsafeFd
        payload[COMM_STDERR] = stderrFd.unsafeFd
        payload[COMM_CHILD_ERR_REPORT] = childErrorSend.unsafeFd
        payload[COMM_STATUS_REPORT] = statusSend.unsafeFd
        payload[COMM_REQ_MEMFD] = memfd
        val payloadSize = COMM_FD_COUNT * sizeOf<IntVar>()
        val bufferSize = cmsgAlign(sizeOf<cmsghdr>().convert()) + cmsgAlign(payloadSize.convert())
        val buffer = allocArray<ByteVar>(bufferSize).also { buf ->
            val header: CPointer<cmsghdr> = buf.plus(sizeOf<cmsghdr>())!!.reinterpret()
            header.pointed.apply {
                cmsg_len = bufferSize.convert()
                cmsg_level = platform.posix.SOL_SOCKET
                cmsg_type = platform.posix.SCM_RIGHTS
                memcpy(__cmsg_data, payload, payloadSize.convert())
            }
        }
        val messageHeader = alloc<msghdr> {
            val iovec = alloc<iovec> {
                iov_len = 1.convert()
                iov_base = "1".cstr.ptr
            }
            msg_iovlen = 1.convert()
            msg_iov = iovec.ptr
            msg_controllen = bufferSize.convert()
            msg_control = buffer
        }
        return messageHeader
    }

    private fun reportChildError(fd: LinuxFd, cwd: String?, file: String) {
        val reader = LinuxReadable(fd)
        val buf = ByteArray(8)
        val readBytes = reader.read(buf, 0, 8)
        if (readBytes == -1) {
            return
        }
        var childErrno = EBADMSG
        var childErrStep = -1
        if (readBytes >= 4) {
            childErrno = buf.getIntAt(0)
        }
        if (readBytes >= 8) {
            childErrStep = buf.getIntAt(4)
        }
        val isSetCwdFailed = childErrStep == 4
        failWithErrno("exec", childErrno, file = if (isSetCwdFailed) cwd.orEmpty() else file)
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
        val (childErrReadFd, childErrWriteFd) = pipe2(O_CLOEXEC)
        val (statusReadFd, statusWriteFd) = pipe2(O_CLOEXEC)
        spawnMutex.lock()
        try {
            memScoped {
                val (memfd, cleanup) = createMemoryFile(debugName, file, argv, envp, cwd)
                defer(cleanup)
                val messageHeader = buildMessage(
                    stdinFd = stdinFd,
                    stdoutFd = stdoutFd,
                    stderrFd = stderrFd,
                    memfd = memfd,
                    childErrorSend = childErrWriteFd,
                    statusSend = statusWriteFd,
                )
                val failed = sendmsg(this@SpawnHelper.fd.unsafeFd, messageHeader.ptr, 0) == (-1).toLong()
                if (failed && (errno == EPIPE || errno == ECONNRESET)) {
                    throw SpawnHelperDead()
                } else if (failed) {
                    failWithErrno("sendmsg", errno)
                }
            }
            childErrWriteFd.close()
            statusWriteFd.close()
            val statusReader = LinuxReadable(statusReadFd)
            val pid = statusReader.readLong().toInt()
            if (pid == -1) {
                val errno = statusReader.readInt()
                failWithErrno("create_process", errno)
            }
            reportChildError(childErrReadFd, cwd, file)
            return pid to statusReadFd
        } catch (th: Throwable) {
            statusReadFd.close()
            throw th
        } finally {
            spawnMutex.unlock()
            childErrReadFd.close()
            childErrWriteFd.close()
            statusWriteFd.close()
        }
    }

    class SpawnHelperDead : RuntimeException()
}

