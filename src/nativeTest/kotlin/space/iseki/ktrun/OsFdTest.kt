package space.iseki.ktrun

import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(NativeRuntimeApi::class)
class OsFdTest {
    @BeforeTest
    fun initialization() {
        OsResourceInternalHolder.tracingEnabled = true
    }


    @Test
    fun test1() {
        val a = {
            assertEquals(0, OsResourceInternalHolder.counter.value)
            OsResource(1) {}
            assertEquals(1, OsResourceInternalHolder.counter.value)
        }
        a()
        gcAndAssert(0)
    }

    @Volatile
    var a: Any? = null

    @Ignore
    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun test2() {
        repeat(2) {
            assertEquals(0, OsResourceInternalHolder.counter.value)
            repeat(100) {
                a = (1..10).map { OsResource(it) {} }
                if (it == 99) a = null
            }
            repeat(3) {
                a = (1..2).map { OsResource(it) {} }
            }
            repeat(3) {
                a = (1..1).map { OsResource(it) {} }
                if (it == 2) a = null
            }
            a = null
        }
        println(OsResourceInternalHolder.counter.value)
        gcAndAssert(0)
    }

    private fun gcAndAssert(v: Int) {
        for (i in 0..500) {
            if (v == OsResourceInternalHolder.counter.value) {
                println("GC $i times")
                return
            }
            GC.collect()
            usleep(10u)
        }
        assertEquals(v, OsResourceInternalHolder.counter.value)
    }
}