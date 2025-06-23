package space.iseki.ktrun

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCStringArray
import kotlinx.cinterop.value
import platform.linux.posix_spawn
import platform.linux.posix_spawn_file_actions_adddup2
import platform.linux.posix_spawn_file_actions_destroy
import platform.linux.posix_spawn_file_actions_init
import platform.linux.posix_spawn_file_actions_t
import platform.linux.posix_spawnattr_destroy
import platform.linux.posix_spawnattr_init
import platform.linux.posix_spawnattr_t
import platform.linux.posix_spawnp
import platform.posix.O_CLOEXEC
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.errno
import platform.posix.open
import platform.posix.pid_tVar
import platform.posix.waitpid
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
            var pid = 0

            val cmdline = pb.cmdline.toList()
            val fname = cmdline.first()
            val spawnFn = if ('/' in fname) ::posix_spawn else ::posix_spawnp

            var posixSpawnFileAction: posix_spawn_file_actions_t? = null
            var posixSpawnAttr: posix_spawnattr_t? = null
            val pidVar = alloc<pid_tVar>()
            try {
                posixSpawnFileAction = alloc<posix_spawn_file_actions_t>().also {
                    posix_spawn_file_actions_init(it.ptr).checkCallResult("posix_spawn_file_actions_init")
                }
                posixSpawnAttr = alloc<posix_spawnattr_t>().also {
                    posix_spawnattr_init(it.ptr).checkCallResult("posix_spawnattr_init")
                }

                stdinPipe = when (val stdin = pb.stdin) {
                    ProcessIOHandler.INHERIT -> null
                    ProcessIOHandler.NULL -> {
                        posixSpawnFileAction.adddup2(NUL_DEV, STDIN_FILENO)
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stdinPipePair = OSPipe().also { posixSpawnFileAction.adddup2(it.r.fd, STDIN_FILENO) }
                        LinuxWritable(stdinPipePair.w)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                stdoutPipe = when (val stdout = pb.stdout) {
                    ProcessIOHandler.INHERIT -> null
                    ProcessIOHandler.NULL -> {
                        posixSpawnFileAction.adddup2(NUL_DEV, STDOUT_FILENO)
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stdoutPipePair = OSPipe().also { posixSpawnFileAction.adddup2(it.w.fd, STDOUT_FILENO) }
                        LinuxReadable(stdoutPipePair.r)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                stderrPipe = if (pb.mergeStderrToStdout) null else when (val stderr = pb.stderr) {
                    ProcessIOHandler.INHERIT -> null
                    ProcessIOHandler.NULL -> {
                        posixSpawnFileAction.adddup2(NUL_DEV, STDERR_FILENO)
                        null
                    }

                    ProcessIOHandler.PIPE -> {
                        stderrPipePair = OSPipe().also { posixSpawnFileAction.adddup2(it.w.fd, STDERR_FILENO) }
                        LinuxReadable(stderrPipePair.r)
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                val workingDirectory = pb.workingDirectory
                if (workingDirectory != null) {
                    posix_spawn_file_actions_addchdir(
                        __file_actions = posixSpawnFileAction.ptr,
                        dir = workingDirectory,
                    ).checkCallResult("posix_spawn_file_actions_addchdir")
                }
                spawnFn(
                    pidVar.ptr,
                    fname,
                    posixSpawnFileAction.ptr,
                    null,
                    cmdline.slice(1..cmdline.lastIndex).toCStringArray(memScope),
                    pb.environment?.toList()?.map { (k, v) -> "$k=$v" }?.toCStringArray(memScope),
                )
                pid = pidVar.value
                var th: Throwable? = null
                th = stdinPipePair?.r?.closeAddSuppressed(th)
                th = stdoutPipePair?.w?.closeAddSuppressed(th)
                th = stderrPipePair?.w?.closeAddSuppressed(th)
                if (th != null) {
                    throw th
                }
            } catch (th: Throwable) {
                stdinPipePair?.closeAddSuppressed(th)
                stdoutPipePair?.closeAddSuppressed(th)
                stderrPipePair?.closeAddSuppressed(th)
                if (pid != 0) {
                    try {
                        platform.posix.kill(pid, SIGKILL).checkCallResult("kill")
                    } catch (th1: Throwable) {
                        th.addSuppressed(th1)
                    }
                    waitpid(pid, alloc<IntVar>().ptr, 0)
                }
                throw th
            } finally {
                posixSpawnAttr?.let { posix_spawnattr_destroy(it.ptr) }
                posixSpawnFileAction?.let { posix_spawn_file_actions_destroy(it.ptr) }
            }
            this@ProcessImpl.pid = pid.toLong()
        }
    }

    companion object {
        private val NUL_DEV: Int = open("/dev/null", O_CLOEXEC).also {
            if (it == 0) throw SyscallException("open", errno)
        }


    }

    override fun kill() {
        platform.posix.kill(pid.toInt(), SIGKILL).checkCallResult("kill")
    }

    override fun waitForExit(dur: Duration): Int? {
        TODO("Not yet implemented")
    }
}

@CName("posix_spawn_file_actions_addchdir")
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private external fun posix_spawn_file_actions_addchdir(
    __file_actions: CValuesRef<posix_spawn_file_actions_t>,
    dir: String,
): Int

@OptIn(ExperimentalForeignApi::class)
private fun posix_spawn_file_actions_t.adddup2(
    __fd: Int,
    __newfd: Int,
) {
    posix_spawn_file_actions_adddup2(
        __file_actions = this.ptr,
        __fd = __fd,
        __newfd = __newfd,
    ).checkCallResult("posix_spawn_file_actions_adddup2")
}

