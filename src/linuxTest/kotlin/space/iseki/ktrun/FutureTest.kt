package space.iseki.ktrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FutureTest {
    @Test
    fun waitZeroReturnsNullWhenIncomplete() {
        val future = Future<Int>()
        assertNull(future.wait(Duration.ZERO))
    }

    @Test
    fun waitPositiveTimesOutWhenIncomplete() {
        val future = Future<Int>()
        assertNull(future.wait(10.milliseconds))
    }

    @Test
    fun waitNegativeThrows() {
        val future = Future<Int>()
        assertFailsWith<IllegalArgumentException> {
            future.wait((-1).seconds)
        }
    }

    @Test
    fun completeSuccessThenWaitReturnsSuccess() {
        val future = Future<Int>()
        future.complete(Result.success(42))

        val result = future.wait(Duration.ZERO)
        assertTrue(result != null && result.isSuccess)
        assertEquals(42, result.getOrThrow())
    }

    @Test
    fun completeFailureThenWaitReturnsFailure() {
        val future = Future<Int>()
        val err = IllegalStateException("boom")
        future.complete(Result.failure(err))

        val result = future.wait(Duration.ZERO)
        assertTrue(result != null && result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("boom", result.exceptionOrNull()!!.message)
    }

    @Test
    fun completeIsOneShotFirstResultWins() {
        val future = Future<Int>()
        future.complete(Result.success(1))
        future.complete(Result.success(2))
        future.complete(Result.failure(IllegalStateException("late")))

        val result = future.wait(Duration.ZERO)
        assertTrue(result != null && result.isSuccess)
        assertEquals(1, result.getOrThrow())
    }
}
