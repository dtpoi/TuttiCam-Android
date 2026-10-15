package com.thinktough.tutticam.storage

import android.location.Location
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.thinktough.tutticam.camera.PhotoCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 写照片的 EXIF 和 XMP，不重新压缩画面；XMP 只写进 JPEG。 */
object PhotoTags {
    class Exif(
        val orientationDegrees: Int,
        val takenAt: Date,
        val exposureSeconds: Double?,
        val iso: Int?,
        val focalMm: Float,
        val equivalentMm: Int,
        val aperture: Float,
        val width: Int,
        val height: Int,
        val location: Location?,
    )

    /** 保存拍摄姿态，字段与 iOS 的 PhotoXMP 相同。 */
    class Attitude(
        val gravity: DoubleArray,
        /** 保存四元数 x, y, z, w。 */
        val quaternion: DoubleArray?,
        val cameraPitch: Double?,
        val cameraHeading: Double?,
        val lens: String,
    )

    private const val NAMESPACE = "http://ns.thinktough.com/tutticam/1.0/"
    private const val CONVENTION = "Gravity: unit vector (g) in the CoreMotion device frame (x right, y toward the top edge, " +
        "z out of the screen), pointing down. Quaternion: CMAttitude, device frame to the xArbitraryZVertical reference " +
        "(z up, yaw origin arbitrary per device). CameraPitch: optical-axis elevation in degrees, positive up. " +
        "CameraHeading: degrees clockwise from the lens direction at session start, per device, empty when the lens " +
        "points straight up or down."

    /** 经临时文件写 EXIF，再按需插入 XMP，失败时返回原数据。 */
    fun apply(jpeg: ByteArray, exif: Exif, attitude: Attitude?, scratch: File): ByteArray {
        val tagged = runCatching { writeExif(jpeg, exif, scratch) }.getOrDefault(jpeg)
        return attitude?.let { a -> runCatching { insertXmp(tagged, xmp(a)) }.getOrNull() } ?: tagged
    }

    /** 借一张极小的 JPEG 生成独立 EXIF 块给 HEIC 用，失败时返回 null。 */
    fun exifBlock(exif: Exif, scratch: File): ByteArray? = runCatching {
        val tiny = ByteArrayOutputStream()
        android.graphics.YuvImage(ByteArray(16 * 16 * 3 / 2) { 0x80.toByte() }, android.graphics.ImageFormat.NV21, 16, 16, null)
            .compressToJpeg(android.graphics.Rect(0, 0, 16, 16), 50, tiny)
        val jpeg = writeExif(tiny.toByteArray(), exif, scratch)
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (marker == 0xE1 && start + 6 <= jpeg.size && String(jpeg, start, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                return@runCatching jpeg.copyOfRange(start, pos + 2 + length)
            }
            if (marker == 0xDA) break
            pos += 2 + length
        }
        null
    }.getOrNull()

    private fun writeExif(jpeg: ByteArray, e: Exif, scratch: File): ByteArray {
        val file = File.createTempFile("exif", ".jpg", scratch)
        file.writeBytes(jpeg)
        try {
            val x = ExifInterface(file)
            x.setAttribute(ExifInterface.TAG_ORIENTATION, PhotoCodec.exifOrientation(e.orientationDegrees).toString())
            val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(e.takenAt)
            x.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            x.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            x.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
            x.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(Locale.US, e.takenAt.time % 1000))
            val offset = java.util.TimeZone.getDefault().getOffset(e.takenAt.time) / 60000
            x.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "%s%02d:%02d".format(Locale.US, if (offset < 0) "-" else "+",
                kotlin.math.abs(offset) / 60, kotlin.math.abs(offset) % 60))
            x.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            x.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            x.setAttribute(ExifInterface.TAG_SOFTWARE, "TuttiCam")
            // 有理数都写成“分子/分母”，因为 ExifInterface 1.4.2 换算某些小数会死循环。
            e.exposureSeconds?.takeIf { it.isFinite() && it > 0 }?.let { x.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, rational(it, 1_000_000)) }
            e.iso?.let { x.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
            x.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(e.focalMm * 1000).roundToInt()}/1000")
            x.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, e.equivalentMm.toString())
            if (e.aperture > 0) x.setAttribute(ExifInterface.TAG_F_NUMBER, rational(e.aperture.toDouble(), 100))
            x.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, e.width.toString())
            x.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, e.height.toString())
            e.location?.let { writeGps(x, it) }
            x.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** 把小数的绝对值写成固定分母的“分子/分母”。 */
    private fun rational(value: Double, denominator: Long): String = "${kotlin.math.round(kotlin.math.abs(value) * denominator).toLong()}/$denominator"

    /** 写 GPS 标签，不用 setGpsInfo，因为它换算某些海拔和速度时会死循环。 */
    private fun writeGps(x: ExifInterface, l: Location) {
        fun dms(value: Double): String {
            val total = kotlin.math.round(kotlin.math.abs(value) * 3_600_000).toLong() // 单位是千分之一角秒
            return "${total / 3_600_000}/1,${total / 60_000 % 60}/1,${total % 60_000}/1000"
        }
        if (!l.latitude.isFinite() || !l.longitude.isFinite()) return
        x.setAttribute(ExifInterface.TAG_GPS_LATITUDE, dms(l.latitude))
        x.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (l.latitude >= 0) "N" else "S")
        x.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, dms(l.longitude))
        x.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (l.longitude >= 0) "E" else "W")
        if (l.hasAltitude() && l.altitude.isFinite()) {
            x.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, rational(l.altitude, 100))
            x.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (l.altitude >= 0) "0" else "1")
        }
        if (l.hasSpeed() && l.speed.isFinite()) {
            x.setAttribute(ExifInterface.TAG_GPS_SPEED_REF, "K")
            x.setAttribute(ExifInterface.TAG_GPS_SPEED, rational(l.speed * 3.6, 100))
        }
        if (l.hasBearing() && l.bearing.isFinite()) {
            x.setAttribute(ExifInterface.TAG_GPS_TRACK_REF, "T")
            x.setAttribute(ExifInterface.TAG_GPS_TRACK, rational(l.bearing.toDouble(), 100))
        }
        if (l.hasAccuracy() && l.accuracy.isFinite()) {
            x.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, rational(l.accuracy.toDouble(), 100))
        }
        val utc = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(l.time)).split(" ")
        x.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, utc[0])
        x.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, utc[1])
    }

    private fun xmp(a: Attitude): String {
        fun n(v: Double) = "%.5f".format(Locale.US, v)
        val fields = buildList {
            add("GravityX" to n(a.gravity[0]))
            add("GravityY" to n(a.gravity[1]))
            add("GravityZ" to n(a.gravity[2]))
            a.quaternion?.let { q ->
                add("QuaternionX" to n(q[0]))
                add("QuaternionY" to n(q[1]))
                add("QuaternionZ" to n(q[2]))
                add("QuaternionW" to n(q[3]))
            }
            a.cameraPitch?.let { add("CameraPitch" to "%.2f".format(Locale.US, it)) }
            a.cameraHeading?.let { add("CameraHeading" to "%.2f".format(Locale.US, it)) }
            add("Lens" to a.lens)
            add("Convention" to CONVENTION)
        }
        val attributes = fields.joinToString("\n") { (k, v) -> "    tutticam:$k=\"${escape(v)}\"" }
        return """<?xpacket begin="\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:tutticam="$NAMESPACE"
$attributes/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** 在 SOI 和已有的 APP0、APP1 段之后插入 XMP 段。 */
    private fun insertXmp(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "不是 JPEG" }
        var pos = 2
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            pos += 2 + length
        }
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        require(payload.size + 2 <= 0xFFFF) { "XMP 太长" }
        val out = ByteArrayOutputStream(jpeg.size + payload.size + 4)
        out.write(jpeg, 0, pos)
        out.write(0xFF)
        out.write(0xE1)
        out.write((payload.size + 2) shr 8)
        out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(jpeg, pos, jpeg.size - pos)
        return out.toByteArray()
    }
}
