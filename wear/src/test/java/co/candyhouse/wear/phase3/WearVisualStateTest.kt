package co.candyhouse.wear.phase3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearVisualStateTest {
    @Test
    fun lockedLocked_usesOneLargeLockedImageState() {
        assertEquals(
            WearVisualState.Single(WearVisualLockState.LOCKED),
            resolveWearVisualState("LOCKED", "LOCKED"),
        )
    }

    @Test
    fun unlockedUnlocked_usesOneLargeUnlockedImageState() {
        assertEquals(
            WearVisualState.Single(WearVisualLockState.UNLOCKED),
            resolveWearVisualState("UNLOCKED", "UNLOCKED"),
        )
    }

    @Test
    fun lockedUnlocked_usesSplitTopLockedBottomUnlocked() {
        assertEquals(
            WearVisualState.Split(WearVisualLockState.LOCKED, WearVisualLockState.UNLOCKED),
            resolveWearVisualState("LOCKED", "UNLOCKED"),
        )
    }

    @Test
    fun unlockedLocked_usesSplitTopUnlockedBottomLocked() {
        assertEquals(
            WearVisualState.Split(WearVisualLockState.UNLOCKED, WearVisualLockState.LOCKED),
            resolveWearVisualState("UNLOCKED", "LOCKED"),
        )
    }

    @Test
    fun oneUnknown_neverCollapsesToDefiniteSingleState() {
        val topUnknown = resolveWearVisualState("UNKNOWN", "LOCKED")
        val bottomUnknown = resolveWearVisualState("UNLOCKED", "UNKNOWN")
        assertEquals(
            WearVisualState.Split(WearVisualLockState.UNKNOWN, WearVisualLockState.LOCKED),
            topUnknown,
        )
        assertEquals(
            WearVisualState.Split(WearVisualLockState.UNLOCKED, WearVisualLockState.UNKNOWN),
            bottomUnknown,
        )
        assertTrue(topUnknown !is WearVisualState.Single)
        assertTrue(bottomUnknown !is WearVisualState.Single)
    }

    @Test
    fun bothUnknown_usesNeutralUnknownState() {
        assertEquals(WearVisualState.Unknown, resolveWearVisualState("UNKNOWN", "UNKNOWN"))
    }

    @Test
    fun visualState_isDisplayOnlyAndDoesNotEncodeCommandTarget() {
        val locked = resolveWearVisualState("LOCKED", "LOCKED")
        val unlocked = resolveWearVisualState("UNLOCKED", "UNLOCKED")
        assertEquals("施錠", locked.groupLabel())
        assertEquals("解錠", unlocked.groupLabel())
        assertEquals(
            WearGroupProtocol.encodeCommand("same-command-id").toList(),
            WearGroupProtocol.encodeCommand("same-command-id").toList(),
        )
    }
}
