package com.thinktough.tutticam.accessory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thinktough.tutticam.R
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.protocol.AccessoryBrief
import com.thinktough.tutticam.protocol.CameraBrand
import com.thinktough.tutticam.protocol.DeviceCode
import com.thinktough.tutticam.protocol.accessory.AccessoryStatus
import com.thinktough.tutticam.protocol.accessory.CameraKind
import com.thinktough.tutticam.protocol.accessory.OsmoCommand
import com.thinktough.tutticam.protocol.accessory.group
import org.json.JSONObject

/** 返回品牌的显示名。 */
fun brandTitle(context: Context, brand: CameraBrand): String = when (brand) {
    CameraBrand.Dji -> "DJI"
    CameraBrand.GoPro -> "GoPro"
    CameraBrand.Sony -> context.getString(R.string.brand_sony)
    CameraBrand.Canon -> context.getString(R.string.brand_canon)
    CameraBrand.Fujifilm -> context.getString(R.string.brand_fujifilm)
    CameraBrand.Nikon -> context.getString(R.string.brand_nikon)
    CameraBrand.Ricoh -> context.getString(R.string.brand_ricoh)
}

/** 返回拿不到设备名时的称呼。 */
fun brandGenericName(context: Context, brand: CameraBrand): String = when (brand) {
    CameraBrand.Dji -> context.getString(R.string.brand_generic_dji)
    CameraBrand.GoPro -> context.getString(R.string.brand_generic_gopro)
    else -> context.getString(R.string.brand_generic, brandTitle(context, brand))
}

/** 返回首次配对时在相机上的操作说明。 */
fun pairingSteps(context: Context, brand: CameraBrand): String = context.getString(when (brand) {
    CameraBrand.Dji -> R.string.pair_dji
    CameraBrand.Sony -> R.string.pair_sony
    CameraBrand.Canon -> R.string.pair_canon
    CameraBrand.Fujifilm -> R.string.pair_fujifilm
    CameraBrand.Nikon -> R.string.pair_nikon
    CameraBrand.Ricoh -> R.string.pair_ricoh
    CameraBrand.GoPro -> R.string.pair_gopro
})

/** 返回连接方式的名称，显示在设备名下面。 */
fun kindTitle(context: Context, kind: CameraKind): String = when (kind) {
    CameraKind.DjiR -> context.getString(R.string.kind_dji)
    CameraKind.CanonRemote -> context.getString(R.string.kind_canon_remote)
    CameraKind.CanonSmart -> context.getString(R.string.kind_canon_smart)
    CameraKind.NikonRemote -> context.getString(R.string.kind_nikon_remote)
    CameraKind.GoPro -> "Open GoPro"
    else -> context.getString(R.string.kind_bluetooth_remote)
}

fun djiModeTitle(context: Context, mode: Int): String = when (mode) {
    OsmoCommand.Mode.PANORAMA_PHOTO -> context.getString(R.string.dji_mode_panorama_photo)
    OsmoCommand.Mode.PHOTO -> context.getString(R.string.dji_mode_photo)
    OsmoCommand.Mode.PANORAMA_VIDEO -> context.getString(R.string.dji_mode_panorama_video)
    OsmoCommand.Mode.VIDEO -> context.getString(R.string.dji_mode_video)
    else -> context.getString(R.string.dji_mode_other, "0x%02X".format(mode))
}

/** 管理本机的外接相机供界面和拍摄使用，全进程一份；蓝牙在打开连接页或已有配对时才启动。 */
class AccessoryHub(private val context: Context) {
    data class Camera(
        val id: String,
        val kind: CameraKind,
        val name: String,
        val number: Int,
        val phase: AccessoryPhase = AccessoryPhase.Connecting,
        val status: AccessoryStatus? = null,
        val lastRttMs: Double? = null,
        /** 记录编号，默认按品牌取组，如 D01，用户可修改。 */
        val code: DeviceCode = DeviceCode("D", 1),
        /** 记录用户起的名字，为空时显示设备名或型号。 */
        val customName: String? = null,
    ) {
        val brand: CameraBrand get() = kind.brand
        val connected: Boolean get() = phase is AccessoryPhase.Connected
        val model: String get() = (phase as? AccessoryPhase.Connected)?.model ?: ""
        /** 表示已连接但相机在休眠。 */
        val sleeping: Boolean get() = connected && status?.sleeping == true
        val failed: Boolean get() = phase is AccessoryPhase.Failed || phase is AccessoryPhase.Rejected
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(AccessoryLink.PREFS, Context.MODE_PRIVATE)

    var bluetoothProblem by mutableStateOf<String?>(null)
        private set
    var discovered by mutableStateOf<List<AccessoryDiscovery>>(emptyList())
        private set
    val cameras = mutableStateListOf<Camera>()
    var scanning by mutableStateOf(false)
        private set
    /** 控制首次连上相机时的“固定在同一支架”提醒。 */
    var showMountReminder by mutableStateOf(false)
    private var remindedThisRun = false

    private val link = AccessoryLink(context) { event -> main.post { handle(event) } }

    val hasPermission: Boolean get() = link.hasPermission

    init {
        if (link.hasKnown && link.hasPermission) link.start()
    }

    /** 返回显示名：依次用自定义名、设备名、型号、品牌通称。 */
    fun displayName(camera: Camera): String = camera.customName?.takeIf { it.isNotEmpty() } ?: defaultName(camera)

    /** 返回不含自定义名的默认名，优先用设备名。 */
    fun defaultName(camera: Camera): String = camera.name.ifEmpty { camera.model.ifEmpty { brandGenericName(context, camera.brand) } }

    val connectedCameras: List<Camera> get() = cameras.filter { it.connected }
    val hasConnected: Boolean get() = cameras.any { it.connected }
    /** 表示有相机还在拍上一张。 */
    val busy: Boolean get() = connectedCameras.any { it.status?.capturing == true }

    val briefs: List<AccessoryBrief>
        get() = cameras.map { c ->
            AccessoryBrief(name = displayName(c), number = c.number, code = c.code.text, brand = c.brand.raw, connected = c.connected,
                battery = c.status?.battery, remainPhotos = c.status?.remainPhotos, capturing = c.status?.capturing ?: false,
                tooHot = c.status?.tooHot ?: false, sleeping = c.sleeping)
        }

    /** 返回状态补充说明，如“全景拍照 · 30MP”。 */
    fun detail(status: AccessoryStatus?): String? {
        val mode = status?.djiMode ?: return null
        return listOfNotNull(djiModeTitle(context, mode), status.photoSize).joinToString(" · ")
    }

    /** 列出要在界面上提示的问题。 */
    val problems: List<String>
        get() = cameras.mapNotNull { camera ->
            val label = context.getString(R.string.acc_label, displayName(camera), camera.code.text)
            when (val phase = camera.phase) {
                is AccessoryPhase.Failed -> return@mapNotNull context.getString(R.string.acc_problem_failed, label, phase.reason)
                AccessoryPhase.Rejected -> return@mapNotNull context.getString(R.string.acc_problem_rejected, label)
                else -> Unit
            }
            val status = camera.status
            if (!camera.connected || status == null) return@mapNotNull null
            when {
                status.tooHot -> context.getString(R.string.acc_problem_hot, label)
                status.sleeping -> context.getString(R.string.acc_problem_sleeping, label)
                status.wrongMode != null -> "$label " + if (status.wrongMode == -1) context.getString(R.string.acc_wrong_shoot_mode)
                else context.getString(R.string.acc_wrong_photo_mode, djiModeTitle(context, status.wrongMode!!))
                else -> null
            }
        }

    // MARK: 操作

    fun startScan() {
        link.start()
        scanning = true
        link.startScan()
    }

    fun stopScan() {
        scanning = false
        link.stopScan()
    }

    /** 按蓝牙地址连接扫描到的相机。 */
    fun connect(address: String) = link.connect(address)

    fun forget(id: String) {
        link.forget(id)
        cameras.removeAll { it.id == id }
        saveLabels(labels().also { it.remove(id) })
    }

    fun retry(id: String) = link.retry(id)

    /** 忘记相机并重新扫描，下次需要重新配对。 */
    fun forgetAndRescan(id: String) {
        forget(id)
        startScan()
    }

    fun camera(id: String): Camera? = cameras.firstOrNull { it.id == id }

    /** 在本机时刻 time 给已连接的相机按快门，completion 在主线程带回实际发出的快门。 */
    fun shutter(time: Double, ids: Set<String>, completion: (List<AccessoryShot>) -> Unit) {
        if (ids.isEmpty()) return
        link.shutter(time, ids) { shots -> main.post { completion(shots) } }
    }

    fun cancelPending() = link.cancelPending()

    // MARK: 命名与编号（保存在本机，按相机的稳定标识记住）

    private class Label(val name: String?, val group: String, val number: Int)

    private fun labels(): MutableMap<String, Label> = runCatching {
        val o = JSONObject(prefs.getString("labels", "{}")!!)
        o.keys().asSequence().associateWith { key ->
            val l = o.getJSONObject(key)
            Label(l.optString("name").takeIf { it.isNotEmpty() }, l.getString("group"), l.getInt("number"))
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun saveLabels(map: Map<String, Label>) {
        val o = JSONObject()
        for ((key, l) in map) o.put(key, JSONObject().put("name", l.name ?: "").put("group", l.group).put("number", l.number))
        prefs.edit().putString("labels", o.toString()).apply()
    }

    /** 给新出现的相机套上已存的名字和编号，没有时按品牌分配空号。 */
    private fun labeled(camera: Camera): Camera {
        val stored = labels()
        stored[camera.id]?.let { return camera.copy(customName = it.name, code = DeviceCode(it.group, it.number)) }
        val code = nextCode(camera.brand.group, camera.id)
        stored[camera.id] = Label(null, code.group, code.number)
        saveLabels(stored)
        return camera.copy(code = code)
    }

    /** 返回本机外接相机某组的下一个空号。 */
    fun nextCode(group: String, excluding: String? = null): DeviceCode {
        val used = cameras.filter { it.code.group == group && it.id != excluding }.map { it.code.number } +
            labels().filter { it.key != excluding && it.value.group == group }.map { it.value.number }
        return DeviceCode(group, (1..99).firstOrNull { it !in used } ?: 99)
    }

    /** 修改名字和编号，重号时不改并返回占用者的名字。 */
    fun relabel(id: String, name: String, code: DeviceCode): String? {
        cameras.firstOrNull { it.id != id && it.code == code }?.let { return displayName(it) }
        val index = cameras.indexOfFirst { it.id == id }
        if (index < 0) return null
        val trimmed = name.trim().ifEmpty { null }
        cameras[index] = cameras[index].copy(customName = trimmed, code = code)
        saveLabels(labels().also { it[id] = Label(trimmed, code.group, code.number) })
        sort()
        return null
    }

    private fun sort() {
        val sorted = cameras.sortedBy { it.code }
        if (sorted != cameras.toList()) {
            cameras.clear()
            cameras.addAll(sorted)
        }
    }

    // MARK: 事件

    private fun handle(event: AccessoryEvent) {
        when (event) {
            is AccessoryEvent.Bluetooth -> bluetoothProblem = event.problem
            is AccessoryEvent.Discovered -> discovered = event.list
            is AccessoryEvent.Phase -> {
                update(event.id, { it.copy(name = event.name, number = event.number, phase = event.phase) }) {
                    labeled(Camera(event.id, event.kind, event.name, event.number, event.phase))
                }
                if (event.phase is AccessoryPhase.Connected && !remindedThisRun && !prefs.getBoolean("mountReminderOff", false)) {
                    remindedThisRun = true
                    showMountReminder = true
                }
            }
            is AccessoryEvent.Status -> update(event.id, { it.copy(status = event.status) })
            is AccessoryEvent.ShutterAck -> {
                update(event.id, { it.copy(lastRttMs = event.rttMs) })
                Log.i("TuttiCam.Accessory", "外接相机快门命令往返 %.0f ms".format(event.rttMs))
            }
            is AccessoryEvent.Disconnected -> update(event.id, { it.copy(phase = AccessoryPhase.Connecting, status = null) })
            is AccessoryEvent.Removed -> cameras.removeAll { it.id == event.id }
        }
    }

    /** 套上截图演示数据：本机连着一台 Osmo 360。 */
    fun applyDemo() {
        if (cameras.none { it.id == ScreenshotDemo.osmo.id }) cameras += ScreenshotDemo.osmo
    }

    /** 关闭支架提醒，以后不再弹出。 */
    fun turnOffMountReminder() {
        prefs.edit().putBoolean("mountReminderOff", true).apply()
        showMountReminder = false
    }

    private fun update(id: String, change: (Camera) -> Camera, create: (() -> Camera)? = null) {
        val index = cameras.indexOfFirst { it.id == id }
        if (index >= 0) {
            cameras[index] = change(cameras[index])
        } else if (create != null) {
            cameras += create()
            sort()
        }
    }
}
