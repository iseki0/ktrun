package space.iseki.ktrun

import platform.posix.sleep
import kotlin.test.Test

class HelloTest {
    @Test
    fun helloLinux() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
        sleep(1u)
    }

    @Test
    fun exit1() {
        val process = buildProcess {
            cmdline = listOf("sh", "-c", "exit 1")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
        sleep(1u)
    }
}