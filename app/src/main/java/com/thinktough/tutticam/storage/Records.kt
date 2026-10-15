package com.thinktough.tutticam.storage

import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.DeviceInfo
import com.thinktough.tutticam.protocol.WireJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 定义与 iOS 相同的记录文件：照片同名 JSON 和 session.json。
// 字段名用 snake_case 并排序，下游重建脚本按名字读取，改名要同步脚本。

/** 描述每张照片的同名 JSON。 */
@Serializable
data class PhotoMetadata(
    val round: Int,
    val deviceId: Int,
    val deviceCode: String,
    val deviceName: String,
    val deviceModel: String,
    val lens: String,
    /** 记录双镜头时另一颗镜头的照片文件名。 */
    val lensPair: String? = null,
    val file: String,
    val rawFile: String? = null,
    /** 记录相机内参 3×3 矩阵（按行），尺寸见 intrinsicsReference。 */
    val intrinsics: List<List<Float>>? = null,
    val intrinsicsReference: List<Int>? = null,
    /** 记录按出片宽度换算的焦距（像素）。 */
    val focalPxEstimate: Double,
    /** 记录计划拍摄时刻（主机时钟，秒）。 */
    val targetTime: Double,
    /** 记录实际曝光时刻（主机时钟，秒）。 */
    val actualTime: Double? = null,
    /** 记录实际减计划时刻（毫秒），正数表示晚。 */
    val offsetMs: Double? = null,
    val qualityMode: String,
    val format: String,
    val stabilityMode: String,
    val shutter: Double? = null,
    val iso: Double? = null,
    /** 记录白平衡色温（K）。 */
    val whiteBalance: Float? = null,
    /** 记录对焦位置，0 最近，1 最远。 */
    val lensPosition: Float,
    /** 记录对焦模式：auto、locked 或 manual。 */
    val focusMode: String,
    val rotationRate: Double? = null,
    val blurPx: Double? = null,
    val moving: Boolean,
    /** 记录拍摄时的重力方向（g），坐标同 iOS：x 右，y 机身顶部，z 屏幕朝外。 */
    val gravity: List<Double>? = null,
    val width: Int,
    val height: Int,
)

/** 记录外接相机的每次触发，用来把相机卡里的照片按组对上。 */
@Serializable
data class AccessoryShotRecord(
    val round: Int,
    val deviceCode: String,
    val cameraNumber: Int,
    /** 记录外接相机编号，如 D01。 */
    val cameraCode: String,
    val cameraName: String,
    val cameraModel: String,
    /** 记录品牌，如 dji、sony。 */
    val brand: String,
    /** 记录计划拍摄时刻（主机时钟，秒）。 */
    val targetTime: Double,
    /** 记录实际发出快门的时刻（主机时钟，秒）。 */
    val sentTime: Double? = null,
    val leadMs: Double,
    val note: String,
)

@Serializable
data class SessionDevice(
    val deviceId: Int,
    val deviceCode: String,
    val name: String,
    val model: String? = null,
)

@Serializable
data class SessionRecord(
    val session: String,
    val deviceId: Int,
    val deviceCode: String,
    val device: DeviceInfo,
    val isHost: Boolean,
    /** 记录开始时间，ISO 8601 UTC 格式。 */
    val startedAt: String,
    val settings: CaptureSettings,
    val syncRttMs: Double? = null,
    val syncErrorMs: Double? = null,
    val devices: List<SessionDevice>? = null,
    val rounds: Int? = null,
)

/** 按 Swift JSONEncoder 的格式写记录：snake_case、缩进、键排序。 */
object RecordJson {
    private val pretty = Json(WireJson.json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        pretty.encodeToString(JsonElement.serializer(), snakeCase(WireJson.json.encodeToJsonElement(serializer, value)))

    fun isoNow(): String = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    private fun snakeCase(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.map { snakeCase(it.key) to snakeCase(it.value) }.sortedBy { it.first }.toMap())
        is JsonArray -> JsonArray(element.map(::snakeCase))
        else -> element
    }

    /** 按 Swift convertToSnakeCase 的算法转换键名，如 prefer48MP → prefer48_mp。 */
    fun snakeCase(key: String): String {
        if (key.isEmpty()) return key
        val words = mutableListOf<String>()
        var wordStart = 0
        var search = 1
        while (true) {
            val upper = (search until key.length).firstOrNull { key[it].isUpperCase() } ?: break
            words += key.substring(wordStart, upper)
            val lower = (upper until key.length).firstOrNull { key[it].isLowerCase() }
            if (lower == null) {
                wordStart = upper
                search = key.length
                break
            }
            if (lower == upper + 1) {
                wordStart = upper
            } else {
                words += key.substring(upper, lower - 1)
                wordStart = lower - 1
            }
            search = lower + 1
        }
        words += key.substring(wordStart)
        return words.filter { it.isNotEmpty() }.joinToString("_") { it.lowercase() }
    }
}
