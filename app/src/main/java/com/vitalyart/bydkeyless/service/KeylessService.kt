package com.vitalyart.bydkeyless.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vitalyart.bydkeyless.KeylessApplication
import com.vitalyart.bydkeyless.MainActivity
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
import com.vitalyart.bydkeyless.model.BleConnectionState
import com.vitalyart.bydkeyless.model.KeylessMode
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.model.CommandError
import com.vitalyart.bydkeyless.model.CommandResult
import com.vitalyart.bydkeyless.model.VehicleProfile
import com.vitalyart.bydkeyless.quick.QuickCommandContract
import com.vitalyart.bydkeyless.quick.QuickCommandPhase
import com.vitalyart.bydkeyless.ui.messageResource
import com.vitalyart.bydkeyless.widget.QuickControlWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class KeylessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as KeylessApplication).graph
    private var monitoring = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationText: String? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:keyless-proximity")
            .apply { setReferenceCounted(false); acquire() }
        createChannel()
        promoteForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopKey()
            return START_NOT_STICKY
        }
        val quickCommand = QuickCommandContract.commandForAction(intent?.action)
        val session = graph.store.loadSession()
        if (quickCommand == null && (session == null || !session.second.hasValidKey() || graph.store.keylessMode == KeylessMode.OFF)) {
            stopKey(); return START_NOT_STICKY
        }
        if (!monitoring && session != null) beginMonitoring(session.second)
        if (quickCommand != null) executeQuickCommand(quickCommand)
        return START_STICKY
    }

    private fun beginMonitoring(profile: VehicleProfile) {
        monitoring = true
        graph.proximity.configure(graph.store.keylessMode, graph.store.calibration(), graph.store.autoUnlock, graph.store.autoLock)
        scope.launch { graph.ble.connect(profile) }
        scope.launch { graph.ble.telemetry.collectLatest { telemetry ->
            if (!profile.hasValidKey()) stopKey()
            else if (graph.store.keylessMode != KeylessMode.OFF) graph.proximity.onTelemetry(telemetry)
            refreshSurfaces()
        } }
        scope.launch { graph.ble.connectionState.collectLatest { refreshSurfaces() } }
        scope.launch { graph.quickCommands.state.collectLatest { refreshSurfaces() } }
    }

    private fun executeQuickCommand(command: VehicleCommand) {
        scope.launch {
            val result = graph.quickCommands.execute(command)
            if (result == CommandResult.Success && command == VehicleCommand.UNLOCK) graph.store.manualUnlockVerified = true
            if (result == CommandResult.Success && command == VehicleCommand.LOCK) graph.store.manualLockVerified = true
            refreshSurfaces()
            val busy = (result as? CommandResult.Rejected)?.error == CommandError.COMMAND_BUSY
            if (!busy) {
                val terminal = graph.quickCommands.state.value
                delay(5_000L)
                val cleared = graph.quickCommands.clearTerminalState(terminal)
                refreshSurfaces()
                if (cleared && graph.store.keylessMode == KeylessMode.OFF) stopKey()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false keeps the foreground key alive when the user swipes
        // the Activity away. START_STICKY restores it if the process is reclaimed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        runBlocking { graph.ble.disconnect() }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopKey() { scope.launch { graph.ble.disconnect() }; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun refreshSurfaces() {
        val text = notificationText()
        if (text != lastNotificationText) {
            lastNotificationText = text
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
        QuickControlWidget.updateAll(this)
    }

    private fun notificationText(): String {
        val quick = graph.quickCommands.state.value
        return when (quick.phase) {
            QuickCommandPhase.CONNECTING -> appString(R.string.quick_connecting)
            QuickCommandPhase.EXECUTING -> appString(R.string.message_executing)
            QuickCommandPhase.SUCCESS -> appString(R.string.message_command_success)
            QuickCommandPhase.ERROR -> quick.error?.let { appString(it.messageResource()) } ?: appString(R.string.status_error)
            QuickCommandPhase.IDLE -> if (graph.ble.connectionState.value == BleConnectionState.READY) appString(R.string.key_service_connected) else appString(R.string.key_service_scanning)
        }
    }

    private fun promoteForeground() {
        val types = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(notificationText()), types)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification_key)
            .setContentTitle(appString(R.string.key_service_title)).setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.addAction(R.drawable.ic_quick_unlock, appString(R.string.action_unlock), commandPendingIntent(VehicleCommand.UNLOCK, 10))
        builder.addAction(R.drawable.ic_quick_lock, appString(R.string.action_lock), commandPendingIntent(VehicleCommand.LOCK, 11))
        builder.addAction(R.drawable.ic_quick_trunk, appString(R.string.widget_trunk), commandPendingIntent(VehicleCommand.OPEN_TRUNK, 12))
        return builder.build()
    }

    private fun commandPendingIntent(command: VehicleCommand, requestCode: Int): PendingIntent = PendingIntent.getForegroundService(
        this,
        requestCode,
        Intent(this, KeylessService::class.java).setAction(QuickCommandContract.actionFor(command)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, appString(R.string.channel_key_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun appString(@androidx.annotation.StringRes id: Int) = localizedString(graph.store.language, id)

    companion object {
        const val CHANNEL_ID = "byd_keyless_active"
        const val NOTIFICATION_ID = 7401
        const val ACTION_STOP = "com.vitalyart.bydkeyless.STOP"
        fun start(context: Context) = context.startForegroundService(Intent(context, KeylessService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, KeylessService::class.java).setAction(ACTION_STOP))
    }
}
