package com.thinktough.tutticam.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 用陀螺仪估算模糊像素：角速度 × 快门时间 × 焦距，角速度取最近 0.1 秒平均并扣除噪声底。 */
class MotionMonitor(context: Context) : SensorEventListener {
    data class Snapshot(val still: Boolean, val blurPx: Double, val gravity: DoubleArray?)

    /** 记录一次测量；attitude 是设备到“z 朝上”参考系的四元数 x, y, z, w，与 iOS 相同。 */
    data class Measurement(val rotationRate: Double, val blurPx: Double, val gravity: DoubleArray?, val attitude: DoubleArray?)

    class Parameters(
        @Volatile var exposureSeconds: Double = 1.0 / 60,
        @Volatile var focalPx: Double = 3000.0,
        @Volatile var thresholdPx: Double = 0.5,
        @Volatile var holdSeconds: Double = 0.5,
    )

    private class Sample(val time: Double, val x: Double, val y: Double, val z: Double)

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val attitudeSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val thread = HandlerThread("tutticam.motion").apply { start() }
    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    @Volatile private var gravity: DoubleArray? = null
    @Volatile private var attitude: DoubleArray? = null
    private var stillSince: Double? = null
    val params = Parameters()

    val isAvailable: Boolean get() = gyro != null

    fun start() {
        if (gyro == null) return
        val handler = Handler(thread.looper)
        // 按 100 Hz 采样，与 iOS 相同。
        sensors.registerListener(this, gyro, 10_000, handler)
        gravitySensor?.let { sensors.registerListener(this, it, 20_000, handler) }
        attitudeSensor?.let { sensors.registerListener(this, it, 20_000, handler) }
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val time = event.timestamp / 1e9
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                // 把安卓“朝上”的重力向量换成 iOS 的方向和单位（竖拿时 y ≈ −1）。
                gravity = doubleArrayOf(-event.values[0] / 9.81, -event.values[1] / 9.81, -event.values[2] / 9.81)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                attitude = doubleArrayOf(q[1].toDouble(), q[2].toDouble(), q[3].toDouble(), q[0].toDouble())
            }
            Sensor.TYPE_GYROSCOPE -> ingest(Sample(time, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
        }
    }

    private fun ingest(sample: Sample) {
        val p = params
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.isNotEmpty() && sample.time - samples.first().time > HISTORY) samples.removeFirst()
            val blur = blur(recent(sample.time), p)
            if (blur < p.thresholdPx) {
                if (stillSince == null) stillSince = sample.time
            } else {
                stillSince = null
            }
        }
    }

    /** 返回窗口内的采样，调用方需持锁。 */
    private fun recent(end: Double): List<Sample> = samples.filter { end - it.time <= WINDOW }

    /** 判断当前是否静止，没有陀螺仪时总是静止。 */
    fun snapshot(): Snapshot {
        if (!isAvailable) return Snapshot(true, 0.0, gravity)
        val p = params
        synchronized(lock) {
            val last = samples.lastOrNull() ?: return Snapshot(false, 0.0, gravity)
            val blur = blur(recent(last.time), p)
            val still = stillSince?.let { last.time - it >= p.holdSeconds } ?: false
            return Snapshot(still && blur < p.thresholdPx, blur, gravity)
        }
    }

    /** 估算曝光期间的转动和模糊，窗口按快门时间向两侧放宽。 */
    fun measure(at: Double, exposure: Double, focalPx: Double): Measurement {
        if (!isAvailable) return Measurement(0.0, 0.0, gravity, attitude)
        synchronized(lock) {
            val half = max(exposure, WINDOW / 2) + 0.01
            var inWindow = samples.filter { abs(it.time - at) <= half }
            if (inWindow.isEmpty()) samples.lastOrNull()?.let { last -> inWindow = recent(last.time) }
            val rate = meanRotation(inWindow)
            return Measurement(rate, max(0.0, rate - NOISE_FLOOR) * exposure * focalPx, gravity, attitude)
        }
    }

    private fun blur(list: List<Sample>, p: Parameters) = max(0.0, meanRotation(list) - NOISE_FLOOR) * p.exposureSeconds * p.focalPx

    private fun meanRotation(list: List<Sample>): Double {
        if (list.isEmpty()) return 0.0
        var x = 0.0; var y = 0.0; var z = 0.0
        list.forEach { x += it.x; y += it.y; z += it.z }
        val n = list.size
        return sqrt((x / n) * (x / n) + (y / n) * (y / n) + (z / n) * (z / n))
    }

    companion object {
        private const val WINDOW = 0.1
        /** 保留 5 秒采样，照片交回时要回看曝光那一刻。 */
        private const val HISTORY = 5.0
        /** 设定陀螺仪静止噪声（rad/s），目前沿用 iPhone 实测值。 */
        const val NOISE_FLOOR = 0.003

        /** 按重力判断照片要顺时针转多少度，平放时保持上次的方向。 */
        fun deviceRotation(gravity: DoubleArray?, current: Int): Int {
            val g = gravity ?: return current
            if (abs(g[2]) >= 0.8) return current
            return if (abs(g[1]) >= abs(g[0])) {
                if (g[1] < 0) 0 else 180
            } else {
                // 左侧朝上时 x 方向重力为正。
                if (g[0] > 0) 90 else 270
            }
        }
    }
}
