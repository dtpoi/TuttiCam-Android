package com.thinktough.tutticam.app

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thinktough.tutticam.R
import com.thinktough.tutticam.accessory.AccessoryHub
import com.thinktough.tutticam.camera.CameraEngine
import com.thinktough.tutticam.engine.CaptureAgent
import com.thinktough.tutticam.engine.HostCoordinator
import com.thinktough.tutticam.engine.LocationProvider
import com.thinktough.tutticam.engine.MonoClock
import com.thinktough.tutticam.engine.MotionMonitor
import com.thinktough.tutticam.net.LanLink
import com.thinktough.tutticam.net.Link
import com.thinktough.tutticam.net.LinkState
import com.thinktough.tutticam.net.PeerEvent
import com.thinktough.tutticam.net.PeerId
import com.thinktough.tutticam.protocol.ClockSync
import com.thinktough.tutticam.protocol.LensConfig
import com.thinktough.tutticam.protocol.Message
import com.thinktough.tutticam.storage.CaptureStore
import com.thinktough.tutticam.storage.RecordJson
import com.thinktough.tutticam.storage.SessionRecord
import com.thinktough.tutticam.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FoundHost(val peer: PeerId, val sessionName: String)

enum class ClientLink { Idle, Connecting, Connected, Lost }

enum class Role { None, Host, Client }

/** 保存全进程的角色、会话、连接和对时状态；状态在主线程修改，只有对时回应在网络线程记录。 */
class AppModel(private val context: Context) {
    val prefs = SettingsStore(context)
    private val scope: CoroutineScope = MainScope()

    /** 提供全进程共用的相机，陀螺仪、存储、定位同样共用。 */
    val camera by lazy { CameraEngine(context) }
    private val motion by lazy { MotionMonitor(context) }
    private val store by lazy { CaptureStore(context) }
    private val location by lazy { LocationProvider(context) }
    /** 提供全进程共用的外接相机，换角色时不用重连。 */
    val accessories by lazy { AccessoryHub(context) }

    var role by mutableStateOf(Role.None)
        private set
    var deviceName by mutableStateOf(prefs.deviceName)
        private set
    var agent by mutableStateOf<CaptureAgent?>(null)
        private set
    var host by mutableStateOf<HostCoordinator?>(null)
        private set
    var alert by mutableStateOf<String?>(null)
        private set

    // 以下是从机状态。
    val foundHosts = mutableStateListOf<FoundHost>()
    var hostPeer by mutableStateOf<PeerId?>(null)
        private set
    var clientLink by mutableStateOf(ClientLink.Idle)
        private set
    /** 记录下次自动重连的时刻（MonoClock 秒）。 */
    var rejoinAt by mutableStateOf<Double?>(null)
        private set
    var rejoining by mutableStateOf(false)
        private set
    var syncResult by mutableStateOf<ClockSync.Result?>(null)
        private set
    /** 保存首次加入前等用户确认的主机。 */
    var pendingJoin by mutableStateOf<FoundHost?>(null)

    private var link: Link? = null
    private var tickLoop: Job? = null
    private var syncLoop: Job? = null
    private var rejoinLoop: Job? = null
    private var backoff = 1.0

    fun dismissAlert() {
        alert = null
    }

    fun rename(name: String) {
        prefs.deviceName = name
        deviceName = prefs.deviceName
    }

    /** 切换镜头：主机改全局设置，从机请主机单独指定。 */
    fun chooseLens(lens: LensConfig) {
        val host = host
        val peer = hostPeer
        if (host != null) {
            host.setGlobalLens(lens)
        } else if (peer != null && clientLink == ClientLink.Connected) {
            prefs.clientLens = lens
            link?.send(Message.LensRequest(lens), listOf(peer))
        }
    }

    private var backgrounded = false
    /** 标记启动时的自动进入已处理，只在启动时进一次。 */
    private var launchHandled = false

    /** 启动时按设置直接进入主机相机页。 */
    fun handleLaunch() {
        if (launchHandled) return
        launchHandled = true
        if (prefs.onboardingDone && prefs.autoHost && role == Role.None) becomeHost()
    }

    /** 回到前台时重建会话广播或主机查找，并重开相机。 */
    fun onForeground() {
        link?.revive()
        if (backgrounded) {
            backgrounded = false
            agent?.prepareCamera()
        }
    }

    /** 进入后台时关闭相机。 */
    fun onBackground() {
        backgrounded = true
        agent?.suspendCamera()
    }

    // MARK: - 角色

    private fun makeAgent(remoteOnly: Boolean = false) =
        CaptureAgent(context, deviceName, camera, motion, store, location, accessories, remoteOnly)

    fun becomeHost(remoteOnly: Boolean = false) {
        val agent = makeAgent(remoteOnly)
        val link = makeLink(null)
        val host = HostCoordinator(context, agent, prefs) { message, peers -> link.send(message, peers) }
        this.agent = agent
        this.host = host
        role = Role.Host
        link.startHosting(deviceName)
        motion.start()
        startTicks()
        agent.prepareCamera()
    }

    /** 以主机身份进入截图演示，套上演示数据。 */
    fun startScreenshotDemo() {
        if (role == Role.None) becomeHost()
        host?.applyDemo()
        accessories.applyDemo()
    }

    fun becomeClient() {
        val agent = makeAgent()
        agent.clock = ClockSync(MonoClock::now)
        val link = makeLink(agent.clock)
        agent.onReport = { report -> hostPeer?.let { link.send(Message.Report(report), listOf(it)) } }
        this.agent = agent
        role = Role.Client
        link.startBrowsing()
        motion.start()
        startTicks()
        agent.prepareCamera()
    }

    /** 加入选中的主机，第一次加入时先请用户确认。 */
    fun join(found: FoundHost) {
        if (found.peer.displayName in prefs.trustedHosts) join(found, retry = false) else pendingJoin = found
    }

    fun confirmJoin(found: FoundHost) {
        pendingJoin = null
        prefs.trustedHosts = prefs.trustedHosts + found.peer.displayName
        join(found, retry = false)
    }

    private fun join(found: FoundHost, retry: Boolean) {
        Log.i(TAG, (if (retry) "重连主机：" else "加入主机：") + found.peer.displayName)
        hostPeer = found.peer
        rejoining = retry
        if (!retry) clientLink = ClientLink.Connecting
        link?.invite(found.peer)
    }

    /** 按 1、2、4… 最多 30 秒的退避自动重连主机，immediately 时先立刻试一次。 */
    private fun startRejoin(immediately: Boolean = false) {
        rejoinLoop?.cancel()
        rejoinLoop = scope.launch {
            var tryNow = immediately
            while (isActive) {
                if (!tryNow) {
                    val wait = backoff
                    backoff = minOf(backoff * 2, 30.0)
                    rejoinAt = MonoClock.now() + wait
                    delay((wait * 1000).toLong())
                    if (clientLink != ClientLink.Lost) return@launch
                }
                tryNow = false
                attemptRejoin()
                repeat(50) { if (rejoining) delay(100) }
                if (clientLink != ClientLink.Lost) return@launch
                rejoining = false
            }
        }
    }

    private fun attemptRejoin() {
        rejoinAt = null
        val name = hostPeer?.displayName ?: return
        val found = foundHosts.firstOrNull { it.peer.displayName == name }
        if (found != null) {
            join(found, retry = true)
        } else {
            rejoining = true
            link?.revive()
        }
    }

    fun reconnectNow() {
        if (clientLink != ClientLink.Lost) return
        backoff = 1.0
        startRejoin(immediately = true)
    }

    fun leave() {
        if (role == Role.Host && host?.running == true) host?.stop()
        agent?.stop()
        agent?.suspendCamera()
        camera.close()
        motion.stop()
        tickLoop?.cancel()
        syncLoop?.cancel()
        rejoinLoop?.cancel()
        link?.shutdown()
        link = null
        agent = null
        host = null
        hostPeer = null
        foundHosts.clear()
        clientLink = ClientLink.Idle
        rejoinAt = null
        rejoining = false
        syncResult = null
        role = Role.None
    }

    /** 创建网络连接，从机传入 clock 以便在网络线程记录对时回应。 */
    private fun makeLink(clock: ClockSync?): Link {
        val link = LanLink(context, deviceName, MonoClock::now)
        link.setHandler { event ->
            if (clock != null && event is PeerEvent.Received && event.message is Message.Pong) {
                val pong = event.message
                clock.addPong(pong.t0, pong.t1, pong.t2, event.receivedAt)
                return@setHandler
            }
            scope.launch { handle(event) }
        }
        this.link = link
        return link
    }

    /** 启动每秒约 20 次的状态上报和主机调度。 */
    private fun startTicks() {
        tickLoop?.cancel()
        tickLoop = scope.launch {
            while (isActive) {
                tick()
                delay(50)
            }
        }
    }

    private fun tick() {
        when (role) {
            Role.Host -> host?.tick()
            Role.Client -> {
                val agent = agent ?: return
                val peer = hostPeer
                if (peer == null || clientLink != ClientLink.Connected) {
                    agent.makeStatus()
                    return
                }
                link?.send(Message.Status(agent.makeStatus()), listOf(peer), reliable = false)
            }
            Role.None -> Unit
        }
    }

    // MARK: - 对时（从机）

    /** 定期对时，没对上时 0.5 秒后重试。 */
    private fun startSync() {
        syncLoop?.cancel()
        syncLoop = scope.launch {
            while (isActive) {
                syncBurst()
                val agent = agent ?: return@launch
                delay(if (agent.clock.isSynced) (agent.settings.syncInterval * 1000).toLong() else 500)
            }
        }
    }

    /** 发一轮 30 个对时探测，首次对时在 6 个后先报临时结果。 */
    private suspend fun syncBurst() {
        val agent = agent ?: return
        val peer = hostPeer ?: return
        val link = link ?: return
        val clock = agent.clock
        var reported = clock.isSynced
        val result = withContext(Dispatchers.Default) {
            for (i in 0 until ClockSync.BURST_COUNT) {
                if (!isActive) return@withContext null
                link.sendProbe({ clock.nextPing() }, peer)
                delay(ClockSync.BURST_SPACING_MS)
                if (!reported && i >= ClockSync.EARLY_AFTER - 1) {
                    clock.provisional()?.let { early ->
                        reported = true
                        withContext(Dispatchers.Main) { reportSync(early, peer) }
                    }
                }
            }
            delay(ClockSync.BURST_TAIL_MS)
            clock.finishBurst()
        }
        if (clientLink != ClientLink.Connected) return
        if (result != null) {
            Log.i(TAG, "对时完成：往返 %.1f ms，误差 ±%.1f ms".format(result.rtt * 1000, result.error * 1000))
            reportSync(result, peer)
        } else {
            Log.w(TAG, "这一轮对时没有收到主机回应")
            link.dropHostDatagrams()
        }
    }

    private fun reportSync(result: ClockSync.Result, peer: PeerId) {
        syncResult = result
        link?.send(Message.SyncQuality(result.rtt * 1000, result.error * 1000), listOf(peer))
    }

    // MARK: - 事件

    private fun handle(event: PeerEvent) {
        when (role) {
            Role.Host -> handleAsHost(event)
            Role.Client -> handleAsClient(event)
            Role.None -> Unit
        }
    }

    private fun handleAsHost(event: PeerEvent) {
        val host = host ?: return
        when (event) {
            is PeerEvent.State -> when (event.state) {
                LinkState.Connected -> host.peerConnected(event.peer)
                LinkState.NotConnected -> host.peerDisconnected(event.peer)
                LinkState.Connecting -> Unit
            }
            is PeerEvent.Received -> when (val m = event.message) {
                is Message.Hello -> host.hello(m.info, event.from)
                is Message.Status -> host.status(m.status, event.from)
                is Message.SyncQuality -> host.syncQuality(m.rttMs, m.errorMs, event.from)
                is Message.Report -> host.receive(m.report, event.from)
                is Message.LensRequest -> host.lensRequest(m.lens, event.from)
                else -> Unit
            }
            is PeerEvent.Failed -> alert = event.reason
            is PeerEvent.Found, is PeerEvent.Lost -> Unit
        }
    }

    private fun handleAsClient(event: PeerEvent) {
        val agent = agent ?: return
        when (event) {
            is PeerEvent.Found -> {
                foundHosts.removeAll { it.peer.displayName == event.peer.displayName }
                foundHosts += FoundHost(event.peer, event.sessionName)
                foundHosts.sortBy { it.sessionName }
                // 掉线的主机重新出现时立刻重连。
                if (clientLink == ClientLink.Lost && event.peer.displayName == hostPeer?.displayName) {
                    foundHosts.firstOrNull { it.peer.displayName == event.peer.displayName }?.let { join(it, retry = true) }
                }
            }
            is PeerEvent.Lost -> foundHosts.removeAll { it.peer == event.peer }
            is PeerEvent.State -> {
                if (event.peer != hostPeer) return
                when (event.state) {
                    LinkState.Connected -> {
                        rejoinLoop?.cancel()
                        backoff = 1.0
                        rejoinAt = null
                        rejoining = false
                        clientLink = ClientLink.Connected
                        link?.send(Message.Hello(agent.info), listOf(event.peer))
                        agent.clock.reset()
                        startSync()
                    }
                    LinkState.NotConnected -> {
                        rejoining = false
                        syncLoop?.cancel()
                        syncResult = null
                        agent.stop()
                        when (clientLink) {
                            ClientLink.Connected -> {
                                clientLink = ClientLink.Lost
                                startRejoin()
                            }
                            ClientLink.Connecting -> {
                                clientLink = ClientLink.Idle
                                hostPeer = null
                                alert = context.getString(R.string.alert_cannot_connect)
                            }
                            else -> Unit
                        }
                    }
                    LinkState.Connecting -> Unit
                }
            }
            is PeerEvent.Received -> {
                if (event.from != hostPeer) return
                when (val m = event.message) {
                    is Message.Pong -> agent.clock.addPong(m.t0, m.t1, m.t2, event.receivedAt)
                    is Message.Welcome -> {
                        agent.setIdentity(m.index, m.code)
                        val lens = prefs.clientLens
                        if (lens != null && lens != agent.settings.lens(m.index)) link?.send(Message.LensRequest(lens), listOf(event.from))
                    }
                    is Message.Rejected -> {
                        alert = m.reason
                        clientLink = ClientLink.Idle
                        hostPeer = null
                    }
                    is Message.Settings -> agent.update(m.settings)
                    is Message.Start -> {
                        val result = syncResult
                        agent.start(m.session, SessionRecord(
                            session = m.session, deviceId = agent.deviceIndex, deviceCode = agent.deviceCode.text, device = agent.info,
                            isHost = false, startedAt = RecordJson.isoNow(), settings = agent.settings,
                            syncRttMs = result?.let { it.rtt * 1000 }, syncErrorMs = result?.let { it.error * 1000 }))
                    }
                    Message.Stop -> agent.stop()
                    is Message.RenameFolder -> agent.renameFolder(m.from, m.to)
                    is Message.Schedule -> agent.schedule(m.round, m.hostTime)
                    is Message.Resolve -> agent.resolve(m.round, m.keep)
                    else -> Unit
                }
            }
            is PeerEvent.Failed -> alert = event.reason
        }
    }

    companion object {
        private const val TAG = "TuttiCam.App"
    }
}
