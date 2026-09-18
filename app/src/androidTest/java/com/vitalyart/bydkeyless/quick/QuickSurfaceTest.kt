package com.vitalyart.bydkeyless.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.vitalyart.bydkeyless.MainActivity
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.widget.QuickControlWidget
import org.junit.Assert.*
import org.junit.Test

class QuickSurfaceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val commands = listOf(VehicleCommand.UNLOCK, VehicleCommand.LOCK, VehicleCommand.OPEN_TRUNK)
    private val snapshot = QuickSurfaceState("Demo vehicle", "Ready", "Automatic commands off", false, commands, false)
    @Test fun notificationActionsKeepOrderAndDisappearWhileBusy() {
        val open = PendingIntent.getActivity(context, 99, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun build(state: QuickSurfaceState) = buildQuickNotification(context, "test", "en", state, open) { _, _ -> open }
        assertEquals(listOf("Unlock", "Lock", "Trunk"), build(snapshot).actions.map { it.title.toString() })
        assertTrue(build(snapshot.copy(busy = true)).actions.isNullOrEmpty())
        assertEquals(1, build(snapshot.copy(commands = listOf(VehicleCommand.LOCK))).actions.size)
    }
    @Test fun widgetInflatesBothSizesAndDisablesUnavailableActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(72, 144).forEach { height ->
                listOf("light", "dark").forEach { theme ->
                    val options = Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height) }
                    val view = QuickControlWidget.render(context, options, snapshot.copy(commands = listOf(VehicleCommand.LOCK)), theme, "ru").apply(context, null)
                    assertFalse(view.findViewById<View>(R.id.widget_unlock_action).isEnabled)
                    assertTrue(view.findViewById<View>(R.id.widget_lock_action).isEnabled)
                    assertFalse(view.findViewById<View>(R.id.widget_trunk_action).isEnabled)
                    val width = (320 * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((height * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    assertTrue(view.findViewById<View>(R.id.widget_lock_action).width >= (48 * context.resources.displayMetrics.density).toInt())
                }
            }
        }
    }
}
