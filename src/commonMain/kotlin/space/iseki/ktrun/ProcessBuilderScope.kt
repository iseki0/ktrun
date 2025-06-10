@file:Suppress("UnusedReceiverParameter")

package space.iseki.ktrun

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

interface ProcessBuilderScope {
    var cmdline: List<String>
    var environment: Map<String, String>?
    var workingDirectory: String?
    var stdin: ProcessInHandler
    var stdout: ProcessOutHandler
    var stderr: ProcessOutHandler
    var mergeStderrToStdout: Boolean
}

sealed interface ProcessIOHandler : ProcessOutHandler, ProcessInHandler {
    data object NULL : ProcessIOHandler
    data object PIPE : ProcessIOHandler
    data object INHERIT : ProcessIOHandler
    data class Path(val path: String) : ProcessIOHandler
}

sealed interface ProcessOutHandler {
//    data class Sink(val block: (Readable) -> Unit) : ProcessOutHandler
}

sealed interface ProcessInHandler {
//    data class Source(val block: (Writable) -> Unit) : ProcessInHandler
}

internal class ProcessBuilderScopeImpl : ProcessBuilderScope {
    override var cmdline: List<String> = emptyList()
    override var environment: Map<String, String>? = null
    override var workingDirectory: String? = null
    override var stdin: ProcessInHandler = ProcessIOHandler.NULL
    override var stdout: ProcessOutHandler = ProcessIOHandler.NULL
    override var stderr: ProcessOutHandler = ProcessIOHandler.NULL
    override var mergeStderrToStdout: Boolean = false
}

//fun ProcessBuilderScope.sink(block: (Readable) -> Unit) = ProcessOutHandler.Sink(block)
//fun ProcessBuilderScope.source(block: (Writable) -> Unit) = ProcessInHandler.Source(block)
fun ProcessBuilderScope.nul() = ProcessIOHandler.NULL
fun ProcessBuilderScope.pipe() = ProcessIOHandler.PIPE
fun ProcessBuilderScope.inherit() = ProcessIOHandler.INHERIT
fun ProcessBuilderScope.file(path: String) =
    ProcessIOHandler.Path(path)

@OptIn(ExperimentalContracts::class)
fun buildProcess(block: ProcessBuilderScope.() -> Unit): Process {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val scope = ProcessBuilderScopeImpl()
    scope.block()
    return launchProcess(scope)
}

internal expect fun launchProcess(builder: ProcessBuilderScopeImpl): Process
