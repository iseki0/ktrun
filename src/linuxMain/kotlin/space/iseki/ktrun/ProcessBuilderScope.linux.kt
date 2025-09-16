package space.iseki.ktrun

internal actual fun launchProcess(builder: ProcessBuilderScopeImpl): Process {
    return ProcessImpl(builder)
}
