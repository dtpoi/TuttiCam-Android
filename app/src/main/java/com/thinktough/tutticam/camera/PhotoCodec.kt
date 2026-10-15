package com.thinktough.tutticam.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.DngCreator
import android.location.Location
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File

/** 把帧编成 JPEG、HEIC 或 DNG 文件，在编码线程调用。 */
object PhotoCodec {
    private const val TAG = "TuttiCam.Codec"

    fun jpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream(width * height / 3)
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }

    /** 把画面顺时针转正后编成 HEIC，因为各软件对 irot 和 EXIF 方向的处理不一致。 */
    fun heic(nv21: ByteArray, width: Int, height: Int, rotation: Int, exif: ByteArray?, scratch: File, quality: Int = 90): ByteArray {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val quarter = rotation % 180 != 0
        val outWidth = if (quarter) height else width
        val outHeight = if (quarter) width else height
        val i420 = ByteArray(width * height * 3 / 2)
        val luma = width * height
        val chroma = luma / 4
        rotate(nv21, 0, 1, width, width, height, rotation, i420, 0)
        // NV21 色度是 VU 交错，I420 是先 U 后 V 两个平面。
        rotate(nv21, luma + 1, 2, width, width / 2, height / 2, rotation, i420, luma)
        rotate(nv21, luma, 2, width, width / 2, height / 2, rotation, i420, luma + chroma)
        val file = File.createTempFile("photo", ".heic", scratch)
        try {
            HeifWriter.Builder(file.absolutePath, outWidth, outHeight, HeifWriter.INPUT_MODE_BUFFER)
                .setQuality(quality)
                .setGridEnabled(true)
                .build()
                .use { writer ->
                    writer.start()
                    writer.addYuvBuffer(ImageFormat.YUV_420_888, i420)
                    exif?.let { writer.addExifData(0, it, 0, it.size) }
                    writer.stop(5_000)
                    android.util.Log.i(TAG, "HEIC ${outWidth}x$outHeight 编码 ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** 用 RAW 数据和拍摄结果写 DNG，方向写进 EXIF，有定位时写 GPS。 */
    fun dng(raw: CameraEngine.RawData, rotation: Int, location: Location?): ByteArray {
        DngCreator(raw.characteristics, raw.result).use { creator ->
            creator.setOrientation(exifOrientation(rotation))
            location?.let(creator::setLocation)
            val out = ByteArrayOutputStream(raw.size.width * raw.size.height * 2 + (1 shl 20))
            raw.pixels.rewind()
            creator.writeByteBuffer(out, raw.size, raw.pixels, 0)
            return out.toByteArray()
        }
    }

    fun exifOrientation(rotation: Int) = when ((rotation % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** 把一个 w×h 平面顺时针转 rotation 度，紧密写进 dst；转 90 或 270 度时宽高对调。 */
    internal fun rotate(src: ByteArray, offset: Int, pixelStride: Int, rowStride: Int, w: Int, h: Int, rotation: Int,
                       dst: ByteArray, dstOffset: Int) {
        when ((rotation % 360 + 360) % 360) {
            90 -> for (y in 0 until h) {
                val row = offset + y * rowStride
                val column = dstOffset + h - 1 - y
                for (x in 0 until w) dst[column + x * h] = src[row + x * pixelStride]
            }
            180 -> for (y in 0 until h) {
                val row = offset + y * rowStride
                val end = dstOffset + (h - 1 - y) * w + w - 1
                for (x in 0 until w) dst[end - x] = src[row + x * pixelStride]
            }
            270 -> for (y in 0 until h) {
                val row = offset + y * rowStride
                val column = dstOffset + (w - 1) * h + y
                for (x in 0 until w) dst[column - x * h] = src[row + x * pixelStride]
            }
            else -> for (y in 0 until h) {
                val row = offset + y * rowStride
                val out = dstOffset + y * w
                if (pixelStride == 1) System.arraycopy(src, row, dst, out, w)
                else for (x in 0 until w) dst[out + x] = src[row + x * pixelStride]
            }
        }
    }
}
