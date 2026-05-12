// AnytimeAlgorithm.kt — Glucose computation: vendor JNI + linear fallback.
//
// Two paths:
//
//  1. NATIVE: Loads `libalgorithm-jni.so` if it has been dropped into
//     src/main/jniLibs/{abi}. Current official CT4/Yuwell builds export
//     `algorithmLatestGlucose(LatestData)` / `algorithmGlucose(HistoryData)`.
//     Older packaged builds export `algorithm(DataInput)`, so compute() tries
//     the official path first and falls back when that symbol is unavailable.
//
//  2. LINEAR: Pure-Kotlin raw display lane. It is not a replacement for the
//     vendor algorithm and stays separate from native output; Auto+Raw modes
//     must never copy the auto value into the raw lane.
//
// This module hides the choice from callers — `compute()` always returns a
// `Result`. We expose `isNativeAvailable` so UI can show "vendor algorithm" vs
// "linear fallback" badges.

package tk.glucodata.drivers.anytime

import ist.com.sdk.AlgorithmTools
import ist.com.sdk.CurrentGlucose
import ist.com.sdk.DataInput
import ist.com.sdk.DataOutput
import ist.com.sdk.HistoryData
import ist.com.sdk.KRDecodeData
import ist.com.sdk.LatestData
import tk.glucodata.Log

object AnytimeAlgorithm {

    private const val TAG = AnytimeConstants.TAG
    private const val LEGACY_WARMUP_RECORDS = 20
    @Volatile private var officialLatestMissing: Boolean = false
    @Volatile private var officialHistoryMissing: Boolean = false
    @Volatile private var legacyAlgorithmMissing: Boolean = false
    @Volatile private var nativeSkippedNoFactoryLogged: Boolean = false

    /** The .so loads lazily on first JNI call (or first `getInstance()`). */
    val isNativeAvailable: Boolean by lazy {
        runCatching {
            val tools = AlgorithmTools.getInstance()
            // Make a no-op call to force the load; getVersion() returns SDKVersion.
            tools.getVersion()
            true
        }.getOrElse { t ->
            Log.w(TAG, "libalgorithm-jni.so not loadable: ${t.message}")
            false
        }
    }

    enum class Source { NATIVE, LINEAR }

    /** Algorithm output. Mirrors the native `DataOutput` where the bundled JNI provides it. */
    data class Result(
        val glucoseId: Int,
        val mmol: Float,
        val mgdlTimes10: Int,
        val ibNa: Float,
        val iwNa: Float,
        val temperatureC: Float,
        val trend: Int,
        val errorCode: Int,
        val warnCode: Int,
        val source: Source,
        /** Uncalibrated/simple K/R glucose estimate for raw-history view. */
        val rawMgdl: Float = Float.NaN,
        // Native-only diagnostics (NaN/-1 for linear path):
        val sensitivityCoefficient: Float = Float.NaN,
        val kBase: Float = Float.NaN,
        val kAuto: Float = Float.NaN,
        val iw30Iir: Float = Float.NaN,
        val iw48Iir: Float = Float.NaN,
        val beVoltageMv: Int = Int.MIN_VALUE,
        val weVoltageMv: Int = Int.MIN_VALUE,
        val reVoltageMv: Int = Int.MIN_VALUE,
        val ceVoltageMv: Int = Int.MIN_VALUE,
        val bVoltageMv: Int = Int.MIN_VALUE,
    ) {
        val mgdl: Float get() = mgdlTimes10 / 10f
    }

    /**
     * Run the algorithm on a single raw record.
     *
     * @param record   parsed `RX_PUSH_GLUCOSE` / `RX_PULL_GLUCOSE` record
     * @param qr       calibration from the QR (K/R + chemistry IDs)
     * @param family   sensor family + algorithm dispatch id
     * @param sensorIdName advertised name (the JNI uses it to re-detect family)
     * @param sampleTimeMs wall-clock ms for this sample
     * @param lastReferenceBgMgdlTimes10 last fingerstick (mg/dL × 10), 0 if none
     * @param lastReferenceBgGlucoseId   id of the record at which it was set
     * @param sessionPacketsSinceInit    packet count since session start (warmup gate)
     * @param sensorStartTimeMs          estimated time for glucose id 0
     */
    @JvmStatic
    fun compute(
        record: AnytimeRawRecord,
        qr: AnytimeQrCalibration?,
        family: AnytimeConstants.FamilyEntry,
        sensorIdName: String,
        sampleTimeMs: Long,
        lastReferenceBgMgdlTimes10: Int = 0,
        lastReferenceBgGlucoseId: Int = 0,
        sessionPacketsSinceInit: Int = 0,
        recentRecords: List<AnytimeRawRecord> = listOf(record),
        sensorStartTimeMs: Long = 0L,
    ): Result {
        val k = qr?.k ?: 0f
        val r = qr?.r ?: 0f
        val voltageFlag = qr?.voltageFlag ?: 0
        val linear = computeLinear(record, k, r, family, voltageFlag)
        val calibration = qr?.takeIf { it.isFactoryCalibration }
        if (isNativeAvailable && calibration != null) {
            val window = recentRecords
                .filter { it.glucoseId <= record.glucoseId }
                .distinctBy { it.glucoseId }
                .sortedBy { it.glucoseId }
                .ifEmpty { listOf(record) }
            val contiguousHistory = contiguousHistoryThrough(record, window)
            tryOfficialLatest(
                record = record,
                calibration = calibration,
                family = family,
                sensorIdName = sensorIdName,
                sampleTimeMs = sampleTimeMs,
                lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                rawMgdl = linear.rawMgdl,
            )?.let { mapped ->
                if (isNativeResultUsable(mapped)) return mapped
                Log.w(
                    TAG,
                    "official latest algorithm returned invalid result: id=${mapped.glucoseId} " +
                            "mmol=${mapped.mmol} mgdl=${mapped.mgdl} trend=${mapped.trend} " +
                            "err=${mapped.errorCode}; trying history/fallback"
                )
            }
            tryOfficialHistory(
                record = record,
                calibration = calibration,
                family = family,
                sensorIdName = sensorIdName,
                sampleTimeMs = sampleTimeMs,
                lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                window = contiguousHistory,
                sensorStartTimeMs = sensorStartTimeMs,
                rawMgdl = linear.rawMgdl,
            )?.let { mapped ->
                if (isNativeResultUsable(mapped)) return mapped
                Log.w(
                    TAG,
                    "official history algorithm returned invalid result: id=${mapped.glucoseId} " +
                            "mmol=${mapped.mmol} mgdl=${mapped.mgdl} trend=${mapped.trend} " +
                            "err=${mapped.errorCode}; using fallback"
                )
            }
            tryLegacyNative(
                record = record,
                calibration = calibration,
                family = family,
                sensorIdName = sensorIdName,
                sampleTimeMs = sampleTimeMs,
                lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                window = contiguousHistory,
                sensorStartTimeMs = sensorStartTimeMs,
                rawMgdl = linear.rawMgdl,
            )?.let { mapped ->
                if (isNativeResultUsable(mapped)) return mapped
                val msg = "legacy native algorithm returned invalid result: id=${mapped.glucoseId} " +
                        "mmol=${mapped.mmol} mgdl=${mapped.mgdl} trend=${mapped.trend} " +
                        "err=${mapped.errorCode}; using linear fallback"
                if (mapped.glucoseId >= LEGACY_WARMUP_RECORDS) {
                    Log.w(TAG, msg)
                }
            }

        } else if (isNativeAvailable && qr != null && !nativeSkippedNoFactoryLogged) {
            nativeSkippedNoFactoryLogged = true
            Log.w(
                TAG,
                "native algorithm skipped: no factory/manual calibration " +
                        "(format=${qr.format} K=$k R=$r); using linear fallback"
            )
        }
        return linear
    }

    private fun tryOfficialLatest(
        record: AnytimeRawRecord,
        calibration: AnytimeQrCalibration,
        family: AnytimeConstants.FamilyEntry,
        sensorIdName: String,
        sampleTimeMs: Long,
        lastReferenceBgMgdlTimes10: Int,
        lastReferenceBgGlucoseId: Int,
        rawMgdl: Float,
    ): Result? {
        if (officialLatestMissing) return null
        return runCatching {
            val latest = LatestData().apply {
                setGlucoseId(record.glucoseId)
                setIw(record.iwNa)
                setIb(record.ibNa)
                setT(record.temperatureC)
                setK0(calibration.k)
                setR(calibration.r)
                setTimeMillis(sampleTimeMs)
                setSensorInfo(calibration.rawQr)
                setTransmitterName(sensorIdName, calibration.voltageFlag)
                setAlgorithm(nativeAlgorithm(family, calibration.voltageFlag))
                if (lastReferenceBgGlucoseId > 0 && lastReferenceBgMgdlTimes10 > 0) {
                    setNewBgToGlucoseId(lastReferenceBgGlucoseId)
                    setNewBgValue(lastReferenceBgMgdlTimes10 / 10)
                }
            }
            val out: CurrentGlucose? = AlgorithmTools.getInstance().algorithmLatestGlucose(latest)
            out?.let { mapCurrentNative(record, it, rawMgdl) }
        }.getOrElse { t ->
            if (t is UnsatisfiedLinkError) {
                officialLatestMissing = true
                Log.d(TAG, "official latest algorithm unavailable: ${t.message}")
            } else {
                Log.w(TAG, "official latest algorithm failed: ${t.message}")
            }
            null
        }
    }

    private fun tryOfficialHistory(
        record: AnytimeRawRecord,
        calibration: AnytimeQrCalibration,
        family: AnytimeConstants.FamilyEntry,
        sensorIdName: String,
        sampleTimeMs: Long,
        lastReferenceBgMgdlTimes10: Int,
        lastReferenceBgGlucoseId: Int,
        window: List<AnytimeRawRecord>,
        sensorStartTimeMs: Long,
        rawMgdl: Float,
        algorithmGlucoseId: Int = record.glucoseId,
    ): Result? {
        if (officialHistoryMissing) return null
        if (window.size < 2) return null
        return runCatching {
            val eventIds: IntArray
            val bgValues: IntArray
            if (lastReferenceBgGlucoseId > 0 && lastReferenceBgMgdlTimes10 > 0) {
                eventIds = intArrayOf(lastReferenceBgGlucoseId)
                bgValues = intArrayOf(lastReferenceBgMgdlTimes10 / 10)
            } else {
                eventIds = IntArray(0)
                bgValues = IntArray(0)
            }
            val history = HistoryData().apply {
                setGlucoseId(algorithmGlucoseId)
                setIws(window.map { it.iwNa }.toFloatArray())
                setIbs(window.map { it.ibNa }.toFloatArray())
                setTs(window.map { it.temperatureC }.toFloatArray())
                setNewBgToGlucoseIds(eventIds)
                setNewBgValues(bgValues)
                setStartTimeMillis(sensorStartTimeMs.takeIf { it > 0L } ?: sampleTimeMs)
                setK0(calibration.k)
                setR(calibration.r)
                setSensorInfo(calibration.rawQr)
                setTransmitterName(sensorIdName, calibration.voltageFlag)
                setAlgorithm(nativeAlgorithm(family, calibration.voltageFlag))
            }
            val out: CurrentGlucose? = AlgorithmTools.getInstance().algorithmGlucose(history)
            out?.let { mapCurrentNative(record, it, rawMgdl) }
        }.getOrElse { t ->
            if (t is UnsatisfiedLinkError) {
                officialHistoryMissing = true
                Log.d(TAG, "official history algorithm unavailable: ${t.message}")
            } else {
                Log.w(TAG, "official history algorithm failed: ${t.message}")
            }
            null
        }
    }

    private fun tryLegacyNative(
        record: AnytimeRawRecord,
        calibration: AnytimeQrCalibration,
        family: AnytimeConstants.FamilyEntry,
        sensorIdName: String,
        sampleTimeMs: Long,
        lastReferenceBgMgdlTimes10: Int,
        lastReferenceBgGlucoseId: Int,
        window: List<AnytimeRawRecord>,
        sensorStartTimeMs: Long,
        rawMgdl: Float,
        algorithmGlucoseId: Int = record.glucoseId,
    ): Result? {
        if (legacyAlgorithmMissing) return null
        if (window.isEmpty()) return null
        return runCatching {
            val eventIds: IntArray?
            val bgValues: IntArray?
            if (lastReferenceBgGlucoseId > 0 && lastReferenceBgMgdlTimes10 > 0) {
                eventIds = intArrayOf(lastReferenceBgGlucoseId)
                bgValues = intArrayOf(lastReferenceBgMgdlTimes10 / 10)
            } else {
                eventIds = null
                bgValues = null
            }
            val input = DataInput(
                glucoseId = algorithmGlucoseId,
                Iws = window.map { it.iwNa }.toFloatArray(),
                Ibs = window.map { it.ibNa }.toFloatArray(),
                Ts = window.map { it.temperatureC }.toFloatArray(),
                eventIds = eventIds,
                BGMGs = bgValues,
                K0 = calibration.k,
                R = calibration.r,
                startTimeMillis = sensorStartTimeMs.takeIf { it > 0L } ?: sampleTimeMs,
                transmitterName = sensorIdName,
            ).apply {
                setAlgorithm(nativeAlgorithm(family, calibration.voltageFlag))
                setWarmup_time(20)
                setLife_time(family.endNumber)
            }
            val out: DataOutput? = AlgorithmTools.getInstance().algorithm(input)
            out?.let { mapLegacyNative(record, it, rawMgdl) }
        }.getOrElse { t ->
            if (t is UnsatisfiedLinkError) {
                legacyAlgorithmMissing = true
                Log.d(TAG, "legacy native algorithm unavailable: ${t.message}")
            } else {
                Log.w(TAG, "legacy native algorithm failed: ${t.message}")
            }
            null
        }
    }

    private fun contiguousHistoryThrough(
        record: AnytimeRawRecord,
        sortedRecords: List<AnytimeRawRecord>,
    ): List<AnytimeRawRecord> {
        if (record.glucoseId < 0) return emptyList()
        val byId = sortedRecords.associateBy { it.glucoseId }
        val out = ArrayList<AnytimeRawRecord>(record.glucoseId + 1)
        for (id in 0..record.glucoseId) {
            val rec = byId[id] ?: return emptyList()
            out.add(rec)
        }
        return out
    }

    /** Linear K/R fallback. */
    @JvmStatic
    fun computeLinear(
        record: AnytimeRawRecord,
        k: Float,
        r: Float,
        family: AnytimeConstants.FamilyEntry? = null,
        voltageFlag: Int = 0,
    ): Result {
        // Effective K/R defaults if the QR wasn't scanned: empirical CT3 averages.
        val kEff = if (k > 0f) k else 0.30f
        val rEff = if (r > 0f) r else 50f
        val rawIw = normalizedRawIw(record.iwNa, family, voltageFlag)
        val rawMmol = kEff * rawIw + rEff / 100f
        val mmol = rawMmol.coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat())
        val mgdlTimes10 = (mmol * 18.0f * 10f + 0.5f).toInt()
            .coerceIn(AnytimeConstants.ALGO_MGDL_MIN_TIMES10, AnytimeConstants.ALGO_MGDL_MAX_TIMES10)
        return Result(
            glucoseId = record.glucoseId,
            mmol = mmol,
            mgdlTimes10 = mgdlTimes10,
            ibNa = record.ibNa,
            iwNa = record.iwNa,
            temperatureC = record.temperatureC,
            trend = 6, // TREND_NONE — linear path doesn't compute trend
            errorCode = 0,
            warnCode = 0,
            source = Source.LINEAR,
            rawMgdl = rawMmol * 18.0f,
        )
    }

    /** Use vendor-computed `0x0C` record directly (bypasses algorithm). */
    @JvmStatic
    fun fromComputedRecord(
        rec: AnytimeComputedRecord,
        qr: AnytimeQrCalibration? = null,
        family: AnytimeConstants.FamilyEntry? = null,
    ): Result {
        val rawLinear = computeLinear(
            AnytimeRawRecord(
                indexInPacket = 0,
                glucoseId = rec.glucoseId,
                ibNa = rec.ibNa,
                iwNa = rec.iwNa,
                temperatureC = rec.temperatureC,
                recordBytes = ByteArray(0),
            ),
            qr?.k ?: 0f,
            qr?.r ?: 0f,
            family,
            qr?.voltageFlag ?: 0,
        )
        val mgdlTimes10 = (rec.gluMgdl * 10).coerceIn(
            AnytimeConstants.ALGO_MGDL_MIN_TIMES10,
            AnytimeConstants.ALGO_MGDL_MAX_TIMES10,
        )
        return Result(
            glucoseId = rec.glucoseId,
            mmol = rec.gluMmol,
            mgdlTimes10 = mgdlTimes10,
            ibNa = rec.ibNa,
            iwNa = rec.iwNa,
            temperatureC = rec.temperatureC,
            trend = rec.trend,
            errorCode = rec.errorCode,
            warnCode = rec.warnCode,
            source = Source.NATIVE, // it's transmitter-native, even more authoritative
            rawMgdl = rawLinear.rawMgdl,
        )
    }

    /** Decode the QR string via the JNI when available; pure-Kotlin otherwise. */
    @JvmStatic
    fun decodeQr(qr: String): AnytimeQrCalibration? {
        if (isNativeAvailable) {
            runCatching {
                val data: KRDecodeData? = AlgorithmTools.getInstance().decodeCT(qr.toCharArray())
                if (data != null && data.k > 0f && data.r > 0f) {
                    return AnytimeQrCalibration(
                        rawQr = qr,
                        format = AnytimeQrCalibration.Format.B,
                        k = data.k,
                        r = data.r,
                        lifeTime = data.lifeTime.takeIf { it > 0 } ?: AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS,
                        productMonth = data.productMonth,
                        productYear = 2000 + data.year,
                        electrodeType = data.electrodeType.orEmpty(),
                        electrodeTecNo = data.electrodeTecNo.orEmpty(),
                        enzymeTecNo = data.enzymeTecNo.orEmpty(),
                        membraneTecNo = data.membraneTecNo.orEmpty(),
                        marketNo = data.marketNo.orEmpty(),
                        serialNo = data.serialNo.orEmpty(),
                        sensorNo = data.sensorNo.orEmpty(),
                        unitOrder = data.unitOrder,
                        voltageFlag = AnytimeQr.inferVoltageFlag(qr),
                        calibrationCount = data.calibration,
                    )
                } else if (data != null) {
                    Log.w(TAG, "native decodeCT returned invalid K/R: K=${data.k} R=${data.r}")
                }
            }.onFailure { t ->
                Log.w(TAG, "native decodeCT failed: ${t.message}")
            }
        }
        return AnytimeQr.parse(qr)
    }

    private fun mapCurrentNative(record: AnytimeRawRecord, native: CurrentGlucose, rawMgdl: Float): Result {
        val preferredMgdl = when {
            native.gluMG_AI > 0 -> native.gluMG_AI
            native.gluMG > 0 -> native.gluMG
            native.glu_AI > 0f -> (native.glu_AI * 18f + 0.5f).toInt()
            native.glu > 0f -> (native.glu * 18f + 0.5f).toInt()
            else -> 0
        }
        val preferredMmol = when {
            native.glu_AI > 0f -> native.glu_AI
            native.glu > 0f -> native.glu
            preferredMgdl > 0 -> preferredMgdl / 18f
            else -> 0f
        }
        return Result(
            glucoseId = record.glucoseId,
            mmol = preferredMmol,
            mgdlTimes10 = preferredMgdl * 10,
            ibNa = record.ibNa,
            iwNa = record.iwNa,
            temperatureC = record.temperatureC,
            trend = native.trend,
            errorCode = native.errorCode,
            warnCode = native.warnCode,
            source = Source.NATIVE,
            rawMgdl = rawMgdl,
            sensitivityCoefficient = native.sensitivityCoefficient,
            kBase = native.k_BASE,
            kAuto = native.k_AUTO,
            iw30Iir = native.iw30IIR,
            iw48Iir = native.iw48IIR,
            beVoltageMv = native.beVoltage,
            weVoltageMv = native.weVoltage,
            reVoltageMv = native.reVoltage,
            ceVoltageMv = native.ceVoltage,
            bVoltageMv = native.bVoltage,
        )
    }

    private fun mapLegacyNative(record: AnytimeRawRecord, native: DataOutput, rawMgdl: Float): Result {
        val mgdl = native.GLU_MG
        return Result(
            glucoseId = record.glucoseId,
            mmol = mgdl / 18f,
            mgdlTimes10 = mgdl * 10,
            ibNa = record.ibNa,
            iwNa = record.iwNa,
            temperatureC = record.temperatureC,
            trend = native.trend,
            errorCode = native.errorCode,
            warnCode = native.warnCode,
            source = Source.NATIVE,
            rawMgdl = rawMgdl,
        )
    }

    private fun normalizedRawIw(
        iwNa: Float,
        family: AnytimeConstants.FamilyEntry?,
        voltageFlag: Int,
    ): Float {
        if (iwNa <= 0f || !iwNa.isFinite()) return iwNa
        return if (family?.family == AnytimeConstants.Family.CT4 && voltageFlag == 1) {
            iwNa / 2f
        } else {
            iwNa
        }
    }

    private fun nativeAlgorithm(family: AnytimeConstants.FamilyEntry, voltageFlag: Int): Int {
        val switchable = when (family.family) {
            AnytimeConstants.Family.CT3,
            AnytimeConstants.Family.CT3_PLUS,
            AnytimeConstants.Family.CT3_YUWELL,
            AnytimeConstants.Family.CT3_ULTRASONIC,
            AnytimeConstants.Family.CT4 -> true
            else -> false
        }
        if (!switchable) return family.algorithm
        return when {
            family.algorithm == 10 && voltageFlag == 0 -> 3
            family.algorithm == 3 && voltageFlag == 1 -> 10
            else -> family.algorithm
        }
    }

    private fun isNativeResultUsable(result: Result): Boolean {
        if (result.errorCode != 0) return false
        if (result.mgdlTimes10 !in AnytimeConstants.ALGO_MGDL_MIN_TIMES10..AnytimeConstants.ALGO_MGDL_MAX_TIMES10) return false
        if (result.mmol <= 0f || result.mmol.isNaN()) return false
        val mgdl = result.mgdl
        val fromMmol = result.mmol * 18f
        val tolerance = maxOf(20f, mgdl * 0.35f)
        return kotlin.math.abs(fromMmol - mgdl) <= tolerance
    }
}
