package com.thinktough.tutticam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thinktough.tutticam.BuildConfig
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.AppModel
import com.thinktough.tutticam.app.ClientLink

/** 给出开始页图标的可见大小，等于自适应图标中间 72dp 那块放大后的尺寸。 */
private val LOGO_SIZE = 120.dp

/** 显示开始页：设备名、创建会话、遥控模式、加入会话。 */
@Composable
fun StartScreen(model: AppModel) {
    var name by remember { mutableStateOf(model.deviceName) }
    val focus = LocalFocusManager.current
    fun commitName() {
        if (name.trim() != model.deviceName) model.rename(name)
        name = model.deviceName
        focus.clearFocus()
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.heightIn(min = 24.dp))
            // 按自适应图标规范：图层 108dp，桌面只显示中间 72dp；这里同样只占中间这块，图标才不会显得小。
            Box(Modifier.size(LOGO_SIZE).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.requiredSize(LOGO_SIZE * 108f / 72f))
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.heightIn(min = 8.dp))
            OutlinedTextField(
                name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.device_name)) },
                supportingText = { Text(stringResource(R.string.device_name_note)) },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commitName() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
            Button({ commitName(); model.becomeHost() }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel(Icons.Rounded.WifiTethering, stringResource(R.string.create_session))
            }
            FilledTonalButton({ commitName(); model.becomeHost(remoteOnly = true) }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel(Icons.Rounded.SettingsRemote, stringResource(R.string.remote_mode))
            }
            OutlinedButton({ commitName(); model.becomeClient() }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                ButtonLabel(Icons.Rounded.GroupAdd, stringResource(R.string.join_session))
            }
            Text(stringResource(R.string.about_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.size(8.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** 查找附近的主机并加入，首次加入前请用户确认。 */
@Composable
fun HostListScreen(model: AppModel) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 560.dp).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 72.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.join_session), style = MaterialTheme.typography.headlineMedium)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching_hosts), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.searching_hosts_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(model.foundHosts, key = { it.peer.key }) { found ->
                val connecting = model.clientLink == ClientLink.Connecting && model.hostPeer == found.peer
                ElevatedCard(onClick = { model.join(found) }, enabled = model.clientLink == ClientLink.Idle, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(found.sessionName) },
                        supportingContent = { Text(stringResource(R.string.host_session)) },
                        leadingContent = { Icon(Icons.Rounded.CameraAlt, null) },
                        trailingContent = { if (connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    )
                }
            }
        }
        FilledTonalIconButton({ model.leave() }, Modifier.align(Alignment.TopStart).padding(12.dp).size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
        }
    }
    model.pendingJoin?.let { found ->
        AlertDialog(
            onDismissRequest = { model.pendingJoin = null },
            title = { Text(stringResource(R.string.join_confirm_title, found.sessionName)) },
            text = { Text(stringResource(R.string.join_confirm_message)) },
            confirmButton = { TextButton({ model.confirmJoin(found) }) { Text(stringResource(R.string.agree_and_join)) } },
            dismissButton = { TextButton({ model.pendingJoin = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
