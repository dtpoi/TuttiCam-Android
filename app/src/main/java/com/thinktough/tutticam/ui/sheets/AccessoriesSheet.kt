package com.thinktough.tutticam.ui.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thinktough.tutticam.R
import com.thinktough.tutticam.accessory.AccessoryDiscovery
import com.thinktough.tutticam.accessory.AccessoryHub
import com.thinktough.tutticam.accessory.AccessoryLink
import com.thinktough.tutticam.accessory.AccessoryPhase
import com.thinktough.tutticam.accessory.brandGenericName
import com.thinktough.tutticam.accessory.brandTitle
import com.thinktough.tutticam.accessory.kindTitle
import com.thinktough.tutticam.accessory.pairingSteps
import com.thinktough.tutticam.protocol.CameraBrand
import com.thinktough.tutticam.protocol.DeviceCode
import com.thinktough.tutticam.protocol.accessory.verified
import com.thinktough.tutticam.ui.components.SheetPadding
import com.thinktough.tutticam.ui.components.SheetSectionTitle
import com.thinktough.tutticam.ui.components.TcSheet
import com.thinktough.tutticam.ui.components.sheetCardColors
import com.thinktough.tutticam.ui.theme.TcColors
import java.util.Locale

/** 显示外接相机页：查找、配对、改名、断开相机；打开时扫描，关闭时停止，缺权限时先申请。 */
@Composable
fun AccessoriesSheet(hub: AccessoryHub, onDismiss: () -> Unit) {
    var permissionTick by remember { mutableIntStateOf(0) }
    val granted = remember(permissionTick) { hub.hasPermission }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }
    LaunchedEffect(granted) { if (granted) hub.startScan() else permission.launch(AccessoryLink.PERMISSIONS) }
    DisposableEffect(Unit) { onDispose { hub.stopScan() } }
    var editing by remember { mutableStateOf<AccessoryHub.Camera?>(null) }
    var expanded by remember { mutableStateOf<CameraBrand?>(null) }
    val context = LocalContext.current

    TcSheet(onDismiss) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = SheetPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "title") {
                Text(stringResource(R.string.external_cameras), style = MaterialTheme.typography.titleLarge)
            }
            item(key = "mount") {
                Notice(Icons.Rounded.Warning, stringResource(R.string.acc_mount_reminder), sheetTone(TcColors.MovingOrange, TcColors.MovingOrangeOnLight))
            }
            if (!granted) item(key = "permission") {
                ElevatedCard(Modifier.fillMaxWidth(), colors = sheetCardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.acc_permission_note), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton({ permission.launch(AccessoryLink.PERMISSIONS) }) { Text(stringResource(R.string.allow)) }
                    }
                }
            }
            hub.bluetoothProblem?.let { problem ->
                item(key = "bluetooth") { Notice(Icons.Rounded.BluetoothDisabled, problem, TcColors.ProblemRed) }
            }

            item(key = "mine") { SheetSectionTitle(stringResource(R.string.acc_my_cameras), Modifier.padding(top = 12.dp)) }
            // 没有相机时把空状态和说明放进卡片；有相机时每台各是一张卡片，说明放在下面。
            if (hub.cameras.isEmpty()) {
                item(key = "mine-empty") {
                    SectionCard {
                        Muted(stringResource(R.string.acc_none_yet), Modifier.padding(horizontal = 16.dp))
                        Footnote(stringResource(R.string.acc_my_cameras_note), Modifier.padding(horizontal = 16.dp))
                    }
                }
            } else {
                items(hub.cameras, key = { "camera-${it.id}" }) { camera -> CameraCard(hub, camera) { editing = camera } }
                item(key = "mine-note") { Footnote(stringResource(R.string.acc_my_cameras_note)) }
            }

            item(key = "nearby") {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetSectionTitle(stringResource(R.string.acc_nearby))
                    if (hub.scanning && granted) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
            // 把找到的相机（或“正在查找”）和说明放进同一张卡片；列表已由蓝牙层排除正在连接的相机。
            item(key = "nearby-list") {
                val nearby = hub.discovered
                SectionCard {
                    if (nearby.isEmpty()) {
                        Muted(stringResource(if (hub.scanning && granted) R.string.acc_searching else R.string.acc_none_found),
                            Modifier.padding(horizontal = 16.dp))
                    }
                    for (found in nearby) key(found.address) { FoundRow(found) { hub.connect(found.address) } }
                    Footnote(stringResource(R.string.acc_nearby_note), Modifier.padding(horizontal = 16.dp))
                }
            }

            item(key = "brands") { SheetSectionTitle(stringResource(R.string.acc_how_to_pair), Modifier.padding(top = 12.dp)) }
            items(CameraBrand.entries, key = { "brand-${it.raw}" }) { brand ->
                val open = expanded == brand
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else brand }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(brandTitle(context, brand), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(stringResource(if (brand.verified) R.string.acc_verified else R.string.acc_untested),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (brand.verified) sheetTone(TcColors.StillGreen, TcColors.StillGreenOnLight) else MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(open) {
                        Text(pairingSteps(context, brand), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            item(key = "sources") { Footnote(stringResource(R.string.acc_sources_note)) }
            item(key = "insta360") {
                Notice(Icons.Rounded.Info, stringResource(R.string.acc_insta360), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    editing?.let { camera -> LabelDialog(hub, hub.camera(camera.id) ?: camera) { editing = null } }
}

@Composable
private fun CameraCard(hub: AccessoryHub, camera: AccessoryHub.Camera, onEdit: () -> Unit) {
    val context = LocalContext.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    ElevatedCard(Modifier.fillMaxWidth(), colors = sheetCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 点编号可修改名字和编号。
                Surface(onClick = onEdit, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(camera.code.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(hub.displayName(camera), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val subtitle = listOfNotNull(brandTitle(context, camera.brand), kindTitle(context, camera.kind),
                        if (!camera.brand.verified) stringResource(R.string.acc_untested) else null).joinToString(" · ")
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 2)
                }
                PhaseLabel(camera)
            }
            when (val phase = camera.phase) {
                is AccessoryPhase.Confirming -> phase.code?.let { PairingCode(it) }
                    ?: Text(stringResource(R.string.acc_confirm_on_phone), style = MaterialTheme.typography.bodySmall, color = secondary)
                is AccessoryPhase.Failed -> Text(phase.reason, style = MaterialTheme.typography.bodySmall, color = TcColors.ProblemRed)
                else -> Unit
            }
            if (camera.sleeping) Text(stringResource(R.string.acc_wake), style = MaterialTheme.typography.bodySmall, color = secondary)
            else if (camera.connected && camera.status?.video == true) {
                Text(stringResource(R.string.acc_video_note), style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            if (camera.connected) StatusLine(hub, camera)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 连接失败时提供重新连接或忘记后重扫。
                if (camera.failed) {
                    FilledTonalButton({ hub.retry(camera.id) }) { Text(stringResource(R.string.reconnect)) }
                    OutlinedButton({ hub.forgetAndRescan(camera.id) }) { Text(stringResource(R.string.acc_forget_rescan)) }
                } else {
                    // DJI 相机可以在这里休眠，省电；手机发不出能叫醒它的蓝牙广播，要按相机上任意按键唤醒。
                    if (camera.brand == CameraBrand.Dji && camera.connected && !camera.sleeping) {
                        OutlinedButton({ hub.sleep(camera.id) }) {
                            Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.acc_sleep), Modifier.padding(start = 8.dp))
                        }
                    }
                    TextButton({ hub.forget(camera.id) }) { Text(stringResource(R.string.acc_forget), color = TcColors.ProblemRed) }
                }
            }
        }
    }
}

@Composable
private fun PhaseLabel(camera: AccessoryHub.Camera) {
    val orange = sheetTone(TcColors.MovingOrange, TcColors.MovingOrangeOnLight)
    val (icon, text, color) = when (camera.phase) {
        AccessoryPhase.Connecting -> Triple(Icons.Rounded.Sensors, R.string.acc_state_connecting, orange)
        is AccessoryPhase.Confirming -> Triple(Icons.Rounded.TouchApp, R.string.acc_state_confirm, orange)
        is AccessoryPhase.Connected -> if (camera.sleeping) Triple(Icons.Rounded.Bedtime, R.string.acc_state_sleeping, orange)
            else Triple(Icons.Rounded.CheckCircle, R.string.acc_state_connected, sheetTone(TcColors.StillGreen, TcColors.StillGreenOnLight))
        AccessoryPhase.Rejected -> Triple(Icons.Rounded.Error, R.string.acc_state_rejected, TcColors.ProblemRed)
        is AccessoryPhase.Failed -> Triple(Icons.Rounded.Warning, R.string.acc_state_failed, TcColors.ProblemRed)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(stringResource(text), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** 显示已连接相机的电量、剩余张数、模式、往返时间和温度提醒。 */
@Composable
private fun StatusLine(hub: AccessoryHub, camera: AccessoryHub.Camera) {
    val status = camera.status
    val parts = buildList {
        status?.battery?.let { add("$it%") }
        remainingText(status?.remainPhotos, status?.remainMinutes)?.let(::add)
        hub.detail(status)?.takeIf { it.isNotEmpty() }?.let(::add)
        camera.lastRttMs?.let { add(stringResource(R.string.acc_rtt, "%.0f".format(Locale.US, it))) }
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    if (status != null && (status.warm || status.tooHot)) {
        Notice(Icons.Rounded.Thermostat, stringResource(if (status.tooHot) R.string.acc_hot else R.string.acc_warm),
            if (status.tooHot) TcColors.ProblemRed else sheetTone(TcColors.MovingOrange, TcColors.MovingOrangeOnLight))
    }
}

/** 显示 4 位配对码，每位一个方块，方便逐位核对。 */
@Composable
private fun PairingCode(code: Int) {
    val digits = "%04d".format(Locale.US, code)
    val description = stringResource(R.string.acc_pairing_code, digits.toList().joinToString(" "))
    Column(Modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (digit in digits) {
                // 用固定深灰底白字，深浅模式下都清楚。
                Surface(Modifier.size(width = 44.dp, height = 52.dp), shape = RoundedCornerShape(10.dp), color = Color(0xFF525252)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(digit.toString(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            style = TextStyle(fontFeatureSettings = "tnum"))
                    }
                }
            }
        }
        Text(stringResource(R.string.acc_check_code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FoundRow(found: AccessoryDiscovery, onConnect: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onConnect).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.CameraAlt, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(found.name.ifEmpty { brandGenericName(context, found.kind.brand) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(brandTitle(context, found.kind.brand) + " · " + kindTitle(context, found.kind), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.acc_signal, found.rssi), style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 修改外接相机的名字和编号，选组后自动取空号。 */
@Composable
private fun LabelDialog(hub: AccessoryHub, camera: AccessoryHub.Camera, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(camera.customName ?: "") }
    var group by remember { mutableStateOf(camera.code.group) }
    var number by remember { mutableIntStateOf(camera.code.number) }
    var problem by remember { mutableStateOf<String?>(null) }
    val code = DeviceCode(group, number)
    val resources = LocalResources.current
    val defaultName = hub.defaultName(camera)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(defaultName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.acc_name)) },
                    placeholder = { Text(defaultName) },
                    supportingText = { Text(stringResource(R.string.acc_name_placeholder, defaultName)) })
                Text(stringResource(R.string.acc_group), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in DeviceCode.groups) FilterChip(g == group, { group = g; number = hub.nextCode(g, camera.id).number }, { Text(g) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ if (number > 1) number-- }) { Text("−") }
                    Text(stringResource(R.string.code_number, "%02d".format(Locale.US, number)), fontFamily = FontFamily.Monospace)
                    TextButton({ if (number < 99) number++ }) { Text("+") }
                }
                Text(stringResource(R.string.acc_new_code_note, code.text), style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = TcColors.ProblemRed, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton({
                val other = hub.relabel(camera.id, name, code)
                if (other == null) onDismiss() else problem = resources.getString(R.string.code_taken, code.text, other)
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
private fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** 把一节的内容放进和设备卡片同色的卡片里，内容自己留左右边距，点按的行能铺满卡片宽度。 */
@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), colors = sheetCardColors()) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** 在浅色弹层上换用深一档的颜色。 */
@Composable
internal fun sheetTone(onDark: Color, onLight: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) onDark else onLight


/** 给出剩余量：拍照模式是剩余张数，录像模式是还能录多久，如“可录 2 小时 5 分”。 */
@Composable
internal fun remainingText(photos: Int?, minutes: Int?): String? = when {
    minutes != null && minutes >= 60 -> stringResource(R.string.acc_remaining_hours, minutes / 60, minutes % 60)
    minutes != null -> stringResource(R.string.acc_remaining_minutes, minutes)
    photos != null -> stringResource(R.string.acc_remaining, photos)
    else -> null
}
