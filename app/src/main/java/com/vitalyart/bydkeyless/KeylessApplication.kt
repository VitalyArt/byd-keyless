package com.vitalyart.bydkeyless

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.vitalyart.bydkeyless.ble.AndroidBleVehicleController
import com.vitalyart.bydkeyless.ble.BydNativeFacade
import com.vitalyart.bydkeyless.hotspot.AndroidHotspotController
import com.vitalyart.bydkeyless.hotspot.HotspotAutomationManager
import com.vitalyart.bydkeyless.hotspot.HotspotAutomationSettings
import com.vitalyart.bydkeyless.network.BydWatchAuthRepository
import com.vitalyart.bydkeyless.network.WatchConfig
import com.vitalyart.bydkeyless.proximity.DefaultProximityKeyManager
import com.vitalyart.bydkeyless.quick.AndroidQuickCommandPreflight
import com.vitalyart.bydkeyless.quick.QuickCommandExecutor
import com.vitalyart.bydkeyless.storage.SecureSessionStore
import com.vitalyart.bydkeyless.update.AppUpdateManager
import com.vitalyart.bydkeyless.widget.QuickControlWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KeylessApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var graph: AppGraph
        private set
    override fun onCreate() {
        super.onCreate()
        val store = SecureSessionStore(this)
        val language = store.language
        AppCompatDelegate.setApplicationLocales(
            if (language == "system") LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language),
        )
        val native = BydNativeFacade()
        val ble = AndroidBleVehicleController(this, native, calibration = store::calibration, keylessMode = { store.keylessMode })
        val proximity = DefaultProximityKeyManager(ble, canUnlock = { store.manualUnlockVerified }, canLock = { store.manualLockVerified })
        val quickCommands = QuickCommandExecutor(
            sessionProvider = store::loadSession,
            controller = ble,
            preflight = AndroidQuickCommandPreflight(this),
        )
        val hotspotAutomation = HotspotAutomationManager(
            settings = { HotspotAutomationSettings(store.hotspotOnConnect, store.hotspotOffOnDisconnect, store.hotspotOffDelayMillis) },
            controller = AndroidHotspotController(this),
            scope = applicationScope,
        )
        val updateManager = AppUpdateManager(this, store, applicationScope)
        graph = AppGraph(
            store = store,
            auth = BydWatchAuthRepository(WatchConfig(countryCode = store.watchCountryCode, watchImei = store.watchImei())),
            ble = ble,
            proximity = proximity,
            native = native,
            quickCommands = quickCommands,
            hotspotAutomation = hotspotAutomation,
            updateManager = updateManager,
        )
        QuickControlWidget.updateAll(this)
        applicationScope.launch {
            ble.connectionState.collectLatest {
                hotspotAutomation.onConnectionStateChanged(it)
                QuickControlWidget.updateAll(this@KeylessApplication)
            }
        }
    }

    fun selectWatchCountry(code: String) {
        graph.store.watchCountryCode = code
        graph.auth = BydWatchAuthRepository(WatchConfig(countryCode = code, watchImei = graph.store.watchImei()))
    }
}

data class AppGraph(
    val store: SecureSessionStore,
    var auth: BydWatchAuthRepository,
    val ble: AndroidBleVehicleController,
    val proximity: DefaultProximityKeyManager,
    val native: BydNativeFacade,
    val quickCommands: QuickCommandExecutor,
    val hotspotAutomation: HotspotAutomationManager,
    val updateManager: AppUpdateManager,
)
