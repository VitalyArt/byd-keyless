package com.vitalyart.bydkeyless.quick

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
import com.vitalyart.bydkeyless.model.VehicleCommand

internal fun buildQuickNotification(
    context: Context, channel: String, language: String, state: QuickSurfaceState,
    open: PendingIntent, commandIntent: (VehicleCommand, Int) -> PendingIntent,
): Notification {
    val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_key)
        .setContentTitle(state.title).setContentText(state.status).setContentIntent(open)
        .setStyle(NotificationCompat.BigTextStyle().bigText(state.status + "\n" + state.detail))
        .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
    listOf(Triple(VehicleCommand.UNLOCK, R.drawable.ic_quick_unlock, 10),
        Triple(VehicleCommand.LOCK, R.drawable.ic_quick_lock, 11),
        Triple(VehicleCommand.OPEN_TRUNK, R.drawable.ic_quick_trunk, 12)).forEach { (command, icon, code) ->
        if (!state.busy && command in state.commands) builder.addAction(icon, context.localizedString(language, quickCommandLabel(command)), commandIntent(command, code))
    }
    return builder.build()
}
