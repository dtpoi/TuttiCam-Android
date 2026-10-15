package com.thinktough.tutticam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.AppModel
import com.thinktough.tutticam.app.ClientLink
import com.thinktough.tutticam.app.Role
import com.thinktough.tutticam.ui.capture.CaptureScreen
import com.thinktough.tutticam.ui.capture.openAppSettings
import com.thinktough.tutticam.ui.theme.TuttiCamTheme

/** 切换引导、开始页、主机列表和拍摄页，拍摄页固定深色。 */
@Composable
fun Root(model: AppModel, onOnboardingDone: () -> Unit) {
    var onboarded by remember { mutableStateOf(model.prefs.onboardingDone) }
    val agent = model.agent
    val inCapture = agent != null && (model.role == Role.Host ||
        (model.role == Role.Client && (model.clientLink == ClientLink.Connected || model.clientLink == ClientLink.Lost)))
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            model.agent?.prepareCamera()
        } else {
            val activity = context as? android.app.Activity
            // 用户选过“不再询问”时只能去系统设置里开。
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                openAppSettings(context)
            }
        }
    }
    TuttiCamTheme(forceDark = inCapture) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !onboarded -> Onboarding {
                    model.prefs.onboardingDone = true
                    onboarded = true
                    onOnboardingDone()
                }
                inCapture -> CaptureScreen(model, agent) { cameraPermission.launch(Manifest.permission.CAMERA) }
                model.role == Role.Client -> HostListScreen(model)
                else -> StartScreen(model)
            }
        }
        model.alert?.let { text ->
            AlertDialog(onDismissRequest = { model.dismissAlert() }, text = { Text(text) },
                confirmButton = { TextButton({ model.dismissAlert() }) { Text(stringResource(R.string.ok)) } })
        }
    }
}
