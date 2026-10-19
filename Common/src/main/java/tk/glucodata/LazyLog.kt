package tk.glucodata

/**
 * Lazy log helpers for hot paths.
 *
 * Kotlin evaluates arguments eagerly, so `Log.d(TAG, "value=$x")` builds and allocates the
 * message string on every call — even when [Log.doLog] is false and the native layer throws it
 * straight away. On per-packet BLE callbacks that is thousands of wasted strings an hour.
 *
 * These take the message as a lambda and are `inline`, so with logging off the call collapses
 * to a volatile read and the string is never built. Behaviour with logging on is identical to
 * calling [Log] directly.
 *
 * Use these only where the call fires per BLE event. Setup, teardown and error paths should keep
 * calling [Log] directly — they are not hot, and unconditional failure logs are worth more there.
 */

inline fun logd(tag: String, message: () -> String) {
    if (Log.doLog) Log.d(tag, message())
}

inline fun logi(tag: String, message: () -> String) {
    if (Log.doLog) Log.i(tag, message())
}

inline fun logv(tag: String, message: () -> String) {
    if (Log.doLog) Log.v(tag, message())
}

inline fun logw(tag: String, message: () -> String) {
    if (Log.doLog) Log.w(tag, message())
}
