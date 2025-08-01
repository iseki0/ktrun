package space.iseki.ktrun

import platform.posix.sleep
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

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

    @Test
    fun notFound(){
        assertFailsWith<NoSuchFileException> {
            val process = buildProcess {
                cmdline = listOf("/program_not_exist")
                stdout = inherit()
                stderr = inherit()
            }
        }
    }
}