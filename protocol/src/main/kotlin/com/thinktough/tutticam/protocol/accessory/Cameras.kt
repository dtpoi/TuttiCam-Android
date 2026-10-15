package com.thinktough.tutticam.protocol.accessory

import com.thinktough.tutticam.protocol.CameraBrand
import java.util.UUID
import kotlin.random.Random

// 识别外接相机的品牌和协议，并提供各家协议的常量和纯数据处理（与 iOS 的 CameraProtocols.swift 相同）。
// 不依赖界面和蓝牙，可以在电脑上直接测试。
//
// 来源：
// - DJI：DJI R SDK 协议（见 Dji.kt）
// - 索尼、佳能、富士、尼康、理光：移植自 furble（https://github.com/gkoh/furble，MIT 许可证，Copyright (c) 2020 Guo-Rong Koh）
// - GoPro：Open GoPro 蓝牙接口规范（https://gopro.github.io/OpenGoPro/）

/** 把 16 位蓝牙 UUID 展开成 128 位。 */
fun uuid16(short: Int): UUID = UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))

fun uuid(text: String): UUID = UUID.fromString(text)

/** 给出品牌的默认编号组字母，如 DJI 为 D、索尼为 S。 */
val CameraBrand.group: String
    get() = when (this) {
        CameraBrand.Dji -> "D"
        CameraBrand.Sony -> "S"
        CameraBrand.Canon -> "C"
        CameraBrand.Fujifilm -> "F"
        CameraBrand.Nikon -> "N"
        CameraBrand.Ricoh -> "R"
        CameraBrand.GoPro -> "G"
    }

/** 表示该品牌已在真机上验证，目前只有 DJI。 */
val CameraBrand.verified: Boolean get() = this == CameraBrand.Dji

/** 区分具体的蓝牙协议，一个品牌可能有几种；raw 值与 iOS 相同并存进配对记录。 */
enum class CameraKind(val raw: String, val brand: CameraBrand) {
    /** 使用 DJI R SDK（Osmo 360 等）。 */
    DjiR("djiR", CameraBrand.Dji),
    /** 使用索尼蓝牙遥控。 */
    Sony("sony", CameraBrand.Sony),
    /** 使用佳能“遥控器”方式，兼容 BR-E1。 */
    CanonRemote("canonRemote", CameraBrand.Canon),
    /** 使用佳能“智能手机”方式。 */
    CanonSmart("canonSmart", CameraBrand.Canon),
    /** 适配富士旧固件。 */
    FujifilmBasic("fujifilmBasic", CameraBrand.Fujifilm),
    /** 适配富士 2025 年 7 月以后的新固件。 */
    FujifilmSecure("fujifilmSecure", CameraBrand.Fujifilm),
    /** 使用尼康“蓝牙遥控器（ML-L7）”方式。 */
    NikonRemote("nikonRemote", CameraBrand.Nikon),
    /** 适配理光 GR III、IIIx、IV。 */
    Ricoh("ricoh", CameraBrand.Ricoh),
    /** 使用 Open GoPro。 */
    GoPro("gopro", CameraBrand.GoPro);

    companion object {
        fun parse(raw: String) = entries.firstOrNull { it.raw == raw }

        /** 按蓝牙广播识别相机，判断顺序与 furble 相同。 */
        fun detect(ad: Advertisement): CameraKind? {
            val m = ad.manufacturer
            if (m.size >= 5 && u(m, 0) == 0xAA && u(m, 1) == 0x08 && u(m, 4) == 0xFA) return DjiR
            if (FujifilmUuid.isBasic(ad)) return FujifilmBasic
            if (ad.advertises(CanonUuid.smartService)) return CanonSmart
            if (ad.advertises(CanonUuid.remoteService)) return CanonRemote
            // 尼康配对遥控器时只广播服务；带厂商数据的广播是在找已配对的遥控器。
            if (m.isEmpty() && ad.advertises(NikonUuid.service)) return NikonRemote
            if (RicohUuid.advertised.any(ad::advertises) || RicohUuid.nameMatches(ad.name)) return Ricoh
            if (SonyUuid.isPairing(ad)) return Sony
            if (FujifilmUuid.isSecure(ad)) return FujifilmSecure
            if (ad.advertises(GoProUuid.service)) return GoPro
            return null
        }
    }
}

/** 保存蓝牙广播里识别相机要用的部分。 */
class Advertisement(
    val name: String?,
    /** 保存厂商数据，前两字节是小端公司编号。 */
    val manufacturer: ByteArray,
    val services: List<UUID>,
) {
    fun company(id: Int): Boolean = manufacturer.size >= 2 && (u(manufacturer, 0) or (u(manufacturer, 1) shl 8)) == id

    fun advertises(uuid: UUID): Boolean = uuid in services

    companion object {
        /** 解析原始广播字节，厂商数据保留开头的公司编号，服务 UUID 收 16 位和 128 位两种。 */
        fun parse(raw: ByteArray, fallbackName: String?): Advertisement {
            var name: String? = null
            var manufacturer = ByteArray(0)
            val services = mutableListOf<UUID>()
            var i = 0
            while (i < raw.size) {
                val length = u(raw, i)
                if (length == 0 || i + 1 + length > raw.size) break
                val type = u(raw, i + 1)
                val data = raw.copyOfRange(i + 2, i + 1 + length)
                when (type) {
                    0x08, 0x09 -> name = String(data, Charsets.UTF_8).trimEnd('\u0000')
                    0xFF -> if (manufacturer.isEmpty()) manufacturer = data
                    0x02, 0x03 -> for (j in 0 until data.size / 2) services += uuid16(u(data, j * 2) or (u(data, j * 2 + 1) shl 8))
                    0x06, 0x07 -> for (j in 0 until data.size / 16) {
                        val chunk = data.copyOfRange(j * 16, j * 16 + 16).reversedArray()
                        val hex = chunk.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                        services += UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
                    }
                }
                i += 1 + length
            }
            return Advertisement(name ?: fallbackName, manufacturer, services)
        }
    }
}

/** 记录外接相机的状态，拿不到的项为空。 */
data class AccessoryStatus(
    val battery: Int? = null,
    val remainPhotos: Int? = null,
    /** 表示相机正在拍或在保存，此时按不下快门。 */
    val capturing: Boolean = false,
    val warm: Boolean = false,
    val tooHot: Boolean = false,
    val sleeping: Boolean = false,
    /** 标记不在拍照模式：DJI 填当前模式，其他品牌填 -1。 */
    val wrongMode: Int? = null,
    /** 记录 DJI 的模式，和 photoSize 一起组成界面说明。 */
    val djiMode: Int? = null,
    val photoSize: String? = null,
)

// MARK: - 索尼

object SonyUuid {
    const val COMPANY = 0x012D
    val control: UUID = uuid("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
    val command: UUID = uuid16(0xFF01)
    val notify: UUID = uuid16(0xFF02)

    val focusUp = byteArrayOf(0x01, 0x06)
    val shutterUp = byteArrayOf(0x01, 0x08)
    val shutterDown = byteArrayOf(0x01, 0x09)

    /** 判断索尼相机是否在等配对：公司 0x012D、类型 0x0003，第 10 字节三个配对标志位都要置位。 */
    fun isPairing(ad: Advertisement): Boolean {
        val m = ad.manufacturer
        if (!ad.company(COMPANY) || m.size < 13 || u(m, 2) != 0x03 || u(m, 3) != 0x00) return false
        val required = 0x80 or 0x40 or 0x02
        return u(m, 9) and required == required
    }
}

// MARK: - 佳能

object CanonUuid {
    // 遥控器方式（兼容 BR-E1）的服务和特征。
    val remoteService: UUID = uuid("00050000-0000-1000-0000-D8492FFFA821")
    val remoteId: UUID = uuid("00050002-0000-1000-0000-D8492FFFA821")
    val remoteControl: UUID = uuid("00050003-0000-1000-0000-D8492FFFA821")
    val remoteShutter = byteArrayOf((0x80 or 0x0C).toByte())
    val remoteRelease = byteArrayOf(0x0C)

    // 智能手机方式的服务和特征。
    val smartService: UUID = uuid("00010000-0000-1000-0000-D8492FFFA821")
    val name: UUID = uuid("00010006-0000-1000-0000-D8492FFFA821")
    val identity: UUID = uuid("0001000A-0000-1000-0000-D8492FFFA821")
    val modeService: UUID = uuid("00030000-0000-1000-0000-D8492FFFA821")
    val mode: UUID = uuid("00030010-0000-1000-0000-D8492FFFA821")
    val shutter: UUID = uuid("00030030-0000-1000-0000-D8492FFFA821")
    const val PAIR_ACCEPT = 0x02
    const val PAIR_REJECT = 0x03
    const val MODE_SHOOT = 0x02
    val smartPress = byteArrayOf(0x00, 0x01)
    val smartRelease = byteArrayOf(0x00, 0x02)
}

// MARK: - 富士

object FujifilmUuid {
    const val COMPANY = 0x04D8

    // 旧固件的服务和特征，广播里带 4 字节配对令牌。
    val basicService: UUID = uuid("AF854C2E-B214-458E-97E2-912C4ECF2CB8")
    val appService: UUID = uuid("117C4142-EDD4-4C77-8696-DD18EEBB770A")
    val pairService: UUID = uuid("91F1DE68-DFF6-466E-8B65-FF13B0F16FB8")
    val pair: UUID = uuid("ABA356EB-9633-4E60-B73F-F52516DBD671")
    val identity: UUID = uuid("85B9163E-62D1-49FF-A6F5-054B4630D4A1")
    val configService: UUID = uuid("4C0020FE-F3B6-40DE-ACC9-77D129067B14")
    val ind1: UUID = uuid("A68E3F66-0FCC-4395-8D4C-AA980B5877FA")
    val ind2: UUID = uuid("BD17BA04-B76B-4892-A545-B73BA1F74DAE")
    val not1: UUID = uuid("F9150137-5D40-4801-A8DC-F7FC5B01DA50")
    val geoUpdate: UUID = uuid("AD06C7B7-F41A-46F4-A29A-712055319122")
    val ind3: UUID = uuid("049EC406-EF75-4205-A390-08FE209C51F0")
    val shutterService: UUID = uuid("6514EB81-4E8F-458D-AA2A-E691336CDFAC")
    val shutter: UUID = uuid("7FCF49C6-4FF0-4777-A03D-1A79166AF7A8")

    // 新固件的服务和特征，连接加密，广播里带 5 字节相机序列号。
    val secureService: UUID = uuid("A9D2B304-E8D6-4902-8336-352B772D7597")
    val securePairService: UUID = uuid("123D8F06-62A1-4935-9322-833C531EE225")
    val status: UUID = uuid("F557D96B-8284-4667-8793-B971C1DECA2A")
    val notifyService: UUID = uuid("4E941240-D01D-46B9-A5EA-67636806830B")
    val not4: UUID = uuid("BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4")
    val not5: UUID = uuid("75823784-FBB7-4B71-ABAE-CD9A34072E3C")
    val not6: UUID = uuid("E6692C5C-B7CD-44F4-95FC-EDA07CE32560")
    val not7: UUID = uuid("AAB609C4-94DD-4D89-BC60-665D5090B828")
    val not8: UUID = uuid("2A125640-706D-4DD1-B420-C0F4AB93C361")
    val not9: UUID = uuid("82A9F452-C5CE-4EF5-8203-3FC9A47F8171")
    val not10: UUID = uuid("DEEF7187-3F43-4364-9E22-11A8C8A15951")
    val geoSyncInterval: UUID = uuid("C95D91AE-B247-4D6D-8661-7DD5D6A0F85B")

    /** 发送快门命令头，之后写 02 00 按下、00 00 松开。 */
    val shutterCommand = byteArrayOf(0x01, 0x00)
    val shutterPress = byteArrayOf(0x02, 0x00)
    val shutterRelease = byteArrayOf(0x00, 0x00)

    fun isBasic(ad: Advertisement) = ad.company(COMPANY) && ad.manufacturer.size == 7 && u(ad.manufacturer, 2) == 0x02 &&
        (ad.advertises(basicService) || ad.advertises(appService))

    fun isSecure(ad: Advertisement) = ad.company(COMPANY) && ad.manufacturer.size == 8 && ad.advertises(secureService)

    /** 取出旧固件配对广播里的令牌。 */
    fun token(ad: Advertisement): ByteArray = if (ad.manufacturer.size == 7) ad.manufacturer.copyOfRange(3, 7) else ByteArray(0)

    /** 取出新固件广播里的相机序列号。 */
    fun serial(ad: Advertisement): ByteArray = if (ad.manufacturer.size == 8) ad.manufacturer.copyOfRange(3, 8) else ByteArray(0)

    /** 判断是否新固件相机在重连：广播配对服务并带同一序列号。 */
    fun isReconnect(ad: Advertisement, serial: ByteArray) =
        serial.isNotEmpty() && ad.company(COMPANY) && ad.advertises(securePairService) && serial(ad).contentEquals(serial)
}

// MARK: - 尼康

object NikonUuid {
    const val COMPANY = 0x0399
    val service: UUID = uuid("0000DE00-3DD4-4255-8D62-6DC7B9BD5561")
    val remotePair: UUID = uuid("00002087-3DD4-4255-8D62-6DC7B9BD5561")
    val remoteIndication: UUID = uuid("00002084-3DD4-4255-8D62-6DC7B9BD5561")
    val remoteShutter: UUID = uuid("00002083-3DD4-4255-8D62-6DC7B9BD5561")
    /** 标出“智能设备”方式的配对特征，它要传统蓝牙，App 不用。 */
    val smartPair: UUID = uuid("00002000-3DD4-4255-8D62-6DC7B9BD5561")

    val shutterPress = byteArrayOf(0x02, 0x02)
    val shutterRelease = byteArrayOf(0x02, 0x00)
}

/** 处理尼康遥控器配对握手，每条 17 字节：阶段 1 字节、时间戳 8 字节、标识或序列号 8 字节。 */
object NikonPairing {
    /** 生成遥控器标识：4 字节设备号（首字节 01）加 4 字节随机数。 */
    fun newId(random: Random = Random.Default): ByteArray = byteArrayOf(0x01) + random.nextBytes(7)

    fun stage1(id: ByteArray): ByteArray = byteArrayOf(0x01) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x01) + id

    /** 给出相机应答的阶段 2，其余字节全为 0。 */
    val stage2: ByteArray = byteArrayOf(0x02) + ByteArray(16)
    val stage3: ByteArray = byteArrayOf(0x03) + ByteArray(16)

    /** 从阶段 4 应答取出相机序列号，格式不符时返回 null。 */
    fun serialFromStage4(m: ByteArray): String? {
        if (m.size < 17 || u(m, 0) != 0x04 || (1..8).any { m[it].toInt() != 0 }) return null
        val serial = m.copyOfRange(9, 17)
        val end = serial.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) serial.size else it }
        return String(serial, 0, end, Charsets.UTF_8)
    }

    /** 判断是否已配对相机在重连：厂商数据里带遥控器设备号。 */
    fun isReconnect(ad: Advertisement, id: ByteArray) = id.size >= 4 && ad.company(NikonUuid.COMPANY) &&
        ad.manufacturer.size >= 6 && ad.manufacturer.copyOfRange(2, 6).contentEquals(id.copyOfRange(0, 4)) && ad.advertises(NikonUuid.service)
}

// MARK: - 理光

object RicohUuid {
    val info: UUID = uuid("9A5ED1C5-74CC-4C50-B5B6-66A48E7CCFF1")
    val model: UUID = uuid("35FE6272-6AA5-44D9-88E1-F09427F51A71")
    val camera: UUID = uuid("4B445988-CAA0-4DD3-941D-37B4F52ACA86")
    val power: UUID = uuid("B58CE84C-0666-4DE9-BEC8-2D27B27B3211")
    val operationMode: UUID = uuid("1452335A-EC7F-4877-B8AB-0F72E18BB295")
    val shooting: UUID = uuid("9F00F387-8345-4BBC-8B92-B87B52E3091A")
    val shootingFlavor: UUID = uuid("B29E6DE3-1AEC-48C1-9D05-02CEA57CE664")
    val operationRequest: UUID = uuid("559644B8-E0BC-4011-929B-5CF9199851E7")
    val captureStatus: UUID = uuid("B5589C08-B5FD-46F5-BE7D-AB1B8C074CAA")
    val bluetoothControl: UUID = uuid("0F291746-0C80-4726-87A7-3C501FD3B4B6")

    val advertised = listOf(info, camera, shooting, bluetoothControl)
    /** 选择立即拍摄，不用自拍定时。 */
    val flavorImmediate = byteArrayOf(0x00)
    /** 开始拍摄并自动对焦。 */
    val start = byteArrayOf(0x01, 0x01)

    fun nameMatches(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val upper = name.uppercase()
        return upper == "GR" || listOf("RICOH", "PENTAX", "GR ", "GRIII", "GR III").any { upper.contains(it) }
    }
}

// MARK: - GoPro

object GoProUuid {
    val service: UUID = uuid16(0xFEA6)
    val command: UUID = uuid("B5F90072-AA8D-11E3-9046-0002A5D5C51B")
    val commandResponse: UUID = uuid("B5F90073-AA8D-11E3-9046-0002A5D5C51B")
    val settings: UUID = uuid("B5F90074-AA8D-11E3-9046-0002A5D5C51B")
    val settingsResponse: UUID = uuid("B5F90075-AA8D-11E3-9046-0002A5D5C51B")
    val query: UUID = uuid("B5F90076-AA8D-11E3-9046-0002A5D5C51B")
    val queryResponse: UUID = uuid("B5F90077-AA8D-11E3-9046-0002A5D5C51B")
}

/** 列出 Open GoPro 命令，每条第一个字节是长度。 */
object GoProCommand {
    /** 按下快门，拍照模式下拍一张。 */
    val shutterOn = byteArrayOf(0x03, 0x01, 0x01, 0x01)
    /** 声明自己是第三方客户端。 */
    val thirdPartyClient = byteArrayOf(0x01, 0x50)
    /** 切到拍照预设组 1001。 */
    val photoPresetGroup = byteArrayOf(0x04, 0x3E, 0x02, 0x03, 0xE9.toByte())
    /** 保持连接：把设置 91（LED）写成 66。 */
    val keepAlive = byteArrayOf(0x03, 0x5B, 0x01, 0x42)

    object StatusId {
        const val HOT = 6
        const val BUSY = 8
        const val REMAINING_PHOTOS = 34
        const val BATTERY = 70
    }

    /** 订阅电量、剩余张数、忙碌、过热四项状态。 */
    val registerStatus = byteArrayOf(0x05, 0x53, StatusId.BATTERY.toByte(), StatusId.REMAINING_PHOTOS.toByte(),
        StatusId.BUSY.toByte(), StatusId.HOT.toByte())

    /** 解析状态回应 [查询编号, 结果, (编号, 长度, 值)…]，结果非 0 时返回 null。 */
    fun statuses(message: ByteArray): Map<Int, ByteArray>? {
        if (message.size < 2 || u(message, 0) !in listOf(0x13, 0x53, 0x93) || message[1].toInt() != 0) return null
        val values = mutableMapOf<Int, ByteArray>()
        var i = 2
        while (i + 2 <= message.size) {
            val id = u(message, i)
            val length = u(message, i + 1)
            i += 2
            if (i + length > message.size) break
            values[id] = message.copyOfRange(i, i + length)
            i += length
        }
        return values
    }

    /** 把字节读成大端无符号整数。 */
    fun integer(bytes: ByteArray): Int = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
}

/** 拼接 Open GoPro 分包：每包最多 20 字节，首包带长度头，后续包首字节最高位为 1。 */
class GoProAssembler {
    private var buffer = ByteArray(0)
    private var expected = 0

    /** 收下一包，拼出完整消息时返回它。 */
    fun append(packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null
        val first = u(packet, 0)
        if (first and 0x80 != 0) {
            if (expected <= 0) return null
            buffer += packet.copyOfRange(1, packet.size)
        } else {
            when ((first shr 5) and 0x03) {
                0 -> {
                    expected = first and 0x1F
                    buffer = packet.copyOfRange(1, packet.size)
                }
                1 -> {
                    if (packet.size < 2) return null
                    expected = ((first and 0x1F) shl 8) or u(packet, 1)
                    buffer = packet.copyOfRange(2, packet.size)
                }
                2 -> {
                    if (packet.size < 3) return null
                    expected = (u(packet, 1) shl 8) or u(packet, 2)
                    buffer = packet.copyOfRange(3, packet.size)
                }
                else -> return null
            }
        }
        if (expected <= 0 || buffer.size < expected) return null
        val message = buffer.copyOfRange(0, expected)
        buffer = ByteArray(0)
        expected = 0
        return message
    }
}
