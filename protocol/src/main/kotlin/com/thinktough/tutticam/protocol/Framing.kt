package com.thinktough.tutticam.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/** 封装 TCP 帧：4 字节大端长度加 UTF-8 JSON 正文，格式与 iOS 相同。 */
object Framing {
    /** 限制正文长度，超过就断开连接。 */
    const val MAX_LENGTH = 16_000_000

    fun frame(body: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()

    fun frame(text: String): ByteArray = frame(text.toByteArray(Charsets.UTF_8))

    /** 读取一帧正文；流结束抛 EOFException，长度非法抛 IllegalStateException。 */
    fun read(input: InputStream): ByteArray {
        val header = readFully(input, 4)
        val length = ByteBuffer.wrap(header).int
        check(length in 1 until MAX_LENGTH) { "帧长度不合法：$length" }
        return readFully(input, length)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream(count)
        val buffer = ByteArray(minOf(count, 64 * 1024))
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) throw EOFException()
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }
}
