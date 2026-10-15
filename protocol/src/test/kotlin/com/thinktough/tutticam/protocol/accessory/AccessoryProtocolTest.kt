package com.thinktough.tutticam.protocol.accessory

import com.thinktough.tutticam.protocol.CameraBrand
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 测试外接相机协议层，用例与 iOS 相同：广播识别、UUID、GoPro 分包和状态、尼康握手。 */
class AccessoryProtocolTest {
    private fun ad(name: String? = null, manufacturer: List<Int> = emptyList(), services: List<java.util.UUID> = emptyList()) =
        Advertisement(name, ByteArray(manufacturer.size) { manufacturer[it].toByte() }, services)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun detectsCamerasFromAdvertisements() {
        assertEquals(CameraKind.DjiR, CameraKind.detect(ad(manufacturer = listOf(0xAA, 0x08, 0x12, 0x34, 0xFA, 0x00))))
        val sony = listOf(0x2D, 0x01, 0x03, 0x00, 0x64, 0x00, 0x45, 0x31, 0x22, 0xC2, 0x00, 0x21, 0x00)
        assertEquals(CameraKind.Sony, CameraKind.detect(ad(manufacturer = sony)), "索尼：配对中且遥控已开")
        assertNull(CameraKind.detect(ad(manufacturer = sony.toMutableList().also { it[9] = 0x82 })), "索尼：没在配对时不列出")
        assertEquals(CameraKind.CanonRemote, CameraKind.detect(ad(services = listOf(CanonUuid.remoteService))))
        assertEquals(CameraKind.CanonSmart, CameraKind.detect(ad(services = listOf(CanonUuid.smartService))))
        val fujiBasic = listOf(0xD8, 0x04, 0x02, 0x11, 0x22, 0x33, 0x44)
        assertEquals(CameraKind.FujifilmBasic, CameraKind.detect(ad(manufacturer = fujiBasic, services = listOf(FujifilmUuid.basicService))))
        assertContentEquals(bytes(0x11, 0x22, 0x33, 0x44), FujifilmUuid.token(ad(manufacturer = fujiBasic)))
        assertNull(CameraKind.detect(ad(manufacturer = fujiBasic)), "富士旧固件：不广播服务时不列出")
        val fujiSecure = listOf(0xD8, 0x04, 0x03, 0x01, 0x02, 0x03, 0x04, 0x05)
        assertEquals(CameraKind.FujifilmSecure, CameraKind.detect(ad(manufacturer = fujiSecure, services = listOf(FujifilmUuid.secureService))))
        assertContentEquals(bytes(1, 2, 3, 4, 5), FujifilmUuid.serial(ad(manufacturer = fujiSecure)))
        assertTrue(FujifilmUuid.isReconnect(ad(manufacturer = fujiSecure, services = listOf(FujifilmUuid.securePairService)), bytes(1, 2, 3, 4, 5)))
        assertFalse(FujifilmUuid.isReconnect(ad(manufacturer = fujiSecure, services = listOf(FujifilmUuid.securePairService)), bytes(9, 9, 9, 9, 9)))
        assertEquals(CameraKind.NikonRemote, CameraKind.detect(ad(services = listOf(NikonUuid.service))))
        val nikonId = NikonPairing.newId()
        val nikonBack = listOf(0x99, 0x03) + nikonId.copyOfRange(0, 4).map { it.toInt() and 0xFF } + listOf(0x00)
        assertNull(CameraKind.detect(ad(manufacturer = nikonBack, services = listOf(NikonUuid.service))), "尼康：在找已配对遥控器时不列为新相机")
        assertTrue(NikonPairing.isReconnect(ad(manufacturer = nikonBack, services = listOf(NikonUuid.service)), nikonId))
        assertEquals(CameraKind.Ricoh, CameraKind.detect(ad(services = listOf(RicohUuid.shooting))))
        assertEquals(CameraKind.Ricoh, CameraKind.detect(ad(name = "RICOH GR IIIx")))
        assertNull(CameraKind.detect(ad(name = "Greg's iPhone")), "名字里只是含 GR 字母的不误认")
        assertEquals(CameraKind.GoPro, CameraKind.detect(ad(name = "GoPro 1234", services = listOf(uuid16(0xFEA6)))))
        assertNull(CameraKind.detect(ad(name = "AirPods", manufacturer = listOf(0x4C, 0x00, 0x07, 0x19))), "无关设备不列出")
    }

    @Test
    fun parsesRawAdvertisementBytes() {
        // 构造广播：名字 GR、厂商数据、16 位服务 FEA6、倒序的 128 位服务。
        val service128 = FujifilmUuid.secureService
        val hex = service128.toString().replace("-", "")
        val little = ByteArray(16) { hex.substring(30 - it * 2, 32 - it * 2).toInt(16).toByte() }
        val raw = bytes(0x03, 0x09, 'G'.code, 'R'.code) + bytes(0x08, 0xFF, 0xD8, 0x04, 0x03, 1, 2, 3, 4) +
            bytes(0x03, 0x03, 0xA6, 0xFE) + bytes(0x11, 0x07) + little + bytes(0x00, 0x00)
        val parsed = Advertisement.parse(raw, null)
        assertEquals("GR", parsed.name)
        assertContentEquals(bytes(0xD8, 0x04, 0x03, 1, 2, 3, 4), parsed.manufacturer)
        assertTrue(parsed.advertises(uuid16(0xFEA6)))
        assertTrue(parsed.advertises(service128))
        assertTrue(parsed.company(FujifilmUuid.COMPANY))
    }

    @Test
    fun uuidFormats() {
        assertEquals("91f1de68-dff6-466e-8b65-ff13b0f16fb8", FujifilmUuid.pairService.toString())
        assertEquals("00002087-3dd4-4255-8d62-6dc7b9bd5561", NikonUuid.remotePair.toString())
        assertEquals("0000ff01-0000-1000-8000-00805f9b34fb", SonyUuid.command.toString())
        assertEquals("b5f90072-aa8d-11e3-9046-0002a5d5c51b", GoProUuid.command.toString())
        val groups = CameraBrand.entries.map { it.group }
        assertEquals(groups.size, groups.toSet().size, "各品牌默认编号组不重复")
        assertEquals(listOf(CameraBrand.Dji), CameraBrand.entries.filter { it.verified })
        assertEquals(CameraKind.FujifilmSecure, CameraKind.parse("fujifilmSecure"))
    }

    @Test
    fun goProCommandsAndPackets() {
        for (command in listOf(GoProCommand.shutterOn, GoProCommand.thirdPartyClient, GoProCommand.photoPresetGroup,
            GoProCommand.keepAlive, GoProCommand.registerStatus)) {
            assertEquals(command.size - 1, command[0].toInt(), "长度头与内容一致")
        }
        val assembler = GoProAssembler()
        assertContentEquals(bytes(0x01, 0x00), assembler.append(bytes(0x02, 0x01, 0x00)))
        val long = ByteArray(30) { it.toByte() }
        assertNull(assembler.append(bytes(0x20, 30) + long.copyOfRange(0, 18)), "13 位长度头：第一包未完整")
        assertContentEquals(long, assembler.append(bytes(0x80) + long.copyOfRange(18, 30)), "续包拼出完整 30 字节")
        assertNull(assembler.append(bytes(0x80, 0x01)), "没有开头的续包丢弃")
    }

    @Test
    fun goProStatuses() {
        val reply = bytes(0x53, 0x00, 70, 1, 87, 34, 4, 0x00, 0x00, 0x04, 0xD2, 8, 1, 1, 6, 1, 0)
        val values = assertNotNull(GoProCommand.statuses(reply))
        assertEquals(87, GoProCommand.integer(values.getValue(GoProCommand.StatusId.BATTERY)))
        assertEquals(1234, GoProCommand.integer(values.getValue(GoProCommand.StatusId.REMAINING_PHOTOS)))
        assertEquals(1, GoProCommand.integer(values.getValue(GoProCommand.StatusId.BUSY)))
        assertEquals(0, GoProCommand.integer(values.getValue(GoProCommand.StatusId.HOT)))
        assertContentEquals(bytes(50), GoProCommand.statuses(bytes(0x93, 0x00, 70, 1, 50))?.get(70))
        assertNull(GoProCommand.statuses(bytes(0x53, 0x02, 70, 1, 50)))
        assertNull(GoProCommand.statuses(bytes(0x01, 0x00)))
    }

    @Test
    fun nikonPairing() {
        val id = NikonPairing.newId()
        val stage1 = NikonPairing.stage1(id)
        assertTrue(stage1.size == 17 && stage1[0].toInt() == 1 && stage1[8].toInt() == 1 && stage1.copyOfRange(9, 17).contentEquals(id))
        assertTrue(id.size == 8 && id[0].toInt() == 1)
        assertTrue(NikonPairing.stage2.size == 17 && NikonPairing.stage3[0].toInt() == 3)
        val stage4 = bytes(0x04) + ByteArray(8) + "3012345\u0000".toByteArray()
        assertEquals("3012345", NikonPairing.serialFromStage4(stage4))
        assertNull(NikonPairing.serialFromStage4(stage4.copyOf().also { it[3] = 1 }))
    }
}
