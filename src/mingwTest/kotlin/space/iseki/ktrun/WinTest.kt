package space.iseki.ktrun

import platform.posix.sleep
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(NativeRuntimeApi::class, ExperimentalUuidApi::class)
class WinTest {
    @Test
    fun testPipeHandleGC() {
        fun aa() {
            WinPipe()
        }
        aa()
        assertNotEquals(0, WinHandle.winHandleCounter.value, "WinHandle counter should be greater than 0")
        repeat(3) {
            GC.collect()
        }
        assertEquals(0, WinHandle.winHandleCounter.value, "WinHandle counter should be 0 after GC")
    }

    @Test
    fun testEchoHello() {
        val process = buildProcess {
            cmdline = listOf("cmd", "/c", "echo Hello, world!")
            stdout = pipe()
            stderr = nul()
        }
        val stdoutPipe = process.stdoutPipe!!
        val output = ByteArray(1024)
        val n = stdoutPipe.readNBytes(output)
        assertEquals("Hello, world!\r\n", output.decodeToString(0, n), "Output should match expected string")
    }

    @Test
    fun testEchoCustomizedEnv() {
        val process = buildProcess {
            cmdline = listOf("cmd", "/c", "echo %CUSTOM_ENV_VAR%")
            environment = mapOf("CUSTOM_ENV_VAR" to "Hello, world!")
            stdout = pipe()
            stderr = nul()
        }
        val stdoutPipe = process.stdoutPipe!!
        val output = ByteArray(1024)
        val n = stdoutPipe.readNBytes(output)
        assertEquals("Hello, world!\r\n", output.decodeToString(0, n), "Output should match expected string")
    }

    @Test
    fun testWaitForExit() {
        val process = buildProcess {
            cmdline = listOf("cmd", "/c", "ping /n 1 127.0.0.1")
            stdout = nul()
            stderr = nul()
        }
        assertEquals(0, process.waitForExit())
    }

    @Test
    fun testExitCode() {
        val process = buildProcess {
            cmdline = listOf("cmd", "/c", "exit 42")
            stdout = nul()
            stderr = nul()
        }
        assertEquals(42, process.waitForExit(), "Process should exit with code 42")
    }

    @Test
    fun testKillProcess() {
        val process = buildProcess {
            cmdline = listOf("cmd", "/c", "ping -t 127.0.0.1")
            stdout = nul()
            stderr = nul()
        }
        process.kill()
        val exitCode = process.waitForExit(1.seconds)
        assertNotEquals(null, exitCode, "Process should have exited")
        assertEquals(1, exitCode, "Process should exit with code 1 after being killed")
    }

    @Test
    fun testWritePipe() {
        val pipe = WinPipe()
        val writable = pipe.writable()
        val data = "Hello, Pipe!".encodeToByteArray()
        val bytesWritten = writable.write(data)
        assertEquals(data.size, bytesWritten, "Should write all bytes to the pipe")
        pipe.write.close()

        val readable = pipe.readable()
        val readBuffer = ByteArray(1024)
        val bytesRead = readable.readNBytes(readBuffer)
        assertEquals(data.size, bytesRead, "Should read all bytes from the pipe")
        assertEquals("Hello, Pipe!", readBuffer.decodeToString(0, bytesRead), "Read data should match written data")

        pipe.close()
    }

    @Test
    fun testWait1Second() {
        val process = buildProcess {
            cmdline = listOf("cmd", "/c", "ping -n 1 -w 1000 127.0.0.1")
            stdout = nul()
            stderr = inherit()
        }
        val exitCode = process.waitForExit(1.5.seconds)
        assertNotEquals(null, exitCode, "Process should have exited")
        assertEquals(0, exitCode, "Process should exit with code 0 after waiting 1 second")
    }

    @Test
    fun testOpenNotepadThenKillIt() {
        val process = buildProcess {
            cmdline = listOf("notepad.exe")
            stdout = nul()
            stderr = nul()
        }
        // Wait for a short time to ensure Notepad is open
        sleep(1u)
        process.kill()
        val exitCode = process.waitForExit(1.5.seconds)
        assertNotEquals(null, exitCode, "Process should have exited")
        assertEquals(1, exitCode, "Process should exit with code 1 after being killed")
    }

    @Test
    fun runRandomProcess() {
        val name = Uuid.random().toString() + "-fake.exe"
        assertFails {
            buildProcess {
                cmdline = listOf(name)
                stdout = pipe()
                stderr = nul()
            }
        }
    }

    @Test
    fun testInteractiveWithCmdEcho() {
        val process = buildProcess {
            cmdline = listOf("cmd")
            stdin = pipe()
            stdout = pipe()
            stderr = nul()
        }
        val stdoutPipe = process.stdoutPipe!!
        val stdinPipe = process.stdinPipe!!
        stdinPipe.write("@echo Hello, world!\r\n".encodeToByteArray())
        stdinPipe.close()
        val output = ByteArray(4096)
        val n = stdoutPipe.readNBytes(output)
        val outStr = output.decodeToString(0, n)
        // assert contains
        assertTrue("Hello, world!" in outStr, "Output should contain 'Hello, world!', actual output: $outStr")
        process.waitForExit(1.seconds)
        assertEquals(0, process.waitForExit(), "Process should exit with code 0")
    }

    @AfterTest
    fun checkHandleGC() {
        repeat(3) {
            GC.collect()
        }
        assertEquals(0, WinHandle.winHandleCounter.value, "WinHandle counter should be 0 after GC")
    }

}
