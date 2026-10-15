package com.thinktough.tutticam.accessory

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.thinktough.tutticam.R
import com.thinktough.tutticam.engine.MonoClock
import com.thinktough.tutticam.protocol.accessory.AccessoryStatus
import com.thinktough.tutticam.protocol.accessory.Advertisement
import com.thinktough.tutticam.protocol.accessory.CameraKind
import com.thinktough.tutticam.protocol.accessory.FujifilmUuid
import com.thinktough.tutticam.protocol.accessory.NikonPairing
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

data class AccessoryDiscovery(val address: String, val kind: CameraKind, val name: String, val rssi: Int)

/** 记录一次触发里发给某台相机的快门。 */
data class AccessoryShot(
    val cameraId: String,
    val kind: CameraKind,
    val name: String,
    val model: String,
    val number: Int,
    /** 记录实际发出快门的时刻（本机单调时钟，秒）。 */
    val sentAt: Double,
)

sealed interface AccessoryPhase {
    data object Connecting : AccessoryPhase
    /** 表示在等用户确认配对，DJI 带配对码。 */
    data class Confirming(val code: Int?) : AccessoryPhase
    data class Connected(val model: String) : AccessoryPhase
    data object Rejected : AccessoryPhase
    data class Failed(val reason: String) : AccessoryPhase
}

sealed interface AccessoryEvent {
    data class Bluetooth(val problem: String?) : AccessoryEvent
    data class Discovered(val list: List<AccessoryDiscovery>) : AccessoryEvent
    data class Phase(val id: String, val kind: CameraKind, val name: String, val number: Int, val phase: AccessoryPhase) : AccessoryEvent
    data class Status(val id: String, val status: AccessoryStatus) : AccessoryEvent
    data class ShutterAck(val id: String, val rttMs: Double) : AccessoryEvent
    data class Disconnected(val id: String) : AccessoryEvent
    /** 移除还没配对成功就断开的相机。 */
    data class Removed(val id: String) : AccessoryEvent
}

/** 保存配对过的相机，下次自动重连。 */
data class KnownCamera(
    /** 给出 App 内部的稳定标识，命名和编号按它保存。 */
    val id: String,
    /** 记录当前蓝牙地址，相机换地址时更新。 */
    val address: String,
    val kind: CameraKind,
    val name: String,
    val secret: ByteArray?,
)

/** 保存本机作为蓝牙遥控器的身份（DJI 设备号和 MAC、佳能 16 字节标识），首次使用时随机生成。 */
class RemoteIdentity(val deviceId: Long, val mac: ByteArray, val uuid: ByteArray) {
    /** 给出在相机上显示的遥控器名字。 */
    val name: String get() = "TuttiCam-%05X".format(deviceId and 0xFFFFF)

    companion object {
        fun load(context: Context): RemoteIdentity {
            val prefs = context.getSharedPreferences(AccessoryLink.PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains("deviceId")) {
                prefs.edit()
                    .putLong("deviceId", Random.nextLong(0x10000000L, 0x7FFFFFFFL))
                    .putString("mac", Random.nextBytes(6).toHex())
                    .putString("uuid", Random.nextBytes(16).toHex())
                    .apply()
            }
            return RemoteIdentity(prefs.getLong("deviceId", 0x12345678L), prefs.getString("mac", "010203040506")!!.fromHex(),
                prefs.getString("uuid", "00".repeat(16))!!.fromHex())
        }
    }
}

internal fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
internal fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/**
 * 用低功耗蓝牙扫描、连接外接相机，交给驱动握手并按时发快门；状态只在 accessory 线程访问。
 * GATT 一次只能有一个读写，所以每台相机一条操作队列，快门插队首；遇到“未认证”先配对再重试。
 */
@SuppressLint("MissingPermission")
class AccessoryLink(private val context: Context, private val handler: (AccessoryEvent) -> Unit) {
    private sealed interface Op {
        val uuid: UUID

        class Write(override val uuid: UUID, val bytes: ByteArray, val noResponse: Boolean) : Op
        class Read(override val uuid: UUID) : Op
        class Subscribe(override val uuid: UUID) : Op
    }

    /** 管理一台相机的一次连接，重连时新建，旧连接的迟到回调会被丢弃。 */
    private inner class Connection(
        val id: String,
        val device: BluetoothDevice,
        val kind: CameraKind,
        val name: String,
        val number: Int,
        val driver: CameraDriver,
        override val paired: Boolean,
    ) : DriverIO {
        var gatt: BluetoothGatt? = null
        var phase: AccessoryPhase = AccessoryPhase.Connecting
        val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
        var started = false
        val ops = ArrayDeque<Op>()
        var busy: Op? = null
        var busyTimeout: Runnable? = null
        /** 保存因未认证而等配对后重试的那一步。 */
        var waitingBond: Op? = null

        val model: String get() = (phase as? AccessoryPhase.Connected)?.model ?: ""

        // 实现 DriverIO。

        override val isConnected: Boolean get() = phase is AccessoryPhase.Connected
        override val identity: RemoteIdentity get() = this@AccessoryLink.identity
        override val shutterSoon: Boolean get() = this@AccessoryLink.shutterSoon

        override fun has(characteristic: UUID) = characteristic in characteristics

        override fun write(bytes: ByteArray, to: UUID, fast: Boolean) {
            val c = characteristics[to]
            if (c == null) {
                log("没有特征 $to，丢弃 ${bytes.toHex()}")
                return
            }
            val props = c.properties
            val noResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
                (fast || props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0)
            enqueue(Op.Write(to, bytes, noResponse), front = fast)
        }

        override fun subscribe(characteristic: UUID) {
            if (characteristic in characteristics) enqueue(Op.Subscribe(characteristic))
        }

        override fun read(characteristic: UUID) {
            if (characteristic in characteristics) enqueue(Op.Read(characteristic))
        }

        override fun after(seconds: Double, work: () -> Unit) {
            thread.postDelayed({ if (isCurrent(this)) work() }, (seconds * 1000).toLong())
        }

        override fun confirming(code: Int?) = report(this, AccessoryPhase.Confirming(code))
        override fun connected(model: String) = report(this, AccessoryPhase.Connected(model))
        override fun rejected() = report(this, AccessoryPhase.Rejected)

        override fun fail(reason: String) {
            if (isCurrent(this)) stop(id, reason)
        }

        override fun status(status: AccessoryStatus) {
            if (isCurrent(this)) handler(AccessoryEvent.Status(id, status))
        }

        override fun shutterRtt(ms: Double) {
            if (isCurrent(this)) handler(AccessoryEvent.ShutterAck(id, ms))
        }

        override fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

        override fun log(message: String) {
            Log.i(TAG, "${kind.brand.raw}：$message")
        }

        // 管理操作队列。

        fun enqueue(op: Op, front: Boolean = false) {
            if (front) ops.addFirst(op) else ops.addLast(op)
            pump()
        }

        fun pump() {
            if (busy != null || waitingBond != null) return
            val gatt = gatt ?: return
            val op = ops.removeFirstOrNull() ?: return
            val c = characteristics[op.uuid] ?: return pump()
            busy = op
            val started = when (op) {
                is Op.Write -> {
                    val type = if (op.noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    log("→ ${op.bytes.toHex()}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(c, op.bytes, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        c.writeType = type
                        @Suppress("DEPRECATION")
                        c.value = op.bytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(c)
                    }
                }
                is Op.Read -> gatt.readCharacteristic(c)
                is Op.Subscribe -> {
                    gatt.setCharacteristicNotification(c, true)
                    val descriptor = c.getDescriptor(CCCD)
                    if (descriptor == null) {
                        // 没有描述符的特征直接算订阅完成。
                        finish(op, null)
                        return
                    }
                    val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = value
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
            if (!started) {
                finish(op, context.getString(R.string.acc_gatt_busy))
                return
            }
            val timeout = Runnable { if (busy === op) finish(op, context.getString(R.string.acc_gatt_timeout)) }
            busyTimeout = timeout
            thread.postDelayed(timeout, 5000)
        }

        /** 结束一步操作，交给驱动后执行下一步。 */
        fun finish(op: Op, error: String?, value: ByteArray? = null) {
            if (busy !== op) return
            busy = null
            busyTimeout?.let(thread::removeCallbacks)
            busyTimeout = null
            when (op) {
                is Op.Write -> driver.wrote(op.uuid, error, this)
                is Op.Subscribe -> driver.notifying(op.uuid, error, this)
                is Op.Read -> if (error == null && value != null) {
                    log("← ${value.toHex()}")
                    driver.received(value, op.uuid, this)
                } else if (error != null && !isConnected) fail(error)
            }
            pump()
        }

        /** 处理 GATT 回调，“未认证”时先发起配对，配对后重试这一步。 */
        fun complete(op: Op?, status: Int, value: ByteArray? = null) {
            val current = busy ?: return
            if (op != null && op.uuid != current.uuid) return
            if (status in AUTH_ERRORS && device.bondState != BluetoothDevice.BOND_BONDED) {
                log("需要配对（结果 $status），发起系统配对")
                busy = null
                busyTimeout?.let(thread::removeCallbacks)
                waitingBond = current
                if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
                return
            }
            finish(current, if (status == BluetoothGatt.GATT_SUCCESS) null else describe(status), value)
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (gatt !== g) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("蓝牙已连接，协商 MTU")
                    // 先调大 MTU（DJI 连接请求有 51 字节），再提高连接优先级降低延迟。
                    if (!g.requestMtu(247)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("蓝牙断开（结果 $status）")
                    g.close()
                    disconnected(this@Connection, status)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (gatt !== g) return
                log("MTU $mtu")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (gatt !== g || !isCurrent(this@Connection)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stop(id, context.getString(R.string.acc_discover_failed, describe(status)))
                    return
                }
                val wanted = driver.services
                val services = g.services.filter { wanted.isEmpty() || it.uuid in wanted }
                if (services.isEmpty()) {
                    stop(id, context.getString(R.string.acc_no_brand_service, brandTitle(context, kind.brand)))
                    return
                }
                for (service in services) for (c in service.characteristics) characteristics.putIfAbsent(c.uuid, c)
                if (!started) {
                    started = true
                    driver.begin(this@Connection)
                }
            }

            @Deprecated("Android 13 起用带 value 的版本")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    changed(g, c.uuid, c.value ?: return)
                }
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = changed(g, c.uuid, value)

            private fun changed(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
                if (gatt !== g || !isCurrent(this@Connection)) return
                log("← ${value.toHex()}")
                driver.received(value, uuid, this@Connection)
            }

            @Deprecated("Android 13 起用带 value 的版本")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && gatt === g) {
                    @Suppress("DEPRECATION")
                    complete(busy, status, c.value)
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                if (gatt === g) complete(busy, status, value)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (gatt === g) complete(busy, status)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                if (gatt === g) complete(busy, status)
            }
        }
    }

    private val thread: Handler = HandlerThread("tutticam.accessory", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        .let { Handler(it.looper) }
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var wantScan = false
    private var scanning = false
    private val discovered = mutableMapOf<String, Triple<Advertisement, CameraKind, Int>>()
    /** 按稳定标识保存当前连接。 */
    private val connections = mutableMapOf<String, Connection>()
    private var known: MutableList<KnownCamera> = loadKnown()
    private val timers = mutableMapOf<Any, Runnable>()
    private val pendingShutters = mutableMapOf<Any, Double>()
    private var lastFire = -100.0
    /** 保存每次连接的期限，超时就放弃并提示用户。 */
    private val deadlines = mutableMapOf<String, Runnable>()
    /** 列出停止自动重连的相机，等用户选择重新连接或忘记。 */
    private val stopped = mutableSetOf<String>()
    private val identity = RemoteIdentity.load(context)
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> thread.post { adapterChanged() }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = deviceFrom(intent) ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    thread.post { bondChanged(device.address, state, previous) }
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            for (connection in connections.values) {
                if (connection.isConnected && connection.id !in stopped) connection.driver.tick(connection)
            }
            thread.postDelayed(this, 1000)
        }
    }

    /** 判断是否已有查找和连接相机的权限。 */
    val hasPermission: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val hasKnown: Boolean get() = known.isNotEmpty()

    /** 注册蓝牙广播并重连配对过的相机，需要先有权限。 */
    fun start() = thread.post {
        if (started || !hasPermission) return@post
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        thread.postDelayed(tick, 1000)
        adapterChanged()
    }

    // MARK: 配对过的相机

    private fun loadKnown(): MutableList<KnownCamera> = runCatching {
        val array = JSONArray(prefs.getString("known", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            KnownCamera(o.getString("id"), o.getString("address"), CameraKind.parse(o.getString("kind")) ?: return@mapNotNull null,
                o.optString("name"), o.optString("secret").takeIf { it.isNotEmpty() }?.fromHex())
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun saveKnown() {
        val array = JSONArray()
        for (k in known) array.put(JSONObject().put("id", k.id).put("address", k.address).put("kind", k.kind.raw).put("name", k.name)
            .put("secret", k.secret?.toHex() ?: ""))
        prefs.edit().putString("known", array.toString()).apply()
    }

    /** 按配对顺序给出 1、2、3… 的相机编号，DJI 协议要用。 */
    private fun number(id: String) = minOf(255, (known.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: known.size) + 1)

    // MARK: 操作

    fun startScan() = thread.post {
        wantScan = true
        updateScan()
    }

    fun stopScan() = thread.post {
        wantScan = false
        updateScan()
    }

    /** 按蓝牙地址连接扫描到的相机。 */
    fun connect(address: String) = thread.post {
        val (ad, kind, _) = discovered[address] ?: return@post
        val device = adapter?.getRemoteDevice(address) ?: return@post
        val id = known.firstOrNull { it.address == address }?.id ?: address
        open(id, device, kind, ad.name ?: "", ad, autoConnect = false)
    }

    /** 重新连接超时或失败的相机。 */
    fun retry(id: String) = thread.post {
        val connection = connections[id] ?: return@post
        open(id, connection.device, connection.kind, connection.name, discovered[connection.device.address]?.first, autoConnect = false)
    }

    /** 断开并忘记相机，下次需要重新配对。 */
    fun forget(id: String) = thread.post {
        known.removeAll { it.id == id }
        saveKnown()
        deadlines.remove(id)?.let(thread::removeCallbacks)
        stopped.remove(id)
        connections.remove(id)?.let { c ->
            c.gatt?.disconnect()
            c.gatt?.close()
        }
        handler(AccessoryEvent.Disconnected(id))
        updateScan()
        publishDiscovered()
    }

    /** 在本机时刻 time 给已连接的相机按快门，completion 在蓝牙线程带回实际发出的快门。 */
    fun shutter(time: Double, ids: Set<String>, completion: (List<AccessoryShot>) -> Unit) = thread.post {
        val key = Any()
        pendingShutters[key] = time
        // 先让需要准备的驱动做准备，如理光设好拍摄方式。
        for (id in ids) {
            val connection = connections[id]?.takeIf { it.isConnected && it.driver.prepareLead > 0 } ?: continue
            val at = time - connection.driver.prepareLead
            val delay = ((at - MonoClock.now()) * 1000).toLong()
            if (delay <= 0) continue
            thread.postDelayed({ if (isCurrent(connection) && connection.isConnected) connection.driver.prepare(connection) }, delay)
        }
        // 先睡到约定前 4 毫秒，再忙等到点，避开消息循环的调度误差。
        val runnable = Runnable {
            timers.remove(key)
            pendingShutters.remove(key)
            while (MonoClock.now() < time) Unit
            completion(fire(ids))
        }
        timers[key] = runnable
        thread.postDelayed(runnable, maxOf(0L, ((time - 0.004 - MonoClock.now()) * 1000).toLong()))
    }

    fun cancelPending() = thread.post {
        timers.values.forEach(thread::removeCallbacks)
        timers.clear()
        pendingShutters.clear()
    }

    private fun fire(ids: Set<String>): List<AccessoryShot> {
        val shots = mutableListOf<AccessoryShot>()
        for (id in ids) {
            val connection = connections[id]?.takeIf { it.isConnected } ?: continue
            val now = MonoClock.now()
            if (!connection.driver.shutter(connection)) continue
            lastFire = now
            shots += AccessoryShot(id, connection.kind, connection.name, connection.model, connection.number, now)
        }
        return shots
    }

    // MARK: 内部

    private val shutterSoon: Boolean
        get() {
            val now = MonoClock.now()
            return now - lastFire < 0.3 || pendingShutters.values.any { it - now < 0.7 }
        }

    private fun isCurrent(connection: Connection) = connections[connection.id] === connection && connection.id !in stopped

    private fun adapterChanged() {
        val adapter = adapter
        when {
            adapter == null -> handler(AccessoryEvent.Bluetooth(context.getString(R.string.acc_bt_unsupported)))
            !adapter.isEnabled -> {
                scanning = false
                // 蓝牙关闭时清掉连接，配对过的等蓝牙打开后重连。
                for (connection in connections.values.toList()) {
                    if (connection.id in stopped) continue
                    deadlines.remove(connection.id)?.let(thread::removeCallbacks)
                    connections.remove(connection.id)
                    connection.gatt?.close()
                    handler(if (known.any { it.id == connection.id }) AccessoryEvent.Disconnected(connection.id) else AccessoryEvent.Removed(connection.id))
                }
                handler(AccessoryEvent.Bluetooth(context.getString(R.string.acc_bt_off)))
            }
            else -> {
                handler(AccessoryEvent.Bluetooth(null))
                // 自动重连配对过的相机，不在附近时系统会一直等。
                for (entry in known) {
                    if (entry.id in connections) continue
                    val device = runCatching { adapter.getRemoteDevice(entry.address) }.getOrNull() ?: continue
                    open(entry.id, device, entry.kind, entry.name, null, autoConnect = true)
                }
                updateScan()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            thread.post { found(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "扫描失败 $errorCode")
            thread.post { scanning = false }
        }
    }

    /** 在连接页打开或有已配对相机没连上时扫描，用来找回换了地址的相机。 */
    private fun updateScan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || !hasPermission) {
            scanning = false
            return
        }
        val needsRescan = known.any { (it.kind == CameraKind.NikonRemote || it.kind == CameraKind.FujifilmSecure) &&
            connections[it.id]?.isConnected != true && it.id !in stopped }
        val want = wantScan || needsRescan
        if (want && !scanning) {
            runCatching {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
                scanning = true
            }
        } else if (!want && scanning) {
            runCatching { scanner.stopScan(scanCallback) }
            scanning = false
        }
    }

    private fun found(result: ScanResult) {
        val bytes = result.scanRecord?.bytes ?: return
        val device = result.device
        val ad = Advertisement.parse(bytes, runCatching { device.name }.getOrNull())
        matchKnown(ad)?.let { entry ->
            if (entry.id in stopped) return@let
            val current = connections[entry.id]
            if (current?.isConnected == true) return
            if (current?.device?.address == device.address && current.gatt != null) return
            Log.i(TAG, "找回配对过的${entry.kind.brand.raw}相机：${ad.name ?: ""}")
            val index = known.indexOfFirst { it.id == entry.id }
            if (index >= 0 && known[index].address != device.address) {
                known[index] = known[index].copy(address = device.address)
                saveKnown()
            }
            open(entry.id, device, entry.kind, entry.name, ad, autoConnect = false)
            return
        }
        val kind = CameraKind.detect(ad) ?: return
        if (device.address !in discovered) Log.i(TAG, "发现${kind.brand.raw}相机（${kind.raw}）：${ad.name ?: ""} 信号 ${result.rssi}")
        discovered[device.address] = Triple(ad, kind, result.rssi)
        publishDiscovered()
    }

    private fun publishDiscovered() {
        val inUse = connections.values.map { it.device.address }.toSet()
        handler(AccessoryEvent.Discovered(discovered.filterKeys { it !in inUse }
            .map { (address, v) -> AccessoryDiscovery(address, v.second, v.first.name ?: "", v.third) }
            .sortedByDescending { it.rssi }))
    }

    private fun open(id: String, device: BluetoothDevice, kind: CameraKind, name: String, ad: Advertisement?, autoConnect: Boolean) {
        stopped.remove(id)
        connections[id]?.let { old ->
            old.gatt?.disconnect()
            old.gatt?.close()
        }
        val entry = known.firstOrNull { it.id == id }
        val number = number(id)
        val connection = Connection(id, device, kind, name, number, makeDriver(kind, entry?.secret, ad, number), paired = entry != null)
        connections[id] = connection
        handler(AccessoryEvent.Phase(id, kind, name, number, AccessoryPhase.Connecting))
        // 配对过的相机用自动连接且不设期限，新相机要在 30 秒内连上。
        if (!autoConnect) arm(id, CONNECT_TIMEOUT)
        connection.gatt = device.connectGatt(context, autoConnect, connection.callback, BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK, thread)
        updateScan()
        publishDiscovered()
    }

    private fun makeDriver(kind: CameraKind, secret: ByteArray?, ad: Advertisement?, number: Int): CameraDriver = when (kind) {
        CameraKind.DjiR -> DjiDriver(identity, number)
        CameraKind.Sony -> SonyDriver()
        CameraKind.CanonRemote -> CanonRemoteDriver()
        CameraKind.CanonSmart -> CanonSmartDriver()
        CameraKind.FujifilmBasic -> FujifilmBasicDriver(secret ?: ad?.let(FujifilmUuid::token) ?: ByteArray(0))
        CameraKind.FujifilmSecure -> FujifilmSecureDriver(secret ?: ad?.let(FujifilmUuid::serial) ?: ByteArray(0))
        CameraKind.NikonRemote -> NikonRemoteDriver(secret ?: NikonPairing.newId())
        CameraKind.Ricoh -> RicohDriver()
        CameraKind.GoPro -> GoProDriver()
    }

    private fun arm(id: String, seconds: Double) {
        deadlines.remove(id)?.let(thread::removeCallbacks)
        val work = Runnable {
            val connection = connections[id] ?: return@Runnable
            if (connection.isConnected) return@Runnable
            stop(id, context.getString(if (connection.phase is AccessoryPhase.Confirming) R.string.acc_confirm_timeout else R.string.acc_connect_timeout))
        }
        deadlines[id] = work
        thread.postDelayed(work, (seconds * 1000).toLong())
    }

    /** 放弃连接并停止自动重连，界面提示用户下一步。 */
    private fun stop(id: String, reason: String?) {
        deadlines.remove(id)?.let(thread::removeCallbacks)
        stopped += id
        val connection = connections[id] ?: return
        connection.gatt?.disconnect()
        connection.gatt?.close()
        connection.gatt = null
        if (reason != null) {
            Log.w(TAG, "${connection.kind.brand.raw}：$reason")
            connection.phase = AccessoryPhase.Failed(reason)
            handler(AccessoryEvent.Phase(id, connection.kind, connection.name, connection.number, connection.phase))
        }
        updateScan()
    }

    private fun report(connection: Connection, phase: AccessoryPhase) {
        if (!isCurrent(connection)) return
        connection.phase = phase
        when (phase) {
            is AccessoryPhase.Connected -> {
                deadlines.remove(connection.id)?.let(thread::removeCallbacks)
                remember(connection)
            }
            is AccessoryPhase.Confirming -> arm(connection.id, CONFIRM_TIMEOUT)
            else -> Unit
        }
        Log.i(TAG, "${connection.kind.brand.raw} 状态：$phase")
        handler(AccessoryEvent.Phase(connection.id, connection.kind, connection.name, connection.number, phase))
        if (phase == AccessoryPhase.Rejected) stop(connection.id, null)
        updateScan()
    }

    private fun remember(connection: Connection) {
        val entry = KnownCamera(connection.id, connection.device.address, connection.kind, connection.name, connection.driver.secret)
        val index = known.indexOfFirst { it.id == connection.id }
        if (index >= 0) known[index] = entry else known += entry
        saveKnown()
    }

    /** 按广播里的配对标识找回已配对的尼康或富士新固件相机。 */
    private fun matchKnown(ad: Advertisement): KnownCamera? = known.firstOrNull { entry ->
        val secret = entry.secret ?: return@firstOrNull false
        when (entry.kind) {
            CameraKind.NikonRemote -> NikonPairing.isReconnect(ad, secret)
            CameraKind.FujifilmSecure -> FujifilmUuid.isReconnect(ad, secret)
            else -> false
        }
    }

    private fun disconnected(connection: Connection, status: Int) {
        if (!isCurrent(connection)) return
        handler(AccessoryEvent.Disconnected(connection.id))
        if (known.any { it.id == connection.id }) {
            // 自动重连配对过的相机。
            open(connection.id, connection.device, connection.kind, connection.name, null, autoConnect = true)
        } else {
            // 处理还没配对成功就断开的情况，比如用户取消了配对。
            stop(connection.id, if (status == BluetoothGatt.GATT_SUCCESS) context.getString(R.string.acc_connection_lost)
            else context.getString(R.string.acc_connection_lost_reason, describe(status)))
        }
    }

    private fun bondChanged(address: String, state: Int, previous: Int) {
        val connection = connections.values.firstOrNull { it.device.address == address } ?: return
        val op = connection.waitingBond ?: return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                connection.log("配对完成，重试")
                connection.waitingBond = null
                connection.ops.addFirst(op)
                connection.pump()
            }
            BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                connection.waitingBond = null
                connection.fail(context.getString(R.string.acc_pairing_incomplete))
            }
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** 把 GATT 结果码翻译成给用户的提示。 */
    private fun describe(status: Int): String = when (status) {
        in AUTH_ERRORS -> context.getString(R.string.acc_pairing_incomplete)
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED, BluetoothGatt.GATT_READ_NOT_PERMITTED -> context.getString(R.string.acc_not_permitted)
        8 -> context.getString(R.string.acc_link_timeout)
        else -> context.getString(R.string.acc_gatt_error, status)
    }

    companion object {
        private const val TAG = "TuttiCam.Accessory"
        const val PREFS = "accessories"

        /** 列出查找和连接相机所需的权限，Android 12 起是“附近的设备”。 */
        val PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        private const val CONNECT_TIMEOUT = 30.0
        /** 限制等用户确认配对的时长（秒）。 */
        private const val CONFIRM_TIMEOUT = 90.0
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** 列出未认证、未加密、认证失败三种结果码。 */
        private val AUTH_ERRORS = setOf(5, 15, 137)
    }
}
