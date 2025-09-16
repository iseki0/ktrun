package space.iseki.ktrun

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.CLOCK_MONOTONIC
import platform.posix.ENOMEM
import platform.posix.ENOSYS
import platform.posix.ETIMEDOUT
import platform.posix.PTHREAD_MUTEX_ERRORCHECK
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
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t
import platform.posix.strerror
import platform.posix.timespec
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, ExperimentalTime::class)
internal class CFuture<T> {
    companion object {
        private fun fatal(call: String, _errno: Int = errno) {
            val m = strerror(_errno)?.toKStringFromUtf8()
            val r = "Fatal error on call $call, errno=$_errno, msg=$m"
            terminateWithUnhandledException(RuntimeException(r))
        }

        private fun fail(call: String, _errno: Int = errno) {
            if (_errno == ENOMEM) throw OutOfMemoryError()
            val m = strerror(_errno)?.toKStringFromUtf8()
            val r = "Call $call, errno=$_errno, msg=$m"
            when (_errno) {
                ENOSYS -> throw UnsupportedOperationException(r)
                else -> throw RuntimeException(r)
            }
        }
    }

    private class Holder : AutoCloseable {
        val arena = Arena()
        val mutex: pthread_mutex_t = arena.alloc()
        val mutexAttr: pthread_mutexattr_t = arena.alloc()
        val cond: pthread_cond_t = arena.alloc()
        val condAttr: pthread_condattr_t = arena.alloc()

        init {
            try {
                if (pthread_mutexattr_init(mutexAttr.ptr) != 0) {
                    fail("pthread_mutexattr_init")
                }
                arena.defer {
                    if (pthread_mutexattr_destroy(mutexAttr.ptr) != 0) {
                        fatal("pthread_mutexattr_destroy")
                    }
                }
                if (pthread_mutexattr_settype(mutexAttr.ptr, PTHREAD_MUTEX_ERRORCHECK.toInt()) != 0) {
                    fail("pthread_mutexattr_settype")
                }
                if (pthread_mutex_init(mutex.ptr, mutexAttr.ptr) != 0) {
                    fail("pthread_mutex_init")
                }
                arena.defer {
                    if (pthread_mutex_destroy(mutex.ptr) != 0) {
                        fatal("pthread_mutex_destroy")
                    }
                }
                if (pthread_condattr_init(condAttr.ptr) != 0) {
                    fail("pthread_condattr_init")
                }
                arena.defer {
                    if (pthread_condattr_destroy(condAttr.ptr) != 0) {
                        fatal("pthread_condattr_destroy")
                    }
                }
                if (pthread_condattr_setclock(condAttr.ptr, CLOCK_MONOTONIC) != 0) {
                    fail("pthread_condattr_setclock")
                }
                if (pthread_cond_init(cond.ptr, condAttr.ptr) != 0) {
                    fail("pthread_cond_init")
                }
                arena.defer {
                    if (pthread_cond_destroy(cond.ptr) != 0) {
                        fatal("pthread_cond_destroy")
                    }
                }
            } catch (th: Throwable) {
                arena.clear()
                throw th
            }
        }

        override fun close() {
            arena.clear()
        }
    }

    private val holder = Holder()
    private val cleaner = createCleaner(holder, Holder::close)
    private var value: T? = null
    private var failure: Throwable? = null
    private var isSet = false


    private fun lock() {
        if (pthread_mutex_lock(holder.mutex.ptr) != 0) fail("pthread_mutex_lock")
    }

    private fun unlock() {
        if (pthread_mutex_unlock(holder.mutex.ptr) != 0) fatal("pthread_mutex_unlock")
    }

    private fun broadcast() {
        if (pthread_cond_broadcast(holder.cond.ptr) != 0) fatal("pthread_cond_broadcast")
    }

    private fun doComplete(value: T?, th: Throwable?) {
        lock()
        try {
            if (isSet) {
                throw IllegalStateException("Future already completed")
            }
            this.value = value
            this.failure = th
            isSet = true
            broadcast()
        } finally {
            unlock()
        }
    }

    fun complete(value: T) {
        doComplete(value, null)
    }

    fun completeExceptionally(th: Throwable) {
        doComplete(null, th)
    }

    private inline fun get0(waitFn: () -> Unit): T {
        while (true) {
            val result: T?
            val error: Throwable?
            lock()
            try {
                result = value
                error = failure
                if (!isSet) {
                    waitFn()
                    continue
                }
            } finally {
                unlock()
            }
            when {
                error != null -> throw error
                result != null -> return result
                else -> throw IllegalStateException("Future completed without value or exception")
            }
        }
    }

    fun get(): T {
        return get0 {
            if (pthread_cond_wait(holder.cond.ptr, holder.mutex.ptr) != 0) {
                fatal("pthread_cond_wait")
            }
        }
    }

    fun get(duration: Duration): T {
        check(!duration.isNegative()) { "Negative timeout: $duration" }
        memScoped {
            val ts = alloc<timespec>()
            val now = alloc<timespec>()
            clock_gettime(CLOCK_MONOTONIC, now.ptr)
            ts.tv_sec = now.tv_sec + duration.inWholeSeconds
            ts.tv_nsec = now.tv_nsec + duration.inWholeNanoseconds % 1_000_000_000
            if (ts.tv_nsec >= 1_000_000_000) {
                ts.tv_sec += 1
                ts.tv_nsec -= 1_000_000_000
            }
            return get0 {
                if (pthread_cond_timedwait(holder.cond.ptr, holder.mutex.ptr, ts.ptr) != 0) {
                    when (val errno = errno) {
                        ETIMEDOUT -> throw TimeoutException()
                        else -> fatal("pthread_cond_timedwait", errno)
                    }
                }
            }
        }
    }

    val isDone: Boolean
        get() {
            lock()
            try {
                return isSet
            } finally {
                unlock()
            }
        }

    class TimeoutException : RuntimeException()
}
