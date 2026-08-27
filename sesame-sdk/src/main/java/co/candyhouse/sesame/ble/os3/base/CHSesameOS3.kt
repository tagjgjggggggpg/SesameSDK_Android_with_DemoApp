package co.candyhouse.sesame.ble.os3.base

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import co.candyhouse.sesame.BuildConfig
import co.candyhouse.sesame.ble.CHBaseDevice
import co.candyhouse.sesame.ble.CHDeviceUtil
import co.candyhouse.sesame.ble.CHadv
import co.candyhouse.sesame.ble.DeviceSegmentType
import co.candyhouse.sesame.ble.SSM2OpCode
import co.candyhouse.sesame.ble.SSM3PublishPayload
import co.candyhouse.sesame.ble.SSM3ResponsePayload
import co.candyhouse.sesame.ble.Sesame2Chracs
import co.candyhouse.sesame.ble.SesameBleTransmit
import co.candyhouse.sesame.ble.SesameItemCode
import co.candyhouse.sesame.ble.SesameNotifypayload
import co.candyhouse.sesame.ble.SesameResultCode
import co.candyhouse.sesame.ble.isBleAvailable
import co.candyhouse.sesame.ble.os2.CHError
import co.candyhouse.sesame.open.CHBleManager
import co.candyhouse.sesame.open.CHBleManager.appContext
import co.candyhouse.sesame.open.CHBleManager.bluetoothAdapter
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.open.devices.base.CHProductModel
import co.candyhouse.sesame.open.devices.base.NSError
import co.candyhouse.sesame.server.CHAPIClientBiz
import co.candyhouse.sesame.server.dto.CHRemoveSignKeyRequest
import co.candyhouse.sesame.utils.BleBaseType
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.CHResultState
import co.candyhouse.sesame.utils.L
import co.candyhouse.sesame.utils.isInternetAvailable
import co.candyhouse.sesame.utils.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

interface CHSesameOS3Publish {
    fun onGattSesamePublish(receivePayload: SSM3PublishPayload)
}
internal typealias SesameOS3ResponseCallback = (result: SSM3ResponsePayload) -> Unit
internal typealias thisItemCodeLastSendTime = Long

@SuppressLint("MissingPermission")
internal open class CHSesameOS3 : CHBaseDevice(), CHSesameOS3Publish {

    var cipher: SesameOS3BleCipher? = null//[加解密層]
    var cmdCallBack: MutableMap<UByte, SesameOS3ResponseCallback> = mutableMapOf()
    private var cmdCallBackMap: ConcurrentHashMap<UByte, thisItemCodeLastSendTime> = ConcurrentHashMap()
    private var semaphore: Semaphore = Semaphore(1)
    @Volatile private var diagGattState: Int? = null

    private fun diagId(): String = deviceId?.toString()?.takeLast(6) ?: "unknown"
    private fun diag(message: String) {
        if (!BuildConfig.DEBUG) return
        try {
            L.d("P2OS3BLE", "dev=${diagId()} $message")
        } catch (_: RuntimeException) {
            // Diagnostics must never alter BLE behavior or break local JVM tests.
        }
    }

    open fun connect(result: CHResult<CHEmpty>) {
        val advertisement = (this as? CHDeviceUtil)?.advertisement
        val deviceAddress = advertisement?.device?.address
        if (deviceAddress == null) {
            L.d("[say]", "[connect] Advertisement or device address is null")
            result.invoke(Result.failure(CHError.Noble.value))
            return
        }
        if (CHBleManager.connectR.contains(deviceAddress)) {
            L.d("[say]", "[connect] 已连接")
            return
        } else {
            CHBleManager.connectR.add(deviceAddress)
        }
        deviceStatus = CHDeviceStatus.BleConnecting
        result.invoke(Result.success(CHResultState.CHResultStateBLE(CHEmpty())))
        if (bluetoothAdapter == null) {
            L.d("[say]", "[connect] BluetoothAdapter is null")
            result.invoke(Result.failure(CHError.Noble.value))
            return
        }
        if (appContext == null) {
            result.invoke(Result.failure(CHError.Noble.value))
            return
        }
        val gattCallback = mBluetoothGattCallback
        if (gattCallback == null) {
            result.invoke(Result.failure(CHError.Noble.value))
            return
        }
        val remoteDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)
        diag("connectGatt start logicalStatus=$deviceStatus")
        remoteDevice.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val mBluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            diag("notify received bytes=${characteristic.value.size} logicalStatus=$deviceStatus gattState=$diagGattState")
            handleCharacteristicChanged(characteristic.value)
        }

        private fun handleCharacteristicChanged(value: ByteArray) {
            L.d("hcia", "[ssm][say]:" + value.toHexString() + ", bytes: " + value.size)
            val ssmSay = gattRxBuffer.feed(value)
            if (ssmSay?.first == DeviceSegmentType.plain) {
                parseNotifyPayload(ssmSay.second)
            } else if (ssmSay?.first == DeviceSegmentType.cipher) {
                parseNotifyPayload(cipher!!.decrypt(ssmSay.second))
            }
        }

        private fun parseNotifyPayload(palntext: ByteArray) {
            val ssm2notify = SesameNotifypayload(palntext)
            L.d("[say]", "[ss5] ssm2notify.notifyOpCode:" + ssm2notify.notifyOpCode)
            if (ssm2notify.notifyOpCode == SSM2OpCode.response) {
                onGattSesameResponse(SSM3ResponsePayload(ssm2notify.payload))
            } else if (ssm2notify.notifyOpCode == SSM2OpCode.publish) {
                val publish = SSM3PublishPayload(ssm2notify.payload)
                diag("publish received item=${publish.cmdItCode} at=${System.currentTimeMillis()} logicalStatus=$deviceStatus")
                onGattSesamePublish(publish)
            }
        }

        private fun onGattSesameResponse(ssm2ResponsePayload: SSM3ResponsePayload) {
            val item = ssm2ResponsePayload.cmdItCode
            val callback = cmdCallBack[item]
            diag("response received item=$item result=${ssm2ResponsePayload.cmdResultCode} callbackPresent=${callback != null} at=${System.currentTimeMillis()}")
            L.d("onGattSesameResponse", "☆1、 ssm2ResponsePayload.cmdItCode: $item")
            if (callback != null) {
                diag("callback invoke item=$item at=${System.currentTimeMillis()}")
                callback.invoke(ssm2ResponsePayload)
                diag("callback invoke returned item=$item at=${System.currentTimeMillis()}")
            }
            L.d("onGattSesameResponse", "☆2、 ssm2ResponsePayload.cmdItCode: $item")
            cmdCallBack.remove(item)
            diag("callback remove item=$item remaining=${cmdCallBack.containsKey(item)} at=${System.currentTimeMillis()}")
            L.d("[say]", "☆3、 🀄Command: <==:  $item ${ssm2ResponsePayload.cmdResultCode}")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            diag("characteristic write callback status=$status gattPresent=${gatt != null} charPresent=${characteristic != null} logicalStatus=$deviceStatus gattState=$diagGattState at=${System.currentTimeMillis()}")
            transmit()
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            L.d("[say]", "[onMtuChanged] mtu: $mtu, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if ((productModel == CHProductModel.SSMTouchPro) || (productModel == CHProductModel.SSMTouch2Pro) || (productModel == CHProductModel.SSMTouch) || (productModel == CHProductModel.SSMTouch2) || (productModel == CHProductModel.SSMFacePro) || (productModel == CHProductModel.SSMFace2Pro)
                    || (productModel == CHProductModel.SSMFaceProAI) || (productModel == CHProductModel.SSMFace2ProAI) || (productModel == CHProductModel.SSMFaceAI) || (productModel == CHProductModel.SSMFace2AI) || (productModel == CHProductModel.SSMFace) || (productModel == CHProductModel.SSMFace2)) {
                    deviceStatus = CHDeviceStatus.DiscoverServices
                    mBluetoothGatt = gatt
                    gatt?.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            L.d("[say]", "[onServicesDiscovered]")
            super.onServicesDiscovered(gatt, status)
            for (service in gatt?.services!!) {
                L.d("[say]", "[onServicesDiscovered] service 01 : " + service.uuid)
                if (service.uuid == Sesame2Chracs.uuidService01) {
                    for (charc in service.characteristics) {
                        L.d("[say]", "[onServicesDiscovered] charc: " + charc.uuid)
                        if (charc.uuid == Sesame2Chracs.uuidChr02) mCharacteristic = charc
                        if (charc.uuid == Sesame2Chracs.uuidChr03) {
                            L.d("[say]", "[NOTIFICATION]【start】[enable: " + BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.toHexString() + "][disable: " + BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.toHexString() + "]")
                            gatt.setCharacteristicNotification(charc, true)
                            val descriptor = charc.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                val check = gatt.writeDescriptor(descriptor)
                                L.d("[say]", "[NOTIFICATION]【end】 [enable] $check")
                            }
                        }
                    }
                }
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            diagGattState = newState
            diag("gatt state change newState=$newState status=$status logicalStatus=$deviceStatus at=${System.currentTimeMillis()}")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if ((productModel == CHProductModel.SSMTouchPro) || (productModel == CHProductModel.SSMTouch2Pro) || (productModel == CHProductModel.SSMTouch) || (productModel == CHProductModel.SSMTouch2) || (productModel == CHProductModel.SSMFacePro) || (productModel == CHProductModel.SSMFace2Pro)
                    || (productModel == CHProductModel.SSMFaceAI) || (productModel == CHProductModel.SSMFace2AI) || (productModel == CHProductModel.SSMFaceProAI) || (productModel == CHProductModel.SSMFace2ProAI) || (productModel == CHProductModel.SSMFace) || (productModel == CHProductModel.SSMFace2)) {
                    val mtuResult = gatt.requestMtu(251)
                    L.d("onConnectionStateChange", "MTU request result: $mtuResult")
                } else {
                    L.d("[say]", "[ss5] :連接狀態＋＋" + BleBaseType.GattConnectStateDec(newState) + " 狀態:" + BleBaseType.GattConnectStatusDec(status))
                    deviceStatus = CHDeviceStatus.DiscoverServices
                    mBluetoothGatt = gatt
                    gatt.discoverServices()
                }
                if (status == BluetoothProfile.STATE_DISCONNECTED) cmdCallBack.clear()
            } else {
                L.d("[say]", "[ss5][$deviceId][${BleBaseType.GattConnectStateDec(newState)}][${BleBaseType.GattConnectStatusDec(status)}]")
                gatt.close()
                (this@CHSesameOS3 as CHDeviceUtil).advertisement = null
                mBluetoothGatt = null
                cmdCallBack.clear()
                CHBleManager.connectR.remove(gatt.device.address)
            }
        }
    }

    fun transmit() {
        val chunkData = gattTxBuffer?.getChunk()
        if (chunkData == null) {
            diag("transmit queue empty release semaphore at=${System.currentTimeMillis()}")
            semaphore.release()
            return
        }
        L.d("sf", "chunkData[0]=${chunkData[0]}")
        diag("characteristic write start bytes=${chunkData.size} gattPresent=${mBluetoothGatt != null} charPresent=${mCharacteristic != null} logicalStatus=$deviceStatus gattState=$diagGattState at=${System.currentTimeMillis()}")
        var check: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (mCharacteristic != null) {
                val result = mBluetoothGatt?.writeCharacteristic(mCharacteristic!!, chunkData, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                L.d("sf", "result===$result")
                check = result == BluetoothStatusCodes.SUCCESS
            } else {
                check = false
            }
        } else {
            mCharacteristic?.value = chunkData
            check = mBluetoothGatt?.writeCharacteristic(mCharacteristic) == true
        }
        diag("characteristic write enqueue result=$check logicalStatus=$deviceStatus gattState=$diagGattState at=${System.currentTimeMillis()}")
        L.d("[say]", "[app][say][$mBluetoothGatt][" + mCharacteristic?.uuid + "]")
        L.d("sf", "[app][say]:" + mCharacteristic?.value?.toHexString() + "; bytes: " + mCharacteristic?.value?.size + "; check:" + check)
        if (!check) {
            var retry = 0
            do {
                retry++
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = mBluetoothGatt?.writeCharacteristic(mCharacteristic!!, chunkData, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    check = result == BluetoothStatusCodes.SUCCESS
                } else {
                    check = mBluetoothGatt?.writeCharacteristic(mCharacteristic) == true
                }
                if (retry > 30000) break
            } while (!check)
            L.d("sf", "[app][say][retry][$retry]")
            diag("characteristic write retry end retries=$retry success=$check at=${System.currentTimeMillis()}")
            if (!check) {
                semaphore.release()
                disconnect { }
            }
        }
    }

    fun sendCommand(payload: SesameOS3Payload, isEncryt: DeviceSegmentType = DeviceSegmentType.cipher, onResponse: SesameOS3ResponseCallback) {
        val now = System.currentTimeMillis()
        val tmp = cmdCallBack[payload.itemCode]
        diag("callback register item=${payload.itemCode} existing=${tmp != null} at=$now logicalStatus=$deviceStatus gattPresent=${mBluetoothGatt != null} charPresent=${mCharacteristic != null} gattState=$diagGattState")
        if (tmp != null) diag("WARNING callback overwrite item=${payload.itemCode} previousRegisteredAt=${cmdCallBackMap[payload.itemCode]} newAt=$now")
        cmdCallBack[payload.itemCode] = onResponse
        if (tmp != null) {
            L.d("hcia", "[qeueu][${payload.itemCode}][again]")
            if (cmdCallBackMap[payload.itemCode] != null && ((System.currentTimeMillis() - cmdCallBackMap[payload.itemCode]!!) > 2000)) {
                L.d("Harry", "上一次的 这个 itemCode 的蓝牙 Response 丢失 ")
                diag("stale callback removed item=${payload.itemCode} ageMs=${System.currentTimeMillis() - cmdCallBackMap[payload.itemCode]!!}")
                cmdCallBack.remove(payload.itemCode)
                cmdCallBackMap.remove(payload.itemCode)
            }
            return
        }
        cmdCallBackMap[payload.itemCode] = now
        diag("command enqueue item=${payload.itemCode} at=${System.currentTimeMillis()}")
        CoroutineScope(IO).launch {
            try {
                semaphore.acquire()
                diag("command transport acquired item=${payload.itemCode} at=${System.currentTimeMillis()} logicalStatus=$deviceStatus gattPresent=${mBluetoothGatt != null} charPresent=${mCharacteristic != null} gattState=$diagGattState")
                val say2ssm = if (isEncryt == DeviceSegmentType.cipher) {
                    cipher?.encrypt(payload.toDataWithHeader()) ?: run {
                        diag("command transport abort item=${payload.itemCode} reason=cipher-null")
                        semaphore.release()
                        return@launch
                    }
                } else {
                    payload.toDataWithHeader()
                }
                gattTxBuffer = SesameBleTransmit(isEncryt, say2ssm)
                diag("command transport transmit item=${payload.itemCode} at=${System.currentTimeMillis()}")
                transmit()
            } catch (e: Exception) {
                diag("command transport exception item=${payload.itemCode} type=${e.javaClass.simpleName}")
                e.printStackTrace()
                semaphore.release()
            }
        }
    }

    open fun getVersionTag(result: CHResult<String>) {
        if (!(this as CHDevices).isBleAvailable(result)) return
        sendCommand(SesameOS3Payload(SesameItemCode.versionTag.value, byteArrayOf()), DeviceSegmentType.cipher) { res ->
            val deviceUUID = deviceId.toString().uppercase()
            if (res.cmdResultCode == SesameResultCode.success.value) {
                val versionTag = String(res.payload)
                L.d("getVersionTag", "$deviceUUID == $versionTag")
                result.invoke(Result.success(CHResultState.CHResultStateBLE(versionTag)))
                CHAPIClientBiz.updateDeviceFirmwareVersion(deviceUUID, versionTag) { it.onFailure { error -> L.e("getVersionTag", error.message.toString()) } }
            } else {
                result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt())))
            }
        }
    }

    open fun reset(result: CHResult<CHEmpty>) {
        sendCommand(SesameOS3Payload(SesameItemCode.Reset.value, byteArrayOf()), DeviceSegmentType.cipher) { res ->
            if (res.cmdResultCode == SesameResultCode.success.value) {
                dropKey(result)
            } else {
                result.invoke(Result.failure(NSError(res.cmdResultCode.toString(), "CBCentralManager", res.cmdResultCode.toInt())))
            }
        }
    }

    open fun updateFirmware(onResponse: CHResult<BluetoothDevice>) {
        val device = (this as CHDeviceUtil).advertisement?.device
        if (device != null) onResponse.invoke(Result.success(CHResultState.CHResultStateBLE(device)))
        else onResponse.invoke(Result.failure(RuntimeException("Bluetooth device is not available.")))
    }

    fun parceADV(value: CHadv?) {
        value?.let {
            rssi = it.rssi
            deviceId = it.deviceID
            isRegistered = it.isRegistered
            productModel = it.productModel!!
            deviceStatus = when {
                deviceStatus == CHDeviceStatus.NoBleSignal -> CHDeviceStatus.ReceivedAdV
                deviceStatus == CHDeviceStatus.WaitingForAuth && isInternetAvailable() -> CHDeviceStatus.ReceivedAdV
                else -> deviceStatus
            }
        } ?: run { deviceStatus = CHDeviceStatus.NoBleSignal }
    }

    override fun onGattSesamePublish(receivePayload: SSM3PublishPayload) {
        if (receivePayload.cmdItCode == SesameItemCode.initial.value) {
            mSesameToken = receivePayload.payload
            if (isRegistered) {
                L.d("[say]", "isNeedAuthFromServer: " + isNeedAuthFromServer.toString())
                if (isNeedAuthFromServer == true) {
                    CHAPIClientBiz.signGuestKey(CHRemoveSignKeyRequest(deviceId.toString().uppercase(), mSesameToken.toHexString(), sesame2KeyData!!.secretKey)) {
                        it.onSuccess { (this as CHDeviceUtil).login(it.data) }
                    }
                } else {
                    (this as CHDeviceUtil).login()
                }
            } else {
                deviceStatus = CHDeviceStatus.ReadyToRegister
            }
        }
    }
}

data class SesameOS3Payload(val itemCode: UByte, val data: ByteArray) {
    fun toDataWithHeader(): ByteArray = byteArrayOf(itemCode.toByte()) + data
}
