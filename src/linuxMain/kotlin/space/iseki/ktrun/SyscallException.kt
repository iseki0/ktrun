package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.ENOMEM
import platform.posix.ENOSYS
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
class SyscallException(
    val call: String,
    val errorCode: Int,
) : RuntimeException() {
    override val message: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        strerror(errorCode)?.toKStringFromUtf8().orEmpty()
    }
}

internal fun Int.checkCallResult(call: String) {
    when (this) {
        ENOSYS -> throw UnsupportedOperationException("Unsupported syscall: $call")
        ENOMEM -> throw OutOfMemoryError("Out of memory in $call")
        else -> if (this != 0) throw SyscallException(call, this)
    }
}
