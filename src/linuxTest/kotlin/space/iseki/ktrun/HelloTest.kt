package space.iseki.ktrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class HelloTest {
    @Test
    fun helloLinux() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
    }

    @Test
    fun exit1() {
        val process = buildProcess {
            cmdline = listOf("sh", "-c", "exit 1")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
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

    @Test
    fun echoShell1(){
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
            stdout = pipe()
            stderr = inherit()
        }
        val buf = ByteArray(1024)
        val n = process.stdoutPipe!!.readNBytes(buf)
        val output = buf.decodeToString(0, n)
        assertEquals("Hello, Linux!\n", output)
    }

    @Test
    fun testShellInteractive() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh")
            stdin = pipe()
            stdout = pipe()
            stderr = inherit()
        }
        val input = "echo Hello, Linux!\nexit\n"
        process.stdinPipe!!.write(input.encodeToByteArray())
        process.stdinPipe!!.close()
        val buf = ByteArray(1024)
        val n = process.stdoutPipe!!.readNBytes(buf)
        val output = buf.decodeToString(0, n)
        assertEquals("Hello, Linux!\n", output)
        assertNotEquals("Hello, Linux!", output)
    }
}