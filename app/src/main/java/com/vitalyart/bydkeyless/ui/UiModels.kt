package com.vitalyart.bydkeyless.ui

import androidx.annotation.StringRes
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.ble.UnavailableReason
import com.vitalyart.bydkeyless.model.CommandError

data class UiMessage(@StringRes val resourceId: Int, val arguments: List<Any> = emptyList())

enum class PermissionState { UNKNOWN, GRANTED, DENIED, PERMANENTLY_DENIED, BLUETOOTH_OFF }

object PermissionStateResolver {
    fun resolve(
        hasRequiredPermissions: Boolean,
        requestedBefore: Boolean,
        hasPermanentlyDeniedPermission: Boolean,
        bluetoothEnabled: Boolean,
    ): PermissionState = when {
        !hasRequiredPermissions && requestedBefore && hasPermanentlyDeniedPermission -> PermissionState.PERMANENTLY_DENIED
        !hasRequiredPermissions -> PermissionState.DENIED
        !bluetoothEnabled -> PermissionState.BLUETOOTH_OFF
        else -> PermissionState.GRANTED
    }
}

@StringRes
fun CommandError.messageResource(): Int = when (this) {
    CommandError.NO_PROFILE -> R.string.error_no_profile
    CommandError.KEY_NOT_READY -> R.string.error_key_not_ready
    CommandError.KEY_EXPIRED -> R.string.error_key_expired
    CommandError.EXPERIMENTAL_DISABLED -> R.string.error_experimental_disabled
    CommandError.CAPABILITY_NOT_CONFIRMED -> R.string.error_capability
    CommandError.UNSUPPORTED_TRANSPORT, CommandError.NATIVE_UNAVAILABLE -> R.string.error_native_unavailable
    CommandError.GATT_WRITE_FAILED -> R.string.error_gatt_write
    CommandError.VEHICLE_TIMEOUT -> R.string.error_vehicle_timeout
    CommandError.CLOUD_REJECTED -> R.string.error_cloud_rejected
    CommandError.NO_SESSION -> R.string.error_no_session
    CommandError.BLUETOOTH_OFF -> R.string.error_bluetooth_off
    CommandError.PERMISSION_DENIED -> R.string.error_permissions_denied
    CommandError.CONNECTION_TIMEOUT -> R.string.error_connection_timeout
    CommandError.COMMAND_BUSY -> R.string.error_command_busy
}

@StringRes
fun UnavailableReason.messageResource(): Int = when (this) {
    UnavailableReason.KEY_NOT_READY -> R.string.unavailable_key
    UnavailableReason.NO_SESSION -> R.string.unavailable_session
    UnavailableReason.EXPERIMENTAL_DISABLED -> R.string.unavailable_experimental
    UnavailableReason.KEY_EXPIRED -> R.string.unavailable_expired
}
