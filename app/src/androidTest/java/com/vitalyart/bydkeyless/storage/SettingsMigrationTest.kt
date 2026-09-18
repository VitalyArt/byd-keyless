package com.vitalyart.bydkeyless.storage

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.service.BootReceiver
import com.vitalyart.bydkeyless.service.KeylessService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SettingsMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("keyless_state", Context.MODE_PRIVATE)
    @Before fun isolatedPackageOnly() {
        assumeTrue("Never modify the owner's app preferences", context.packageName.endsWith(".preview"))
        prefs.edit().clear().commit()
    }
    @Test fun passiveEntryMigratesBeforeBootHandlingWithoutEnablingAutomation() {
        prefs.edit().putString("keyless_mode", "PASSIVE_ENTRY").putBoolean("auto_unlock", true).putBoolean("auto_lock", true)
            .putString("session", "sentinel").putInt("near_rssi", -55).putInt("far_rssi", -85).commit()
        val store = SecureSessionStore(context)
        assertEquals(KeylessMode.OFF, store.keylessMode)
        assertFalse(store.autoUnlock); assertFalse(store.autoLock)
        assertEquals("sentinel", prefs.getString("session", null))
        assertNotNull(store.calibration())
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(KeylessService.running.value)
    }
    @Test fun failedWriteRestoresPreviousCalibration() {
        var failNext = false
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                return object : android.content.SharedPreferences by original {
                    override fun edit(): android.content.SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : android.content.SharedPreferences.Editor by editor {
                            override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                            override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                            override fun commit(): Boolean {
                                editor.commit()
                                return if (failNext) { failNext = false; false } else true
                            }
                        }
                    }
                }
            }
        }
        val store = SecureSessionStore(wrapped)
        val previous = ProximityCalibration(-55, -85)
        assertTrue(store.saveCalibration(previous))
        failNext = true
        assertFalse(store.saveCalibration(ProximityCalibration(-65, -95)))
        assertEquals(previous, store.calibration())
        assertEquals(previous, SecureSessionStore(context).calibration())
    }

    @Test fun preservesThresholdsAndPausesAcrossStoreRecreation() {
        prefs.edit().putInt("near_rssi", -55).putInt("far_rssi", -85)
            .putFloat("unlock_distance_meters", 1.5f).putFloat("lock_distance_meters", 4f)
            .putString("keyless_mode", "AUTO_UNLOCK_LOCK").putBoolean("auto_unlock", true).commit()
        val store = SecureSessionStore(context)
        val expected = ProximityCalibration.fromLegacy(-55, -85, 1.5, 4.0)
        assertEquals(expected, store.calibration())
        assertEquals(2, prefs.getInt("calibration_version", 0))
        assertTrue(store.pauseAutomation())
        val recreated = SecureSessionStore(context)
        assertFalse(recreated.autoUnlock); assertFalse(recreated.autoLock)
        assertEquals(expected, recreated.calibration())
        val replacement = ProximityCalibration(-60.5, -90.5)
        assertTrue(store.saveCalibration(replacement))
        assertEquals(replacement, SecureSessionStore(context).calibration())
        store.clearSession()
        assertNull(store.calibration())
    }
}
