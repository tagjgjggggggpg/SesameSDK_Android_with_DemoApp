package co.candyhouse.sesame.ble.os3

import co.candyhouse.sesame.ble.SesameItemCode

/**
 * Dispatches SESAME 5-family lock operations through BLE only.
 *
 * This path is intentionally separate from CHSesame5Device.lock/unlock so a
 * group operation can never inherit their IoT/toggle fallback behavior.
 */
internal class StrictSesame5CommandExecutor(
    private val isBleAvailable: () -> Boolean,
    private val sendCommand: (SesameItemCode) -> Unit,
) {
    fun lock() = execute(SesameItemCode.lock)

    fun unlock() = execute(SesameItemCode.unlock)

    private fun execute(itemCode: SesameItemCode) {
        check(itemCode == SesameItemCode.lock || itemCode == SesameItemCode.unlock) {
            "Strict lock operations may only send lock or unlock"
        }
        if (!isBleAvailable()) return
        sendCommand(itemCode)
    }
}
