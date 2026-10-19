// AlgorithmTools.kt — JNI bridge to libalgorithm-jni.so.
//
// CRITICAL: This package and class name must match the symbols exported from
// the vendor library exactly:
//   Java_ist_com_sdk_AlgorithmTools_algorithmLatestGlucose
//   Java_ist_com_sdk_AlgorithmTools_algorithmGlucose
//   Java_ist_com_sdk_AlgorithmTools_algorithm
//   Java_ist_com_sdk_AlgorithmTools_decodeCT
//   Java_ist_com_sdk_AlgorithmTools_getVersion
//
// `System.loadLibrary("algorithm-jni")` looks up libalgorithm-jni.so under
// jniLibs/{abi}/. Keep arm64-v8a and armeabi-v7a on the same vendor SDK family:
// arm64 devices otherwise silently miss the official latest/history calibration
// entry points and fall back to the weaker legacy DataInput path.

package ist.com.sdk

class AlgorithmTools private constructor() {

    external fun algorithm(input: DataInput): DataOutput?

    external fun algorithmGlucose(history: HistoryData): CurrentGlucose?

    external fun algorithmLatestGlucose(latest: LatestData): CurrentGlucose?

    external fun decodeCT(qr: CharArray): KRDecodeData?

    external fun getVersion(): SDKVersion?

    companion object {
        private val INSTANCE = AlgorithmTools()

        @JvmStatic
        fun getInstance(): AlgorithmTools = INSTANCE

        init {
            System.loadLibrary("algorithm-jni")
        }
    }
}
