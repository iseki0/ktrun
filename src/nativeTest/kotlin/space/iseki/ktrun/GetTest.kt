package space.iseki.ktrun

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import kotlin.test.assertEquals

class GetTest {

    @OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
    @Test
    fun testGetInt() {
        val b = ByteArray(4)
        memScoped {
            b.usePinned { it.addressOf(0).reinterpret<IntVar>().pointed.value = 1 }
        }
        assertEquals(1, b.getIntAt(0))
        if (Platform.isLittleEndian) {
            assertEquals(1, b[0])
        } else {
            assertEquals(1, b[3])
        }
    }
}
