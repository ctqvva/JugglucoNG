// AnytimeAlgorithm.kt — Glucose computation: vendor JNI + linear fallback.
//
// Two paths:
//
//  1. NATIVE: Loads `libalgorithm-jni.so` if it has been dropped into
//     src/main/jniLibs/{abi}. The library exports four JNI calls under
//     `ist.com.sdk.AlgorithmTools`. Vendor accuracy, all chemistry corrections.
//
//  2. LINEAR: Pure-Kotlin K/R linear `mmol = K × Iw + R` with a clamp matching
//     the official UI. ~80% accuracy, drifts at extremes. Used when the .so
//     is missing or rejects the device.
//
// This module hides the choice from callers — `compute()` always returns a
// `Result`. We expose `isNativeAvailable` so UI can show "vendor algorithm" vs
// "linear fallback" badges.

package tk.glucodata.drivers.anytime

import ist.com.sdk.AlgorithmTools
import ist.com.sdk.CurrentGlucose
import ist.com.sdk.HistoryData
import ist.com.sdk.KRDecodeData
import ist.com.sdk.LatestData
import java.util.Calendar
import tk.glucodata.Log

object AnytimeAlgorithm {

    private const val TAG = AnytimeConstants.TAG

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

    /** Algorithm output. Mirrors the rich `CurrentGlucose` for native callers. */
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
    ): Result {
        val k = qr?.k ?: 0f
        val r = qr?.r ?: 0f
        val linear = computeLinear(record, k, r)
        val nativeQrReady = qr != null && k > 0f && r > 0f && hasDecodedQrChemistry(qr)
        if (isNativeAvailable && nativeQrReady) {
            runCatching {
                val cal = Calendar.getInstance().apply { timeInMillis = sampleTimeMs }
                val latest = LatestData().apply {
                    setIw(record.iwNa)
                    setIb(record.ibNa)
                    setT(record.temperatureC)
                    setK0(k)
                    setR(r)
                    setGlucoseId(record.glucoseId)
                    setYear(cal.get(Calendar.YEAR))
                    setMonth(cal.get(Calendar.MONTH) + 1)
                    setDay(cal.get(Calendar.DAY_OF_MONTH))
                    setHour(cal.get(Calendar.HOUR_OF_DAY))
                    setMinute(cal.get(Calendar.MINUTE))
                    setName(sensorIdName)
                    setSensorInfo(qr.rawQr)
                    setAlgorithm(family.algorithm)
                    setLifeTime(qr.lifeTime)
                    setProductMonth(qr.productMonth)
                    setBatch(qr.marketNo)
                    setEnzymeActivity(0f)
                    setMembraneLayers(0f)
                    setLenIw(0f)
                    setLeft(0f)
                    setRight(0f)
                    setUserType(0)
                    setAge(40)
                    setGender(0)
                    setHeight(170)
                    setSickDuration(0f)
                    setEndCount(family.endNumber)
                    setInitCount(20)
                    setNewBgToGlucoseId(lastReferenceBgGlucoseId)
                    setNewBgValue(lastReferenceBgMgdlTimes10)
                }
                val out: CurrentGlucose? = AlgorithmTools.getInstance().algorithmLatestGlucose(latest)
                if (out != null) {
                    val mapped = mapNative(record, out, linear.mgdl)
                    if (isNativeResultUsable(mapped)) {
                        return mapped
                    }
                    Log.w(
                        TAG,
                        "native algorithm returned invalid result: id=${mapped.glucoseId} " +
                                "mmol=${mapped.mmol} mgdl=${mapped.mgdl} trend=${mapped.trend} " +
                                "err=${mapped.errorCode}; using linear fallback"
                    )
                }
            }.onFailure { t ->
                Log.w(TAG, "native algorithm failed: ${t.message}")
            }
        } else if (isNativeAvailable && qr != null) {
            Log.w(
                TAG,
                "native algorithm skipped: QR is not decoded enough for vendor algorithm " +
                        "(K=$k R=$r chemistry=${hasDecodedQrChemistry(qr)}); using linear fallback"
            )
        }
        return linear
    }

    /**
     * The vendor algorithm needs more than just any K/R pair: chemistry/batch metadata
     * from decodeCT/QR parsing is required. A synthesised fallback QR has plausible K/R
     * but empty chemistry fields; feeding that into native produces false positives such
     * as a long run of 50 mg/dL / 2.78 mmol/L clamp values.
     */
    private fun hasDecodedQrChemistry(qr: AnytimeQrCalibration): Boolean =
        qr.electrodeType.isNotBlank() ||
                qr.electrodeTecNo.isNotBlank() ||
                qr.enzymeTecNo.isNotBlank() ||
                qr.membraneTecNo.isNotBlank() ||
                qr.marketNo.isNotBlank() ||
                qr.serialNo.isNotBlank() ||
                qr.productMonth > 0 ||
                qr.productYear > 2000

    /** Linear K/R fallback. */
    @JvmStatic
    fun computeLinear(record: AnytimeRawRecord, k: Float, r: Float): Result {
        // Effective K/R defaults if the QR wasn't scanned: empirical CT3 averages.
        val kEff = if (k > 0f) k else 0.30f
        val rEff = if (r > 0f) r else 50f
        val mmol = (kEff * record.iwNa + rEff / 100f).coerceAtLeast(AnytimeConstants.ALGO_MMOL_FLOOR.toFloat())
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
            rawMgdl = mgdlTimes10 / 10f,
        )
    }

    /** Use vendor-computed `0x0C` record directly (bypasses algorithm). */
    @JvmStatic
    fun fromComputedRecord(rec: AnytimeComputedRecord): Result {
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
                        voltageFlag = qr.firstOrNull()?.digitToIntOrNull() ?: 0,
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

    private fun mapNative(record: AnytimeRawRecord, native: CurrentGlucose, rawMgdl: Float): Result {
        val rawNativeMmol = native.glu
        val mgdlTimes10 = native.gluMG.takeIf { it > 0 }
            ?: ((normaliseNativeMmol(rawNativeMmol, 0) * 18f * 10f + 0.5f).toInt())
        val mmol = normaliseNativeMmol(rawNativeMmol, mgdlTimes10)
        return Result(
            glucoseId = native.glucoseId.takeIf { it > 0 } ?: record.glucoseId,
            mmol = mmol,
            mgdlTimes10 = mgdlTimes10,
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

    private fun normaliseNativeMmol(rawMmol: Float, mgdlTimes10: Int): Float {
        if (rawMmol <= 0f) return rawMmol
        val mgdl = mgdlTimes10 / 10f
        if (mgdl <= 0f) return if (rawMmol > 25f) rawMmol / 10f else rawMmol
        val directDiff = kotlin.math.abs(rawMmol * 18f - mgdl)
        val scaledMmol = rawMmol / 10f
        val scaledDiff = kotlin.math.abs(scaledMmol * 18f - mgdl)
        return if (rawMmol > 20f && scaledDiff < directDiff) scaledMmol else rawMmol
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
