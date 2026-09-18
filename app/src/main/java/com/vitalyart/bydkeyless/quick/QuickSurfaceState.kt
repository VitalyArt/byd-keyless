package com.vitalyart.bydkeyless.quick

import android.content.Context
import com.vitalyart.bydkeyless.AppGraph
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.ui.messageResource

data class QuickSurfaceState(val title: String, val status: String, val detail: String, val busy: Boolean, val commands: List<VehicleCommand>, val error: Boolean)

fun quickCommandLabel(command: VehicleCommand?): Int = when (command) {
    VehicleCommand.UNLOCK -> R.string.action_unlock
    VehicleCommand.LOCK -> R.string.action_lock
    VehicleCommand.OPEN_TRUNK -> R.string.widget_trunk
    else -> R.string.app_name
}

fun quickSurfaceState(context: Context, graph: AppGraph): QuickSurfaceState {
    val profile = graph.store.loadSession()?.second
    val language = graph.store.language
    fun text(id: Int) = context.localizedString(language, id)
    val quick = graph.quickCommands.state.value
    val busy = quick.phase in setOf(QuickCommandPhase.CONNECTING, QuickCommandPhase.EXECUTING)
    val status = when (quick.phase) {
        QuickCommandPhase.CONNECTING -> text(R.string.quick_connecting)
        QuickCommandPhase.EXECUTING -> text(R.string.quick_action_progress).format(text(quickCommandLabel(quick.command)))
        QuickCommandPhase.SUCCESS -> text(R.string.quick_action_success).format(text(quickCommandLabel(quick.command)))
        QuickCommandPhase.ERROR -> text(quick.error?.messageResource() ?: R.string.status_error)
        QuickCommandPhase.IDLE -> text(when {
            profile == null -> R.string.error_no_session
            !profile.hasValidKey() -> R.string.error_key_expired
            graph.proximity.state.value.needsConfirmation -> R.string.automation_needs_confirmation
            graph.ble.connectionState.value == BleConnectionState.READY -> R.string.widget_connected
            graph.ble.connectionState.value == BleConnectionState.ERROR -> R.string.widget_error
            graph.ble.connectionState.value in setOf(BleConnectionState.IDLE, BleConnectionState.DISCONNECTED) -> R.string.widget_disconnected
            else -> R.string.widget_connecting
        })
    }
    val detail = if (graph.store.keylessMode == KeylessMode.OFF || (!graph.store.autoUnlock && !graph.store.autoLock)) text(R.string.automation_off)
        else listOfNotNull(if (graph.store.autoUnlock) text(R.string.auto_unlock) else null, if (graph.store.autoLock) text(R.string.auto_lock) else null).joinToString(" · ")
    return QuickSurfaceState(profile?.modelName?.takeIf { it.isNotBlank() } ?: text(R.string.app_name), status, detail, busy,
        if (busy || profile?.hasValidKey() != true) emptyList() else listOf(VehicleCommand.UNLOCK, VehicleCommand.LOCK, VehicleCommand.OPEN_TRUNK).filter { it.functionCode in profile.capabilities },
        quick.phase == QuickCommandPhase.ERROR || graph.ble.connectionState.value == BleConnectionState.ERROR)
}
