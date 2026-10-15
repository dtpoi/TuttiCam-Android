package com.thinktough.tutticam.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 列出主机与从机的全部消息，与 iOS 一一对应；时间单位是秒，ping 的 t0 用从机时钟，其余用主机时钟。 */
sealed interface Message {
    data class Hello(val info: DeviceInfo) : Message
    /** 通知从机它的序号和编号。 */
    data class Welcome(val index: Int, val code: DeviceCode) : Message
    /** 请求主机给这台从机单独指定镜头。 */
    data class LensRequest(val lens: LensConfig) : Message
    data class Rejected(val reason: String) : Message
    data class Settings(val settings: CaptureSettings) : Message
    data class Ping(val seq: Int, val t0: Double) : Message
    data class Pong(val seq: Int, val t0: Double, val t1: Double, val t2: Double) : Message
    data class Status(val status: DeviceStatus) : Message
    data class SyncQuality(val rttMs: Double, val errorMs: Double) : Message
    /** 开始拍摄或切换场景，session 是场景文件夹名。 */
    data class Start(val session: String) : Message
    /** 让各设备把场景文件夹改名。 */
    data class RenameFolder(val from: String, val to: String) : Message
    data object Stop : Message
    data class Schedule(val round: Int, val hostTime: Double) : Message
    data class Report(val report: RoundReport) : Message
    data class Resolve(val round: Int, val keep: Boolean) : Message
    /** 告诉从机主机的 UDP 端口。 */
    data class UdpPort(val port: Int) : Message
}

/** 包装 UDP 消息：p 标明发送方（主机为 "host"，从机为握手 key），m 是消息。 */
data class Datagram(val p: String, val m: Message)

/** 按 Swift JSONEncoder 的格式编解码消息：外层是 case 名，无标签参数叫 `_0`，未知字段忽略。 */
object WireJson {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(message: Message): String = json.encodeToString(JsonObject.serializer(), toJson(message))

    /** 解码一条消息，格式不对或消息未知时返回 null。 */
    fun decode(text: String): Message? = runCatching {
        fromJson(json.parseToJsonElement(text).jsonObject)
    }.getOrNull()

    fun encode(hello: LinkHello): String = json.encodeToString(LinkHello.serializer(), hello)

    fun decodeLinkHello(text: String): LinkHello? = runCatching {
        json.decodeFromString(LinkHello.serializer(), text)
    }.getOrNull()

    fun encode(datagram: Datagram): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("p", datagram.p)
        put("m", toJson(datagram.m))
    })

    fun decodeDatagram(text: String): Datagram? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        val message = fromJson(obj.getValue("m").jsonObject) ?: return null
        Datagram(obj.getValue("p").jsonPrimitive.content, message)
    }.getOrNull()

    // MARK: - 编码

    private fun toJson(message: Message): JsonObject = when (message) {
        is Message.Hello -> wrap("hello", unlabeled(json.encodeToJsonElement(DeviceInfo.serializer(), message.info)))
        is Message.Welcome -> wrap("welcome", buildJsonObject {
            put("index", message.index)
            put("code", json.encodeToJsonElement(DeviceCode.serializer(), message.code))
        })
        is Message.LensRequest -> wrap("lensRequest", unlabeled(json.encodeToJsonElement(LensConfig.serializer(), message.lens)))
        is Message.Rejected -> wrap("rejected", buildJsonObject { put("reason", message.reason) })
        is Message.Settings -> wrap("settings", unlabeled(json.encodeToJsonElement(CaptureSettings.serializer(), message.settings)))
        is Message.Ping -> wrap("ping", buildJsonObject {
            put("seq", message.seq)
            put("t0", message.t0)
        })
        is Message.Pong -> wrap("pong", buildJsonObject {
            put("seq", message.seq)
            put("t0", message.t0)
            put("t1", message.t1)
            put("t2", message.t2)
        })
        is Message.Status -> wrap("status", unlabeled(json.encodeToJsonElement(DeviceStatus.serializer(), message.status)))
        is Message.SyncQuality -> wrap("syncQuality", buildJsonObject {
            put("rttMs", message.rttMs)
            put("errorMs", message.errorMs)
        })
        is Message.Start -> wrap("start", buildJsonObject { put("session", message.session) })
        is Message.RenameFolder -> wrap("renameFolder", buildJsonObject {
            put("from", message.from)
            put("to", message.to)
        })
        Message.Stop -> wrap("stop", JsonObject(emptyMap()))
        is Message.Schedule -> wrap("schedule", buildJsonObject {
            put("round", message.round)
            put("hostTime", message.hostTime)
        })
        is Message.Report -> wrap("report", unlabeled(json.encodeToJsonElement(RoundReport.serializer(), message.report)))
        is Message.Resolve -> wrap("resolve", buildJsonObject {
            put("round", message.round)
            put("keep", message.keep)
        })
        is Message.UdpPort -> wrap("udpPort", unlabeled(JsonPrimitive(message.port)))
    }

    private fun wrap(case: String, body: JsonObject) = JsonObject(mapOf(case to body))

    private fun unlabeled(value: JsonElement) = JsonObject(mapOf("_0" to value))

    // MARK: - 解码

    private fun fromJson(obj: JsonObject): Message? {
        val (case, value) = obj.entries.singleOrNull() ?: return null
        val body = value.jsonObject
        fun field(name: String) = body.getValue(name).jsonPrimitive
        fun only() = body.getValue("_0")
        return when (case) {
            "hello" -> Message.Hello(json.decodeFromJsonElement(DeviceInfo.serializer(), only()))
            "welcome" -> Message.Welcome(field("index").int, json.decodeFromJsonElement(DeviceCode.serializer(), body.getValue("code")))
            "lensRequest" -> Message.LensRequest(json.decodeFromJsonElement(LensConfig.serializer(), only()))
            "rejected" -> Message.Rejected(field("reason").content)
            "settings" -> Message.Settings(json.decodeFromJsonElement(CaptureSettings.serializer(), only()))
            "ping" -> Message.Ping(field("seq").int, field("t0").double)
            "pong" -> Message.Pong(field("seq").int, field("t0").double, field("t1").double, field("t2").double)
            "status" -> Message.Status(json.decodeFromJsonElement(DeviceStatus.serializer(), only()))
            "syncQuality" -> Message.SyncQuality(field("rttMs").double, field("errorMs").double)
            "start" -> Message.Start(field("session").content)
            "renameFolder" -> Message.RenameFolder(field("from").content, field("to").content)
            "stop" -> Message.Stop
            "schedule" -> Message.Schedule(field("round").int, field("hostTime").double)
            "report" -> Message.Report(json.decodeFromJsonElement(RoundReport.serializer(), only()))
            "resolve" -> Message.Resolve(field("round").int, field("keep").boolean)
            "udpPort" -> Message.UdpPort(only().jsonPrimitive.int)
            else -> null
        }
    }
}
