package com.thinktough.tutticam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thinktough.tutticam.R
import kotlinx.coroutines.launch

/** 显示 3 页引导：功能、组队、权限。 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (page) {
                        0 -> {
                            Header(stringResource(R.string.welcome_title, stringResource(R.string.app_name)), stringResource(R.string.tagline))
                            Feature(Icons.Rounded.Devices, R.string.feature_sync_title, R.string.feature_sync_detail)
                            Feature(Icons.Rounded.CenterFocusStrong, R.string.feature_still_title, R.string.feature_still_detail)
                            Feature(Icons.Rounded.ViewInAr, R.string.feature_3d_title, R.string.feature_3d_detail)
                        }
                        1 -> {
                            Header(stringResource(R.string.team_title), stringResource(R.string.team_subtitle))
                            Feature(Icons.Rounded.Wifi, R.string.feature_wifi_title, R.string.feature_wifi_detail)
                            Feature(Icons.Rounded.Groups, R.string.feature_mixed_title, R.string.feature_mixed_detail)
                            Feature(Icons.Rounded.Lock, R.string.feature_local_title, R.string.feature_local_detail)
                        }
                        else -> PermissionsPage()
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    Surface(Modifier.size(8.dp), shape = CircleShape,
                        color = if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
                }
            }
            Button({
                if (pager.currentPage < 2) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onDone()
            }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(if (pager.currentPage < 2) R.string.next else R.string.get_started), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, detail: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val camera = remember(tick) { granted(Manifest.permission.CAMERA) }
    val location = remember(tick) { granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION) }
    val storage = remember(tick) { granted(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    Header(stringResource(R.string.permissions_title), stringResource(R.string.permissions_subtitle))
    PermissionRow(Icons.Rounded.CameraAlt, R.string.permission_camera, R.string.permission_camera_detail, required = true,
        granted = camera) { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }
    PermissionRow(Icons.Rounded.LocationOn, R.string.permission_location, R.string.permission_location_detail, required = false,
        granted = location) {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        PermissionRow(Icons.Rounded.Folder, R.string.permission_storage, R.string.permission_storage_detail, required = true,
            granted = storage) {
            launcher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
    Text(stringResource(R.string.permissions_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start)
}

@Composable
private fun PermissionRow(icon: ImageVector, title: Int, detail: Int, required: Boolean, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (required) R.string.required else R.string.optional), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.granted), tint = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onRequest) { Text(stringResource(R.string.allow)) }
        }
    }
    Spacer(Modifier.size(0.dp))
}
