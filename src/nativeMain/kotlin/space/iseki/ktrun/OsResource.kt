package space.iseki.ktrun

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner

internal class OsResourceInternalHolder<T>(
    val resource: T,
    val closeFn: (T) -> Unit,
) : AutoCloseable {
    companion object {
        var tracingEnabled = false
        val counter = atomic(0)
    }

    init {
        if (tracingEnabled) {
            counter.addAndGet(1)
        }
    }

    private var closed = atomic(false)
    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            try {
                closeFn(resource)
            } catch (th: Throwable) {
                closed.value = false
                throw th
            }
            if (tracingEnabled) {
                counter.addAndGet(-1)
            }
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal open class OsResource<T> : AutoCloseable {
    private val holder: OsResourceInternalHolder<T>

    private val cleaner: Cleaner

    constructor(resource: T, closeFn: (T) -> Unit) {
        this.holder = OsResourceInternalHolder(resource, closeFn)
        this.cleaner = createCleaner(holder, OsResourceInternalHolder<T>::close)
    }


    @OptIn(ExperimentalForeignApi::class)
    inline fun <R> useResource(f: (T) -> R): R {
        usePinned { return f(holder.resource) }
    }

    override fun close() {
        holder.close()
    }
}

