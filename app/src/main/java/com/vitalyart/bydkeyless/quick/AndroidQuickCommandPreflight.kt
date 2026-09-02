package com.vitalyart.bydkeyless.quick

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vitalyart.bydkeyless.model.CommandError

class AndroidQuickCommandPreflight(private val context: Context) : () -> CommandError? {
    override fun invoke(): CommandError? {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            return CommandError.PERMISSION_DENIED
        }
        val enabled = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        return if (enabled) null else CommandError.BLUETOOTH_OFF
    }
}
