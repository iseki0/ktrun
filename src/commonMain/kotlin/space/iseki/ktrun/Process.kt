package space.iseki.ktrun

import kotlin.time.Duration

interface Process {
    val stdinPipe: Writable?
    val stdoutPipe: Readable?
    val stderrPipe: Readable?
    val pid: Long
    fun terminate(force: Boolean = false)
    fun kill() = terminate(force = true)
    fun waitForExit(dur: Duration = Duration.INFINITE): Int?
}

