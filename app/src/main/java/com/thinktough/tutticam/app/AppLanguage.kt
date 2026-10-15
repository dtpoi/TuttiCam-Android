package com.thinktough.tutticam.app

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** 读取和切换 App 的显示语言：Android 13 起交给系统的应用语言，更早的系统自己保存并替换资源配置。 */
object AppLanguage {
    /** 列出可选语言的标签，空字符串表示跟随系统。 */
    val tags = listOf("", "zh-CN", "en", "ja")

    private const val PREFS = "tutticam"
    private const val KEY = "language"

    /** 返回当前选的语言标签，跟随系统时为空字符串。 */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) "" else tags.firstOrNull { it.isNotEmpty() && matches(locales[0], it) } ?: locales[0].toLanguageTag()
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    }

    /** 切换语言；Android 13 起由系统刷新界面，更早的系统更新资源后重建页面。 */
    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        val locale = locale(tag)
        Locale.setDefault(locale)
        // 更新 Application 的资源，非界面代码取的文字也会换语言。
        @Suppress("DEPRECATION")
        for (resources in listOf(activity.applicationContext.resources, activity.resources)) {
            val config = Configuration(resources.configuration).apply { setLocale(locale) }
            resources.updateConfiguration(config, resources.displayMetrics)
        }
        activity.recreate()
    }

    /** 给 Android 13 以下的 Context 套上选定的语言，跟随系统时原样返回。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** 把标签换成 Locale，空标签返回系统语言。 */
    private fun locale(tag: String): Locale =
        if (tag.isEmpty()) android.content.res.Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)

    private fun matches(locale: Locale, tag: String): Boolean {
        val wanted = Locale.forLanguageTag(tag)
        return locale.language == wanted.language && (wanted.country.isEmpty() || locale.country.isEmpty() || locale.country == wanted.country)
    }
}
