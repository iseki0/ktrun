package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.dlsym
import platform.posix.fork
import platform.posix.sleep
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.system.exitProcess
import kotlin.test.Test

class HelloTest {
    @OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
    @Test
    fun helloLinux() {
        val pid = fork()
        println("pid: $pid")
        for (i in 1..10) {
            println("$i in pid: $pid")
            sleep(1u)
        }
        exitProcess(1)
        println(dlsym(null, "printf"))
        println(dlsym(null, "posix_spawn_file_actions_adddup2"))
        println(dlsym(null, "posix_spawn_file_actions_addchdir"))
        val process = buildProcess {
            cmdline = listOf("sh", "-c", "echo Hello, Linux!")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
        sleep(1u)
    }
}