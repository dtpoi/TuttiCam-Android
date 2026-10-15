package com.thinktough.tutticam.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 定义与 iOS 一致的品牌色。 */
object TcColors {
    val ManualYellow = Color(0xFFFFD60A)
    val StillGreen = Color(0xFF30D158)
    val MovingOrange = Color(0xFFFF9F0A)
    val ProblemRed = Color(0xFFFF453A)
    /** 定义深一档的蓝，白色小字对比度约 4.7:1。 */
    val SelectBlue = Color(0xFF006FED)
    /** 定义亮底上用的绿、橙、黄。 */
    val StillGreenOnLight = Color(0xFF007A2E)
    val MovingOrangeOnLight = Color(0xFFB85200)
    val WarningOnLight = Color(0xFF946600)
}

private val BrandDark = darkColorScheme(primary = Color(0xFF8AB4FF), secondary = TcColors.ManualYellow)
private val BrandLight = lightColorScheme(primary = TcColors.SelectBlue, secondary = Color(0xFF946F00))

/** 套用 Material 3 主题，Android 12 起用动态颜色；forceDark 让拍摄页固定深色。 */
@Composable
fun TuttiCamTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
