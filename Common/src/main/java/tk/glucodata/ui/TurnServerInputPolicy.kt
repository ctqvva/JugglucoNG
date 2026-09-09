package tk.glucodata.ui

/** Limits of the native turnserver_t buffers, excluding their trailing NUL. */
internal object TurnServerInputPolicy {
    const val HOST_BYTES = 191
    const val USERNAME_BYTES = 95
    const val PASSWORD_BYTES = 127

    // JNI GetStringUTFChars uses modified UTF-8: NUL takes two bytes and each
    // UTF-16 surrogate takes three. String.length and ordinary UTF-8 differ.
    fun fitsNativeBuffer(value: String, maxBytes: Int): Boolean {
        var bytes = 0
        for (char in value) {
            bytes += when (char.code) {
                in 1..0x7f -> 1
                in 0..0x7ff -> 2
                else -> 3
            }
            if (bytes > maxBytes) return false
        }
        return true
    }
}
