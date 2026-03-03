package space.iseki.ktrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpawnHelperInitTest {
    private class FakeInitOps(
        private val failOnCreateSocket: Boolean = false,
        private val failOnCreateShim: Boolean = false,
        private val failOnSpawn: Boolean = false,
    ) : SpawnHelperInitOps {
        val closed = mutableListOf<Int>()
        val unlinked = mutableListOf<String>()
        val calls = mutableListOf<String>()

        override fun createHelperSocketPair(): Pair<Int, Int> {
            calls += "createHelperSocketPair"
            if (failOnCreateSocket) {
                throw IllegalStateException("create socket failed")
            }
            return 100 to 101
        }

        override fun createShimExecutable(): SpawnHelperShimExecutable {
            calls += "createShimExecutable"
            if (failOnCreateShim) {
                throw IllegalStateException("create shim failed")
            }
            return SpawnHelperShimExecutable(
                fd = 200,
                execPath = "/tmp/fake-shim",
                unlinkPath = "/tmp/fake-shim",
            )
        }

        override fun spawnShimProcess(shimPath: String, helperSocketFd: Int): Int {
            calls += "spawnShimProcess"
            if (failOnSpawn) {
                throw IllegalStateException("spawn failed")
            }
            return 12345
        }

        override fun closeFd(fd: Int) {
            closed += fd
        }

        override fun unlinkPath(path: String) {
            unlinked += path
        }
    }

    @Test
    fun initializeSuccessCleansUpTemporaryResources() {
        val ops = FakeInitOps()

        val result = initializeSpawnHelper(ops)

        assertEquals(12345, result.pid)
        assertEquals(100, result.clientSocketFd)
        assertEquals(listOf(101, 200), ops.closed)
        assertEquals(listOf("/tmp/fake-shim"), ops.unlinked)
        assertEquals(
            listOf("createHelperSocketPair", "createShimExecutable", "spawnShimProcess"),
            ops.calls,
        )
    }

    @Test
    fun initializeFailureAlsoClosesClientSocket() {
        val ops = FakeInitOps(failOnSpawn = true)

        assertFailsWith<IllegalStateException> {
            initializeSpawnHelper(ops)
        }

        assertEquals(listOf(100, 101, 200), ops.closed)
        assertEquals(listOf("/tmp/fake-shim"), ops.unlinked)
        assertEquals(
            listOf("createHelperSocketPair", "createShimExecutable", "spawnShimProcess"),
            ops.calls,
        )
    }

    @Test
    fun initializeFailureWhileCreatingShimOnlyClosesSocketPair() {
        val ops = FakeInitOps(failOnCreateShim = true)

        assertFailsWith<IllegalStateException> {
            initializeSpawnHelper(ops)
        }

        assertEquals(listOf(100, 101), ops.closed)
        assertEquals(emptyList(), ops.unlinked)
        assertEquals(
            listOf("createHelperSocketPair", "createShimExecutable"),
            ops.calls,
        )
    }

    @Test
    fun initializeFailureWhileCreatingSocketDoesNotRunCleanupHooks() {
        val ops = FakeInitOps(failOnCreateSocket = true)

        assertFailsWith<IllegalStateException> {
            initializeSpawnHelper(ops)
        }

        assertEquals(emptyList(), ops.closed)
        assertEquals(emptyList(), ops.unlinked)
        assertEquals(listOf("createHelperSocketPair"), ops.calls)
    }
}
