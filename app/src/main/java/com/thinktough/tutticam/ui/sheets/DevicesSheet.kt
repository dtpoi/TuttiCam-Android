package com.thinktough.tutticam.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LinkedCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.accessory.brandTitle
import com.thinktough.tutticam.protocol.AccessoryBrief
import com.thinktough.tutticam.protocol.CameraBrand
import com.thinktough.tutticam.ui.components.SheetPadding
import com.thinktough.tutticam.ui.components.SheetSectionTitle
import com.thinktough.tutticam.ui.components.TcSheet
import com.thinktough.tutticam.ui.components.sheetCardColors
import com.thinktough.tutticam.engine.DeviceEntry
import com.thinktough.tutticam.engine.DeviceState
import com.thinktough.tutticam.engine.HostCoordinator
import com.thinktough.tutticam.engine.RoundSummary
import com.thinktough.tutticam.net.LanLink
import com.thinktough.tutticam.protocol.DeviceCode
import com.thinktough.tutticam.ui.Format
import com.thinktough.tutticam.ui.theme.TcColors
import com.thinktough.tutticam.ui.title
import java.util.Locale

/** 显示设备页：各设备卡片、外接相机入口、最近几组、已拍摄媒体入口和保存位置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(host: HostCoordinator, onDismiss: () -> Unit) {
    var editingCode by remember { mutableStateOf<DeviceEntry?>(null) }
    var editingLens by remember { mutableStateOf<DeviceEntry?>(null) }
    var showAccessories by remember { mutableStateOf(ScreenshotDemo.sheet == "accessories") }
    var showMedia by remember { mutableStateOf(false) }
    val accessories = host.agent.accessories
    TcSheet(onDismiss) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SheetPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.devices_title, host.devices.size, LanLink.MAX_PEERS), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.devices_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            items(host.devices, key = { "device-${it.index}" }) { device ->
                DeviceCard(host, device, onCode = { editingCode = device }, onLens = { editingLens = device })
            }
            // 放外接相机入口，点开连接页。
            item(key = "accessories") {
                ElevatedCard(onClick = { showAccessories = true }, modifier = Modifier.fillMaxWidth(), colors = sheetCardColors()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.LinkedCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.acc_device_row), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        val count = accessories.connectedCameras.size
                        Text(if (count > 0) stringResource(R.string.acc_device_connected, count) else stringResource(R.string.acc_device_none),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.acc_device_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            item {
                SheetSectionTitle(stringResource(R.string.recent_rounds), Modifier.padding(top = 12.dp))
                if (host.log.isEmpty()) {
                    Text(stringResource(R.string.no_rounds_yet), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            // 不按组号做键，因为换场景后组号会重复。
            items(host.log.take(20)) { RoundRow(it) }
            // 放已拍摄媒体入口，点开按场景浏览本机照片。
            item(key = "media") {
                ElevatedCard(onClick = { showMedia = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = sheetCardColors()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.media_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.media_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            // 在最底下放保存位置，路径是超链接，点开系统“文件”App。
            item(key = "folder") { SavedFolderLink(R.string.saved_folder) }
        }
    }
    editingCode?.let { device -> CodeDialog(host, device) { editingCode = null } }
    editingLens?.let { device -> LensDialog(host, device) { editingLens = null } }
    // 把连接页叠在设备页上面。
    if (showAccessories) AccessoriesSheet(accessories) { showAccessories = false }
    if (showMedia) MediaSheet(host.agent.store.folder.takeIf { host.agent.running }) { showMedia = false }
}


@Composable
private fun DeviceCard(host: HostCoordinator, device: DeviceEntry, onCode: () -> Unit, onLens: () -> Unit) {
    val context = LocalContext.current
    val state = device.state
    val stateColor = when {
        state.isProblem -> TcColors.ProblemRed
        state == DeviceState.Ready -> TcColors.StillGreen
        state == DeviceState.Moving -> TcColors.MovingOrange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val badSync = (device.errorMs ?: 0.0) > 5
    ElevatedCard(Modifier.fillMaxWidth(), colors = sheetCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = onCode, shape = RoundedCornerShape(10.dp),
                    color = if (badSync) TcColors.ManualYellow.copy(alpha = 0.3f) else MaterialTheme.colorScheme.secondaryContainer) {
                    Text(device.code.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (device.isHost) stringResource(R.string.this_device) else device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    device.info?.model?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(stringResource(state.title), color = stateColor, fontWeight = FontWeight.SemiBold)
            }
            if (device.isHost && host.agent.remoteOnly) {
                Text(stringResource(R.string.remote_mode), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (device.isHost) {
                    Meta(Icons.Rounded.Schedule, stringResource(R.string.role_host))
                } else device.errorMs?.let { error ->
                    val rtt = device.rttMs?.let { " · " + stringResource(R.string.rtt_ms, "%.1f".format(Locale.US, it)) } ?: ""
                    Meta(Icons.Rounded.Schedule, "±%.1f ms".format(Locale.US, error) + rtt, if (error > 5) TcColors.WarningOnLight else null)
                }
                device.status?.let { status ->
                    if (status.battery >= 0) Meta(Icons.Rounded.BatteryStd, "${(status.battery * 100).toInt()}%")
                    if (status.freeBytes > 0) Meta(Icons.Rounded.SdStorage, Format.bytes(context, status.freeBytes))
                    Text(stringResource(R.string.blur_px, "%.2f".format(Locale.US, status.blurPx)), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onLens).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp))
                Text(stringResource(R.string.lens_value, host.settings.lens(device.index).title(labelsFor(host, device))), style = MaterialTheme.typography.bodyMedium)
                if (device.index in host.settings.lensOverrides) {
                    Text(stringResource(R.string.lens_individual), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            // 每台外接相机一行：名字、编号、品牌、电量、剩余张数、状态。
            for (accessory in device.status?.accessories ?: emptyList()) {
                val attention = !accessory.connected || accessory.tooHot || accessory.sleeping
                Meta(Icons.Rounded.LinkedCamera, accessoryText(accessory),
                    if (attention) sheetTone(TcColors.MovingOrange, TcColors.MovingOrangeOnLight) else null)
            }
            device.status?.cameraError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TcColors.ProblemRed) }
            if (!device.connected && !device.isHost) {
                TextButton({ host.removeOffline(device) }) { Text(stringResource(R.string.remove_device)) }
            }
        }
    }
}

@Composable
private fun accessoryText(a: AccessoryBrief): String {
    val context = LocalContext.current
    val code = a.code.ifEmpty { stringResource(R.string.acc_number, a.number) }
    return buildList {
        add(stringResource(R.string.acc_label, a.name, code))
        CameraBrand.entries.firstOrNull { it.raw == a.brand }?.let { add(brandTitle(context, it)) }
        if (!a.connected) add(stringResource(R.string.acc_device_none))
        a.battery?.let { add("$it%") }
        remainingText(a.remainPhotos, a.remainMinutes)?.let(::add)
        if (a.capturing) add(stringResource(R.string.acc_capturing))
        if (a.tooHot) add(stringResource(R.string.acc_hot_short))
        if (a.sleeping) add(stringResource(R.string.acc_state_sleeping))
    }.joinToString(" · ")
}

@Composable
private fun Meta(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RoundRow(summary: RoundSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.round_n, summary.round), fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.round_summary, summary.participants, summary.photos), style = MaterialTheme.typography.bodySmall)
            val extras = buildList {
                summary.maxOffsetMs?.let { add(stringResource(R.string.max_offset_ms, "%.1f".format(Locale.US, it))) }
                if (summary.moving > 0) add(stringResource(R.string.n_moving, summary.moving))
                if (summary.missed > 0) add(stringResource(R.string.n_missed, summary.missed))
            }
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = if (summary.moving + summary.missed > 0) TcColors.MovingOrange else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        summary.kept?.let {
            Text(stringResource(if (it) R.string.kept else R.string.discarded),
                color = if (it) TcColors.StillGreen else TcColors.ProblemRed, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 修改设备编号，重号时提示。 */
@Composable
private fun CodeDialog(host: HostCoordinator, device: DeviceEntry, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf(device.code.group) }
    var number by remember { mutableIntStateOf(device.code.number) }
    var problem by remember { mutableStateOf<String?>(null) }
    val code = DeviceCode(group, number)
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in DeviceCode.groups.take(8)) FilterChip(g == group, { group = g; number = host.nextCode(g, device.index).number }, { Text(g) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ if (number > 1) number-- }) { Text("−") }
                    Text(stringResource(R.string.code_number, "%02d".format(number)), fontFamily = FontFamily.Monospace)
                    TextButton({ if (number < 99) number++ }) { Text("+") }
                }
                Text(stringResource(R.string.new_code_note, code.text), style = MaterialTheme.typography.bodySmall)
                problem?.let { Text(it, color = TcColors.ProblemRed, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton({
                val other = host.setCode(code, device.index)
                if (other == null) onDismiss() else problem = resources.getString(R.string.code_taken, code.text, other.name)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 为设备单独指定镜头，或跟随全局设置。 */
@Composable
private fun LensDialog(host: HostCoordinator, device: DeviceEntry, onDismiss: () -> Unit) {
    val options = device.info?.lensOptions ?: emptyList()
    val current = host.settings.lensOverrides[device.index]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lens_for, device.code.text)) },
        text = {
            Column {
                LensOption(stringResource(R.string.follow_global, host.settings.lens.title(labelsFor(host, device))), current == null) {
                    host.setLens(null, device.index); onDismiss()
                }
                for (option in options) {
                    LensOption(option.title(labelsFor(host, device)), current == option) { host.setLens(option, device.index); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun LensOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected, onClick)
        Text(title, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 返回镜头倍率文字，本机用实际倍率，其他设备为空。 */
private fun labelsFor(host: HostCoordinator, device: DeviceEntry): Map<com.thinktough.tutticam.protocol.LensKind, String> =
    if (device.isHost) host.agent.lensLabels else emptyMap()
