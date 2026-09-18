package com.vitalyart.bydkeyless.ui

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.annotation.StringRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.ble.ActionAvailability
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.network.WatchRegions
import com.vitalyart.bydkeyless.update.UpdateError
import com.vitalyart.bydkeyless.update.UpdateState
import java.text.NumberFormat

@Composable
fun KeylessApp(
    viewModel: MainViewModel,
    onKeyAction: () -> Unit,
    onBackgroundSettings: () -> Unit,
    onDangerousCommand: (VehicleCommand) -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    KeylessTheme(state.theme) {
        Surface(Modifier.fillMaxSize(), color = Night) {
            if (state.profile == null) {
                AuthorizationScreen(state, viewModel::beginAuthorization, viewModel::selectWatchCountry)
            } else {
                MainShell(state, viewModel, onKeyAction, onBackgroundSettings, onDangerousCommand, onInstallUpdate)
            }
        }
        UpdatePrompt(state.updateState, viewModel, onInstallUpdate)
    }
}

@Composable
internal fun AuthorizationScreen(state: MainUiState, begin: () -> Unit, selectCountry: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 48.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.brand_name), color = Electric, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.auth_description), color = TextSecondary, textAlign = TextAlign.Center)
        }
        if (state.qrSession == null) {
            item { RegionCard(state.watchCountryCode, selectCountry) }
            item {
                Button(
                    onClick = begin,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.QrCode2, null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (state.loading) R.string.connecting else R.string.create_qr))
                }
            }
        } else {
            item { QrCard(state.qrSession.qrPayload, state.qrStatus) }
            if (state.qrStatus == WatchQrStatus.EXPIRED) {
                item { TextButton(begin) { Text(stringResource(R.string.refresh_qr)) } }
            }
        }
        state.error?.let { item { MessageCard(it, true) } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, null, tint = Electric, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.auth_instruction), color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Image(
        painter = painterResource(R.drawable.ic_launcher_artwork),
        contentDescription = null,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun RegionCard(code: String, onSelected: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val localized = WatchRegions.all.map { region ->
        region to if (region.code in localizedRegionCodes) stringResource(regionName(region.code)) else region.name
    }.sortedBy { it.second }
    val selectedName = localized.firstOrNull { it.first.code == code }?.second.orEmpty()
    Surface(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Panel,
        border = BorderStroke(1.dp, StrokeColor),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Electric.copy(.12f)), contentAlignment = Alignment.Center) {
                Text(code, color = Electric, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.region_title), fontWeight = FontWeight.SemiBold)
                Text(selectedName, color = TextSecondary, fontSize = 13.sp)
            }
            Icon(Icons.Rounded.ExpandMore, stringResource(R.string.region_choose), tint = Electric)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.region_choose)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 430.dp)) {
                items(localized, key = { it.first.code }) { (region, name) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelected(region.code); open = false }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(region.code, Modifier.width(54.dp), color = Electric, fontWeight = FontWeight.Bold)
                        Text(name, Modifier.weight(1f))
                        if (region.code == code) Icon(Icons.Rounded.Check, null, tint = Electric)
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun QrCard(payload: String, status: WatchQrStatus) {
    val bitmap = remember(payload) { qrBitmap(payload, 720) }
    val statusId = when (status) {
        WatchQrStatus.WAITING_FOR_SCAN -> R.string.qr_waiting_scan
        WatchQrStatus.WAITING_FOR_CONFIRMATION -> R.string.qr_waiting_confirmation
        WatchQrStatus.APPROVED -> R.string.qr_approved
        WatchQrStatus.EXPIRED -> R.string.qr_expired
        else -> R.string.qr_preparing
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            BitmapPainter(bitmap.asImageBitmap()),
            stringResource(R.string.qr_content_description),
            Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(statusId), color = Night, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

private enum class MainTab { HOME, CONTROLS, SETTINGS }

@Composable
private fun MainShell(
    state: MainUiState,
    vm: MainViewModel,
    keyAction: () -> Unit,
    backgroundSettings: () -> Unit,
    dangerous: (VehicleCommand) -> Unit,
    installUpdate: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    val tab = runCatching { MainTab.valueOf(tabName) }.getOrDefault(MainTab.HOME)
    var controls by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(controls) { controls = false }
    Scaffold(
        containerColor = Night,
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                listOf(
                    Triple(MainTab.HOME, Icons.Rounded.DirectionsCar, R.string.tab_key),
                    Triple(MainTab.CONTROLS, Icons.Rounded.NearMe, R.string.tab_access),
                    Triple(MainTab.SETTINGS, Icons.Rounded.Settings, R.string.tab_settings),
                ).forEach { (item, icon, label) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tabName = item.name; controls = false },
                        icon = { Icon(icon, stringResource(label)) },
                        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.HOME -> if (controls) Column(Modifier.padding(padding)) {
                TextButton({ controls = false }) { Text(stringResource(R.string.back)) }
                ControlsScreen(state, vm, dangerous, Modifier.weight(1f))
            } else HomeScreen(state, vm::availability, { vm.execute(it) }, keyAction, { controls = true }, Modifier.padding(padding))
            MainTab.CONTROLS -> AccessScreen(state, vm, keyAction, Modifier.padding(padding))
            MainTab.SETTINGS -> SettingsScreen(state, vm, keyAction, backgroundSettings, installUpdate, Modifier.padding(padding))
        }
    }
}

@Composable
internal fun HomeScreen(state: MainUiState, availability: (VehicleCommand) -> ActionAvailability, execute: (VehicleCommand) -> Unit, keyAction: () -> Unit, controls: () -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 24.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Text(state.profile?.modelName ?: stringResource(R.string.vehicle_fallback), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(maskVin(state.profile?.vin).orEmpty(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        item {
            SectionCard(R.string.key_status, Icons.Rounded.Key) {
                Text(stringResource(connectionStatus(state.bleState)), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(doorStatus(state.telemetry.doorState)), color = TextSecondary)
                Text(stringResource(R.string.last_known_state), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (shouldShowKeyCta(state)) OutlinedButton(keyAction, Modifier.fillMaxWidth()) { Text(stringResource(keyCtaLabel(state))) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(VehicleCommand.UNLOCK, VehicleCommand.LOCK).forEach { command ->
                    Button({ execute(command) }, enabled = availability(command).enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp), shape = RoundedCornerShape(12.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(commandIcon(command), null)
                            Text(stringResource(commandLabel(command)))
                        }
                    }
                }
            }
        }
        item {
            listOf(VehicleCommand.OPEN_TRUNK, VehicleCommand.FIND_CAR).filter { availability(it).supported }.forEach { command ->
                ControlRow(command, availability(command)) { execute(command) }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(controls, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.more_controls)) }
        }
        state.commandResult?.let { item { MessageCard(it, false) } }
        state.error?.let { item { MessageCard(it, true) } }
    }
}

@Composable
internal fun ControlsScreen(state: MainUiState, vm: MainViewModel, dangerous: (VehicleCommand) -> Unit, modifier: Modifier) {
    val groups = listOf(
        R.string.controls_access to listOf(VehicleCommand.UNLOCK, VehicleCommand.LOCK, VehicleCommand.OPEN_TRUNK, VehicleCommand.CLOSE_TRUNK),
        R.string.controls_comfort to listOf(VehicleCommand.CLOSE_WINDOWS, VehicleCommand.TURN_ON_AC, VehicleCommand.TURN_OFF_AC),
        R.string.controls_locate to listOf(VehicleCommand.FIND_CAR, VehicleCommand.FLASH_LIGHT, VehicleCommand.UWB_LOCATE),
        R.string.controls_power to listOf(VehicleCommand.START_ENGINE, VehicleCommand.STOP_ENGINE),
        R.string.controls_experimental to listOf(VehicleCommand.OPEN_ELECTRIC_REAR_DOOR, VehicleCommand.CLOSE_ELECTRIC_REAR_DOOR, VehicleCommand.RPA_UNLOCK, VehicleCommand.RPA_FORWARD, VehicleCommand.RPA_BACKWARD, VehicleCommand.RPA_LEFT, VehicleCommand.RPA_RIGHT, VehicleCommand.YUNNIAN_SCENE),
    ).map { (title, commands) -> title to commands.filter { vm.availability(it).supported && (!it.experimental || state.experimental) } }
        .filter { it.second.isNotEmpty() }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.controls_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.controls_description), color = TextSecondary, fontSize = 13.sp)
        }
        if (groups.isEmpty()) item { EmptyControls() }
        groups.forEach { (title, commands) ->
            item { Text(stringResource(title).uppercase(), color = if (title == R.string.controls_experimental) Critical else Electric, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
            items(commands, key = { it.name }) { command ->
                ControlRow(command, vm.availability(command)) {
                    if (command.dangerous) dangerous(command) else vm.execute(command)
                }
            }
        }
        state.commandResult?.let { item { MessageCard(it, false) } }
        state.error?.let { item { MessageCard(it, true) } }
    }
}

@Composable
private fun EmptyControls() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.DirectionsCar, null, tint = TextSecondary, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.controls_empty), color = TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ControlRow(command: VehicleCommand, availability: ActionAvailability, action: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() },
        enabled = availability.enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Panel,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background((if (availability.enabled) Electric else TextSecondary).copy(.1f)), contentAlignment = Alignment.Center) {
                Icon(commandIcon(command), null, tint = if (command.dangerous) Critical else if (availability.enabled) Electric else TextSecondary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(commandLabel(command)), color = if (availability.enabled) TextPrimary else TextSecondary, fontWeight = FontWeight.SemiBold)
                availability.reason?.let { Text(stringResource(it.messageResource()), color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp) }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = if (availability.enabled) Electric else TextSecondary)
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    vm: MainViewModel,
    keyAction: () -> Unit,
    backgroundSettings: () -> Unit,
    installUpdate: () -> Unit,
    modifier: Modifier,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf(0) }
    androidx.activity.compose.BackHandler(detail != 0) { detail = 0 }
    if (detail != 0) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton({ detail = 0 }) { Text(stringResource(R.string.back)) }
            if (detail == 1) BackgroundDiagnostics(state) else SectionCard(R.string.safety_title, Icons.Rounded.Security) {
                SettingSwitch(R.string.experimental_commands, state.experimental, true, vm::setExperimental)
                Text(stringResource(R.string.experimental_warning), color = TextSecondary)
                Text(stringResource(R.string.quick_controls_warning), color = TextSecondary)
            }
        }
        return
    }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium) }
        item { ThemeSetting(state.theme, vm::setTheme) }
        item { TextButton({ detail = 2 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.experimental_commands)) } }
        item { LanguageSetting(state.language, vm::setLanguage) }
        item { UpdateSettingsCard(state, vm, installUpdate) }
        item { TextButton({ detail = 1 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.diagnostics_title)) } }
        item {
            SectionCard(R.string.background_title, Icons.Rounded.BatterySaver) {
                Text(stringResource(R.string.background_description), color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                OutlinedButton(backgroundSettings, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.BatteryAlert, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.battery_settings))
                }
                Text(stringResource(R.string.force_stop_warning), color = Critical, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        item { OutlinedButton(keyAction, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Rounded.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.permissions_title)) } }
        item { OutlinedButton({ vm.configureKeyless(KeylessMode.OFF, false, false) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Critical)) { Icon(Icons.Rounded.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.emergency_stop)) } }
        item { TextButton({ confirmLogout = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.sign_out), color = TextSecondary) } }
        state.commandResult?.let { item { MessageCard(it, false) } }
        state.error?.let { item { MessageCard(it, true) } }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        icon = { Icon(Icons.Rounded.DeleteForever, null, tint = Critical) },
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = { Text(stringResource(R.string.sign_out_description)) },
        dismissButton = { TextButton({ confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton({ confirmLogout = false; vm.logout() }) { Text(stringResource(R.string.delete_key), color = Critical) } },
    )
}

@Composable
private fun UpdateSettingsCard(state: MainUiState, vm: MainViewModel, installUpdate: () -> Unit) {
    val update = state.updateState
    SectionCard(R.string.update_title, Icons.Rounded.SystemUpdate) {
        Text(stringResource(R.string.update_current_version, state.currentAppVersion), color = TextSecondary, fontSize = 13.sp)
        SettingSwitch(
            R.string.update_prerelease_channel,
            state.includePrereleaseUpdates,
            update !is UpdateState.Downloading && update !is UpdateState.ReadyToInstall && update !is UpdateState.Installing,
            vm::setPrereleaseUpdates,
        )
        when (update) {
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.update_checking), color = TextSecondary)
            }
            is UpdateState.Available -> {
                Text(stringResource(R.string.update_available, update.release.version.toString()), fontWeight = FontWeight.SemiBold)
                Button(vm::downloadUpdate, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_download)) }
            }
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.update_downloading, update.release.version.toString()), fontWeight = FontWeight.SemiBold)
                if (update.progress != null) {
                    LinearProgressIndicator(progress = { update.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_progress, update.progress), color = TextSecondary, fontSize = 12.sp)
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.ReadyToInstall -> {
                Text(stringResource(R.string.update_ready, update.release.version.toString()), fontWeight = FontWeight.SemiBold)
                Button(installUpdate, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install)) }
            }
            is UpdateState.Installing -> Text(stringResource(R.string.update_installing), color = TextSecondary)
            is UpdateState.Error -> {
                Text(stringResource(updateErrorText(update.error)), color = Critical, fontSize = 13.sp)
                update.details?.let { details ->
                    Text(stringResource(R.string.update_error_details, details), color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            UpdateState.Idle -> Text(stringResource(R.string.update_no_update), color = TextSecondary, fontSize = 13.sp)
        }
        OutlinedButton(
            vm::checkForUpdates,
            enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading && update !is UpdateState.ReadyToInstall && update !is UpdateState.Installing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.update_check)) }
    }
}

@Composable
private fun UpdatePrompt(state: UpdateState, vm: MainViewModel, installUpdate: () -> Unit) {
    when (state) {
        is UpdateState.Available -> if (state.prompt) AlertDialog(
            onDismissRequest = vm::dismissUpdatePrompt,
            icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = Electric) },
            title = { Text(stringResource(R.string.update_dialog_title)) },
            text = { Text(stringResource(R.string.update_dialog_message, state.release.version.toString())) },
            dismissButton = { TextButton(vm::dismissUpdatePrompt) { Text(stringResource(R.string.update_later)) } },
            confirmButton = { Button(vm::downloadUpdate) { Text(stringResource(R.string.update_download)) } },
        )
        is UpdateState.ReadyToInstall -> if (state.prompt) AlertDialog(
            onDismissRequest = vm::dismissUpdatePrompt,
            icon = { Icon(Icons.Rounded.InstallMobile, null, tint = Electric) },
            title = { Text(stringResource(R.string.update_ready_title)) },
            text = { Text(stringResource(R.string.update_ready_message, state.release.version.toString())) },
            dismissButton = { TextButton(vm::dismissUpdatePrompt) { Text(stringResource(R.string.update_later)) } },
            confirmButton = { Button(installUpdate) { Text(stringResource(R.string.update_install)) } },
        )
        is UpdateState.Error -> if (state.showDialog) AlertDialog(
            onDismissRequest = vm::clearUpdateError,
            icon = { Icon(Icons.Rounded.ErrorOutline, null, tint = Critical) },
            title = { Text(stringResource(R.string.update_error_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(updateErrorText(state.error)))
                    state.details?.let { details ->
                        Text(stringResource(R.string.update_error_details, details), color = TextSecondary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(vm::clearUpdateError) { Text(stringResource(R.string.close)) } },
        )
        UpdateState.Checking, UpdateState.Idle, is UpdateState.Downloading, is UpdateState.Installing -> Unit
    }
}

@StringRes
private fun updateErrorText(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.RELEASE_INVALID -> R.string.update_error_release
    UpdateError.DOWNLOAD_FAILED -> R.string.update_error_download
    UpdateError.CHECKSUM_MISMATCH -> R.string.update_error_checksum
    UpdateError.PACKAGE_INVALID -> R.string.update_error_package
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
}

@Composable
internal fun SectionCard(@StringRes title: Int, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp, StrokeColor, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Electric, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Text(stringResource(title), color = Electric, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        }
        content()
    }
}

@Composable
internal fun SettingSwitch(@StringRes title: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f), color = if (enabled) TextPrimary else TextSecondary)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun LanguageSetting(selected: String, onSelected: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val options = listOf("system" to R.string.language_system, "en" to R.string.language_english, "ru" to R.string.language_russian, "uz" to R.string.language_uzbek)
    SectionCard(R.string.language_title, Icons.Rounded.Language) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(options.firstOrNull { it.first == selected }?.second ?: R.string.language_system))
            Spacer(Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.language_title)) },
        text = { Column { options.forEach { (code, name) -> Row(Modifier.fillMaxWidth().clickable { onSelected(code); open = false }.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == code, { onSelected(code); open = false }); Text(stringResource(name)) } } } },
        confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun MessageCard(message: UiMessage, error: Boolean) {
    val text = stringResource(message.resourceId, *message.arguments.toTypedArray())
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background((if (error) Critical else Electric).copy(.1f)).padding(14.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, tint = if (error) Critical else Electric)
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (error) Critical else Electric, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@StringRes internal fun connectionStatus(state: BleConnectionState) = when (state) {
    BleConnectionState.IDLE -> R.string.status_idle
    BleConnectionState.SCANNING -> R.string.status_scanning
    BleConnectionState.CONNECTING -> R.string.status_connecting
    BleConnectionState.DISCOVERING -> R.string.status_discovering
    BleConnectionState.AUTHENTICATING -> R.string.status_authenticating
    BleConnectionState.READY -> R.string.status_ready
    BleConnectionState.DISCONNECTED -> R.string.status_disconnected
    BleConnectionState.ERROR -> R.string.status_error
}

@StringRes private fun doorStatus(state: DoorState) = when (state) { DoorState.LOCKED -> R.string.door_locked; DoorState.UNLOCKED -> R.string.door_unlocked; DoorState.UNKNOWN -> R.string.door_unknown }

@StringRes internal fun commandLabel(command: VehicleCommand) = when (command) {
    VehicleCommand.LOCK -> R.string.action_lock; VehicleCommand.UNLOCK -> R.string.action_unlock; VehicleCommand.FIND_CAR -> R.string.action_find_car
    VehicleCommand.OPEN_TRUNK -> R.string.widget_trunk; VehicleCommand.CLOSE_TRUNK -> R.string.action_close_trunk; VehicleCommand.CLOSE_WINDOWS -> R.string.action_close_windows
    VehicleCommand.FLASH_LIGHT -> R.string.action_flash_lights; VehicleCommand.TURN_ON_AC -> R.string.action_ac_on; VehicleCommand.TURN_OFF_AC -> R.string.action_ac_off
    VehicleCommand.START_ENGINE -> R.string.action_start_engine; VehicleCommand.STOP_ENGINE -> R.string.action_stop_engine; VehicleCommand.OPEN_ELECTRIC_REAR_DOOR -> R.string.action_open_rear_door
    VehicleCommand.CLOSE_ELECTRIC_REAR_DOOR -> R.string.action_close_rear_door; VehicleCommand.PAUSE_ELECTRIC_REAR_DOOR -> R.string.action_pause_rear_door
    VehicleCommand.UWB_LOCATE -> R.string.action_uwb_locate; VehicleCommand.RPA_UNLOCK -> R.string.action_rpa_unlock; VehicleCommand.RPA_FORWARD -> R.string.action_rpa_forward
    VehicleCommand.RPA_BACKWARD -> R.string.action_rpa_backward; VehicleCommand.RPA_LEFT -> R.string.action_rpa_left; VehicleCommand.RPA_RIGHT -> R.string.action_rpa_right
    VehicleCommand.YUNNIAN_SCENE -> R.string.action_yunnian
}

private fun commandIcon(command: VehicleCommand): ImageVector = when (command) {
    VehicleCommand.LOCK -> Icons.Rounded.Lock; VehicleCommand.UNLOCK, VehicleCommand.RPA_UNLOCK -> Icons.Rounded.LockOpen
    VehicleCommand.OPEN_TRUNK, VehicleCommand.CLOSE_TRUNK, VehicleCommand.OPEN_ELECTRIC_REAR_DOOR, VehicleCommand.CLOSE_ELECTRIC_REAR_DOOR, VehicleCommand.PAUSE_ELECTRIC_REAR_DOOR -> Icons.Rounded.DoorBack
    VehicleCommand.FIND_CAR, VehicleCommand.UWB_LOCATE -> Icons.Rounded.LocationSearching
    VehicleCommand.FLASH_LIGHT -> Icons.Rounded.Highlight; VehicleCommand.TURN_ON_AC, VehicleCommand.TURN_OFF_AC -> Icons.Rounded.AcUnit
    VehicleCommand.CLOSE_WINDOWS -> Icons.Rounded.WebAsset; VehicleCommand.START_ENGINE, VehicleCommand.STOP_ENGINE -> Icons.Rounded.PowerSettingsNew
    else -> Icons.Rounded.DirectionsCar
}

@StringRes private fun regionName(code: String) = when (code) {
    "UZ" -> R.string.region_uz; "KZ" -> R.string.region_kz; "NO" -> R.string.region_no; "NL" -> R.string.region_nl; "DE" -> R.string.region_de; "GB" -> R.string.region_gb
    "FR" -> R.string.region_fr; "IT" -> R.string.region_it; "ES" -> R.string.region_es; "SG" -> R.string.region_sg; "AU" -> R.string.region_au; "BR" -> R.string.region_br
    "JP" -> R.string.region_jp; "OM" -> R.string.region_om; "MX" -> R.string.region_mx; "ID" -> R.string.region_id; "TR" -> R.string.region_tr; "KR" -> R.string.region_kr
    "IN" -> R.string.region_in; "VNM" -> R.string.region_vnm; "SA" -> R.string.region_sa; else -> R.string.region_uz
}

private val localizedRegionCodes = setOf(
    "UZ", "KZ", "NO", "NL", "DE", "GB", "FR", "IT", "ES", "SG", "AU", "BR", "JP", "OM", "MX", "ID", "TR", "KR", "IN", "VNM", "SA",
)

private fun shouldShowKeyCta(state: MainUiState): Boolean = state.permissionState != PermissionState.GRANTED || state.bleState in setOf(BleConnectionState.IDLE, BleConnectionState.DISCONNECTED, BleConnectionState.ERROR)

@StringRes private fun keyCtaLabel(state: MainUiState) = when (state.permissionState) {
    PermissionState.DENIED, PermissionState.UNKNOWN -> R.string.permission_allow
    PermissionState.PERMANENTLY_DENIED -> R.string.permission_open_settings
    PermissionState.BLUETOOTH_OFF -> R.string.bluetooth_enable
    PermissionState.GRANTED -> if (state.bleState == BleConnectionState.IDLE) R.string.key_start else R.string.key_retry
}

private fun keyCtaIcon(state: MainUiState): ImageVector = when (state.permissionState) {
    PermissionState.PERMANENTLY_DENIED -> Icons.Rounded.Settings
    PermissionState.BLUETOOTH_OFF -> Icons.Rounded.BluetoothDisabled
    PermissionState.DENIED, PermissionState.UNKNOWN -> Icons.Rounded.Bluetooth
    PermissionState.GRANTED -> Icons.Rounded.Refresh
}

private fun qrBitmap(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

private fun maskVin(vin: String?): String? = vin?.takeIf { it.isNotBlank() }?.let { "•••••••••••${it.takeLast(6)}" }

@Composable
private fun BackgroundDiagnostics(state: MainUiState) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = SystemClock.elapsedRealtime() } }
    SectionCard(R.string.diagnostics_title, Icons.Rounded.Bluetooth) {
        Text(stringResource(if (state.serviceRunning) R.string.diagnostics_service_on else R.string.diagnostics_service_off))
        Text(stringResource(connectionStatus(state.bleState)), color = TextSecondary)
        state.bleError?.let { Text(stringResource(it.messageResource()), color = Critical) }
        val rssiAge = state.telemetry.rssiAtMillis?.let { ((now - it).coerceAtLeast(0) / 1_000).toString() } ?: "—"
        val packetAge = state.diagnostics.lastPacketAtMillis?.let { ((now - it).coerceAtLeast(0) / 1_000).toString() } ?: "—"
        Text(stringResource(R.string.diagnostics_signal_age, rssiAge), color = TextSecondary)
        Text(stringResource(R.string.diagnostics_packet_age, packetAge), color = TextSecondary)
        Text(stringResource(R.string.diagnostics_recoveries, state.diagnostics.recoveries), color = TextSecondary)
        state.diagnostics.lastRecovery?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
    }
}
