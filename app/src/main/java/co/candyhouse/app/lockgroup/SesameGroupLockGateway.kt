package co.candyhouse.app.lockgroup

import android.util.Log
import co.candyhouse.app.BuildConfig
import co.candyhouse.sesame.open.CHBleManager
import co.candyhouse.sesame.open.CHDeviceManager
import co.candyhouse.sesame.open.CHScanStatus
import co.candyhouse.sesame.open.devices.CHSesame5StrictLock
import co.candyhouse.sesame.open.devices.base.CHDeviceLoginStatus
import co.candyhouse.sesame.open.devices.base.CHDeviceStatus
import co.candyhouse.sesame.open.devices.base.CHDevices
import co.candyhouse.sesame.utils.CHEmpty
import co.candyhouse.sesame.utils.CHResult
import co.candyhouse.sesame.utils.P2DiagnosticLog
import co.candyhouse.sesame.utils.p2DiagnosticDeviceId
import co.receiver.widget.AutoUnlockForegroundService
import co.utils.UserUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume

private const val P2_GROUP_TAG = "P2GroupBLE"
private fun p2GroupLog(message: String) { if (!BuildConfig.DEBUG) return; P2DiagnosticLog.append(P2_GROUP_TAG, message); try { Log.d(P2_GROUP_TAG, message) } catch (_: RuntimeException) {} }
private fun tracePrefix(deviceId: String? = null): String = buildString { append("op=").append(GroupOperationTrace.current()); if (deviceId != null) append(" dev=").append(p2DiagnosticDeviceId(deviceId)) }

internal sealed interface TimedBleCallbackResult { data object Success : TimedBleCallbackResult; data class Failure(val error: Throwable) : TimedBleCallbackResult; data object Timeout : TimedBleCallbackResult }
internal suspend fun awaitTimedBleCallback(timeoutMs: Long, callbackAwaiter: suspend () -> Throwable?): TimedBleCallbackResult {
    val completed = withTimeoutOrNull(timeoutMs) { callbackAwaiter()?.let { TimedBleCallbackResult.Failure(it) } ?: TimedBleCallbackResult.Success }
    return completed ?: TimedBleCallbackResult.Timeout
}

internal interface SesameGroupLockRuntime {
    suspend fun resolveDevice(deviceId: String): Result<CHDevices>
    fun isStrictBleTransportReady(device: CHDevices): Boolean
    suspend fun ensureScan(): String?
    suspend fun waitForBleLogin(device: CHDevices): String?
    suspend fun finishOperation()
    fun historyTag(): ByteArray?
}

internal class ProductionSesameGroupLockRuntime : SesameGroupLockRuntime {
    private var scanInitialized = false; private var scanStartedByGateway = false; private var scanError: String? = null
    override suspend fun resolveDevice(deviceId: String): Result<CHDevices> {
        val started = System.currentTimeMillis(); val prefix = tracePrefix(deviceId); p2GroupLog("$prefix resolveDevice start")
        val result = withTimeoutOrNull(RESOLVE_DEVICE_TIMEOUT_MS) { suspendCancellableCoroutine<Result<CHDevices>> { c ->
            try { CHDeviceManager.getCandyDeviceByUUID(deviceId) { r -> r.onSuccess { if (c.isActive) c.resume(Result.success(it.data)) }; r.onFailure { if (c.isActive) c.resume(Result.failure(it)) } } }
            catch (e: Throwable) { if (c.isActive) c.resume(Result.failure(e)) }
        } }
        if (result == null) { p2GroupLog("$prefix resolveDevice timeout elapsed=${System.currentTimeMillis()-started}ms"); return Result.failure(TimeoutException("Device resolution timed out")) }
        return result
    }
    override fun isStrictBleTransportReady(device: CHDevices) = device.deviceStatus.value == CHDeviceLoginStatus.logined && (device as? CHSesame5StrictLock)?.isStrictBleTransportReady() == true
    override suspend fun ensureScan(): String? {
        if (scanInitialized) return scanError
        val wasEnabled = CHBleManager.mScanning == CHScanStatus.Enable
        val outcome = awaitTimedBleCallback(SCAN_START_TIMEOUT_MS) { awaitSimpleBleResult { CHBleManager.enableScan(false, it) } }
        scanInitialized = true
        when (outcome) { TimedBleCallbackResult.Success -> { scanStartedByGateway = !wasEnabled; scanError = null }; is TimedBleCallbackResult.Failure -> { scanStartedByGateway=false; scanError=outcome.error.message ?: outcome.error.javaClass.simpleName }; TimedBleCallbackResult.Timeout -> { scanStartedByGateway=false; scanError="BLE scan start timed out" } }
        return scanError
    }
    override suspend fun waitForBleLogin(device: CHDevices): String? {
        if (device.deviceStatus.value == CHDeviceLoginStatus.logined) return null
        val loggedIn = withTimeoutOrNull(LOGIN_TIMEOUT_MS) { var requested=false; while (device.deviceStatus.value != CHDeviceLoginStatus.logined) { if (device.deviceStatus == CHDeviceStatus.ReceivedAdV && !requested) { requested=true; try { device.connect{} } catch (_:Throwable) { return@withTimeoutOrNull false } }; if (device.deviceStatus == CHDeviceStatus.NoBleSignal) requested=false; delay(LOGIN_POLL_MS) }; true } ?: false
        return if (loggedIn) null else "BLE login timed out or disconnected"
    }
    override suspend fun finishOperation() { val disable=scanStartedByGateway && !AutoUnlockForegroundService.isLive; scanInitialized=false; scanStartedByGateway=false; scanError=null; if (disable) withTimeoutOrNull(SCAN_FINISH_TIMEOUT_MS) { awaitSimpleBleResult { CHBleManager.disableScan(it) } } }
    override fun historyTag() = UserUtils.getEnvironmentIdWithByte()
    private suspend fun awaitSimpleBleResult(block:(CHResult<CHEmpty>)->Unit):Throwable?=suspendCancellableCoroutine { c -> try { block(callback@{ r -> if (!c.isActive) return@callback; c.resume(r.exceptionOrNull()) }) } catch (e:CancellationException) { c.cancel(e) } catch(e:Throwable){ if(c.isActive)c.resume(e) } }
    companion object { private const val LOGIN_TIMEOUT_MS=8_000L; private const val LOGIN_POLL_MS=100L; private const val RESOLVE_DEVICE_TIMEOUT_MS=10_000L; private const val SCAN_START_TIMEOUT_MS=10_000L; private const val SCAN_FINISH_TIMEOUT_MS=2_000L }
}

internal class SesameGroupLockGateway(private val runtime: SesameGroupLockRuntime=ProductionSesameGroupLockRuntime(), private val stateObserveTimeoutMs:Long=STATE_OBSERVE_TIMEOUT_MS, private val statePollMs:Long=STATE_POLL_MS):GroupLockGateway {
    override suspend fun operate(deviceId:String, action:GroupLockAction):DeviceOperationResult {
        val device=runtime.resolveDevice(deviceId).getOrElse{return DeviceOperationResult(deviceId,false,it.message?:"Device not found")}
        if(!isSupportedGroupLockDevice(device)) return DeviceOperationResult(deviceId,false,"Unsupported group lock device: ${device.productModel}",effectiveState(device))
        if(!runtime.isStrictBleTransportReady(device)){ runtime.ensureScan()?.let{return DeviceOperationResult(deviceId,false,it,effectiveState(device))}; runtime.waitForBleLogin(device)?.let{return DeviceOperationResult(deviceId,false,it,effectiveState(device))} }
        val target=if(action==GroupLockAction.LOCK)LockState.LOCKED else LockState.UNLOCKED
        val outcome=withTimeoutOrNull(COMMAND_TIMEOUT_MS){awaitCommand{cb->if(device !is CHSesame5StrictLock){cb(Result.failure(UnsupportedOperationException("Strict BLE lock is not supported")));return@awaitCommand};if(action==GroupLockAction.LOCK)device.strictLock(historytag=runtime.historyTag(),result=cb)else device.strictUnlock(historytag=runtime.historyTag(),result=cb)}}?:CommandOutcome.Failure(TimeoutException("BLE command timed out"))
        return when(outcome){CommandOutcome.Success->{val observed=awaitObservedState(device,target);if(observed==target)DeviceOperationResult(deviceId,true,finalKnownState=observed)else DeviceOperationResult(deviceId,false,TARGET_NOT_CONFIRMED_ERROR,observed)};is CommandOutcome.Failure->DeviceOperationResult(deviceId,false,outcome.error.message?:outcome.error::class.java.simpleName,effectiveState(device))}
    }
    override suspend fun getState(deviceId:String)=getDeviceSnapshot(deviceId).state
    override suspend fun getDeviceSnapshot(deviceId:String):EntranceDeviceSnapshot {
        val device=runtime.resolveDevice(deviceId).getOrNull()?:return EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false)
        if(!isSupportedGroupLockDevice(device))return EntranceDeviceSnapshot(LockState.UNKNOWN,fresh=false)
        val state=effectiveState(device)
        val fresh=state!=LockState.UNKNOWN
        return EntranceDeviceSnapshot(state=state,batteryPercent=if(fresh)device.batteryPercentage else null,fresh=fresh)
    }
    override suspend fun finishOperation(){runtime.finishOperation()}
    private suspend fun awaitObservedState(device:CHDevices,target:LockState):LockState{withTimeoutOrNull(stateObserveTimeoutMs){while(bleState(device)!=target)delay(statePollMs)};return bleState(device)}
    private fun bleState(device:CHDevices)=when(device.deviceStatus){CHDeviceStatus.Locked->LockState.LOCKED;CHDeviceStatus.Unlocked,CHDeviceStatus.Moved->LockState.UNLOCKED;else->LockState.UNKNOWN}
    private fun effectiveState(device:CHDevices):LockState{val local=bleState(device);if(local!=LockState.UNKNOWN)return local;return when(device.deviceShadowStatus){CHDeviceStatus.Locked->LockState.LOCKED;CHDeviceStatus.Unlocked,CHDeviceStatus.Moved->LockState.UNLOCKED;else->LockState.UNKNOWN}}
    private suspend fun awaitCommand(block:(CHResult<CHEmpty>)->Unit):CommandOutcome=suspendCancellableCoroutine{c->try{block(callback@{r->if(!c.isActive)return@callback;r.fold(onSuccess={c.resume(CommandOutcome.Success)},onFailure={c.resume(CommandOutcome.Failure(it))})})}catch(e:CancellationException){c.cancel(e)}catch(e:Throwable){if(c.isActive)c.resume(CommandOutcome.Failure(e))}}
    private sealed interface CommandOutcome{data object Success:CommandOutcome;data class Failure(val error:Throwable):CommandOutcome}
    companion object{internal const val TARGET_NOT_CONFIRMED_ERROR="command accepted but target state was not confirmed";private const val COMMAND_TIMEOUT_MS=8_000L;private const val STATE_OBSERVE_TIMEOUT_MS=1_500L;private const val STATE_POLL_MS=50L}
}
