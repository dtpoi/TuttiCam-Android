package com.thinktough.tutticam.accessory

import com.thinktough.tutticam.R
import com.thinktough.tutticam.engine.MonoClock
import com.thinktough.tutticam.protocol.accessory.AccessoryStatus
import com.thinktough.tutticam.protocol.accessory.CanonUuid
import com.thinktough.tutticam.protocol.accessory.DjiProtocol
import com.thinktough.tutticam.protocol.accessory.FujifilmUuid
import com.thinktough.tutticam.protocol.accessory.GoProAssembler
import com.thinktough.tutticam.protocol.accessory.GoProCommand
import com.thinktough.tutticam.protocol.accessory.GoProUuid
import com.thinktough.tutticam.protocol.accessory.NikonPairing
import com.thinktough.tutticam.protocol.accessory.NikonUuid
import com.thinktough.tutticam.protocol.accessory.OsmoCommand
import com.thinktough.tutticam.protocol.accessory.OsmoSession
import com.thinktough.tutticam.protocol.accessory.OsmoStatus
import com.thinktough.tutticam.protocol.accessory.RicohUuid
import com.thinktough.tutticam.protocol.accessory.SonyUuid
import com.thinktough.tutticam.protocol.accessory.uuid16
import java.util.UUID
import kotlin.random.Random

/** 定义驱动访问蓝牙连接的接口，所有调用都在蓝牙线程。 */
interface DriverIO {
    /** 表示这台相机以前配对过，这次是重连。 */
    val paired: Boolean
    val isConnected: Boolean
    /** 给出本机作为蓝牙遥控器的身份。 */
    val identity: RemoteIdentity
    /** 表示半秒内有快门要发，此时不发保活等命令。 */
    val shutterSoon: Boolean
    fun has(characteristic: UUID): Boolean
    /** 写入特征；fast 时用无回应写入并排到队首。 */
    fun write(bytes: ByteArray, to: UUID, fast: Boolean = false)
    fun subscribe(characteristic: UUID)
    fun read(characteristic: UUID)
    /** 延时执行，期间连接断开或更换就取消。 */
    fun after(seconds: Double, work: () -> Unit)
    /** 进入等用户确认的状态，DJI 带上配对码。 */
    fun confirming(code: Int?)
    fun connected(model: String)
    fun rejected()
    fun fail(reason: String)
    fun status(status: AccessoryStatus)
    fun shutterRtt(ms: Double)
    fun text(id: Int, vararg args: Any): String
    fun log(message: String)
}

/**
 * 实现一种相机协议的握手和快门，每次连接新建一个实例，只在蓝牙线程使用。
 * 各驱动在连接阶段先读写一次受保护的特征，借此触发系统配对。
 */
abstract class CameraDriver {
    /** 列出要查找的服务。 */
    open val services: List<UUID> = emptyList()
    /** 设定在约定时刻前多久调用 prepare，0 表示不调用。 */
    open val prepareLead: Double = 0.0
    /** 返回要随配对保存的数据，如富士令牌、尼康遥控器标识。 */
    open val secret: ByteArray? = null

    /** 按特征计数连接阶段未完成的订阅和写入，清零后调用 ready。 */
    private val pending = mutableMapOf<UUID, Int>()
    /** 列出订阅失败也不影响连接的特征。 */
    private val optional = mutableSetOf<UUID>()
    /** 记录最近一次快门命令，收到写入回应时算往返时间。 */
    private var probe: Pair<UUID, Double>? = null

    /** 在服务和特征都找到后开始握手。 */
    open fun begin(io: DriverIO) {}
    /** 在连接阶段的订阅和写入全部完成后调用。 */
    open fun ready(io: DriverIO) {}
    /** 处理收到的通知、指示或读取结果。 */
    open fun received(value: ByteArray, from: UUID, io: DriverIO) {}
    open fun prepare(io: DriverIO) {}
    /** 在约定时刻按快门，没发出时（如相机休眠）返回 false。 */
    open fun shutter(io: DriverIO): Boolean = false
    /** 每秒调用一次，用于保活等。 */
    open fun tick(io: DriverIO) {}
    /** 表示能用命令让相机休眠，目前只有 DJI；唤醒要按相机上的按键。 */
    open val canSleep: Boolean = false
    /** 让相机休眠，没发出时返回 false。 */
    open fun sleep(io: DriverIO): Boolean = false
    /** 通知开始或停止拍摄；录像模式的 DJI 据此开录、停录。 */
    open fun shooting(on: Boolean, io: DriverIO) {}

    fun wrote(characteristic: UUID, error: String?, io: DriverIO) {
        if (error != null) {
            if (io.isConnected) io.log("写入 $characteristic 失败：$error") else io.fail(error)
            return
        }
        probe?.let { (target, sentAt) ->
            if (target == characteristic) {
                probe = null
                io.shutterRtt((MonoClock.now() - sentAt) * 1000)
            }
        }
        complete(characteristic, io)
    }

    fun notifying(characteristic: UUID, error: String?, io: DriverIO) {
        if (error != null && !io.isConnected && characteristic !in optional) {
            io.fail(error)
            return
        }
        complete(characteristic, io)
    }

    // 以下供子类调用。

    /** 加一步连接阶段的写入，完成后才算连接好。 */
    protected fun step(write: ByteArray, to: UUID, io: DriverIO) {
        pending[to] = (pending[to] ?: 0) + 1
        io.write(write, to)
    }

    /** 加一步连接阶段的订阅，optional 时失败也不影响连接。 */
    protected fun step(subscribe: UUID, io: DriverIO, isOptional: Boolean = false) {
        if (!io.has(subscribe)) {
            if (!isOptional) io.fail(io.text(R.string.acc_missing_characteristic, subscribe.toString().take(8).uppercase()))
            return
        }
        if (isOptional) optional += subscribe
        pending[subscribe] = (pending[subscribe] ?: 0) + 1
        io.subscribe(subscribe)
    }

    /** 发出快门命令，收到写入回应时报告往返时间。 */
    fun press(bytes: ByteArray, to: UUID, io: DriverIO) {
        probe = to to MonoClock.now()
        io.write(bytes, to, fast = true)
    }

    /** 按下并在 hold 秒后松开，模拟按一下快门键。 */
    protected fun click(down: ByteArray, up: ByteArray, on: UUID, io: DriverIO, hold: Double = 0.1) {
        press(down, on, io)
        io.after(hold) { io.write(up, on) }
    }

    private fun complete(characteristic: UUID, io: DriverIO) {
        val count = pending[characteristic] ?: return
        if (count > 1) pending[characteristic] = count - 1 else pending.remove(characteristic)
        if (pending.isEmpty() && !io.isConnected) ready(io)
    }
}

// MARK: - DJI

/** 把 DJI 状态换成通用的外接相机状态。 */
fun djiAccessoryStatus(s: OsmoStatus) = AccessoryStatus(
    battery = s.battery, remainPhotos = if (s.videoMode) null else s.remainPhotos.toInt(),
    remainMinutes = if (s.videoMode) (s.remainSeconds / 60).toInt() else null, capturing = s.capturing, warm = s.temperature == 1,
    tooHot = s.tooHot, sleeping = s.sleeping, wrongMode = if (s.photoMode || s.videoMode) null else s.mode, djiMode = s.mode,
    photoSize = s.photoSizeTitle, video = s.videoMode,
)

/** 驱动 DJI 相机，协议细节由 OsmoSession 处理。 */
class DjiDriver(identity: RemoteIdentity, cameraNumber: Int) : CameraDriver() {
    companion object {
        val SERVICE: UUID = uuid16(0xFFF0)
        val NOTIFY: UUID = uuid16(0xFFF4)
        val COMMAND: UUID = uuid16(0xFFF5)
    }

    private val session = OsmoSession(identity.deviceId, identity.mac, cameraNumber)
    private val sentAt = mutableMapOf<Int, Double>()
    /** 标记相机在休眠，DJI 要求休眠时不发数据以免堵塞。 */
    private var sleeping = false
    private var lastStatus: OsmoStatus? = null
    private var model: String? = null
    /** 记录主机是否在拍摄。 */
    private var shooting = false
    /** 标记录像是本 App 开的，停止拍摄时只停自己开的。 */
    private var recording = false
    private var modeChecked = false

    override val canSleep = true

    override fun shooting(on: Boolean, io: DriverIO) {
        shooting = on
        syncRecording(io)
    }

    /** 录像模式下按拍摄状态开录、停录；拍摄中切到录像模式或重连上来也会开录。 */
    private fun syncRecording(io: DriverIO) {
        val status = lastStatus ?: return
        if (sleeping) return
        when {
            shooting && status.videoMode && !recording && !status.capturing -> record(true, io)
            !shooting && recording -> record(false, io)
            // 用户切回拍照模式时相机自己会停。
            !status.videoMode && recording -> recording = false
        }
    }

    private fun record(start: Boolean, io: DriverIO) {
        val bytes = session.record(start) ?: return
        io.write(bytes, COMMAND, fast = true)
        recording = start
        io.log(if (start) "DJI：录像模式，开始录像" else "DJI：停止录像")
    }

    /** 连上后看一次模式：拍照、录像都照用；延时、直播等其他模式切到拍照，Osmo 360 用全景拍照。 */
    private fun checkMode(status: OsmoStatus, io: DriverIO) {
        if (modeChecked) return
        modeChecked = true
        if (status.photoMode || status.videoMode) return
        val mode = if (model == "Osmo 360") OsmoCommand.Mode.PANORAMA_PHOTO else OsmoCommand.Mode.PHOTO
        session.switchMode(mode)?.let { io.write(it, COMMAND, fast = true) }
    }

    override fun sleep(io: DriverIO): Boolean {
        if (sleeping) return false
        val bytes = session.sleep() ?: return false
        io.write(bytes, COMMAND, fast = true)
        // 先当作已休眠，不再给相机发数据；唤醒后状态推送会改回来。
        sleeping = true
        lastStatus?.let { io.status(djiAccessoryStatus(it.copy(powerMode = OsmoCommand.PowerMode.SLEEP))) }
        io.log("DJI：已请求休眠")
        return true
    }

    override val services = listOf(SERVICE)

    override fun begin(io: DriverIO) {
        if (!io.has(NOTIFY) || !io.has(COMMAND)) {
            io.fail(io.text(R.string.acc_no_dji_service))
            return
        }
        step(subscribe = NOTIFY, io = io)
    }

    /** 在通知打开后开始握手。 */
    override fun ready(io: DriverIO) = apply(session.start(io.paired, Random.nextInt(1000, 10000)), io)

    override fun received(value: ByteArray, from: UUID, io: DriverIO) {
        if (from == NOTIFY) apply(session.receive(value), io)
    }

    override fun shutter(io: DriverIO): Boolean {
        if (sleeping) {
            io.log("DJI：相机休眠中，跳过这次快门")
            return false
        }
        // 录像模式在开始、停止拍摄时开录、停录，每组不按快门，免得把录像停掉。
        if (lastStatus?.videoMode == true) return false
        val bytes = session.shutter() ?: return false
        sentAt[DjiProtocol.readLe16(bytes, 8)] = MonoClock.now()
        if (sentAt.size > 20) sentAt.clear()
        io.write(bytes, COMMAND, fast = true)
        return true
    }

    private fun apply(outputs: List<OsmoSession.Output>, io: DriverIO) {
        for (output in outputs) {
            when (output) {
                is OsmoSession.Output.Send -> io.write(output.bytes, COMMAND, fast = true)
                is OsmoSession.Output.PhaseChanged -> when (val phase = output.phase) {
                    OsmoSession.Phase.Idle -> Unit
                    is OsmoSession.Phase.WaitingForCamera -> if (!io.paired) io.confirming(phase.code)
                    is OsmoSession.Phase.Connected -> {
                        model = phase.model
                        io.connected(phase.model ?: io.text(R.string.acc_dji_camera_id, "0x%04X".format(phase.cameraDeviceId and 0xFFFF)))
                    }
                    OsmoSession.Phase.Rejected -> io.rejected()
                    is OsmoSession.Phase.Failed -> io.fail(io.text(R.string.acc_dji_refused, phase.returnCode))
                }
                is OsmoSession.Output.Status -> {
                    lastStatus = output.status
                    sleeping = output.status.sleeping
                    io.status(djiAccessoryStatus(output.status))
                    if (!sleeping) {
                        checkMode(output.status, io)
                        syncRecording(io)
                    }
                }
                is OsmoSession.Output.ShutterAck -> sentAt.remove(output.seq)?.let { io.shutterRtt((MonoClock.now() - it) * 1000) }
            }
        }
    }
}

// MARK: - 索尼（移植自 furble）

class SonyDriver : CameraDriver() {
    override val services = listOf(SonyUuid.control)

    override fun begin(io: DriverIO) {
        if (!io.has(SonyUuid.command)) {
            io.fail(io.text(R.string.acc_no_sony_service))
            return
        }
        if (!io.paired) io.confirming(null)
        // 写一次无副作用的“松开半按”来触发配对，有状态通知就顺便订阅。
        step(write = SonyUuid.focusUp, to = SonyUuid.command, io = io)
        step(subscribe = SonyUuid.notify, io = io, isOptional = true)
    }

    override fun ready(io: DriverIO) = io.connected("")

    override fun shutter(io: DriverIO): Boolean {
        click(SonyUuid.shutterDown, SonyUuid.shutterUp, SonyUuid.command, io)
        return true
    }
}

// MARK: - 佳能（移植自 furble）

/** 驱动佳能遥控器方式（兼容 BR-E1），配对后写入遥控器名字。 */
class CanonRemoteDriver : CameraDriver() {
    override val services = listOf(CanonUuid.remoteService)

    override fun begin(io: DriverIO) {
        if (!io.has(CanonUuid.remoteId) || !io.has(CanonUuid.remoteControl)) {
            io.fail(io.text(R.string.acc_no_canon_service))
            return
        }
        if (!io.paired) io.confirming(null)
        step(write = byteArrayOf(0x03) + io.identity.name.toByteArray(), to = CanonUuid.remoteId, io = io)
    }

    override fun ready(io: DriverIO) = io.connected("")

    override fun shutter(io: DriverIO): Boolean {
        click(CanonUuid.remoteShutter, CanonUuid.remoteRelease, CanonUuid.remoteControl, io)
        return true
    }
}

/** 驱动佳能智能手机方式：报名后等用户确认（最多 60 秒），再切到拍摄模式。 */
class CanonSmartDriver : CameraDriver() {
    private enum class Stage { Subscribing, Introducing, Waiting, Finishing }

    private var stage = Stage.Subscribing
    private var answer: Int? = null

    override val services = listOf(CanonUuid.smartService, CanonUuid.modeService)

    override fun begin(io: DriverIO) {
        if (!listOf(CanonUuid.name, CanonUuid.identity, CanonUuid.mode, CanonUuid.shutter).all(io::has)) {
            io.fail(io.text(R.string.acc_no_canon_smart_service))
            return
        }
        if (!io.paired) io.confirming(null)
        step(subscribe = CanonUuid.name, io = io)
    }

    override fun ready(io: DriverIO) {
        when (stage) {
            Stage.Subscribing -> {
                stage = Stage.Introducing
                val name = io.identity.name.toByteArray()
                step(write = byteArrayOf(0x01) + name, to = CanonUuid.name, io = io)
                step(write = byteArrayOf(0x03) + io.identity.uuid, to = CanonUuid.identity, io = io)
                step(write = byteArrayOf(0x04) + name, to = CanonUuid.identity, io = io)
                step(write = byteArrayOf(0x05, 0x02), to = CanonUuid.identity, io = io)
            }
            Stage.Introducing -> when {
                // 配对过的相机直接视为同意。
                io.paired || answer == CanonUuid.PAIR_ACCEPT -> finish(io)
                answer == CanonUuid.PAIR_REJECT -> io.rejected()
                else -> {
                    stage = Stage.Waiting
                    io.after(60.0) { if (stage == Stage.Waiting) io.fail(io.text(R.string.acc_canon_no_confirm)) }
                }
            }
            Stage.Waiting -> Unit
            Stage.Finishing -> io.connected("")
        }
    }

    override fun received(value: ByteArray, from: UUID, io: DriverIO) {
        if (from != CanonUuid.name || value.isEmpty()) return
        val first = value[0].toInt() and 0xFF
        answer = first
        if (stage != Stage.Waiting) return
        if (first == CanonUuid.PAIR_ACCEPT) finish(io) else if (first == CanonUuid.PAIR_REJECT) io.rejected()
    }

    override fun shutter(io: DriverIO): Boolean {
        click(CanonUuid.smartPress, CanonUuid.smartRelease, CanonUuid.shutter, io)
        return true
    }

    private fun finish(io: DriverIO) {
        stage = Stage.Finishing
        step(write = byteArrayOf(0x01), to = CanonUuid.identity, io = io)
        step(write = byteArrayOf(CanonUuid.MODE_SHOOT.toByte()), to = CanonUuid.mode, io = io)
    }
}

// MARK: - 富士（移植自 furble）

/** 按富士快门：命令和参数分两次写。 */
private fun fujifilmClick(driver: CameraDriver, io: DriverIO) {
    driver.press(FujifilmUuid.shutterCommand, FujifilmUuid.shutter, io)
    io.write(FujifilmUuid.shutterPress, FujifilmUuid.shutter, fast = true)
    io.after(0.1) {
        io.write(FujifilmUuid.shutterCommand, FujifilmUuid.shutter)
        io.write(FujifilmUuid.shutterRelease, FujifilmUuid.shutter)
    }
}

/** 驱动富士旧固件，用广播里的令牌表明身份。 */
class FujifilmBasicDriver(private val token: ByteArray) : CameraDriver() {
    override val services = listOf(FujifilmUuid.pairService, FujifilmUuid.configService, FujifilmUuid.shutterService)
    override val secret: ByteArray? get() = token.takeIf { it.size == 4 }

    override fun begin(io: DriverIO) {
        if (token.size != 4) {
            io.fail(io.text(R.string.acc_fuji_no_token))
            return
        }
        if (!io.has(FujifilmUuid.pair) || !io.has(FujifilmUuid.identity) || !io.has(FujifilmUuid.shutter)) {
            io.fail(io.text(R.string.acc_no_fuji_service))
            return
        }
        if (!io.paired) io.confirming(null)
        step(write = token, to = FujifilmUuid.pair, io = io)
        step(write = io.identity.name.toByteArray(), to = FujifilmUuid.identity, io = io)
        for (c in listOf(FujifilmUuid.ind1, FujifilmUuid.ind2, FujifilmUuid.not1)) step(subscribe = c, io = io)
        step(subscribe = FujifilmUuid.geoUpdate, io = io, isOptional = true)
        step(subscribe = FujifilmUuid.ind3, io = io, isOptional = true)
    }

    override fun ready(io: DriverIO) = io.connected("")

    override fun shutter(io: DriverIO): Boolean {
        fujifilmClick(this, io)
        return true
    }
}

/** 驱动富士新固件：加密连接后确认配对，再订阅通知。 */
class FujifilmSecureDriver(private val serial: ByteArray) : CameraDriver() {
    private var acknowledged = false

    override val services = listOf(FujifilmUuid.securePairService, FujifilmUuid.configService, FujifilmUuid.notifyService,
        FujifilmUuid.shutterService)
    override val secret: ByteArray? get() = serial.takeIf { it.isNotEmpty() }

    override fun begin(io: DriverIO) {
        if (!io.has(FujifilmUuid.status) || !io.has(FujifilmUuid.identity) || !io.has(FujifilmUuid.shutter)) {
            io.fail(io.text(R.string.acc_no_fuji_service))
            return
        }
        if (!io.paired) io.confirming(null)
        io.read(FujifilmUuid.status)
    }

    override fun received(value: ByteArray, from: UUID, io: DriverIO) {
        if (from != FujifilmUuid.status || acknowledged) return
        acknowledged = true
        if (value.size != 4) {
            io.fail(io.text(R.string.acc_fuji_bad_status))
            return
        }
        step(write = byteArrayOf(value[0], value[1], value[2], 0x20), to = FujifilmUuid.status, io = io)
        step(write = io.identity.name.toByteArray(), to = FujifilmUuid.identity, io = io)
        listOf(FujifilmUuid.ind1, FujifilmUuid.ind2, FujifilmUuid.not1, FujifilmUuid.geoUpdate, FujifilmUuid.not4, FujifilmUuid.not5,
            FujifilmUuid.geoSyncInterval).forEach { step(subscribe = it, io = io) }
        listOf(FujifilmUuid.not6, FujifilmUuid.not7, FujifilmUuid.not8, FujifilmUuid.not9, FujifilmUuid.not10)
            .forEach { step(subscribe = it, io = io, isOptional = true) }
        // 设置位置同步间隔为 10 秒（小端）。
        step(write = byteArrayOf(0x0A, 0x00), to = FujifilmUuid.geoSyncInterval, io = io)
    }

    override fun ready(io: DriverIO) = io.connected("")

    override fun shutter(io: DriverIO): Boolean {
        fujifilmClick(this, io)
        return true
    }
}

// MARK: - 尼康（移植自 furble）

/** 驱动尼康遥控器（ML-L7）方式，订阅配对特征后走两轮握手。 */
class NikonRemoteDriver(private val id: ByteArray) : CameraDriver() {
    private var stage = 0

    override val services = listOf(NikonUuid.service)
    override val secret: ByteArray get() = id

    override fun begin(io: DriverIO) {
        if (!io.has(NikonUuid.remotePair)) {
            io.fail(io.text(if (io.has(NikonUuid.smartPair)) R.string.acc_nikon_smart_mode else R.string.acc_no_nikon_service))
            return
        }
        if (!io.has(NikonUuid.remoteShutter)) {
            io.fail(io.text(R.string.acc_no_nikon_service))
            return
        }
        if (!io.paired) io.confirming(null)
        step(subscribe = NikonUuid.remoteIndication, io = io, isOptional = true)
        step(subscribe = NikonUuid.remotePair, io = io)
    }

    override fun ready(io: DriverIO) = send(1, NikonPairing.stage1(id), io)

    override fun received(value: ByteArray, from: UUID, io: DriverIO) {
        if (from != NikonUuid.remotePair || value.isEmpty()) return
        when (value[0].toInt()) {
            0x00 -> send(1, NikonPairing.stage1(id), io)
            0x02 -> if (stage == 1) {
                if (!value.copyOf(minOf(17, value.size)).contentEquals(NikonPairing.stage2)) {
                    io.fail(io.text(R.string.acc_nikon_handshake_failed))
                    return
                }
                send(3, NikonPairing.stage3, io)
            }
            0x04 -> if (stage == 3) {
                val serial = NikonPairing.serialFromStage4(value)
                if (serial == null) {
                    io.fail(io.text(R.string.acc_nikon_handshake_failed))
                    return
                }
                stage = 5
                io.connected(serial)
            }
        }
    }

    override fun shutter(io: DriverIO): Boolean {
        click(NikonUuid.shutterPress, NikonUuid.shutterRelease, NikonUuid.remoteShutter, io)
        return true
    }

    /** 发出一轮握手，等相机回应 10 秒。 */
    private fun send(stage: Int, message: ByteArray, io: DriverIO) {
        this.stage = stage
        io.write(message, NikonUuid.remotePair)
        io.after(10.0) { if (this.stage == stage) io.fail(io.text(R.string.acc_nikon_no_reply)) }
    }
}

// MARK: - 理光（移植自 furble）

class RicohDriver : CameraDriver() {
    private var status = AccessoryStatus()

    override val services = listOf(RicohUuid.info, RicohUuid.camera, RicohUuid.shooting, RicohUuid.bluetoothControl)
    /** 提前设好“立即拍摄”，到点只写一次开始拍摄。 */
    override val prepareLead = 0.3

    override fun begin(io: DriverIO) {
        if (!io.has(RicohUuid.operationRequest) || !io.has(RicohUuid.shootingFlavor)) {
            io.fail(io.text(R.string.acc_no_ricoh_service))
            return
        }
        if (!io.paired) io.confirming(null)
        step(write = RicohUuid.flavorImmediate, to = RicohUuid.shootingFlavor, io = io)
        for (c in listOf(RicohUuid.captureStatus, RicohUuid.power, RicohUuid.operationMode)) step(subscribe = c, io = io, isOptional = true)
    }

    override fun ready(io: DriverIO) {
        io.connected("")
        // 连好后读型号和状态，读不到也不影响拍摄。
        for (c in listOf(RicohUuid.model, RicohUuid.power, RicohUuid.operationMode)) if (io.has(c)) io.read(c)
    }

    override fun received(value: ByteArray, from: UUID, io: DriverIO) {
        when (from) {
            RicohUuid.model -> {
                val end = value.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) value.size else it }
                val model = String(value, 0, end, Charsets.UTF_8)
                if (model.isNotEmpty()) io.connected(model)
            }
            RicohUuid.power -> {
                // 电源：0 关机，1 开机，2 休眠。
                status = status.copy(sleeping = value.firstOrNull()?.toInt() == 2)
                io.status(status)
            }
            RicohUuid.operationMode -> {
                // 模式：0 拍摄，1 回放，其他为设置。
                status = status.copy(wrongMode = value.firstOrNull()?.let { if (it.toInt() == 0) null else -1 })
                io.status(status)
            }
        }
    }

    override fun prepare(io: DriverIO) = io.write(RicohUuid.flavorImmediate, RicohUuid.shootingFlavor)

    override fun shutter(io: DriverIO): Boolean {
        if (status.sleeping) {
            io.log("理光：相机休眠中，跳过这次快门")
            return false
        }
        press(RicohUuid.start, RicohUuid.operationRequest, io)
        return true
    }
}

// MARK: - GoPro（Open GoPro）

class GoProDriver : CameraDriver() {
    private val assemblers = mutableMapOf<UUID, GoProAssembler>()
    private var status = AccessoryStatus()
    private var busy = false
    private var lastShutter = -100.0
    private var lastKeepAlive = 0.0
    private var shutterSentAt: Double? = null

    /** 限制拍完后把“忙”当作保存中的时长，因为“忙”也可能是用户在操作菜单。 */
    private val saveWindow = 5.0

    override val services = listOf(GoProUuid.service)

    override fun begin(io: DriverIO) {
        if (!listOf(GoProUuid.command, GoProUuid.commandResponse, GoProUuid.query, GoProUuid.queryResponse).all(io::has)) {
            io.fail(io.text(R.string.acc_no_gopro_service))
            return
        }
        if (!io.paired) io.confirming(null)
        step(subscribe = GoProUuid.commandResponse, io = io)
        step(subscribe = GoProUuid.queryResponse, io = io)
        step(subscribe = GoProUuid.settingsResponse, io = io, isOptional = true)
    }

    override fun ready(io: DriverIO) {
        io.write(GoProCommand.thirdPartyClient, GoProUuid.command)
        io.write(GoProCommand.photoPresetGroup, GoProUuid.command)
        io.write(GoProCommand.registerStatus, GoProUuid.query)
        lastKeepAlive = MonoClock.now()
        io.connected("")
    }

    override fun received(value: ByteArray, from: UUID, io: DriverIO) {
        val message = assemblers.getOrPut(from) { GoProAssembler() }.append(value) ?: return
        if (from == GoProUuid.commandResponse && message.size >= 2 && message[0].toInt() == 0x01) {
            shutterSentAt?.let { io.shutterRtt((MonoClock.now() - it) * 1000) }
            shutterSentAt = null
            if (message[1].toInt() != 0) io.log("GoPro：快门命令被拒绝（结果 ${message[1]}）")
        } else if (from == GoProUuid.queryResponse) {
            val values = GoProCommand.statuses(message) ?: return
            values[GoProCommand.StatusId.BATTERY]?.let { status = status.copy(battery = GoProCommand.integer(it)) }
            values[GoProCommand.StatusId.REMAINING_PHOTOS]?.let { status = status.copy(remainPhotos = GoProCommand.integer(it)) }
            values[GoProCommand.StatusId.BUSY]?.let { busy = GoProCommand.integer(it) != 0 }
            values[GoProCommand.StatusId.HOT]?.let { status = status.copy(tooHot = GoProCommand.integer(it) != 0) }
            report(io)
        }
    }

    override fun tick(io: DriverIO) {
        val now = MonoClock.now()
        // 按官方 SDK 每 3 秒保活一次。
        if (now - lastKeepAlive >= 3 && !io.shutterSoon && io.has(GoProUuid.settings)) {
            lastKeepAlive = now
            io.write(GoProCommand.keepAlive, GoProUuid.settings)
        }
        if (status.capturing && now - lastShutter >= saveWindow) report(io)
    }

    override fun shutter(io: DriverIO): Boolean {
        val now = MonoClock.now()
        shutterSentAt = now
        lastShutter = now
        io.write(GoProCommand.shutterOn, GoProUuid.command, fast = true)
        return true
    }

    private fun report(io: DriverIO) {
        status = status.copy(capturing = busy && MonoClock.now() - lastShutter < saveWindow)
        io.status(status)
    }
}
