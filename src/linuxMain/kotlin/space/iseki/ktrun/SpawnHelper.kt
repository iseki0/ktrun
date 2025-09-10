package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import platform.posix.errno
import space.iseki.ktrun.native.initHelper
import space.iseki.ktrun.native.startHelper
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
internal class SpawnHelper {
    companion object {
        init {
            memScoped {
                if (initHelper() == -1) {
                    val errno = errno
                    tryTranslateErrno("initHelper", errno)?.let { throw it }
                    throw RuntimeException("initHelper failed: errno=$errno, ${strerror(errno)}")
                }
            }
        }
    }

    private var fd: LinuxFd

    init {
        try {
            memScoped {
                startHelper().useContents {
                    if (childErrno != 0) failWithErrno("spawnHelper/children", childErrno)
                    if (commFd == -1) failWithErrno("spawnHelper", errno)
                    this@SpawnHelper.fd = LinuxFd(commFd)
                }
                Worker.start().execute(
                    mode = TransferMode.SAFE,
                    producer = { this@SpawnHelper },
                    job = { it.loop() },
                )
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to start SpawnHelper, ${e.message}", e)
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    private fun loop() {
        fd.useResource { fd ->
            TODO()
        }
    }

}

