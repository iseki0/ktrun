package space.iseki.ktrun

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal fun waitHelper(dur: Duration, block: (dur: Duration) -> Boolean): Boolean {
    require(dur.isPositive() || dur.isInfinite()) { "Duration must be positive or infinite" }
    val step = 1.seconds
    if (dur.isInfinite() || dur < step) {
        return block(dur)
    }
    val begin = TimeSource.Monotonic.markNow()
    while (true) {
        val elapsed = begin.elapsedNow()
        if (elapsed >= dur) {
            return false
        }
        if (block(minOf(step, dur - elapsed))) return true
    }
}
