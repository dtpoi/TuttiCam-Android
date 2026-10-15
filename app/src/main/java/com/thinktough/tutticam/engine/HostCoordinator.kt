package com.thinktough.tutticam.engine

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.net.LanLink
import com.thinktough.tutticam.net.PeerId
import com.thinktough.tutticam.protocol.CaptureSettings
import com.thinktough.tutticam.protocol.DeviceCode
import com.thinktough.tutticam.protocol.DeviceInfo
import com.thinktough.tutticam.protocol.DeviceStatus
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.Message
import com.thinktough.tutticam.protocol.RoundReport
import com.thinktough.tutticam.protocol.StabilityMode
import com.thinktough.tutticam.storage.CaptureStore
import com.thinktough.tutticam.storage.RecordJson
import com.thinktough.tutticam.storage.SessionDevice
import com.thinktough.tutticam.storage.SessionRecord
import com.thinktough.tutticam.storage.SettingsStore

enum class DeviceState(val title: Int, val isProblem: Boolean = false) {
    Connecting(R.string.state_connecting),
    Syncing(R.string.state_syncing),
    Ready(R.string.state_ready),
    Moving(R.string.state_moving),
    Saving(R.string.state_saving),
    Offline(R.string.state_offline, true),
    LowStorage(R.string.state_low_storage, true),
    Overheated(R.string.state_overheated, true),
    CameraError(R.string.state_camera_error, true),
}

data class DeviceEntry(
    val name: String,
    val index: Int,
    val code: DeviceCode,
    /** 指向这台设备的连接，主机自己为 null。 */
    val peer: PeerId?,
    val info: DeviceInfo?,
    val status: DeviceStatus? = null,
    val statusTime: Double = 0.0,
    val connected: Boolean = true,
    val rttMs: Double? = null,
    val errorMs: Double? = null,
    val notReadySince: Double? = null,
) {
    val isHost: Boolean get() = peer == null
    val synced: Boolean get() = isHost || errorMs != null
    val statusFresh: Boolean get() = MonoClock.now() - statusTime < 1

    val state: DeviceState
        get() {
            if (!connected) return DeviceState.Offline
            val status = status ?: return DeviceState.Connecting
            if (!synced) return DeviceState.Syncing
            if (status.cameraError != null) return DeviceState.CameraError
            if (status.thermal >= 2) return DeviceState.Overheated
            if (status.freeBytes > 0 && status.freeBytes < DeviceStatus.LOW_STORAGE_BYTES) return DeviceState.LowStorage
            if (!status.ready) return DeviceState.Saving
            if (!status.still || !statusFresh) return DeviceState.Moving
            return DeviceState.Ready
        }
}

/** 表示一个场景，对应一个文件夹，第一次开拍时才创建。 */
data class SceneRef(val name: String, val folder: String? = null) {
    val id: String get() = folder ?: name
}

data class RoundSummary(
    val round: Int,
    val participants: Int,
    val photos: Int,
    val missed: Int,
    val moving: Int,
    val maxOffsetMs: Double?,
    /** 记录强制同步时这组是否保留，默认模式下为 null。 */
    val kept: Boolean?,
)

/** 在主机上调度拍摄：等全员静止，按间隔安排，收集回报，处理强制同步；公开方法都在主线程调用。 */
class HostCoordinator(
    private val context: Context,
    val agent: CaptureAgent,
    private val settingsStore: SettingsStore,
    private val send: (Message, List<PeerId>) -> Unit,
) {
    private class InFlight(
        val round: Int,
        val participants: Set<Int>,
        val reports: MutableMap<Int, RoundReport> = mutableMapOf(),
        val deadline: Double,
        val forced: Boolean,
        val hostTakesPart: Boolean,
    )

    val devices = mutableStateListOf(DeviceEntry(agent.deviceName, 1, DeviceCode.Host, null, agent.info))
    var settings by mutableStateOf(CaptureSettings())
        private set
    var running by mutableStateOf(false)
        private set
    var sessionName by mutableStateOf<String?>(null)
        private set
    var round by mutableStateOf(0)
        private set
    val log = mutableStateListOf<RoundSummary>()
    var scene by mutableStateOf(SceneRef(sceneName(1)))
        private set
    /** 列出用过的其他场景，最近的在前。 */
    val recentScenes = mutableStateListOf<SceneRef>()
    var waitingReason by mutableStateOf<String?>(null)
        private set
    /** 按设备号记录从机最近一组的问题（错过、缺一路、没回报），下一组顺利时清掉。 */
    val peerProblems = mutableStateMapOf<Int, String>()

    private val sceneRounds = mutableMapOf<String, Int>()
    private var sceneCounter = 1
    private var inFlight: InFlight? = null
    private var lastRoundAt = -100.0
    /** 记录最近一次改动相机配置的时刻，之后要等各设备相机重新就绪。 */
    private var cameraChangedAt: Double? = null

    init {
        agent.setIdentity(1, DeviceCode.Host)
        agent.onReport = { receive(it) }
        settings = settingsStore.loadSettings()
        agent.update(settings)
    }

    /** 列出会话里出现过的外接相机品牌，设置页据此显示提前量。 */
    val accessoryBrands: List<com.thinktough.tutticam.protocol.CameraBrand>
        get() {
            val brands = agent.accessories.cameras.map { it.brand }.toMutableSet()
            for (device in devices) device.status?.accessories?.forEach { a ->
                com.thinktough.tutticam.protocol.CameraBrand.entries.firstOrNull { it.raw == a.brand }?.let(brands::add)
            }
            return com.thinktough.tutticam.protocol.CameraBrand.entries.filter { it in brands }
        }

    private val remotePeers: List<PeerId> get() = devices.filter { it.connected }.mapNotNull { it.peer }

    private inline fun updateDevice(predicate: (DeviceEntry) -> Boolean, change: (DeviceEntry) -> DeviceEntry) {
        val i = devices.indexOfFirst(predicate)
        if (i >= 0) devices[i] = change(devices[i])
    }

    private fun sortDevices() {
        val sorted = devices.sortedBy { it.code }
        if (sorted != devices.toList()) {
            devices.clear()
            devices.addAll(sorted)
        }
    }

    // MARK: - 设备

    fun peerConnected(peer: PeerId) {
        updateDevice({ it.name == peer.displayName && !it.isHost }) {
            it.copy(peer = peer, connected = true, status = null, errorMs = null)
        }
    }

    fun peerDisconnected(peer: PeerId) {
        updateDevice({ it.peer == peer }) { it.copy(connected = false) }
    }

    fun hello(info: DeviceInfo, peer: PeerId) {
        val existing = devices.indexOfFirst { it.name == peer.displayName && !it.isHost }
        val index: Int
        if (existing >= 0) {
            val entry = devices[existing]
            if (entry.connected && entry.peer != null && entry.peer != peer) {
                send(Message.Rejected(context.getString(R.string.reject_duplicate_name, peer.displayName)), listOf(peer))
                return
            }
            devices[existing] = entry.copy(peer = peer, info = info, connected = true)
            index = entry.index
        } else {
            val used = devices.map { it.index }.toSet()
            val free = (2..LanLink.MAX_PEERS).firstOrNull { it !in used }
            if (free == null) {
                send(Message.Rejected(context.getString(R.string.reject_full, LanLink.MAX_PEERS)), listOf(peer))
                return
            }
            index = free
            devices += DeviceEntry(peer.displayName, free, nextCode("A"), peer, info)
            sortDevices()
        }
        val code = devices.firstOrNull { it.index == index }?.code ?: nextCode("A")
        send(Message.Welcome(index, code), listOf(peer))
        sendSettings(settings, listOf(peer))
        val session = sessionName
        if (running && session != null) send(Message.Start(session), listOf(peer))
    }

    fun status(status: DeviceStatus, peer: PeerId?) {
        val now = MonoClock.now()
        updateDevice({ it.peer == peer }) {
            it.copy(status = status, statusTime = now,
                notReadySince = if (status.ready) null else it.notReadySince ?: now)
        }
    }

    fun syncQuality(rttMs: Double, errorMs: Double, peer: PeerId) {
        updateDevice({ it.peer == peer }) { it.copy(rttMs = rttMs, errorMs = errorMs) }
    }

    fun removeOffline(entry: DeviceEntry) {
        if (entry.connected || entry.isHost) return
        devices.removeAll { it.index == entry.index }
        peerProblems.remove(entry.index)
        if (entry.index in settings.lensOverrides) update(settings.copy(lensOverrides = settings.lensOverrides - entry.index))
    }

    // MARK: - 编号

    /** 返回某组的下一个空号，如已有 A01、A02 时返回 A03。 */
    fun nextCode(group: String, excluding: Int? = null): DeviceCode {
        val used = devices.filter { it.code.group == group && it.index != excluding }.map { it.code.number }.toSet()
        return DeviceCode(group, (1..99).firstOrNull { it !in used } ?: 99)
    }

    /** 修改编号，重号时不改并返回占用它的设备。 */
    fun setCode(code: DeviceCode, index: Int): DeviceEntry? {
        devices.firstOrNull { it.code == code && it.index != index }?.let { return it }
        val i = devices.indexOfFirst { it.index == index }
        if (i < 0) return null
        val entry = devices[i].copy(code = code)
        devices[i] = entry
        sortDevices()
        if (entry.isHost) {
            agent.setIdentity(index, code)
        } else if (entry.peer != null && entry.connected) {
            send(Message.Welcome(index, code), listOf(entry.peer))
        }
        return null
    }

    // MARK: - 设置

    fun update(new: CaptureSettings) {
        if (new == settings) return
        if (new.lens != settings.lens || new.lensOverrides != settings.lensOverrides || new.format != settings.format ||
            new.prefer48MP != settings.prefer48MP || new.frontFraming != settings.frontFraming) {
            cameraChangedAt = MonoClock.now()
        }
        settings = new
        agent.update(new)
        sendSettings(new, remotePeers)
        if (!ScreenshotDemo.isOn) settingsStore.saveSettings(new)
    }

    /** 给从机下发设置，发给 iPhone 时把 RAW 换成 RAW + JPEG。 */
    private fun sendSettings(new: CaptureSettings, peers: List<PeerId>) {
        val (android, others) = peers.partition { peer -> devices.firstOrNull { it.peer == peer }?.info?.isAndroid == true }
        if (android.isNotEmpty()) send(Message.Settings(new), android)
        if (others.isNotEmpty()) send(Message.Settings(new.copy(format = new.format.forIos)), others)
    }

    /** 切换全部相机参数的锁定。 */
    fun toggleLockAll() = update(settings.copy(lockAll = !settings.lockAll))

    /** 修改全局镜头，并清除主机自己的单独指定。 */
    fun setGlobalLens(lens: LensConfig) = update(settings.copy(lens = lens, lensOverrides = settings.lensOverrides - 1))

    fun lensRequest(lens: LensConfig, peer: PeerId) {
        val index = devices.firstOrNull { it.peer == peer }?.index ?: return
        setLens(lens, index)
    }

    fun setLens(lens: LensConfig?, index: Int) {
        val overrides = if (lens == null || lens == settings.lens) settings.lensOverrides - index
        else settings.lensOverrides + (index to lens)
        update(settings.copy(lensOverrides = overrides))
    }

    // MARK: - 拍摄

    /** 套上截图演示数据：拍到第 12 组，另有 3 台从机。 */
    fun applyDemo() {
        update(CaptureSettings(lens = ScreenshotDemo.lens))
        running = true
        round = ScreenshotDemo.ROUNDS
        agent.deviceName = ScreenshotDemo.HOST_NAME
        devices.removeAll { !it.isHost }
        devices[0] = devices[0].copy(name = ScreenshotDemo.HOST_NAME, info = devices[0].info?.copy(name = ScreenshotDemo.HOST_NAME, model = ScreenshotDemo.HOST_MODEL))
        devices += ScreenshotDemo.peers(Double.POSITIVE_INFINITY)
        log.clear()
        log += ScreenshotDemo.log
        agent.applyDemo()
    }

    fun start() {
        running = true
        log.clear()
        peerProblems.clear()
        lastRoundAt = -100.0
        beginSceneFolder()
    }

    /** 让所有设备存进当前场景文件夹，同一场景再开拍时组号接着编。 */
    private fun beginSceneFolder() {
        val folder = ensureSceneFolder()
        sessionName = folder
        round = sceneRounds[folder] ?: 0
        send(Message.Start(folder), remotePeers)
        agent.start(folder, sessionRecord(folder))
    }

    private fun ensureSceneFolder(): String {
        scene.folder?.let { return it }
        val folder = CaptureStore.folderName(scene.name)
        scene = scene.copy(folder = folder)
        return folder
    }

    // MARK: - 场景

    val nextSceneName: String get() = sceneName(sceneCounter + 1)

    /** 按序号生成默认场景名，如“场景01”。 */
    fun sceneName(number: Int): String = context.getString(R.string.scene_name, "%02d".format(number))

    fun newScene() {
        sceneCounter += 1
        switchScene(SceneRef(sceneName(sceneCounter)))
    }

    /** 切换场景，拍摄中已取帧的那组仍存进旧文件夹。 */
    fun switchScene(target: SceneRef) {
        if (target == scene) return
        scene.folder?.let { folder ->
            sceneRounds[folder] = round
            recentScenes.remove(scene)
            recentScenes.add(0, scene)
        }
        recentScenes.remove(target)
        scene = target
        if (running) beginSceneFolder()
    }

    /** 重命名当前场景并让各设备改文件夹名，拍摄中不允许，失败时返回错误说明。 */
    fun renameScene(newName: String): String? {
        if (running) return context.getString(R.string.rename_while_running)
        val name = newName.replace("/", "-").replace(":", "-").trim()
        if (name.isEmpty()) return context.getString(R.string.name_empty)
        val old = scene.folder ?: run {
            scene = scene.copy(name = name)
            return null
        }
        val suffix = if (CaptureStore.displayName(old) == old) "" else old.takeLast(16)
        val new = name + suffix
        if (new == old) return null
        agent.renameFolder(old, new)
        send(Message.RenameFolder(old, new), remotePeers)
        sceneRounds.remove(old)?.let { sceneRounds[new] = it }
        if (sessionName == old) sessionName = new
        scene = SceneRef(name, new)
        return null
    }

    fun stop() {
        inFlight?.let { finalize(it) }
        running = false
        waitingReason = null
        send(Message.Stop, remotePeers)
        agent.stop()
        scene.folder?.let { sceneRounds[it] = round }
        sessionName?.let { agent.writeSession(sessionRecord(it).copy(rounds = round)) }
    }

    private fun sessionRecord(name: String) = SessionRecord(
        session = name, deviceId = 1, deviceCode = agent.deviceCode.text, device = agent.info, isHost = true,
        startedAt = RecordJson.isoNow(), settings = settings,
        devices = devices.map { SessionDevice(it.index, it.code.text, it.name, it.info?.model) },
    )

    /** 运行调度循环（每秒约 20 次）：先结算进行中的组，条件满足时安排下一组。 */
    fun tick() {
        status(agent.makeStatus(), null)
        if (ScreenshotDemo.isOn) return
        val now = MonoClock.now()
        inFlight?.let { flight ->
            if (flight.reports.keys.containsAll(flight.participants) || now > flight.deadline) finalize(flight)
            return
        }
        if (!running) return
        // 有外接相机时间隔加上额外等待，留给相机保存。
        val accessoryInSession = devices.any { d -> d.connected && d.status?.accessories?.any { it.connected } == true }
        if (now - lastRoundAt < settings.minInterval + if (accessoryInSession) settings.osmoExtraWait else 0.0) {
            waitingReason = null
            return
        }

        // 改了相机配置后，等各设备报来相机就绪再拍，最多等 5 秒。
        cameraChangedAt?.let { changedAt ->
            if (now - changedAt < 5) {
                val switching = devices.filter {
                    it.connected && it.synced && !(it.isHost && agent.remoteOnly) &&
                        (it.statusTime < changedAt + 0.3 || it.status?.cameraReady != true)
                }
                if (switching.isNotEmpty()) {
                    waitingReason = context.getString(R.string.waiting_camera_switch)
                    return
                }
            }
            cameraChangedAt = null
        }

        val participants = mutableListOf<DeviceEntry>()
        for (device in devices) {
            if (!device.connected || !device.synced) continue
            val status = device.status ?: continue
            if (!status.cameraReady || !device.statusFresh) continue
            if (!status.ready) {
                // 上一张还没写完时等 5 秒，超时就跳过这台。
                val since = device.notReadySince
                if (since != null && now - since > 5) continue
                waitingReason = context.getString(R.string.waiting_saving, device.code.text)
                return
            }
            participants += device
        }
        if (participants.isEmpty()) {
            waitingReason = context.getString(R.string.waiting_no_device)
            return
        }
        when {
            settings.stability == StabilityMode.Off -> Unit
            settings.hostStillnessOnly -> {
                if (devices.firstOrNull { it.isHost }?.status?.still != true) {
                    waitingReason = context.getString(R.string.waiting_host_still)
                    return
                }
            }
            else -> {
                val moving = participants.filter { it.status?.still != true }
                if (moving.isNotEmpty()) {
                    waitingReason = context.getString(R.string.waiting_still, moving.joinToString(context.getString(R.string.list_separator)) { it.code.text })
                    return
                }
            }
        }
        waitingReason = null
        schedule(participants, now)
    }

    private fun schedule(participants: List<DeviceEntry>, now: Double) {
        round += 1
        lastRoundAt = now
        val hostTime = now + settings.scheduleLead
        val hostTakesPart = participants.any { it.isHost }
        inFlight = InFlight(round, participants.map { it.index }.toSet(), deadline = hostTime + 6,
            forced = settings.forceSync, hostTakesPart = hostTakesPart)
        send(Message.Schedule(round, hostTime), participants.mapNotNull { it.peer })
        if (hostTakesPart) agent.schedule(round, hostTime)
    }

    private fun receive(report: RoundReport) {
        val flight = inFlight
        if (flight == null || flight.round != report.round) {
            // 迟到的回报在强制同步时让该设备删掉待定照片。
            if (settings.forceSync) {
                devices.firstOrNull { it.index == report.deviceIndex }?.peer?.let {
                    send(Message.Resolve(report.round, false), listOf(it))
                }
            }
            return
        }
        flight.reports[report.deviceIndex] = report
    }

    fun receive(report: RoundReport, @Suppress("UNUSED_PARAMETER") peer: PeerId) = receive(report)

    private fun finalize(flight: InFlight) {
        inFlight = null
        val reports = flight.participants.mapNotNull { flight.reports[it] }
        val delivered = reports.filter { !it.missed }
        val missed = flight.participants.size - delivered.size
        // 只看主机时按主机判断是否在动，不判定时不因在动作废。
        val moving = if (settings.stability == StabilityMode.Off) 0
        else delivered.count { it.moving && (!settings.hostStillnessOnly || it.deviceIndex == 1) }
        var kept: Boolean? = null
        if (flight.forced) {
            val keep = missed == 0 && moving == 0
            kept = keep
            val peers = devices.filter { it.index in flight.participants }.mapNotNull { it.peer }
            send(Message.Resolve(flight.round, keep), peers)
            if (flight.hostTakesPart) agent.resolve(flight.round, keep)
        }
        val summary = RoundSummary(flight.round, flight.participants.size, delivered.sumOf { it.photos.size }, missed, moving,
            delivered.mapNotNull { it.maxAbsOffsetMs }.maxOrNull(), kept)
        notePeerProblems(flight)
        Log.i(TAG, "第 ${flight.round} 组结算：${flight.participants.size} 台参与，${summary.photos} 张，错过 $missed，在动 $moving")
        log.add(0, summary)
        if (log.size > 50) log.removeAt(log.lastIndex)
    }

    /** 记下各从机这一组的问题；主机自己的问题由 CaptureAgent.lastProblem 显示。 */
    private fun notePeerProblems(flight: InFlight) {
        for (index in flight.participants - 1) {
            val report = flight.reports[index]
            val problem = when {
                report == null -> context.getString(R.string.round_no_reply, flight.round)
                report.missed -> context.getString(R.string.round_missed, flight.round, report.error ?: context.getString(R.string.missed_no_photo))
                report.error != null -> context.getString(R.string.round_partial, flight.round, report.error)
                else -> null
            }
            if (problem != null) peerProblems[index] = problem else peerProblems.remove(index)
        }
    }

    companion object {
        private const val TAG = "TuttiCam.Host"
    }
}
