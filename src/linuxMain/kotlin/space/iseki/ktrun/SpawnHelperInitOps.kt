package space.iseki.ktrun

internal interface SpawnHelperInitOps {
    fun createHelperSocketPair(): Pair<Int, Int>
    fun createShimExecutable(): SpawnHelperShimExecutable
    fun spawnShimProcess(shimPath: String, helperSocketFd: Int): Int
    fun closeFd(fd: Int)
    fun unlinkPath(path: String)
}

internal data class SpawnHelperShimExecutable(
    val fd: Int,
    val execPath: String,
    val unlinkPath: String?,
)

internal data class SpawnHelperInitResult(
    val pid: Int,
    val clientSocketFd: Int,
)
