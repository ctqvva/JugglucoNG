// SPDX-License-Identifier: GPL-3.0-or-later
package tk.glucodata.drivers.caresens

import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Invoked by test-caresens-air-oracle.sh. Runtime inputs are the private engine,
 * a packed DeviceInfo from this repo's native struct, and upstream's five oracle lots.
 */
fun main(args: Array<String>) {
    check(args.size == 2)
    val javaEngine = checkNotNull(CareSensAirJavaEngine.available) { "Private Java engine could not be loaded" }
    val oracle = File(args[0])
    val factory = File(args[1]).readBytes()
    val eappByLot = floatArrayOf(0.10067f, 0.15f, 0.05f, 0.10067f, 0.10067f)
    var checked = 0
    for ((lot, eapp) in eappByLot.withIndex()) {
        val inputDir = File(oracle, "lot$lot")
        val start = 1_709_726_400L
        val packed = factory.copyOf().also {
            it.littleEndian().putFloat(438, eapp).putInt(442, start.toInt())
        }
        val engine = javaEngine.open(packed)
        val temp = Files.createTempDirectory("caresens-oracle-lot").toFile()
        val journal = CareSensAirJournal(temp, "oracle", "lot$lot")
        val config = CareSensAirJournal.Config(packed, ByteArray(0), IntArray(0))
        journal.saveConfig(config)
        var session = CareSensAirSession(journal, config, javaEngine.open(packed))
        val persisted = mutableListOf<CareSensAirSession.Computed>()
        try {
            for (record in 1..400) {
                val stem = "seq_%04d".format(record)
                val input = File(inputDir, "${stem}_input.bin").readBytes().also { check(it.size == 74) }.littleEndian()
                val output = File(inputDir, "${stem}_output.bin").readBytes().also { check(it.size == 155) }.littleEndian()
                val sequence = input.u16(0)
                val stamp = input.u32(2)
                val samples = IntArray(30) { input.u16(6 + it * 2) }
                val temperature = input.getDouble(66)
                val actual = engine.process(
                    sequence, stamp, samples, temperature,
                )
                fun exact(actualValue: Number, expected: Number, field: String) {
                    check(actualValue == expected) { "Lot $lot reading $record $field: $actualValue vs $expected" }
                }
                fun close(actualValue: Double, expected: Double, field: String) {
                    val error = abs(actualValue - expected)
                    check(actualValue == expected || (actualValue.isNaN() && expected.isNaN()) ||
                        error < 1e-12 || error < abs(expected) * 1e-10) {
                        "Lot $lot reading $record $field: $actualValue vs $expected"
                    }
                }
                exact(actual.sequence, output.u16(2), "sequence")
                exact(actual.timestampSeconds, output.u32(4), "time")
                close(actual.glucoseMgdl, output.getDouble(68), "glucose")
                close(actual.rate, output.getDouble(76), "trend")
                exact(actual.stage, output.u8(84), "stage")
                exact(actual.error, output.u16(151), "error")
                val frame = ByteArray(84)
                val bytes = frame.littleEndian().put(0, 0xC5.toByte()).put(1, 1)
                    .putInt(12, sequence).putInt(16, stamp.toInt()).putShort(22, (temperature * 100).roundToInt().toShort())
                samples.forEachIndexed { index, sample -> bytes.putShort(24 + index * 2, sample.toShort()) }
                val arrival = session.receive(frame, stamp + 1) as CareSensAirSession.Arrival.Accepted
                arrival.computed?.let { persisted.add(it) }
                if (record == 183) {
                    val saved = checkNotNull(journal.loadConfig())
                    session = CareSensAirSession(journal, saved, javaEngine.open(saved.packed))
                    check(session.recover() == persisted) { "Lot $lot changed during restart replay" }
                    check(session.cursor == 183)
                }
                checked++
            }
            val saved = checkNotNull(journal.loadConfig())
            val replayed = CareSensAirSession(journal, saved, javaEngine.open(saved.packed))
            check(replayed.recover() == persisted && replayed.cursor == 400)
        } finally {
            temp.deleteRecursively()
        }
        println("Lot $lot: 400 comparisons and journal restart replay passed")
    }
    check(checked == 2_000)
    println("Matched final sequence, time, glucose, trend, stage and error for $checked oracle inputs")
}
