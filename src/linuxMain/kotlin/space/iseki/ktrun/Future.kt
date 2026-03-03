package space.iseki.ktrun

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.ETIMEDOUT
import platform.posix.clock_gettime
import platform.posix.errno
import platform.posix.pthread_cond_broadcast
import platform.posix.pthread_cond_destroy
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_timedwait
import platform.posix.pthread_cond_wait
import platform.posix.pthread_condattr_destroy
import platform.posix.pthread_condattr_init
import platform.posix.pthread_condattr_setclock
import platform.posix.pthread_condattr_t
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.timespec
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlin.time.Duration

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class Future<T> {
    companion object {
        private fun Int.checkFailure(name: String) {
            if (this != 0) failWithErrno(name, this)
        }

        private fun saturatingAdd(a: Long, b: Long): Long {
            val r = a + b
            if (((a xor r) and (b xor r)) < 0) {
                return if (b >= 0) Long.MAX_VALUE else Long.MIN_VALUE
            }
            return r
        }
    }

    private class Sync {

        val arena = Arena()

        val pthreadCondAttrT = arena.alloc<pthread_condattr_t>()

        init {
            pthread_condattr_init(pthreadCondAttrT.ptr).checkFailure("pthread_condattr_init")
            arena.defer { pthread_condattr_destroy(pthreadCondAttrT.ptr) }
            pthread_condattr_setclock(pthreadCondAttrT.ptr, CLOCK_MONOTONIC).checkFailure("pthread_condattr_setclock")
        }

        val pthreadCondT = arena.alloc<pthread_cond_t>()

        init {
            pthread_cond_init(pthreadCondT.ptr, pthreadCondAttrT.ptr).checkFailure("pthread_cond_init")
            arena.defer { pthread_cond_destroy(pthreadCondT.ptr) }
        }

        val pthreadMutexT = arena.alloc<pthread_mutex_t>()

        init {
            pthread_mutex_init(pthreadMutexT.ptr, null).checkFailure("pthread_mutex_init")
            arena.defer { pthread_mutex_destroy(pthreadMutexT.ptr) }
        }

    }

    private val sync = Sync()
    private val cleaner = createCleaner(sync) { it.arena.clear() }

    @Volatile
    private var result: Result<T>? = null

    fun wait(dur: Duration = Duration.INFINITE): Result<T>? {
        require(dur.isPositive() || dur.isInfinite() || dur == Duration.ZERO) { "Duration must be positive or infinite" }
        if (result != null || dur == Duration.ZERO) return result
        if (dur.isInfinite()) {
            while (true) {
                if (result != null) return result
                pthread_mutex_lock(sync.pthreadMutexT.ptr).checkFailure("pthread_mutex_lock")
                try {
                    pthread_cond_wait(
                        sync.pthreadCondT.ptr,
                        sync.pthreadMutexT.ptr,
                    ).checkFailure("pthread_cond_wait")
                } finally {
                    pthread_mutex_unlock(sync.pthreadMutexT.ptr).checkFailure("pthread_mutex_unlock")
                }
            }
        } else {
            memScoped {
                val now = alloc<timespec>()
                if (clock_gettime(CLOCK_MONOTONIC, now.ptr) == -1) {
                    failWithErrno("clock_gettime", errno)
                }
                now.tv_nsec += (dur.inWholeNanoseconds % 1_000_000_000)
                if (now.tv_nsec >= 1_000_000_000) {
                    now.tv_sec += 1
                    now.tv_nsec -= 1_000_000_000
                }
                now.tv_sec = saturatingAdd(now.tv_sec, dur.inWholeSeconds)

                while (true) {
                    pthread_mutex_lock(sync.pthreadMutexT.ptr).checkFailure("pthread_mutex_lock")
                    try {
                        if (result != null) return result
                        val e = pthread_cond_timedwait(sync.pthreadCondT.ptr, sync.pthreadMutexT.ptr, now.ptr)
                        if (e == ETIMEDOUT) return null
                        if (e != 0) failWithErrno("pthread_cond_timedwait", e)
                        return result ?: continue
                    } finally {
                        pthread_mutex_unlock(sync.pthreadMutexT.ptr).checkFailure("pthread_mutex_unlock")
                    }
                }
            }
        }
    }

    fun complete(value: Result<T>) {
        pthread_mutex_lock(sync.pthreadMutexT.ptr).checkFailure("pthread_mutex_lock")
        try {
            if (result != null) return
            result = value
        } finally {
            pthread_mutex_unlock(sync.pthreadMutexT.ptr).checkFailure("pthread_mutex_unlock")
        }
        pthread_cond_broadcast(sync.pthreadCondT.ptr).checkFailure("pthread_cond_broadcast")
    }
}
