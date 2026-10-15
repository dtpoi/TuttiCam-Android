package com.thinktough.tutticam.protocol.accessory

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 测试 DJI R SDK 协议层，用例与 iOS 相同，含官方示例生成的 10 条连接请求帧。 */
class DjiProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** 使用 DJI 文档里“模式切换”的示例帧（切到运动延时）。 */
    private val docFrame = bytes(0xAA, 0x1B, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x57, 0xEE, 0x1D, 0x04,
        0x00, 0x00, 0x33, 0xFF, 0x0A, 0x01, 0x47, 0x39, 0x36, 0xF4, 0xFA, 0xE1, 0xD0)

    private val samples by lazy {
        javaClass.getResource("/dji-connect-frames.txt")!!.readText().lines().filter { it.isNotBlank() }.map { hex(it.trim()) }
    }

    @Test
    fun crcMatchesDocument() {
        assertEquals(0xEE57, DjiProtocol.crc16(docFrame, 0, 10))
        assertEquals(0xD0E1FAF4L, DjiProtocol.crc32(docFrame, 0, 23))
    }

    @Test
    fun encodesAndDecodesDocumentFrame() {
        val frame = DjiProtocol.Frame(0x01, 5, 0x1D, 0x04, OsmoCommand.modeSwitch(0x0A))
        assertContentEquals(docFrame, DjiProtocol.encode(frame))
        assertEquals(frame, DjiProtocol.decode(docFrame))
        assertNull(DjiProtocol.decode(docFrame.copyOf().also { it[20] = (it[20].toInt() xor 0xFF).toByte() }))
    }

    @Test
    fun rebuildsOfficialConnectionRequests() {
        assertEquals(10, samples.size)
        for (sample in samples) {
            val frame = assertNotNull(DjiProtocol.decode(sample))
            val p = frame.payload
            val rebuilt = DjiProtocol.encode(DjiProtocol.Frame(frame.cmdType, frame.seq, 0x00, 0x19,
                OsmoCommand.connectionRequest(0x12345678L, bytes(0x38, 0x34, 0x56, 0x78, 0x9A, 0xBC), p[26].toInt() and 0xFF,
                    DjiProtocol.readLe16(p, 27))))
            assertContentEquals(sample, rebuilt)
            assertTrue(frame.cmdSet == 0x00 && frame.cmdId == 0x19 && frame.cmdType == 0x02)
        }
    }

    @Test
    fun streamParserHandlesSplitAndNoise() {
        val shutter = DjiProtocol.encode(DjiProtocol.Frame(0x01, 7, 0x00, 0x11, OsmoCommand.shutterClick))
        val stream = bytes(0x55, 0x0D, 0x04, 0x33, 0x00) + docFrame + bytes(0x12, 0xAA, 0x00) + samples[0] + shutter
        val parser = DjiProtocol.StreamParser()
        val parsed = mutableListOf<DjiProtocol.Frame>()
        var offset = 0
        for (size in listOf(3, 1, 9, 20, 2, 40, 7, 100, 200)) {
            if (offset >= stream.size) break
            val end = minOf(stream.size, offset + size)
            parsed += parser.append(stream.copyOfRange(offset, end))
            offset = end
        }
        parsed += parser.append(stream.copyOfRange(offset, stream.size))
        assertEquals(listOf(0x04, 0x19, 0x11), parsed.map { it.cmdId })
    }

    @Test
    fun handshakeShutterAndStatus() {
        val session = OsmoSession(0x12345678L, bytes(1, 2, 3, 4, 5, 6), 1)
        val start = session.start(paired = false, code = 4321)
        val request = assertNotNull(DjiProtocol.decode((start.first() as OsmoSession.Output.Send).bytes))
        assertTrue(request.cmdId == 0x19 && request.payload[26].toInt() == 1 && DjiProtocol.readLe16(request.payload, 27) == 4321)
        assertTrue(OsmoSession.Output.PhaseChanged(OsmoSession.Phase.WaitingForCamera(4321)) in start)

        val cameraAck = DjiProtocol.encode(DjiProtocol.Frame(0x20, request.seq, 0x00, 0x19, DjiProtocol.le32(0xFF66) + ByteArray(5)))
        assertTrue(session.receive(cameraAck).isEmpty(), "相机应答后继续等待确认")

        val cameraRequest = DjiProtocol.encode(DjiProtocol.Frame(0x02, 300, 0x00, 0x19,
            OsmoCommand.connectionRequest(0xFF66, bytes(9, 9, 9, 9, 9, 9), 2, 0)))
        val out = session.receive(cameraRequest.copyOfRange(0, 10)) + session.receive(cameraRequest.copyOfRange(10, cameraRequest.size))
        assertTrue(OsmoSession.Output.PhaseChanged(OsmoSession.Phase.Connected("Osmo 360", 0xFF66)) in out)
        val sent = out.filterIsInstance<OsmoSession.Output.Send>().mapNotNull { DjiProtocol.decode(it.bytes) }
        assertEquals(3, sent.size)
        assertTrue(sent[0].isAck && sent[0].seq == 300 && sent[0].payload[4].toInt() == 0 && sent[0].payload[5].toInt() == 1)
        assertTrue(sent[1].cmdSet == 0x1D && sent[1].cmdId == 0x05 && sent[1].payload[0].toInt() == 3)
        assertTrue(sent[2].cmdSet == 0x1D && sent[2].cmdId == 0x04 && (sent[2].payload[4].toInt() and 0xFF) == 0x3F)

        val shutter = assertNotNull(session.shutter()?.let(DjiProtocol::decode))
        assertTrue(shutter.cmdId == 0x11 && shutter.payload.contentEquals(OsmoCommand.shutterClick))
        val ack = DjiProtocol.encode(DjiProtocol.Frame(0x20, shutter.seq, 0x00, 0x11, bytes(0)))
        assertEquals(listOf<OsmoSession.Output>(OsmoSession.Output.ShutterAck(shutter.seq)), session.receive(ack))
        assertTrue(session.receive(ack).isEmpty(), "同一条应答不重复计数")

        val payload = ByteArray(38)
        payload[0] = 0x3F; payload[1] = 0x03; payload[2] = 4
        DjiProtocol.le32(1234).copyInto(payload, 19)
        payload[30] = 1; payload[37] = 87
        val push = DjiProtocol.encode(DjiProtocol.Frame(0x00, 9, 0x1D, 0x02, payload))
        val status = (session.receive(push).first() as OsmoSession.Output.Status).status
        assertTrue(status.capturing && status.mode == 0x3F && status.photoSizeTitle == "30MP")
        assertTrue(status.remainPhotos == 1234L && status.battery == 87 && status.temperature == 1 && !status.tooHot)
    }

    @Test
    fun rejectedOnCamera() {
        val session = OsmoSession(1, bytes(1, 2, 3, 4, 5, 6), 0)
        session.start(paired = true, code = 1)
        val refuse = DjiProtocol.encode(DjiProtocol.Frame(0x02, 2, 0x00, 0x19, OsmoCommand.connectionRequest(0xFF66, ByteArray(0), 2, 1)))
        assertEquals(listOf<OsmoSession.Output>(OsmoSession.Output.PhaseChanged(OsmoSession.Phase.Rejected)), session.receive(refuse))
        assertNull(session.shutter())
    }
}
