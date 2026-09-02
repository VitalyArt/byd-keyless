package com.vitalyart.bydkeyless.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vitalyart.bydkeyless.KeylessApplication
import com.vitalyart.bydkeyless.MainActivity
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
import com.vitalyart.bydkeyless.model.KeylessMode

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val graph = (context.applicationContext as KeylessApplication).graph
        if (graph.store.loadSession()?.second?.hasValidKey() != true) return
        if (graph.store.keylessMode != KeylessMode.OFF) {
            runCatching { KeylessService.start(context) }.onFailure { showResumeNotification(context) }
        }
    }

    private fun showResumeNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val language = (context.applicationContext as KeylessApplication).graph.store.language
        manager.createNotificationChannel(
            NotificationChannel(KeylessService.CHANNEL_ID, context.localizedString(language, R.string.channel_key_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val pending = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(7402, NotificationCompat.Builder(context, KeylessService.CHANNEL_ID).setSmallIcon(R.drawable.ic_notification_key)
            .setContentTitle(context.localizedString(language, R.string.key_service_title)).setContentText(context.localizedString(language, R.string.key_service_resume))
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
