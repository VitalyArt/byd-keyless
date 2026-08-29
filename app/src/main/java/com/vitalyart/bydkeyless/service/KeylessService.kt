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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class KeylessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as KeylessApplication).graph
    private var monitoring = false
    private var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:keyless-proximity")
            .apply { setReferenceCounted(false); acquire() }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(appString(R.string.key_service_scanning)),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopKey(); return START_NOT_STICKY }
        val session = graph.store.loadSession()
        if (session == null || !session.second.hasValidKey() || graph.store.keylessMode == KeylessMode.OFF) {
            stopKey(); return START_NOT_STICKY
        }
        if (monitoring) return START_STICKY
        monitoring = true
        graph.proximity.configure(graph.store.keylessMode, graph.store.calibration(), graph.store.autoUnlock, graph.store.autoLock)
        scope.launch { graph.ble.connect(session.second) }
        scope.launch { graph.ble.telemetry.collectLatest {
            if (!session.second.hasValidKey()) stopKey() else graph.proximity.onTelemetry(it)
        } }
        scope.launch { graph.ble.connectionState.collectLatest { state ->
            val text = if (state == BleConnectionState.READY) appString(R.string.key_service_connected) else appString(R.string.key_service_scanning)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        } }
        return START_STICKY
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

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, KeylessService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification_key)
            .setContentTitle(appString(R.string.key_service_title)).setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, appString(R.string.key_service_stop), stop).build()
    }

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
