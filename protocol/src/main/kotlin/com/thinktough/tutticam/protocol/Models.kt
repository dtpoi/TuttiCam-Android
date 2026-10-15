package com.thinktough.tutticam.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// 与 iOS 的 Messages.swift、CaptureSettings.swift 一一对应：字段名、取值、可选性都不能改（见 docs/PROTOCOL.md）。
// iOS 要求非可选字段必填，所以这些字段都带默认值并始终编码。

/** 区分超广角、主摄、长焦、前置四种镜头。 */
@Serializable
enum class LensKind {
    @SerialName("uw") UltraWide,
    @SerialName("w") Wide,
    @SerialName("t") Telephoto,
    @SerialName("f") Front;

    /** 给出文件名里的镜头代号。 */
    val code: String
        get() = when (this) {
            UltraWide -> "uw"
            Wide -> "w"
            Telephoto -> "t"
            Front -> "f"
        }
}

/** 描述单镜头或双镜头组合，secondary 为空表示单镜头。 */
@Serializable
data class LensConfig(
    val primary: LensKind,
    val secondary: LensKind? = null,
) {
    val lenses: List<LensKind> get() = listOfNotNull(primary, secondary)
    val isDual: Boolean get() = secondary != null

    companion object {
        val Main = LensConfig(LensKind.Wide)
    }
}

@Serializable
enum class QualityMode {
    @SerialName("quality") Quality,
    @SerialName("balanced") Balanced,
    @SerialName("speed") Speed,
}

/** 列出照片格式；raw 只在安卓上有，发给 iPhone 时换成 rawJpeg，未知值解码为 jpeg。 */
@Serializable(with = PhotoFormatSerializer::class)
enum class PhotoFormat(val raw: String) {
    Jpeg("jpeg"),
    Heic("heic"),
    /** 只存 DNG，安卓专有。 */
    Raw("raw"),
    RawJpeg("rawJpeg"),
    RawHeic("rawHeic");

    val includesRaw: Boolean get() = this == Raw || this == RawJpeg || this == RawHeic
    /** 表示会存 JPEG 或 HEIC，只存 RAW 时为 false。 */
    val hasProcessed: Boolean get() = this != Raw
    val usesJpeg: Boolean get() = this == Jpeg || this == RawJpeg
    val processedExtension: String get() = if (usesJpeg || this == Raw) "jpg" else "heic"

    /** 返回去掉 RAW 后的格式，镜头不出 RAW 时用它。 */
    val withoutRaw: PhotoFormat get() = when (this) {
        Raw, RawJpeg -> Jpeg
        RawHeic -> Heic
        else -> this
    }

    /** 返回 iOS 能识别的格式，raw 换成 rawJpeg。 */
    val forIos: PhotoFormat get() = if (this == Raw) RawJpeg else this

    companion object {
        fun parse(raw: String): PhotoFormat = entries.firstOrNull { it.raw == raw } ?: Jpeg
    }
}

object PhotoFormatSerializer : KSerializer<PhotoFormat> {
    override val descriptor = PrimitiveSerialDescriptor("PhotoFormat", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PhotoFormat) = encoder.encodeString(value.raw)
    override fun deserialize(decoder: Decoder): PhotoFormat = PhotoFormat.parse(decoder.decodeString())
}

/** 选择静止判定模式：三脚架、手持或不判定。 */
@Serializable
enum class StabilityMode {
    @SerialName("tripod") Tripod,
    @SerialName("handheld") Handheld,
    @SerialName("off") Off,
}

/** 选择方形前置镜头的画幅；也能解码旧值 "wide"、"tall"，未知值按 1:1。 */
@Serializable(with = FrontFramingSerializer::class)
enum class FrontFraming(val raw: String) {
    Square("square"),
    Wide43("wide43"),
    Tall43("tall43");

    companion object {
        fun parse(raw: String): FrontFraming = when (raw) {
            "wide" -> Wide43
            "tall" -> Tall43
            else -> entries.firstOrNull { it.raw == raw } ?: Square
        }
    }
}

object FrontFramingSerializer : KSerializer<FrontFraming> {
    override val descriptor = PrimitiveSerialDescriptor("FrontFraming", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: FrontFraming) = encoder.encodeString(value.raw)
    override fun deserialize(decoder: Decoder): FrontFraming = FrontFraming.parse(decoder.decodeString())
}

/** 列出外接相机品牌，声明顺序就是界面顺序。 */
@Serializable
enum class CameraBrand {
    @SerialName("dji") Dji,
    @SerialName("gopro") GoPro,
    @SerialName("sony") Sony,
    @SerialName("canon") Canon,
    @SerialName("fujifilm") Fujifilm,
    @SerialName("nikon") Nikon,
    @SerialName("ricoh") Ricoh;

    val raw: String get() = name.lowercase().let { if (it == "gopro") "gopro" else it }
}

@Serializable
data class StillnessParams(
    /** 限制允许的模糊量（像素）。 */
    val blurPx: Double,
    /** 要求保持静止的时长（秒）。 */
    val holdSeconds: Double,
)

/** 保存主机下发的拍摄设置；iOS 要求字段齐全，所以编码时写出默认值。 */
@Serializable
data class CaptureSettings(
    val lens: LensConfig = LensConfig.Main,
    /** 按设备序号单独指定镜头，JSON 键是数字字符串。 */
    val lensOverrides: Map<Int, LensConfig> = emptyMap(),
    val quality: QualityMode = QualityMode.Balanced,
    val format: PhotoFormat = PhotoFormat.Jpeg,
    val writeAttitude: Boolean = false,
    val writeLocation: Boolean = true,
    val saveToPhotos: Boolean = false,
    val writePhotoJSON: Boolean = false,
    val lockAll: Boolean = false,
    val prefer48MP: Boolean = true,
    val manualShutter: Boolean = false,
    val manualISO: Boolean = false,
    val shutterSeconds: Double = 1.0 / 250,
    val iso: Float = 100f,
    val lockFocus: Boolean = false,
    val lockWhiteBalance: Boolean = false,
    val whiteBalanceKelvin: Float = 5500f,
    val whiteBalanceTint: Float = 0f,
    val exposureBias: Float = 0f,
    val stability: StabilityMode = StabilityMode.Tripod,
    val tripod: StillnessParams = StillnessParams(blurPx = 1.0, holdSeconds = 0.3),
    val handheld: StillnessParams = StillnessParams(blurPx = 3.0, holdSeconds = 0.3),
    val hostStillnessOnly: Boolean = false,
    val minInterval: Double = 2.0,
    val triggerLead: Double = 0.25,
    val syncInterval: Double = 20.0,
    val forceSync: Boolean = false,
    val frontFraming: FrontFraming = FrontFraming.Square,
    val osmoLead: Double = 0.0,
    val accessoryLeads: Map<String, Double> = emptyMap(),
    val osmoExtraWait: Double = 1.0,
) {
    val lockExposure: Boolean get() = manualShutter && manualISO
    val anyManualExposure: Boolean get() = manualShutter || manualISO
    val isLongExposure: Boolean get() = manualShutter && shutterSeconds > LONG_EXPOSURE_THRESHOLD * 1.01

    /** 给出安排拍摄时刻的提前量（秒），长曝光时按曝光时长加大。 */
    val scheduleLead: Double get() = if (isLongExposure) maxOf(triggerLead, shutterSeconds * 2 + 0.3) else triggerLead

    val stillness: StillnessParams get() = if (stability == StabilityMode.Tripod) tripod else handheld

    fun lens(forDevice: Int): LensConfig = lensOverrides[forDevice] ?: lens

    fun accessoryLead(brand: CameraBrand): Double =
        if (brand == CameraBrand.Dji) osmoLead else accessoryLeads[brand.raw] ?: 0.0

    fun withAccessoryLead(brand: CameraBrand, seconds: Double): CaptureSettings =
        if (brand == CameraBrand.Dji) copy(osmoLead = seconds) else copy(accessoryLeads = accessoryLeads + (brand.raw to seconds))

    companion object {
        /** 定义长曝光的快门下限。 */
        const val LONG_EXPOSURE_THRESHOLD = 1.0 / 15
        val shutterChoices = listOf(1.0, 1.0 / 2, 1.0 / 4, 1.0 / 8, 1.0 / 15, 1.0 / 30, 1.0 / 50, 1.0 / 60, 1.0 / 100,
            1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000)
        val intervalChoices = listOf(0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 30.0, 60.0)
        val biasChoices = listOf(-2f, -1.7f, -1.3f, -1f, -0.7f, -0.3f, 0f, 0.3f, 0.7f, 1f, 1.3f, 1.7f, 2f)
        val isoChoices = listOf(25f, 32f, 50f, 64f, 100f, 125f, 200f, 250f, 400f, 640f, 800f, 1250f, 1600f, 2500f, 3200f, 6400f)
    }
}

/** 表示设备编号：组字母加两位数字，如 A01。 */
@Serializable
data class DeviceCode(val group: String, val number: Int) : Comparable<DeviceCode> {
    val text: String get() = group + number.toString().padStart(2, '0')

    override fun compareTo(other: DeviceCode): Int = compareValuesBy(this, other, { it.group }, { it.number })

    companion object {
        val Host = DeviceCode("A", 1)
        val groups = ('A'..'Z').map { it.toString() }
    }
}

@Serializable
data class DeviceInfo(
    val name: String,
    val model: String,
    val systemVersion: String,
    val lenses: List<LensKind>,
    val dualCombos: List<LensConfig>,
    /** 表示前置是方形传感器，旧版本不报时为空。 */
    val squareFront: Boolean? = null,
) {
    /** 列出全部可选镜头组合，单镜头在前。 */
    val lensOptions: List<LensConfig> get() = lenses.map { LensConfig(it) } + dualCombos

    /** 判断是否安卓设备，安卓专有的设置值只发给它们。 */
    val isAndroid: Boolean get() = systemVersion.startsWith("Android")
}

@Serializable
data class AccessoryBrief(
    val name: String,
    val number: Int,
    /** 记录外接相机编号，如 D01。 */
    val code: String = "",
    /** 记录品牌，取 CameraBrand 的值。 */
    val brand: String = "dji",
    val connected: Boolean,
    val battery: Int? = null,
    val remainPhotos: Int? = null,
    val capturing: Boolean,
    val tooHot: Boolean,
    val sleeping: Boolean = false,
)

@Serializable
data class DeviceStatus(
    val still: Boolean = false,
    val blurPx: Double = 0.0,
    /** 表示上一组已存完、外接相机也空闲。 */
    val ready: Boolean = true,
    val cameraReady: Boolean = false,
    val cameraError: String? = null,
    /** 记录主机从回报里看不到的问题，如保存失败、RAW 或 HEIC 退回 JPEG；只有安卓发，iOS 忽略。 */
    val problem: String? = null,
    val lens: LensConfig? = null,
    /** 记录电量 0…1，未知为 -1。 */
    val battery: Float = -1f,
    val freeBytes: Long = 0,
    /** 记录发热等级，0 正常到 3 危急。 */
    val thermal: Int = 0,
    val accessories: List<AccessoryBrief> = emptyList(),
) {
    companion object {
        const val LOW_STORAGE_BYTES = 1_000_000_000L
    }
}

@Serializable
data class PhotoReport(
    val lens: LensKind,
    /** 记录主文件名，如 r0012_A02_w.jpg。 */
    val file: String,
    /** 记录实际曝光减约定时刻（毫秒），正数表示晚。 */
    val offsetMs: Double? = null,
    val moving: Boolean,
    val blurPx: Double? = null,
)

@Serializable
data class RoundReport(
    val round: Int,
    val deviceIndex: Int,
    val missed: Boolean,
    val photos: List<PhotoReport>,
    val error: String? = null,
) {
    val moving: Boolean get() = photos.any { it.moving }
    val maxAbsOffsetMs: Double? get() = photos.mapNotNull { it.offsetMs?.let(Math::abs) }.maxOrNull()
}

/** 描述从机连上 TCP 后的第一帧（不是 Message），v 是协议版本。 */
@Serializable
data class LinkHello(val key: String, val name: String, val v: Int? = null)
