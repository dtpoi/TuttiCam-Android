package com.thinktough.tutticam.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 定义预览上的悬浮层颜色：半透明深色，不模糊，因为 Compose 取不到 SurfaceView 的画面。 */
object Chrome {
    val Container = Color(0xFF1B1B1F).copy(alpha = 0.74f)
    val Outline = Color.White.copy(alpha = 0.08f)
    val Content = Color.White
    val Secondary = Color.White.copy(alpha = 0.72f)
    val Pressed = Color.White.copy(alpha = 0.14f)
    /** 定义主题蓝，与 iOS 深色模式的系统蓝相同。 */
    val Accent = Color(0xFF0A84FF)
}

/** 显示悬浮容器，带圆角、投影和细描边。 */
@Composable
fun TcFloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Chrome.Container,
        contentColor = Chrome.Content,
        shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, Chrome.Outline),
        content = content,
    )
}

/** 显示横向胶囊工具栏。 */
@Composable
fun TcFloatingToolbar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    TcFloatingSurface(modifier) {
        Row(Modifier.padding(contentPadding), horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** 显示竖向工具栏，放在侧栏布局的边上。 */
@Composable
fun TcVerticalToolbar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    TcFloatingSurface(modifier) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** 显示悬浮卡片，用于参数条和调节条。 */
@Composable
fun TcFloatingCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    TcFloatingSurface(modifier, RoundedCornerShape(radius), content)
}

/** 显示工具栏图标按钮，触控区 48dp。 */
@Composable
fun ChromeIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Chrome.Content,
    badge: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)) { badge() }
        }
    }
}

/** 显示胶囊形文字按钮，如“重新连接”。 */
@Composable
fun ChromePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minHeight = 48.dp),
        shape = CircleShape,
        color = if (prominent) Color.White else Chrome.Pressed,
        contentColor = if (prominent) Color.Black else Chrome.Content,
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
