package com.thinktough.tutticam.net

import com.thinktough.tutticam.protocol.Message

/** 标识一台对端设备：从机用握手 key，主机用 "host:" 加会话名。 */
data class PeerId(val key: String, val displayName: String)

enum class LinkState { Connecting, Connected, NotConnected }

sealed interface PeerEvent {
    data class Found(val peer: PeerId, val sessionName: String) : PeerEvent
    data class Lost(val peer: PeerId) : PeerEvent
    data class State(val peer: PeerId, val state: LinkState) : PeerEvent
    /** 回报收到的消息，receivedAt 是本机收到时刻（秒）。 */
    data class Received(val message: Message, val from: PeerId, val receivedAt: Double) : PeerEvent
    data class Failed(val reason: String) : PeerEvent
}

/** 定义上层使用的连接接口，事件在网络线程回调，主机在网络线程直接回 pong。 */
interface Link {
    val me: PeerId

    fun setHandler(handler: ((PeerEvent) -> Unit)?)

    /** 以主机身份广播会话并监听。 */
    fun startHosting(sessionName: String)

    /** 以从机身份查找附近的主机。 */
    fun startBrowsing()

    fun invite(host: PeerId)

    /** 发送消息，reliable 为 false 时走 UDP，没建好就走 TCP。 */
    fun send(message: Message, to: List<PeerId>, reliable: Boolean = true)

    /** 发送对时探测，在发出前一刻才生成以便排队不计入往返。 */
    fun sendProbe(make: () -> Message, to: PeerId)

    /** 让这条连接以后改走 TCP，用于 UDP 收不到回应时。 */
    fun dropHostDatagrams()

    /** 回到前台时重建失效的监听或查找。 */
    fun revive()

    fun shutdown()
}
