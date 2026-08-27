package co.candyhouse.sesame.ble.os3

import co.candyhouse.sesame.ble.DeviceSegmentType
import co.candyhouse.sesame.ble.SesameItemCode
import co.candyhouse.sesame.db.model.CHDevice
import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.CHSesame5LiveLockState
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.CHResultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CHSesame5DeviceStrictLockTest {
    @Test fun liveTracker_disconnectMakesStateInvalid() {
        val tracker=Sesame5LiveBleStateTracker();tracker.onLiveMechStatus(CHSesame5LiveLockState.LOCKED,10)
        assertEquals(CHSesame5LiveLockState.LOCKED,tracker.snapshot(true)?.lockState)
        assertNull(tracker.snapshot(false))
    }
    @Test fun liveTracker_reconnectBeforeMechStatusHasNoState() {
        val tracker=Sesame5LiveBleStateTracker();tracker.onLiveMechStatus(CHSesame5LiveLockState.LOCKED,10);tracker.invalidate()
        assertNull(tracker.snapshot(true))
    }
    @Test fun liveTracker_reconnectAfterNewMechStatusRestoresState() {
        val tracker=Sesame5LiveBleStateTracker();tracker.onLiveMechStatus(CHSesame5LiveLockState.LOCKED,10);tracker.invalidate();tracker.onLiveMechStatus(CHSesame5LiveLockState.UNLOCKED,20)
        assertEquals(CHSesame5LiveLockState.UNLOCKED,tracker.snapshot(true)?.lockState)
        assertEquals(20,tracker.snapshot(true)?.stateObservedAtMillis)
    }
    @Test fun batterySampleMustBelongToCurrentLiveMechStatus() {
        val tracker=Sesame5LiveBleStateTracker();tracker.onLiveMechStatus(CHSesame5LiveLockState.LOCKED,10);tracker.onLiveMechStatus(CHSesame5LiveLockState.UNLOCKED,20);tracker.onBatteryPercentage(18,10)
        assertNull(tracker.snapshot(true)?.batterySample)
        tracker.onBatteryPercentage(18,20)
        assertEquals(18,tracker.snapshot(true)?.batterySample?.percentage)
    }

    @Test
    fun strictLock_routesExplicitLockThroughDeviceStrictPathToCommandSink() {
        val sent = mutableListOf<SesameItemCode>()
        val device = deviceWithSink(sent)
        var success = false
        device.strictLock(result = { success = it.isSuccess })
        assertTrue(success);assertEquals(listOf(SesameItemCode.lock), sent);assertFalse(sent.contains(SesameItemCode.toggle))
    }

    @Test
    fun strictUnlock_routesExplicitUnlockThroughDeviceStrictPathToCommandSink() {
        val sent = mutableListOf<SesameItemCode>()
        val device = deviceWithSink(sent)
        var success = false
        device.strictUnlock(result = { success = it.isSuccess })
        assertTrue(success);assertEquals(listOf(SesameItemCode.unlock), sent);assertFalse(sent.contains(SesameItemCode.toggle))
    }

    @Test
    fun strictLock_reachesProductionSendStrictBleCommandAndFinalBleTransportAsLock() {
        val sentItemCodes = mutableListOf<UByte>();val sentSegments = mutableListOf<DeviceSegmentType>();val device = deviceWithTransport(sentItemCodes, sentSegments)
        device.strictLock(result = { })
        assertEquals(listOf(SesameItemCode.lock.value), sentItemCodes);assertEquals(listOf(DeviceSegmentType.cipher), sentSegments);assertEquals(0, sentItemCodes.count { it == SesameItemCode.toggle.value });assertEquals(0, sentItemCodes.count { it == SesameItemCode.unlock.value })
    }

    @Test
    fun strictUnlock_reachesProductionSendStrictBleCommandAndFinalBleTransportAsUnlock() {
        val sentItemCodes = mutableListOf<UByte>();val sentSegments = mutableListOf<DeviceSegmentType>();val device = deviceWithTransport(sentItemCodes, sentSegments)
        device.strictUnlock(result = { })
        assertEquals(listOf(SesameItemCode.unlock.value), sentItemCodes);assertEquals(listOf(DeviceSegmentType.cipher), sentSegments);assertEquals(0, sentItemCodes.count { it == SesameItemCode.toggle.value });assertEquals(0, sentItemCodes.count { it == SesameItemCode.lock.value })
    }

    @Test
    fun strictBleUnavailable_reportsFailureWithoutCommand() {
        val sent = mutableListOf<SesameItemCode>()
        val device = CHSesame5Device(strictBleAvailability = { result -> result(Result.failure(IllegalStateException("BLE unavailable")));false },strictCommandSink = { itemCode, _, result -> sent += itemCode;result(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) })
        var failure = false;device.strictLock(result = { failure = it.isFailure });assertTrue(failure);assertTrue(sent.isEmpty())
    }

    @Test
    fun bleDropsAfterAvailabilityCheck_commandRemainsExplicitAndNeverBecomesToggle() {
        val sent = mutableListOf<SesameItemCode>();var bleConnected = true
        val device = CHSesame5Device(strictBleAvailability = { val availableAtCheck = bleConnected;bleConnected = false;availableAtCheck },strictCommandSink = { itemCode, _, result -> sent += itemCode;if (bleConnected) result(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) else result(Result.failure(IllegalStateException("BLE disconnected"))) })
        var failure = false;device.strictUnlock(result = { failure = it.isFailure });assertTrue(failure);assertEquals(listOf(SesameItemCode.unlock), sent);assertFalse(sent.contains(SesameItemCode.toggle))
    }

    @Test fun publicCHSesame5Hierarchy_doesNotRequireStrictCapability_butProductionDeviceImplementsIt(){assertFalse(CHSesame5StrictLock::class.java.isAssignableFrom(CHSesame5::class.java));assertTrue(CHSesame5StrictLock::class.java.isAssignableFrom(CHSesame5Device::class.java))}

    private fun deviceWithSink(sent: MutableList<SesameItemCode>): CHSesame5Device = CHSesame5Device(strictBleAvailability = { true },strictCommandSink = { itemCode, _, result -> sent += itemCode;result(Result.success(CHResultState.CHResultStateBLE(CHEmpty()))) })
    private fun deviceWithTransport(sentItemCodes: MutableList<UByte>,sentSegments: MutableList<DeviceSegmentType>): CHSesame5Device = CHSesame5Device(strictBleAvailability = { true },strictBleTransportSink = { payload, segment, _ -> sentItemCodes += payload.itemCode;sentSegments += segment }).apply { sesame2KeyData = CHDevice(deviceUUID = "00000000-0000-0000-0000-000000000001",deviceModel = "sesame_5",keyIndex = "0000",secretKey = "00112233445566778899aabbccddeeff",sesame2PublicKey = "00") }
}
