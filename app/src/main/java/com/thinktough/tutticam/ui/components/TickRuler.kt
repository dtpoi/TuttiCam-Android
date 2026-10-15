package com.thinktough.tutticam.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 显示刻度尺选择器：可拖动、带惯性、按格吸附并轻震，点刻度可直接选。
 * 格距随尺长伸缩（至少 12dp），中间显示当前值；selection 为空时指针跟随 autoValue。
 */
@Composable
fun TickRuler(
    values: List<Float>,
    selection: Float?,
    autoValue: Float?,
    format: (Float) -> String,
    isMajor: (Float) -> Boolean,
    label: String,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onSelect: (Float) -> Unit,
) {
    BoxWithConstraints(if (vertical) modifier.width(64.dp).fillMaxHeight() else modifier.fillMaxWidth().height(40.dp)) {
        val density = LocalDensity.current
        val length = with(density) { (if (vertical) maxHeight else maxWidth).toPx() }
        val spacing = maxOf(with(density) { 12.dp.toPx() }, floor(length / values.size))
        RulerCanvas(values, selection, autoValue, format, isMajor, label, vertical, spacing, onSelect)
    }
}

@Composable
private fun RulerCanvas(
    values: List<Float>,
    selection: Float?,
    autoValue: Float?,
    format: (Float) -> String,
    isMajor: (Float) -> Boolean,
    label: String,
    vertical: Boolean,
    spacing: Float,
    onSelect: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    fun nearest(value: Float?): Int? = value?.let { v -> values.indices.minByOrNull { abs(values[it] - v) } }
    val anchor = nearest(selection) ?: nearest(autoValue) ?: (values.size / 2)
    var position by remember(spacing) { mutableFloatStateOf(anchor * spacing) }
    var dragging by remember { mutableStateOf(false) }
    val maxPosition = (values.size - 1) * spacing
    val index = (position / spacing).roundToInt().coerceIn(0, values.lastIndex)

    // 不拖动时跟随外部的值。
    LaunchedEffect(anchor, dragging, spacing) {
        if (!dragging) animate(position, anchor * spacing, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> position = v }
    }
    LaunchedEffect(index) {
        if (dragging) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    fun settle(velocity: Float) {
        scope.launch {
            val projected = (position - velocity * 0.12f).coerceIn(0f, maxPosition)
            val target = (projected / spacing).roundToInt().coerceIn(0, values.lastIndex)
            animate(position, target * spacing, initialVelocity = -velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { v, _ -> position = v }
            dragging = false
            onSelect(values[target])
        }
    }

    // 拖动时显示手下的值，否则显示选中值或实测值。
    val current = if (dragging) values[index] else selection ?: autoValue ?: values[index]
    val accent = Chrome.Accent
    val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum")
    val currentStyle = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

    Canvas(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .semantics {
                contentDescription = label
                stateDescription = (if (selection == null) "A " else "") + format(current)
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..values.lastIndex.toFloat(), values.size - 2)
                setProgress { target ->
                    onSelect(values[target.roundToInt().coerceIn(0, values.lastIndex)])
                    true
                }
            }
            .draggable(
                orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
                state = rememberDraggableState { delta -> position = (position - delta).coerceIn(0f, maxPosition) },
                onDragStarted = { dragging = true },
                onDragStopped = { velocity -> settle(velocity) },
            )
            .pointerInput(values, spacing, vertical) {
                detectTapGestures { tap ->
                    val along = if (vertical) tap.y - size.height / 2f else tap.x - size.width / 2f
                    val target = ((position + along) / spacing).roundToInt().coerceIn(0, values.lastIndex)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(values[target])
                }
            },
    ) {
        val majorLength = (if (vertical) 18 else 15).dp.toPx()
        // 先测量当前值，主刻度数字与它重叠时淡出。
        val value = measurer.measure(format(current), currentStyle)
        val gap = 4.dp.toPx()
        val fadeRange = 10.dp.toPx()
        val minorLength = (if (vertical) 10 else 8).dp.toPx()
        val stroke = 1.dp.toPx()
        if (!vertical) {
            val center = size.width / 2
            val bottom = size.height
            val labelTop = 2.dp.toPx()
            for (i in values.indices) {
                val x = center + i * spacing - position
                if (x < -spacing || x > size.width + spacing) continue
                val major = isMajor(values[i])
                drawLine(Color.White.copy(alpha = if (major) 0.9f else 0.45f), Offset(x, bottom - if (major) majorLength else minorLength),
                    Offset(x, bottom), strokeWidth = stroke)
                if (major) {
                    val text = measurer.measure(format(values[i]), labelStyle)
                    val clear = (value.size.width + text.size.width) / 2f + gap
                    val fade = ((abs(x - center) - clear) / fadeRange).coerceIn(0f, 1f)
                    if (fade > 0.02f) drawText(text, topLeft = Offset(x - text.size.width / 2, labelTop), alpha = fade)
                }
            }
            drawText(value, topLeft = Offset(center - value.size.width / 2, 0f))
            // 画中间指针：竖线加下端小三角。
            drawLine(accent, Offset(center, bottom - majorLength), Offset(center, bottom - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
            val t = 3.dp.toPx()
            drawPath(Path().apply {
                moveTo(center - t, bottom)
                lineTo(center + t, bottom)
                lineTo(center, bottom - t * 1.4f)
                close()
            }, accent)
        } else {
            val center = size.height / 2
            val right = size.width
            val labelRight = right - majorLength - 4.dp.toPx()
            for (i in values.indices) {
                val y = center + i * spacing - position
                if (y < -spacing || y > size.height + spacing) continue
                val major = isMajor(values[i])
                drawLine(Color.White.copy(alpha = if (major) 0.9f else 0.45f), Offset(right - if (major) majorLength else minorLength, y),
                    Offset(right, y), strokeWidth = stroke)
                if (major) {
                    val text = measurer.measure(format(values[i]), labelStyle)
                    val clear = (value.size.height + text.size.height) / 2f + gap / 2
                    val fade = ((abs(y - center) - clear) / fadeRange).coerceIn(0f, 1f)
                    if (fade > 0.02f) drawText(text, topLeft = Offset(labelRight - text.size.width, y - text.size.height / 2), alpha = fade)
                }
            }
            drawText(value, topLeft = Offset(0f, center - value.size.height / 2))
            drawLine(accent, Offset(right - majorLength, center), Offset(right - 2.dp.toPx(), center), strokeWidth = 2.dp.toPx())
            val t = 3.dp.toPx()
            drawPath(Path().apply {
                moveTo(right, center - t)
                lineTo(right, center + t)
                lineTo(right - t * 1.4f, center)
                close()
            }, accent)
        }
    }
}
