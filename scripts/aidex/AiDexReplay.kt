package tk.glucodata.drivers.aidex.adaptive2

import java.io.File

/** Private TSV replay. No Android/JNI, settings, account tokens or pairing keys. */
object AiDexReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: AiDexReplay input.tsv output.csv" }
        val models = mutableMapOf<Pair<String, String>, AiDexAdaptiveV2>()
        File(args[1]).bufferedWriter().use { output ->
            output.appendLine("sensor,timestampMs,sessionStartMs,model,factoryMmol,rawProxyMmol,referenceMmol,preReferenceMmol,glucoseMmol,lower90Mmol,upper90Mmol,normalizedRawMmol,scale,rate,artifactProbability,driftProbability")
            File(args[0]).useLines { lines ->
                lines.drop(1).forEach { line ->
                    val f = line.split('\t')
                    require(f.size == 7)
                    val sensor = f[0]
                    val sample = AiDexAdaptiveSample(f[1].toLong(), f[2].toLong(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat())
                    val references = f[6].split(';').filter { it.isNotBlank() }.map {
                        val pair = it.split(':')
                        AiDexReference(pair[0].toLong(), pair[1].toFloat())
                    }
                    for (name in listOf("factory_assisted", "factory_assisted_references", "reference_only")) {
                        val model = models.getOrPut(sensor to name) {
                            AiDexAdaptiveV2(if (name == "reference_only") AiDexNormalization.REFERENCE_ONLY else AiDexNormalization.FACTORY_ASSISTED)
                        }
                        val applied = if (name == "factory_assisted") emptyList() else references
                        // Score BEFORE this reference has reached the estimator.
                        // A cloned checkpoint avoids advancing the real model twice.
                        val prediction = if (applied.isNotEmpty()) {
                            AiDexAdaptiveV2(model.normalization).also {
                                check(it.restore(model.snapshot()))
                            }.process(sample)
                        } else null
                        val result = model.process(sample, applied)
                        output.appendLine(listOf(
                            sensor, sample.timestampMs, sample.sessionStartMs, name,
                            sample.factoryMgDl / AiDexAdaptiveV2.MGDL_PER_MMOL,
                            sample.rawProxyMgDl / AiDexAdaptiveV2.MGDL_PER_MMOL,
                            references.firstOrNull()?.glucoseMgDl?.div(AiDexAdaptiveV2.MGDL_PER_MMOL) ?: Float.NaN,
                            prediction?.estimate?.glucoseMmol ?: if (name == "factory_assisted" && references.isNotEmpty()) result?.estimate?.glucoseMmol ?: Float.NaN else Float.NaN,
                            result?.estimate?.glucoseMmol ?: Float.NaN,
                            result?.estimate?.lower90Mmol ?: Float.NaN,
                            result?.estimate?.upper90Mmol ?: Float.NaN,
                            result?.normalizedRawMmol ?: Float.NaN,
                            result?.rawToGlucoseScale ?: Float.NaN,
                            result?.estimate?.rateMmolPerMin ?: Float.NaN,
                            result?.estimate?.artifactProbability ?: Float.NaN,
                            result?.estimate?.driftProbability ?: Float.NaN,
                        ).joinToString(","))
                    }
                }
            }
        }
    }
}
