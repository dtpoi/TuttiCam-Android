package com.thinktough.tutticam.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 显示底部弹层：先占 2/3 屏，上拉到全屏，下拉收回或关闭；点暗处或返回键也会关闭。
 * 弹层画在页面之上，可以叠加，内容滚动和拖动弹层自然衔接。
 */
@Composable
fun TcSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val full = with(density) { maxHeight.toPx() }
        // 定义三个停靠位置：全屏、2/3 屏、关闭。
        val expanded = 0f
        val partial = full / 3f
        val hidden = full
        var offset by remember { mutableFloatStateOf(hidden) }
        var closing by remember { mutableStateOf(false) }

        fun settleTo(target: Float, velocity: Float = 0f) {
            scope.launch {
                animate(offset, target, initialVelocity = velocity,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
                if (target == hidden) onDismiss()
            }
        }

        fun close() {
            if (closing) return
            closing = true
            settleTo(hidden)
        }

        /** 松手后停到下一个或最近的停靠位置，快速甩动时按方向走。 */
        fun release(velocity: Float) {
            val anchors = listOf(expanded, partial, hidden)
            val target = when {
                velocity < -1200f -> anchors.lastOrNull { it < offset - 1f } ?: expanded
                velocity > 1200f -> anchors.firstOrNull { it > offset + 1f } ?: hidden
                else -> anchors.minBy { abs(it - offset) }
            }
            if (target == hidden) {
                closing = true
            }
            settleTo(target, velocity)
        }

        LaunchedEffect(Unit) { settleTo(partial) }
        BackHandler { close() }

        val connection = remember(full) {
            object : NestedScrollConnection {
                // 上滑时先把弹层推到全屏，再滚动内容。
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy < 0 && offset > expanded) {
                        val consumed = maxOf(dy, expanded - offset)
                        offset += consumed
                        return Offset(0f, consumed)
                    }
                    return Offset.Zero
                }

                // 下滑到内容顶部后，剩下的距离拖动弹层。
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = available.y
                    if (dy > 0 && source == NestedScrollSource.UserInput) {
                        val moved = minOf(dy, hidden - offset)
                        offset += moved
                        return Offset(0f, moved)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (available.y < 0 && offset > expanded) {
                        release(available.y)
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (offset !in listOf(expanded, partial)) release(available.y)
                    return available
                }
            }
        }

        // 让暗处随弹层上升渐深。
        val progress = ((hidden - offset) / (hidden - partial)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f * progress)).pointerInput(Unit) {
            detectTapGestures { close() }
        })
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .height(maxHeight)
                .offset { IntOffset(0, offset.roundToInt()) }
                .nestedScroll(connection)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(expanded, hidden) },
                    onDragStopped = { velocity -> release(velocity) },
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // 全屏时给状态栏留位置。
                val topInset = with(density) { WindowInsets.statusBars.getTop(density).toFloat() }
                val insetFraction = ((topInset - offset) / topInset.coerceAtLeast(1f)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(with(density) { (topInset * insetFraction).toDp() }))
                // 画拖动条。
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.size(width = 32.dp, height = 4.dp), shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) {}
                }
                content()
            }
        }
    }
}

/** 显示弹层里的分组标题，用主题色小字，与设置页一致。 */
/** 给出弹层内容的左右边距，和设置页的标题、选项、说明对齐。 */
val SheetPadding = 24.dp

/** 返回弹层里卡片的颜色：比弹层底色深浅差一档，卡片边缘看得出来，内边距才不显得错位。 */
@Composable
fun sheetCardColors(): CardColors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

@Composable
fun SheetSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}
