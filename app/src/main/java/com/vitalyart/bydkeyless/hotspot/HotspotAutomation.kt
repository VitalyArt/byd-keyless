package com.vitalyart.bydkeyless.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.TetheringManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.vitalyart.bydkeyless.model.BleConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Controls the phone's configured internet-sharing Wi-Fi hotspot. */
interface HotspotController {
    suspend fun setEnabled(enabled: Boolean): Boolean
}

class AndroidHotspotController(context: Context) : HotspotController {
    private val appContext = context.applicationContext

    override suspend fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 36) withTimeoutOrNull(15_000L) { Api36.setEnabled(appContext, enabled) } == true
        else setEnabledWithLegacyApi(enabled)
    }.onFailure { Log.w(TAG, "Unable to change Wi-Fi hotspot state", it) }
        .getOrDefault(false)

    private fun setEnabledWithLegacyApi(enabled: Boolean): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val method = manager.javaClass.methods.firstOrNull { candidate ->
            candidate.name == (if (enabled) "startTethering" else "stopTethering") &&
                candidate.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
        } ?: return false
        val arguments = method.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> 0 // ConnectivityManager.TETHERING_WIFI
                type == Boolean::class.javaPrimitiveType -> true
                type == Handler::class.java -> Handler(Looper.getMainLooper())
                else -> null
            }
        }.toTypedArray()
        method.invoke(manager, *arguments)
        return true
    }

    @RequiresApi(36)
    private object Api36 {
        private var request: TetheringManager.TetheringRequest? = null

        suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
            val manager = context.getSystemService(TetheringManager::class.java)
            val activeRequest = request ?: TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI)
                .build().also { request = it }
            return suspendCancellableCoroutine { continuation ->
                if (enabled) {
                    manager.startTethering(activeRequest, context.mainExecutor, object : TetheringManager.StartTetheringCallback {
                        override fun onTetheringStarted() { if (continuation.isActive) continuation.resume(true) }
                        override fun onTetheringFailed(error: Int) {
                            Log.w(TAG, "Wi-Fi hotspot start failed: $error")
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                } else {
                    manager.stopTethering(activeRequest, context.mainExecutor, object : TetheringManager.StopTetheringCallback {
                        override fun onStopTetheringSucceeded() { if (continuation.isActive) continuation.resume(true) }
                        override fun onStopTetheringFailed(error: Int) {
                            Log.w(TAG, "Wi-Fi hotspot stop failed: $error")
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                }
            }
        }
    }

    private companion object { const val TAG = "HotspotAutomation" }
}

class HotspotAutomationManager(
    private val settings: () -> HotspotAutomationSettings,
    private val controller: HotspotController,
    private val scope: CoroutineScope,
) {
    private var connectionState = BleConnectionState.IDLE
    private var managedHotspot = false
    private var enableJob: Job? = null
    private var disableJob: Job? = null

    fun onConnectionStateChanged(state: BleConnectionState) {
        val wasReady = connectionState == BleConnectionState.READY
        connectionState = state
        if (state == BleConnectionState.READY) {
            disableJob?.cancel()
            if (!wasReady && settings().onConnect && !managedHotspot) enableHotspot()
        } else if (wasReady && settings().offOnDisconnect) {
            scheduleDisable()
        }
    }

    /** Re-applies settings immediately when the user changes them while connected. */
    fun refreshSettings() {
        val current = settings()
        if (connectionState == BleConnectionState.READY && current.onConnect && managedHotspot.not()) {
            enableHotspot()
        }
        if (!current.offOnDisconnect) disableJob?.cancel()
        else if (connectionState != BleConnectionState.READY && managedHotspot) scheduleDisable()
    }

    private fun enableHotspot() {
        if (enableJob?.isActive == true) return
        enableJob = scope.launch {
            if (controller.setEnabled(true)) {
                managedHotspot = true
                if (connectionState != BleConnectionState.READY && settings().offOnDisconnect) scheduleDisable()
            }
        }
    }

    private fun scheduleDisable() {
        if (!managedHotspot) return
        disableJob?.cancel()
        val delayMillis = settings().offDelayMillis.coerceAtLeast(0L)
        disableJob = scope.launch {
            delay(delayMillis)
            if (connectionState != BleConnectionState.READY && settings().offOnDisconnect && controller.setEnabled(false)) {
                managedHotspot = false
            }
        }
    }
}

data class HotspotAutomationSettings(
    val onConnect: Boolean,
    val offOnDisconnect: Boolean,
    val offDelayMillis: Long,
)
