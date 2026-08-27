package co.candyhouse.sesame.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class P2DiagnosticIdTest {
    @Test fun stableAndAnonymous() {
        val uuid = "11111111-2222-3333-4444-555555ffffff"
        val id = p2DiagnosticDeviceId(uuid)
        assertEquals(id, p2DiagnosticDeviceId(uuid))
        assertEquals(8, id.length)
        assertFalse(id.contains("ffffff"))
        assertFalse(id.contains(uuid))
    }

    @Test fun distinctUuidsWithSameSuffixRemainDistinct() {
        val a = p2DiagnosticDeviceId("11111111-2222-3333-4444-555555ffffff")
        val b = p2DiagnosticDeviceId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeffffff")
        assertNotEquals(a, b)
    }
}
