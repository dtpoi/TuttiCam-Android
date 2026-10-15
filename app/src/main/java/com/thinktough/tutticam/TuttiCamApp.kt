package com.thinktough.tutticam

import android.app.Application
import android.content.Context
import com.thinktough.tutticam.app.AppLanguage
import com.thinktough.tutticam.app.AppModel

class TuttiCamApp : Application() {
    /** 保存全进程唯一的状态（角色、会话、连接、相机），界面重建不影响它。 */
    lateinit var model: AppModel
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        model = AppModel(this)
        // 调试版探测多镜头组合并写日志。
        if (BuildConfig.DEBUG) Thread({ com.thinktough.tutticam.camera.DualProbe.run(this) }, "tutticam.dual-probe").start()
    }
}
