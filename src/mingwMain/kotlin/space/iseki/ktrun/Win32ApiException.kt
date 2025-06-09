package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import platform.windows.FORMAT_MESSAGE_ALLOCATE_BUFFER
import platform.windows.FORMAT_MESSAGE_FROM_SYSTEM
import platform.windows.FORMAT_MESSAGE_IGNORE_INSERTS
import platform.windows.FormatMessageW
import platform.windows.GetLastError
import platform.windows.LPTSTRVar
import platform.windows.LocalFree

@OptIn(ExperimentalForeignApi::class)
internal class Win32ApiException(
    val apiName: String,
    val lastError: UInt,
    val arguments: List<Pair<String, String>> = emptyList(),
) : RuntimeException() {

    override val message: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildString {
            append("Win32 API call failed: $apiName")
            val msg = memScoped {
                val v = alloc<LPTSTRVar>()
                FormatMessageW(
                    dwFlags = FORMAT_MESSAGE_ALLOCATE_BUFFER.toUInt() or FORMAT_MESSAGE_FROM_SYSTEM.toUInt() or FORMAT_MESSAGE_IGNORE_INSERTS.toUInt(),
                    lpSource = null,
                    dwMessageId = lastError,
                    dwLanguageId = 0u,
                    lpBuffer = v.ptr.reinterpret(),
                    nSize = 0u,
                    Arguments = null,
                )
                v.value?.toKStringFromUtf16().orEmpty().also { LocalFree(v.value) }
            }
            append(": ")
            append(msg)
            append(". error code: $lastError")
        }
    }
}

internal fun throwLastWinError(apiName: String, arguments: List<Pair<String, String>> = emptyList()): Nothing {
    throw Win32ApiException(
        apiName = apiName,
        lastError = GetLastError(),
        arguments = arguments,
    )
}

