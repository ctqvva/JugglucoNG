package tk.glucodata.drivers.sibionics

/**
 * The sensor type is chosen by the user in setup and is never revised afterwards. It decides which
 * registration key authenticates the sensor, so it also decides which key group leads.
 *
 * Nothing the driver observes at runtime is evidence about the type. The V120 accept response is a
 * bare 0x01 decrypted with the fixed master key, so it reads back identically whichever
 * registration key was actually sent — a sensor answers it even when the key was wrong, and simply
 * never streams, dropping the link about 30 s later. The AA55 framing says which protocol the
 * firmware speaks, not which product line the sensor belongs to. Both were once allowed to rewrite
 * the recorded type, and both got it wrong in the field: a Sibionics 2 that had its battery pulled
 * came back as "Sibionics EU" with a 14-day official end and no 22-day auto-reset, flipping type on
 * every reconnect.
 *
 * Treating that ACK as a *hint* was just as damaging: one bad credit persisted "lead with the EU
 * key" for a Sibionics 2, every later connection authenticated with the wrong key, got its
 * meaningless ACK, and streamed nothing — a sensor that showed as connected for hours without a
 * single reading.
 *
 * So the type is an input, not a conclusion: what this sensor is comes from setup, only setup can
 * change it, and the key group its type names is always tried first.
 *
 * A sensor declared as the wrong type stays wrong until the user re-runs setup for it. That is the
 * deliberate trade: a mis-declared sensor is a setup mistake the user can see and correct, whereas
 * a type that rewrites itself corrupts the sample journal (samples are tagged with the variant),
 * the lifetime and the reset window behind the user's back.
 */
internal object SibionicsVariantLock {
    /** Fallback order used once the locked variant's own key group has had its turn. */
    private val DEFAULT_ORDER = listOf(
        SibionicsConstants.Variant.EU,
        SibionicsConstants.Variant.HEMATONIX,
        SibionicsConstants.Variant.SIBIONICS2,
        SibionicsConstants.Variant.GS3,
    )

    /**
     * The variant a sensor runs as. The setup record is the only authority; [cachedVariant] is the
     * per-sensor copy kept for records that no longer exist, and it loses every disagreement.
     */
    fun lockedVariant(
        recordVariant: SibionicsConstants.Variant?,
        cachedVariant: SibionicsConstants.Variant?,
    ): SibionicsConstants.Variant =
        recordVariant ?: cachedVariant ?: SibionicsConstants.Variant.EU

    /**
     * The variant a registry write must use. Re-running setup is an explicit choice and may change
     * the type; every other write path — address binding, legacy migration, a driver refreshing its
     * own record — inherits what is already recorded.
     */
    fun variantForWrite(
        existingVariant: SibionicsConstants.Variant?,
        requestedVariant: SibionicsConstants.Variant,
        isUserChoice: Boolean,
    ): SibionicsConstants.Variant =
        if (isUserChoice || existingVariant == null) requestedVariant else existingVariant

    /**
     * Key groups to try, in order, for one connection. The locked variant's own key always leads —
     * it is the only key the sensor is known to want — and the rest exist solely so an auth that
     * draws no response at all can still fall through. Distinct by the material that actually
     * decides authentication, so variants sharing a key are never tried twice.
     */
    fun keyOrder(lockedVariant: SibionicsConstants.Variant): List<SibionicsConstants.Variant> =
        (listOf(lockedVariant) + DEFAULT_ORDER)
            .distinctBy { it.appId + it.registrationKeyHex }
}
