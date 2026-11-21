package tk.glucodata.drivers.caresens

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.drivers.caresens.CareSensAirInfoAssembler.Failure
import tk.glucodata.drivers.caresens.CareSensAirInfoAssembler.Result

class CareSensAirInfoAssemblerTests {
    @Test
    fun checksumValidTransferDecodesEveryPackedHeaderField() {
        val payload = fixture()
        val assembler = receive(payload, 157, 205, 292)
        // The terminator contributes no bytes to the checksum.
        val result = assembler.accept(wireBytes("c203abcd")) as Result.Verified
        val header = result.header
        assertEquals(129, header.sensorVersion)
        assertEquals(-1.25f, header.ycept, 0f)
        assertEquals(3.5f, header.slope100, 0f)
        assertEquals(4.25f, header.slope, 0f)
        assertEquals(0.75f, header.r2, 0f)
        assertEquals(12.5f, header.t90, 0f)
        assertEquals(1.125f, header.slopeRatio, 0f)
        assertEquals("LOT1234", header.lot)
        assertEquals("SYNTHETIC123", header.sensorId)
        assertEquals("291231", header.expiry)
        assertEquals(7200, header.stabilizationInterval)
        assertEquals(300, header.cgmDataInterval)
        assertEquals(600, header.bleAdvInterval)
        assertEquals(250, header.bleAdvDuration)
        assertEquals(18, header.age)
        assertEquals(0xA501, header.allowedList)
        assertEquals(500f, header.maxGlucose, 0f)
        assertEquals(40f, header.minGlucose, 0f)
        assertEquals(3, header.libraryVersion)
        assertArrayEquals(payload, result.payloadWithChecksum())
        assertArrayEquals(intArrayOf(157, 205, 292), result.continuationPayloadSizes())
        result.payloadWithChecksum().fill(0)
        result.continuationPayloadSizes().fill(0)
        assertArrayEquals(payload, result.payloadWithChecksum())
        assertArrayEquals(intArrayOf(157, 205, 292), result.continuationPayloadSizes())
        assertEquals(Result.Rejected(Failure.CLOSED), assembler.accept(END))
    }

    @Test
    fun acceptsDifferentContinuationBoundariesWithoutInterpretingThem() {
        val payload = fixture().apply {
            this[71] = 1
            wireBytes("d615").copyInto(this, destinationOffset = 724)
        }
        // Deliberately split the CRC word across two continuation frames.
        val result = receive(payload, 202, 125, 326, 1).accept(END) as Result.Verified
        assertEquals(1, result.header.libraryVersion)
        assertArrayEquals(payload, result.payloadWithChecksum())
    }

    @Test
    fun rejectsBadCrcEvenWhenAllBytesArePresent() {
        val payload = fixture().apply { this[389] = (this[389].toInt() xor 1).toByte() }
        assertEquals(
            Result.Rejected(Failure.CHECKSUM),
            receive(payload, 327, 327).accept(END),
        )

        val swapped = fixture().apply { wireBytes("b79d").copyInto(this, destinationOffset = 724) }
        assertEquals(
            Result.Rejected(Failure.CHECKSUM),
            receive(swapped, 327, 327).accept(END),
        )
    }

    @Test
    fun rejectsNonFiniteHeaderEvenWithValidCrc() {
        val payload = fixture().apply {
            wireBytes("0000c07f").copyInto(this, destinationOffset = 1)
            wireBytes("d891").copyInto(this, destinationOffset = 724)
        }
        assertEquals(
            Result.Rejected(Failure.INVALID_HEADER),
            receive(payload, 327, 327).accept(END),
        )
    }

    @Test
    fun failsClosedOnMalformedOrOutOfOrderFrames() {
        val header = wireBytes("c201") + fixture().copyOf(72)
        assertEquals(
            Result.Rejected(Failure.UNEXPECTED_FRAME),
            CareSensAirInfoAssembler().accept(wireBytes("c2")),
        )
        assertEquals(
            Result.Rejected(Failure.OUT_OF_ORDER),
            CareSensAirInfoAssembler().accept(wireBytes("c202ab")),
        )
        assertEquals(
            Result.Rejected(Failure.INCOMPLETE),
            CareSensAirInfoAssembler().accept(END),
        )
        assertEquals(
            Result.Rejected(Failure.FRAME_SIZE),
            CareSensAirInfoAssembler().accept(header.copyOf(73)),
        )
        assertEquals(
            Result.Rejected(Failure.FRAME_SIZE),
            CareSensAirInfoAssembler().accept(header.copyOf(75)),
        )
        for ((badFrame, failure) in listOf(
            header to Failure.OUT_OF_ORDER,
            wireBytes("c202") to Failure.FRAME_SIZE,
            wireBytes("c202") + ByteArray(655) to Failure.FRAME_SIZE,
            wireBytes("c204") to Failure.UNEXPECTED_FRAME,
            END to Failure.INCOMPLETE,
        )) {
            val assembler = CareSensAirInfoAssembler()
            assertEquals(Result.Pending, assembler.accept(header))
            assertEquals(Result.Rejected(failure), assembler.accept(badFrame))
            assertEquals(Result.Rejected(Failure.CLOSED), assembler.accept(header))
        }

        assertEquals(
            Result.Rejected(Failure.INCOMPLETE),
            receive(fixture(), 327, 326).accept(END),
        )
    }

    private fun receive(payload: ByteArray, vararg continuationSizes: Int): CareSensAirInfoAssembler {
        val assembler = CareSensAirInfoAssembler()
        assertEquals(Result.Pending, assembler.accept(wireBytes("c201") + payload.copyOf(72)))
        var offset = 72
        for (size in continuationSizes) {
            val frame = wireBytes("c202") + payload.copyOfRange(offset, offset + size)
            assertEquals(Result.Pending, assembler.accept(frame))
            frame.fill(0)
            offset += size
        }
        return assembler
    }

    private fun fixture(): ByteArray {
        // Synthetic header packed independently with Python struct. Checksum from binascii.crc_hqx.
        val header = wireBytes(
            "810000a0bf00006040000088400000403f000048410000903f4c4f5431323334000000" +
                "53594e544845544943313233323931323331201c2c015802fa1201a50000fa430000204203",
        )
        val continuation = ByteArray(652) { (it * 47 + 31).toByte() }
        return header + continuation + wireBytes("9db7")
    }

    private companion object {
        val END = wireBytes("c203")
    }
}
