package com.thinktough.tutticam.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.thinktough.tutticam.R
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.LensKind
import com.thinktough.tutticam.protocol.PhotoFormat
import com.thinktough.tutticam.protocol.QualityMode
import com.thinktough.tutticam.protocol.StabilityMode
import java.util.Locale
import kotlin.math.roundToInt

/** 格式化数值和枚举的显示文字，写法与 iOS 相同。 */
object Format {
    /** 返回快门的短写法，如 1/250、1.0"。 */
    fun shutterShort(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return "—"
        return if (seconds >= 1) "%.1f\"".format(Locale.US, seconds) else "1/${(1 / seconds).roundToInt()}"
    }

    fun shutterTitle(context: Context, seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return "—"
        return if (seconds >= 1) context.getString(R.string.seconds_value, "%.1f".format(Locale.US, seconds))
        else context.getString(R.string.seconds_value, "1/${(1 / seconds).roundToInt()}")
    }

    fun rounded(value: Float): String = value.roundToInt().toString()

    fun signed(value: Float): String = if (value.roundToInt() == 0) "0" else "%+d".format(Locale.US, value.roundToInt())

    fun bias(value: Float): String = if (value == 0f) "0.0" else "%+.1f".format(Locale.US, value)

    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** 返回秒数文字，如 0.5 秒、2 秒。 */
    fun seconds(context: Context, value: Double): String =
        context.getString(R.string.seconds_value, if (value == value.toInt().toDouble()) value.toInt().toString() else value.toString())
}

/** 返回镜头全名，如“0.6x 超广角”；labels 给本机实际倍率，缺省时用 0.5x、1x、3x。 */
@Composable
fun LensKind.title(labels: Map<LensKind, String> = emptyMap()): String = when (this) {
    LensKind.UltraWide -> (labels[this] ?: "0.5x") + " " + stringResource(R.string.lens_ultra_wide)
    LensKind.Wide -> (labels[this] ?: "1x") + " " + stringResource(R.string.lens_wide)
    LensKind.Telephoto -> (labels[this] ?: "3x") + " " + stringResource(R.string.lens_tele)
    LensKind.Front -> stringResource(R.string.lens_front)
}

@Composable
fun LensConfig.title(labels: Map<LensKind, String> = emptyMap()): String = lenses.map { it.title(labels) }.joinToString(" + ")

@Composable
fun QualityMode.title(): String = when (this) {
    QualityMode.Quality -> stringResource(R.string.quality_quality)
    QualityMode.Balanced -> stringResource(R.string.quality_balanced)
    QualityMode.Speed -> stringResource(R.string.quality_speed)
}

@Composable
fun StabilityMode.title(): String = when (this) {
    StabilityMode.Tripod -> stringResource(R.string.stability_tripod)
    StabilityMode.Handheld -> stringResource(R.string.stability_handheld)
    StabilityMode.Off -> stringResource(R.string.stability_off)
}

fun PhotoFormat.title(): String = when (this) {
    PhotoFormat.Jpeg -> "JPEG"
    PhotoFormat.Heic -> "HEIC"
    PhotoFormat.Raw -> "RAW"
    PhotoFormat.RawJpeg -> "RAW + JPEG"
    PhotoFormat.RawHeic -> "RAW + HEIC"
}

/** 列出安卓设置里可选的照片格式。 */
val androidPhotoFormats = listOf(PhotoFormat.Jpeg, PhotoFormat.Heic, PhotoFormat.Raw, PhotoFormat.RawJpeg)
