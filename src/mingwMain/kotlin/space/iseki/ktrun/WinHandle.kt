package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.CloseHandle
import platform.windows.HANDLE

@OptIn(ExperimentalForeignApi::class)
private val f = { handle: HANDLE ->
    if (CloseHandle(handle) == 0) throwLastWinError("CloseHandle")
}

@OptIn(ExperimentalForeignApi::class)
internal class WinHandle(val handle: HANDLE) : OsResource<HANDLE>(handle, f)

