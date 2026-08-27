package co.candyhouse.app.lockgroup

import android.bluetooth.BluetoothDevice
import co.candyhouse.sesame.ble.SesameItemCode
import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.CHSesame5MechSettings
import co.candyhouse.sesame.open.devices.CHSesame5OpsSettings
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.CHWifiModule2Delegate
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHDeviceStatusDelegate
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.open.devices.base.CHProductModel
import co.candyhouse.sesame.open.devices.base.CHSesameProtocolMechStatus
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHMulticastDelegate
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.CHResultState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SesameGroupLockGatewayTest {
    @Test
    fun lock_usesStrictCapabilityAndNeverLegacyOrToggle() = runBlocking {
        val device = FakeStrictSesame5Device(CHDeviceStatus.Unlocked)
        val gateway = gateway(mapOf("device-a" to device))

        val result = gateway.operate("device-a", GroupLockAction.LOCK)

        assertTrue(result.success)
        assertEquals(LockState.LOCKED, result.finalKnownState)
        assertEquals(1, device.strictLockCalls)
        assertEquals(0, device.strictUnlockCalls)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun unlock_usesStrictCapabilityAndNeverLegacyOrToggle() = runBlocking {
        val device = FakeStrictSesame5Device(CHDeviceStatus.Locked)
        val gateway = gateway(mapOf("device-a" to device))

        val result = gateway.operate("device-a", GroupLockAction.UNLOCK)

        assertTrue(result.success)
        assertEquals(LockState.UNLOCKED, result.finalKnownState)
        assertEquals(0, device.strictLockCalls)
        assertEquals(1, device.strictUnlockCalls)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun mixedState_lock_dispatchesExplicitStrictLockToBothDevices() = runBlocking {
        val alreadyLocked = FakeStrictSesame5Device(CHDeviceStatus.Locked)
        val unlocked = FakeStrictSesame5Device(CHDeviceStatus.Unlocked)
        val gateway = gateway(mapOf("device-a" to alreadyLocked, "device-b" to unlocked))
        val controller = GroupLockController(gateway, delayBetweenDevices = { })
        val group = LockGroup("entrance", "玄関", listOf("device-a", "device-b"))

        val result = controller.lockGroup(group)

        assertEquals(GroupOperationStatus.SUCCESS, result.status)
        assertEquals(1, alreadyLocked.strictLockCalls)
        assertEquals(1, unlocked.strictLockCalls)
        assertNoUnsafeFallback(alreadyLocked)
        assertNoUnsafeFallback(unlocked)
    }

    @Test
    fun mixedState_unlock_dispatchesExplicitStrictUnlockToBothDevices() = runBlocking {
        val locked = FakeStrictSesame5Device(CHDeviceStatus.Locked)
        val alreadyUnlocked = FakeStrictSesame5Device(CHDeviceStatus.Unlocked)
        val gateway = gateway(mapOf("device-a" to locked, "device-b" to alreadyUnlocked))
        val controller = GroupLockController(gateway, delayBetweenDevices = { })
        val group = LockGroup("entrance", "玄関", listOf("device-a", "device-b"))

        val result = controller.unlockGroup(group)

        assertEquals(GroupOperationStatus.SUCCESS, result.status)
        assertEquals(1, locked.strictUnlockCalls)
        assertEquals(1, alreadyUnlocked.strictUnlockCalls)
        assertNoUnsafeFallback(locked)
        assertNoUnsafeFallback(alreadyUnlocked)
    }

    @Test
    fun bleUnavailable_failsBeforeSendingStrictOrLegacyCommand() = runBlocking {
        val device = FakeStrictSesame5Device(CHDeviceStatus.Unlocked)
        val runtime = FakeRuntime(
            devices = mapOf("device-a" to device),
            loginError = "BLE login timed out or disconnected",
        )
        val gateway = SesameGroupLockGateway(runtime, stateObserveTimeoutMs = 5L, statePollMs = 1L)

        val result = gateway.operate("device-a", GroupLockAction.LOCK)

        assertFalse(result.success)
        assertEquals(0, device.strictLockCalls)
        assertEquals(0, device.strictUnlockCalls)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun loginRace_strictFailureNeverFallsBackToLegacyLockUnlockOrToggle() = runBlocking {
        val device = FakeStrictSesame5Device(CHDeviceStatus.Unlocked).apply {
            strictFailure = IllegalStateException("BLE disconnected")
            dropBleBeforeStrictResult = true
        }
        val gateway = gateway(mapOf("device-a" to device))

        val result = gateway.operate("device-a", GroupLockAction.LOCK)

        assertFalse(result.success)
        assertEquals("BLE disconnected", result.error)
        assertEquals(1, device.strictLockCalls)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun commandAcceptedButTargetStateNotConfirmed_isFailure() = runBlocking {
        val device = FakeStrictSesame5Device(CHDeviceStatus.Unlocked).apply {
            confirmTargetState = false
        }
        val gateway = gateway(mapOf("device-a" to device), stateObserveTimeoutMs = 5L)

        val result = gateway.operate("device-a", GroupLockAction.LOCK)

        assertFalse(result.success)
        assertEquals(SesameGroupLockGateway.TARGET_NOT_CONFIRMED_ERROR, result.error)
        assertEquals(LockState.UNLOCKED, result.finalKnownState)
        assertEquals(1, device.strictLockCalls)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun unsupportedCHSesame5WithoutStrictCapability_failsClosed() = runBlocking {
        val device = FakeLegacySesame5(CHDeviceStatus.Unlocked)
        val gateway = gateway(mapOf("device-a" to device))

        val result = gateway.operate("device-a", GroupLockAction.LOCK)

        assertFalse(result.success)
        assertTrue(result.error?.startsWith("Unsupported group lock device") == true)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun os2Product_isExcludedBySharedUiAndGatewaySupportPredicate() = runBlocking {
        val device = FakeStrictSesame5Device(CHDeviceStatus.Unlocked).apply {
            productModel = CHProductModel.SS2
        }
        assertFalse(isSupportedGroupLockDevice(device))
        val gateway = gateway(mapOf("device-a" to device))

        val result = gateway.operate("device-a", GroupLockAction.LOCK)

        assertFalse(result.success)
        assertEquals(0, device.strictLockCalls)
        assertNoUnsafeFallback(device)
    }

    @Test
    fun corruptedAndDeletedDeviceIds_failClosedWithoutAnyCommand() = runBlocking {
        val runtime = FakeRuntime(
            devices = emptyMap(),
            resolutionErrors = mapOf(
                "not-a-uuid" to IllegalArgumentException("Invalid UUID"),
                "deleted-id" to IllegalStateException("Device not found"),
            ),
        )
        val gateway = SesameGroupLockGateway(runtime, stateObserveTimeoutMs = 5L, statePollMs = 1L)

        val corrupted = gateway.operate("not-a-uuid", GroupLockAction.LOCK)
        val deleted = gateway.operate("deleted-id", GroupLockAction.UNLOCK)

        assertFalse(corrupted.success)
        assertEquals("Invalid UUID", corrupted.error)
        assertFalse(deleted.success)
        assertEquals("Device not found", deleted.error)
        assertEquals(0, runtime.historyTagCalls)
    }

    private fun gateway(
        devices: Map<String, CHDevices>,
        stateObserveTimeoutMs: Long = 20L,
    ): SesameGroupLockGateway = SesameGroupLockGateway(
        runtime = FakeRuntime(devices),
        stateObserveTimeoutMs = stateObserveTimeoutMs,
        statePollMs = 1L,
    )

    private fun assertNoUnsafeFallback(device: FakeLegacySesame5) {
        assertEquals(0, device.legacyLockCalls)
        assertEquals(0, device.legacyUnlockCalls)
        assertEquals(0, device.toggleCalls)
        assertEquals(0, device.iotToggleCalls)
    }

    private class FakeRuntime(
        private val devices: Map<String, CHDevices>,
        private val resolutionErrors: Map<String, Throwable> = emptyMap(),
        private val scanError: String? = null,
        private val loginError: String? = null,
    ) : SesameGroupLockRuntime {
        var historyTagCalls = 0
        var finishCalls = 0

        override suspend fun resolveDevice(deviceId: String): Result<CHDevices> {
            resolutionErrors[deviceId]?.let { return Result.failure(it) }
            return devices[deviceId]?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Device not found"))
        }

        override suspend fun ensureScan(): String? = scanError

        override suspend fun waitForBleLogin(device: CHDevices): String? = loginError

        override suspend fun finishOperation() {
            finishCalls += 1
        }

        override fun historyTag(): ByteArray {
            historyTagCalls += 1
            return byteArrayOf(0x01)
        }
    }

    private open class FakeLegacySesame5(initialStatus: CHDeviceStatus) : CHSesame5 {
        override var mechStatus: CHSesameProtocolMechStatus? = null
        override var deviceTimestamp: Long? = null
        override var loginTimestamp: Long? = null
        override var delegate: CHDeviceStatusDelegate? = null
        override val multicastDelegate = CHMulticastDelegate<CHWifiModule2Delegate>()
        override var deviceStatus: CHDeviceStatus = initialStatus
        override var deviceShadowStatus: CHDeviceStatus? = null
        override var rssi: Int? = 0
        override var bleTxPower: Byte = 0
        override var sensorDetectIntervalMs: Short = -1
        override var lockUnlockSwitchPoint: Short = 45
        override var hasLockUnlockSwitchPointSetting: Boolean = false
        override var deviceId: UUID? = UUID.randomUUID()
        override var isRegistered: Boolean = true
        override var productModel: CHProductModel = CHProductModel.SS5
        override var batteryPercentage: Int? = null
        override var mechSetting: CHSesame5MechSettings? = null
        override var opsSetting: CHSesame5OpsSettings? = null

        var legacyLockCalls = 0
        var legacyUnlockCalls = 0
        var toggleCalls = 0
        var iotToggleCalls = 0

        override fun connect(result: CHResult<CHEmpty>) {}
        override fun disconnect(result: CHResult<CHEmpty>) {}
        override fun dropKey(result: CHResult<CHEmpty>) {}
        override fun getVersionTag(result: CHResult<String>) {}
        override fun register(result: CHResult<CHEmpty>) {}
        override fun reset(result: CHResult<CHEmpty>) {}
        override fun updateFirmware(onResponse: CHResult<BluetoothDevice>) {}

        override fun lock(historytag: ByteArray?, result: CHResult<CHEmpty>) {
            legacyLockCalls += 1
            iotToggleCalls += 1
            result(Result.failure(IllegalStateException("legacy lock path must not be used by group operations")))
        }

        override fun unlock(historytag: ByteArray?, result: CHResult<CHEmpty>) {
            legacyUnlockCalls += 1
            iotToggleCalls += 1
            result(Result.failure(IllegalStateException("legacy unlock path must not be used by group operations")))
        }

        override fun toggle(historytag: ByteArray?, result: CHResult<CHEmpty>) {
            toggleCalls += 1
            iotToggleCalls += 1
            result(Result.failure(IllegalStateException("toggle path must not be used by group operations")))
        }

        override fun magnet(result: CHResult<CHEmpty>) {}
        override fun configureLockPosition(lockTarget: Short, unlockTarget: Short, result: CHResult<CHEmpty>) {}
        override fun autolock(delay: Int, result: CHResult<Int>) {}
        override fun opSensorControl(isEnable: Int, result: CHResult<Int>) {}
    }

    private class FakeStrictSesame5Device(initialStatus: CHDeviceStatus) :
        FakeLegacySesame5(initialStatus),
        CHSesame5StrictLock {
        var strictLockCalls = 0
        var strictUnlockCalls = 0
        var confirmTargetState = true
        var strictFailure: Throwable? = null
        var dropBleBeforeStrictResult = false

        override fun strictLock(historytag: ByteArray?, result: CHResult<CHEmpty>) {
            strictLockCalls += 1
            completeStrict(GroupLockAction.LOCK, result)
        }

        override fun strictUnlock(historytag: ByteArray?, result: CHResult<CHEmpty>) {
            strictUnlockCalls += 1
            completeStrict(GroupLockAction.UNLOCK, result)
        }

        private fun completeStrict(action: GroupLockAction, result: CHResult<CHEmpty>) {
            if (dropBleBeforeStrictResult) {
                deviceStatus = CHDeviceStatus.NoBleSignal
            }
            strictFailure?.let {
                result(Result.failure(it))
                return
            }
            if (confirmTargetState) {
                deviceStatus = if (action == GroupLockAction.LOCK) CHDeviceStatus.Locked else CHDeviceStatus.Unlocked
            }
            result(Result.success(CHResultState.CHResultStateBLE(CHEmpty())))
        }
    }
}
