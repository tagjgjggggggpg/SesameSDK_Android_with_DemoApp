package co.candyhouse.sesame.utils

import java.security.MessageDigest

/** Stable anonymous identifier for Phase 2 diagnostics. Never returns the source UUID. */
fun p2DiagnosticDeviceId(deviceId: String?): String {
    if (deviceId.isNullOrBlank()) return "unknown"
    val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }
}
