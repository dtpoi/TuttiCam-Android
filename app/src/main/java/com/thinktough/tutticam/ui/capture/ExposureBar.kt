package com.thinktough.tutticam.ui.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbIncandescent
import androidx.compose.material.icons.rounded.WbIridescent
import androidx.compose.material.icons.rounded.WbShade
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thinktough.tutticam.R
import com.thinktough.tutticam.engine.CaptureAgent
import com.thinktough.tutticam.engine.HostCoordinator
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.LensKind
import com.thinktough.tutticam.ui.Format
import com.thinktough.tutticam.ui.components.Chrome
import com.thinktough.tutticam.ui.components.TcFloatingCard
import com.thinktough.tutticam.ui.components.TickRuler
import com.thinktough.tutticam.ui.theme.TcColors
import java.util.Locale
import kotlin.math.roundToInt

/** 列出可展开调节的参数，对焦由镜头旁的按钮展开。 */
enum class Parameter { Shutter, Aperture, Iso, WhiteBalance, Tint, Bias, Focus }

private val Tabular = TextStyle(fontFeatureSettings = "tnum")
/** 让行高按字号居中，中英文混排时每格高度一致。 */
private val CenteredLines = androidx.compose.ui.text.style.LineHeightStyle(
    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)
private val TitleStyle = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, lineHeightStyle = CenteredLines)
private val ValueStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum",
    lineHeightStyle = CenteredLines)
private val AutoStyle = TextStyle(fontSize = 10.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, lineHeightStyle = CenteredLines)

/**
 * 显示顶部参数条：自动值带“A”，手动值变黄，锁定后变蓝。
 * 单击展开调节条，双击恢复自动；只有主机能修改。
 */
@Composable
fun ExposureBar(agent: CaptureAgent, host: HostCoordinator?, editing: Parameter?, onEdit: (Parameter?) -> Unit,
                modifier: Modifier = Modifier) {
    val s = agent.settings
    val live = agent.live
    fun edit(change: (CaptureSettings) -> CaptureSettings) {
        host?.let { it.update(change(it.settings)) }
    }
    TcFloatingCard(modifier) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell(Parameter.Shutter, stringResource(R.string.param_shutter),
                if (s.manualShutter) Format.shutterShort(s.shutterSeconds) else live?.let { Format.shutterShort(it.shutter) } ?: "—",
                auto = !s.manualShutter, s, editing, onEdit) { edit { it.copy(manualShutter = false) } }
            Cell(Parameter.Aperture, stringResource(R.string.param_aperture),
                live?.takeIf { it.aperture > 0 }?.let { "f/%.2g".format(Locale.US, it.aperture) } ?: "—",
                auto = false, s, editing, onEdit, onReset = null)
            Cell(Parameter.Iso, "ISO", (if (s.manualISO) s.iso else live?.iso)?.let(Format::rounded) ?: "—",
                auto = !s.manualISO, s, editing, onEdit) { edit { it.copy(manualISO = false) } }
            Cell(Parameter.WhiteBalance, stringResource(R.string.param_white_balance),
                (if (s.lockWhiteBalance) s.whiteBalanceKelvin else live?.kelvin)?.let { "${((it / 10).roundToInt() * 10)}K" } ?: "—",
                auto = !s.lockWhiteBalance, s, editing, onEdit) { edit { it.copy(lockWhiteBalance = false) } }
            Cell(Parameter.Tint, stringResource(R.string.param_tint),
                (if (s.lockWhiteBalance) s.whiteBalanceTint else live?.tint)?.let(Format::signed) ?: "—",
                auto = !s.lockWhiteBalance, s, editing, onEdit) { edit { if (it.lockWhiteBalance) it.copy(whiteBalanceTint = 0f) else it } }
            Cell(Parameter.Bias, stringResource(R.string.param_bias), Format.bias(s.exposureBias), auto = false, s, editing, onEdit,
                dimmed = s.lockExposure) { edit { it.copy(manualShutter = false, manualISO = false, exposureBias = 0f) } }
            LockButton(s.lockAll, enabled = host != null) { host?.toggleLockAll() }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    parameter: Parameter, title: String, value: String, auto: Boolean, settings: CaptureSettings,
    editing: Parameter?, onEdit: (Parameter?) -> Unit, dimmed: Boolean = false, onReset: (() -> Unit)?,
) {
    // 按内容分配格宽，白平衡和 ISO 最宽。
    val weight = when (parameter) {
        Parameter.WhiteBalance -> 1.3f
        Parameter.Iso -> 1.15f
        Parameter.Aperture, Parameter.Tint -> 0.9f
        else -> 1f
    }
    val showsAuto = auto && !settings.lockAll
    val selected = editing == parameter
    val manual = !auto && parameter != Parameter.Aperture && !(parameter == Parameter.Bias && settings.exposureBias == 0f)
    val valueColor = when {
        manual -> TcColors.ManualYellow
        settings.lockAll -> Color(0xFF8AB4FF)
        else -> Color.White
    }
    Column(
        Modifier
            .weight(weight)
            .heightIn(min = 44.dp)
            .combinedClickable(onClick = { onEdit(if (selected) null else parameter) }, onDoubleClick = onReset)
            .semantics {
                contentDescription = title
                stateDescription = (if (showsAuto) "A " else "") + value
                this.selected = selected
            }
            // 把选中黄条画在底边上，不占布局。
            .drawWithContent {
                drawContent()
                if (selected) {
                    val w = 20.dp.toPx()
                    val h = 2.dp.toPx()
                    drawRoundRect(TcColors.ManualYellow, topLeft = Offset((size.width - w) / 2, size.height - h - 1.dp.toPx()),
                        size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 把标题和数值放进固定高度的格子居中，因为中英文字体行高不同。
        Box(Modifier.height(14.dp), contentAlignment = Alignment.Center) {
            // 纯英文标题下移 1dp，看起来与中文标题等高。
            val latin = title.none { it.code > 0x2E7F }
            Text(title, color = Chrome.Secondary, maxLines = 1, softWrap = false, style = TitleStyle,
                modifier = if (latin) Modifier.offset(y = 1.dp) else Modifier)
        }
        Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            if (showsAuto) Text("A", color = Chrome.Secondary, maxLines = 1, style = AutoStyle, modifier = Modifier.alignByBaseline())
            Text(value, color = valueColor.copy(alpha = if (dimmed) 0.4f else 1f), maxLines = 1, softWrap = false, style = ValueStyle,
                modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun LockButton(locked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (locked) R.string.unlock_all else R.string.lock_all)
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = Color.Transparent,
        modifier = Modifier.size(40.dp, 44.dp).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, contentDescription = null,
                tint = if (locked) Color(0xFF8AB4FF) else Chrome.Secondary, modifier = Modifier.size(20.dp))
        }
    }
}

/** 显示参数条下方展开的调节条。 */
@Composable
fun ParameterAdjuster(agent: CaptureAgent, host: HostCoordinator?, parameter: Parameter?, modifier: Modifier = Modifier) {
    AnimatedVisibility(parameter != null && parameter != Parameter.Focus, modifier, enter = fadeIn(), exit = fadeOut()) {
        val p = parameter ?: return@AnimatedVisibility
        TcFloatingCard {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) { AdjusterContent(agent, host, p) }
        }
    }
}

@Composable
private fun AdjusterContent(agent: CaptureAgent, host: HostCoordinator?, parameter: Parameter) {
    val s = agent.settings
    val live = agent.live
    fun edit(change: (CaptureSettings) -> CaptureSettings) {
        host?.let { it.update(change(it.settings)) }
    }
    /** 改色温或色调时进入手动白平衡，另一项取当前实测值。 */
    fun lockWhiteBalance(change: (CaptureSettings) -> CaptureSettings) = edit { old ->
        var s1 = old
        if (!old.lockWhiteBalance && live != null) {
            s1 = s1.copy(whiteBalanceKelvin = (live.kelvin / 100).roundToInt() * 100f, whiteBalanceTint = (live.tint / 5).roundToInt() * 5f)
        }
        change(s1.copy(lockWhiteBalance = true))
    }
    if (host == null && parameter != Parameter.Aperture) {
        Note(stringResource(R.string.params_by_host))
        return
    }
    when (parameter) {
        Parameter.Shutter -> RulerRow(stringResource(R.string.param_shutter), auto = !s.manualShutter,
            onAuto = { edit { it.copy(manualShutter = false) } }) {
            TickRuler(CaptureSettings.shutterChoices.map { it.toFloat() }, if (s.manualShutter) s.shutterSeconds.toFloat() else null,
                live?.shutter?.toFloat(), { Format.shutterShort(it.toDouble()) },
                { Format.shutterShort(it.toDouble()) in SHUTTER_MAJORS }, stringResource(R.string.param_shutter), Modifier.weight(1f)) { v ->
                edit { it.copy(manualShutter = true, shutterSeconds = v.toDouble()) }
            }
        }
        Parameter.Iso -> RulerRow("ISO", auto = !s.manualISO, onAuto = { edit { it.copy(manualISO = false) } }) {
            TickRuler(CaptureSettings.isoChoices, if (s.manualISO) s.iso else null, live?.iso, Format::rounded,
                { it in listOf(25f, 100f, 400f, 1600f, 6400f) }, "ISO", Modifier.weight(1f)) { v ->
                edit { it.copy(manualISO = true, iso = v) }
            }
        }
        Parameter.WhiteBalance -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RulerRow(stringResource(R.string.param_white_balance), auto = !s.lockWhiteBalance,
                onAuto = { edit { it.copy(lockWhiteBalance = false) } }) {
                TickRuler((25..80).map { it * 100f }, if (s.lockWhiteBalance) s.whiteBalanceKelvin else null, live?.kelvin,
                    { "${it.roundToInt()}K" }, { it.roundToInt() % 500 == 0 }, stringResource(R.string.param_white_balance),
                    Modifier.weight(1f)) { v -> lockWhiteBalance { it.copy(whiteBalanceKelvin = v) } }
            }
            Row(Modifier.fillMaxWidth()) {
                for ((name, icon, kelvin) in listOf(
                    Triple(R.string.wb_incandescent, Icons.Rounded.WbIncandescent, 3200f),
                    Triple(R.string.wb_fluorescent, Icons.Rounded.WbIridescent, 4000f),
                    Triple(R.string.wb_shade, Icons.Rounded.WbShade, 4500f),
                    Triple(R.string.wb_daylight, Icons.Rounded.WbSunny, 5600f),
                    Triple(R.string.wb_cloudy, Icons.Rounded.WbCloudy, 6500f),
                )) {
                    val selected = s.lockWhiteBalance && s.whiteBalanceKelvin == kelvin
                    PresetButton(stringResource(name), icon, selected, Modifier.weight(1f)) {
                        lockWhiteBalance { it.copy(whiteBalanceKelvin = kelvin, whiteBalanceTint = 0f) }
                    }
                }
                PresetButton(stringResource(R.string.wb_lock), if (s.lockWhiteBalance) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    false, Modifier.weight(1f)) {
                    if (live != null) lockWhiteBalance {
                        it.copy(whiteBalanceKelvin = (live.kelvin / 100).roundToInt() * 100f, whiteBalanceTint = (live.tint / 5).roundToInt() * 5f)
                    }
                }
            }
        }
        Parameter.Tint -> RulerRow(stringResource(R.string.param_tint), auto = !s.lockWhiteBalance,
            onAuto = { edit { it.copy(lockWhiteBalance = false) } }) {
            TickRuler((-20..20).map { it * 5f }, if (s.lockWhiteBalance) s.whiteBalanceTint else null, live?.tint, Format::signed,
                { it.roundToInt() % 25 == 0 }, stringResource(R.string.param_tint), Modifier.weight(1f)) { v ->
                lockWhiteBalance { it.copy(whiteBalanceTint = v) }
            }
        }
        Parameter.Bias -> if (s.lockExposure) {
            Note(stringResource(R.string.bias_disabled))
        } else {
            RulerRow(stringResource(R.string.param_bias), auto = false, autoText = stringResource(R.string.reset_zero),
                onAuto = { edit { it.copy(exposureBias = 0f) } }) {
                TickRuler(CaptureSettings.biasChoices, s.exposureBias, null, { if (it == 0f) "0" else "%+.1f".format(Locale.US, it) },
                    { it == it.roundToInt().toFloat() }, stringResource(R.string.param_bias), Modifier.weight(1f)) { v ->
                    edit { it.copy(exposureBias = v) }
                }
            }
        }
        Parameter.Aperture -> Note(stringResource(R.string.aperture_note, live?.aperture?.let { "f/%.2g".format(Locale.US, it) } ?: "—"))
        Parameter.Focus -> Unit
    }
}

/** 显示竖向对焦标尺：上远下近，拖动改为手动，点 AF 恢复自动；刻度是对焦位置而非距离。 */
@Composable
fun FocusPanel(agent: CaptureAgent, lens: LensKind, modifier: Modifier = Modifier) {
    // 使用点选镜头的实时对焦位置。
    val live = agent.live?.let { agent.camera.live(lens) ?: it }
    val dual = agent.configuredLens?.isDual == true
    val manual = agent.manualFocus[lens]
    val focus = if (dual) stringResource(R.string.focus_of, agent.lensLabels[lens] ?: lens.code) else stringResource(R.string.focus)
    TcFloatingCard(modifier.width(80.dp).heightIn(max = 340.dp), radius = 16.dp) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(focus, color = if (dual) Chrome.Accent else Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (live?.manualFocusSupported == false) {
                Text(stringResource(R.string.fixed_focus), color = Chrome.Secondary, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false).padding(vertical = 24.dp))
            } else {
                Text(stringResource(R.string.focus_far), color = Chrome.Secondary, fontSize = 11.sp)
                TickRuler(FOCUS_VALUES, manual, live?.focus, { "%.2f".format(Locale.US, it) }, { (it * 100).roundToInt() % 10 == 0 },
                    focus, Modifier.weight(1f), vertical = true) { v -> agent.setManualFocus(v, lens) }
                Text(stringResource(R.string.focus_near), color = Chrome.Secondary, fontSize = 11.sp)
            }
            Surface(onClick = { agent.setManualFocus(null, lens) }, shape = CircleShape, color = Chrome.Pressed,
                modifier = Modifier.size(52.dp, 44.dp).semantics { contentDescription = "AF" }) {
                Box(contentAlignment = Alignment.Center) {
                    Text("AF", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (manual == null) TcColors.ManualYellow else Color.White)
                }
            }
        }
    }
}

/** 列出对焦刻度，上面远（1），下面近（0）。 */
private val FOCUS_VALUES = (100 downTo 0).map { it / 100f }

@Composable
private fun RulerRow(title: String, auto: Boolean, onAuto: () -> Unit, autoText: String? = null,
                     content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
        Surface(onClick = onAuto, shape = CircleShape, color = Chrome.Pressed,
            modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = "$title ${autoText ?: ""}" }) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(autoText ?: stringResource(R.string.auto), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (auto && autoText == null) TcColors.ManualYellow else Color.White)
            }
        }
    }
}

@Composable
private fun PresetButton(name: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = CircleShape,
        modifier = modifier.heightIn(min = 36.dp).semantics { contentDescription = name; this.selected = selected }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (selected) TcColors.ManualYellow else Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Chrome.Secondary, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

/** 列出快门刻度尺上标数字的档位。 */
private val SHUTTER_MAJORS = setOf("1.0\"", "1/4", "1/15", "1/60", "1/250", "1/1000", "1/4000")
