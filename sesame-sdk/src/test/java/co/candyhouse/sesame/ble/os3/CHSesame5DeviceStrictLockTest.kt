package co.candyhouse.sesame.ble.os3

import co.candyhouse.sesame.ble.SesameItemCode
import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.CHResultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CHSesame5DeviceStrictLockTest {
    @Test
    fun strictLock_routesExplicitLockThroughDeviceStrictPathToCommandSink() {
        val sent = mutableListOf<SesameItemCode>()
        val device = deviceWithSink(sent)
        var success = false

        device.strictLock(result = { success = it.isSuccess })

        assertTrue(success)
        assertEquals(listOf(SesameItemCode.lock), sent)
        assertFalse(sent.contains(SesameItemCode.toggle))
    }

    @Test
    fun strictUnlock_routesExplicitUnlockThroughDeviceStrictPathToCommandSink() {
        val sent = mutableListOf<SesameItemCode>()
        val device = deviceWithSink(sent)
        var success = false

        device.strictUnlock(result = { success = it.isSuccess })

        assertTrue(success)
        assertEquals(listOf(SesameItemCode.unlock), sent)
        assertFalse(sent.contains(SesameItemCode.toggle))
    }

    @Test
    fun strictBleUnavailable_reportsFailureWithoutCommand() {
        val sent = mutableListOf<SesameItemCode>()
        val device = CHSesame5Device(
            strictBleAvailability = { result ->
                result(Result.failure(IllegalStateException("BLE unavailable")))
                false
            },
            strictCommandSink = { itemCode, _, result ->
                sent += itemCode
                result(Result.success(CHResultState.CHResultStateBLE(CHEmpty())))
            },
        )
        var failure = false

        device.strictLock(result = { failure = it.isFailure })

        assertTrue(failure)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun bleDropsAfterAvailabilityCheck_commandRemainsExplicitAndNeverBecomesToggle() {
        val sent = mutableListOf<SesameItemCode>()
        var bleConnected = true
        val device = CHSesame5Device(
            strictBleAvailability = {
                val availableAtCheck = bleConnected
                bleConnected = false
                availableAtCheck
            },
            strictCommandSink = { itemCode, _, result ->
                sent += itemCode
                if (bleConnected) {
                    result(Result.success(CHResultState.CHResultStateBLE(CHEmpty())))
                } else {
                    result(Result.failure(IllegalStateException("BLE disconnected")))
                }
            },
        )
        var failure = false

        device.strictUnlock(result = { failure = it.isFailure })

        assertTrue(failure)
        assertEquals(listOf(SesameItemCode.unlock), sent)
        assertFalse(sent.contains(SesameItemCode.toggle))
    }

    @Test
    fun publicCHSesame5Hierarchy_doesNotRequireStrictCapability_butProductionDeviceImplementsIt() {
        assertFalse(CHSesame5StrictLock::class.java.isAssignableFrom(CHSesame5::class.java))
        assertTrue(CHSesame5StrictLock::class.java.isAssignableFrom(CHSesame5Device::class.java))
    }

    private fun deviceWithSink(sent: MutableList<SesameItemCode>): CHSesame5Device = CHSesame5Device(
        strictBleAvailability = { true },
        strictCommandSink = { itemCode, _, result ->
            sent += itemCode
            result(Result.success(CHResultState.CHResultStateBLE(CHEmpty())))
        },
    )
}
