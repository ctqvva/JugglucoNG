package tk.glucodata

/**
 * The phone↔watch protocol version (direction.md §6 Q2).
 *
 * The payloads a managed message carries are versioned per payload already ([WearJournalSync],
 * [WearCalibrationPayload]); the display-preferences message is the first to carry the protocol
 * version itself, in a leading `v:<n>` line. It is deliberately a line the old parser skips (no
 * `=`), so a new phone can still talk to an old watch: the rest of the payload arrives unchanged.
 *
 * A receiver that meets a version newer than [VERSION] ignores the whole payload and keeps its
 * current state, rather than applying a shape it does not understand. That is the visible half of
 * "the peer can tell it is talking to a mismatched build"; a peer that never connects at all is a
 * separate diagnostic.
 */
object WearProtocol {
    /** Bumped whenever the meaning of a managed message changes. */
    const val VERSION = 1

    private const val VERSION_PREFIX = "v:"

    fun versionLine(): String = "$VERSION_PREFIX$VERSION"

    /**
     * The version the payload declares, or null when it declares none — a payload from a build
     * before this line existed, i.e. version 1. Only the first non-blank line is inspected.
     */
    fun declaredVersion(payload: String): Int? {
        val first = payload.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        if (!first.startsWith(VERSION_PREFIX)) return null
        return first.removePrefix(VERSION_PREFIX).trim().toIntOrNull()
    }

    /** A payload with no declared version is legacy (1); one from the future is not applied. */
    fun accepts(declaredVersion: Int?): Boolean =
        declaredVersion == null || declaredVersion <= VERSION

    // ---- the other version carriers ------------------------------------------------------------------
    //
    // The `v:` line above is not the only version in the protocol, and the others do not
    // agree with it. Worth knowing before changing any of them:
    //
    // | family | carrier | policy |
    // |---|---|---|
    // | text payloads (display prefs, toggles, glucose colours) | `v:<n>` line | legacy and older accepted, newer refused |
    // | sensor ownership (`/sync2/own`) | private version byte in [SensorOwnershipRuntime] | that one value only |
    // | sensor handoff (`/sensorhandoff`) | `version` key in the JSON | that one value only; no key reads as 0 |
    //
    // So the text payloads degrade, and the two binary ones break: a version bump in
    // either direction makes the pair drop messages with nothing on screen. Relaxing the
    // binary policies means letting a build that does not know a field apply a payload
    // that carries one, which is a behaviour change on which device reads a sensor --
    // a maintainer decision (plan §8.3), not a refactor. The behaviour as it stands is
    // pinned in WearPayloadVersionPolicyTests.

    // ---- the handshake ------------------------------------------------------------------------

    @Volatile
    private var peerVersion: Int? = null

    /**
     * The version the peer last advertised, or null before one has been heard. Used to tell a
     * mismatched build apart from a silent one (direction.md §6 Q2, lesson #1).
     */
    fun peerVersion(): Int? = peerVersion

    /** True until a peer version is known, and when it matches this build's. */
    fun peerMatches(): Boolean = peerVersion == null || peerVersion == VERSION

    /**
     * Records a received handshake. Returns the parsed version, or null for a payload that
     * carries none. A version that differs from [VERSION] is logged once so the mismatch is
     * visible instead of the peers silently disagreeing about message shapes.
     */
    fun onPeerVersionReport(data: ByteArray?): Int? {
        val text = data?.toString(Charsets.UTF_8) ?: return null
        val version = declaredVersion(text) ?: return null
        peerVersion = version
        if (version != VERSION) {
            Log.w("WearProtocol", "peer speaks protocol v$version, this build speaks v$VERSION")
        }
        return version
    }

    /** Test seam: forget the peer so a case starts from "unknown". */
    internal fun resetForTest() {
        peerVersion = null
    }
}
