package com.thinktough.tutticam.storage

import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.DeviceInfo
import com.thinktough.tutticam.protocol.LensKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查记录文件的字段名与 iOS 的 convertToSnakeCase 完全一致。 */
class RecordJsonTest {
    @Test
    fun snakeCaseMatchesSwift() {
        // 右边是 Swift JSONEncoder 实际输出的结果。
        val swift = mapOf(
            "writePhotoJSON" to "write_photo_json", "prefer48MP" to "prefer48_mp", "focalPxEstimate" to "focal_px_estimate",
            "deviceId" to "device_id", "rttMs" to "rtt_ms", "lensOverrides" to "lens_overrides", "manualISO" to "manual_iso",
            "round" to "round", "osmoLead" to "osmo_lead", "syncRttMs" to "sync_rtt_ms", "whiteBalanceKelvin" to "white_balance_kelvin",
            "intrinsicsReference" to "intrinsics_reference", "squareFront" to "square_front", "URLValue" to "u_rl_value",
            "aB" to "a_b", "isHDR10Mode" to "is_hdr10_mode",
        )
        for ((key, expected) in swift) assertEquals(key, expected, RecordJson.snakeCase(key))
    }

    @Test
    fun sessionRecordUsesSnakeCaseSortedKeysAndOmitsNulls() {
        val record = SessionRecord(
            session = "场景01_20261014_101500", deviceId = 2, deviceCode = "A02",
            device = DeviceInfo("S24", "Samsung SM-S9210", "Android 16", listOf(LensKind.Wide), emptyList(), squareFront = false),
            isHost = false, startedAt = "2026-10-14T02:15:00Z", settings = CaptureSettings(lensOverrides = mapOf(3 to com.thinktough.tutticam.protocol.LensConfig(LensKind.Front))),
        )
        val text = RecordJson.encode(SessionRecord.serializer(), record)
        assertTrue(text.contains("\"device_code\" : \"A02\"") || text.contains("\"device_code\": \"A02\""))
        assertTrue(text.contains("\"write_photo_json\""))
        assertTrue(text.contains("\"prefer48_mp\""))
        assertTrue(text.contains("\"lens_overrides\""))
        assertFalse("空的可选字段应省略", text.contains("sync_rtt_ms"))
        // 检查键已排序。
        val top = Regex("^  \"([a-z_0-9]+)\"", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(top.sorted(), top)
    }
}
