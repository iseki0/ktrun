package space.iseki.ktrun

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.placeTo
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CREATE_UNICODE_ENVIRONMENT
import platform.windows.CreateFileW
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.EXTENDED_STARTUPINFO_PRESENT
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetExitCodeProcess
import platform.windows.GetHandleInformation
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INFINITE
import platform.windows.OPEN_EXISTING
import platform.windows.PROCESS_INFORMATION
import platform.windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOEXW
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetHandleInformation
import platform.windows.TerminateProcess
import platform.windows.UpdateProcThreadAttribute
import platform.windows.WAIT_FAILED
import platform.windows.WAIT_OBJECT_0
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class WinProcessImpl(
    processBuilder: ProcessBuilderScopeImpl,
) : Process {

    val dwProcessId: UInt
    val dwThreadId: UInt
    val procecssHandle: WinHandle
    val threadHandle: WinHandle?

    override val pid: Long
        get() = dwProcessId.toLong()
    override val stdinPipe: Writable?
    override val stdoutPipe: Readable?
    override val stderrPipe: Readable?


    init {
        val cmdline = windowsQuoteArgs(processBuilder.cmdline)
        var stdinPipe: Writable? = null
        var stdoutPipe: Readable? = null
        var stderrPipe: Readable? = null
        memScoped {
            val failCleanup = mutableListOf<() -> Unit>()
            val successCleanup = mutableListOf<() -> Unit>()
            try {
                val subStdinHandle: HANDLE = when (processBuilder.stdin) {
                    ProcessIOHandler.INHERIT -> getStdHandle(STD_INPUT_HANDLE)
                    ProcessIOHandler.NULL -> NUL_HANDLE
                    ProcessIOHandler.PIPE -> {
                        val pipe = WinPipe()
                        failCleanup.add { pipe.close() }
                        successCleanup.add { pipe.closeRead() }
                        stdinPipe = pipe.writable()
                        pipe.read.handle
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                val subStdoutHandle: HANDLE = when (processBuilder.stdout) {
                    ProcessIOHandler.INHERIT -> getStdHandle(STD_OUTPUT_HANDLE)
                    ProcessIOHandler.NULL -> NUL_HANDLE
                    ProcessIOHandler.PIPE /*is ProcessOutHandler.Sink*/ -> {
                        val pipe = WinPipe()
                        failCleanup.add { pipe.close() }
                        successCleanup.add { pipe.closeWrite() }
                        stdoutPipe = pipe.readable()
                        pipe.write.handle
                    }

                    is ProcessIOHandler.Path -> TODO()
                }

                val subStderrHandle: HANDLE = if (processBuilder.mergeStderrToStdout) {
                    subStdoutHandle
                } else {
                    when (processBuilder.stderr) {
                        ProcessIOHandler.INHERIT -> getStdHandle(STD_ERROR_HANDLE)
                        ProcessIOHandler.NULL -> NUL_HANDLE
                        ProcessIOHandler.PIPE /*is ProcessOutHandler.Sink*/ -> {
                            val pipe = WinPipe()
                            failCleanup.add { pipe.close() }
                            successCleanup.add { pipe.closeWrite() }
                            stderrPipe = pipe.readable()
                            pipe.write.handle
                        }

                        is ProcessIOHandler.Path -> TODO()
                    }
                }

                val pAttributeList = WinProcThreadAttributeList(1)
                successCleanup.add { pAttributeList.close() }
                failCleanup.add { pAttributeList.close() }

                val startupInfo = alloc<STARTUPINFOEXW>()
                startupInfo.lpAttributeList = pAttributeList.pointer
                with(startupInfo.StartupInfo) {
                    cb = sizeOf<STARTUPINFOEXW>().toUInt()
                    dwFlags = STARTF_USESTDHANDLES.toUInt()
                    hStdInput = subStdinHandle.also(Companion::ensureHandleInherit)
                    hStdOutput = subStdoutHandle.also(Companion::ensureHandleInherit)
                    hStdError = subStderrHandle.also(Companion::ensureHandleInherit)
                }


                val iHandles = allocArray<HANDLEVar>(3)
                var iHandleSize = 0

                fun putToHandles(handle: HANDLE?, arr: CArrayPointer<HANDLEVar>, cSize: Int): Int {
                    for (i in 0..<cSize) {
                        if (arr[i] == handle) return cSize
                    }
                    arr[cSize] = handle
                    return cSize + 1
                }

                iHandleSize = putToHandles(startupInfo.StartupInfo.hStdInput, iHandles, iHandleSize)
                iHandleSize = putToHandles(startupInfo.StartupInfo.hStdOutput, iHandles, iHandleSize)
                iHandleSize = putToHandles(startupInfo.StartupInfo.hStdError, iHandles, iHandleSize)

                if (UpdateProcThreadAttribute(
                        lpAttributeList = startupInfo.lpAttributeList,
                        dwFlags = 0u,
                        Attribute = PROC_THREAD_ATTRIBUTE_HANDLE_LIST.toULong(),
                        lpValue = iHandles,
                        cbSize = sizeOf<HANDLEVar>().toULong() * iHandleSize.toULong(),
                        lpPreviousValue = null,
                        lpReturnSize = null,
                    ) == 0
                ) throwLastWinError(apiName = "UpdateProcThreadAttribute")

                val pi = alloc<PROCESS_INFORMATION>()
                if (CreateProcessW(
                        lpApplicationName = null,
                        lpCommandLine = cmdline.wcstr.placeTo(memScope),
                        lpProcessAttributes = null,
                        lpThreadAttributes = null,
                        bInheritHandles = 1,
                        dwCreationFlags = CREATE_UNICODE_ENVIRONMENT.toUInt() or EXTENDED_STARTUPINFO_PRESENT.toUInt(),
                        lpEnvironment = buildEnvBlock(processBuilder.environment, memScope),
                        lpCurrentDirectory = processBuilder.workingDirectory,
                        lpStartupInfo = startupInfo.ptr.reinterpret(),
                        lpProcessInformation = pi.ptr,
                    ) == 0
                ) throwLastWinError("CreateProcessW")
                dwProcessId = pi.dwProcessId
                dwThreadId = pi.dwThreadId
                procecssHandle = WinHandle(pi.hProcess!!).also { failCleanup.add { it.close() } }
                threadHandle = pi.hThread?.let { WinHandle(it) }?.also { failCleanup.add { it.close() } }
                successCleanup.forEach { it.invoke() }
            } catch (th: Throwable) {
                failCleanup.forEach { runCatching { it() }.onFailure { th.addSuppressed(it) } }
                throw th
            }

        }
        this.stdinPipe = stdinPipe
        this.stdoutPipe = stdoutPipe
        this.stderrPipe = stderrPipe
    }


    override fun kill() {
        TerminateProcess(procecssHandle.handle, 1u)
    }

    override fun waitForExit(dur: Duration): Int? {
        check(dur.isPositive() || dur.isInfinite()) { "Duration must be positive or infinite" }
        memScoped {
            val exitCode = alloc<DWORDVar>()
            val f = waitHelper(dur) { dur0 ->
                val waitR = if (dur0.isInfinite()) INFINITE else dur0.inWholeMilliseconds.toUInt()
                when (val r = WaitForSingleObject(procecssHandle.handle, waitR)) {
                    WAIT_FAILED -> throwLastWinError(
                        apiName = "WaitForSingleObject",
                        arguments = listOf(
                            "hHandle" to procecssHandle.handle.toString(),
                            "dwMilliseconds" to waitR.toString(),
                        ),
                    )

                    WAIT_TIMEOUT.toUInt() -> return@waitHelper false
                    WAIT_OBJECT_0 -> {
                        if (GetExitCodeProcess(procecssHandle.handle, exitCode.ptr) == 0) throwLastWinError(
                            apiName = "GetExitCodeProcess",
                            arguments = listOf(
                                "hProcess" to procecssHandle.handle.toString(),
                                "lpExitCode" to exitCode.ptr.toString(),
                            ),
                        )
                        return@waitHelper true
                    }

                    else -> error("Unexpected return value from WaitForSingleObject: $r")
                }
            }
            return if (f) exitCode.value.toInt() else null

        }
    }


    companion object {
        private val NUL_HANDLE: HANDLE

        init {
            memScoped {
                val handle = CreateFileW(
                    lpFileName = "NUL",
                    dwDesiredAccess = GENERIC_READ or GENERIC_WRITE.toUInt(),
                    dwShareMode = FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt(),
                    lpSecurityAttributes = null,
                    dwCreationDisposition = OPEN_EXISTING.toUInt(),
                    dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
                    hTemplateFile = null,
                )
                if (handle == null) {
                    throwLastWinError("CreateFileW", listOf("lpFileName" to "NUL"))
                }
                ensureHandleInherit(handle)
                NUL_HANDLE = handle
            }
        }


        private fun getStdHandle(nStdHandle: UInt): HANDLE {
            val handle = GetStdHandle(nStdHandle) ?: run {
                throwLastWinError(
                    apiName = "GetStdHandle",
                    arguments = listOf("nStdHandle" to nStdHandle.toString()),
                )
            }
            return handle
        }

        private fun ensureHandleInherit(handle: HANDLE) {
            memScoped {
                val info = alloc<DWORDVar>()
                if (GetHandleInformation(handle, info.ptr) == 0) {
                    throwLastWinError(
                        apiName = "GetHandleInformation",
                        arguments = listOf("handle" to handle.toString()),
                    )
                }
                if (info.value.toInt() and HANDLE_FLAG_INHERIT == 0) {
                    if (SetHandleInformation(
                            hObject = handle,
                            dwMask = HANDLE_FLAG_INHERIT.toUInt(),
                            dwFlags = HANDLE_FLAG_INHERIT.toUInt(),
                        ) == 0
                    ) {
                        throwLastWinError(
                            apiName = "SetHandleInformation",
                            arguments = listOf("handle" to handle.toString()),
                        )
                    }
                }
            }
        }

        private fun windowsQuoteArgs(args: List<String>): String {
            return args.joinToString(" ") { arg ->
                if (arg.isEmpty()) {
                    "\"\""
                } else if (' ' !in arg && '\t' !in arg && '"' !in arg) {
                    arg
                } else {
                    buildString {
                        append('"')
                        for (c in arg) {
                            if (c == '\\' || c == '"') {
                                append('\\')
                            }
                            append(c)
                        }
                        append('"')
                    }
                }
            }
        }

        private fun buildEnvBlock(env: Map<String, String>?, scope: MemScope): CPointer<out CPointed>? {
            if (env.isNullOrEmpty()) return null
            val sorted = env.entries.sortedBy { it.key }
            val size = sorted.sumOf { it.key.length + it.value.length + 2 } + 1
            val block = scope.allocArray<ShortVar>(size)
            var p = 0
            for ((key, value) in sorted) {
                for (b in key) {
                    block[p++] = b.code.toShort()
                }
                block[p++] = '='.code.toShort()
                for (b in value) {
                    block[p++] = b.code.toShort()
                }
                block[p++] = 0
            }
            return block.reinterpret()
        }

    }

}

internal actual fun launchProcess(builder: ProcessBuilderScopeImpl): Process = WinProcessImpl(builder)



