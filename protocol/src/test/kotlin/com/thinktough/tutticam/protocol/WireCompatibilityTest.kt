package com.thinktough.tutticam.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 检查与 iOS 的报文兼容：ios-golden.tsv 里每条都要能解码，再编码后与原文语义相同。
 * 安卓编出的报文写到 build/android-encoded.tsv，可用 `golden decode` 让 iOS 反向解码。
 */
class WireCompatibilityTest {
    private val golden: List<Pair<String, String>> by lazy {
        val text = javaClass.getResource("/ios-golden.tsv")?.readText() ?: fail("缺少 ios-golden.tsv")
        text.lines().filter { it.isNotBlank() }.map { line ->
            val tab = line.indexOf('\t')
            line.substring(0, tab) to line.substring(tab + 1)
        }
    }

    private val androidEncoded = mutableListOf<Pair<String, String>>()

    @Test
    fun everyIosMessageRoundTrips() {
        for ((name, json) in golden) {
            val again = when {
                name.startsWith("linkHello") -> WireJson.decodeLinkHello(json)?.let { WireJson.encode(it) }
                name.startsWith("datagram") -> WireJson.decodeDatagram(json)?.let { WireJson.encode(it) }
                else -> WireJson.decode(json)?.let { WireJson.encode(it) }
            }
            assertNotNull(again, "Android 解码失败：$name")
            assertSameJson(parse(json), parse(again), name)
            androidEncoded += name to again
        }
        writeAndroidEncoded()
    }

    @Test
    fun defaultSettingsHaveEveryFieldIosRequires() {
        val ios = parse(golden.first { it.first == "settingsDefault" }.second).jsonObject.getValue("settings").jsonObject.getValue("_0").jsonObject
        val android = parse(WireJson.encode(Message.Settings(CaptureSettings()))).jsonObject.getValue("settings").jsonObject.getValue("_0").jsonObject
        assertEquals(ios.keys, android.keys, "设置字段与 iOS 不一致")
        assertSameJson(ios, android, "settingsDefault")
    }

    @Test
    fun androidOnlyMessagesEncodeLikeSwift() {
        val status = Message.Status(DeviceStatus(cameraError = "相机未就绪"))
        val json = parse(WireJson.encode(status)).jsonObject.getValue("status").jsonObject.getValue("_0").jsonObject
        // 检查可选字段为空时省略，非可选字段照写。
        assertTrue("lens" !in json && "cameraError" in json && "accessories" in json && "thermal" in json)
        assertEquals("""{"stop":{}}""", WireJson.encode(Message.Stop))
        assertEquals("""{"udpPort":{"_0":54321}}""", WireJson.encode(Message.UdpPort(54321)))
        val hello = WireJson.encode(LinkHello(key = "K", name = "S24", v = 1))
        androidEncoded += "linkHelloAndroid" to hello
        androidEncoded += "helloAndroid" to WireJson.encode(Message.Hello(DeviceInfo("Galaxy S24", "SM-S9210", "16",
            listOf(LensKind.UltraWide, LensKind.Wide, LensKind.Telephoto, LensKind.Front), emptyList())))
        androidEncoded += "statusAndroid" to WireJson.encode(status)
        androidEncoded += "settingsAndroid" to WireJson.encode(Message.Settings(CaptureSettings(
            lensOverrides = mapOf(3 to LensConfig(LensKind.Front)), iso = 250f, exposureBias = -0.7f,
            frontFraming = FrontFraming.Tall43, accessoryLeads = mapOf("sony" to 0.12))))
        androidEncoded += "reportAndroid" to WireJson.encode(Message.Report(RoundReport(5, 2, false,
            listOf(PhotoReport(LensKind.Wide, "r0005_A02_w.jpg", offsetMs = -0.3, moving = false, blurPx = 0.2)))))
        androidEncoded += "datagramAndroid" to WireJson.encode(Datagram("K", Message.Ping(1, 12.5)))
        writeAndroidEncoded()
    }

    @Test
    fun legacyFrontFramingValuesDecode() {
        val settings = (WireJson.decode(golden.first { it.first == "settingsCustom" }.second) as Message.Settings).settings
        assertEquals(FrontFraming.Wide43, settings.frontFraming)
        assertEquals(LensConfig(LensKind.Front), settings.lensOverrides[2])
        assertEquals(FrontFraming.Wide43, FrontFraming.parse("wide"))
        assertEquals(FrontFraming.Square, FrontFraming.parse("something-new"))
    }

    @Test
    fun photoFormatsRoundTripAndUnknownFallsBack() {
        for (format in PhotoFormat.entries) {
            val json = WireJson.encode(Message.Settings(CaptureSettings(format = format)))
            assertEquals(format, (WireJson.decode(json) as Message.Settings).settings.format)
        }
        val future = WireJson.encode(Message.Settings(CaptureSettings())).replace("\"format\":\"jpeg\"", "\"format\":\"proRaw\"")
        assertEquals(PhotoFormat.Jpeg, (WireJson.decode(future) as Message.Settings).settings.format, "不认识的格式按 JPEG，整条设置照样能解")
        assertEquals(PhotoFormat.RawJpeg, PhotoFormat.Raw.forIos)
        assertEquals(PhotoFormat.Heic, PhotoFormat.RawHeic.withoutRaw)
    }

    @Test
    fun unknownFieldsAndMessagesAreTolerated() {
        val withExtra = """{"ping":{"seq":1,"t0":2,"future":true}}"""
        assertEquals(Message.Ping(1, 2.0), WireJson.decode(withExtra))
        assertEquals(null, WireJson.decode("""{"somethingNew":{}}"""))
        assertEquals(null, WireJson.decode("not json"))
    }

    private fun writeAndroidEncoded() {
        val path = System.getProperty("androidEncodedOut") ?: return
        val file = File(path)
        file.parentFile.mkdirs()
        val existing = if (file.exists()) file.readLines().filter { it.isNotBlank() }.associate {
            it.substringBefore('\t') to it.substringAfter('\t')
        } else emptyMap()
        val merged = existing + androidEncoded
        file.writeText(merged.entries.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" })
    }

    private fun parse(text: String): JsonElement = WireJson.json.parseToJsonElement(text)

    private fun assertSameJson(expected: JsonElement, actual: JsonElement, path: String) {
        when (expected) {
            is JsonObject -> {
                val other = actual as? JsonObject ?: fail("$path：应为对象")
                assertEquals(expected.keys, other.keys, "$path：键不同")
                for (key in expected.keys) assertSameJson(expected.getValue(key), other.getValue(key), "$path.$key")
            }
            is JsonArray -> {
                val other = actual as? JsonArray ?: fail("$path：应为数组")
                assertEquals(expected.size, other.size, "$path：长度不同")
                expected.indices.forEach { assertSameJson(expected[it], other[it], "$path[$it]") }
            }
            is JsonPrimitive -> {
                val other = actual as? JsonPrimitive ?: fail("$path：应为基本值")
                val a = expected.doubleOrNull
                val b = other.doubleOrNull
                if (!expected.isString && a != null && b != null) {
                    assertTrue(abs(a - b) <= 1e-6 * maxOf(1.0, abs(a)), "$path：$a ≠ $b")
                } else {
                    assertEquals(expected.content, other.content, "$path")
                    assertEquals(expected.isString, other.isString, "$path：类型不同")
                }
            }
            JsonNull -> assertEquals(JsonNull, actual, path)
        }
    }
}
