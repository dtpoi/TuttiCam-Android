package com.thinktough.tutticam.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.thinktough.tutticam.protocol.Datagram
import com.thinktough.tutticam.protocol.Framing
import com.thinktough.tutticam.protocol.LinkHello
import com.thinktough.tutticam.protocol.Message
import com.thinktough.tutticam.protocol.WireJson
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 通过同一 Wi‑Fi 或热点连接设备，与 iOS 互通：主机用 NSD 广播 _tutticam._tcp 和 _tutticam._udp。
 * TCP 传可靠消息，第一帧是 LinkHello；UDP 传对时探测和状态，丢了不重发；所有读写在后台线程。
 */
class LanLink(context: Context, displayName: String, private val now: () -> Double) : Link {
    override val me = PeerId(UUID.randomUUID().toString().uppercase(), displayName)

    @Volatile private var handler: ((PeerEvent) -> Unit)? = null
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    /** 用一条线程按顺序发送，对时回应在收包线程直接回。 */
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "tutticam.send").apply { isDaemon = true } }
    @Volatile private var closed = false

    // MARK: 主机

    private class Remote(val id: PeerId, val socket: Socket, val out: OutputStream) {
        @Volatile var udp: SocketAddress? = null
        val writeLock = Any()
    }

    @Volatile private var answersPings = false
    private var sessionName: String? = null
    private var server: ServerSocket? = null
    private var hostUdp: DatagramSocket? = null
    private val remotes = ConcurrentHashMap<String, Remote>()
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()

    // MARK: 从机

    private class Client(val host: PeerId, val socket: Socket, val out: OutputStream) {
        @Volatile var udp: DatagramSocket? = null
        val writeLock = Any()
    }

    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = ConcurrentHashMap<String, InetSocketAddress>()
    @Volatile private var client: Client? = null
    /** 排队逐个解析服务，因为 Android 14 之前 NSD 一次只能解析一个。 */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun setHandler(handler: ((PeerEvent) -> Unit)?) {
        this.handler = handler
    }

    private fun emit(event: PeerEvent) {
        handler?.invoke(event)
    }

    private fun acquireMulticast() {
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock("tutticam").apply { setReferenceCounted(false); acquire() }
        }
    }

    // MARK: - 主机

    override fun startHosting(sessionName: String) {
        answersPings = true
        this.sessionName = sessionName
        acquireMulticast()
        openListeners()
    }

    private fun openListeners() {
        val name = sessionName ?: return
        closeListeners()
        try {
            val tcp = ServerSocket(0)
            val udp = DatagramSocket(0)
            server = tcp
            hostUdp = udp
            thread(name = "tutticam.accept", isDaemon = true) { acceptLoop(tcp) }
            thread(name = "tutticam.host-udp", isDaemon = true) { hostDatagramLoop(udp) }
            register(name, TCP_TYPE, tcp.localPort)
            register(name, UDP_TYPE, udp.localPort)
            Log.i(TAG, "主机监听 TCP ${tcp.localPort}、UDP ${udp.localPort}，广播“$name”")
        } catch (e: Exception) {
            emit(PeerEvent.Failed("无法广播会话：${e.message}"))
        }
    }

    private fun register(name: String, type: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "已广播 ${info.serviceName} $type")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "广播失败 $type：$errorCode")
                emit(PeerEvent.Failed("无法广播会话（$errorCode）"))
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        synchronized(registrations) { registrations += listener }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun closeListeners() {
        synchronized(registrations) {
            registrations.forEach { runCatching { nsd.unregisterService(it) } }
            registrations.clear()
        }
        runCatching { server?.close() }
        runCatching { hostUdp?.close() }
        server = null
        hostUdp = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            configure(socket)
            thread(name = "tutticam.host-tcp", isDaemon = true) { hostConnection(socket) }
        }
    }

    private fun hostConnection(socket: Socket) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        var key: String? = null
        try {
            while (true) {
                val (body, time) = readFrame(input)
                val text = body.toString(Charsets.UTF_8)
                val remote = key?.let { remotes[it] }
                if (remote == null) {
                    // 要求第一帧是握手。
                    val hello = WireJson.decodeLinkHello(text) ?: break
                    key = hello.key
                    val peer = PeerId(hello.key, hello.name)
                    val replacement = Remote(peer, socket, out)
                    remotes.put(hello.key, replacement)?.let { old ->
                        replacement.udp = old.udp
                        if (old.socket !== socket) runCatching { old.socket.close() }
                    }
                    hostUdp?.localPort?.let { port -> write(replacement, WireJson.encode(Message.UdpPort(port))) }
                    emit(PeerEvent.State(peer, LinkState.Connected))
                    continue
                }
                val message = WireJson.decode(text) ?: continue
                if (message is Message.Ping && answersPings) {
                    // 经 TCP 来的探测同样在网络线程立刻回。
                    write(remote, WireJson.encode(Message.Pong(message.seq, message.t0, time, now())))
                    continue
                }
                emit(PeerEvent.Received(message, remote.id, time))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
            val k = key
            if (k != null && remotes[k]?.socket === socket) {
                remotes.remove(k)?.let { emit(PeerEvent.State(it.id, LinkState.NotConnected)) }
            }
        }
    }

    private fun hostDatagramLoop(socket: DatagramSocket) {
        val buffer = ByteArray(64 * 1024)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                break
            }
            val time = now()
            val datagram = WireJson.decodeDatagram(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            val message = datagram.m
            if (message is Message.Ping && answersPings) {
                val pong = WireJson.encode(Datagram(HOST_SENDER, Message.Pong(message.seq, message.t0, time, now())))
                runCatching { socket.send(DatagramPacket(pong.toByteArray(), pong.toByteArray().size, packet.socketAddress)) }
                continue
            }
            val remote = remotes[datagram.p] ?: continue
            remote.udp = packet.socketAddress
            emit(PeerEvent.Received(message, remote.id, time))
        }
    }

    // MARK: - 从机

    override fun startBrowsing() {
        acquireMulticast()
        openBrowser()
    }

    private fun openBrowser() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        found.keys.toList().forEach { emit(PeerEvent.Lost(hostId(it))) }
        found.clear()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == sessionName) return
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) emit(PeerEvent.Lost(hostId(info.serviceName)))
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                emit(PeerEvent.Failed("无法查找主机（$errorCode）"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        nsd.discoverServices(TCP_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val address = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host
                if (address != null) {
                    found[info.serviceName] = InetSocketAddress(address, info.port)
                    emit(PeerEvent.Found(hostId(info.serviceName), info.serviceName))
                }
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "解析 ${info.serviceName} 失败：$errorCode")
                resolveNext()
            }
        })
    }

    override fun invite(host: PeerId) {
        closeClient()
        val address = found[host.displayName]
        if (address == null) {
            emit(PeerEvent.State(host, LinkState.NotConnected))
            return
        }
        emit(PeerEvent.State(host, LinkState.Connecting))
        thread(name = "tutticam.client-tcp", isDaemon = true) {
            val socket = Socket()
            try {
                socket.connect(address, 5000)
                configure(socket)
                val connection = Client(host, socket, socket.getOutputStream())
                client = connection
                write(connection, WireJson.encode(LinkHello(me.key, me.displayName, v = 1)))
                // 等主机告知端口再建 UDP，之前状态和探测走 TCP。
                emit(PeerEvent.State(host, LinkState.Connected))
                val input = socket.getInputStream()
                while (true) {
                    val (body, time) = readFrame(input)
                    val message = WireJson.decode(body.toString(Charsets.UTF_8)) ?: continue
                    if (message is Message.UdpPort) {
                        openHostDatagrams(connection, socket.inetAddress, message.port)
                        continue
                    }
                    emit(PeerEvent.Received(message, host, time))
                }
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
                if (client?.socket === socket || client == null) {
                    closeClient()
                    if (!closed) emit(PeerEvent.State(host, LinkState.NotConnected))
                }
            }
        }
    }

    /** 用 TCP 对端地址和主机告知的端口建立 UDP。 */
    private fun openHostDatagrams(connection: Client, address: InetAddress, port: Int) {
        val udp = DatagramSocket()
        udp.connect(InetSocketAddress(address, port))
        connection.udp?.close()
        connection.udp = udp
        Log.i(TAG, "主机 UDP 端口 $port，直接连 ${address.hostAddress}")
        thread(name = "tutticam.client-udp", isDaemon = true) {
            val buffer = ByteArray(64 * 1024)
            while (!udp.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val time = now()
                val datagram = WireJson.decodeDatagram(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                emit(PeerEvent.Received(datagram.m, connection.host, time))
            }
        }
    }

    override fun dropHostDatagrams() {
        client?.let { connection ->
            connection.udp?.let {
                Log.i(TAG, "UDP 收不到主机回应，改走 TCP")
                it.close()
            }
            connection.udp = null
        }
    }

    private fun closeClient() {
        val old = client
        client = null
        old?.udp?.close()
        old?.let { runCatching { it.socket.close() } }
    }

    // MARK: - 发送

    override fun send(message: Message, to: List<PeerId>, reliable: Boolean) {
        sender.execute { sendNow(message, to, reliable) }
    }

    override fun sendProbe(make: () -> Message, to: PeerId) {
        sender.execute { sendNow(make(), listOf(to), reliable = false) }
    }

    private fun sendNow(message: Message, to: List<PeerId>, reliable: Boolean) {
        for (peer in to) {
            val remote = remotes[peer.key]
            if (remote != null) {
                val udpTarget = remote.udp
                val udp = hostUdp
                if (!reliable && udpTarget != null && udp != null) {
                    val bytes = WireJson.encode(Datagram(HOST_SENDER, message)).toByteArray()
                    runCatching { udp.send(DatagramPacket(bytes, bytes.size, udpTarget)) }
                } else {
                    write(remote, WireJson.encode(message))
                }
                continue
            }
            val connection = client ?: continue
            if (connection.host != peer) continue
            val udp = connection.udp
            if (!reliable && udp != null && !udp.isClosed) {
                val bytes = WireJson.encode(Datagram(me.key, message)).toByteArray()
                runCatching { udp.send(DatagramPacket(bytes, bytes.size)) }
            } else {
                write(connection, WireJson.encode(message))
            }
        }
    }

    private fun write(remote: Remote, text: String) = synchronized(remote.writeLock) {
        runCatching { remote.out.write(Framing.frame(text)); remote.out.flush() }
    }

    private fun write(connection: Client, text: String) = synchronized(connection.writeLock) {
        runCatching { connection.out.write(Framing.frame(text)); connection.out.flush() }
    }

    // MARK: - 生命周期

    override fun revive() {
        if (sessionName != null && (server?.isClosed != false || hostUdp?.isClosed != false)) {
            Log.i(TAG, "回到前台：会话广播已失效，重建")
            dropAllRemotes()
            openListeners()
        }
        if (discovery != null) openBrowser()
    }

    private fun dropAllRemotes() {
        val old = remotes.values.toList()
        remotes.clear()
        old.forEach {
            runCatching { it.socket.close() }
            emit(PeerEvent.State(it.id, LinkState.NotConnected))
        }
    }

    override fun shutdown() {
        closed = true
        handler = null
        sessionName = null
        answersPings = false
        closeListeners()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        remotes.values.forEach { runCatching { it.socket.close() } }
        remotes.clear()
        closeClient()
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        sender.shutdown()
    }

    // MARK: - 收帧

    /** 读一帧，返回正文和长度头到达的时刻。 */
    private fun readFrame(input: InputStream): Pair<ByteArray, Double> {
        val header = ByteArray(4)
        readFully(input, header)
        val time = now()
        val length = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        check(length in 1 until Framing.MAX_LENGTH)
        val body = ByteArray(length)
        readFully(input, body)
        return body to time
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw java.io.EOFException()
            offset += n
        }
    }

    private fun configure(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        // 请求低时延的服务等级，接近 iOS 的 responsiveData。
        runCatching { socket.trafficClass = 0x10 }
    }

    companion object {
        private const val TAG = "TuttiCam.Lan"
        const val TCP_TYPE = "_tutticam._tcp"
        const val UDP_TYPE = "_tutticam._udp"
        private const val HOST_SENDER = "host"
        /** 限制一个会话的设备数（含主机），与 iOS 相同。 */
        const val MAX_PEERS = 8

        fun hostId(sessionName: String) = PeerId("host:$sessionName", sessionName)
    }
}
