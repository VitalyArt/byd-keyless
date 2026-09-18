package com.vitalyart.bydkeyless

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vitalyart.bydkeyless.ble.ActionAvailability
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.quick.QuickSurfaceState
import com.vitalyart.bydkeyless.ui.*
import com.vitalyart.bydkeyless.widget.QuickControlWidget

/** Isolated screenshot harness. Never loads credentials or sends vehicle commands. */
class DesignPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!BuildConfig.APPLICATION_ID.endsWith(".preview")) { super.onCreate(savedInstanceState); finish(); return }
        val language = intent.getStringExtra("language") ?: "ru"
        val previewConfig = Configuration(resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(language))
            fontScale = intent.getFloatExtra("fontScale", 1f)
        }
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val screen = intent.getStringExtra("screen") ?: "home"
        val theme = intent.getStringExtra("theme") ?: "light"
        val vm = MainViewModel(application)
        val state = MainUiState(
            profile = VehicleProfile(modelName = "BYD SEALION 7", vin = "DEMO0000000000000", macAddress = null, digitalKey = null, keyNumber = 0, keyValidTo = null, capabilities = emptySet()),
            theme = theme, bleState = BleConnectionState.READY, permissionState = PermissionState.GRANTED,
            telemetry = VehicleTelemetry(doorState = DoorState.LOCKED), calibrated = true,
            manualUnlockVerified = true, manualLockVerified = true,
            calibration = when (screen) {
                "prepare" -> CalibrationSession(step = CalibrationStep.PREPARE)
                "measure" -> CalibrationSession(step = CalibrationStep.OPEN, measuring = true, samples = 5)
                "review" -> CalibrationSession(step = CalibrationStep.REVIEW, open = -55.0, close = -85.0)
                "error" -> CalibrationSession(step = CalibrationStep.OPEN, error = UiMessage(R.string.calibration_unstable))
                else -> CalibrationSession()
            },
        )
        val previewContext = createConfigurationContext(previewConfig)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides previewContext,
                androidx.compose.ui.platform.LocalConfiguration provides previewConfig,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(resources.displayMetrics.density, previewConfig.fontScale),
            ) {
            KeylessTheme(theme) {
                Scaffold(bottomBar = {
                    if (screen !in setOf("welcome", "widgets", "notification")) NavigationBar {
                        listOf(R.string.tab_key to Icons.Rounded.DirectionsCar, R.string.tab_access to Icons.Rounded.NearMe, R.string.tab_settings to Icons.Rounded.Settings).forEachIndexed { index, (title, icon) ->
                            NavigationBarItem(selected = index == if (screen == "home") 0 else if (screen == "settings") 2 else 1, onClick = {}, icon = { Icon(icon, null) }, label = { Text(stringResource(title), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                        }
                    }
                }) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (screen) {
                        "home" -> HomeScreen(state, { ActionAvailability(true, true) }, {}, {}, {}, modifier)
                        "settings" -> SettingsScreen(state, vm, {}, {}, {}, modifier)
                        "welcome" -> AuthorizationScreen(MainUiState(), {}, {})
                        "notification" -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Text("Android notification · preview", style = MaterialTheme.typography.titleMedium)
                            AndroidView(factory = { context ->
                                val open = android.app.PendingIntent.getActivity(context, 90, android.content.Intent(context, DesignPreviewActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
                                val snapshot = QuickSurfaceState("BYD SEALION 7", context.getString(R.string.widget_connected), context.getString(R.string.automation_off), false, listOf(VehicleCommand.UNLOCK, VehicleCommand.LOCK, VehicleCommand.OPEN_TRUNK), false)
                                val notification = com.vitalyart.bydkeyless.quick.buildQuickNotification(context, "preview", language, snapshot, open) { _, _ -> open }
                                android.app.Notification.Builder.recoverBuilder(context, notification).createBigContentView().apply(context, null).apply {
                                    setBackgroundColor(if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) 0xFF252A33.toInt() else 0xFFFFFFFF.toInt())
                                }
                            }, modifier = Modifier.fillMaxWidth())
                        }
                        "widgets" -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Text("4 × 1", style = MaterialTheme.typography.titleMedium)
                            val snapshot = QuickSurfaceState("BYD SEALION 7", previewContext.getString(R.string.widget_connected), "", false, listOf(VehicleCommand.UNLOCK, VehicleCommand.LOCK, VehicleCommand.OPEN_TRUNK), false)
                            listOf(88 to 72, 180 to 144).forEach { (height, option) ->
                                AndroidView(factory = { context ->
                                    QuickControlWidget.render(context, Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, option) }, snapshot, theme, language).apply(context, null).apply {
                                        // The preview cannot dispatch PendingIntents, including widget buttons.
                                        fun disable(view: android.view.View) { view.setOnClickListener(null); if (view is android.view.ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i)) }
                                        disable(this)
                                    }
                                }, modifier = Modifier.fillMaxWidth().height(height.dp))
                            }
                        }
                        else -> AccessScreen(state, vm, {}, modifier)
                    }
                }
            }
            }
        }
    }
}
