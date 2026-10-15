package com.thinktough.tutticam.ui.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import com.thinktough.tutticam.protocol.LensKind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.thinktough.tutticam.engine.HostCoordinator
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.StabilityMode
import com.thinktough.tutticam.ui.Format
import com.thinktough.tutticam.ui.components.TcFloatingCard
import com.thinktough.tutticam.ui.title
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.AppModel
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.engine.CaptureAgent
import com.thinktough.tutticam.ui.components.Chrome
import com.thinktough.tutticam.ui.components.ChromePillButton
import com.thinktough.tutticam.ui.sheets.AccessoriesSheet
import com.thinktough.tutticam.ui.sheets.DevicesSheet
import com.thinktough.tutticam.ui.theme.TcColors
import com.thinktough.tutticam.ui.sheets.SettingsSheet

/** 显示拍摄页，按可用空间选手机布局或侧栏布局。 */
@Composable
fun CaptureScreen(model: AppModel, agent: CaptureAgent, onRequestCamera: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<Parameter?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(ScreenshotDemo.sheet == "settings") }
    var showDevices by rememberSaveable { mutableStateOf(ScreenshotDemo.sheet == "devices" || ScreenshotDemo.sheet == "accessories") }
    var showAccessories by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    // 记住多镜头时点选的画面，镜头组合变化后清掉。
    var selectedLens by remember { mutableStateOf<LensKind?>(null) }
    val config = agent.configuredLens
    LaunchedEffect(config) { if (config == null || !config.isDual || selectedLens !in config.lenses) selectedLens = null }
    val focusTarget = config?.let { if (it.isDual) selectedLens ?: it.primary else it.primary }
    val lensState = LensState(selectedLens, { selectedLens = it }, focusTarget)
    BackHandler { if (editing != null) editing = null else confirmLeave = true }
    val rotation = rememberDisplayRotation()
    LaunchedEffect(rotation) { model.camera.displayRotation = rotation }
    val hinge = rememberVerticalHinge()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val side = (maxWidth > maxHeight && maxWidth >= 600.dp) || maxWidth / maxHeight > 0.62f
        if (side) {
            SideLayout(model, agent, editing, { editing = it }, rotation, hinge, maxWidth, onRequestCamera,
                { showSettings = true }, { showDevices = true }, { showAccessories = true }, { confirmLeave = true }, lensState)
        } else {
            PhoneLayout(model, agent, editing, { editing = it }, rotation, onRequestCamera, { showSettings = true }, { showDevices = true },
                { showAccessories = true }, { confirmLeave = true }, lensState)
        }
    }

    if (confirmLeave) LeaveDialog(model) { confirmLeave = false }
    if (showSettings) SettingsSheet(model, agent) { showSettings = false }
    val host = model.host
    if (showDevices && host != null) DevicesSheet(host) { showDevices = false }
    if (showAccessories) AccessoriesSheet(agent.accessories) { showAccessories = false }
    // 提醒把外接相机和手机固定在同一支架上。
    if (agent.accessories.showMountReminder) {
        AlertDialog(
            onDismissRequest = { agent.accessories.showMountReminder = false },
            title = { Text(stringResource(R.string.acc_mount_title)) },
            text = { Text(stringResource(R.string.acc_mount_reminder)) },
            confirmButton = { TextButton({ agent.accessories.showMountReminder = false }) { Text(stringResource(R.string.got_it)) } },
            dismissButton = { TextButton({ agent.accessories.turnOffMountReminder() }) { Text(stringResource(R.string.dont_show_again)) } },
        )
    }
}

@Composable
private fun PhoneLayout(model: AppModel, agent: CaptureAgent, editing: Parameter?, onEdit: (Parameter?) -> Unit, rotation: Int,
                        onRequestCamera: () -> Unit, onSettings: () -> Unit, onDevices: () -> Unit, onAccessories: () -> Unit,
                        onLeave: () -> Unit, lens: LensState) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            IdentityCapsule(model, agent, onLeave, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 4.dp))
            ExposureBar(agent, model.host, editing, onEdit, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            // 让取景区延伸到底部，按钮悬浮在画面上。
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                Viewfinder(model, agent, editing, onEdit, rotation, onRequestCamera, Modifier.fillMaxSize(), Alignment.TopCenter,
                    lens.selected, lens.onSelect)
                // 让调节条浮在画面上沿，状态文字跟在下面。
                Column(Modifier.fillMaxWidth()) {
                    ParameterAdjuster(agent, model.host, editing, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                    StatusPanel(model, agent, overImage = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = 8.dp),
        ) {
            // 在对焦按钮上方展开竖向对焦标尺。
            if (editing == Parameter.Focus) lens.focusTarget?.let { FocusPanel(agent, it, Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 4.dp)) }
            // 在镜头胶囊上方居中放单镜头和多镜头切换。
            LensModeToggle(model, agent, Modifier.align(Alignment.CenterHorizontally))
            // 让镜头胶囊居中，对焦按钮固定在右边。
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                LensBar(model, agent, Modifier.align(Alignment.Center))
                FocusButton(agent, lens.focusTarget, editing, onEdit, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
            }
            ControlBar(model, agent, onSettings, onDevices, onAccessories, Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun SideLayout(model: AppModel, agent: CaptureAgent, editing: Parameter?, onEdit: (Parameter?) -> Unit, rotation: Int,
                       hinge: HingeBounds?, width: Dp, onRequestCamera: () -> Unit, onSettings: () -> Unit, onDevices: () -> Unit,
                       onAccessories: () -> Unit, onLeave: () -> Unit, lens: LensState) {
    val density = LocalDensity.current
    // 书本式折叠时把信息区放在折痕左侧，避开折痕。
    val hingeLeft = hinge?.let { with(density) { it.left.toDp() } }
    val hingeWidth = hinge?.let { with(density) { (it.right - it.left).toDp() } } ?: 0.dp
    val panelWidth = hingeLeft?.let { it - 24.dp } ?: (width * 0.36f).coerceIn(300.dp, 420.dp)
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
        Column(Modifier.width(panelWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IdentityCapsule(model, agent, onLeave, Modifier.fillMaxWidth())
            ExposureBar(agent, model.host, editing, onEdit, Modifier.fillMaxWidth())
            ParameterAdjuster(agent, model.host, editing, Modifier.fillMaxWidth())
            StatusPanel(model, agent, overImage = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            LensModeToggle(model, agent, Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LensBar(model, agent)
                FocusButton(agent, lens.focusTarget, editing, onEdit)
            }
        }
        Spacer(Modifier.width(if (hinge != null) 12.dp + hingeWidth + 12.dp else 12.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Viewfinder(model, agent, editing, onEdit, rotation, onRequestCamera, Modifier.fillMaxSize(), Alignment.Center,
                lens.selected, lens.onSelect)
            if (editing == Parameter.Focus) lens.focusTarget?.let { FocusPanel(agent, it, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        ControlBar(model, agent, onSettings, onDevices, onAccessories, Modifier.align(Alignment.CenterVertically), vertical = true)
    }
}

/** 显示取景画面，完整不裁切，每取到一组照片闪黑一下。 */
@Composable
private fun Viewfinder(model: AppModel, agent: CaptureAgent, editing: Parameter?, onEdit: (Parameter?) -> Unit, rotation: Int,
                       onRequestCamera: () -> Unit, modifier: Modifier, alignment: Alignment,
                       selectedLens: LensKind? = null, onSelectLens: (LensKind) -> Unit = {}) {
    val haptics = LocalHapticFeedback.current
    val config = agent.configuredLens
    val streamLenses = config?.let { model.camera.streamLenses(it) } ?: emptyList()
    val flash = remember { Animatable(0f) }
    LaunchedEffect(agent.shutterCount) {
        if (agent.shutterCount > 0) {
            flash.snapTo(0.85f)
            flash.animateTo(0f, tween(220))
        }
    }
    val description = stringResource(R.string.viewfinder)
    // 单击收起调节条并选中画面，双击恢复自动对焦，长按切换参数锁定。
    fun Modifier.gestures(kind: LensKind?) = this
        .semantics { contentDescription = description }
        .pointerInput(model.host, kind) {
            detectTapGestures(
                onTap = {
                    onEdit(null)
                    kind?.let(onSelectLens)
                },
                onDoubleTap = { agent.resetFocus() },
                onLongPress = {
                    model.host?.let {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it.toggleLockAll()
                    }
                },
            )
        }
    Box(modifier, contentAlignment = alignment) {
        when {
            agent.remoteOnly -> RemotePanel(model, Modifier.fillMaxSize().padding(bottom = remoteBottomInset))
            !agent.hasCameraPermission -> CameraNote(stringResource(R.string.error_camera_permission_short), stringResource(R.string.allow_camera), onRequestCamera)
            streamLenses.size == 2 -> {
                // 多镜头时左右分屏，点选的画面描边表示对焦对象。
                Box(Modifier.fillMaxSize().padding(bottom = remoteBottomInset), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (lens in streamLenses) {
                            val selected = lens.kind == selectedLens
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    key(lens.kind) {
                                        if (ScreenshotDemo.isOn) DemoPreview(lens, model.camera.lenses, Modifier.fillMaxWidth().gestures(lens.kind))
                                        else CameraPreview(model.camera, lens, (lens.sensorOrientation + rotation) % 180 == 90,
                                            Modifier.fillMaxWidth().gestures(lens.kind))
                                    }
                                    Box(Modifier.matchParentSize().alpha(flash.value).background(Color.Black))
                                    if (selected) Box(Modifier.matchParentSize().border(2.dp, Chrome.Accent, RoundedCornerShape(2.dp)))
                                }
                                Surface(shape = CircleShape, color = Chrome.Container) {
                                    Text(agent.lensLabels[lens.kind] ?: lens.kind.code, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (selected) Chrome.Accent else Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
            streamLenses.size == 1 -> Box(contentAlignment = Alignment.Center) {
                val lens = streamLenses.first()
                // 按镜头重建预览视图，避免 Surface 回调指向旧镜头。
                key(lens.kind) {
                    if (ScreenshotDemo.isOn) DemoPreview(lens, model.camera.lenses, Modifier.gestures(null))
                    else CameraPreview(model.camera, lens, (lens.sensorOrientation + rotation) % 180 == 90, Modifier.gestures(null))
                }
                Box(Modifier.matchParentSize().alpha(flash.value).background(Color.Black))
            }
            agent.cameraError != null -> CameraNote(agent.cameraError ?: "", null, null)
        }
    }
}

@Composable
private fun CameraNote(text: String, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, color = Chrome.Secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
        if (action != null && onAction != null) ChromePillButton(action, onAction, prominent = true)
    }
}

/** 在遥控模式中央显示连接数，以及静止判定和最小间隔两项设置。 */
@Composable
private fun RemotePanel(model: AppModel, modifier: Modifier) {
    val host = model.host ?: return
    val clients = host.devices.count { !it.isHost && it.connected }
    val accessories = host.agent.accessories.connectedCameras.size
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.SettingsRemote, contentDescription = null, tint = Chrome.Secondary, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.remote_mode), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (accessories > 0) stringResource(R.string.remote_connected_acc, clients, accessories) else stringResource(R.string.remote_connected, clients),
                color = Chrome.Secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            if (accessories > 0) {
                Text(stringResource(R.string.remote_mount_note), color = TcColors.MovingOrange, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            RemoteRhythm(host, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun RemoteRhythm(host: HostCoordinator, modifier: Modifier) {
    val context = LocalContext.current
    val s = host.settings
    var open by remember { mutableStateOf(false) }
    TcFloatingCard(modifier.fillMaxWidth(), radius = 16.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.section_stillness), color = Chrome.Secondary, fontSize = 13.sp)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                StabilityMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(s.stability == mode, { host.update(host.settings.copy(stability = mode)) },
                        SegmentedButtonDefaults.itemShape(i, StabilityMode.entries.size)) { Text(mode.title(), maxLines = 1) }
                }
            }
            Box {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { open = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.min_interval), color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(Format.seconds(context, s.minInterval), color = Chrome.Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        style = TextStyle(fontFeatureSettings = "tnum"))
                    Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = Chrome.Accent, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(open, { open = false }) {
                    for (choice in CaptureSettings.intervalChoices) {
                        DropdownMenuItem(text = { Text(Format.seconds(context, choice)) },
                            onClick = { open = false; host.update(host.settings.copy(minInterval = choice)) })
                    }
                }
            }
            if (s.stability == StabilityMode.Off) {
                Text(stringResource(R.string.stability_off_note), color = Chrome.Secondary, fontSize = 12.sp)
            }
        }
    }
}

/** 给出底部悬浮控件的大致高度，遥控面板据此居中。 */
private val remoteBottomInset = 120.dp

/** 打开本 App 的系统设置页，用于权限被拒后。 */
fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 跟踪屏幕旋转角，用 DisplayListener 才能感知 180° 翻转。 */
@Composable
private fun rememberDisplayRotation(): Int {
    val view = LocalView.current
    val context = LocalContext.current
    var rotation by remember { mutableIntStateOf(degrees(view.display?.rotation ?: Surface.ROTATION_0)) }
    DisposableEffect(view) {
        val manager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                view.display?.let { if (it.displayId == displayId) rotation = degrees(it.rotation) }
            }
        }
        manager.registerDisplayListener(listener, null)
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return rotation
}

private fun degrees(rotation: Int) = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

/** 保存多镜头时点选的画面和对焦对象。 */
class LensState(val selected: LensKind?, val onSelect: (LensKind) -> Unit, val focusTarget: LensKind?)

/** 记录折痕在窗口里的位置（像素）。 */
data class HingeBounds(val left: Int, val right: Int)

/** 返回书本式折叠的竖向折痕位置，没有折痕时为 null。 */
@Composable
private fun rememberVerticalHinge(): HingeBounds? {
    val activity = LocalActivity.current ?: return null
    var hinge by remember { mutableStateOf<HingeBounds?>(null) }
    LaunchedEffect(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>()
                .firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL && it.isSeparating }
            hinge = fold?.bounds?.let { HingeBounds(it.left, it.right) }
        }
    }
    return hinge
}
