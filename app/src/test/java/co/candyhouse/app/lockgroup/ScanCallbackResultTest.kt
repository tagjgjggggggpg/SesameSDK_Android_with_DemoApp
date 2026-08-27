package co.candyhouse.app.lockgroup

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanCallbackResultTest {
    @Test fun callbackSuccess_isNotClassifiedAsTimeout() = runBlocking {
        assertEquals(TimedBleCallbackResult.Success, awaitTimedBleCallback(100) { null })
    }

    @Test fun callbackFailure_isFailure() = runBlocking {
        val result = awaitTimedBleCallback(100) { IllegalStateException("scan failed") }
        assertTrue(result is TimedBleCallbackResult.Failure)
        assertEquals("scan failed", (result as TimedBleCallbackResult.Failure).error.message)
    }

    @Test fun callbackNotReached_isTimeout() = runBlocking {
        val result = awaitTimedBleCallback(5) { delay(50); null }
        assertEquals(TimedBleCallbackResult.Timeout, result)
    }
}
