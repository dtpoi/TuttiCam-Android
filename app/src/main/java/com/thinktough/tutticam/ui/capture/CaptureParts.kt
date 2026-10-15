package com.thinktough.tutticam.ui.capture

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery0Bar
import androidx.compose.material.icons.rounded.Battery1Bar
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatteryUnknown
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.LinkedCamera
import androidx.compose.material.icons.rounded.Panorama
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Check
import com.thinktough.tutticam.protocol.LensKind
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thinktough.tutticam.R
import com.thinktough.tutticam.accessory.AccessoryHub
import com.thinktough.tutticam.app.AppModel
import com.thinktough.tutticam.protocol.CameraBrand
import com.thinktough.tutticam.app.ClientLink
import com.thinktough.tutticam.app.Role
import com.thinktough.tutticam.engine.CaptureAgent
import com.thinktough.tutticam.engine.HostCoordinator
import com.thinktough.tutticam.engine.MonoClock
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.storage.CaptureStore
import com.thinktough.tutticam.ui.components.Chrome
import com.thinktough.tutticam.ui.components.ChromeIconButton
import com.thinktough.tutticam.ui.components.ChromePillButton
import com.thinktough.tutticam.ui.components.TcFloatingSurface
import com.thinktough.tutticam.ui.components.TcFloatingToolbar
import com.thinktough.tutticam.ui.theme.TcColors
import com.thinktough.tutticam.ui.title
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil

private val Tabular = TextStyle(fontFeatureSettings = "tnum")
private val TrimmedLines = androidx.compose.ui.text.style.LineHeightStyle(
    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)
private val CodeStyle = TextStyle(fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
    lineHeightStyle = TrimmedLines)
private val NameStyle = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, lineHeightStyle = TrimmedLines)

// MARK: - 顶部身份胶囊

/** 显示顶部胶囊：离开按钮、编号和设备名、场景、角色。 */
@Composable
fun IdentityCapsule(model: AppModel, agent: CaptureAgent, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    TcFloatingSurface(modifier) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ChromeIconButton(Icons.Rounded.Close, stringResource(R.string.leave_session), onLeave)
            // 收紧编号和设备名的行高，让整块与两侧按钮垂直居中。
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.Center) {
                Text(agent.deviceCode.text, color = Color.White, style = CodeStyle)
                Text(agent.deviceName, color = Chrome.Secondary, maxLines = 1, style = NameStyle, modifier = Modifier.padding(top = 2.dp))
            }
            SceneButton(model, agent)
            Surface(shape = CircleShape, color = Color(0xFF8AB4FF).copy(alpha = 0.25f), contentColor = Color.White,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp)) {
                Text(stringResource(if (model.role == Role.Host) R.string.role_host else R.string.role_client),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/** 在离开会话前请用户确认。 */
@Composable
fun LeaveDialog(model: AppModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leave_title)) },
        text = { Text(stringResource(if (model.role == Role.Host) R.string.leave_host_message else R.string.leave_client_message)) },
        confirmButton = { TextButton({ onDismiss(); model.leave() }) { Text(stringResource(R.string.leave)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 显示场景按钮：主机可新建、重命名、切换场景，从机只显示。 */
@Composable
private fun SceneButton(model: AppModel, agent: CaptureAgent) {
    val host = model.host
    if (host == null) {
        val folder = agent.sessionName ?: return
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = Chrome.Secondary)
            Text(CaptureStore.displayName(folder), fontSize = 13.sp, color = Color.White, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp).widthIn(max = 120.dp))
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val sceneDescription = stringResource(R.string.current_scene, host.scene.name)
    Box {
        Surface(onClick = { open = true }, shape = CircleShape, color = Chrome.Pressed, contentColor = Color.White,
            modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = sceneDescription }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(host.scene.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = 110.dp))
            }
        }
        DropdownMenu(open, { open = false }) {
            Text(sceneDescription, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            DropdownMenuItem(text = { Text(stringResource(R.string.new_scene, host.nextSceneName)) },
                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { open = false; host.newScene() })
            DropdownMenuItem(text = { Text(stringResource(R.string.rename_scene)) }, enabled = !host.running,
                leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; renaming = true })
            if (host.recentScenes.isNotEmpty()) {
                HorizontalDivider()
                for (scene in host.recentScenes.take(8)) {
                    DropdownMenuItem(text = { Text(scene.name) }, leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                        onClick = { open = false; host.switchScene(scene) })
                }
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(host.scene.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_scene)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true)
                    Text(stringResource(R.string.rename_scene_note), fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton({ renaming = false; problem = host.renameScene(name) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton({ renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    problem?.let {
        AlertDialog(onDismissRequest = { problem = null }, text = { Text(it) },
            confirmButton = { TextButton({ problem = null }) { Text(stringResource(R.string.ok)) } })
    }
}

// MARK: - 状态文字

/** 选择状态文字配色：画面偏亮时用黑字，否则白字，都用实色。 */
data class StatusTone(val onLight: Boolean) {
    val text: Color get() = if (onLight) Color.Black else Color.White
    val still: Color get() = if (onLight) TcColors.StillGreenOnLight else TcColors.StillGreen
    val moving: Color get() = if (onLight) TcColors.MovingOrangeOnLight else TcColors.MovingOrange
    val warning: Color get() = if (onLight) TcColors.WarningOnLight else TcColors.ManualYellow
    val shadow: Shadow get() = Shadow(if (onLight) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f),
        androidx.compose.ui.geometry.Offset(0f, 2f), 4f)
}

/** 显示静止状态、模糊量、镜头、组数、偏差和提醒。 */
@Composable
fun StatusPanel(model: AppModel, agent: CaptureAgent, overImage: Boolean, modifier: Modifier = Modifier) {
    val onLight = overImage && !agent.remoteOnly && agent.cameraReady && agent.statusOnLight
    val tone = StatusTone(onLight)
    val style = TextStyle(color = tone.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, shadow = tone.shadow,
        fontFeatureSettings = "tnum")
    val still = agent.snapshot.still
    Column(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLabel(if (still) Icons.Rounded.CheckCircle else Icons.Rounded.GraphicEq,
                stringResource(if (still) R.string.still else R.string.moving), style.copy(color = if (still) tone.still else tone.moving, fontSize = 14.sp))
            Text(stringResource(R.string.blur_px, "%.2f".format(Locale.US, agent.snapshot.blurPx)), style = style, maxLines = 1)
            Text(stringResource(R.string.mode_suffix, agent.settings.stability.title()), style = style, maxLines = 1)
            Spacer(Modifier.weight(1f))
            agent.configuredLens?.let { lens ->
                StatusLabel(Icons.Rounded.CameraAlt, agent.label(lens), style)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model.role == Role.Client) ClientLinkLabel(model, tone, style)
            if (agent.running) {
                Text(roundText(model, agent), style = style.copy(fontWeight = FontWeight.Medium))
                agent.lastOffsetMs?.let { Text(stringResource(R.string.offset_ms, "%+.1f".format(Locale.US, it)), style = style.copy(fontWeight = FontWeight.Medium)) }
            } else {
                Text(stringResource(if (model.role == Role.Host) R.string.tap_to_start else R.string.wait_host_start), style = style.copy(fontWeight = FontWeight.Medium))
            }
        }
        // 每台已连接的外接相机占一行。
        for (camera in agent.accessories.connectedCameras) {
            AccessoryLine(agent.accessories, camera, tone, style.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
        for (note in notes(model, agent)) {
            StatusLabel(Icons.Rounded.Info, note, style.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium))
        }
    }
}

/** 显示一台外接相机的电量和剩余张数；只有 DJI 和 GoPro 能报，没报上来时显示“—”。 */
@Composable
private fun AccessoryLine(hub: AccessoryHub, camera: AccessoryHub.Camera, tone: StatusTone, style: TextStyle) {
    val battery = camera.status?.battery
    val remain = camera.status?.remainPhotos
    val reports = camera.brand == CameraBrand.Dji || camera.brand == CameraBrand.GoPro
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (camera.name.contains("360")) Icons.Rounded.Panorama else Icons.Rounded.LinkedCamera, contentDescription = null,
                tint = style.color, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.acc_connected_line, hub.displayName(camera)), style = style, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false))
        }
        if (camera.sleeping) StatusLabel(Icons.Rounded.Bedtime, stringResource(R.string.acc_state_sleeping), style.copy(color = tone.moving))
        if (reports || battery != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(batteryIcon(battery), contentDescription = null, tint = style.color, modifier = Modifier.size(16.dp))
                Text(battery?.let { "$it%" } ?: "—", style = style, maxLines = 1)
            }
        }
        if (reports || remain != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Rounded.Photo, contentDescription = null, tint = style.color, modifier = Modifier.size(16.dp))
                Text(remain?.let { stringResource(R.string.acc_remaining, it) } ?: "—", style = style, maxLines = 1)
            }
        }
    }
}

/** 按电量选电池图标。 */
private fun batteryIcon(level: Int?): ImageVector = when {
    level == null -> Icons.Rounded.BatteryUnknown
    level >= 88 -> Icons.Rounded.BatteryFull
    level >= 63 -> Icons.Rounded.Battery5Bar
    level >= 38 -> Icons.Rounded.Battery3Bar
    level >= 13 -> Icons.Rounded.Battery1Bar
    else -> Icons.Rounded.Battery0Bar
}

@Composable
private fun StatusLabel(icon: ImageVector, text: String, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = style.color, modifier = Modifier.size(16.dp))
        Text(text, style = style, maxLines = 2)
    }
}

@Composable
private fun roundText(model: AppModel, agent: CaptureAgent): String {
    val host = model.host
    return when {
        host != null && agent.remoteOnly -> stringResource(R.string.round_n, host.round)
        host != null -> stringResource(R.string.round_n_local, host.round, agent.roundsCaptured)
        else -> stringResource(R.string.rounds_captured, agent.roundsCaptured)
    }
}

@Composable
private fun notes(model: AppModel, agent: CaptureAgent): List<String> = buildList {
    val host = model.host
    if (host != null && host.running) host.waitingReason?.let(::add)
    agent.lensNote?.let(::add)
    agent.cameraError?.let(::add)
    if (agent.settings.isLongExposure) add(stringResource(R.string.long_exposure_note))
    if (agent.rawUnavailable) add(stringResource(R.string.raw_unavailable_note, agent.settings.format.withoutRaw.title()))
    agent.lastProblem?.let(::add)
    // 主机再列出各从机的问题，如 “A02：保存失败：…”。
    if (host != null) for (device in host.devices) {
        if (device.isHost || !device.connected) continue
        listOfNotNull(device.status?.cameraError, device.status?.problem, host.peerProblems[device.index]).distinct()
            .forEach { add(stringResource(R.string.peer_problem, device.code.text, it)) }
    }
    addAll(agent.accessories.problems)
}

/** 显示从机与主机的连接和对时状态。 */
@Composable
private fun ClientLinkLabel(model: AppModel, tone: StatusTone, style: TextStyle) {
    when (model.clientLink) {
        ClientLink.Idle, ClientLink.Connecting -> StatusLabel(Icons.Rounded.SettingsInputAntenna, stringResource(R.string.connecting), style)
        ClientLink.Lost -> {
            var now by remember { mutableDoubleStateOf(MonoClock.now()) }
            LaunchedEffect(Unit) {
                while (true) {
                    now = MonoClock.now()
                    delay(500)
                }
            }
            val at = model.rejoinAt
            val text = if (at != null && !model.rejoining) stringResource(R.string.host_lost_retry_in, maxOf(1, ceil(at - now).toInt()))
            else stringResource(R.string.host_lost_retrying)
            StatusLabel(Icons.Rounded.WifiOff, text, style.copy(color = tone.moving))
        }
        ClientLink.Connected -> {
            val result = model.syncResult
            if (result != null) {
                val errorMs = result.error * 1000
                StatusLabel(if (errorMs > 5) Icons.Rounded.Warning else Icons.Rounded.Schedule,
                    stringResource(R.string.sync_error_ms, "%.1f".format(Locale.US, errorMs)),
                    if (errorMs > 5) style.copy(color = tone.warning) else style)
            } else {
                StatusLabel(Icons.Rounded.Schedule, stringResource(R.string.syncing), style)
            }
        }
    }
}

// MARK: - 镜头

/** 提供镜头选项和切换规则，与 iOS 相同。 */
private class LensChoices(private val model: AppModel, private val agent: CaptureAgent) {
    val duals: List<LensConfig> = agent.dualCombos
    /** 禁止从机在连上主机前换镜头。 */
    val locked: Boolean = model.role == Role.Client && model.clientLink != ClientLink.Connected

    fun canPair(a: LensKind, b: LensKind) = a != b && duals.any { it.lenses.toSet() == setOf(a, b) }
    fun partners(of: LensKind) = agent.lenses.filter { canPair(of, it) }
    val lensesWithPartner: List<LensKind> get() = agent.lenses.filter { partners(it).isNotEmpty() }

    /** 换主镜头，选到副镜头时主副对调。 */
    fun choosePrimary(pick: LensKind, current: LensConfig) {
        val second = current.secondary ?: return
        val partner = if (pick == second) current.primary else if (canPair(pick, second)) second else partners(pick).firstOrNull() ?: second
        model.chooseLens(LensConfig(pick, partner))
    }

    fun chooseSecondary(pick: LensKind, current: LensConfig) = model.chooseLens(LensConfig(current.primary, pick))

    /** 切换单镜头和多镜头，并尽量保留当前主镜头。 */
    fun switchMode(multi: Boolean) {
        val current = agent.configuredLens ?: return
        if (current.isDual == multi) return
        if (multi) {
            (duals.firstOrNull { it.primary == current.primary } ?: duals.firstOrNull())?.let(model::chooseLens)
        } else {
            model.chooseLens(LensConfig(current.primary))
        }
    }
}

/** 显示单镜头和多镜头切换，没有多镜头组合时隐藏。 */
@Composable
fun LensModeToggle(model: AppModel, agent: CaptureAgent, modifier: Modifier = Modifier) {
    if (agent.remoteOnly || agent.dualCombos.isEmpty()) return
    val choices = LensChoices(model, agent)
    val multi = agent.configuredLens?.isDual == true
    val description = stringResource(R.string.lens_mode)
    TcFloatingSurface(modifier.padding(bottom = 4.dp).semantics { contentDescription = description }) {
        // 外圈留 2dp，整体 32dp 高。
        Row(Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((isMulti, label) in listOf(false to R.string.single_lens, true to R.string.multi_lens)) {
                val selected = multi == isMulti
                Surface(
                    onClick = { choices.switchMode(isMulti) },
                    enabled = !choices.locked,
                    shape = CircleShape,
                    color = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (selected) Color.White else Chrome.Secondary,
                    modifier = Modifier.height(28.dp).semantics { this.selected = selected },
                ) {
                    Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 显示镜头按钮组：单镜头列出各镜头，多镜头显示主副两格。 */
@Composable
fun LensBar(model: AppModel, agent: CaptureAgent, modifier: Modifier = Modifier) {
    if (agent.remoteOnly || agent.lenses.isEmpty()) return
    val current = agent.configuredLens
    val choices = LensChoices(model, agent)
    // 让胶囊与对焦按钮同为 52dp 高。
    TcFloatingToolbar(modifier, contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (current != null && current.isDual && current.secondary != null) {
            val second = current.secondary!!
            LensSlot(agent, current.primary, choices.lensesWithPartner, !choices.locked) { choices.choosePrimary(it, current) }
            Text("+", color = Chrome.Secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 2.dp).semantics { contentDescription = "" })
            LensSlot(agent, second, choices.partners(current.primary), !choices.locked) { choices.chooseSecondary(it, current) }
        } else {
            for (kind in agent.lenses) {
                val selected = current?.primary == kind
                val label = agent.lensLabels[kind] ?: kind.code
                val description = LensConfig(kind).title(agent.lensLabels)
                Surface(
                    // 和设置比较，这样设置已回退时点当前镜头也会写回设置。
                    onClick = { if (agent.settings.lens(agent.deviceIndex) != LensConfig(kind)) model.chooseLens(LensConfig(kind)) },
                    enabled = !choices.locked,
                    shape = CircleShape,
                    color = if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (selected) TcColors.ManualYellow else Color.White,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = description },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontSize = if (label.length > 3) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = Tabular)
                    }
                }
            }
        }
    }
}

/** 显示多镜头的一个格子，点开选镜头。 */
@Composable
private fun LensSlot(agent: CaptureAgent, lens: LensKind, options: List<LensKind>, enabled: Boolean, onPick: (LensKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = agent.lensLabels[lens] ?: lens.code
    val description = stringResource(R.string.lens_tap_to_change, LensConfig(lens).title(agent.lensLabels))
    Box {
        Surface(onClick = { open = true }, enabled = enabled, shape = CircleShape, color = Color.White.copy(alpha = 0.18f),
            contentColor = TcColors.ManualYellow, modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 64.dp).semantics { contentDescription = description }) {
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = Tabular)
            }
        }
        DropdownMenu(open, { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(LensConfig(option).title(agent.lensLabels)) },
                    trailingIcon = { if (option == lens) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

/** 显示对焦按钮（AF、AF-L 或 MF），单击展开对焦标尺，双击恢复自动对焦。 */
@Composable
fun FocusButton(agent: CaptureAgent, lens: LensKind?, editing: Parameter?, onEdit: (Parameter?) -> Unit, modifier: Modifier = Modifier) {
    if (agent.remoteOnly || agent.lenses.isEmpty()) return
    val manual = lens != null && agent.manualFocus[lens] != null
    val open = editing == Parameter.Focus
    val title = if (manual) "MF" else if (agent.settings.lockFocus) "AF-L" else "AF"
    val description = stringResource(R.string.focus) + " " + title
    TcFloatingSurface(modifier) {
        Box(
            Modifier
                .size(52.dp)
                .combinedClickable(onClick = { onEdit(if (open) null else Parameter.Focus) },
                    onDoubleClick = { if (lens != null) agent.setManualFocus(null, lens) else agent.resetFocus() })
                .semantics { contentDescription = description; selected = open },
            contentAlignment = Alignment.Center,
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (manual) TcColors.ManualYellow else Color.White)
            if (open) {
                Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).size(16.dp, 2.dp), shape = CircleShape,
                    color = TcColors.ManualYellow) {}
            }
        }
    }
}

// MARK: - 底部工具栏

/** 显示底部按钮：主机为设置、快门、设备，从机为设置、拍摄状态、外接相机。 */
@Composable
fun ControlBar(model: AppModel, agent: CaptureAgent, onSettings: () -> Unit, onDevices: () -> Unit, onAccessories: () -> Unit,
               modifier: Modifier = Modifier, vertical: Boolean = false) {
    val host = model.host
    val settings: @Composable () -> Unit = {
        TcFloatingSurface {
            ChromeIconButton(Icons.Rounded.Tune, stringResource(R.string.capture_settings), onSettings, Modifier.size(56.dp))
        }
    }
    if (vertical) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            settings()
            if (host != null) {
                ShutterButton(host.running) { if (host.running) host.stop() else host.start() }
                DevicesButton(host, onDevices)
            } else {
                TcFloatingSurface { ClientCaptureStatus(model, agent, compact = true) }
                AccessoriesButton(agent, onAccessories)
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            settings()
            if (host != null) {
                ShutterButton(host.running) { if (host.running) host.stop() else host.start() }
                DevicesButton(host, onDevices)
            } else {
                TcFloatingSurface(Modifier.padding(horizontal = 12.dp)) { ClientCaptureStatus(model, agent, compact = false) }
                // 从机在右边放外接相机入口。
                AccessoriesButton(agent, onAccessories)
            }
        }
    }
}

@Composable
private fun DevicesButton(host: HostCoordinator, onClick: () -> Unit) {
    val connected = host.devices.count { it.connected }
    val problem = host.devices.any { it.state.isProblem }
    val description = stringResource(R.string.devices_connected, connected)
    TcFloatingSurface(Modifier.semantics { contentDescription = description }) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent, contentColor = Color.White,
        modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Smartphone, contentDescription = null, modifier = Modifier.size(22.dp))
                Text("$connected", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
            }
            if (problem) {
                Icon(Icons.Rounded.Warning, contentDescription = stringResource(R.string.device_needs_attention),
                    tint = TcColors.MovingOrange, modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(14.dp))
            }
        }
    }
    }
}

/** 显示从机的外接相机入口，有已连接相机时带绿色勾。 */
@Composable
private fun AccessoriesButton(agent: CaptureAgent, onClick: () -> Unit) {
    TcFloatingSurface {
        Box {
            ChromeIconButton(Icons.Rounded.LinkedCamera, stringResource(R.string.external_cameras), onClick, Modifier.size(56.dp))
            if (agent.accessories.hasConnected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = TcColors.StillGreen,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(14.dp))
            }
        }
    }
}

/** 显示快门按钮：白圆表示开始，红方块表示停止。 */
@Composable
fun ShutterButton(running: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (running) R.string.stop_capture else R.string.start_capture)
    TcFloatingSurface(Modifier.semantics { contentDescription = description }) {
        Surface(onClick = onClick, shape = CircleShape, color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
            modifier = Modifier.padding(5.dp).size(68.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (running) {
                    Surface(Modifier.size(28.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), color = TcColors.ProblemRed) {}
                } else {
                    Surface(Modifier.size(54.dp), shape = CircleShape, color = Color.White) {}
                }
            }
        }
    }
}

/** 在从机的快门位置显示拍摄状态，断开时显示“重新连接”。 */
@Composable
private fun ClientCaptureStatus(model: AppModel, agent: CaptureAgent, compact: Boolean) {
    if (model.clientLink == ClientLink.Lost) {
        ChromePillButton(stringResource(if (model.rejoining) R.string.reconnecting else R.string.reconnect), { model.reconnectNow() },
            icon = Icons.Rounded.Refresh, enabled = !model.rejoining, prominent = true)
    } else if (compact) {
        Icon(if (agent.running) Icons.Rounded.RadioButtonChecked else Icons.Rounded.HourglassEmpty,
            contentDescription = stringResource(if (agent.running) R.string.capturing_by_host else R.string.wait_host_start),
            tint = if (agent.running) TcColors.ProblemRed else Color.White, modifier = Modifier.padding(12.dp).size(24.dp))
    } else {
        Row(Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (agent.running) Icons.Rounded.RadioButtonChecked else Icons.Rounded.HourglassEmpty, contentDescription = null,
                tint = if (agent.running) TcColors.ProblemRed else Color.White, modifier = Modifier.size(20.dp))
            Text(stringResource(if (agent.running) R.string.capturing_by_host else R.string.wait_host_start),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
