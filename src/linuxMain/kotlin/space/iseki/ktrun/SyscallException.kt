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
        "call: $call, errno: $errorCode, " + strerror(errorCode)?.toKStringFromUtf8().orEmpty()
    }
}

internal fun translateErrno(call: String, errno: Int): Throwable {
    return when (errno) {
        ENOSYS -> UnsupportedOperationException("Unsupported syscall: $call")
        ENOMEM -> OutOfMemoryError("Out of memory in $call")
        else -> SyscallException(call, errno)
    }
}

internal fun failWithErrno(call: String, errno: Int) {
    throw translateErrno(call, errno)
}
