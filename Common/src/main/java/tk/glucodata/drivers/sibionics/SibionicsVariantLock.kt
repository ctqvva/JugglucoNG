package tk.glucodata.drivers.sibionics

/**
 * The sensor type is chosen by the user in setup and is never revised afterwards.
 *
 * Everything the driver can observe at runtime is weak evidence. The V120 accept response is a
 * bare ACK that says nothing about which key unlocked the sensor, so crediting it is positional
 * guesswork; the AA55 framing says which protocol the firmware speaks, not which product line the
 * sensor belongs to. Both were previously allowed to rewrite the recorded type, and both got it
 * wrong in the field — a Sibionics 2 that had its battery pulled came back as "Sibionics EU",
 * taking the 14-day official end, the missing 22-day auto-reset and the wrong maintenance reset
 * packet with it, and flipping back and forth as the sensor reconnected.
 *
 * So the type is now an input, not a conclusion:
 *  - *what this sensor is* comes from setup and only setup can change it;
 *  - *which key to try first* is a cheap ordering hint, updated on every accepted auth, and it
 *    carries no meaning beyond saving one auth timeout on the next connection.
 *
 * A sensor declared as the wrong type stays wrong until the user re-runs setup for it. That is the
 * deliberate trade: a mis-declared sensor is a setup mistake the user can see and correct, whereas
 * a type that rewrites itself corrupts the sample journal (samples are tagged with the variant),
 * the lifetime and the reset window behind the user's back.
 */
internal object SibionicsVariantLock {
    /** Fallback order used once the hint and the locked variant have had their turn. */
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
     * Key groups to try, in order, for one connection. Distinct by the material that actually
     * decides authentication, so variants sharing a key are never tried twice. Ordering only: no
     * entry here can become the sensor's identity.
     */
    fun keyOrder(
        lockedVariant: SibionicsConstants.Variant,
        keyHint: SibionicsConstants.Variant?,
    ): List<SibionicsConstants.Variant> =
        (listOfNotNull(keyHint, lockedVariant) + DEFAULT_ORDER)
            .distinctBy { it.appId + it.registrationKeyHex }
}
