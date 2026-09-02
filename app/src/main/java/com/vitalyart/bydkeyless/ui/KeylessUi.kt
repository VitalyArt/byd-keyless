package com.vitalyart.bydkeyless.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import java.text.NumberFormat

private val Night = Color(0xFF070A0D)
private val Panel = Color(0xFF10161B)
private val PanelRaised = Color(0xFF172027)
private val Electric = Color(0xFF52E2C3)
private val ElectricDark = Color(0xFF082A24)
private val TextPrimary = Color(0xFFF3F7F6)
private val TextSecondary = Color(0xFFA3B1B1)
private val Critical = Color(0xFFFF7777)
private val StrokeColor = Color.White.copy(alpha = .08f)

private val KeylessColors = darkColorScheme(
    primary = Electric,
    onPrimary = ElectricDark,
    background = Night,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextSecondary,
    error = Critical,
)

private val BaseTypography = Typography()
private val KeylessTypography = BaseTypography.copy(
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
)

@Composable
fun KeylessApp(
    viewModel: MainViewModel,
    onKeyAction: () -> Unit,
    onBackgroundSettings: () -> Unit,
    onDangerousCommand: (VehicleCommand) -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = KeylessColors, typography = KeylessTypography) {
        Surface(Modifier.fillMaxSize(), color = Night) {
            if (state.profile == null) {
                AuthorizationScreen(state, viewModel::beginAuthorization, viewModel::selectWatchCountry)
            } else {
                MainShell(state, viewModel, onKeyAction, onBackgroundSettings, onDangerousCommand)
            }
        }
    }
}

@Composable
private fun AuthorizationScreen(state: MainUiState, begin: () -> Unit, selectCountry: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
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
                    shape = RoundedCornerShape(18.dp),
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
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)),
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
        shape = RoundedCornerShape(22.dp),
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White).padding(20.dp),
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
) {
    var tabName by rememberSaveable { mutableStateOf(MainTab.HOME.name) }
    val tab = runCatching { MainTab.valueOf(tabName) }.getOrDefault(MainTab.HOME)
    Scaffold(
        containerColor = Night,
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                listOf(
                    Triple(MainTab.HOME, Icons.Rounded.Key, R.string.tab_key),
                    Triple(MainTab.CONTROLS, Icons.Rounded.DirectionsCar, R.string.tab_controls),
                    Triple(MainTab.SETTINGS, Icons.Rounded.Settings, R.string.tab_settings),
                ).forEach { (item, icon, label) ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tabName = item.name },
                        icon = { Icon(icon, stringResource(label)) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            MainTab.HOME -> HomeScreen(state, vm, keyAction, Modifier.padding(padding))
            MainTab.CONTROLS -> ControlsScreen(state, vm, dangerous, Modifier.padding(padding))
            MainTab.SETTINGS -> SettingsScreen(state, vm, keyAction, backgroundSettings, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(state: MainUiState, vm: MainViewModel, keyAction: () -> Unit, modifier: Modifier) {
    val ready = state.bleState == BleConnectionState.READY
    val primary = if (state.telemetry.doorState == DoorState.UNLOCKED) VehicleCommand.LOCK else VehicleCommand.UNLOCK
    val primaryAvailability = vm.availability(primary)
    val quickCommands = listOf(VehicleCommand.OPEN_TRUNK, VehicleCommand.CLOSE_TRUNK, VehicleCommand.FIND_CAR, VehicleCommand.TURN_ON_AC)
        .filter { vm.availability(it).supported }.take(3)
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.profile?.modelName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.vehicle_fallback), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(maskVin(state.profile?.vin) ?: stringResource(R.string.vin_unavailable), color = TextSecondary, fontSize = 12.sp, letterSpacing = 1.sp)
                }
                StatusPill(ready, stringResource(connectionStatus(state.bleState)))
            }
        }
        item { VehicleHero(state) }
        item {
            Button(
                onClick = { vm.execute(primary) },
                enabled = primaryAvailability.enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (primary == VehicleCommand.UNLOCK) Electric else PanelRaised,
                    contentColor = if (primary == VehicleCommand.UNLOCK) ElectricDark else TextPrimary,
                ),
            ) {
                Icon(if (primary == VehicleCommand.UNLOCK) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(commandLabel(primary)), fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            primaryAvailability.reason?.let {
                Text(stringResource(it.messageResource()), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
        }
        if (shouldShowKeyCta(state)) {
            item {
                OutlinedButton(onClick = keyAction, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(keyCtaIcon(state), null)
                    Spacer(Modifier.width(9.dp))
                    Text(stringResource(keyCtaLabel(state)))
                }
            }
        }
        if (quickCommands.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    quickCommands.forEach { command ->
                        QuickAction(command, vm.availability(command), Modifier.weight(1f)) {
                            if (command.dangerous) Unit else vm.execute(command)
                        }
                    }
                }
            }
        }
        state.commandResult?.let { item { MessageCard(it, false) } }
        state.error?.let { item { MessageCard(it, true) } }
    }
}

@Composable
private fun VehicleHero(state: MainUiState) {
    val pulse by rememberInfiniteTransition(label = "proximity").animateFloat(
        .82f, 1.06f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo",
    )
    val number = NumberFormat.getNumberInstance(LocalContext.current.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
    val distance = state.telemetry.approximateMeters?.let { stringResource(R.string.distance_meters, number.format(it)) } ?: "—"
    val zoneId = when (state.proximity.zone) {
        ProximityZone.NEAR -> R.string.proximity_near
        ProximityZone.APPROACHING -> R.string.proximity_approaching
        ProximityZone.FAR -> R.string.proximity_far
        else -> R.string.proximity_unknown
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 260.dp).clip(RoundedCornerShape(30.dp))
            .background(Brush.verticalGradient(listOf(PanelRaised, Panel))).border(1.dp, StrokeColor, RoundedCornerShape(30.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = center.copy(y = size.height * .49f)
            drawCircle(Electric.copy(alpha = .04f), size.minDimension * .43f * pulse, center)
            drawCircle(Electric.copy(alpha = .16f), size.minDimension * .30f * pulse, center, style = Stroke(1.5.dp.toPx()))
            val car = Path().apply {
                moveTo(size.width * .14f, size.height * .62f)
                quadraticBezierTo(size.width * .23f, size.height * .45f, size.width * .39f, size.height * .41f)
                lineTo(size.width * .67f, size.height * .41f)
                quadraticBezierTo(size.width * .80f, size.height * .47f, size.width * .86f, size.height * .62f)
                lineTo(size.width * .89f, size.height * .69f); lineTo(size.width * .11f, size.height * .69f); close()
            }
            drawPath(car, Brush.horizontalGradient(listOf(Color(0xFF34444B), Color(0xFFC9D7D6), Color(0xFF2A363C))))
            drawCircle(Night, 16.dp.toPx(), Offset(size.width * .27f, size.height * .70f))
            drawCircle(Night, 16.dp.toPx(), Offset(size.width * .74f, size.height * .70f))
        }
        Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Text(stringResource(zoneId), color = Electric, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(distance, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text(stringResource(R.string.approximate_distance), color = TextSecondary, fontSize = 12.sp)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Metric(stringResource(R.string.metric_signal), state.telemetry.rssi?.let { "$it dBm" } ?: "—", Modifier.weight(1f))
            Metric(stringResource(R.string.metric_zone), state.telemetry.nativeArea?.takeIf { it >= 0 }?.toString() ?: "—", Modifier.weight(1f))
            Metric(stringResource(R.string.metric_doors), stringResource(doorStatus(state.telemetry.doorState)), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = TextSecondary, fontSize = 12.sp, letterSpacing = .5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusPill(active: Boolean, text: String) {
    Row(
        Modifier.clip(CircleShape).background((if (active) Electric else TextSecondary).copy(.12f)).padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) Electric else TextSecondary))
        Spacer(Modifier.width(7.dp))
        Text(text, color = if (active) Electric else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun QuickAction(command: VehicleCommand, availability: ActionAvailability, modifier: Modifier, action: () -> Unit) {
    Surface(onClick = action, enabled = availability.enabled, modifier = modifier.heightIn(min = 96.dp), shape = RoundedCornerShape(20.dp), color = Panel) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(commandIcon(command), null, tint = if (availability.enabled) Electric else TextSecondary)
            Text(stringResource(commandLabel(command)), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2)
        }
    }
}

@Composable
private fun ControlsScreen(state: MainUiState, vm: MainViewModel, dangerous: (VehicleCommand) -> Unit, modifier: Modifier) {
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        shape = RoundedCornerShape(20.dp),
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
private fun SettingsScreen(
    state: MainUiState,
    vm: MainViewModel,
    keyAction: () -> Unit,
    backgroundSettings: () -> Unit,
    modifier: Modifier,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge) }
        item {
            SectionCard(R.string.access_mode, Icons.Rounded.Key) {
                KeylessMode.entries.forEach { mode ->
                    val (title, description) = modeText(mode)
                    Row(Modifier.fillMaxWidth().clickable { vm.configureKeyless(mode = mode) }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(state.mode == mode, { vm.configureKeyless(mode = mode) })
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
                            Text(stringResource(description), color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
        item {
            SectionCard(R.string.automation, Icons.Rounded.AutoAwesome) {
                SettingSwitch(R.string.auto_unlock, state.autoUnlock, state.mode == KeylessMode.AUTO_UNLOCK_LOCK && state.calibrated && state.manualUnlockVerified) { vm.configureKeyless(autoUnlock = it) }
                SettingSwitch(R.string.auto_lock, state.autoLock, state.mode == KeylessMode.AUTO_UNLOCK_LOCK && state.calibrated && state.manualLockVerified) { vm.configureKeyless(autoLock = it) }
                if (!state.calibrated || !state.manualUnlockVerified || !state.manualLockVerified) Text(stringResource(R.string.automation_prerequisites), color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        item { DistanceSettingsCard(state, vm) }
        item { CalibrationCard(state, vm) }
        item {
            SectionCard(R.string.safety_title, Icons.Rounded.Security) {
                SettingSwitch(R.string.experimental_commands, state.experimental, true, vm::setExperimental)
                Text(stringResource(R.string.experimental_warning), color = if (state.experimental) Critical else TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Text(stringResource(R.string.quick_controls_warning), color = Critical, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        item { LanguageSetting(state.language, vm::setLanguage) }
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
private fun DistanceSettingsCard(state: MainUiState, vm: MainViewModel) {
    val formatter = NumberFormat.getNumberInstance(LocalContext.current.resources.configuration.locales[0]).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }
    SectionCard(R.string.distance_thresholds_title, Icons.Rounded.SocialDistance) {
        Text(stringResource(R.string.distance_thresholds_description), color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Text(stringResource(R.string.unlock_distance, formatter.format(state.unlockDistanceMeters)), fontWeight = FontWeight.SemiBold)
        Slider(
            value = state.unlockDistanceMeters.toFloat(),
            onValueChange = { value -> vm.setProximityDistances(unlockMeters = value.toDouble().coerceAtMost(state.lockDistanceMeters - .5)) },
            valueRange = .5f..3f,
            steps = 4,
        )
        Text(stringResource(R.string.lock_distance, formatter.format(state.lockDistanceMeters)), fontWeight = FontWeight.SemiBold)
        Slider(
            value = state.lockDistanceMeters.toFloat(),
            onValueChange = { value -> vm.setProximityDistances(lockMeters = value.toDouble().coerceAtLeast(state.unlockDistanceMeters + .5)) },
            valueRange = 2f..10f,
            steps = 15,
        )
        Text(stringResource(R.string.distance_approximate_warning), color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun CalibrationCard(state: MainUiState, vm: MainViewModel) {
    SectionCard(R.string.calibration_title, Icons.Rounded.Radar) {
        Text(stringResource(R.string.calibration_description), color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Text(stringResource(R.string.current_signal, state.telemetry.rssi?.let { "$it dBm" } ?: "—"), color = Electric, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CalibrationStep(R.string.capture_near, state.nearSample, Modifier.weight(1f), vm::captureNear)
            CalibrationStep(R.string.capture_far, state.farSample, Modifier.weight(1f), vm::captureFar)
        }
        Button(vm::saveCalibration, enabled = state.nearSample != null && state.farSample != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.save_calibration))
        }
        when {
            state.commandResult?.resourceId == R.string.calibration_saved -> MessageCard(state.commandResult, false)
            state.error?.resourceId == R.string.error_calibration_save || state.error?.resourceId == R.string.error_near_signal ->
                MessageCard(state.error, true)
        }
    }
}

@Composable
private fun CalibrationStep(@StringRes title: Int, value: Int?, modifier: Modifier, action: () -> Unit) {
    OutlinedButton(action, modifier.heightIn(min = 70.dp), contentPadding = PaddingValues(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(title), fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(stringResource(R.string.captured_signal, value?.let { "$it dBm" } ?: "—"), color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectionCard(@StringRes title: Int, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).border(1.dp, StrokeColor, RoundedCornerShape(24.dp)).padding(18.dp),
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
private fun SettingSwitch(@StringRes title: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), Modifier.weight(1f), color = if (enabled) TextPrimary else TextSecondary)
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
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
private fun MessageCard(message: UiMessage, error: Boolean) {
    val text = stringResource(message.resourceId, *message.arguments.toTypedArray())
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background((if (error) Critical else Electric).copy(.1f)).padding(14.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, tint = if (error) Critical else Electric)
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (error) Critical else Electric, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@StringRes private fun connectionStatus(state: BleConnectionState) = when (state) {
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

@StringRes private fun commandLabel(command: VehicleCommand) = when (command) {
    VehicleCommand.LOCK -> R.string.action_lock; VehicleCommand.UNLOCK -> R.string.action_unlock; VehicleCommand.FIND_CAR -> R.string.action_find_car
    VehicleCommand.OPEN_TRUNK -> R.string.action_open_trunk; VehicleCommand.CLOSE_TRUNK -> R.string.action_close_trunk; VehicleCommand.CLOSE_WINDOWS -> R.string.action_close_windows
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

private fun modeText(mode: KeylessMode): Pair<Int, Int> = when (mode) {
    KeylessMode.OFF -> R.string.mode_off to R.string.mode_off_description
    KeylessMode.PASSIVE_ENTRY -> R.string.mode_passive to R.string.mode_passive_description
    KeylessMode.AUTO_UNLOCK_LOCK -> R.string.mode_auto to R.string.mode_auto_description
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
