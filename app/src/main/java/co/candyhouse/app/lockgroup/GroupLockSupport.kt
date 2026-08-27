package co.candyhouse.app.lockgroup

import co.candyhouse.sesame.open.devices.CHSesame5
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.open.devices.base.CHProductModel

internal fun isSupportedGroupLockDevice(device: CHDevices): Boolean = when (device.productModel) {
    CHProductModel.SS5,
    CHProductModel.SS5PRO,
    CHProductModel.SS5US,
    CHProductModel.SS6,
    CHProductModel.SS6Pro,
    CHProductModel.SS6ProSlidingDoor,
    CHProductModel.SSM_MIWA -> device is CHSesame5 && device is CHSesame5StrictLock

    else -> false
}
