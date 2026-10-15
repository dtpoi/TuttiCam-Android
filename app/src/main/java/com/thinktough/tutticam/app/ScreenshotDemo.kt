package com.thinktough.tutticam.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.thinktough.tutticam.BuildConfig
import com.thinktough.tutticam.accessory.AccessoryHub
import com.thinktough.tutticam.accessory.AccessoryPhase
import com.thinktough.tutticam.camera.CameraEngine
import com.thinktough.tutticam.engine.DeviceEntry
import com.thinktough.tutticam.engine.MotionMonitor
import com.thinktough.tutticam.engine.RoundSummary
import com.thinktough.tutticam.net.PeerId
import com.thinktough.tutticam.protocol.AccessoryBrief
import com.thinktough.tutticam.protocol.CameraBrand
import com.thinktough.tutticam.protocol.DeviceCode
import com.thinktough.tutticam.protocol.DeviceInfo
import com.thinktough.tutticam.protocol.DeviceStatus
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.LensKind
import com.thinktough.tutticam.protocol.accessory.AccessoryStatus
import com.thinktough.tutticam.protocol.accessory.CameraKind
import com.thinktough.tutticam.protocol.accessory.OsmoCommand
import java.io.File
import kotlin.math.roundToInt

/**
 * 提供截图演示模式，只在调试版生效：不开相机，取景框显示参考照片，主机模拟 3 台从机、外接相机和第 12 组的状态。
 * 用法见 docs/SCREENSHOTS.md。
 */
object ScreenshotDemo {
    /** 表示演示模式已打开。 */
    var isOn = false
        private set

    /** 记录启动时要打开的页面：settings、devices 或 accessories。 */
    var sheet: String? = null
        private set

    /** 记录演示用的镜头组合，如 “w” 或 “w,uw”。 */
    var lens: LensConfig = LensConfig(LensKind.Wide)
        private set

    private var folder: File? = null
    private val images = mutableMapOf<LensKind, ImageBitmap?>()
    private val lumas = mutableMapOf<LensKind, Double>()

    /** 按启动参数打开演示模式：screenshot 打开，sheet 选页面，lens 选镜头。 */
    fun enable(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra("screenshot", false)) return
        isOn = true
        folder = context.getExternalFilesDir("screenshot")
        sheet = intent.getStringExtra("sheet")
        intent.getStringExtra("lens")?.split(",")?.mapNotNull { code -> LensKind.entries.firstOrNull { it.code == code.trim() } }
            ?.takeIf { it.isNotEmpty() }?.let { lens = LensConfig(it[0], it.getOrNull(1)) }
    }

    // MARK: 取景画面

    /** 返回这颗镜头的取景照片，没有照片时返回 null。 */
    fun image(lens: CameraEngine.LensInfo, all: List<CameraEngine.LensInfo>): ImageBitmap? =
        images.getOrPut(lens.kind) { load(lens, all)?.also { lumas[lens.kind] = topLuma(it) }?.asImageBitmap() }

    /** 返回照片上沿那一条的平均亮度，用来选状态文字的颜色。 */
    fun topLuminance(kind: LensKind): Double? = lumas[kind]

    private val fileNames = mapOf(LensKind.UltraWide to "ultrawide", LensKind.Wide to "wide", LensKind.Telephoto to "telephoto", LensKind.Front to "front")

    /** 优先用同名照片；后置镜头没有时从 scene 中间按相对最广镜头的倍率裁一块。 */
    private fun load(lens: CameraEngine.LensInfo, all: List<CameraEngine.LensInfo>): Bitmap? {
        file(fileNames.getValue(lens.kind))?.let { return render(it, 1.0) }
        if (lens.kind == LensKind.Front) return null
        val widest = all.filter { it.kind != LensKind.Front }.minOfOrNull { it.equivalentMm } ?: return null
        return file("scene")?.let { render(it, lens.equivalentMm.toDouble() / widest) }
    }

    private fun file(name: String): Bitmap? {
        val dir = folder ?: return null
        return listOf("jpg", "jpeg", "png").firstNotNullOfOrNull { ext ->
            File(dir, "$name.$ext").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
    }

    /** 从中间裁出竖幅 3:4 并放大 zoom 倍，长边缩到 2400 像素以内。 */
    private fun render(source: Bitmap, zoom: Double): Bitmap {
        val cropWidth = (minOf(source.width.toDouble(), source.height * 3.0 / 4) / zoom).toInt().coerceIn(1, source.width)
        val cropHeight = (cropWidth * 4.0 / 3).toInt().coerceIn(1, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - cropWidth) / 2, (source.height - cropHeight) / 2, cropWidth, cropHeight)
        val scale = minOf(1.0, 2400.0 / cropHeight)
        return if (scale >= 1) cropped else Bitmap.createScaledBitmap(cropped, (cropWidth * scale).roundToInt(), (cropHeight * scale).roundToInt(), true)
    }

    private fun topLuma(bitmap: Bitmap): Double {
        val band = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, maxOf(1, (bitmap.height * 0.14).roundToInt())), 16, 4, true)
        var sum = 0.0
        for (x in 0 until 16) for (y in 0 until 4) {
            val c = band.getPixel(x, y)
            sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        return sum / 64 / 255
    }

    // MARK: 本机

    const val HOST_NAME = "Galaxy S24"
    const val HOST_MODEL = "Samsung SM-S9210"
    const val ROUNDS = 12
    const val OFFSET_MS = 0.4
    val snapshot = MotionMonitor.Snapshot(still = true, blurPx = 0.42, gravity = null)

    /** 返回演示用的实时曝光参数。 */
    fun live(lens: CameraEngine.LensInfo?) = CameraEngine.Live(shutter = 1.0 / 125, iso = 100f, kelvin = 5600f, tint = 3f,
        aperture = lens?.aperture ?: 1.8f, focus = 0.62f, manualFocusSupported = true)

    /** 返回本机连着的 Osmo 360。 */
    val osmo = AccessoryHub.Camera(id = "demo-osmo", kind = CameraKind.DjiR, name = "Osmo360-624C", number = 1,
        phase = AccessoryPhase.Connected("Osmo 360"),
        status = AccessoryStatus(battery = 87, remainPhotos = 1234, djiMode = OsmoCommand.Mode.PANORAMA_PHOTO, photoSize = "30MP"),
        lastRttMs = 79.0, code = DeviceCode("D", 1))

    fun hostStatus(lens: LensConfig?, accessories: List<AccessoryBrief>) = DeviceStatus(still = true, blurPx = snapshot.blurPx,
        ready = true, cameraReady = true, lens = lens, battery = 0.82f, freeBytes = 112_000_000_000, thermal = 0, accessories = accessories)

    // MARK: 其他设备

    /** 返回 3 台从机：一台还在动、一台电量低、一台连着 GoPro。 */
    fun peers(now: Double): List<DeviceEntry> = listOf(
        peer(2, "iPhone 16 Pro", "iPhone17,1", "26.0", LensConfig(LensKind.UltraWide), 0.64f, 38_000_000_000, 0.61, true, 0.4, 3.1, now,
            listOf(AccessoryBrief(name = "GoPro HERO13 Black", number = 1, code = "G01", brand = CameraBrand.GoPro.raw, connected = true,
                battery = 76, remainPhotos = 412, capturing = false, tooHot = false))),
        peer(3, "Pixel 9 Pro", "Google Pixel 9 Pro", "Android 16", LensConfig(LensKind.Wide), 0.19f, 210_000_000_000, 1.84, false, 0.7, 4.6, now),
        peer(4, "Galaxy S23", "Samsung SM-S9110", "Android 15", LensConfig(LensKind.Telephoto), 0.91f, 64_000_000_000, 0.38, true, 0.3, 2.8, now),
    )

    private fun peer(index: Int, name: String, model: String, system: String, lens: LensConfig, battery: Float, free: Long, blur: Double,
                     still: Boolean, errorMs: Double, rttMs: Double, now: Double, accessories: List<AccessoryBrief> = emptyList()) =
        DeviceEntry(name = name, index = index, code = DeviceCode("A", index), peer = PeerId("demo-$index", name),
            info = DeviceInfo(name, model, system, listOf(LensKind.UltraWide, LensKind.Wide, LensKind.Telephoto, LensKind.Front), emptyList()),
            status = DeviceStatus(still = still, blurPx = blur, ready = true, cameraReady = true, lens = lens, battery = battery,
                freeBytes = free, thermal = 0, accessories = accessories),
            statusTime = now, rttMs = rttMs, errorMs = errorMs)

    /** 返回设备页“最近几组”。 */
    val log: List<RoundSummary> = (ROUNDS downTo ROUNDS - 4).map { round ->
        RoundSummary(round = round, participants = 4, photos = 4, missed = 0, moving = if (round == ROUNDS) 1 else 0,
            maxOffsetMs = listOf(0.6, 0.4, 0.9, 0.5, 0.7)[round % 5], kept = null)
    }
}
