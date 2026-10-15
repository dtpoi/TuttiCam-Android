package com.thinktough.tutticam.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.accessory.AccessoryHub
import com.thinktough.tutticam.camera.CameraEngine
import com.thinktough.tutticam.camera.PhotoCodec
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.ClockSync
import com.thinktough.tutticam.protocol.DeviceCode
import com.thinktough.tutticam.protocol.DeviceInfo
import com.thinktough.tutticam.protocol.DeviceStatus
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.LensKind
import com.thinktough.tutticam.protocol.PhotoFormat
import com.thinktough.tutticam.protocol.PhotoReport
import com.thinktough.tutticam.protocol.RoundReport
import com.thinktough.tutticam.protocol.accessory.group
import com.thinktough.tutticam.storage.AccessoryShotRecord
import com.thinktough.tutticam.storage.CaptureStore
import com.thinktough.tutticam.storage.PhotoMetadata
import com.thinktough.tutticam.storage.PhotoTags
import com.thinktough.tutticam.storage.RecordJson
import com.thinktough.tutticam.storage.SessionRecord
import java.util.Date
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.roundToInt

/**
 * 在每台设备上执行拍摄：管相机和静止检测，按指令取帧、保存并回报。
 * 安卓从连续帧里取离约定时刻最近的一帧，所以不需要快门延迟补偿；公开方法都在主线程调用。
 */
class CaptureAgent(
    private val context: Context,
    deviceName: String,
    val camera: CameraEngine,
    val motion: MotionMonitor,
    val store: CaptureStore,
    val location: LocationProvider,
    /** 管理本机通过蓝牙连接的外接相机。 */
    val accessories: AccessoryHub,
    /** 开启遥控模式：本机不开相机，只控制从机和外接相机。 */
    val remoteOnly: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())

    var clock = ClockSync(MonoClock::now, fixedOffset = 0.0)
    var onReport: ((RoundReport) -> Unit)? = null

    var deviceName by mutableStateOf(deviceName)
    var deviceIndex by mutableStateOf(1)
        private set
    var deviceCode by mutableStateOf(DeviceCode.Host)
        private set
    /** 每取到一组照片加一，界面据此让预览闪黑一下。 */
    var shutterCount by mutableStateOf(0)
        private set
    var settings by mutableStateOf(CaptureSettings())
        private set
    var configuredLens by mutableStateOf<LensConfig?>(null)
        private set
    var cameraReady by mutableStateOf(false)
        private set
    var cameraError by mutableStateOf<String?>(null)
        private set
    /** 记录想要的镜头组合和实际退回的组合，没有退回时为空。 */
    private var lensFallback by mutableStateOf<Pair<LensConfig, LensConfig>?>(null)

    /** 给出镜头退回的提示，按当前语言生成。 */
    val lensNote: String?
        get() = lensFallback?.let { (wanted, actual) -> context.getString(R.string.lens_unsupported, title(wanted), title(actual)) }
    var running by mutableStateOf(false)
        private set
    var sessionName by mutableStateOf<String?>(null)
        private set
    var roundsCaptured by mutableStateOf(0)
        private set
    var lastOffsetMs by mutableStateOf<Double?>(null)
        private set
    var lastProblem by mutableStateOf<String?>(null)
        private set
    /** 记录主机从回报里看不到的问题（保存失败、格式退回），随状态发给主机。 */
    private var hostProblem: String? = null
    /** 记录最近一次开相机的序号，用来认出过时的结果。 */
    private var openToken = 0
    private val openLatest = Runnable { openCamera() }
    var snapshot by mutableStateOf(MotionMonitor.Snapshot(false, 0.0, null))
        private set
    var live by mutableStateOf<CameraEngine.Live?>(null)
        private set
    /** 表示格式含 RAW 但当前镜头组合不出 RAW。 */
    var rawUnavailable by mutableStateOf(false)
        private set
    /** 表示状态文字背后的画面偏亮，文字该用黑色。 */
    var statusOnLight by mutableStateOf(false)
        private set
    /** 保存各镜头的手动对焦位置（0 最近，1 最远），每台设备各自设置。 */
    val manualFocus = mutableStateMapOf<LensKind, Float>()

    val lenses: List<LensKind> = camera.lenses.map { it.kind }
    /** 记录后置镜头的倍率文字，如 0.6x、1x、3x。 */
    private val zoomLabels: Map<LensKind, String> = run {
        val wide = camera.lenses.firstOrNull { it.kind == LensKind.Wide }?.equivalentMm?.toDouble()
        camera.lenses.filter { it.kind != LensKind.Front }.associate { lens ->
            lens.kind to if (wide == null || lens.kind == LensKind.Wide) "1x" else zoomText(lens.equivalentMm / wide)
        }
    }

    /** 给出镜头按钮上的文字，“前置”按当前语言取。 */
    val lensLabels: Map<LensKind, String>
        get() = if (LensKind.Front in lenses) zoomLabels + (LensKind.Front to context.getString(R.string.lens_front)) else zoomLabels
    /** 列出探测到的多镜头组合，打开失败时退回单镜头。 */
    val dualCombos: List<LensConfig> = camera.dualCombos

    private var saving = 0
    private val pendingRounds = mutableSetOf<Int>()
    /** 记录每组的场景文件夹，强制同步结算时按它找待定照片。 */
    private val roundFolders = mutableMapOf<Int, String?>()
    private var rotation = 0
    private var freeBytes = 0L
    private var freeBytesCheckedAt = -100.0
    private val scratch = context.cacheDir

    val info: DeviceInfo
        get() = DeviceInfo(deviceName, Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL,
            "Android " + Build.VERSION.RELEASE, lenses, dualCombos, squareFront = false)

    val hasCameraPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    init {
        camera.setOnError { message -> main.post { cameraReady = false; cameraError = message } }
        // 用回差切换文字颜色，避免在临界值附近来回闪。
        camera.setOnLuma { luma ->
            main.post {
                val onLight = if (statusOnLight) luma > DARK_TEXT_OFF else luma > DARK_TEXT_ON
                if (onLight != statusOnLight) statusOnLight = onLight
            }
        }
    }

    // MARK: - 相机

    /** 在有相机权限时打开相机，权限由界面申请。 */
    fun prepareCamera() {
        if (remoteOnly) return
        if (!hasCameraPermission) {
            cameraError = context.getString(R.string.error_camera_permission)
            return
        }
        applyLens(force = true)
    }

    /** 进入后台或离开会话时关闭相机，回到前台由 prepareCamera 重开。 */
    fun suspendCamera() {
        main.removeCallbacks(openLatest)
        openToken += 1
        cameraReady = false
        camera.cancelPending()
        camera.close()
    }

    private fun applyLens(force: Boolean = false) {
        if (remoteOnly || !hasCameraPermission) return
        if (lenses.isEmpty()) {
            cameraError = context.getString(R.string.error_no_camera)
            return
        }
        val wanted = settings.lens(deviceIndex)
        val options = info.lensOptions
        val supported = options.any { it.lenses.toSet() == wanted.lenses.toSet() }
        val target = if (supported) wanted else options.firstOrNull { it.primary == wanted.primary } ?: options.first()
        lensFallback = if (target == wanted) null else wanted to target
        if (!force && target == configuredLens) return
        configuredLens = target
        cameraError = null
        if (ScreenshotDemo.isOn) {
            cameraReady = true
            return
        }
        cameraReady = false
        // 开会话时会连着改几次镜头，同一轮消息里只开最后一次，免得上一颗相机还没关完就开下一颗被系统拒绝。
        main.removeCallbacks(openLatest)
        main.post(openLatest)
    }

    /** 打开 configuredLens；结果按序号核对，旧一次的结果到得晚时直接丢掉，不会把过时的错误留在界面上。 */
    private fun openCamera() {
        val target = configuredLens ?: return
        val token = ++openToken
        camera.open(target, settings) { error ->
            main.post {
                if (token != openToken) return@post
                if (error != null && target.isDual) {
                    // 系统不支持这对镜头时退回主镜头单拍并提示。
                    val single = LensConfig(target.primary)
                    lensFallback = target to single
                    configuredLens = single
                    val retry = ++openToken
                    camera.open(single, settings) { again ->
                        main.post {
                            if (retry != openToken) return@post
                            if (again != null) cameraError = again else {
                                cameraReady = true
                                cameraError = null
                                pushParameters()
                            }
                        }
                    }
                } else if (error != null) {
                    cameraError = error
                } else {
                    cameraReady = true
                    cameraError = null
                    pushParameters()
                }
            }
        }
    }

    fun label(config: LensConfig): String = config.lenses.joinToString(" + ") { lensLabels[it] ?: it.code }

    /** 返回提示用的镜头组合名称，如“0.6x 超广角”，前置只写“前置”。 */
    private fun title(config: LensConfig): String = config.lenses.joinToString(" + ") { kind ->
        val name = context.getString(when (kind) {
            LensKind.UltraWide -> R.string.lens_ultra_wide
            LensKind.Wide -> R.string.lens_wide
            LensKind.Telephoto -> R.string.lens_tele
            LensKind.Front -> R.string.lens_front
        })
        // 本机没有的镜头不知道倍率，只写名称。
        if (kind == LensKind.Front) name else lensLabels[kind]?.let { "$it $name" } ?: name
    }

    fun setManualFocus(position: Float?, lens: LensKind) {
        if (position == null) manualFocus.remove(lens) else manualFocus[lens] = position
        pushParameters()
    }

    fun resetFocus() {
        configuredLens?.lenses?.forEach { manualFocus.remove(it) }
        pushParameters()
    }

    private fun pushParameters() {
        if (configuredLens == null) return
        camera.apply(settings, manualFocus.toMap())
    }

    fun setIdentity(index: Int, code: DeviceCode) {
        deviceIndex = index
        deviceCode = code
        applyLens()
    }

    fun update(settings: CaptureSettings) {
        this.settings = settings
        applyLens()
        if (cameraReady) pushParameters()
        if (running) updateLocation()
    }

    private fun updateLocation() {
        if (running && settings.writeLocation && !remoteOnly) location.start() else location.stop()
    }

    // MARK: - 会话

    fun start(session: String, record: SessionRecord) {
        sessionName = session
        running = true
        roundsCaptured = 0
        lastOffsetMs = null
        lastProblem = null
        hostProblem = null
        Log.i(TAG, "开始会话 $session · ${configuredLens?.let(::label)} · ${settings.stability}")
        store.begin(session)
        if (!remoteOnly) store.writeText("session.json", RecordJson.encode(SessionRecord.serializer(), record), session)
        camera.armRaw(true)
        updateLocation()
        if (cameraReady) pushParameters()
        accessories.setShooting(true)
    }

    /** 停止拍摄，并取消还没取到的那一组。 */
    fun stop() {
        running = false
        camera.cancelPending()
        camera.armRaw(false)
        accessories.cancelPending()
        accessories.setShooting(false)
        location.stop()
    }

    fun resolve(round: Int, keep: Boolean) {
        val folder = roundFolders.remove(round) ?: store.folder
        store.resolve(round, keep, folder)
    }

    fun renameFolder(old: String, new: String) {
        store.renameFolder(old, new)
        if (sessionName == old) sessionName = new
        roundFolders.replaceAll { _, folder -> if (folder == old) new else folder }
    }

    fun writeSession(record: SessionRecord) {
        if (!remoteOnly) store.writeText("session.json", RecordJson.encode(SessionRecord.serializer(), record), record.session)
    }

    // MARK: - 拍摄

    fun schedule(round: Int, hostTime: Double) {
        if (!running) return
        if (remoteOnly) {
            fireAccessoriesOnly(round, hostTime)
            return
        }
        if (!cameraReady) return sendMissed(round, context.getString(R.string.missed_camera_not_ready))
        val target = clock.hostToLocal(hostTime) ?: return sendMissed(round, context.getString(R.string.missed_not_synced))
        val now = MonoClock.now()
        if (target - now <= 0.003) return sendMissed(round, context.getString(R.string.missed_too_late))
        val settings = settings
        saving += 1
        pendingRounds += round
        // 照片超时未交回就判没拍到，免得“正在保存”一直不归零。
        val timeout = target - now + 8 + if (settings.isLongExposure) settings.shutterSeconds * 2 else 0.0
        main.postDelayed({
            if (pendingRounds.remove(round)) {
                saving -= 1
                sendMissed(round, context.getString(R.string.missed_timeout))
            }
        }, (timeout * 1000).toLong())
        val folder = store.folder
        roundFolders[round] = folder
        triggerAccessories(round, hostTime, target, folder)
        main.postDelayed({ shutterCount += 1 }, ((target - now) * 1000).toLong().coerceAtLeast(0))
        Log.i(TAG, "第 $round 组：%.0f ms 后取帧".format((target - now) * 1000))
        camera.captureAt(MonoClock.toNanos(target), settings.format.includesRaw) { frames, error ->
            // 在编码线程编码和写元数据，多镜头时各张 JSON 互相记下对方文件名。
            val encoded = frames.map { encode(it, round, target, settings) }
            val prepared = encoded.map { e ->
                prepare(e, round, hostTime, target, settings, encoded.firstOrNull { it !== e }?.file)
            }
            main.post { finish(round, prepared, error, settings, folder) }
        }
    }

    /** 按各品牌提前量给外接相机按快门，并为每台相机写一条 JSON 记录。 */
    private fun triggerAccessories(round: Int, hostTime: Double, target: Double, folder: String?) {
        val cameras = accessories.connectedCameras
        if (cameras.isEmpty()) return
        val code = deviceCode.text
        for ((lead, group) in cameras.groupBy { settings.accessoryLead(it.brand) }) {
            val fireAt = target - lead
            val late = fireAt - MonoClock.now() < 0.003
            accessories.shutter(if (late) MonoClock.now() + 0.003 else fireAt, group.map { it.id }.toSet()) { shots ->
                for (shot in shots) {
                    val camera = accessories.camera(shot.cameraId)
                    val cameraCode = camera?.code?.text ?: "%s%02d".format(shot.kind.brand.group, shot.number)
                    val record = AccessoryShotRecord(round = round, deviceCode = code, cameraNumber = shot.number, cameraCode = cameraCode,
                        cameraName = camera?.let(accessories::displayName) ?: shot.name, cameraModel = shot.model, brand = shot.kind.brand.raw,
                        targetTime = hostTime, sentTime = clock.localToHost(shot.sentAt), leadMs = lead * 1000,
                        note = if (late) "指令到达时已来不及按提前量发出，已立即发出" else "")
                    store.writeText(CaptureStore.photoBase(round, code, cameraCode) + ".json",
                        RecordJson.encode(AccessoryShotRecord.serializer(), record), folder)
                    Log.i(TAG, "%s 第 %d 组快门已发出（比 T %+.0f ms）".format(cameraCode, round, (shot.sentAt - target) * 1000))
                }
            }
        }
    }

    /** 在遥控模式下只给外接相机按快门，然后立即回报。 */
    private fun fireAccessoriesOnly(round: Int, hostTime: Double) {
        val target = clock.hostToLocal(hostTime)
        if (target == null || target - MonoClock.now() <= 0.003) return sendMissed(round, context.getString(R.string.missed_too_late))
        triggerAccessories(round, hostTime, target, store.folder)
        roundsCaptured += 1
        onReport?.invoke(RoundReport(round, deviceIndex, missed = false, photos = emptyList()))
    }

    /** 保存一张照片编好的文件和写记录要用的测量值。 */
    private class Encoded(
        val frame: CameraEngine.Frame,
        val files: List<Pair<String, ByteArray>>,
        /** 记录主文件名，只出 RAW 时是 DNG。 */
        val file: String,
        val rawFile: String?,
        /** 记录实际存下的格式，退回时与设置不同。 */
        val format: PhotoFormat,
        val problem: String?,
        val time: Double,
        val exposure: Double,
        val measured: MotionMonitor.Measurement,
        val orientation: Int,
    )

    /** 保存一张照片待写入的文件、回报和 JSON。 */
    private class Prepared(
        val files: List<Pair<String, ByteArray>>,
        val report: PhotoReport,
        val metadata: Pair<String, String>?,
        val problem: String?,
    )

    /** 按照片格式编码；缺 RAW 或 HEIC 失败时退回 JPEG，并把原因写进 problem。 */
    private fun encode(frame: CameraEngine.Frame, round: Int, target: Double, settings: CaptureSettings): Encoded {
        val lens = frame.lens
        val time = MonoClock.toSeconds(frame.timestampNanos)
        val exposure = frame.exposureNanos?.let { it / 1e9 } ?: camera.exposureSeconds
        val measured = motion.measure(time + exposure / 2, exposure, lens.focalPx)
        val gravity = measured.gravity
        val orientation = if (lens.kind == LensKind.Front) (lens.sensorOrientation - rotation + 360) % 360
        else (lens.sensorOrientation + rotation) % 360
        val base = CaptureStore.photoBase(round, deviceCode.text, lens.kind.code)
        val place = if (settings.writeLocation) location.fix else null
        val takenAt = Date(System.currentTimeMillis() - ((MonoClock.now() - time) * 1000).toLong())
        val rotated = orientation % 180 != 0
        val width = if (rotated) frame.height else frame.width
        val height = if (rotated) frame.width else frame.height
        var problem: String? = null

        val files = mutableListOf<Pair<String, ByteArray>>()
        var format = settings.format
        val raw = frame.raw
        var rawFile: String? = null
        if (format.includesRaw) {
            val dng = raw?.let { data ->
                runCatching { PhotoCodec.dng(data, orientation, place) }.onFailure { Log.w(TAG, "写 DNG 失败", it) }.getOrNull()
            }
            if (dng != null) {
                rawFile = "$base.dng"
                files += rawFile to dng
            } else {
                // 只在要了 RAW 却没取到或写不出时记问题，本来不出 RAW 不算。
                format = format.withoutRaw
                problem = frame.rawProblem ?: if (raw != null) context.getString(R.string.raw_missing) else null
            }
        }
        var file = rawFile
        if (format.hasProcessed) {
            val exif = PhotoTags.Exif(orientation, takenAt, exposure, frame.iso, lens.focalMm, lens.equivalentMm, lens.aperture,
                frame.width, frame.height, place)
            val heic = if (!format.usesJpeg) {
                runCatching {
                    PhotoCodec.heic(frame.nv21, frame.width, frame.height, orientation,
                        PhotoTags.exifBlock(PhotoTags.Exif(0, takenAt, exposure, frame.iso, lens.focalMm, lens.equivalentMm,
                            lens.aperture, width, height, place), scratch), scratch)
                }.onFailure {
                    Log.w(TAG, "HEIC 编码失败", it)
                    problem = context.getString(R.string.heic_failed, it.message ?: it.javaClass.simpleName)
                }.getOrNull()
            } else null
            if (heic != null) {
                file = "$base.heic"
                files.add(0, file to heic)
            } else {
                if (!format.usesJpeg) format = if (format.includesRaw) PhotoFormat.RawJpeg else PhotoFormat.Jpeg
                val attitude = if (settings.writeAttitude && gravity != null) {
                    // 计算光轴仰角：后置光轴是 −z，前置是 +z，所以仰角 = asin(±g.z)。
                    val pitch = Math.toDegrees(asin((if (lens.kind == LensKind.Front) -gravity[2] else gravity[2]).coerceIn(-1.0, 1.0)))
                    PhotoTags.Attitude(gravity, measured.attitude, pitch, null, lens.kind.code)
                } else null
                file = "$base.jpg"
                files.add(0, file to PhotoTags.apply(PhotoCodec.jpeg(frame.nv21, frame.width, frame.height), exif, attitude, scratch))
            }
        }
        return Encoded(frame, files, file ?: "$base.jpg", rawFile, format, problem, time, exposure, measured, orientation)
    }

    private fun prepare(e: Encoded, round: Int, hostTime: Double, target: Double, settings: CaptureSettings, lensPair: String?): Prepared {
        val frame = e.frame
        val lens = frame.lens
        val measured = e.measured
        val code = deviceCode.text
        val base = CaptureStore.photoBase(round, code, lens.kind.code)
        val moving = measured.blurPx > settings.stillness.blurPx
        val offsetMs = (e.time - target) * 1000
        val rotated = e.orientation % 180 != 0
        val live = camera.live(lens.kind)
        val metadata = if (settings.writePhotoJSON) "$base.json" to RecordJson.encode(PhotoMetadata.serializer(), PhotoMetadata(
            round = round, deviceId = deviceIndex, deviceCode = code, deviceName = deviceName, deviceModel = Build.MODEL,
            lens = lens.kind.code, lensPair = lensPair, file = e.file, rawFile = e.rawFile, focalPxEstimate = lens.focalPx,
            targetTime = hostTime, actualTime = clock.localToHost(e.time), offsetMs = offsetMs, qualityMode = jsonName(settings.quality.name),
            format = e.format.raw, stabilityMode = jsonName(settings.stability.name), shutter = e.exposure, iso = frame.iso?.toDouble(),
            whiteBalance = live?.kelvin, lensPosition = live?.focus ?: 1f,
            focusMode = if (manualFocus[lens.kind] != null) "manual" else if (settings.lockFocus) "locked" else "auto",
            rotationRate = measured.rotationRate, blurPx = measured.blurPx, moving = moving, gravity = measured.gravity?.toList(),
            width = if (rotated) frame.height else frame.width, height = if (rotated) frame.width else frame.height,
        )) else null
        Log.i(TAG, "  ${e.files.joinToString { it.first }} ${frame.width}x${frame.height} 偏差 %+.1f ms 快门 1/%.0f ISO ${frame.iso} 模糊 %.2f px%s%s".format(
            offsetMs, 1 / maxOf(e.exposure, 1e-5), measured.blurPx, if (moving) " 在动" else "", e.problem?.let { "（$it）" } ?: ""))
        return Prepared(e.files, PhotoReport(lens.kind, e.file, offsetMs, moving, measured.blurPx), metadata, e.problem)
    }

    private fun finish(round: Int, photos: List<Prepared>, error: String?, settings: CaptureSettings, folder: String?) {
        if (!pendingRounds.remove(round)) {
            Log.w(TAG, "第 $round 组的照片在超时之后才交回，已按没拍到处理")
            return
        }
        if (error == CameraEngine.CANCELLED) {
            saving -= 1
            return
        }
        if (photos.isEmpty()) {
            saving -= 1
            sendMissed(round, error ?: context.getString(R.string.missed_no_photo))
            return
        }
        // 多镜头缺一路时仍回报已取到的照片，并附上错误。
        val report = RoundReport(round, deviceIndex, missed = false, photos = photos.map { it.report }, error = error)
        roundsCaptured += 1
        lastOffsetMs = photos.mapNotNull { it.report.offsetMs }.maxByOrNull { abs(it) }
        photos.firstNotNullOfOrNull { it.problem }?.let { lastProblem = it; hostProblem = it }
        onReport?.invoke(report)
        if (!settings.forceSync) roundFolders.remove(round)
        store.save(photos.flatMap { it.files }, photos.mapNotNull { it.metadata }, settings.forceSync, folder) { problem ->
            main.post {
                saving -= 1
                if (problem != null) {
                    lastProblem = context.getString(R.string.save_failed, problem)
                    hostProblem = lastProblem
                }
            }
        }
    }

    private fun sendMissed(round: Int, reason: String) {
        Log.w(TAG, "第 $round 组错过：$reason")
        lastProblem = context.getString(R.string.round_missed, round, reason)
        onReport?.invoke(RoundReport(round, deviceIndex, missed = true, photos = emptyList(), error = reason))
    }

    // MARK: - 状态

    /** 刷新静止判定参数并生成上报状态，每秒约 20 次。 */
    fun makeStatus(): DeviceStatus {
        if (ScreenshotDemo.isOn) return demoStatus()
        val stillness = settings.stillness
        motion.params.exposureSeconds = camera.exposureSeconds
        motion.params.focalPx = camera.active?.focalPx ?: 3000.0
        motion.params.thresholdPx = stillness.blurPx
        motion.params.holdSeconds = stillness.holdSeconds
        val now = motion.snapshot()
        if (now.still != snapshot.still || abs(now.blurPx - snapshot.blurPx) > 0.005) snapshot = now
        val reading = camera.live
        if (reading != live) live = reading
        val noRaw = settings.format.includesRaw && cameraReady && !camera.rawActive
        if (noRaw != rawUnavailable) {
            rawUnavailable = noRaw
            Log.i(TAG, if (noRaw) "当前镜头组合不出 RAW" else "RAW 恢复")
        }
        rotation = MotionMonitor.deviceRotation(now.gravity, rotation)
        val time = MonoClock.now()
        if (time - freeBytesCheckedAt > 5) {
            freeBytesCheckedAt = time
            freeBytes = store.freeBytes()
        }
        return DeviceStatus(
            still = if (remoteOnly && !settings.hostStillnessOnly) true else now.still,
            blurPx = now.blurPx,
            // 外接相机还在保存上一张时也算没准备好。
            ready = saving == 0 && !accessories.busy,
            // 遥控模式下连着外接相机才算可以拍。
            cameraReady = if (remoteOnly) accessories.hasConnected else cameraReady,
            cameraError = cameraError,
            problem = hostProblem,
            lens = configuredLens,
            battery = battery(),
            freeBytes = freeBytes,
            thermal = thermal(),
            accessories = accessories.briefs,
        )
    }

    /** 套上截图演示数据：已拍 12 组，偏差 +0.4 ms。 */
    fun applyDemo() {
        running = true
        roundsCaptured = ScreenshotDemo.ROUNDS
        lastOffsetMs = ScreenshotDemo.OFFSET_MS
    }

    private fun demoStatus(): DeviceStatus {
        val lens = configuredLens
        snapshot = ScreenshotDemo.snapshot
        live = ScreenshotDemo.live(camera.lenses.firstOrNull { it.kind == lens?.primary })
        statusOnLight = lens != null && !lens.isDual && (ScreenshotDemo.topLuminance(lens.primary) ?: 0.0) > DARK_TEXT_ON
        return ScreenshotDemo.hostStatus(lens, accessories.briefs)
    }

    private fun battery(): Float {
        val level = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (level in 0..100) level / 100f else -1f
    }

    /** 把系统发热状态换成 iOS 的 0 正常到 3 危急。 */
    private fun thermal(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0) {
            PowerManager.THERMAL_STATUS_NONE -> 0
            PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> 1
            PowerManager.THERMAL_STATUS_SEVERE -> 2
            else -> 3
        }
    }

    companion object {
        private const val TAG = "TuttiCam.Agent"
        /** 设定改用黑字的亮度门限，降到 DARK_TEXT_OFF 以下才换回白字。 */
        const val DARK_TEXT_ON = 0.62
        const val DARK_TEXT_OFF = 0.5

        /** 返回枚举在协议里的取值，如 Quality → quality。 */
        private fun jsonName(name: String) = name.replaceFirstChar { it.lowercase() }

        /** 把倍率格式化成 0.6x、2x、3x 这样的文字。 */
        fun zoomText(zoom: Double): String {
            val rounded = (zoom * 10).roundToInt() / 10.0
            return if (rounded >= 2 || rounded == rounded.toInt().toDouble()) "${rounded.roundToInt()}x" else "${rounded}x"
        }
    }
}
