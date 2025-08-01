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

private fun tryTranslateErrno(call: String, errno: Int): Throwable? {
    return when (errno) {
        ENOSYS -> UnsupportedOperationException("Unsupported syscall: $call")
        ENOMEM -> OutOfMemoryError("Out of memory in $call")
        EIO -> IOException("I/O error in $call")
        else -> null
    }
}

internal fun translateFsError(call: String,errno: Int, file: String) = when(errno) {
    EACCES, EPERM, EISDIR -> AccessDeniedException(file, null, strerror(errno))
    ENOENT -> NoSuchFileException(file, null, strerror(errno))
    ENOTDIR -> NotDirectoryException(file)
    else -> translateErrno(call, errno)
}

internal fun translateErrno(call: String, errno: Int): Throwable {
    return tryTranslateErrno(call, errno) ?: SyscallException(call, errno)
}

internal fun failWithErrno(call: String, errno: Int): Nothing {
    throw translateErrno(call, errno)
}
