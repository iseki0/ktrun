package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.EACCES
import platform.posix.EIO
import platform.posix.EISDIR
import platform.posix.ENOENT
import platform.posix.ENOMEM
import platform.posix.ENOSYS
import platform.posix.ENOTDIR
import platform.posix.EPERM
import kotlin.experimental.ExperimentalNativeApi

class SyscallException(
    val call: String,
    val errorCode: Int,
) : IOException() {
    override val message: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        "call: $call, errno: $errorCode, " + strerror(errorCode)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun strerror(errno: Int): String {
    return platform.posix.strerror(errno)?.toKStringFromUtf8().orEmpty()
}

internal fun tryTranslateErrno(call: String, errno: Int, file: String): Throwable? {
    return when (errno) {
        ENOSYS -> UnsupportedOperationException("Unsupported operation $call")
        ENOMEM -> OutOfMemoryError("Resource is not enough in $call")
        EIO -> IOException("I/O error in $call")
        EACCES, EPERM, EISDIR -> AccessDeniedException(file, null, strerror(errno))
        ENOENT -> NoSuchFileException(file, null, strerror(errno))
        ENOTDIR -> NotDirectoryException(file)
        else -> null
    }
}

internal fun translateErrnoNoThrow(call: String, errno: Int, file: String = ""): Throwable {
    return tryTranslateErrno(call, errno, file) ?: SyscallException(call, errno)
}

internal fun failWithErrno(call: String, errno: Int, file: String = ""): Nothing {
    throw translateErrnoNoThrow(call, errno, file)
}

@OptIn(ExperimentalNativeApi::class)
internal fun panicWithErrno(call: String, errno: Int): Nothing {
    terminateWithUnhandledException(translateErrnoNoThrow(call, errno))
}
