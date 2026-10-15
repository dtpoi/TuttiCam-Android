package com.thinktough.tutticam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PhotoCodecTest {
    // 使用 3×2 的平面：
    // 1 2 3
    // 4 5 6
    private val plane = byteArrayOf(1, 2, 3, 4, 5, 6)

    private fun rotated(rotation: Int, src: ByteArray = plane, offset: Int = 0, pixelStride: Int = 1, rowStride: Int = 3): ByteArray {
        val out = ByteArray(6)
        PhotoCodec.rotate(src, offset, pixelStride, rowStride, 3, 2, rotation, out, 0)
        return out
    }

    @Test
    fun rotatesClockwise() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), rotated(0))
        // 顺时针 90° 后变成 2×3：
        // 4 1
        // 5 2
        // 6 3
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), rotated(90))
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), rotated(180))
        // 顺时针 270° 后：
        // 3 6
        // 2 5
        // 1 4
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), rotated(270))
    }

    @Test
    fun readsInterleavedChromaWithStride() {
        // 模拟 NV21 交错色度加 2 字节行尾填充，读 U（offset 1，像素跨度 2，行跨度 8）。
        val vu = byteArrayOf(0, 1, 0, 2, 0, 3, 9, 9, 0, 4, 0, 5, 0, 6, 9, 9)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), rotated(90, vu, offset = 1, pixelStride = 2, rowStride = 8))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), rotated(0, vu, offset = 1, pixelStride = 2, rowStride = 8))
    }
}
