package com.vitalyart.bydkeyless.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.vitalyart.bydkeyless.KeylessApplication
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
import com.vitalyart.bydkeyless.model.BleConnectionState
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.quick.QuickCommandContract
import com.vitalyart.bydkeyless.quick.QuickCommandPhase
import com.vitalyart.bydkeyless.service.KeylessService
import com.vitalyart.bydkeyless.ui.messageResource

class QuickControlWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, QuickControlWidget::class.java)
            manager.getAppWidgetIds(component).forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val graph = (context.applicationContext as KeylessApplication).graph
            val language = graph.store.language
            return RemoteViews(context.packageName, R.layout.widget_quick_control).apply {
                setTextViewText(R.id.widget_title, context.localizedString(language, R.string.app_name))
                setTextViewText(R.id.widget_status, statusText(context, language))
                setTextViewText(R.id.widget_unlock, context.localizedString(language, R.string.action_unlock))
                setTextViewText(R.id.widget_lock, context.localizedString(language, R.string.action_lock))
                setTextViewText(R.id.widget_trunk, context.localizedString(language, R.string.widget_trunk))
                setTextColor(R.id.widget_status, ContextCompat.getColor(context, statusColor(graph)))
                setImageViewResource(R.id.widget_status_dot, statusDot(graph))
                setOnClickPendingIntent(R.id.widget_open_app, openAppIntent(context))
                setOnClickPendingIntent(R.id.widget_unlock_action, commandIntent(context, VehicleCommand.UNLOCK, 21))
                setOnClickPendingIntent(R.id.widget_lock_action, commandIntent(context, VehicleCommand.LOCK, 22))
                setOnClickPendingIntent(R.id.widget_trunk_action, commandIntent(context, VehicleCommand.OPEN_TRUNK, 23))
                val busy = graph.quickCommands.state.value.phase in setOf(QuickCommandPhase.CONNECTING, QuickCommandPhase.EXECUTING)
                listOf(R.id.widget_unlock_action, R.id.widget_lock_action, R.id.widget_trunk_action).forEach { id ->
                    setBoolean(id, "setEnabled", !busy)
                    setFloat(id, "setAlpha", if (busy) .55f else 1f)
                }
            }
        }

        private fun statusColor(graph: com.vitalyart.bydkeyless.AppGraph): Int = when {
            graph.quickCommands.state.value.phase == QuickCommandPhase.ERROR || graph.ble.connectionState.value == BleConnectionState.ERROR -> R.color.widget_error
            graph.quickCommands.state.value.phase in setOf(QuickCommandPhase.CONNECTING, QuickCommandPhase.EXECUTING) -> R.color.widget_working
            graph.ble.connectionState.value == BleConnectionState.READY -> R.color.widget_ready
            else -> R.color.widget_muted
        }

        private fun statusDot(graph: com.vitalyart.bydkeyless.AppGraph): Int = when (statusColor(graph)) {
            R.color.widget_error -> R.drawable.widget_dot_error
            R.color.widget_working -> R.drawable.widget_dot_working
            R.color.widget_ready -> R.drawable.widget_dot_ready
            else -> R.drawable.widget_dot_muted
        }

        private fun statusText(context: Context, language: String): String {
            val graph = (context.applicationContext as KeylessApplication).graph
            val quick = graph.quickCommands.state.value
            val resource = when (quick.phase) {
                QuickCommandPhase.CONNECTING -> R.string.quick_connecting
                QuickCommandPhase.EXECUTING -> R.string.message_executing
                QuickCommandPhase.SUCCESS -> R.string.message_command_success
                QuickCommandPhase.ERROR -> quick.error?.messageResource() ?: R.string.status_error
                QuickCommandPhase.IDLE -> when (graph.ble.connectionState.value) {
                    BleConnectionState.READY -> R.string.widget_connected
                    BleConnectionState.ERROR -> R.string.widget_error
                    BleConnectionState.IDLE, BleConnectionState.DISCONNECTED -> R.string.widget_disconnected
                    else -> R.string.widget_connecting
                }
            }
            return context.localizedString(language, resource)
        }

        private fun commandIntent(context: Context, command: VehicleCommand, requestCode: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                requestCode,
                Intent(context, KeylessService::class.java).setAction(QuickCommandContract.actionFor(command)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            20,
            Intent(context, com.vitalyart.bydkeyless.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
