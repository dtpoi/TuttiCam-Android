package com.thinktough.tutticam.ui.sheets

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thinktough.tutticam.BuildConfig
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.AppLanguage
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.accessory.brandTitle
import com.thinktough.tutticam.protocol.CameraBrand
import com.thinktough.tutticam.ui.components.TcSheet
import com.thinktough.tutticam.app.AppModel
import com.thinktough.tutticam.engine.CaptureAgent
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.QualityMode
import com.thinktough.tutticam.protocol.StabilityMode
import com.thinktough.tutticam.protocol.StillnessParams
import com.thinktough.tutticam.ui.Format
import com.thinktough.tutticam.ui.androidPhotoFormats
import com.thinktough.tutticam.ui.title
import java.util.Locale
import kotlin.math.roundToInt

/** 显示拍摄设置，主机修改后立即下发，从机只能查看。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(model: AppModel, agent: CaptureAgent, onDismiss: () -> Unit) {
    val host = model.host
    val s = host?.settings ?: agent.settings
    val editable = host != null
    fun edit(change: (CaptureSettings) -> CaptureSettings) {
        host?.let { it.update(change(it.settings)) }
    }
    val context = LocalContext.current
    var locationTick by remember { mutableIntStateOf(0) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationTick++ }
    val locationAuthorized = remember(locationTick) { agent.location.authorized }
    var autoHost by remember { mutableStateOf(model.prefs.autoHost) }
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    val activity = LocalActivity.current
    var showAbout by remember { mutableStateOf(false) }

    TcSheet(onDismiss) {
        // 截图演示时直接滚到“静止判定”分组。
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (ScreenshotDemo.sheet == "settings") 5 else 0)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), state = listState) {
            item {
                Text(stringResource(R.string.capture_settings), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (!editable) Footer(stringResource(R.string.settings_by_host))
            }

            section(R.string.section_general) {
                SwitchRow(stringResource(R.string.auto_host), autoHost, true) {
                    autoHost = it
                    model.prefs.autoHost = it
                }
                Footer(stringResource(R.string.auto_host_note))
            }

            section(R.string.section_quality) {
                PickerRow(stringResource(R.string.quality_mode), QualityMode.entries, s.quality, { it.title() }, editable) { v -> edit { it.copy(quality = v) } }
                PickerRow(stringResource(R.string.photo_format), androidPhotoFormats, s.format, { it.title() }, editable) { v -> edit { it.copy(format = v) } }
                Footer(stringResource(R.string.quality_note_android))
            }

            section(R.string.section_photo_info) {
                SwitchRow(stringResource(R.string.write_location), s.writeLocation, editable) { v -> edit { it.copy(writeLocation = v) } }
                if (s.writeLocation && !locationAuthorized) {
                    TextButton({
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.allow_location)) }
                }
                SwitchRow(stringResource(R.string.write_attitude), s.writeAttitude, editable) { v -> edit { it.copy(writeAttitude = v) } }
                SwitchRow(stringResource(R.string.write_photo_json), s.writePhotoJSON, editable) { v -> edit { it.copy(writePhotoJSON = v) } }
                Footer(stringResource(R.string.photo_info_note))
            }

            section(R.string.section_lock) {
                SwitchRow(stringResource(R.string.manual_shutter), s.manualShutter, editable) { v -> edit { it.copy(manualShutter = v) } }
                if (s.manualShutter) {
                    PickerRow(stringResource(R.string.param_shutter), CaptureSettings.shutterChoices, s.shutterSeconds,
                        { Format.shutterTitle(context, it) }, editable) { v -> edit { it.copy(shutterSeconds = v) } }
                }
                SwitchRow(stringResource(R.string.manual_iso), s.manualISO, editable) { v -> edit { it.copy(manualISO = v) } }
                if (s.manualISO) {
                    PickerRow("ISO", CaptureSettings.isoChoices, s.iso, { Format.rounded(it) }, editable) { v -> edit { it.copy(iso = v) } }
                }
                SwitchRow(stringResource(R.string.lock_focus), s.lockFocus, editable) { v -> edit { it.copy(lockFocus = v) } }
                SwitchRow(stringResource(R.string.lock_white_balance), s.lockWhiteBalance, editable) { v -> edit { it.copy(lockWhiteBalance = v) } }
                if (s.lockWhiteBalance) {
                    SliderRow(stringResource(R.string.kelvin_value, s.whiteBalanceKelvin.roundToInt()), s.whiteBalanceKelvin, 2500f..8000f, 54,
                        editable) { v -> edit { it.copy(whiteBalanceKelvin = (v / 100).roundToInt() * 100f) } }
                }
                Footer(stringResource(R.string.lock_note))
            }

            section(R.string.section_stillness) {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        StabilityMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(s.stability == mode, { edit { it.copy(stability = mode) } },
                                SegmentedButtonDefaults.itemShape(i, StabilityMode.entries.size), enabled = editable) { Text(mode.title()) }
                        }
                    }
                }
                if (s.stability != StabilityMode.Off) {
                    val stillness = s.stillness
                    fun setStillness(p: StillnessParams) = edit {
                        if (it.stability == StabilityMode.Tripod) it.copy(tripod = p) else it.copy(handheld = p)
                    }
                    SliderRow(stringResource(R.string.blur_allowed, "%.1f".format(Locale.US, stillness.blurPx)), stillness.blurPx.toFloat(),
                        0.2f..5f, 47, editable) { v -> setStillness(stillness.copy(blurPx = (v * 10).roundToInt() / 10.0)) }
                    SliderRow(stringResource(R.string.hold_seconds, "%.1f".format(Locale.US, stillness.holdSeconds)), stillness.holdSeconds.toFloat(),
                        0.2f..2f, 17, editable) { v -> setStillness(stillness.copy(holdSeconds = (v * 10).roundToInt() / 10.0)) }
                    SwitchRow(stringResource(R.string.host_stillness_only), s.hostStillnessOnly, editable) { v -> edit { it.copy(hostStillnessOnly = v) } }
                }
                Footer(stringResource(if (s.stability == StabilityMode.Off) R.string.stability_off_note else R.string.host_stillness_note))
            }

            section(R.string.section_interval) {
                PickerRow(stringResource(R.string.min_interval), CaptureSettings.intervalChoices, s.minInterval,
                    { Format.seconds(context, it) }, editable) { v -> edit { it.copy(minInterval = v) } }
                SliderRow(stringResource(R.string.trigger_lead, (s.triggerLead * 1000).roundToInt()), s.triggerLead.toFloat(), 0.15f..0.5f, 34,
                    editable) { v -> edit { it.copy(triggerLead = (v * 100).roundToInt() / 100.0) } }
                PickerRow(stringResource(R.string.sync_interval), listOf(10.0, 20.0, 30.0, 60.0), s.syncInterval,
                    { Format.seconds(context, it) }, editable) { v -> edit { it.copy(syncInterval = v) } }
            }

            // 为会话中出现过或调过的品牌各列一条提前量，再加额外等待。
            section(R.string.acc_settings_section) {
                val seen = host?.accessoryBrands ?: agent.accessories.cameras.map { it.brand }
                val brands = CameraBrand.entries.filter { it in seen || s.accessoryLead(it) != 0.0 }
                if (brands.isEmpty()) Footer(stringResource(R.string.acc_settings_empty))
                for (brand in brands) {
                    val lead = s.accessoryLead(brand)
                    SliderRow(stringResource(R.string.acc_lead, brandTitle(context, brand), (lead * 1000).roundToInt()), lead.toFloat(), 0f..1f, 99,
                        editable) { v -> edit { it.withAccessoryLead(brand, (v * 100).roundToInt() / 100.0) } }
                }
                SliderRow(stringResource(R.string.acc_extra_wait, "%.1f".format(Locale.US, s.osmoExtraWait)), s.osmoExtraWait.toFloat(), 0f..3f, 29,
                    editable) { v -> edit { it.copy(osmoExtraWait = (v * 10).roundToInt() / 10.0) } }
                Footer(stringResource(R.string.acc_settings_note))
            }

            section(null) {
                SwitchRow(stringResource(R.string.force_sync), s.forceSync, editable) { v -> edit { it.copy(forceSync = v) } }
                Footer(stringResource(if (s.stability == StabilityMode.Off) R.string.force_sync_note_off else R.string.force_sync_note))
            }

            section(null) {
                PickerRow(stringResource(R.string.language), AppLanguage.tags, language, { languageTitle(it) }, true,
                    leading = Icons.Rounded.Language) { tag ->
                    language = tag
                    activity?.let { AppLanguage.set(it, tag) }
                }
                SettingRow(stringResource(R.string.about), Modifier.clickable { showAbout = true },
                    subtitle = stringResource(R.string.version_value, BuildConfig.VERSION_NAME), leading = Icons.Rounded.Info) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // 把关于页叠在设置上面。
    if (showAbout) AboutSheet { showAbout = false }
}

/** 返回语言选项的名称，各语言用自己的写法。 */
@Composable
private fun languageTitle(tag: String): String = when (tag) {
    "zh-CN" -> "简体中文"
    "en" -> "English"
    "ja" -> "日本語"
    else -> stringResource(R.string.language_system)
}

private fun LazyListScope.section(title: Int?, content: @Composable () -> Unit) {
    item {
        Column(Modifier.padding(top = 12.dp)) {
            if (title != null) {
                Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            content()
        }
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
}

/** 显示设置行，左右 24dp 与标题对齐，至少 56dp 高。 */
@Composable
private fun SettingRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: ImageVector? = null,
                       trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (leading != null) Icon(leading, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, Modifier.clickable(enabled = enabled) { onChange(!checked) }) { Switch(checked, onChange, enabled = enabled) }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean,
                      onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge.merge(TextStyle(fontFeatureSettings = "tnum")))
        Slider(value.coerceIn(range), onChange, enabled = enabled, valueRange = range, steps = steps)
    }
}

@Composable
private fun <T> PickerRow(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, enabled: Boolean,
                          leading: ImageVector? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, Modifier.clickable(enabled = enabled) { open = true }, leading = leading) {
        // 让菜单从右侧数值下方展开。
        Box {
            Text(label(selected), style = MaterialTheme.typography.bodyLarge.merge(TextStyle(fontFeatureSettings = "tnum")),
                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(open, { open = false }) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        trailingIcon = { if (option == selected) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = { open = false; onSelect(option) },
                    )
                }
            }
        }
    }
}
