package space.iseki.ktrun

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
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

    val tracing = tracingEnabled
    init {
        if (tracing) {
            println("Tracing: resource allocated: $this -> $resource")
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
            if (tracing) {
                if (counter.addAndGet(-1) < 0) {
                    error("OsResource counter < 0, close called too many times?")
                }
                if (counter.value < 0) {
                    error("OsResource counter < 0, close called too many times?(2)")
                }
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

    protected val unsafeResource: T get() = holder.resource

    @OptIn(ExperimentalForeignApi::class)
    inline fun <R> useResource(f: (T) -> R): R {
        return f(holder.resource)
        // In theory, we should pin the resource here, but in practice, it seems unnecessary for most use cases.
        // usePinned { return f(holder.resource) }
    }

    override fun close() {
        holder.close()
    }
}

