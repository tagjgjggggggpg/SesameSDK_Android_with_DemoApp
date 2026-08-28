package co.candyhouse.app.lockgroup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3UxPolicyTest {
    @Test
    fun fullyConfirmedLockedUnlocked_remainsMixedForDisplay() {
        val display = EntranceGroupWidgetProvider.failClosedDisplaySnapshot(
            groupSnap(snap(LockState.LOCKED, 10), snap(LockState.UNLOCKED, 11), 11)
        )
        assertEquals(GroupState.MIXED, display.groupState)
        assertEquals(LockState.LOCKED, display.deviceA.state)
        assertEquals(LockState.UNLOCKED, display.deviceB.state)
    }

    @Test
    fun fullyConfirmedUnlockedLocked_remainsMixedForDisplay() {
        val display = EntranceGroupWidgetProvider.failClosedDisplaySnapshot(
            groupSnap(snap(LockState.UNLOCKED, 10), snap(LockState.LOCKED, 11), 11)
        )
        assertEquals(GroupState.MIXED, display.groupState)
        assertEquals(LockState.UNLOCKED, display.deviceA.state)
        assertEquals(LockState.LOCKED, display.deviceB.state)
    }

    @Test
    fun incompleteConfirmation_persistsUnknownStateForQs() {
        val display = EntranceGroupWidgetProvider.failClosedDisplaySnapshot(
            groupSnap(
                snap(LockState.LOCKED, 10),
                EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false),
                10,
            )
        )
        assertEquals(GroupState.UNKNOWN, display.groupState)
    }

    @Test
    fun fullyConfirmedMixed_persistsMixedStateForQs() {
        val display = EntranceGroupWidgetProvider.failClosedDisplaySnapshot(
            groupSnap(snap(LockState.LOCKED, 10), snap(LockState.UNLOCKED, 11), 11)
        )
        assertEquals(GroupState.MIXED, display.groupState)
    }

    @Test
    fun oneConfirmedOneUnknown_failsClosedInsteadOfMixed() {
        val raw = groupSnap(
            snap(LockState.LOCKED, 10),
            EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false),
            10,
        )
        val display = EntranceGroupWidgetProvider.failClosedDisplaySnapshot(raw)
        assertEquals(GroupState.UNKNOWN, display.groupState)
        assertEquals(LockState.UNKNOWN, display.deviceA.state)
        assertEquals(LockState.UNKNOWN, display.deviceB.state)
        assertEquals(0L, display.capturedAt)
    }

    @Test
    fun partialOperation_neverPublishesFullLookingMixedState() {
        val raw = groupSnap(snap(LockState.LOCKED, 10), snap(LockState.UNLOCKED, 11), 11)
        val display = EntranceGroupWidgetProvider.failClosedPostOperationSnapshot(
            operationResult(GroupOperationStatus.PARTIAL),
            raw,
        )
        assertEquals(GroupState.UNKNOWN, display.groupState)
    }

    @Test
    fun failedOperation_neverPublishesFullLookingMixedState() {
        val raw = groupSnap(snap(LockState.UNLOCKED, 10), snap(LockState.LOCKED, 11), 11)
        val display = EntranceGroupWidgetProvider.failClosedPostOperationSnapshot(
            operationResult(GroupOperationStatus.FAILURE),
            raw,
        )
        assertEquals(GroupState.UNKNOWN, display.groupState)
    }

    @Test
    fun successfulOperation_canPublishFullyConfirmedMixedState() {
        val raw = groupSnap(snap(LockState.LOCKED, 10), snap(LockState.UNLOCKED, 11), 11)
        val display = EntranceGroupWidgetProvider.failClosedPostOperationSnapshot(
            operationResult(GroupOperationStatus.SUCCESS),
            raw,
        )
        assertEquals(GroupState.MIXED, display.groupState)
    }

    @Test
    fun incompleteConfirmation_doesNotPersistConfirmationTime() {
        val partial = groupSnap(
            snap(LockState.LOCKED, 1000),
            EntranceDeviceSnapshot(LockState.UNKNOWN, fresh = false),
            1000,
        )
        assertNull(EntranceGroupWidgetProvider.confirmationTimeToPersist(partial))
    }

    @Test
    fun stateObserveTimeout_isThreeSeconds() {
        assertEquals(3_000L, SesameGroupLockGateway.STATE_OBSERVE_TIMEOUT_MS)
    }

    @Test
    fun operationService_hasNoResultToastOrResultNotification() {
        val source = sourceText("src/main/java/co/candyhouse/app/lockgroup/EntranceGroupOperationService.kt")
        assertFalse(source.contains("Toast.makeText"))
        assertFalse(source.contains("notifyFailure("))
        assertFalse(source.contains("RESULT_NOTIFICATION_ID"))
    }

    @Test
    fun operationService_keepsForegroundAndLowBatteryNotifications() {
        val source = sourceText("src/main/java/co/candyhouse/app/lockgroup/EntranceGroupOperationService.kt")
        assertTrue(source.contains("startForeground(FOREGROUND_NOTIFICATION_ID"))
        assertTrue(source.contains("notifyLowBattery(index, percent)"))
        assertTrue(source.contains("LOW_BATTERY_NOTIFICATION_BASE"))
    }

    @Test
    fun widgetCachePersistence_isFailClosedBeforeSavingStates() {
        val source = sourceText("src/main/java/co/candyhouse/app/lockgroup/EntranceGroupWidgetProvider.kt")
        assertTrue(source.contains("val display = failClosedDisplaySnapshot(snapshot)"))
        assertTrue(source.contains("display_state_confirmed_v2"))
        assertTrue(source.contains("\"状態を確認できません\""))
    }

    private fun snap(state: LockState, at: Long) =
        EntranceDeviceSnapshot(state, fresh = true, stateObservedAtMillis = at)

    private fun groupSnap(a: EntranceDeviceSnapshot, b: EntranceDeviceSnapshot, at: Long) =
        EntranceGroupStateSnapshot(a, b, at)

    private fun operationResult(status: GroupOperationStatus) =
        GroupOperationResult("e", GroupLockAction.UNLOCK, status, emptyList())

    private fun sourceText(path: String): String {
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"))
        return candidates.firstOrNull { it.isFile }?.readText() ?: error("Source file not found: $path")
    }
}
