package com.thinktough.tutticam.storage

import android.content.Context
import android.os.Build
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.WireJson

/** 在本机保存拍摄设置和设备名，读取时缺的字段取默认值。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tutticam", Context.MODE_PRIVATE)

    fun loadSettings(): CaptureSettings = prefs.getString(KEY_SETTINGS, null)?.let { text ->
        runCatching { WireJson.json.decodeFromString(CaptureSettings.serializer(), text) }.getOrNull()
    } ?: CaptureSettings()

    fun saveSettings(settings: CaptureSettings) {
        prefs.edit().putString(KEY_SETTINGS, WireJson.json.encodeToString(CaptureSettings.serializer(), settings)).apply()
    }

    var deviceName: String
        get() = prefs.getString(KEY_NAME, null) ?: defaultName()
        set(value) = prefs.edit().putString(KEY_NAME, value.trim().ifEmpty { defaultName() }).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** 控制启动时直接进入主机相机页，默认关闭。 */
    var autoHost: Boolean
        get() = prefs.getBoolean(KEY_AUTO_HOST, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_HOST, value).apply()

    /** 记录同意过的主机名，再加入时不询问。 */
    var trustedHosts: Set<String>
        get() = prefs.getStringSet(KEY_TRUSTED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_TRUSTED, value).apply()

    /** 记录从机上次选的镜头，重新加入时请主机沿用。 */
    var clientLens: LensConfig?
        get() = prefs.getString(KEY_CLIENT_LENS, null)?.let { runCatching { WireJson.json.decodeFromString(LensConfig.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_CLIENT_LENS) else putString(KEY_CLIENT_LENS, WireJson.json.encodeToString(LensConfig.serializer(), value))
        }.apply()

    private fun defaultName(): String = Build.MODEL ?: "Android"

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_NAME = "deviceName"
        private const val KEY_ONBOARDING = "onboardingDone"
        private const val KEY_AUTO_HOST = "autoHost"
        private const val KEY_TRUSTED = "trustedHosts"
        private const val KEY_CLIENT_LENS = "clientLens"
    }
}
