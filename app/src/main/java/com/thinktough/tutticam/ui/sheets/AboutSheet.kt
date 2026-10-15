package com.thinktough.tutticam.ui.sheets

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thinktough.tutticam.BuildConfig
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.UpdateChecker
import com.thinktough.tutticam.ui.components.TcSheet
import kotlinx.coroutines.launch

/** 显示关于页：图标、版本、官网、检查更新、源代码、协议链接、开源项目和外接相机协议来源。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
    TcSheet(onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // 用白底圆角方块加前景图层画出应用图标。
                Surface(Modifier.size(60.dp), shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = Color.Unspecified,
                            modifier = Modifier.size(90.dp))
                    }
                }
                Column {
                    Text(stringResource(R.string.app_full_name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.version_value, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("tutticam.com", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { open(WEBSITE) }.padding(vertical = 2.dp))
                }
            }
            // 只在点按时查询 GitHub 上的最新版本；有新版本时再点打开 Release 页面下载。
            Text(stringResource(R.string.section_updates), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp))
            UpdateRow(checking, update) {
                val result = update
                if (result is UpdateChecker.Result.Available) open(result.url)
                else if (!checking) scope.launch {
                    checking = true
                    update = UpdateChecker.check()
                    checking = false
                }
            }
            LinkRow(stringResource(R.string.source_code), Icons.AutoMirrored.Rounded.OpenInNew, value = "GitHub · GPL-3.0") {
                open(UpdateChecker.REPO_URL)
            }
            Text(stringResource(R.string.update_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            Text(stringResource(R.string.section_legal), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp))
            LinkRow(stringResource(R.string.privacy_policy), Icons.AutoMirrored.Rounded.OpenInNew) { open("https://tutticam.com/privacy.html") }
            LinkRow(stringResource(R.string.terms_of_service), Icons.AutoMirrored.Rounded.OpenInNew) { open("https://tutticam.com/terms.html") }
            LinkRow(stringResource(R.string.eula), Icons.AutoMirrored.Rounded.OpenInNew) { open("https://tutticam.com/eula.html") }
            LinkRow(stringResource(R.string.contact_us), Icons.Rounded.MailOutline, value = "help@tutticam.com") { open("mailto:help@tutticam.com") }
            Text(stringResource(R.string.legal_note_android), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            // 列出用到的开源项目，点开是 GitHub 仓库。
            Text(stringResource(R.string.section_open_source), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp))
            for (item in OPEN_SOURCE) {
                Row(Modifier.fillMaxWidth().clickable { open(item.url) }.heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(item.url.removePrefix("https://") + " · " + item.license, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
            }
            Text(stringResource(R.string.open_source_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { open("https://www.apache.org/licenses/LICENSE-2.0") }
                    .padding(horizontal = 24.dp, vertical = 4.dp))

            // 列出外接相机协议的来源和商标声明。
            Text(stringResource(R.string.acc_sources_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp))
            val appName = stringResource(R.string.app_name)
            for (text in listOf(R.string.ack_furble, R.string.ack_dji, R.string.ack_gopro, R.string.trademarks)) {
                Text(stringResource(text, appName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
        }
    }
}

private const val WEBSITE = "https://www.tutticam.com"

private class OpenSource(val name: String, val url: String, val license: String)

/** 列出 App 运行时用到的开源项目。 */
private val OPEN_SOURCE = listOf(
    OpenSource("Android Jetpack（Compose、Material 3、Activity、Lifecycle、Core、ExifInterface、HeifWriter、WindowManager）",
        "https://github.com/androidx/androidx", "Apache-2.0"),
    OpenSource("Kotlin", "https://github.com/JetBrains/kotlin", "Apache-2.0"),
    OpenSource("kotlinx.coroutines", "https://github.com/Kotlin/kotlinx.coroutines", "Apache-2.0"),
    OpenSource("kotlinx.serialization", "https://github.com/Kotlin/kotlinx.serialization", "Apache-2.0"),
    OpenSource("Material Design Icons", "https://github.com/google/material-design-icons", "Apache-2.0"),
    OpenSource("furble", "https://github.com/gkoh/furble", "MIT"),
    OpenSource("Open GoPro", "https://github.com/gopro/OpenGoPro", "MIT"),
)

/** 显示“检查更新”一行，标题下面是检查结果。 */
@Composable
private fun UpdateRow(checking: Boolean, result: UpdateChecker.Result?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = when {
        checking -> stringResource(R.string.update_checking) to colors.onSurfaceVariant
        result is UpdateChecker.Result.Available -> stringResource(R.string.update_available, result.version) to colors.primary
        result == UpdateChecker.Result.Latest -> stringResource(R.string.update_latest) to colors.onSurfaceVariant
        result == UpdateChecker.Result.Failed -> stringResource(R.string.update_failed) to colors.error
        else -> null
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.check_updates), style = MaterialTheme.typography.bodyLarge)
            if (status != null) Text(status.first, style = MaterialTheme.typography.bodySmall, color = status.second)
        }
        when {
            checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            result is UpdateChecker.Result.Available ->
                Icon(Icons.Rounded.Download, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Rounded.Refresh, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LinkRow(title: String, icon: ImageVector, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
