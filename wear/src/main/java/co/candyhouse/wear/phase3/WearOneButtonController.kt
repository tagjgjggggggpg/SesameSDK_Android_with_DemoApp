package co.candyhouse.wear.phase3

import android.app.KeyguardManager
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface WearDeviceLockChecker {
    fun isLocked(): Boolean
}

internal interface WearPhoneNodeResolver {
    suspend fun reachablePhoneNodeId(): String?
}

internal interface WearMessageSender {
    suspend fun send(nodeId: String, path: String, payload: ByteArray)
}

internal sealed interface WearActivationResult {
    data object Locked : WearActivationResult
    data object PhoneUnavailable : WearActivationResult
    data class Sent(val commandId: String) : WearActivationResult
}

internal class WearOneButtonController(
    private val lockChecker: WearDeviceLockChecker,
    private val nodeResolver: WearPhoneNodeResolver,
    private val sender: WearMessageSender,
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun activateNewTap(): WearActivationResult {
        if (lockChecker.isLocked()) return WearActivationResult.Locked
        return send(commandIdFactory())
    }

    suspend fun retry(commandId: String): WearActivationResult {
        if (lockChecker.isLocked()) return WearActivationResult.Locked
        return send(commandId)
    }

    private suspend fun send(commandId: String): WearActivationResult {
        val nodeId = nodeResolver.reachablePhoneNodeId() ?: return WearActivationResult.PhoneUnavailable
        return try {
            sender.send(nodeId, WearGroupProtocol.COMMAND_PATH, WearGroupProtocol.encodeCommand(commandId))
            WearActivationResult.Sent(commandId)
        } catch (_: Throwable) {
            WearActivationResult.PhoneUnavailable
        }
    }

    companion object {
        fun production(context: Context): WearOneButtonController = WearOneButtonController(
            lockChecker = AndroidWearDeviceLockChecker(context),
            nodeResolver = DataLayerPhoneNodeResolver(context),
            sender = DataLayerMessageSender(context),
        )
    }
}

internal class WearControlDispatcher(private val controller: WearOneButtonController) {
    suspend fun activateFromApp(): WearActivationResult = controller.activateNewTap()
    suspend fun activateFromTile(): WearActivationResult = controller.activateNewTap()
}

private class AndroidWearDeviceLockChecker(context: Context) : WearDeviceLockChecker {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    override fun isLocked(): Boolean = keyguard?.isDeviceLocked != false
}

private class DataLayerPhoneNodeResolver(context: Context) : WearPhoneNodeResolver {
    private val capabilityClient = Wearable.getCapabilityClient(context)

    override suspend fun reachablePhoneNodeId(): String? = capabilityClient
        .getCapability(WearGroupProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
        .awaitValue()
        .nodes
        .firstOrNull()
        ?.id
}

private class DataLayerMessageSender(context: Context) : WearMessageSender {
    private val messageClient = Wearable.getMessageClient(context)
    override suspend fun send(nodeId: String, path: String, payload: ByteArray) {
        messageClient.sendMessage(nodeId, path, payload).awaitValue()
    }
}

private suspend fun <T> Task<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
