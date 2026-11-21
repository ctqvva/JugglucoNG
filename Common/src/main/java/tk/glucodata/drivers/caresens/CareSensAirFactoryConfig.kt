// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

internal object CareSensAirFactoryConfig {
    const val PACKED_BYTES = 446
    private const val HEADER_BYTES = 72
    private const val CONFIG_BYTES = 434
    private const val STABILIZATION_OFFSET = 53
    const val START_OFFSET = 442
    const val RECORD_INTERVAL_SECONDS = 300L
    const val MAX_SEQUENCE = 4608L // Legacy allocates 16 days of five-minute readings.

    /**
     * Apply the v2+ continuation copy limits from air/java.cpp. The checksum
     * covers transport data including bytes that do not belong to DeviceInfo.
     * The three trailing fields are supplied independently by the C0 start reply.
     */
    fun pack(
        transfer: CareSensAirInfoAssembler.Result.Verified,
        startInfo: CareSensAirProtocol.StartInfo,
        startSeconds: Long,
    ): ByteArray {
        require(transfer.header.libraryVersion >= 2) { "Unsupported factory library version" }
        require(transfer.header.cgmDataInterval.toLong() == RECORD_INTERVAL_SECONDS) { "Unexpected record interval" }
        require(transfer.header.slope100 > 0f && transfer.header.minGlucose < transfer.header.maxGlucose) {
            "Invalid factory calibration scale"
        }
        require(startSeconds in 1..0xFFFFFFFFL && startInfo.eapp > 0f && startInfo.vref > 0f) {
            "Invalid CareSens Air start information"
        }

        val payload = transfer.payloadWithChecksum()
        val packed = payload.copyOf(HEADER_BYTES).copyOf(PACKED_BYTES)
        var source = transfer.headerPayloadSize
        var destination = HEADER_BYTES
        for (size in transfer.continuationDataSizes()) {
            val legacyLimit = if (destination == HEADER_BYTES) 157 else 205
            val count = minOf(size, legacyLimit, CONFIG_BYTES - destination)
            payload.copyInto(packed, destination, source, source + count)
            source += size
            destination += count
        }
        require(destination == CONFIG_BYTES) { "Incomplete factory calibration parameters" }
        packed.littleEndian()
            .putShort(STABILIZATION_OFFSET, 1800)
            .putFloat(CONFIG_BYTES, startInfo.vref)
            .putFloat(CONFIG_BYTES + Float.SIZE_BYTES, startInfo.eapp)
            .putInt(START_OFFSET, startSeconds.toInt())
        return packed
    }

    /**
     * Report each field in packed order, using the signedness of
     * air1_opcal4_device_info_t in air.hpp. The Java adapter owns the field binding.
     */
    fun decode(packed: ByteArray, set: (String, Any) -> Unit) {
        require(packed.size == PACKED_BYTES)
        val bytes = packed.littleEndian()
        fun u8() = bytes.get().toInt() and 0xFF
        fun i16() = bytes.getShort().toInt()
        fun value() = bytes.getFloat().also { require(it.isFinite()) { "Non-finite factory coefficient" } }
        fun u8(name: String) = set(name, u8())
        fun i8(name: String) = set(name, bytes.get().toInt())
        fun u16(name: String) = set(name, i16() and 0xFFFF)
        fun i16(name: String) = set(name, i16())
        fun u32(name: String) = set(name, bytes.getInt().toLong() and 0xFFFFFFFFL)
        fun float(name: String) = set(name, value())
        fun ascii(name: String, size: Int) {
            val str = ByteArray(size).also { bytes.get(it) }
            set(name, String(str, Charsets.US_ASCII).trimEnd('\u0000'))
        }
        fun u8(name: String, size: Int) = set(name, IntArray(size) { u8() })
        fun u8(name: String, rows: Int, cols: Int) = set(name, Array(rows) { IntArray(cols) { u8() } })
        fun u16(name: String, size: Int) = set(name, IntArray(size) { i16() and 0xFFFF })
        fun i16(name: String, rows: Int, cols: Int) = set(name, Array(rows) { IntArray(cols) { i16() } })
        fun float(name: String, size: Int) = set(name, FloatArray(size) { value() })
        fun float(name: String, rows: Int, cols: Int) = set(name, Array(rows) { FloatArray(cols) { value() } })

        u8("sensorVersion")
        float("ycept")
        float("slope100")
        float("slope")
        float("r2")
        float("t90")
        float("slopeRatio")
        ascii("lot", 10)
        ascii("sensorId", 12)
        ascii("expiryDate", 6)
        u16("stabilizationInterval")
        u16("cgmDataInterval")
        u16("bleAdvInterval")
        u8("bleAdvDuration")
        u8("age")
        u16("allowedList")
        float("maximumValue")
        float("minimumValue")
        u8("cLibraryVersion")
        u8("parameterVersion")
        u8("basicWarmup")
        float("basicYcept")
        u8("contactWinLen")
        u8("contactCond1X10")
        u8("contactCond2X10")
        u8("contactCond3X10")
        u8("fillFlag")
        u8("driftCorrectionOn")
        float("driftCoefficient", 3, 3)
        i16("iRefX100")
        u16("coefLength")
        u16("divPoint")
        u8("iirFlag")
        u8("iirStDX10")
        u8("correct1Flag")
        float("correct1Coeff", 4)
        u8("kalmanT90")
        u8("kalmanDeltaT")
        i16("kalmanQX100", 3, 3)
        i16("kalmanRX100")
        float("bgCalRatio")
        u8("bgCalTimeFactor")
        u8("slopeFactorX10")
        u8("slopeInterUpX10")
        i8("slopeInterDownX10")
        u8("slopeMultiVX10")
        u8("slopeIirThr")
        u8("slopeNegInterThr1X10")
        u8("slopeNegInterThr2X10")
        u8("slopeBgCalThrDown")
        u8("slopeBgCalThrUp")
        u16("slopeMaxSlopeX100")
        u16("slopeMinSlopeX100")
        float("slopeDcalRate")
        u16("slopeDcalTargetLength")
        u16("slopeDcalWindow")
        u8("slopeDcalFactoryCalUse")
        u8("shiftMSel")
        float("shiftCoeff", 4)
        u16("shiftM2X100", 3)
        u8("wSgX100", 7)
        u8("calTrendRate")
        float("calNoise")
        u8("errcodeVersion")
        u8("err1Seq", 3)
        float("err1ContactBad")
        float("err1ThDiff")
        float("err1ThSseDmean", 3)
        u8("err1ThN1", 4)
        u8("err1ThN2", 2, 2)
        u8("err1NConsecutive")
        float("err1ISseDmeanNow", 2)
        u8("err1CountSseDmean")
        u16("err1NLast")
        u8("err1Multi", 2)
        float("err1CurrentAvgDiff")
        u16("err2StartSeq")
        u8("err2Seq", 3)
        float("err2Glu")
        float("err2Cv", 3)
        u8("err2Cummax")
        u8("err2Multi")
        float("err2Ycept")
        float("err2Alpha")
        u16("err345Seq1", 2)
        u8("err345Seq2")
        u16("err345Seq3", 3)
        u16("err345Seq4", 5)
        u16("err345Seq5", 3)
        float("err345Raw", 4)
        float("err345Filtered", 2)
        float("err345Min", 2)
        float("err345Range")
        u8("err345NRange")
        float("err345Md")
        u16("err345NMd")
        u8("err6CalNPts")
        float("err6CalBasicPrct")
        u16("err6CalBasicSeq")
        float("err6CalOriginSlope")
        float("err6CalInVitro", 2)
        float("err6CgmRpd")
        float("err6CgmSlp")
        u8("err6CgmLow3dSeq")
        float("err6CgmLow3dP")
        u8("err6CgmLow1dSeq")
        float("err6CgmLow1dP")
        u8("err6CgmPrct", 3)
        u8("err6CgmDay", 2)
        u16("err6CgmBleBad", 2)
        float("err6CgmPoly2")
        u8("err32Dt", 2)
        u8("err32N", 2)
        float("vref")
        float("eapp")
        u32("sensorStartTime")

        check(!bytes.hasRemaining())
    }
}
