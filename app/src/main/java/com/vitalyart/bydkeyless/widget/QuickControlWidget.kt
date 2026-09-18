package com.vitalyart.bydkeyless.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import com.vitalyart.bydkeyless.KeylessApplication
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.quick.*
import com.vitalyart.bydkeyless.service.KeylessService

class QuickControlWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context, manager.getAppWidgetOptions(it))) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        manager.updateAppWidget(appWidgetId, views(context, newOptions))
    }

    companion object {
        private var lastRenderKey: List<Any?>? = null
        fun updateAll(context: Context, force: Boolean = false) {
            val graph = (context.applicationContext as KeylessApplication).graph
            val key = listOf(quickSurfaceState(context, graph), graph.store.language, graph.store.theme, context.resources.configuration.uiMode)
            if (!force && key == lastRenderKey) return
            lastRenderKey = key
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, QuickControlWidget::class.java)).forEach {
                manager.updateAppWidget(it, views(context, manager.getAppWidgetOptions(it)))
            }
        }

        private fun views(context: Context, options: Bundle): RemoteViews {
            val graph = (context.applicationContext as KeylessApplication).graph
            val state = quickSurfaceState(context, graph)
            return render(context, options, state, graph.store.theme, graph.store.language)
        }

        internal fun render(context: Context, options: Bundle, state: QuickSurfaceState, theme: String, language: String): RemoteViews {
            val dark = theme == "dark" || (theme == "system" &&
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            val primary = if (dark) 0xFFE5E7ED.toInt() else 0xFF191C22.toInt()
            val secondary = if (dark) 0xFFBBC2CF.toInt() else 0xFF505C6E.toInt()
            val accent = if (dark) 0xFFAAC7FF.toInt() else 0xFF315D96.toInt()
            val tall = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72) >= 120
            return RemoteViews(context.packageName, if (tall) R.layout.widget_quick_control_expanded else R.layout.widget_quick_control).apply {
                setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background)
                setTextViewText(R.id.widget_title, state.title)
                setTextViewText(R.id.widget_status, state.status)
                setTextColor(R.id.widget_title, primary)
                setTextColor(R.id.widget_status, if (state.error) (if (dark) 0xFFFFB4AB.toInt() else 0xFFB3261E.toInt()) else secondary)
                setOnClickPendingIntent(R.id.widget_open_app, openAppIntent(context))
                setContentDescription(R.id.widget_open_app, state.title + ". " + state.status)
                listOf(
                    Triple(VehicleCommand.UNLOCK, R.id.widget_unlock_action, R.id.widget_unlock),
                    Triple(VehicleCommand.LOCK, R.id.widget_lock_action, R.id.widget_lock),
                    Triple(VehicleCommand.OPEN_TRUNK, R.id.widget_trunk_action, R.id.widget_trunk),
                ).forEachIndexed { index, (command, action, label) ->
                    val title = context.localizedString(language, quickCommandLabel(command))
                    setTextViewText(label, title)
                    setTextColor(label, primary)
                    setContentDescription(action, title)
                    setOnClickPendingIntent(action, commandIntent(context, command, 21 + index))
                    setBoolean(action, "setEnabled", command in state.commands)
                    setFloat(action, "setAlpha", if (command in state.commands) 1f else .4f)
                }
                listOf(R.id.widget_unlock_icon, R.id.widget_lock_icon, R.id.widget_trunk_icon).forEach { setInt(it, "setColorFilter", accent) }
            }
        }

        private fun commandIntent(context: Context, command: VehicleCommand, requestCode: Int): PendingIntent =
            PendingIntent.getForegroundService(context, requestCode,
                Intent(context, KeylessService::class.java).setAction(QuickCommandContract.actionFor(command)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 20,
            Intent(context, com.vitalyart.bydkeyless.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
