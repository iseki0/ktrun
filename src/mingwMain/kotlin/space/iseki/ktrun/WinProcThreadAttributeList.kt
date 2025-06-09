package space.iseki.ktrun

import cnames.structs._PROC_THREAD_ATTRIBUTE_LIST
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.DeleteProcThreadAttributeList
import platform.windows.ERROR_INSUFFICIENT_BUFFER
import platform.windows.GetLastError
import platform.windows.InitializeProcThreadAttributeList
import platform.windows.SIZE_TVar

@OptIn(ExperimentalForeignApi::class)
internal class WinProcThreadAttributeList(size: Int) : AutoCloseable {
    private var released = false
    private val arena = Arena()
    val pointer: CPointer<_PROC_THREAD_ATTRIBUTE_LIST>

    init {
        val pSize = arena.alloc<SIZE_TVar>()
        check(
            InitializeProcThreadAttributeList(
                lpAttributeList = null,
                dwAttributeCount = size.toUInt(),
                dwFlags = 0u,
                lpSize = pSize.ptr,
            ) == 0
        ) { "expect InitializeProcThreadAttributeList(measure size) == 0" }
        val lastError = GetLastError()
        if (lastError != ERROR_INSUFFICIENT_BUFFER.toUInt()) {
            // failed to measure the size of the attribute list
            throw Win32ApiException(
                apiName = "InitializeProcThreadAttributeList",
                lastError = lastError,
                arguments = listOf("stage" to "measure size"),
            )
        }
        pointer = interpretCPointer(arena.alloc(pSize.value.toLong(), 0).rawPtr)!!
        if (InitializeProcThreadAttributeList(
                lpAttributeList = pointer,
                dwAttributeCount = size.toUInt(),
                dwFlags = 0u,
                lpSize = pSize.ptr,
            ) == 0
        ) throw Win32ApiException(
            apiName = "InitializeProcThreadAttributeList",
            lastError = GetLastError(),
            arguments = listOf("stage" to "initialize attribute list"),
        )

    }

    override fun close() {
        if (released) released
        released = true
        DeleteProcThreadAttributeList(pointer)
        arena.clear()
    }
}