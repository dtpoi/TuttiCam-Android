// Portions copyright (c) 2014-Present DJI. All rights reserved.

package com.thinktough.tutticam.protocol.accessory

/**
 * 编解码 DJI R SDK 帧（见 dji-sdk/Osmo-GPS-Controller-Demo 的 docs/protocol*.md），小端布局如下：
 * | SOF 0xAA | Ver/Length 2 | CmdType 1 | ENC 1 | RES 3 | SEQ 2 | CRC16 2 | CmdSet 1 | CmdID 1 | 载荷 n | CRC32 4 |
 */
object DjiProtocol {
    const val SOF = 0xAA
    /** 给出帧里除数据外的字节数。 */
    const val OVERHEAD = 16

    // 计算 CRC：反射多项式，初值 0x3AA3，无最终异或。

    fun crc16(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0x3AA3
        for (i in from until to) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    fun crc32(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Long {
        var crc = 0x3AA3L
        for (i in from until to) {
            crc = crc xor (bytes[i].toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
        }
        return crc and 0xFFFFFFFFL
    }

    /** 定义 CmdType：低 5 位是应答要求，第 5 位为 1 表示应答帧。 */
    object CmdType {
        const val NO_RESPONSE = 0x00
        const val RESPONSE_OPTIONAL = 0x01
        const val RESPONSE_REQUIRED = 0x02
        const val ACK = 0x20
    }

    data class Frame(
        val cmdType: Int,
        val seq: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray,
        /** 保存 ENC 后的 3 个保留字节，通常为 0。 */
        val reserved: ByteArray = ByteArray(3),
    ) {
        val isAck: Boolean get() = cmdType and 0x20 != 0

        override fun equals(other: Any?): Boolean = other is Frame && cmdType == other.cmdType && seq == other.seq &&
            cmdSet == other.cmdSet && cmdId == other.cmdId && payload.contentEquals(other.payload) && reserved.contentEquals(other.reserved)

        override fun hashCode(): Int = ((cmdType * 31 + seq) * 31 + cmdSet) * 31 + cmdId
    }

    fun encode(frame: Frame): ByteArray {
        val data = byteArrayOf(frame.cmdSet.toByte(), frame.cmdId.toByte()) + frame.payload
        val length = OVERHEAD + data.size
        var bytes = byteArrayOf(SOF.toByte()) + le16(length and 0x03FF) + byteArrayOf(frame.cmdType.toByte(), 0) +
            frame.reserved + le16(frame.seq)
        bytes += le16(crc16(bytes))
        bytes += data
        bytes += le32(crc32(bytes))
        return bytes
    }

    /** 解析一整帧，长度或校验不对时返回 null。 */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < OVERHEAD + 2 || u(bytes, 0) != SOF) return null
        val length = readLe16(bytes, 1) and 0x03FF
        if (length != bytes.size) return null
        if (readLe16(bytes, 10) != crc16(bytes, 0, 10)) return null
        if (readLe32(bytes, length - 4) != crc32(bytes, 0, length - 4)) return null
        return Frame(u(bytes, 3), readLe16(bytes, 8), u(bytes, 12), u(bytes, 13), bytes.copyOfRange(14, length - 4), bytes.copyOfRange(5, 8))
    }

    /** 拼接蓝牙通知，按 0xAA 帧头切出校验正确的帧，跳过 0x55 开头的其他帧。 */
    class StreamParser {
        private var buffer = ByteArray(0)

        fun append(chunk: ByteArray): List<Frame> {
            buffer += chunk
            val frames = mutableListOf<Frame>()
            while (true) {
                val start = buffer.indexOfFirst { it.toInt() and 0xFF == SOF }
                if (start < 0) {
                    buffer = ByteArray(0)
                    break
                }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size < 3) break
                val length = readLe16(buffer, 1) and 0x03FF
                if (length < OVERHEAD + 2 || length > 1024) {
                    buffer = buffer.copyOfRange(1, buffer.size) // 跳过假帧头
                    continue
                }
                // 先校验 CRC16，避免偶然的 0xAA 读出大长度后一直空等。
                if (buffer.size < 12) break
                if (readLe16(buffer, 10) != crc16(buffer, 0, 10)) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }
                if (buffer.size < length) break
                val frame = decode(buffer.copyOfRange(0, length))
                if (frame != null) {
                    frames += frame
                    buffer = buffer.copyOfRange(length, buffer.size)
                } else {
                    buffer = buffer.copyOfRange(1, buffer.size)
                }
            }
            return frames
        }
    }

    // 读写小端整数。

    fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    fun le32(value: Long) = ByteArray(4) { ((value shr (8 * it)) and 0xFF).toByte() }
    fun readLe16(bytes: ByteArray, offset: Int) = u(bytes, offset) or (u(bytes, offset + 1) shl 8)
    fun readLe32(bytes: ByteArray, offset: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or (u(bytes, offset + i).toLong() shl (8 * i)) }
}

/** 按无符号数读一个字节。 */
internal fun u(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF

// MARK: - Osmo 相机命令

object OsmoCommand {
    /** 按 device_id 返回相机型号，不认识时返回 null。 */
    fun modelName(deviceId: Long): String? = when ((deviceId and 0xFFFF).toInt()) {
        0xFF66 -> "Osmo 360"
        0xFF55 -> "Osmo Action 6"
        0xFF44 -> "Osmo Action 5 Pro"
        0xFF33 -> "Osmo Action 4"
        else -> null
    }

    /** 列出状态推送 1D02 里的 camera_mode 取值。 */
    object Mode {
        const val SLOW_MOTION = 0x00
        const val VIDEO = 0x01
        const val PHOTO = 0x05
        /** 表示低光视频（超级夜景）。 */
        const val LOW_LIGHT_VIDEO = 0x28
        const val PANORAMA_VIDEO = 0x38
        /** 表示 Osmo 360 全景拍照。 */
        const val PANORAMA_PHOTO = 0x3F
        /** 表示 Osmo 360 极广角视频。 */
        const val ULTRA_WIDE_VIDEO = 0x41

        /** 列出录像类模式：开始拍摄时开录、停止拍摄时停录，不按单张快门。 */
        val VIDEOS = setOf(SLOW_MOTION, VIDEO, LOW_LIGHT_VIDEO, PANORAMA_VIDEO, ULTRA_WIDE_VIDEO)
    }

    /** 生成 33 字节的连接请求（0019）载荷。 */
    fun connectionRequest(deviceId: Long, mac: ByteArray, verifyMode: Int, verifyCode: Int): ByteArray {
        val field = mac.copyOf(minOf(mac.size, 16))
        return DjiProtocol.le32(deviceId) + byteArrayOf(field.size.toByte()) + field.copyOf(16) + DjiProtocol.le32(0) +
            byteArrayOf(0, verifyMode.toByte()) + DjiProtocol.le16(verifyCode) + ByteArray(4)
    }

    /** 应答相机的连接请求，允许连接并分配相机编号（0 表示单机）。 */
    fun connectionAck(deviceId: Long, cameraNumber: Int): ByteArray =
        DjiProtocol.le32(deviceId) + byteArrayOf(0x00, cameraNumber.toByte(), 0, 0, 0)

    /** 上报拍录键单击（0011），效果等于短按快门，是官方推荐的触发方式。 */
    val shutterClick = byteArrayOf(0x01, 0x01, 0x00, 0x00)

    /** 生成拍录控制命令（1D03）载荷：start 为 true 开始录像，false 停止；device_id 和保留字节照抄官方示例。 */
    fun recordControl(start: Boolean): ByteArray = DjiProtocol.le32(0x33FF0000L) + byteArrayOf(if (start) 0 else 1, 0, 0, 0, 0)

    /** 生成模式切换命令（1D04），device_id 和保留字节照抄官方示例。 */
    fun modeSwitch(mode: Int): ByteArray = DjiProtocol.le32(0xFF330000L) + byteArrayOf(mode.toByte(), 0x01, 0x47, 0x39, 0x36)

    /** 订阅相机状态（1D05），按 2 Hz 和状态变化推送。 */
    val statusSubscription = byteArrayOf(0x03, 20, 0, 0, 0, 0)

    /** 列出电源模式设置（001A）的取值。 */
    object PowerMode {
        const val NORMAL = 0
        const val SLEEP = 3
    }
}

/** 保存状态推送（1D02）里 App 用到的字段。 */
data class OsmoStatus(
    val mode: Int,
    /** 记录拍摄状态，0x03 表示正在拍照或录像。 */
    val status: Int,
    val photoSize: Int,
    val remainPhotos: Long,
    /** 记录温度：0 正常，1 偏高，2 过热不能拍，3 即将关机。 */
    val temperature: Int,
    val battery: Int,
    /** 记录电源模式，3 表示休眠。 */
    val powerMode: Int,
    /** 记录还能录多少秒，录像模式下用来代替剩余张数。 */
    val remainSeconds: Long = 0,
) {
    val capturing: Boolean get() = status == 0x03
    val sleeping: Boolean get() = powerMode == 3
    val tooHot: Boolean get() = temperature >= 2
    val photoMode: Boolean get() = mode == OsmoCommand.Mode.PANORAMA_PHOTO || mode == OsmoCommand.Mode.PHOTO
    val videoMode: Boolean get() = mode in OsmoCommand.Mode.VIDEOS

    /** 给出拍照模式下的照片尺寸说明，如 30MP。 */
    val photoSizeTitle: String?
        get() {
            if (!photoMode) return null
            return when (photoSize) {
                4 -> "30MP"
                3 -> "20MP"
                2 -> "12MP"
                else -> null
            }
        }

    companion object {
        fun parse(p: ByteArray): OsmoStatus? {
            if (p.size < 38) return null
            return OsmoStatus(u(p, 0), u(p, 1), u(p, 2), DjiProtocol.readLe32(p, 19), u(p, 30), u(p, 37), u(p, 28), DjiProtocol.readLe32(p, 23))
        }
    }
}

// MARK: - 连接握手

/**
 * 处理与一台 DJI 相机的握手和命令收发，不依赖蓝牙。
 * 手机发连接请求，相机先应答；用户确认后相机回发请求，手机应答后订阅状态。
 */
class OsmoSession(private val deviceId: Long, private val mac: ByteArray, private val cameraNumber: Int) {
    sealed interface Phase {
        data object Idle : Phase
        /** 等用户在相机上确认，code 是要显示给用户核对的配对码。 */
        data class WaitingForCamera(val code: Int) : Phase
        /** 表示已连接，model 为空时界面改用 cameraDeviceId。 */
        data class Connected(val model: String?, val cameraDeviceId: Long) : Phase
        data object Rejected : Phase
        /** 表示相机拒绝了连接，returnCode 是返回码。 */
        data class Failed(val returnCode: Int) : Phase
    }

    sealed interface Output {
        data class Send(val bytes: ByteArray) : Output {
            override fun equals(other: Any?) = other is Send && bytes.contentEquals(other.bytes)
            override fun hashCode() = bytes.contentHashCode()
        }
        data class PhaseChanged(val phase: Phase) : Output
        data class Status(val status: OsmoStatus) : Output
        /** 回报快门命令的应答，用来估算往返耗时。 */
        data class ShutterAck(val seq: Int) : Output
    }

    var phase: Phase = Phase.Idle
        private set
    private var seq = 0
    private val pendingShutters = mutableSetOf<Int>()
    private val parser = DjiProtocol.StreamParser()

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFFFF
        return seq
    }

    private fun command(cmdSet: Int, cmdId: Int, payload: ByteArray, type: Int = DjiProtocol.CmdType.RESPONSE_OPTIONAL): Pair<ByteArray, Int> {
        val s = nextSeq()
        return DjiProtocol.encode(DjiProtocol.Frame(type, s, cmdSet, cmdId, payload)) to s
    }

    /** 开始握手，paired 为 true 时相机通常不再弹出配对确认。 */
    fun start(paired: Boolean, code: Int): List<Output> {
        val payload = OsmoCommand.connectionRequest(deviceId, mac, if (paired) 0 else 1, code)
        val request = command(0x00, 0x19, payload, DjiProtocol.CmdType.RESPONSE_REQUIRED)
        phase = Phase.WaitingForCamera(code)
        return listOf(Output.Send(request.first), Output.PhaseChanged(phase))
    }

    /** 生成一条快门命令，连接建立前返回 null。 */
    fun shutter(): ByteArray? {
        if (phase !is Phase.Connected) return null
        val (bytes, s) = command(0x00, 0x11, OsmoCommand.shutterClick)
        pendingShutters += s
        return bytes
    }

    /** 生成让相机休眠的命令（001A）；休眠后不能再给相机发任何数据，否则可能堵塞。 */
    fun sleep(): ByteArray? {
        if (phase !is Phase.Connected) return null
        return command(0x00, 0x1A, byteArrayOf(OsmoCommand.PowerMode.SLEEP.toByte())).first
    }

    /** 生成开始或停止录像的命令（1D03），连接建立前返回 null。 */
    fun record(start: Boolean): ByteArray? {
        if (phase !is Phase.Connected) return null
        return command(0x1D, 0x03, OsmoCommand.recordControl(start)).first
    }

    fun switchMode(mode: Int): ByteArray? {
        if (phase !is Phase.Connected) return null
        return command(0x1D, 0x04, OsmoCommand.modeSwitch(mode)).first
    }

    /** 处理蓝牙收到的一段数据。 */
    fun receive(chunk: ByteArray): List<Output> = parser.append(chunk).flatMap(::handle)

    private fun handle(frame: DjiProtocol.Frame): List<Output> {
        val p = frame.payload
        return when {
            frame.cmdSet == 0x00 && frame.cmdId == 0x19 && frame.isAck -> {
                // 相机应答我们的请求，ret_code 非 0 表示失败。
                if (p.size >= 5 && u(p, 4) != 0) {
                    phase = Phase.Failed(u(p, 4))
                    listOf(Output.PhaseChanged(phase))
                } else emptyList()
            }
            frame.cmdSet == 0x00 && frame.cmdId == 0x19 -> {
                // 相机发来用户确认的结果。
                if (p.size < 29 || u(p, 26) != 2) return emptyList()
                val cameraDeviceId = DjiProtocol.readLe32(p, 0)
                if (DjiProtocol.readLe16(p, 27) != 0) {
                    phase = Phase.Rejected
                    return listOf(Output.PhaseChanged(phase))
                }
                // 用相同的 SEQ 应答，然后订阅状态；不动相机模式，拍照、录像由驱动按状态处理。
                val ack = DjiProtocol.Frame(DjiProtocol.CmdType.ACK, frame.seq, 0x00, 0x19, OsmoCommand.connectionAck(deviceId, cameraNumber))
                phase = Phase.Connected(OsmoCommand.modelName(cameraDeviceId), cameraDeviceId)
                val subscribe = command(0x1D, 0x05, OsmoCommand.statusSubscription)
                listOf(Output.Send(DjiProtocol.encode(ack)), Output.PhaseChanged(phase), Output.Send(subscribe.first))
            }
            frame.cmdSet == 0x1D && frame.cmdId == 0x02 -> listOfNotNull(OsmoStatus.parse(p)?.let(Output::Status))
            frame.cmdSet == 0x00 && frame.cmdId == 0x11 && frame.isAck ->
                if (pendingShutters.remove(frame.seq)) listOf(Output.ShutterAck(frame.seq)) else emptyList()
            else -> emptyList()
        }
    }
}
