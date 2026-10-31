package tk.glucodata.share.ican

/**
 * The build-time values of the SinoCare "iCan Health" cloud channel (RU tenant).
 *
 * The vendor rejects a request whose shape differs from the official app's, so every value here
 * is a pinned copy of what the official RU app v01.16.01.02 sends — the host, the header set, the
 * app version it identifies itself with and the RSA public key it encrypts the AES session key
 * with. They are recovered in
 * `docs/superpowers/specs/2026-10-31-ican-health-cloud-design.md` §6.1–§6.4.
 *
 * The RSA key is public material (it only opens an envelope, it grants nothing) and is the same
 * key the official app carries in its MMKV store.
 */
object ICanHealthCloudConstants {

    /** The RU tenant host. The CE tenant (`sg.icancgm.com`) is not wired in this stage. */
    const val BASE_URL = "https://rus.icancgm.com"

    /** The path prefix every app-host endpoint sits behind. */
    const val APP_HOST_FIRST_PATH = "/ican/api/"

    /**
     * The `authorization` header value: the vendor app's own Basic credential pair, base64
     * encoded. It identifies the client to the token endpoint and is not the patient's secret.
     */
    const val AUTHORIZATION_BASIC =
        "Basic aWNhbi1oZWFsdGgtYW5kcm9pZDplOTFhOGM5YzViNGY0MGZhYTExMjYzZTdiYjdjYTBhNw=="

    /** The app version the request announces itself with, as the official app sends it. */
    const val VERSION_CODE = "30"
    const val VERSION_NAME = "01.16.01.02"

    /** The client id paired with [AUTHORIZATION_BASIC]. */
    const val CLIENT_ID = "ican-health-android"

    /** `platformType` — 1 marks the Android app. */
    const val PLATFORM_TYPE = "1"

    /** The tenant's country and language, as the official app sends them. */
    const val COUNTRY = "RUS"
    const val LANG_CODE = "ru"

    /** The `timeZone` header: the UTC offset in hours. */
    const val TIME_ZONE = "3"

    /** The alphabet the vendor draws the per-request AES key from. */
    const val GET_RANDOM_STRING_ALPHABET =
        "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    /**
     * The RSA public key that encrypts the per-request AES key (RSA-OAEP-SHA1).
     *
     * Verbatim from the official app's MMKV store, so the server can decrypt the key the client
     * sends. It is a single unwrapped base64 line: the line breaks here are the PEM framing, and
     * the body itself carries none.
     */
    const val RSA_PUBLIC_KEY =
        "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAimdiS7wHMpmFCw0j6Gc07TRchln/pp4m8kAe" +
            "DuI8HvRdDB0ul/ic8fJue7Ow8YTqCEPt2IKZZtTfBGsfQgQ/pFdlQrG31lgErRBVitOZxpbxB6t2O2pH0" +
            "WguI2sCYOKoOefPLjiTovMrbWQatZrTd3MeNELD7zY1WY8NLkQ5Ur+Je8q6XO+cGS1lbpfxCQvdLobhe" +
            "wy+9aRXsLb3Bcs0FQ4RdWweWRt0gj9akTvVlaTjgGq5/pTE3crfQXFxi8L9NPu8LKguYtLDuE/ffYSd" +
            "uWdowPIgJk/8w7RxAiwKARFGNSDpVCqsrFO9poQ56ZJqOxHg1gLGnJCcdj2uvbQp8QIDAQAB\n" +
            "-----END PUBLIC KEY-----"
}
