package space.iseki.ktrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class HelloTest {
    @Test
    fun helloLinux() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
        assertEquals(0, process.waitForExit())
    }

    @Test
    fun exit1() {
        val process = buildProcess {
            cmdline = listOf("sh", "-c", "exit 1")
            stdout = inherit()
            stderr = inherit()
        }
        println(process.pid)
        assertEquals(1, process.waitForExit())
    }

    @Test
    fun execveNotFound() {
        assertFailsWith<NoSuchFileException> {
            buildProcess {
                cmdline = listOf("/program_not_exist")
                stdout = inherit()
                stderr = inherit()
            }
        }.also {
            println(it)
            assertEquals("/program_not_exist", it.file)
        }
    }

    @Test
    fun chdirNotFound() {
        assertFailsWith<NoSuchFileException> {
            buildProcess {
                cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
                workingDirectory = "/path_not_exist"
                stdout = inherit()
                stderr = inherit()
            }
        }.also {
            println(it)
            assertEquals("/path_not_exist", it.file)
        }
    }

    @Test
    fun echoShell1() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
            stdout = pipe()
            stderr = inherit()
        }
        val buf = ByteArray(1024)
        val n = process.stdoutPipe!!.readNBytes(buf)
        val output = buf.decodeToString(0, n)
        assertEquals("Hello, Linux!\n", output)
        assertEquals(0, process.waitForExit(1.seconds))
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
        assertEquals(0, process.waitForExit(1.seconds))
    }

    @Test
    fun testHelperKilled() {
        val deadP = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "sleep 10")
            stdout = inherit()
            stderr = inherit()
        }
        ProcessImpl.terminateHelperForTest()
        assertFailsWith<RuntimeException> {
            deadP.waitForExit()
        }.also { println(it) }
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "echo Hello, Linux!")
            stdout = pipe()
            stderr = inherit()
        }
        assertEquals(0, process.waitForExit())
    }

    @Test
    fun terminateGracefullyUsesSigtermByDefault() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/bash", "-c", "trap 'exit 42' TERM; echo READY; sleep 3")
            stdout = pipe()
            stderr = inherit()
        }
        val out = ByteArray(6)
        val n = process.stdoutPipe!!.readNBytes(out, length = 6)
        assertEquals("READY\n", out.decodeToString(0, n))
        process.terminate()
        assertEquals(42, process.waitForExit(6.seconds))
    }

    @Test
    fun killAliasRemainsForceful() {
        val process = buildProcess {
            cmdline = listOf("/usr/bin/sh", "-c", "trap 'exit 42' TERM; while true; do sleep 1; done")
            stdout = inherit()
            stderr = inherit()
        }
        process.kill()
        val code = process.waitForExit(3.seconds)
        assertNotNull(code)
        assertEquals(-1, code)
    }
}
