package com.thinktough.tutticam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 在色温、色调和 Camera2 白平衡增益之间换算，做法同 DNG：增益等于 1 / 传感器里的中性色。
 * 色调与 Adobe 相同，单位 1/3000 uv，正值偏洋红；没有标定数据时用黑体近似。
 */
class WhiteBalance(characteristics: CameraCharacteristics) {
    private class Calibration(val kelvin: Double, val matrix: DoubleArray)

    private val calibrations: List<Calibration> = buildList {
        fun add(illuminant: Int?, color: ColorSpaceTransform?, calibration: ColorSpaceTransform?) {
            val kelvin = illuminant?.let(::illuminantKelvin) ?: return
            val cm = color?.let(::toArray) ?: return
            val cc = calibration?.let(::toArray) ?: IDENTITY
            add(Calibration(kelvin, multiply(cc, cm)))
        }
        add(characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
        add(characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
    }.sortedBy { it.kelvin }

    val calibrated: Boolean get() = calibrations.isNotEmpty()

    private var cacheKey: FloatArray? = null
    private var cacheValue = 5500f to 0f

    /** 返回把该色温、色调校正成中性灰的增益，绿色为 1。 */
    fun gains(kelvin: Float, tint: Float): RggbChannelVector {
        val (r, g, b) = neutral(kelvin.toDouble(), tint.toDouble())
        return RggbChannelVector((g / r).toFloat(), 1f, 1f, (g / b).toFloat())
    }

    /** 由增益反推色温和色调：先按红蓝比找色温，再按绿色找色调。 */
    @Synchronized
    fun estimate(gains: RggbChannelVector): Pair<Float, Float> {
        val key = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
        cacheKey?.let { old -> if (old.indices.all { abs(old[it] - key[it]) < 0.002f }) return cacheValue }
        val green = (gains.greenEven + gains.greenOdd) / 2
        val red = gains.red / green
        val blue = gains.blue / green
        fun ratioError(k: Float, t: Float): Float {
            val g = gains(k, t)
            return abs(g.red / g.blue - red / blue)
        }
        var kelvin = search(2000f, 12000f, 50f) { ratioError(it, 0f) }
        val tint = search(-150f, 150f, 2f) { t ->
            val g = gains(kelvin, t)
            // 对齐红蓝的几何平均后，只比较绿色的偏离。
            val scale = Math.sqrt((red * blue).toDouble() / (g.red * g.blue)).toFloat()
            abs(scale - 1f)
        }
        kelvin = search(maxOf(2000f, kelvin - 300f), minOf(12000f, kelvin + 300f), 10f) { ratioError(it, tint) }
        cacheKey = key
        cacheValue = kelvin to tint
        return cacheValue
    }

    private inline fun search(from: Float, to: Float, step: Float, error: (Float) -> Float): Float {
        var best = from
        var bestError = Float.MAX_VALUE
        var x = from
        while (x <= to) {
            val e = error(x)
            if (e < bestError) {
                bestError = e
                best = x
            }
            x += step
        }
        return best
    }

    /** 返回传感器里白点的颜色，未归一。 */
    private fun neutral(kelvin: Double, tint: Double): Triple<Double, Double, Double> {
        val (x, y) = whitePoint(kelvin.coerceIn(1700.0, 20000.0), tint)
        val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        if (calibrations.isEmpty()) {
            // 没有标定数据时用线性 sRGB 近似传感器颜色。
            val rgb = apply(XYZ_TO_SRGB, xyz)
            return Triple(maxOf(rgb[0], 1e-3), maxOf(rgb[1], 1e-3), maxOf(rgb[2], 1e-3))
        }
        val cm = interpolated(kelvin)
        val n = apply(cm, xyz)
        return Triple(maxOf(n[0], 1e-4), maxOf(n[1], 1e-4), maxOf(n[2], 1e-4))
    }

    private fun interpolated(kelvin: Double): DoubleArray {
        if (calibrations.size == 1) return calibrations[0].matrix
        val low = calibrations.first()
        val high = calibrations.last()
        if (kelvin <= low.kelvin) return low.matrix
        if (kelvin >= high.kelvin) return high.matrix
        val w = (1 / kelvin - 1 / high.kelvin) / (1 / low.kelvin - 1 / high.kelvin)
        return DoubleArray(9) { w * low.matrix[it] + (1 - w) * high.matrix[it] }
    }

    companion object {
        private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        private val XYZ_TO_SRGB = doubleArrayOf(
            3.2406, -1.5372, -0.4986,
            -0.9689, 1.8758, 0.0415,
            0.0557, -0.2040, 1.0570,
        )

        /** 把 EXIF LightSource 取值换成色温，与 DNG SDK 相同。 */
        private fun illuminantKelvin(value: Int): Double? = when (value) {
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2850.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3450.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5000.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 -> 5500.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6400.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65 -> 6500.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE, CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 -> 7500.0
            else -> null
        }

        private fun toArray(t: ColorSpaceTransform): DoubleArray =
            DoubleArray(9) { t.getElement(it % 3, it / 3).toDouble() }

        private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val row = i / 3
            val col = i % 3
            (0 until 3).sumOf { a[row * 3 + it] * b[it * 3 + col] }
        }

        private fun apply(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { row ->
            m[row * 3] * v[0] + m[row * 3 + 1] * v[1] + m[row * 3 + 2] * v[2]
        }

        /** 返回普朗克轨迹上的 CIE 1960 uv，用 Krystek 近似。 */
        private fun planckUv(t: Double): Pair<Double, Double> {
            val u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t)
            val v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)
            return u to v
        }

        /** 把色温和色调换成 CIE xy，色调沿轨迹法线偏移。 */
        fun whitePoint(kelvin: Double, tint: Double): Pair<Double, Double> {
            val (u0, v0) = planckUv(kelvin)
            val (u1, v1) = planckUv(kelvin + 1)
            val du = u1 - u0
            val dv = v1 - v0
            val len = hypot(du, dv).takeIf { it > 0 } ?: 1.0
            // 切线转 90° 得法线，取偏绿的方向。
            var nu = -dv / len
            var nv = du / len
            if (nv < 0) {
                nu = -nu
                nv = -nv
            }
            val offset = -tint / 3000
            val u = u0 + nu * offset
            val v = v0 + nv * offset
            val d = 2 * u - 8 * v + 4
            return (3 * u / d) to (2 * v / d)
        }
    }
}
