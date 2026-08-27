package co.candyhouse.sesame.ble.os3

import co.candyhouse.sesame.ble.SesameItemCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictSesame5CommandExecutorTest {

    @Test
    fun connected_unlock_sendsExplicitUnlock_andNeverToggle() {
        val sent = mutableListOf<SesameItemCode>()
        val executor = StrictSesame5CommandExecutor(
            isBleAvailable = { true },
            sendCommand = { sent += it }
        )

        executor.unlock()

        assertEquals(listOf(SesameItemCode.unlock), sent)
        assertNoToggle(sent)
    }

    @Test
    fun connected_lock_sendsExplicitLock_andNeverToggle() {
        val sent = mutableListOf<SesameItemCode>()
        val executor = StrictSesame5CommandExecutor(
            isBleAvailable = { true },
            sendCommand = { sent += it }
        )

        executor.lock()

        assertEquals(listOf(SesameItemCode.lock), sent)
        assertNoToggle(sent)
    }

    @Test
    fun bleOff_failsWithoutSendingCommand_andNeverToggle() {
        val sent = mutableListOf<SesameItemCode>()
        var failureReported = false
        val executor = StrictSesame5CommandExecutor(
            isBleAvailable = {
                failureReported = true
                false
            },
            sendCommand = { sent += it }
        )

        executor.unlock()

        assertTrue(failureReported)
        assertTrue(sent.isEmpty())
        assertNoToggle(sent)
    }

    @Test
    fun bleDisconnected_failsWithoutSendingCommand_andNeverToggle() {
        val sent = mutableListOf<SesameItemCode>()
        var failureReported = false
        val executor = StrictSesame5CommandExecutor(
            isBleAvailable = {
                failureReported = true
                false
            },
            sendCommand = { sent += it }
        )

        executor.lock()

        assertTrue(failureReported)
        assertTrue(sent.isEmpty())
        assertNoToggle(sent)
    }

    @Test
    fun unlogged_failsWithoutSendingCommand_andNeverToggle() {
        val sent = mutableListOf<SesameItemCode>()
        var failureReported = false
        val executor = StrictSesame5CommandExecutor(
            isBleAvailable = {
                failureReported = true
                false
            },
            sendCommand = { sent += it }
        )

        executor.unlock()

        assertTrue(failureReported)
        assertTrue(sent.isEmpty())
        assertNoToggle(sent)
    }

    @Test
    fun bleChangesAfterAvailabilityCheck_beforeSend_failureStillNeverToggles() {
        val sent = mutableListOf<SesameItemCode>()
        var bleConnected = true
        var sendFailureReported = false
        val executor = StrictSesame5CommandExecutor(
            isBleAvailable = {
                val availableAtCheck = bleConnected
                // Simulate the link dropping immediately after the strict BLE gate.
                bleConnected = false
                availableAtCheck
            },
            sendCommand = { itemCode ->
                sent += itemCode
                if (!bleConnected) sendFailureReported = true
            }
        )

        executor.unlock()

        assertTrue(sendFailureReported)
        assertEquals(listOf(SesameItemCode.unlock), sent)
        assertNoToggle(sent)
    }

    private fun assertNoToggle(sent: List<SesameItemCode>) {
        assertFalse("strict operation must never send toggle", sent.contains(SesameItemCode.toggle))
    }
}
