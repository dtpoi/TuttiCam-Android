package com.thinktough.tutticam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraDevice.StateCallback
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import com.thinktough.tutticam.R
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.LensKind
import com.thinktough.tutticam.protocol.QualityMode
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 驱动 Camera2：连续出全尺寸 YUV 帧，拍摄时取曝光时刻离约定时刻最近的一帧（见 docs/DESIGN.md 6.4）。
 * 两颗后置走逻辑多摄的物理子镜头，后置加前置走并发相机；CameraUnit 是一台相机，Stream 是一路画面。
 * 单镜头支持 RAW 时多开一路 RAW_SENSOR，并按传感器时间戳配上同一次曝光的 RAW。
 * 相机操作在 camera 线程，取帧在 image 线程，交回帧在 encode 线程。
 */
class CameraEngine(context: Context) {
    data class LensInfo(
        val kind: LensKind,
        val cameraId: String,
        val facing: Int,
        val sensorOrientation: Int,
        val focalMm: Float,
        val equivalentMm: Int,
        val aperture: Float,
        val yuvSize: Size,
        val previewSize: Size,
        /** 给出按出片宽度换算的焦距（像素），用来估算模糊。 */
        val focalPx: Double,
        val exposureRange: Range<Long>?,
        val isoRange: Range<Int>?,
        val manualSensor: Boolean,
        /** 表示帧时间戳与 elapsedRealtimeNanos 同一时基。 */
        val realtimeTimestamps: Boolean,
        /** 记录最近对焦距离（屈光度），0 表示定焦。 */
        val minFocusDiopters: Float,
        val compensationStep: Rational,
        val compensationRange: Range<Int>,
        val fpsRange: Range<Int>?,
        val whiteBalance: WhiteBalance,
        /** 记录切到这颗镜头要设的变焦比例，用于没有独立编号的长焦，1 表示不变焦。 */
        val zoomRatio: Float = 1f,
        /** 记录逻辑多摄里的物理子镜头编号，两颗后置同时拍时用。 */
        val physicalId: String? = null,
        /** 记录 RAW_SENSOR 尺寸，镜头不支持 RAW 时为空。 */
        val rawSize: Size? = null,
    ) {
        /** 给出竖拿时画面的高宽比。 */
        val portraitAspect: Double get() = maxOf(yuvSize.width, yuvSize.height).toDouble() / minOf(yuvSize.width, yuvSize.height)
    }

    /** 描述多镜头组合的打开方式。 */
    sealed interface DualPlan {
        val lenses: List<LensInfo>

        /** 打开一台逻辑多摄的两颗物理子镜头。 */
        data class Physical(val logicalId: String, override val lenses: List<LensInfo>) : DualPlan

        /** 并发打开两台相机。 */
        data class Concurrent(override val lenses: List<LensInfo>) : DualPlan
    }

    /** 记录实时曝光参数，供参数条显示。 */
    data class Live(
        val shutter: Double,
        val iso: Float,
        val kelvin: Float,
        val tint: Float,
        val aperture: Float,
        /** 记录对焦位置，0 最近，1 无穷远。 */
        val focus: Float,
        val manualFocusSupported: Boolean,
    )

    class Frame(
        /** 保存传感器方向的 NV21 画面，由调用方编码。 */
        val nv21: ByteArray,
        /** 记录曝光开始时刻（elapsedRealtimeNanos）。 */
        val timestampNanos: Long,
        val exposureNanos: Long?,
        val iso: Int?,
        val width: Int,
        val height: Int,
        val lens: LensInfo,
        /** 保存同一次曝光的 RAW，没要或没取到时为空。 */
        val raw: RawData?,
        /** 说明要了 RAW 却没取到的原因。 */
        val rawProblem: String?,
    )

    /** 保存紧密排列的 RAW16 数据，以及写 DNG 要用的相机参数和拍摄结果。 */
    class RawData(val pixels: ByteBuffer, val size: Size, val characteristics: CameraCharacteristics, val result: CaptureResult)

    private class Pending(val targetNanos: Long, val raw: Boolean, val done: (Frame?, String?) -> Unit)

    /** 收集选中的一帧，等 RAW 和拍摄结果到齐后交回。 */
    private inner class Shot(val stream: Stream, val pending: Pending, val nv21: ByteArray, val width: Int, val height: Int,
                             val timestampNanos: Long, val sensorTimestamp: Long) {
        var raw: ByteBuffer? = null
        var rawSize: Size? = null
        var rawProblem: String? = null

        fun finish() = encoder.execute {
            // 拍摄结果常比图像晚到，写 DNG 必须有它，所以多等一会儿。
            val result = awaitResult(stream, sensorTimestamp, if (raw != null) 500 else 150)
            val pixels = raw
            val size = rawSize
            val rawData = if (pixels != null && size != null && result != null) RawData(pixels, size, stream.characteristics, result) else null
            val problem = rawProblem ?: if (pixels != null && rawData == null) text(R.string.raw_missing) else null
            pending.done(Frame(nv21, timestampNanos, result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result?.get(CaptureResult.SENSOR_SENSITIVITY), width, height, stream.lens, rawData, problem), null)
        }
    }

    /** 管理一颗镜头的预览和连续 YUV 帧。 */
    private inner class Stream(val lens: LensInfo) {
        var reader: ImageReader? = null
        @Volatile var rawReader: ImageReader? = null
        /** 暂存最近的 RAW，等同一次曝光的 YUV 帧；只在 image 线程读写。 */
        val rawRecent = ArrayDeque<Image>()
        /** 指向正在等 RAW 的选中帧；只在 image 线程读写。 */
        var rawWait: Shot? = null
        val characteristics: CameraCharacteristics by lazy { manager.getCameraCharacteristics(lens.physicalId ?: lens.cameraId) }
        var activePreview: Surface? = null
        @Volatile var pending: Pending? = null
        @Volatile var lastFrameNanos = 0L
        @Volatile var framePeriodNanos = 33_333_333L
        @Volatile var live: Live? = null
        /** 把非 REALTIME 时基的帧时间戳换算到 elapsedRealtimeNanos。 */
        val timeOffsetNanos = if (lens.realtimeTimestamps) 0L else android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        /** 按传感器时间戳缓存最近的拍摄结果，物理子镜头存自己的结果。 */
        val results = object : LinkedHashMap<Long, CaptureResult>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CaptureResult>?) = size > 60
        }
    }

    /** 管理一台打开的相机及它的一路或两路画面。 */
    private inner class CameraUnit(val cameraId: String, val streams: List<Stream>) {
        val physical: Boolean get() = streams.any { it.lens.physicalId != null }
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var sessionGeneration = 0
        @Volatile var lastAuto: TotalCaptureResult? = null
        /** 表示当前会话带 RAW 输出。 */
        @Volatile var rawConfigured = false
        /** 标记系统拒绝了带 RAW 的会话，这次打开期间不再尝试。 */
        var rawRejected = false
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("tutticam.camera").apply { start() }
    private val camera = Handler(cameraThread.looper)
    private val imageThread = HandlerThread("tutticam.image").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val encoder = Executors.newSingleThreadExecutor { Thread(it, "tutticam.encode").apply { isDaemon = true } }
    private val cameraExecutor = Executor { camera.post(it) }
    private val resources = context.applicationContext.resources
    /** 记录打开相机的代数，只在 camera 线程读写。 */
    private var generation = 0

    private fun text(id: Int, vararg args: Any): String = resources.getString(id, *args)

    /** 列出单镜头可用的镜头。 */
    val lenses: List<LensInfo>
    /** 按镜头集合查多镜头组合的打开方式。 */
    val dualPlans: Map<Set<LensKind>, DualPlan>

    init {
        val probed = runCatching { probe() }.getOrElse {
            Log.w(TAG, "相机能力探测失败", it)
            emptyList<LensInfo>() to emptyMap()
        }
        lenses = probed.first
        dualPlans = probed.second
    }

    /** 列出多镜头组合，主镜头在前。 */
    val dualCombos: List<LensConfig>
        get() = DUAL_ORDER.mapNotNull { (a, b) -> if (setOf(a, b) in dualPlans) LensConfig(a, b) else null }

    /** 返回镜头组合实际用到的各路画面，界面据此摆预览。 */
    fun streamLenses(config: LensConfig): List<LensInfo> {
        if (config.isDual) {
            val plan = dualPlans[config.lenses.toSet()]
            if (plan != null) return config.lenses.mapNotNull { kind -> plan.lenses.firstOrNull { it.kind == kind } }
        }
        return listOfNotNull(lenses.firstOrNull { it.kind == config.primary } ?: lenses.firstOrNull())
    }

    @Volatile private var units: List<CameraUnit> = emptyList()
    /** 让连续出帧请求带上 RAW 输出，只在拍摄期间打开；只在 camera 线程读写。 */
    private var rawArmed = false
    /** 按镜头保存界面给的预览 Surface 和尺寸；只在 camera 线程读写。 */
    private val previewSurfaces = mutableMapOf<LensKind, Pair<Surface, Size>>()
    private var settings = CaptureSettings()
    private var manualFocus: Map<LensKind, Float> = emptyMap()
    @Volatile private var streams: List<Stream> = emptyList()
    @Volatile private var lastLumaNanos = 0L
    /** 记录屏幕旋转角，用来找状态文字背后的画面区域。 */
    @Volatile var displayRotation = 0
    private var onLuma: ((Double) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** 列出当前各路镜头，主镜头在前。 */
    val activeLenses: List<LensInfo> get() = streams.map { it.lens }
    val active: LensInfo? get() = streams.firstOrNull()?.lens
    /** 返回主镜头的实时参数。 */
    val live: Live? get() = streams.firstOrNull()?.live

    /** 返回指定镜头的实时参数。 */
    fun live(kind: LensKind): Live? = streams.firstOrNull { it.lens.kind == kind }?.live

    /** 表示当前会话在出 RAW。 */
    val rawActive: Boolean get() = units.singleOrNull()?.rawConfigured == true

    /** 返回当前曝光时长（秒），供静止判定使用。 */
    val exposureSeconds: Double get() = live?.shutter ?: (1.0 / 60)

    // MARK: - 能力探测

    private fun probe(): Pair<List<LensInfo>, Map<Set<LensKind>, DualPlan>> {
        val list = mutableListOf<LensInfo>()
        /** 记录第一颗后置的等效焦距，其余后置按与它的比例分类。 */
        var mainEquivalent: Int? = null
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            val facing = c.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing == CameraMetadata.LENS_FACING_EXTERNAL) continue
            val equivalent = equivalent(c) ?: continue
            // 按焦距比例分类，因为部分机型报的传感器尺寸不准。
            val main = if (facing == CameraMetadata.LENS_FACING_BACK) mainEquivalent ?: equivalent.also { mainEquivalent = it } else null
            val kind = when {
                facing == CameraMetadata.LENS_FACING_FRONT -> LensKind.Front
                main == null -> continue
                else -> classify(equivalent, main)
            }
            if (list.any { it.kind == kind }) continue // 同种镜头只取第一颗
            val info = lensInfo(kind, id, c, null, 1920) ?: continue
            list += info
            Log.i(TAG, "镜头 $kind：相机 $id，等效 ${info.equivalentMm}mm，出片 ${info.yuvSize}，预览 ${info.previewSize}，" +
                "RAW ${info.rawSize}，手动=${info.manualSensor} 时间戳REALTIME=${info.realtimeTimestamps}")
        }
        zoomTelephoto(list)?.let { list += it }

        val plans = mutableMapOf<Set<LensKind>, DualPlan>()
        // 两两组合逻辑多摄的物理子镜头，打开时再确认能否同时出画面。
        val main = mainEquivalent
        if (main != null) {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                val physical = runCatching { c.physicalCameraIds }.getOrDefault(emptySet())
                if (physical.size < 2) continue
                val subs = physical.mapNotNull { pid ->
                    runCatching {
                        val pc = manager.getCameraCharacteristics(pid)
                        val eq = equivalent(pc) ?: return@runCatching null
                        // 两路同时出画面，预览用小一档的尺寸。
                        lensInfo(classify(eq, main), id, pc, pid, 1440)
                    }.getOrNull()
                }.distinctBy { it.kind }
                for (i in subs.indices) for (j in subs.indices) {
                    if (i < j) plans.getOrPut(setOf(subs[i].kind, subs[j].kind)) { DualPlan.Physical(id, listOf(subs[i], subs[j])) }
                }
                Log.i(TAG, "逻辑多摄 $id：物理子镜头 ${subs.joinToString { "${it.physicalId}=${it.kind}" }}")
            }
        }
        // 组合并发相机里的后置和前置。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (set in runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())) {
                val back = list.filter { it.cameraId in set && it.facing == CameraMetadata.LENS_FACING_BACK }
                val front = list.filter { it.cameraId in set && it.facing == CameraMetadata.LENS_FACING_FRONT }
                for (b in back) for (f in front) {
                    plans.getOrPut(setOf(b.kind, f.kind)) { DualPlan.Concurrent(listOf(b, f)) }
                }
            }
        }
        Log.i(TAG, "多镜头组合：${plans.entries.joinToString { (k, v) -> "$k=${v.javaClass.simpleName}" }}")
        return list.sortedBy { it.kind.ordinal } to plans
    }

    private fun equivalent(c: CameraCharacteristics): Int? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return (focal * 43.27 / hypot(sensor.width, sensor.height)).roundToInt()
    }

    private fun classify(equivalent: Int, main: Int) = when {
        equivalent < main * 0.75 -> LensKind.UltraWide
        equivalent > main * 1.6 -> LensKind.Telephoto
        else -> LensKind.Wide
    }

    private fun lensInfo(kind: LensKind, cameraId: String, c: CameraCharacteristics, physicalId: String?, previewMax: Int): LensInfo? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val yuv = pick43(map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null, Int.MAX_VALUE) ?: return null
        val preview = pick43(map.getOutputSizes(android.view.SurfaceHolder::class.java) ?: return null, previewMax) ?: return null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val raw = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        } else null
        return LensInfo(
            kind = kind,
            cameraId = cameraId,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_BACK,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            focalMm = focal,
            equivalentMm = (focal * 43.27 / hypot(sensor.width, sensor.height)).roundToInt(),
            aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f,
            yuvSize = yuv,
            previewSize = preview,
            focalPx = focal.toDouble() / sensor.width * maxOf(yuv.width, yuv.height),
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            realtimeTimestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            compensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3),
            compensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            fpsRange = fps?.filter { it.lower == it.upper && it.upper <= 30 }?.maxByOrNull { it.upper }
                ?: fps?.filter { it.upper == 30 }?.maxByOrNull { it.lower },
            whiteBalance = WhiteBalance(c),
            physicalId = physicalId,
            rawSize = raw,
        )
    }

    /** 在主摄上用 CONTROL_ZOOM_RATIO 补出长焦，倍率取物理子镜头的最长焦距，读不到时用 3x。 */
    private fun zoomTelephoto(list: List<LensInfo>): LensInfo? {
        if (list.any { it.kind == LensKind.Telephoto } || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val main = list.firstOrNull { it.kind == LensKind.Wide } ?: return null
        val c = manager.getCameraCharacteristics(main.cameraId)
        val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return null
        val physical = runCatching { c.physicalCameraIds }.getOrDefault(emptySet())
        // 读物理子镜头的等效焦距，读不到就跳过。
        val longest = physical.mapNotNull { id ->
            runCatching {
                val pc = manager.getCameraCharacteristics(id)
                val f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return@runCatching null
                val size = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
                Triple(id, f, f * 43.27 / hypot(size.width, size.height))
            }.getOrNull()
        }.maxByOrNull { it.third }
        Log.i(TAG, "主摄 ${main.cameraId} 变焦范围 $range，物理子镜头 $physical，最长 ${longest?.let { "${it.first}：${it.third.roundToInt()}mm" }}")
        val zoom = longest?.takeIf { it.third > main.equivalentMm * 1.6 }?.let { (it.third / main.equivalentMm).roundToInt().toFloat() } ?: 3f
        if (range.upper < zoom) return null
        return main.copy(
            kind = LensKind.Telephoto,
            focalMm = longest?.second ?: main.focalMm * zoom,
            equivalentMm = (main.equivalentMm * zoom).roundToInt(),
            focalPx = main.focalPx * zoom,
            zoomRatio = zoom,
            // 不出 RAW，因为变焦后 RAW 来自哪颗传感器无法确定。
            rawSize = null,
        ).also { Log.i(TAG, "镜头 Telephoto：相机 ${main.cameraId} 变焦 ${zoom}x，等效 ${it.equivalentMm}mm") }
    }

    /** 选出长边不超过 maxLong 的最大 4:3 尺寸，没有 4:3 时取最大的。 */
    private fun pick43(sizes: Array<Size>, maxLong: Int): Size? {
        val fit = sizes.filter { maxOf(it.width, it.height) <= maxLong }
        return fit.filter { abs(it.width.toDouble() / it.height - 4.0 / 3) < 0.01 }.maxByOrNull { it.width.toLong() * it.height }
            ?: fit.maxByOrNull { it.width.toLong() * it.height }
    }

    // MARK: - 打开与会话

    fun setOnError(handler: (String) -> Unit) {
        onError = handler
    }

    /** 每秒约 4 次回报画面上沿亮度，只在单镜头时回调，在 image 线程。 */
    fun setOnLuma(handler: ((Double) -> Unit)?) {
        onLuma = handler
    }

    /** 打开镜头组合，done 在 camera 线程回调，参数为空表示成功，否则是错误说明。 */
    fun open(config: LensConfig, settings: CaptureSettings, done: (String?) -> Unit) = camera.post {
        closeNow()
        this.settings = settings
        val plan = if (config.isDual) dualPlans[config.lenses.toSet()] else null
        val opened: List<CameraUnit> = when {
            plan is DualPlan.Physical -> listOf(CameraUnit(plan.logicalId, streamLenses(config).map { Stream(it) }))
            plan is DualPlan.Concurrent -> streamLenses(config).map { CameraUnit(it.cameraId, listOf(Stream(it))) }
            else -> {
                val lens = lenses.firstOrNull { it.kind == config.primary } ?: lenses.firstOrNull()
                if (lens == null) {
                    done(text(R.string.error_no_camera))
                    return@post
                }
                listOf(CameraUnit(lens.cameraId, listOf(Stream(lens))))
            }
        }
        units = opened
        streams = opened.flatMap { it.streams }.sortedBy { s -> config.lenses.indexOf(s.lens.kind).let { if (it < 0) 9 else it } }
        // 记下这次打开的代数，连续打开时忽略旧一次的回调。
        val gen = generation
        var remaining = opened.size
        var failed = false
        for (unit in opened) {
            openUnit(unit, gen) { error ->
                if (gen != generation || failed) return@openUnit
                if (error != null) {
                    failed = true
                    done(error)
                } else if (--remaining == 0) {
                    done(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openUnit(unit: CameraUnit, gen: Int, attempt: Int = 0, done: (String?) -> Unit) {
        // 刚开就被拒（前一颗相机还没关完、人脸解锁正占着）时稍等再试，不立刻报错。
        fun retry(reason: Int): Boolean {
            if (attempt >= OPEN_RETRIES) return false
            Log.i(TAG, "相机 ${unit.cameraId} 正被占用（$reason），$OPEN_RETRY_MS ms 后重试")
            camera.postDelayed({ if (gen == generation) openUnit(unit, gen, attempt + 1, done) }, OPEN_RETRY_MS)
            return true
        }
        try {
            manager.openCamera(unit.cameraId, cameraExecutor, object : StateCallback() {
                private var opened = false

                override fun onOpened(device: CameraDevice) {
                    opened = true
                    if (gen != generation) {
                        device.close()
                        return
                    }
                    unit.device = device
                    configureUnit(unit, done)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (gen != generation) return
                    if (unit.device === device) unit.device = null
                    onError?.invoke(text(R.string.camera_in_use))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (gen != generation) return
                    if (unit.device === device) unit.device = null
                    val busy = error == StateCallback.ERROR_MAX_CAMERAS_IN_USE || error == StateCallback.ERROR_CAMERA_IN_USE
                    if (!opened && busy && retry(error)) return
                    done(text(R.string.camera_error_code, error))
                }
            })
        } catch (e: CameraAccessException) {
            val busy = e.reason == CameraAccessException.MAX_CAMERAS_IN_USE || e.reason == CameraAccessException.CAMERA_IN_USE
            if (busy && retry(e.reason)) return
            done(text(R.string.camera_open_failed, e.message ?: e.javaClass.simpleName))
        } catch (e: Exception) {
            done(text(R.string.camera_open_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** 接收界面的预览 Surface，变化后重建对应相机的会话。 */
    fun setPreviewSurface(kind: LensKind, surface: Surface?, size: Size?) = camera.post {
        val old = previewSurfaces[kind]
        if (surface == null || size == null) {
            if (old == null) return@post
            previewSurfaces.remove(kind)
        } else {
            if (old?.first === surface && old.second == size) return@post
            previewSurfaces[kind] = surface to size
        }
        units.filter { u -> u.device != null && u.streams.any { it.lens.kind == kind } }.forEach { configureUnit(it, null) }
    }

    /** 判断这台相机是否该出 RAW：格式含 RAW、单镜头、镜头支持且没被系统拒绝。 */
    private fun rawWanted(unit: CameraUnit): Boolean {
        val lens = unit.streams.singleOrNull()?.lens ?: return false
        return settings.format.includesRaw && units.size == 1 && !unit.rawRejected && lens.rawSize != null && lens.physicalId == null
    }

    private fun configureUnit(unit: CameraUnit, done: ((String?) -> Unit)?, allowRaw: Boolean = true) {
        val device = unit.device ?: return
        runCatching { unit.session?.close() }
        unit.session = null
        val withRaw = allowRaw && rawWanted(unit)
        val outputs = mutableListOf<OutputConfiguration>()
        for (stream in unit.streams) {
            stream.reader?.close()
            stream.rawReader?.close()
            stream.rawReader = null
            val lens = stream.lens
            val reader = ImageReader.newInstance(lens.yuvSize.width, lens.yuvSize.height, ImageFormat.YUV_420_888, if (unit.physical) 3 else 4)
            reader.setOnImageAvailableListener({ onFrame(stream, it) }, imageHandler)
            stream.reader = reader
            // Surface 尺寸还不对时先不接预览，等界面设好后再重建。
            val preview = previewSurfaces[lens.kind]?.takeIf { it.second == lens.previewSize && it.first.isValid }?.first
            stream.activePreview = preview
            preview?.let { outputs += OutputConfiguration(it).apply { lens.physicalId?.let(::setPhysicalCameraId) } }
            outputs += OutputConfiguration(reader.surface).apply { lens.physicalId?.let(::setPhysicalCameraId) }
            val rawSize = lens.rawSize
            if (withRaw && rawSize != null) {
                val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 4)
                rawReader.setOnImageAvailableListener({ onRaw(stream, it) }, imageHandler)
                stream.rawReader = rawReader
                outputs += OutputConfiguration(rawReader.surface)
            }
        }
        unit.rawConfigured = withRaw
        Log.i(TAG, "配置相机 ${unit.cameraId} 的会话：${unit.streams.joinToString { it.lens.kind.code }}，RAW ${if (withRaw) "开" else "关"}")
        // 丢掉旧会话留下的 RAW。
        imageHandler.post {
            for (stream in unit.streams) {
                stream.rawRecent.forEach { runCatching { it.close() } }
                stream.rawRecent.clear()
            }
        }
        val gen = ++unit.sessionGeneration
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != unit.sessionGeneration) {
                        runCatching { s.close() }
                        return
                    }
                    unit.session = s
                    startRepeating(unit)
                    done?.invoke(null)
                    // 配置期间是否要 RAW 变了，就再配一次。
                    if (rawWanted(unit) != unit.rawConfigured) configureUnit(unit, null)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != unit.sessionGeneration) return
                    if (withRaw) {
                        Log.w(TAG, "带 RAW 的会话配置失败，改为不出 RAW")
                        unit.rawRejected = true
                        configureUnit(unit, done, allowRaw = false)
                        return
                    }
                    (done ?: onError)?.invoke(text(R.string.camera_session_failed))
                }
            })
        try {
            // 系统不支持带 RAW 的配置时改为不出 RAW。
            if (withRaw && !supported(device, config)) {
                Log.w(TAG, "相机 ${unit.cameraId} 不支持预览 + YUV + RAW 同时出，改为不出 RAW")
                unit.rawRejected = true
                configureUnit(unit, done, allowRaw = false)
                return
            }
            // 系统不支持两路物理子镜头时报错，调用方会退回单镜头。
            if (unit.physical && !supported(device, config)) {
                Log.w(TAG, "逻辑多摄 ${unit.cameraId} 不支持 ${unit.streams.map { it.lens.physicalId }} 同时出画面")
                (done ?: onError)?.invoke(text(R.string.dual_unsupported))
                return
            }
            device.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "配置会话失败", e)
            (done ?: onError)?.invoke(text(R.string.camera_session_failed))
        }
    }

    /** 询问系统是否支持该会话配置，Android 10 以下直接当作支持。 */
    private fun supported(device: CameraDevice, config: SessionConfiguration): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || runCatching { device.isSessionConfigurationSupported(config) }.getOrDefault(true)

    fun close() = camera.post { closeNow() }

    private fun closeNow() {
        generation += 1
        for (unit in units) {
            unit.sessionGeneration += 1
            runCatching { unit.session?.close() }
            unit.session = null
            runCatching { unit.device?.close() }
            unit.device = null
            unit.rawConfigured = false
            for (stream in unit.streams) {
                stream.pending?.done?.invoke(null, CANCELLED)
                stream.pending = null
                stream.reader?.close()
                stream.reader = null
                stream.rawReader?.close()
                stream.rawReader = null
                stream.live = null
            }
        }
        units = emptyList()
        streams = emptyList()
    }

    // MARK: - 参数

    /** 应用拍摄设置和各镜头的手动对焦位置，没有位置的镜头自动对焦。 */
    fun apply(settings: CaptureSettings, manualFocus: Map<LensKind, Float>) = camera.post {
        this.settings = settings
        this.manualFocus = manualFocus
        for (unit in units) {
            // 是否要 RAW 变了就重配会话，正在配置的会话配好后会再检查。
            if (unit.session != null && rawWanted(unit) != unit.rawConfigured) configureUnit(unit, null) else startRepeating(unit)
        }
    }

    /** 开关连续出帧里的 RAW 输出，只在拍摄期间打开以省电。 */
    fun armRaw(on: Boolean) = camera.post {
        if (rawArmed == on) return@post
        rawArmed = on
        units.filter { it.rawConfigured }.forEach(::startRepeating)
    }

    private fun startRepeating(unit: CameraUnit) {
        val s = unit.session ?: return
        val device = unit.device ?: return
        val primary = unit.streams.first().lens
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        for (stream in unit.streams) {
            stream.activePreview?.let { builder.addTarget(it) }
            stream.reader?.surface?.let { builder.addTarget(it) }
            if (rawArmed) stream.rawReader?.surface?.let { builder.addTarget(it) }
        }
        fill(builder, primary, unit)
        try {
            s.setRepeatingRequest(builder.build(), callback(unit), camera)
        } catch (e: Exception) {
            Log.w(TAG, "设置连续出帧失败", e)
        }
    }

    private fun fill(b: CaptureRequest.Builder, lens: LensInfo, unit: CameraUnit) {
        val s = settings
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        // 只给单路长焦设变焦比例，物理子镜头保持全视场。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !unit.physical && lens.zoomRatio != 1f) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, lens.zoomRatio)
        }
        lens.fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        // 画质优先用高质量降噪和锐化，其余用快速模式以稳住帧率。
        val hq = s.quality == QualityMode.Quality
        b.set(CaptureRequest.NOISE_REDUCTION_MODE,
            if (hq) CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY else CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        b.set(CaptureRequest.EDGE_MODE, if (hq) CameraMetadata.EDGE_MODE_HIGH_QUALITY else CameraMetadata.EDGE_MODE_FAST)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        // 手动曝光时关掉自动曝光，只手动一项时按最近的自动读数换算另一项。
        val auto = unit.lastAuto
        if (lens.manualSensor && s.anyManualExposure) {
            val autoExposure = auto?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L
            val autoIso = auto?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
            var exposure = if (s.manualShutter) (s.shutterSeconds * 1e9).toLong() else autoExposure
            var iso = if (s.manualISO) s.iso.roundToInt() else (autoIso.toDouble() * autoExposure / exposure).roundToInt()
            if (!s.manualShutter) exposure = (autoExposure.toDouble() * autoIso / iso).toLong()
            lens.exposureRange?.let { exposure = exposure.coerceIn(it.lower, it.upper) }
            lens.isoRange?.let { iso = iso.coerceIn(it.lower, it.upper) }
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure, 33_333_333L))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val step = lens.compensationStep.toDouble().takeIf { it > 0 } ?: (1.0 / 3)
            val index = (s.exposureBias / step).roundToInt().coerceIn(lens.compensationRange.lower, lens.compensationRange.upper)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, index)
            b.set(CaptureRequest.CONTROL_AE_LOCK, s.lockAll)
        }

        // 锁定白平衡时按色温和色调换算增益，色彩矩阵沿用最近的自动值。
        if (s.lockWhiteBalance) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, lens.whiteBalance.gains(s.whiteBalanceKelvin, s.whiteBalanceTint))
            auto?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, s.lockAll)
        }

        // 设置对焦：手动位置优先，锁定时停在当前位置，物理子镜头各写各的。
        val primaryManual = manualFocus[lens.kind]
        val canFocus = lens.minFocusDiopters > 0
        when {
            canFocus && primaryManual != null -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, lens.minFocusDiopters * (1 - primaryManual))
            }
            canFocus && (s.lockFocus || s.lockAll) && auto?.get(CaptureResult.LENS_FOCUS_DISTANCE) != null -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, auto.get(CaptureResult.LENS_FOCUS_DISTANCE))
            }
            else -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (unit.physical) {
            for (stream in unit.streams.drop(1)) {
                val physicalId = stream.lens.physicalId ?: continue
                val manual = manualFocus[stream.lens.kind] ?: continue
                if (stream.lens.minFocusDiopters <= 0) continue
                runCatching {
                    b.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF, physicalId)
                    b.setPhysicalCameraKey(CaptureRequest.LENS_FOCUS_DISTANCE, stream.lens.minFocusDiopters * (1 - manual), physicalId)
                }
            }
        }
    }

    private fun callback(unit: CameraUnit) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (result.get(CaptureResult.CONTROL_AE_MODE) != CameraMetadata.CONTROL_AE_MODE_OFF &&
                result.get(CaptureResult.CONTROL_AWB_MODE) != CameraMetadata.CONTROL_AWB_MODE_OFF) {
                unit.lastAuto = result
            }
            val physicalResults = if (unit.physical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) result.physicalCameraTotalResults else emptyMap()
            for (stream in unit.streams) {
                // 优先用物理子镜头自己的结果，读不到时用逻辑结果。
                val own: CaptureResult = stream.lens.physicalId?.let { physicalResults[it] } ?: result
                val timestamp = own.get(CaptureResult.SENSOR_TIMESTAMP) ?: continue
                synchronized(stream.results) { stream.results[timestamp] = own }
                stream.live = liveFrom(own, stream.lens)
            }
        }
    }

    private fun liveFrom(result: CaptureResult, lens: LensInfo): Live? {
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val (kelvin, tint) = gains?.let { lens.whiteBalance.estimate(it) } ?: (5500f to 0f)
        val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        return Live(
            shutter = exposure / 1e9,
            iso = (result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0).toFloat(),
            kelvin = kelvin,
            tint = tint,
            aperture = result.get(CaptureResult.LENS_APERTURE) ?: lens.aperture,
            focus = if (lens.minFocusDiopters > 0) (1 - distance / lens.minFocusDiopters).coerceIn(0f, 1f) else 1f,
            manualFocusSupported = lens.minFocusDiopters > 0,
        )
    }

    // MARK: - 取帧

    /** 每路取曝光时刻离 targetNanos 最近的一帧，全部交回后在编码线程回调；部分失败时仍交回已取到的帧。 */
    fun captureAt(targetNanos: Long, raw: Boolean, done: (List<Frame>, String?) -> Unit) {
        val current = streams.filter { it.reader != null }
        if (current.isEmpty()) {
            done(emptyList(), text(R.string.missed_camera_not_ready))
            return
        }
        val lock = Any()
        val frames = mutableListOf<Frame>()
        val errors = mutableListOf<String>()
        var remaining = current.size
        val collect: (Frame?, String?) -> Unit = { frame, error ->
            val finished = synchronized(lock) {
                frame?.let { frames += it }
                error?.let { errors += it }
                --remaining == 0
            }
            if (finished) {
                val error = when {
                    frames.isEmpty() && errors.all { it == CANCELLED } -> CANCELLED
                    else -> errors.firstOrNull { it != CANCELLED }
                }
                done(frames.sortedBy { f -> current.indexOfFirst { it.lens.kind == f.lens.kind } }, error)
            }
        }
        for (stream in current) {
            stream.pending?.done?.invoke(null, text(R.string.camera_superseded))
            stream.pending = Pending(targetNanos, raw && stream.rawReader != null, collect)
            // 目标时刻过去 1 秒还没有帧就判这一路没拍到。
            val wait = (targetNanos - android.os.SystemClock.elapsedRealtimeNanos()) / 1_000_000 + 1000
            imageHandler.postDelayed({
                val p = stream.pending
                if (p != null && p.targetNanos == targetNanos) {
                    stream.pending = null
                    p.done(null, text(R.string.camera_no_frame))
                }
            }, maxOf(wait, 1000))
        }
    }

    /** 取消还没取到的那一组。 */
    fun cancelPending() {
        for (stream in streams) {
            val p = stream.pending
            stream.pending = null
            p?.done(null, CANCELLED)
        }
    }

    private fun onFrame(stream: Stream, reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val raw = image.timestamp
        val timestamp = raw + stream.timeOffsetNanos
        if (stream.lastFrameNanos > 0) {
            val period = timestamp - stream.lastFrameNanos
            if (period in 5_000_000..1_000_000_000) stream.framePeriodNanos = (stream.framePeriodNanos * 7 + period) / 8
        }
        stream.lastFrameNanos = timestamp
        if (streams.size == 1 && timestamp - lastLumaNanos > 250_000_000L) {
            lastLumaNanos = timestamp
            onLuma?.let { callback -> runCatching { sampleTopLuma(stream.lens, image) }.getOrNull()?.let(callback) }
        }
        val p = stream.pending
        // 第一帧晚于“目标减半个帧间隔”时，它就是离目标最近的帧。
        if (p == null || timestamp < p.targetNanos - stream.framePeriodNanos / 2) {
            image.close()
            return
        }
        stream.pending = null
        val width = image.width
        val height = image.height
        val nv21 = try {
            toNv21(image)
        } catch (e: Exception) {
            p.done(null, text(R.string.encode_failed, e.message ?: e.javaClass.simpleName))
            return
        } finally {
            image.close()
        }
        val shot = Shot(stream, p, nv21, width, height, timestamp, raw)
        if (!p.raw) return shot.finish()
        // 按传感器时间戳配 RAW，没到就等，半秒后只交 YUV。
        val match = stream.rawRecent.firstOrNull { it.timestamp == raw }
        if (match != null) {
            stream.rawRecent.remove(match)
            takeRaw(shot, match)
        }
        stream.rawRecent.forEach { runCatching { it.close() } }
        stream.rawRecent.clear()
        if (match != null) return shot.finish()
        stream.rawWait = shot
        imageHandler.postDelayed({
            if (stream.rawWait === shot) {
                stream.rawWait = null
                shot.rawProblem = text(R.string.raw_missing)
                shot.finish()
            }
        }, 500)
    }

    private fun onRaw(stream: Stream, reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val wait = stream.rawWait
        when {
            wait != null && image.timestamp < wait.sensorTimestamp -> image.close()
            wait != null -> {
                stream.rawWait = null
                if (image.timestamp == wait.sensorTimestamp) takeRaw(wait, image) else {
                    // 更晚的 RAW 已经到了，说明这一张被丢了。
                    image.close()
                    wait.rawProblem = text(R.string.raw_missing)
                }
                wait.finish()
            }
            // 有拍摄在等时留下最近两张，等着配 YUV 帧。
            stream.pending?.raw == true -> {
                stream.rawRecent.addLast(image)
                while (stream.rawRecent.size > 2) runCatching { stream.rawRecent.removeFirst().close() }
            }
            else -> image.close()
        }
    }

    /** 把 RAW 拷成紧密排列的 RAW16 并立即关闭 Image。 */
    private fun takeRaw(shot: Shot, image: Image) {
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowBytes = width * 2
            val pixels = ByteArray(rowBytes * height)
            val buffer = plane.buffer
            if (plane.rowStride == rowBytes) {
                buffer.position(0)
                buffer.get(pixels)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(pixels, row * rowBytes, rowBytes)
                }
            }
            shot.raw = ByteBuffer.wrap(pixels)
            shot.rawSize = Size(width, height)
        } catch (e: Exception) {
            Log.w(TAG, "取 RAW 失败", e)
            shot.rawProblem = text(R.string.raw_missing)
        } finally {
            image.close()
        }
    }

    private fun awaitResult(stream: Stream, sensorTimestamp: Long, waitMs: Int): CaptureResult? {
        var waited = 0
        while (true) {
            synchronized(stream.results) { stream.results[sensorTimestamp] }?.let { return it }
            if (waited >= waitMs) return null
            Thread.sleep(10)
            waited += 10
        }
    }

    /** 计算屏幕上沿那条画面的平均亮度 0…1，按传感器方向和屏幕方向找对应的边。 */
    private fun sampleTopLuma(lens: LensInfo, image: Image): Double {
        val rotation = if (lens.facing == CameraMetadata.LENS_FACING_FRONT) (lens.sensorOrientation + displayRotation) % 360
        else (lens.sensorOrientation - displayRotation + 360) % 360
        val plane = image.planes[0]
        val buffer = plane.buffer
        val w = image.width
        val h = image.height
        var sum = 0L
        var count = 0
        for (i in 0 until 32) {
            for (j in 0 until 6) {
                val along = (i + 0.5) / 32
                val depth = (j + 0.5) / 6 * 0.14
                val (x, y) = when (rotation) {
                    90 -> depth * w to (1 - along) * h
                    180 -> (1 - along) * w to (1 - depth) * h
                    270 -> (1 - depth) * w to along * h
                    else -> along * w to depth * h
                }
                val index = y.toInt().coerceIn(0, h - 1) * plane.rowStride + x.toInt().coerceIn(0, w - 1) * plane.pixelStride
                sum += buffer.get(index).toInt() and 0xFF
                count++
            }
        }
        return sum.toDouble() / count / 255
    }

    /** 把 YUV_420_888 转成 NV21，按整行读出以避免逐像素访问缓冲区。 */
    private fun toNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        val y = image.planes[0]
        var pos = 0
        val yBuffer = y.buffer
        val yRow = if (y.pixelStride == 1) null else ByteArray((width - 1) * y.pixelStride + 1)
        for (row in 0 until height) {
            yBuffer.position(row * y.rowStride)
            if (yRow == null) {
                yBuffer.get(out, pos, width)
                pos += width
            } else {
                yBuffer.get(yRow, 0, yRow.size)
                for (col in 0 until width) out[pos++] = yRow[col * y.pixelStride]
            }
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val chromaWidth = width / 2
        val uRow = ByteArray((chromaWidth - 1) * u.pixelStride + 1)
        val vRow = ByteArray((chromaWidth - 1) * v.pixelStride + 1)
        for (row in 0 until height / 2) {
            uBuffer.position(row * u.rowStride)
            uBuffer.get(uRow, 0, minOf(uRow.size, uBuffer.remaining()))
            vBuffer.position(row * v.rowStride)
            vBuffer.get(vRow, 0, minOf(vRow.size, vBuffer.remaining()))
            for (col in 0 until chromaWidth) {
                out[pos++] = vRow[col * v.pixelStride]
                out[pos++] = uRow[col * u.pixelStride]
            }
        }
        return out
    }

    companion object {
        private const val TAG = "TuttiCam.Camera"
        /** 给出相机被占用时的重试次数和间隔，合计约 1.5 秒，够人脸解锁或上一颗相机放手。 */
        private const val OPEN_RETRIES = 5
        private const val OPEN_RETRY_MS = 300L
        /** 标记拍摄被取消，这不算“没拍到”。 */
        const val CANCELLED = "cancelled"

        /** 定义多镜头组合的顺序和主副，与 iOS 相同。 */
        private val DUAL_ORDER = listOf(
            LensKind.Wide to LensKind.UltraWide,
            LensKind.Telephoto to LensKind.Wide,
            LensKind.UltraWide to LensKind.Telephoto,
            LensKind.Wide to LensKind.Front,
            LensKind.Telephoto to LensKind.Front,
            LensKind.UltraWide to LensKind.Front,
        )
    }
}
