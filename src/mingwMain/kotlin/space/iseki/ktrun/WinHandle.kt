package space.iseki.ktrun

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.CloseHandle
import platform.windows.HANDLE
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class)
internal interface WinHandle : AutoCloseable {
    val handle: HANDLE

    companion object {
        val winHandleCounter = atomic(0)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal fun WinHandle(handle: HANDLE): WinHandle {
    val wrapper = WinHandleImpl(handle)
    return object : WinHandle by wrapper {
        @OptIn(ExperimentalNativeApi::class)
        private val cleaner = createCleaner(wrapper) {
            it.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class WinHandleImpl(override val handle: HANDLE) : WinHandle {
    init {
        WinHandle.winHandleCounter.incrementAndGet()
    }

    private val closed = atomic(false)
    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            WinHandle.winHandleCounter.decrementAndGet()
            if (CloseHandle(handle) == 0) throwLastWinError("CloseHandle")
        }
    }
}

