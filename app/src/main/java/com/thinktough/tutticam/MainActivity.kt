package com.thinktough.tutticam

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import com.thinktough.tutticam.app.AppLanguage
import com.thinktough.tutticam.app.Role
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.ui.Root
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model get() = (application as TuttiCamApp).model

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenshotDemo.enable(this, intent)
        if (ScreenshotDemo.isOn) model.startScreenshotDemo() else model.handleLaunch()
        setContent { Root(model) { if (model.prefs.autoHost && model.role == Role.None) model.becomeHost() } }
        // 会话期间保持亮屏，主机要调度，从机要上报状态。
        lifecycleScope.launch {
            snapshotFlow { model.role }.collect { role ->
                if (role == Role.None) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        super.onStop()
        model.onBackground()
    }
}
