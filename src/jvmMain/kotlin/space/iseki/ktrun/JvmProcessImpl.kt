package space.iseki.ktrun

import java.io.File
import kotlin.time.Duration

internal actual fun launchProcess(builder: ProcessBuilderScopeImpl): Process = JvmProcessImpl(builder)

internal class JvmProcessImpl(builder: ProcessBuilderScopeImpl) : Process {
    private val jvmProcess: java.lang.Process

    override val pid: Long
        get() = jvmProcess.pid()

    override fun kill() {
        jvmProcess.destroyForcibly()
    }

    override fun waitForExit(dur: Duration): Int? {
        return if (dur.isInfinite()) {
            jvmProcess.waitFor()
        } else {
            try {
                if (jvmProcess.waitFor(dur.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    jvmProcess.exitValue()
                } else {
                    null // Timeout
                }
            } catch (e: InterruptedException) {
                null // Interrupted
            }
        }
    }

    init {
        val pb = ProcessBuilder(builder.cmdline)
        val env = builder.environment
        if (!env.isNullOrEmpty()) {
            for ((key, value) in env) {
                pb.environment()[key] = value
            }
        }
        val workingDirectory = builder.workingDirectory
        if (workingDirectory != null) {
            pb.directory(File(workingDirectory))
        }
        if (builder.mergeStderrToStdout) {
            pb.redirectErrorStream(true)
        } else {
            when (val stderr = builder.stderr) {
                is ProcessIOHandler.Path -> File(stderr.path)
                ProcessIOHandler.NULL -> pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                ProcessIOHandler.PIPE -> pb.redirectError(ProcessBuilder.Redirect.PIPE)
                ProcessIOHandler.INHERIT -> pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            }
        }
        when (val stdin = builder.stdin) {
            is ProcessIOHandler.Path -> pb.redirectInput(File(stdin.path))
            ProcessIOHandler.PIPE, ProcessIOHandler.NULL -> pb.redirectInput(ProcessBuilder.Redirect.PIPE)
            ProcessIOHandler.INHERIT -> pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
        }
        when (val stdout = builder.stdout) {
            is ProcessIOHandler.Path -> pb.redirectOutput(File(stdout.path))
            ProcessIOHandler.NULL -> pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            ProcessIOHandler.PIPE -> pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            ProcessIOHandler.INHERIT -> pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        }
        this.jvmProcess = pb.start()
        if (builder.stdin == ProcessIOHandler.NULL) {
            jvmProcess.outputStream.close() // Ensure the output stream is closed
        }
    }

    override val stdinPipe: Writable? = when (builder.stdin) {
        ProcessIOHandler.PIPE -> JvmWritable(jvmProcess.outputStream)
        else -> null
    }

    override val stdoutPipe: Readable? = when (builder.stdout) {
        ProcessIOHandler.PIPE -> JvmReadable(jvmProcess.inputStream)
        else -> null
    }

    override val stderrPipe: Readable? = when (builder.stderr) {
        ProcessIOHandler.PIPE -> JvmReadable(jvmProcess.errorStream)
        else -> null
    }
}

