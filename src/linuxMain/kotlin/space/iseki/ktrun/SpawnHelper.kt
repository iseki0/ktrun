package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.useContents
import platform.posix.ECONNRESET
import platform.posix.EINTR
import platform.posix.EPIPE
import platform.posix.errno
import platform.posix.iovec
import platform.posix.msghdr
import platform.posix.recvmsg
import space.iseki.ktrun.native.ProcessExitMsg
import space.iseki.ktrun.native.initHelper
import space.iseki.ktrun.native.startHelper
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class SpawnHelper {
    companion object {
        init {
            memScoped {
                if (initHelper() == -1) {
                    val errno = errno
                    tryTranslateErrno("initHelper", errno)?.let { throw it }
                    throw RuntimeException("initHelper failed: errno=$errno, ${strerror(errno)}")
                }
            }
        }
    }

    private val fd: LinuxFd
    private val spawnMutex = ReentrantLock()
    private val waitFutures = hashMapOf<Int, CFuture<Int>>()
    private var disconnected =  false

    init {
        try {
            memScoped {
                startHelper().useContents {
                    if (childErrno != 0) failWithErrno("spawnHelper/children", childErrno)
                    if (commFd == -1) failWithErrno("spawnHelper", errno)
                    this@SpawnHelper.fd = LinuxFd(commFd)
                }
                Worker.start().execute(
                    mode = TransferMode.SAFE,
                    producer = { this@SpawnHelper },
                    job = { it.loop() },
                )
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to start SpawnHelper, ${e.message}", e)
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    private fun loop() {
        try {
            while (true) {
                memScoped {
                    val msg = alloc<ProcessExitMsg>()
                    val iovec = alloc<iovec>()
                    iovec.iov_base = msg.ptr
                    iovec.iov_len = sizeOf<ProcessExitMsg>().toULong()
                    val msghdr = alloc<msghdr>()
                    msghdr.msg_iov = iovec.ptr
                    val msgLen = recvmsg(fd.unsafeFd, msghdr.ptr, 0)
                    if (msgLen == -1L) {
                        when (val errno = errno) {
                            EINTR -> continue
                            else -> failWithErrno("recvmsg", errno)
                        }
                    }
                    if (msgLen.toUInt() < sizeOf<ProcessExitMsg>().toUInt()) {
                        throw RuntimeException("Short read from spawn helper: $msgLen")
                    }
                    spawnMutex.withLock {
                        waitFutures[msg.pid]?.complete(msg.pid)
                        waitFutures.remove(msg.pid)
                    }
                }
            }
        } finally {
            spawnMutex.withLock {
                disconnected = true
                fd.close()
                val e = RuntimeException("spawn_helper disconnected")
                waitFutures.values.forEach { it.completeExceptionally(e) }
            }
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
        waitFuture: CFuture<Int>,
    ): Int {
        spawnMutex.withLock {
            if (disconnected) {
                throw SpawnHelperDead()
            }
            memScoped {
                val pid = space.iseki.ktrun.native.sendSpawnRequest(
                    helperFd = fd.unsafeFd,
                    debugName = debugName.cstr,
                    file = file.cstr,
                    argv = Array(argv.size + 1) { i -> argv.getOrNull(i)?.cstr?.getPointer(this) }.toCValues(),
                    envp = envp?.let { Array(it.size + 1) { i -> it.getOrNull(i)?.cstr?.getPointer(this) }.toCValues() },
                    cwd = cwd?.cstr,
                    stdinFd = stdinFd.unsafeFd,
                    stdoutFd = stdoutFd.unsafeFd,
                    stderrFd = stderrFd.unsafeFd,
                )
                if (pid == -1) {
                    val errno = errno
                    if (errno == EPIPE || errno == ECONNRESET) {
                        throw SpawnHelperDead()
                    }
                    failWithErrno("create_process", errno)
                }
                waitFutures[pid]?.completeExceptionally(RuntimeException("Process $pid reaped by helper before waitpid was called"))
                waitFutures[pid] = waitFuture
                return pid
            }
        }
    }

    class SpawnHelperDead : RuntimeException()
}

