package com.vitalyart.bydkeyless

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.ui.*
import com.vitalyart.bydkeyless.update.AppUpdateManager

class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var pendingDangerous: VehicleCommand? = null
    private val permissionPrefs by lazy { getSharedPreferences("permission_state", MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPrefs.edit().putBoolean(PERMISSIONS_REQUESTED, true).apply()
        val state = resolvePermissionState()
        viewModel.updatePermissionState(state)
        if (state == PermissionState.GRANTED) viewModel.startKey()
        else viewModel.showError(UiMessage(R.string.error_permissions_denied))
    }
    private val bluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val state = resolvePermissionState()
        viewModel.updatePermissionState(state)
        if (state == PermissionState.GRANTED) viewModel.startKey()
        else if (state == PermissionState.BLUETOOTH_OFF) viewModel.showError(UiMessage(R.string.error_bluetooth_off))
    }
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) pendingDangerous?.let { viewModel.execute(it, confirmed = true) }
        pendingDangerous = null
    }
    private val unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) launchUpdateInstaller()
    }
    private val updateInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        (application as KeylessApplication).graph.updateManager.installationCancelled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeylessApp(
                viewModel = viewModel,
                onKeyAction = ::handleKeyAction,
                onBackgroundSettings = ::openBackgroundSettings,
                onDangerousCommand = ::confirmDangerous,
                onInstallUpdate = ::installUpdate,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updatePermissionState(resolvePermissionState())
    }

    private fun handleKeyAction() {
        when (resolvePermissionState().also(viewModel::updatePermissionState)) {
            PermissionState.GRANTED -> viewModel.startKey()
            PermissionState.BLUETOOTH_OFF -> bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            PermissionState.PERMANENTLY_DENIED -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            PermissionState.UNKNOWN, PermissionState.DENIED -> permissionLauncher.launch(requestedPermissions().toTypedArray())
        }
    }

    private fun resolvePermissionState(): PermissionState {
        val missing = requiredNearbyPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val enabled = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        val requested = permissionPrefs.getBoolean(PERMISSIONS_REQUESTED, false)
        return PermissionStateResolver.resolve(
            hasRequiredPermissions = missing.isEmpty(),
            requestedBefore = requested,
            hasPermanentlyDeniedPermission = missing.any { !shouldShowRequestPermissionRationale(it) },
            bluetoothEnabled = enabled,
        )
    }

    private fun requiredNearbyPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestedPermissions() = buildList {
        addAll(requiredNearbyPermissions())
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun confirmDangerous(command: VehicleCommand) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.dangerous_title),
            getString(R.string.dangerous_description),
        )
        if (intent == null) {
            viewModel.showError(UiMessage(R.string.error_device_security))
            return
        }
        pendingDangerous = command
        credentialLauncher.launch(intent)
    }

    private fun openBackgroundSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun installUpdate() {
        if (!packageManager.canRequestPackageInstalls()) {
            unknownSourcesLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
            )
            return
        }
        launchUpdateInstaller()
    }

    private fun launchUpdateInstaller() {
        val manager = (application as KeylessApplication).graph.updateManager
        val file = manager.readyFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        manager.markInstalling()
        updateInstallerLauncher.launch(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, AppUpdateManager.APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private companion object {
        const val PERMISSIONS_REQUESTED = "nearby_requested"
    }
}
