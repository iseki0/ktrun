package space.iseki.ktrun

import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.CreatePipe
import platform.windows.ERROR_BROKEN_PIPE
import platform.windows.GetLastError
import platform.windows.HANDLEVar
import platform.windows.ReadFile
import platform.windows.WriteFile


@OptIn(ExperimentalForeignApi::class)
internal class WinPipe {
    val read: WinHandle
    val write: WinHandle
    private val readCloseLock = ReentrantLock()
    private val writeCloseLock = ReentrantLock()
    private var readClosed = false
    private var writeClosed = false

    init {
        memScoped {
            val readHandle = alloc<HANDLEVar>()
            val writeHandle = alloc<HANDLEVar>()
            if (CreatePipe(
                    hReadPipe = readHandle.ptr,
                    hWritePipe = writeHandle.ptr,
                    lpPipeAttributes = null,
                    nSize = 0u,
                ) == 0
            ) throwLastWinError("CreatePipe")
            read = WinHandle(readHandle.value!!)
            write = WinHandle(writeHandle.value!!)
        }
    }

    fun closeWrite() {
        writeCloseLock.withLock { write.close() }
    }

    fun closeRead() {
        readCloseLock.withLock { read.close() }
    }

    fun close() {
        closeWrite()
        closeRead()
    }

    private val readable = object : Readable {
        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            readCloseLock.withLock {
                Readable.checkBounds(buf, offset, length)
                if (readClosed) throw IllegalStateException("Pipe is closed for reading")
                memScoped {
                    val totalRead = alloc<UIntVar>()
                    buf.usePinned { pinned ->
                        val r = ReadFile(
                            hFile = read.handle,
                            lpBuffer = pinned.addressOf(offset),
                            nNumberOfBytesToRead = length.toUInt(),
                            lpNumberOfBytesRead = totalRead.ptr,
                            lpOverlapped = null,
                        )
                        if (r != 0) {
                            return totalRead.value.toInt()
                        }
                        val e = GetLastError()
                        if (e == ERROR_BROKEN_PIPE.toUInt()) {
                            return -1
                        }
                        throw Win32ApiException(apiName = "ReadFile", lastError = e)
                    }
                }
            }
        }

        override fun close() {
            closeRead()
        }
    }

    fun readable(): Readable = readable

    private val writable = object : Writable {
        override fun write(buf: ByteArray, offset: Int, length: Int): Int {
            writeCloseLock.withLock {
                Writable.checkBounds(buf, offset, length)
                if (writeClosed) throw IllegalStateException("Pipe is closed for writing")
                memScoped {
                    val totalWritten = alloc<UIntVar>()
                    buf.usePinned { pinned ->
                        val r = WriteFile(
                            hFile = write.handle,
                            lpBuffer = pinned.addressOf(offset),
                            nNumberOfBytesToWrite = length.toUInt(),
                            lpNumberOfBytesWritten = totalWritten.ptr,
                            lpOverlapped = null,
                        )
                        if (r != 0) {
                            return totalWritten.value.toInt().also { check(it == length) { "short written" } }
                        }
                        throw Win32ApiException(apiName = "WriteFile", lastError = GetLastError())
                    }
                }
            }
        }

        override fun flush() {}

        override fun close() {
            closeWrite()
        }
    }


    fun writable(): Writable = writable
}

