package com.thinktough.tutticam.app

import com.thinktough.tutticam.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** 查询 GitHub 上的最新正式版并与本机版本比较，只在用户点“检查更新”时联网。 */
object UpdateChecker {
    const val REPO_URL = "https://github.com/dtpoi/TuttiCam-Android"
    private const val LATEST_API = "https://api.github.com/repos/dtpoi/TuttiCam-Android/releases/latest"

    sealed interface Result {
        /** 表示本机已是最新版本。 */
        data object Latest : Result

        /** 表示有新版本，url 是它的 Release 页面。 */
        data class Available(val version: String, val url: String) : Result

        /** 表示查询失败，如没有网络或 GitHub 打不开。 */
        data object Failed : Result
    }

    /** 查询最新正式版，在 IO 线程执行。 */
    suspend fun check(current: String = BuildConfig.VERSION_NAME): Result = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(LATEST_API).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "TuttiCam-Android/$current")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching Result.Failed
                val release = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
                val tag = release.getValue("tag_name").jsonPrimitive.content
                val url = release["html_url"]?.jsonPrimitive?.content ?: "$REPO_URL/releases/latest"
                if (isNewer(tag, current)) Result.Available(tag.removePrefix("v"), url) else Result.Latest
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(Result.Failed)
    }

    /** 按数字逐段比较版本号，如 v1.10 比 1.9 新；标签前面的 v 忽略。 */
    internal fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".", "-").map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
