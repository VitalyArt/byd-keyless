package com.vitalyart.bydkeyless.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.model.*

@Composable internal fun AccessScreen(state: MainUiState, vm: MainViewModel, connect: () -> Unit, modifier: Modifier) {
    val owner = LocalLifecycleOwner.current
    val activity = androidx.compose.ui.platform.LocalView.current.context as? android.app.Activity
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) vm.cancelMeasurement() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); if (activity?.isChangingConfigurations != true) vm.cancelMeasurement() }
    }
    val draft = state.calibration
    BackHandler(draft.step != CalibrationStep.CLOSED) {
        if (draft.step in setOf(CalibrationStep.PREPARE, CalibrationStep.SAVED, CalibrationStep.TEST)) vm.closeCalibration()
        else vm.previousCalibrationStep()
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (draft.step == CalibrationStep.CLOSED) {
            Text(stringResource(R.string.tab_access), style = MaterialTheme.typography.headlineMedium)
            SectionCard(R.string.automation, Icons.Rounded.NearMe) {
                SettingSwitch(R.string.mode_auto, state.mode == KeylessMode.AUTO_UNLOCK_LOCK, true) {
                    vm.configureKeyless(if (it) KeylessMode.AUTO_UNLOCK_LOCK else KeylessMode.OFF, false, false)
                }
                SettingSwitch(R.string.auto_unlock, state.autoUnlock, state.mode == KeylessMode.AUTO_UNLOCK_LOCK && state.calibrated && state.manualUnlockVerified) { vm.configureKeyless(autoUnlock = it) }
                SettingSwitch(R.string.auto_lock, state.autoLock, state.mode == KeylessMode.AUTO_UNLOCK_LOCK && state.calibrated && state.manualLockVerified) { vm.configureKeyless(autoLock = it) }
                if (!state.calibrated || !state.manualUnlockVerified || !state.manualLockVerified) Text(stringResource(R.string.automation_prerequisites), color = TextSecondary)
                if (state.proximity.needsConfirmation) Text(stringResource(R.string.automation_needs_confirmation), color = Critical)
            }
            SectionCard(R.string.calibration_title, Icons.Rounded.Tune) {
                Text(stringResource(if (state.calibrated) R.string.points_ready else R.string.points_missing))
                Button({ vm.beginCalibration() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.setup_points)) }
                if (state.calibrated) {
                    TextButton({ vm.beginCalibration(true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.change_open_point)) }
                    TextButton({ vm.beginCalibration(false) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.change_close_point)) }
                    OutlinedButton({ vm.beginCalibration(test = true) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.test_zones)) }
                }
                Text(stringResource(R.string.zone_explanation), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { MessageCard(it, true) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (draft.step in setOf(CalibrationStep.OPEN, CalibrationStep.CLOSE, CalibrationStep.REVIEW)) TextButton(vm::previousCalibrationStep) { Text(stringResource(R.string.back)) }
                TextButton(vm::closeCalibration) { Text(stringResource(R.string.close)) }
            }
            val step = when (draft.step) {
                CalibrationStep.PREPARE -> 1
                CalibrationStep.OPEN -> 2
                CalibrationStep.CLOSE -> 3
                else -> 4
            }
            if (draft.step != CalibrationStep.TEST) Text(stringResource(R.string.step_of_four, step), color = TextSecondary)
            Text(stringResource(when (draft.step) {
                CalibrationStep.PREPARE -> R.string.prepare_points
                CalibrationStep.OPEN -> R.string.open_point
                CalibrationStep.CLOSE -> R.string.close_point
                CalibrationStep.REVIEW -> R.string.review_points
                CalibrationStep.SAVED -> R.string.calibration_saved
                else -> R.string.test_zones
            }), style = MaterialTheme.typography.headlineMedium)
            when (draft.step) {
                CalibrationStep.PREPARE -> {
                    Text(stringResource(R.string.prepare_points_body))
                    Text(stringResource(R.string.connection_label, stringResource(connectionStatus(state.bleState))), color = TextSecondary)
                    if (state.bleState != BleConnectionState.READY) OutlinedButton(connect, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.connect_key)) }
                    Button(vm::advanceCalibration, Modifier.fillMaxWidth(), enabled = state.bleState == BleConnectionState.READY, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.begin_measurement)) }
                }
                CalibrationStep.OPEN, CalibrationStep.CLOSE -> {
                    Text(stringResource(if (draft.step == CalibrationStep.OPEN) R.string.open_point_body else R.string.close_point_body))
                    val ready = if (draft.step == CalibrationStep.OPEN) draft.open != null else draft.close != null
                    if (draft.measuring) {
                        LinearProgressIndicator(progress = draft.samples / 8f, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.samples_progress, draft.samples))
                    } else if (ready) Text(stringResource(R.string.calibration_sample_ready), color = Electric)
                    OutlinedButton(vm::captureCalibration, Modifier.fillMaxWidth(), enabled = !draft.measuring && state.bleState == BleConnectionState.READY, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(if (ready) R.string.measure_again else R.string.measure_point))
                    }
                    Button(vm::advanceCalibration, Modifier.fillMaxWidth(), enabled = ready && !draft.measuring, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.next_step)) }
                }
                CalibrationStep.REVIEW -> {
                    Text(stringResource(R.string.review_points_body))
                    Text(stringResource(R.string.zone_explanation), color = TextSecondary)
                    Button(vm::saveCalibration, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.save_calibration)) }
                }
                CalibrationStep.SAVED -> {
                    Text(stringResource(R.string.points_saved_body))
                    Button(vm::closeCalibration, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.done)) }
                }
                CalibrationStep.TEST -> {
                    Text(stringResource(R.string.test_zones_body))
                    val zone = if (state.bleState == BleConnectionState.READY) state.proximity.zone else ProximityZone.UNKNOWN
                    Text(stringResource(when (zone) {
                        ProximityZone.NEAR -> R.string.proximity_near
                        ProximityZone.FAR -> R.string.proximity_far
                        ProximityZone.APPROACHING -> R.string.proximity_approaching
                        else -> R.string.proximity_unknown
                    }), style = MaterialTheme.typography.headlineLarge)
                }
                else -> Unit
            }
            draft.error?.let { MessageCard(it, true) }
        }
    }
}

@Composable internal fun ThemeSetting(selected: String, change: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val options = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
    SectionCard(R.string.appearance, Icons.Rounded.Palette) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(stringResource(options.firstOrNull { it.first == selected }?.second ?: R.string.theme_system))
            Spacer(Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null)
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.appearance)) }, text = {
        Column { options.forEach { (value, label) ->
            TextButton({ change(value); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (selected == value) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(label))
            }
        } }
    }, confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.close)) } })
}
