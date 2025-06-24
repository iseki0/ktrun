package space.iseki.ktrun

import kotlinx.atomicfu.atomic
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

internal interface OsFd : AutoCloseable {
    val fd: Int
    fun closeAddSuppressed(th: Throwable?): Throwable?
}

@OptIn(ExperimentalNativeApi::class)
internal fun OsFd(fd: Int): OsFd {
    val impl = OsFdImpl(fd)
    return object : OsFd by impl {
        val cleaner = createCleaner(impl, AutoCloseable::close)
    }
}

internal class OsFdImpl(override val fd: Int) : AutoCloseable, OsFd {
    private val closed = atomic(false)
    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            if(platform.posix.close(fd) == -1) failWithErrno("close", platform.posix.errno)
        }
    }

    override fun closeAddSuppressed(th: Throwable?): Throwable? {
        try {
            close()
        } catch (e: Throwable) {
            if (th == null) return e
            th.addSuppressed(e)
        }
        return th
    }
}
