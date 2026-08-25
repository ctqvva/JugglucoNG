package tk.glucodata.data.journal

/**
 * Versioned pharmacodynamic activity definitions for journal insulin presets.
 *
 * A source-backed curve represents glucose-lowering activity (GIR), never
 * plasma insulin concentration. See docs/insulin-curve-evidence.md for the
 * source figure, study dose, population and extraction limits for each model.
 */
object JournalInsulinCurveCatalogue {
    const val MODEL_VERSION = 1

    enum class DoseAxis {
        FIXED,
        UNITS,
        UNITS_PER_KG
    }

    data class Variant(
        val dose: Float,
        val points: List<JournalCurvePoint>
    )

    data class Definition(
        val profile: JournalBuiltInCurveProfile,
        val evidence: JournalCurveEvidence,
        val doseAxis: DoseAxis,
        val variants: List<Variant>,
        val referenceDose: Float
    ) {
        val profileId: String get() = profile.storageValue
    }

    fun definition(profile: JournalBuiltInCurveProfile): Definition {
        val (evidence, axis, variants, referenceDose) = when (profile) {
            JournalBuiltInCurveProfile.FIASP -> DefinitionParts(
                JournalCurveEvidence.SOURCE_SINGLE_DOSE,
                DoseAxis.UNITS_PER_KG,
                fiaspVariants(),
                0.1f
            )
            JournalBuiltInCurveProfile.URLI -> DefinitionParts(
                JournalCurveEvidence.SOURCE_SINGLE_DOSE,
                DoseAxis.UNITS,
                lyumjevVariants(),
                7f
            )
            JournalBuiltInCurveProfile.AFREZZA -> DefinitionParts(
                JournalCurveEvidence.SOURCE_SINGLE_DOSE,
                DoseAxis.UNITS,
                afrezzaVariants(),
                12f
            )
            JournalBuiltInCurveProfile.HUMAN_REGULAR -> singleDose(
                JournalCurveEvidence.SOURCE_SINGLE_DOSE,
                DoseAxis.UNITS_PER_KG,
                0.3f,
                regularU100Curve()
            )
            JournalBuiltInCurveProfile.HUMAN_REGULAR_U500 -> singleDose(
                JournalCurveEvidence.SOURCE_SINGLE_DOSE,
                DoseAxis.UNITS,
                100f,
                regularU500Curve()
            )
            JournalBuiltInCurveProfile.ASPART_MIX_70_30 -> singleDose(
                JournalCurveEvidence.SOURCE_SINGLE_DOSE,
                DoseAxis.UNITS_PER_KG,
                0.3f,
                aspartMix7030Curve()
            )
            JournalBuiltInCurveProfile.GLARGINE_U100 -> fixed(
                JournalCurveEvidence.SOURCE_REFERENCE,
                0.3f,
                glargineU100Curve()
            )
            JournalBuiltInCurveProfile.GLARGINE_U300 -> fixed(
                JournalCurveEvidence.SOURCE_STEADY_STATE,
                0.4f,
                glargineU300Curve()
            )
            JournalBuiltInCurveProfile.DETEMIR -> DefinitionParts(
                JournalCurveEvidence.SOURCE_REFERENCE,
                DoseAxis.UNITS_PER_KG,
                detemirVariants(),
                0.4f
            )
            JournalBuiltInCurveProfile.DEGLUDEC -> fixed(
                JournalCurveEvidence.SOURCE_STEADY_STATE,
                0.4f,
                degludecSteadyStateCurve()
            )
            JournalBuiltInCurveProfile.ICODEC -> fixed(
                JournalCurveEvidence.SOURCE_STEADY_STATE,
                1f,
                icodecSteadyStateCurve()
            )
            JournalBuiltInCurveProfile.RYZODEG_70_30 -> fixed(
                JournalCurveEvidence.SOURCE_REFERENCE,
                0.8f,
                ryzodegCurve()
            )
            JournalBuiltInCurveProfile.NPH -> fixed(
                JournalCurveEvidence.SOURCE_REFERENCE,
                0.4f,
                nphCurve()
            )
            JournalBuiltInCurveProfile.LISPRO_MIX_50_50 -> fixed(
                JournalCurveEvidence.SOURCE_REFERENCE,
                0.3f,
                lisproMix5050Reference()
            )
            JournalBuiltInCurveProfile.LISPRO_MIX_75_25 -> fixed(
                JournalCurveEvidence.SOURCE_REFERENCE,
                0.3f,
                lisproMix7525Reference()
            )
            JournalBuiltInCurveProfile.HUMAN_MIX_70_30 -> fixed(
                JournalCurveEvidence.SOURCE_REFERENCE,
                0.3f,
                humanMix7030Reference()
            )
            else -> fixed(
                JournalCurveEvidence.UNVERIFIED,
                1f,
                legacyGeneratedJournalCurve(profile)
            )
        }
        return Definition(profile, evidence, axis, variants, referenceDose)
    }

    fun referenceCurve(profile: JournalBuiltInCurveProfile): List<JournalCurvePoint> {
        val definition = definition(profile)
        return definition.variants.minByOrNull { kotlin.math.abs(it.dose - definition.referenceDose) }
            ?.points
            ?: emptyList()
    }

    fun resolve(
        profile: JournalBuiltInCurveProfile,
        amountUnits: Float,
        bodyWeightKg: Float?
    ): JournalResolvedCurve {
        val definition = definition(profile)
        val validWeight = bodyWeightKg?.takeIf { it.isFinite() && it in 10f..400f }
        val requestedDose = when (definition.doseAxis) {
            DoseAxis.FIXED -> definition.referenceDose
            DoseAxis.UNITS -> amountUnits
            DoseAxis.UNITS_PER_KG -> validWeight?.let { amountUnits / it }
        }
        val approximated = when {
            definition.evidence != JournalCurveEvidence.SOURCE_SINGLE_DOSE -> true
            requestedDose == null -> true
            definition.doseAxis == DoseAxis.FIXED -> false
            requestedDose < definition.variants.first().dose -> true
            requestedDose > definition.variants.last().dose -> true
            else -> definition.variants.none { kotlin.math.abs(it.dose - requestedDose) < 0.0001f }
        }
        val points = if (requestedDose == null) {
            referenceCurve(profile)
        } else {
            interpolateVariants(definition.variants, requestedDose)
        }
        return JournalResolvedCurve(
            points = points,
            profileId = definition.profileId,
            modelVersion = MODEL_VERSION,
            evidence = definition.evidence,
            usedBodyWeightKg = if (definition.doseAxis == DoseAxis.UNITS_PER_KG) validWeight else null,
            approximated = approximated
        )
    }

    private data class DefinitionParts(
        val evidence: JournalCurveEvidence,
        val axis: DoseAxis,
        val variants: List<Variant>,
        val referenceDose: Float
    )

    private fun fixed(
        evidence: JournalCurveEvidence,
        referenceDose: Float,
        points: List<JournalCurvePoint>
    ) = DefinitionParts(evidence, DoseAxis.FIXED, listOf(Variant(referenceDose, points)), referenceDose)

    private fun singleDose(
        evidence: JournalCurveEvidence,
        axis: DoseAxis,
        dose: Float,
        points: List<JournalCurvePoint>
    ) = DefinitionParts(evidence, axis, listOf(Variant(dose, points)), dose)

    private fun interpolateVariants(variants: List<Variant>, requestedDose: Float): List<JournalCurvePoint> {
        if (variants.isEmpty()) return emptyList()
        val sorted = variants.sortedBy { it.dose }
        if (requestedDose <= sorted.first().dose) return sorted.first().points
        if (requestedDose >= sorted.last().dose) return sorted.last().points
        val upperIndex = sorted.indexOfFirst { it.dose >= requestedDose }.coerceAtLeast(1)
        val lower = sorted[upperIndex - 1]
        val upper = sorted[upperIndex]
        val fraction = ((requestedDose - lower.dose) / (upper.dose - lower.dose)).coerceIn(0f, 1f)
        val minutes = (lower.points.map { it.minute } + upper.points.map { it.minute }).distinct().sorted()
        val interpolated = minutes.map { minute ->
            val lowerActivity = interpolateJournalCurve(lower.points, minute.toFloat())
            val upperActivity = interpolateJournalCurve(upper.points, minute.toFloat())
            JournalCurvePoint(minute, lowerActivity + ((upperActivity - lowerActivity) * fraction))
        }
        val maxActivity = interpolated.maxOfOrNull { it.activity }?.coerceAtLeast(0.0001f) ?: 1f
        return normalizeJournalCurvePoints(
            interpolated.map { it.copy(activity = (it.activity / maxActivity).coerceIn(0f, 1f)) }
        )
    }

    private fun curve(
        vararg points: Pair<Int, Float>,
        forceZeroEndpoints: Boolean = true
    ): List<JournalCurvePoint> {
        val normalizedEndpoints = normalizeJournalCurvePoints(
            points.map { JournalCurvePoint(it.first, it.second) },
            forceZeroEndpoints = forceZeroEndpoints
        )
        val peak = normalizedEndpoints.maxOfOrNull { it.activity }?.coerceAtLeast(0.0001f) ?: 1f
        return normalizedEndpoints.map { point ->
            point.copy(activity = (point.activity / peak).coerceIn(0f, 1f))
        }
    }

    private fun fiaspVariants() = listOf(
        Variant(0.1f, curve(
            0 to 0f, 20 to 0f, 30 to 0.34f, 60 to 0.79f, 90 to 0.96f,
            120 to 0.93f, 150 to 0.78f, 180 to 0.55f, 210 to 0.28f,
            240 to 0.18f, 270 to 0.09f, 300 to 0.02f, 360 to 0f
        )),
        Variant(0.2f, curve(
            0 to 0f, 17 to 0f, 30 to 0.35f, 60 to 0.66f, 90 to 0.94f,
            122 to 1f, 150 to 0.93f, 180 to 0.74f, 210 to 0.54f,
            240 to 0.36f, 270 to 0.22f, 300 to 0.14f, 330 to 0.08f,
            360 to 0.04f, 420 to 0f
        )),
        Variant(0.4f, curve(
            0 to 0f, 16 to 0f, 30 to 0.35f, 60 to 0.70f, 90 to 0.94f,
            133 to 1f, 180 to 0.91f, 210 to 0.82f, 240 to 0.64f,
            270 to 0.51f, 300 to 0.35f, 330 to 0.25f, 360 to 0.16f,
            390 to 0.08f, 420 to 0f
        ))
    )

    private fun lyumjevVariants() = listOf(
        Variant(7f, curve(
            0 to 0f, 17 to 0f, 30 to 0.32f, 60 to 0.70f, 90 to 0.91f,
            120 to 1f, 150 to 0.89f, 180 to 0.66f, 210 to 0.42f,
            240 to 0.20f, 276 to 0f
        )),
        Variant(15f, curve(
            0 to 0f, 17 to 0f, 30 to 0.30f, 60 to 0.64f, 90 to 0.84f,
            120 to 0.97f, 138 to 1f, 180 to 0.88f, 240 to 0.59f,
            300 to 0.31f, 360 to 0.08f, 372 to 0f
        )),
        Variant(30f, curve(
            0 to 0f, 15 to 0f, 30 to 0.28f, 60 to 0.57f, 90 to 0.75f,
            120 to 0.88f, 150 to 0.96f, 174 to 1f, 240 to 0.85f,
            300 to 0.61f, 360 to 0.36f, 420 to 0.08f, 438 to 0f
        ))
    )

    private fun afrezzaVariants() = listOf(
        Variant(4f, curve(0 to 0f, 12 to 0f, 20 to 0.55f, 35 to 1f, 55 to 0.55f, 75 to 0.18f, 90 to 0f)),
        Variant(12f, curve(0 to 0f, 12 to 0f, 25 to 0.55f, 45 to 1f, 75 to 0.62f, 120 to 0.27f, 165 to 0.06f, 180 to 0f)),
        Variant(48f, curve(0 to 0f, 12 to 0f, 30 to 0.53f, 55 to 1f, 90 to 0.78f, 150 to 0.48f, 210 to 0.20f, 255 to 0.05f, 270 to 0f))
    )

    private fun regularU100Curve() = curve(
        0 to 0f, 15 to 0.02f, 30 to 0.14f, 60 to 0.49f, 120 to 0.86f,
        180 to 1f, 240 to 0.94f, 300 to 0.76f, 360 to 0.50f,
        420 to 0.31f, 480 to 0.18f, 540 to 0.09f, 600 to 0.04f,
        720 to 0.02f, 840 to 0f
    )

    private fun regularU500Curve() = curve(
        0 to 0f, 30 to 0.18f, 60 to 0.38f, 120 to 0.56f, 240 to 0.83f,
        360 to 1f, 480 to 0.88f, 600 to 0.78f, 720 to 0.66f,
        840 to 0.48f, 960 to 0.34f, 1080 to 0.24f, 1200 to 0.12f,
        1320 to 0.04f, 1440 to 0f
    )

    private fun nphCurve() = curve(
        0 to 0f, 60 to 0.05f, 120 to 0.28f, 180 to 0.64f,
        240 to 0.75f, 300 to 0.95f, 360 to 1f, 420 to 0.92f,
        480 to 0.96f, 600 to 0.94f, 720 to 0.82f, 840 to 0.68f,
        960 to 0.55f, 1080 to 0.43f, 1200 to 0.35f, 1320 to 0.29f,
        forceZeroEndpoints = false
    )

    private fun glargineU100Curve() = curve(
        0 to 0f, 120 to 0.27f, 240 to 0.55f, 360 to 0.82f,
        480 to 1f, 600 to 0.78f, 720 to 0.74f, 960 to 0.79f,
        1200 to 0.80f, 1440 to 0.72f,
        forceZeroEndpoints = false
    )

    private fun glargineU300Curve() = curve(
        0 to 0.92f, 240 to 1f, 480 to 0.91f, 720 to 0.94f,
        960 to 0.86f, 1200 to 0.72f, 1440 to 0.64f, 1680 to 0.49f,
        1920 to 0.20f, 2160 to 0.10f,
        forceZeroEndpoints = false
    )

    private fun detemirVariants() = listOf(
        Variant(0.2f, curve(
            0 to 0f, 60 to 0.45f, 180 to 0.75f, 360 to 0.93f, 540 to 1f,
            720 to 0.83f, 900 to 0.55f, 1080 to 0.25f, 1260 to 0.05f,
            1440 to 0f
        )),
        Variant(0.4f, curve(
            0 to 0f, 60 to 0.42f, 180 to 0.58f, 360 to 0.77f, 540 to 1f,
            720 to 0.91f, 900 to 0.70f, 1080 to 0.44f, 1260 to 0.18f,
            1440 to 0f
        ))
    )

    private fun degludecSteadyStateCurve() = curve(
        0 to 0.70f, 360 to 0.96f, 720 to 0.96f, 1080 to 0.83f,
        1440 to 0.67f, 1800 to 0.38f, 2160 to 0.14f, 2520 to 0.05f,
        forceZeroEndpoints = false
    )

    private fun icodecSteadyStateCurve() = curve(
        0 to 0.80f, 1440 to 0.88f, 2880 to 0.96f, 4320 to 0.90f,
        5760 to 1f, 7200 to 0.86f, 8640 to 0.90f, 10080 to 0.82f,
        forceZeroEndpoints = false
    )

    private fun aspartMix7030Curve() = curve(
        0 to 0f, 10 to 0.10f, 20 to 0.42f, 60 to 0.86f, 120 to 1f,
        180 to 0.92f, 240 to 0.82f, 360 to 0.58f, 480 to 0.43f,
        600 to 0.27f, 720 to 0.21f, 900 to 0.13f, 1080 to 0.08f,
        1260 to 0.10f, 1380 to 0.06f, 1440 to 0f
    )

    private fun ryzodegCurve() = curve(
        0 to 0f, 20 to 0.12f, 60 to 0.62f, 120 to 0.94f,
        180 to 1f, 240 to 0.87f, 300 to 0.62f, 360 to 0.42f,
        480 to 0.25f, 720 to 0.18f, 1080 to 0.16f, 1440 to 0.14f,
        forceZeroEndpoints = false
    )

    private fun lisproMix5050Reference() = curve(
        0 to 0f, 60 to 0.50f, 120 to 0.86f, 180 to 1f,
        240 to 0.76f, 300 to 0.50f, 360 to 0.42f, 480 to 0.34f,
        600 to 0.28f, 720 to 0.22f, 900 to 0.15f, 1080 to 0.10f,
        1320 to 0.09f,
        forceZeroEndpoints = false
    )

    private fun lisproMix7525Reference() = curve(
        0 to 0f, 60 to 0.45f, 120 to 0.85f, 180 to 1f,
        240 to 0.70f, 300 to 0.52f, 360 to 0.48f, 480 to 0.43f,
        600 to 0.38f, 720 to 0.31f, 900 to 0.23f, 1080 to 0.17f,
        1320 to 0.13f,
        forceZeroEndpoints = false
    )

    private fun humanMix7030Reference() = curve(
        0 to 0f, 60 to 0.34f, 120 to 0.82f, 150 to 1f,
        240 to 0.88f, 360 to 0.65f, 480 to 0.47f, 600 to 0.28f,
        720 to 0.23f,
        forceZeroEndpoints = false
    )
}
