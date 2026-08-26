@file:Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "LocalVariableName")

package tk.glucodata.drivers.sibionics.v116a

import tk.glucodata.drivers.sibionics.SibionicsChemicalSignal
import tk.glucodata.drivers.sibionics.SibionicsSensorObservation

private data class Ptr(val bytes: ByteArray, val off: Int = 0) {
    fun plus(delta: Int): Ptr = Ptr(bytes, off + delta)
}

private fun b(value: Boolean): Int = if (value) 1 else 0
private const val C_DONE = -1
private fun f32(value: Double): Double = value.toFloat().toDouble()
private fun uLt(a: Int, b: Int): Boolean = Integer.compareUnsigned(a, b) < 0
private fun uLe(a: Int, b: Int): Boolean = Integer.compareUnsigned(a, b) <= 0
private fun uGt(a: Int, b: Int): Boolean = Integer.compareUnsigned(a, b) > 0
private fun uGe(a: Int, b: Int): Boolean = Integer.compareUnsigned(a, b) >= 0
private fun u64ToDouble(value: Long): Double = java.lang.Long.toUnsignedString(value).toDouble()
private fun sBorrow4(a: Int, b: Int): Boolean { val r = a - b; return ((a xor b) and (a xor r)) < 0 }
private fun readI32(p: Ptr): Int =
    (p.bytes[p.off].toInt() and 0xff) or ((p.bytes[p.off + 1].toInt() and 0xff) shl 8) or
        ((p.bytes[p.off + 2].toInt() and 0xff) shl 16) or (p.bytes[p.off + 3].toInt() shl 24)
private fun writeI32(p: Ptr, value: Int) { for (i in 0..3) p.bytes[p.off + i] = (value ushr (8 * i)).toByte() }
private fun readI64(p: Ptr): Long = (0..7).fold(0L) { a, i -> a or ((p.bytes[p.off + i].toLong() and 0xffL) shl (8 * i)) }
private fun writeI64(p: Ptr, value: Long) { for (i in 0..7) p.bytes[p.off + i] = (value ushr (8 * i)).toByte() }
private fun readF32(p: Ptr): Double = Float.fromBits(readI32(p)).toDouble()
private fun writeF32(p: Ptr, value: Double) = writeI32(p, value.toFloat().toRawBits())
private fun readF64(p: Ptr): Double = Double.fromBits(readI64(p))
private fun writeF64(p: Ptr, value: Double) = writeI64(p, value.toRawBits())

private fun initClipContext(p: Ptr, sensitivity: Double) {
    p.bytes.fill(0)
    writeI64(p.plus(0x000), 0x4120000042c80000L)
    writeI64(p.plus(0x134), 0x4120000041c80000L)
    writeI64(p.plus(0x13c), 0x4120000000000000L)
    for (off in intArrayOf(0x158, 0x15c, 0x160, 0x164, 0x178, 0x180, 0x270)) writeF32(p.plus(off), 10.0)
    writeF32(p.plus(0x1e4), 100.0)
    writeF32(p.plus(0x208), sensitivity)
    writeF32(p.plus(0x20c), sensitivity)
    writeI64(p.plus(0x214), 0x4248000000000000L)
    writeF32(p.plus(0x21c), 50.0)
    writeF32(p.plus(0x268), 100.0)
}

private fun initCalibrationContext(p: Ptr) {
    p.bytes.fill(0)
    writeI64(p.plus(0x44), 0x0000000041a00000L)
}


private val ARR_0012f2c8 = doubleArrayOf(-5.2, -4.7)
private val ARR_0012f2d8 = doubleArrayOf(0.9, 0.4)
private val ARR_0012f2e8 = doubleArrayOf(0.8, 0.4)
private const val DAT_0012ee70: Double = 0.9
private const val DAT_0012ee78: Double = 1.1
private const val DAT_0012ee80: Double = 1.3
private const val DAT_0012ee88: Double = 1.6
private const val DAT_0012ee90: Double = 1.8
private const val DAT_0012eeb0: Double = 0.93
private const val DAT_0012eef8: Double = 0.2
private const val DAT_0012ef00: Double = -35.8
private const val DAT_0012ef08: Double = -35.2
private const val DAT_0012ef10: Double = -34.7
private const val DAT_0012ef18: Double = -34.3
private const val DAT_0012ef20: Double = -33.7
private const val DAT_0012ef28: Double = -32.7
private const val DAT_0012ef30: Double = -31.7
private const val DAT_0012ef38: Double = 0.4
private const val DAT_0012ef40: Double = -35.7
private const val DAT_0012ef48: Double = -34.2
private const val DAT_0012ef50: Double = -33.2
private const val DAT_0012ef58: Double = -32.2
private const val DAT_0012ef60: Double = 0.65
private const val DAT_0012ef68: Double = 7.2
private const val DAT_0012ef70: Double = 0.8
private const val DAT_0012ef78: Double = -0.2
private const val DAT_0012ef80: Double = 1.35
private const val DAT_0012ef88: Double = 0.03
private const val DAT_0012ef90: Long = -4612966458782121984L
private const val DAT_0012ef98: Long = 4153075059401076675L
private const val DAT_0012f010: Double = 0.55
private const val DAT_0012f018: Double = 0.1
private const val DAT_0012f020: Double = -0.1
private const val DAT_0012f028: Double = 0.01
private const val DAT_0012f030: Double = 3.2
private const val DAT_0012f038: Double = 2.7
private const val DAT_0012f040: Double = 0.45
private const val DAT_0012f048: Double = 4.2
private const val DAT_0012f050: Double = 3.6
private const val DAT_0012f058: Double = 0.83
private const val DAT_0012f060: Double = 3.7
private const val DAT_0012f068: Double = 2.6
private const val DAT_0012f070: Double = 2.2
private const val DAT_0012f078: Double = 4.7
private const val DAT_0012f080: Double = 3.1
private const val DAT_0012f088: Double = 1.11
private const val DAT_0012f090: Double = 5.9
private const val DAT_0012f098: Double = 4.4
private const val DAT_0012f0a0: Double = -3.2
private const val DAT_0012f0a8: Double = -4.7
private const val DAT_0012f0b0: Double = 8.8
private const val DAT_0012f0b8: Double = -0.01
private const val DAT_0012f0c0: Double = 0.97
private const val DAT_0012f0c8: Double = 11.8
private const val DAT_0012f0d0: Double = -0.4
private const val DAT_0012f0d8: Double = 0.065
private const val DAT_0012f0e0: Double = -12.8
private const val DAT_0012f0e8: Double = 10.8
private const val DAT_0012f0f0: Double = 11.3
private const val DAT_0012f0f8: Double = 12.3
private const val DAT_0012f100: Double = 13.3
private const val DAT_0012f108: Double = -0.32
private const val DAT_0012f110: Double = -13.4
private const val DAT_0012f118: Double = -11.8
private const val DAT_0012f120: Double = -12.3
private const val DAT_0012f128: Double = -13.3
private const val DAT_0012f130: Double = 2.1
private const val DAT_0012f138: Double = -0.39
private const val DAT_0012f140: Double = 8.3
private const val DAT_0012f148: Double = -0.37
private const val DAT_0012f150: Double = 5.2
private const val DAT_0012f158: Double = -5.2
private const val DAT_0012f160: Double = 6.2
private const val DAT_0012f168: Double = -5.7
private const val DAT_0012f170: Double = 4.9
private const val DAT_0012f178: Double = -0.05
private const val DAT_0012f180: Double = -4.9
private const val DAT_0012f188: Double = -0.29
private const val DAT_0012f190: Double = 0.29
private const val DAT_0012f198: Double = -3.6
private const val DAT_0012f1a0: Double = -0.04
private const val DAT_0012f1a8: Double = -0.08
private const val DAT_0012f1b0: Double = -0.28
private const val DAT_0012f1b8: Double = 0.27
private const val DAT_0012f1c0: Double = 0.02
private const val DAT_0012f1c8: Double = 0.37
private const val DAT_0012f1d0: Double = 5.7
private const val DAT_0012f1d8: Long = 1092616192L
private const val DAT_0012f1e0: Double = -0.03
private const val DAT_0012f1e8: Double = 0.025
private const val DAT_0012f1f0: Double = 0.08
private const val DAT_0012f1f8: Double = -0.33
private const val DAT_0012f200: Double = 0.28
private const val DAT_0012f208: Double = 12.8
private const val DAT_0012f210: Double = -0.34
private const val DAT_0012f218: Double = 0.33
private const val DAT_0012f220: Double = -0.06
private const val DAT_0012f228: Double = 0.05
private const val DAT_0012f230: Double = 0.32
private const val DAT_0012f238: Double = 0.31
private const val DAT_0012f240: Double = -0.27
private const val DAT_0012f248: Double = 0.04
private const val DAT_0012f250: Double = -0.45
private const val DAT_0012f258: Double = 9.4
private const val DAT_0012f260: Double = 6.4
private const val DAT_0012f268: Double = -2.2
private const val DAT_0012f270: Double = -2.7
private const val DAT_0012f278: Double = 5.4
private const val DAT_0012f280: Double = -0.8
private const val DAT_0012f288: Double = -3.7
private const val DAT_0012f290: Double = 7.4
private const val DAT_0012f298: Double = -4.2
private const val DAT_0012f2a0: Double = 8.4
private const val DAT_0012f2a8: Double = 9.8
private const val DAT_0012f2b0: Double = -0.02
private const val DAT_0012f2b8: Double = -0.36
private const val DAT_0012f2c0: Double = -0.032
private const val _DAT_0012efb0: Long = 4595870030058487808L
private const val _DAT_0012efc0: Long = -4619661647076655104L
private const val _DAT_0012efd0: Long = -4618074106904969216L
private const val _DAT_0012efe0: Long = -4627502006796288000L
private const val _UNK_0012efb8: Long = 4561288392199122845L
private const val _UNK_0012efc8: Long = 4302520619822285159L
private const val _UNK_0012efd8: Long = 4282177455685783945L
private const val _UNK_0012efe8: Long = 4369764740929695645L

private class algorithmconvertprocessFrame(var param_1: Ptr, var param_2: Double, var param_3: Double, var param_4: Double, var param_5: Int, var param_6: Ptr, var param_7: Double, var param_8: Double) {
    var iVar1: Int = 0
    var iVar2: Int = 0
    var fVar3: Double = 0.0
    var uVar4: Long = 0L
    var uVar5: Long = 0L
    var uVar6: Int = 0
    var uVar7: Int = 0
    var uVar8: Long = 0L
    var pjVar9: Ptr = Ptr(ByteArray(0), 0)
    var fVar10: Double = 0.0
    var fVar11: Double = 0.0
    var fVar12: Double = 0.0
    var fVar13: Double = 0.0
    var dVar14: Double = 0.0
    var dVar15: Double = 0.0
    var fVar16: Double = 0.0
    var fVar17: Double = 0.0
    var fVar18: Double = 0.0
    var fVar19: Double = 0.0
    var fVar20: Double = 0.0
    var result: Double = 0.0
}

private fun algorithmconvertprocessStep0(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        0 -> {
            277
        }
        1 -> {
            276
        }
        2 -> {
            290
        }
        3 -> {
            287
        }
        4 -> {
            164
        }
        5 -> {
            117
        }
        6 -> {
            116
        }
        7 -> {
            150
        }
        8 -> {
            144
        }
        9 -> {
            143
        }
        10 -> {
            139
        }
        11 -> {
            138
        }
        12 -> {
            114
        }
        13 -> {
            238
        }
        14 -> {
            236
        }
        15 -> {
            225
        }
        16 -> {
            220
        }
        17 -> {
            209
        }
        18 -> {
            203
        }
        19 -> {
            201
        }
        20 -> {
            196
        }
        21 -> {
            191
        }
        22 -> {
            174
        }
        23 -> {
            181
        }
        24 -> {
            113
        }
        25 -> {
            100
        }
        26 -> {
            32
        }
        27 -> {
            f.result = f32(f.fVar11)
            C_DONE
        }
        28 -> {
            f.fVar11 = f32(0.0)
            27
        }
        29 -> {
            if ((b((f.fVar11) <= (0.0))) != 0) 28 else 27
        }
        30 -> {
            f.fVar11 = f32(f.fVar18)
            29
        }
        31 -> {
            if ((b((f.fVar10) != (-(1.0)))) != 0) 30 else 29
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep1(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        32 -> {
            f.fVar11 = f32(0.0)
            31
        }
        33 -> {
            writeI32(f.param_1.plus(0x30), 2)
            26
        }
        34 -> {
            writeI32(f.param_1.plus(0x30), 0)
            26
        }
        35 -> {
            if ((b((f.param_8) <= (readF32(f.param_1.plus(0x28))))) != 0) 33 else 34
        }
        36 -> {
            writeI32(f.param_1.plus(0x30), 1)
            26
        }
        37 -> {
            if ((b((f.param_7) <= (readF32(f.param_1.plus(0x28))))) != 0) 35 else 36
        }
        38 -> {
            writeI32(f.param_1.plus(0x3c), f.uVar7)
            37
        }
        39 -> {
            writeI32(f.param_1.plus(0x950), f.uVar7)
            38
        }
        40 -> {
            f.uVar7 = Arrow_direction(f.param_1.plus(0x950), f.fVar11, f.iVar1)
            39
        }
        41 -> {
            writeF32(f.param_1.plus(0x5b4), f32(f.fVar18))
            40
        }
        42 -> {
            f.fVar18 = f32(((f32(((((((f.fVar18) * (10.0))) + (0.5))).toInt()).toDouble())) / (10.0)))
            41
        }
        43 -> {
            f.fVar18 = f32(f32((f.fVar11) + (readF32(f.param_1.plus(0x40)))))
            42
        }
        44 -> {
            f.fVar18 = f32(f32((f.fVar11) + (readF32(f.param_1.plus(0x40)))))
            42
        }
        45 -> {
            f.fVar18 = f32(f32((f.fVar10) + (readF32(f.param_1.plus(0x40)))))
            42
        }
        46 -> {
            if ((b((readI32(f.param_1.plus(0x57c))) == (1))) != 0) 44 else 45
        }
        47 -> {
            if ((b((readI32(f.param_1.plus(0x57c))) == (0))) != 0) 43 else 46
        }
        48 -> {
            writeF32(f.param_1.plus(0x5ac), f32(f.fVar12))
            47
        }
        49 -> {
            writeF32(f.param_1.plus(0x28), f32(f.fVar11))
            48
        }
        50 -> {
            f.fVar11 = f32(f32(Regular_deconvolution(f.param_1.plus(0x858), f.fVar11, f.iVar1)))
            49
        }
        51 -> {
            writeF32(f.param_1.plus(0x28), f32(f.fVar11))
            50
        }
        52 -> {
            f.fVar11 = f32(f32(ESA_Compensate(f.param_1.plus(0x538), f.fVar10, readF32(f.param_1.plus(0x510)), f.iVar1)))
            51
        }
        53 -> {
            writeF32(f.pjVar9, f32(f.fVar11))
            52
        }
        54 -> {
            if ((b(((b((f.fVar11) != (0.0))) != 0) && ((b((f.fVar11) != (readF32(f.pjVar9)))) != 0))) != 0) 53 else 52
        }
        55 -> {
            f.fVar11 = f32(readF32(f.param_1.plus(0x4c8)))
            54
        }
        56 -> {
            Clipping_filter(f.param_1.plus(0x29c), f.param_1.plus(0x9ac), f.param_1.plus(0x250), f.fVar10, f.fVar11, f.fVar16, f.fVar17, f.fVar12, f.fVar13, f.fVar18, f.fVar19, f.fVar20, f.fVar3, f.iVar1)
            55
        }
        57 -> {
            writeI32(f.param_1.plus(0x4c8), readI32(f.param_1.plus(0xdc)))
            56
        }
        58 -> {
            if ((b((readF32(f.param_1.plus(0x4c8))) == (0.0))) != 0) 57 else 56
        }
        59 -> {
            f.fVar13 = f32(f32(functionFilter(f.fVar10, f.param_1.plus(0x224), f.param_1.plus(0x230), f.param_1.plus(0x238), f.param_1.plus(0x244), f.iVar1)))
            58
        }
        60 -> {
            writeI64(f.param_1.plus(0x248), 4369764740896163294L)
            59
        }
        61 -> {
            writeI64(f.param_1.plus(0x238), f.uVar4)
            60
        }
        62 -> {
            writeI64(f.param_1.plus(0x240), _UNK_0012efe8)
            61
        }
        63 -> {
            f.uVar4 = _DAT_0012efe0
            62
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep2(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        64 -> {
            f.fVar12 = f32(f32(functionFilter(f.fVar10, f.param_1.plus(0x1f8), f.param_1.plus(0x204), f.param_1.plus(0x20c), f.param_1.plus(0x218), f.iVar1)))
            63
        }
        65 -> {
            writeI64(f.param_1.plus(0x21c), 4282177455628573109L)
            64
        }
        66 -> {
            writeI64(f.param_1.plus(0x20c), f.uVar4)
            65
        }
        67 -> {
            writeI64(f.param_1.plus(0x214), _UNK_0012efd8)
            66
        }
        68 -> {
            f.uVar4 = _DAT_0012efd0
            67
        }
        69 -> {
            f.fVar17 = f32(f32(functionFilter(f.fVar10, f.param_1.plus(0x1cc), f.param_1.plus(0x1d8), f.param_1.plus(0x1e0), f.param_1.plus(0x1ec), f.iVar1)))
            68
        }
        70 -> {
            writeI32(f.param_1.plus(500), 0x3922afc3)
            69
        }
        71 -> {
            writeI64(f.param_1.plus(0x1ec), f.uVar5)
            70
        }
        72 -> {
            writeI64(f.param_1.plus(0x1e0), f.uVar4)
            71
        }
        73 -> {
            writeI32(f.param_1.plus(0x1e8), 0x3f770f8f)
            72
        }
        74 -> {
            f.uVar4 = DAT_0012ef90
            73
        }
        75 -> {
            f.uVar5 = DAT_0012ef98
            74
        }
        76 -> {
            f.fVar16 = f32(f32(functionFilter(f.fVar10, f.param_1.plus(0x1a0), f.param_1.plus(0x1ac), f.param_1.plus(0x1b4), f.param_1.plus(0x1c0), f.iVar1)))
            75
        }
        77 -> {
            writeI64(f.param_1.plus(0x1c4), 4302520619770421162L)
            76
        }
        78 -> {
            writeI64(f.param_1.plus(0x1b4), f.uVar4)
            77
        }
        79 -> {
            writeI64(f.param_1.plus(0x1bc), _UNK_0012efc8)
            78
        }
        80 -> {
            f.uVar4 = _DAT_0012efc0
            79
        }
        81 -> {
            f.fVar11 = f32(f32(functionFilter(f.fVar10, f.param_1.plus(0x174), f.param_1.plus(0x180), f.param_1.plus(0x188), f.param_1.plus(0x194), f.iVar1)))
            80
        }
        82 -> {
            writeI64(f.param_1.plus(0x198), 4561288392210183072L)
            81
        }
        83 -> {
            writeI64(f.param_1.plus(0x188), f.uVar4)
            82
        }
        84 -> {
            writeI64(f.param_1.plus(400), f.uVar5)
            83
        }
        85 -> {
            writeF32(f.param_1.plus(0x5b0), f32(f.fVar10))
            84
        }
        86 -> {
            f.uVar4 = _DAT_0012efb0
            85
        }
        87 -> {
            f.uVar5 = _UNK_0012efb8
            86
        }
        88 -> {
            f.fVar10 = f32(f32(((f.fVar10) - (((((((1.0) / (((f.dVar15) + (1.0))))) + (-(0.5)))) * (f.fVar10))))))
            87
        }
        89 -> {
            f.dVar15 = kotlin.math.exp(((((14.0) - (f.fVar10))) * (DAT_0012ef88)))
            88
        }
        90 -> {
            if ((b((14.0) < (f.fVar10))) != 0) 89 else 87
        }
        91 -> {
            f.fVar10 = f32(f32((f32((f.fVar11) - (f.fVar3))) / (f.fVar10)))
            90
        }
        92 -> {
            f.fVar10 = f32(f32((f32((f.fVar11) - (f.fVar3))) / (f.fVar10)))
            90
        }
        93 -> {
            25
        }
        94 -> {
            f.dVar15 = f32((f.fVar11) - (f.fVar3))
            93
        }
        95 -> {
            f.dVar14 = 1.45
            94
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep3(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        96 -> {
            if ((b(((b((2.5) <= (f.fVar10))) != 0) || ((b((f.fVar10) <= (0.7))) != 0))) != 0) 95 else 92
        }
        97 -> {
            f.fVar10 = f32(readF32(f.param_1.plus(700)))
            96
        }
        98 -> {
            if ((b(((b(((b((2.5) > (f.fVar10))) != 0) && ((b((f.dVar15) > (0.7))) != 0))) != 0) || ((run { run { f.fVar10 = f32(readF32(f.pjVar9)); f32(readF32(f.pjVar9)) }; b(((b((f.fVar10) < (2.5))) != 0) && ((b((0.7) < (f.fVar10))) != 0)) }) != 0))) != 0) 91 else 97
        }
        99 -> {
            f.fVar10 = f32(f32(f.dVar15))
            90
        }
        100 -> {
            f.dVar15 = ((f.dVar15) / (f.dVar14))
            99
        }
        101 -> {
            f.dVar14 = DAT_0012ef80
            25
        }
        102 -> {
            if ((b(((b((2.5) <= (readF32(f.param_1.plus(700))))) != 0) || ((run { run { f.dVar14 = readF32(f.param_1.plus(700)); readF32(f.param_1.plus(700)) }; b((f.dVar14) <= (0.7)) }) != 0))) != 0) 101 else 25
        }
        103 -> {
            f.dVar15 = ((f.fVar11) + (DAT_0012ef78))
            102
        }
        104 -> {
            f.dVar15 = ((((f.fVar11) + (DAT_0012ef78))) / (f.dVar15))
            99
        }
        105 -> {
            if ((b(((b((2.5) <= (f.fVar10))) != 0) || ((b((f.dVar15) <= (0.7))) != 0))) != 0) 103 else 104
        }
        106 -> {
            if ((b(((b((0.0) <= (f.fVar3))) != 0) || ((b((((readF32(f.param_1.plus(0x4cc))) * (0.95))) <= (f.dVar15))) != 0))) != 0) 98 else 105
        }
        107 -> {
            writeF32(f.param_1.plus(0x54), f32(f.fVar3))
            106
        }
        108 -> {
            f.dVar15 = f.fVar10
            107
        }
        109 -> {
            f.fVar10 = f32(readF32(f.param_1.plus(0x4c8)))
            108
        }
        110 -> {
            writeF32(f.param_1.plus(0x4c8), f32(f.fVar10))
            109
        }
        111 -> {
            writeF32(f.param_1.plus(700), f32(f.fVar10))
            110
        }
        112 -> {
            writeF32(f.param_1.plus(0x4cc), f32(f.fVar10))
            111
        }
        113 -> {
            if ((b(((b((f.iVar2) == (4))) != 0) && ((run { run { f.fVar10 = f32(readF32(f.pjVar9)); f32(readF32(f.pjVar9)) }; b((DAT_0012ef70) < (f.fVar10)) }) != 0))) != 0) 112 else 109
        }
        114 -> {
            f.fVar3 = f32(f32(((f.dVar15) + (0.3))))
            24
        }
        115 -> {
            f.dVar15 = ((f.dVar15) * (DAT_0012eef8))
            12
        }
        116 -> {
            f.dVar15 = ((((f.fVar18) + (20.0))) + (f.dVar15))
            115
        }
        117 -> {
            f.dVar15 = -(36.5)
            6
        }
        118 -> {
            22
        }
        119 -> {
            if ((b((15.5) <= (f.fVar18))) != 0) 118 else 5
        }
        120 -> {
            11
        }
        121 -> {
            f.fVar10 = f32(-(36.0))
            120
        }
        122 -> {
            if ((b((f.fVar18) < (14.5))) != 0) 121 else 119
        }
        123 -> {
            9
        }
        124 -> {
            f.uVar8 = 211106232532992L
            123
        }
        125 -> {
            if ((b((f.fVar18) < (14.0))) != 0) 124 else 122
        }
        126 -> {
            10
        }
        127 -> {
            if ((b((f.fVar18) < (13.0))) != 0) 126 else 125
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep4(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        128 -> {
            8
        }
        129 -> {
            if ((b((f.fVar18) < (12.0))) != 0) 128 else 127
        }
        130 -> {
            16
        }
        131 -> {
            if ((b((f.fVar18) < (11.0))) != 0) 130 else 129
        }
        132 -> {
            11
        }
        133 -> {
            f.fVar10 = f32(-(33.0))
            132
        }
        134 -> {
            if ((b((f.fVar18) < (10.0))) != 0) 133 else 131
        }
        135 -> {
            11
        }
        136 -> {
            f.fVar10 = f32(-(32.0))
            135
        }
        137 -> {
            if ((b((f.fVar18) < (9.0))) != 0) 136 else 134
        }
        138 -> {
            f.dVar15 = ((((f.fVar18) + (20.0))) + (f.fVar10))
            115
        }
        139 -> {
            f.fVar10 = f32(-(35.0))
            11
        }
        140 -> {
            22
        }
        141 -> {
            if ((b(((b(((b(((b((12.0) <= (f.fVar18))) != 0) && ((b((13.0) <= (f.fVar18))) != 0))) != 0) && ((b((14.0) <= (f.fVar18))) != 0))) != 0) && ((b((15.0) <= (f.fVar18))) != 0))) != 0) 140 else 10
        }
        142 -> {
            6
        }
        143 -> {
            f.dVar15 = Double.fromBits((f.uVar8).or(-4593390144941195264L))
            142
        }
        144 -> {
            f.uVar8 = 70368744177664L
            9
        }
        145 -> {
            if ((b((f.fVar18) < (11.0))) != 0) 8 else 141
        }
        146 -> {
            7
        }
        147 -> {
            f.uVar8 = 211106232532992L
            146
        }
        148 -> {
            if ((b((f.fVar18) < (10.0))) != 0) 147 else 145
        }
        149 -> {
            6
        }
        150 -> {
            f.dVar15 = Double.fromBits((f.uVar8).or(-4593671619917905920L))
            149
        }
        151 -> {
            f.uVar8 = 70368744177664L
            7
        }
        152 -> {
            if ((b((f.fVar18) < (9.0))) != 0) 151 else 148
        }
        153 -> {
            if ((b(((b((11.0) <= (f.fVar20))) != 0) || ((b((5.0) < (f.fVar10))) != 0))) != 0) 137 else 152
        }
        154 -> {
            24
        }
        155 -> {
            if ((b(((b((15.0) < (readF32(f.param_1.plus(0x4ec))))) != 0) && ((run { run { f.fVar3 = f32(0.3); f32(0.3) }; b(((b((8.0) < (f.fVar17))) != 0) || ((b((8.0) < (f.fVar16))) != 0)) }) != 0))) != 0) 154 else 153
        }
        156 -> {
            22
        }
        157 -> {
            if ((b(((b((f.fVar20) < (10.5))) != 0) && ((b((8.5) < (f.fVar16))) != 0))) != 0) 156 else 155
        }
        158 -> {
            14
        }
        159 -> {
            if ((b(((b((8.6) < (f.fVar17))) != 0) && ((b(((b(((b((9.0) < (f.fVar16))) != 0) || ((b((f32((f.fVar17) - (f.fVar16))) < (2.0))) != 0))) != 0) && ((b((10.0) < (readF32(f.param_1.plus(0x4ec))))) != 0))) != 0))) != 0) 158 else 157
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep5(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        160 -> {
            f.fVar16 = f32(readF32(f.param_1.plus(0x498)))
            159
        }
        161 -> {
            f.fVar17 = f32(readF32(f.param_1.plus(0x494)))
            160
        }
        162 -> {
            22
        }
        163 -> {
            if ((b(((b((DAT_0012ef68) <= (f.fVar10))) != 0) && ((b(((b(((b((11.0) <= (f.fVar20))) != 0) || ((b((f.fVar10) <= (7.8))) != 0))) != 0) && ((b((DAT_0012ef70) <= (f32((readF32(f.param_1.plus(0x468))) - (f.fVar10))))) != 0))) != 0))) != 0) 162 else 161
        }
        164 -> {
            f.fVar10 = f32(readF32(f.param_1.plus(0x4f4)))
            163
        }
        165 -> {
            writeF32(f.param_1.plus(0x4c8), f32(f.fVar10))
            4
        }
        166 -> {
            f.fVar10 = f32(readF32(f.param_1.plus(0x4cc)))
            165
        }
        167 -> {
            4
        }
        168 -> {
            if ((b((readI32(f.param_1.plus(0x2a8))) != (1))) != 0) 167 else 166
        }
        169 -> {
            if ((b(((b((readI32(f.param_1.plus(0x4bc))) != (0))) != 0) || ((run { run { f.fVar10 = f32(readF32(f.param_1.plus(0x4cc))); f32(readF32(f.param_1.plus(0x4cc))) }; b((f.fVar10) == (0.0)) }) != 0))) != 0) 168 else 165
        }
        170 -> {
            writeF32(f.param_1.plus(0x4c8), f32(((((1.0) - (((((((f.fVar18) / (11.0))) * (((11.0) - (f.fVar18))))) / (11.0))))) * (readF32(f.param_1.plus(0x4cc))))))
            4
        }
        171 -> {
            if ((b(((b(((b((11.0) <= (f.fVar18))) != 0) || ((b((f.fVar18) <= (9.0))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x438))) <= (3.5))) != 0) || ((b((readI32(f.param_1.plus(0x2a8))) != (0))) != 0))) != 0))) != 0) 169 else 170
        }
        172 -> {
            13
        }
        173 -> {
            if ((b((18.5) <= (readF32(f.param_1.plus(0x404))))) != 0) 172 else 171
        }
        174 -> {
            f.fVar3 = f32(0.3)
            24
        }
        175 -> {
            18
        }
        176 -> {
            17
        }
        177 -> {
            if ((b(((b(((b(((b(((b(((b((10.0) <= (f.fVar18))) != 0) && ((run { run { f.dVar15 = DAT_0012ef50; DAT_0012ef50 }; b((11.0) <= (f.fVar18)) }) != 0))) != 0) && ((run { run { f.dVar15 = DAT_0012ef20; DAT_0012ef20 }; b((12.0) <= (f.fVar18)) }) != 0))) != 0) && ((run { run { f.dVar15 = DAT_0012ef48; DAT_0012ef48 }; b(((b((13.0) <= (f.fVar18))) != 0) && ((run { run { f.dVar15 = DAT_0012ef10; DAT_0012ef10 }; b((14.0) <= (f.fVar18)) }) != 0)) }) != 0))) != 0) && ((run { run { f.dVar15 = DAT_0012ef10; DAT_0012ef10 }; b((14.5) <= (f.fVar18)) }) != 0))) != 0) && ((run { run { f.dVar15 = DAT_0012ef08; DAT_0012ef08 }; run { f.dVar14 = DAT_0012ef40; DAT_0012ef40 }; b((15.0) <= (f.fVar18)) }) != 0))) != 0) 176 else 175
        }
        178 -> {
            f.dVar15 = DAT_0012ef58
            177
        }
        179 -> {
            6
        }
        180 -> {
            f.dVar15 = -(31.2)
            179
        }
        181 -> {
            if ((b((f.fVar18) < (9.0))) != 0) 180 else 178
        }
        182 -> {
            24
        }
        183 -> {
            if ((b(((b((16.0) < (f.fVar16))) != 0) || ((run { run { f.fVar3 = f32(0.3); f32(0.3) }; b(((b((f.fVar10) <= (1.0))) != 0) && ((b((DAT_0012ef38) <= (f.fVar10))) != 0)) }) != 0))) != 0) 182 else 23
        }
        184 -> {
            f.fVar3 = f32(0.3)
            183
        }
        185 -> {
            f.fVar10 = f32(f32((readF32(f.param_1.plus(0x414))) - (readF32(f.param_1.plus(0x430)))))
            184
        }
        186 -> {
            22
        }
        187 -> {
            if ((b((16.0) < (f.fVar16))) != 0) 186 else 23
        }
        188 -> {
            if ((b((DAT_0012ef60) <= (f32((readF32(f.param_1.plus(0x3fc))) - (readF32(f.param_1.plus(0x414))))))) != 0) 185 else 187
        }
        189 -> {
            if ((b(((b((f.fVar10) < (9.0))) != 0) && ((b((readF32(f.param_1.plus(0x534))) < (3.9))) != 0))) != 0) 188 else 24
        }
        190 -> {
            f.fVar3 = f32(0.3)
            189
        }
        191 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x4bc))) != (1))) != 0) || ((b((72.0) <= (readF32(f.param_1.plus(0x44c))))) != 0))) != 0) || ((run { run { f.fVar16 = f32(readF32(f.param_1.plus(0x4ec))); f32(readF32(f.param_1.plus(0x4ec))) }; b((f.fVar16) <= (9.5)) }) != 0))) != 0) 22 else 190
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep6(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        192 -> {
            20
        }
        193 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x4bc))) == (1))) != 0) && ((b((f.fVar10) < (9.0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x44c))) < (42.0))) != 0))) != 0) 192 else 21
        }
        194 -> {
            23
        }
        195 -> {
            21
        }
        196 -> {
            if ((b(((b(((b((f32((readF32(f.param_1.plus(0x414))) - (readF32(f.param_1.plus(0x430))))) <= (1.0))) != 0) && ((b((DAT_0012ef38) <= (f32((readF32(f.param_1.plus(0x414))) - (readF32(f.param_1.plus(0x430))))))) != 0))) != 0) || ((b((16.0) < (readF32(f.param_1.plus(0x4ec))))) != 0))) != 0) 195 else 194
        }
        197 -> {
            21
        }
        198 -> {
            if ((b((9.0) <= (f.fVar10))) != 0) 197 else 20
        }
        199 -> {
            if ((b((readF32(f.param_1.plus(0x4f4))) < (5.3))) != 0) 198 else 193
        }
        200 -> {
            22
        }
        201 -> {
            if ((b((6.0) <= (readF32(f.param_1.plus(0x468))))) != 0) 200 else 199
        }
        202 -> {
            12
        }
        203 -> {
            f.dVar15 = ((((((f.fVar18) + (20.0))) + (f.dVar15))) * (DAT_0012eef8))
            202
        }
        204 -> {
            24
        }
        205 -> {
            5
        }
        206 -> {
            if ((b((f.fVar18) < (16.5))) != 0) 205 else 204
        }
        207 -> {
            f.fVar3 = f32(0.0)
            206
        }
        208 -> {
            if ((b((15.5) <= (f.fVar18))) != 0) 207 else 18
        }
        209 -> {
            f.dVar15 = f.dVar14
            208
        }
        210 -> {
            18
        }
        211 -> {
            if ((b((f.fVar18) < (15.0))) != 0) 210 else 17
        }
        212 -> {
            f.dVar14 = DAT_0012ef00
            211
        }
        213 -> {
            f.dVar15 = DAT_0012ef08
            212
        }
        214 -> {
            10
        }
        215 -> {
            if ((b((f.fVar18) < (14.5))) != 0) 214 else 213
        }
        216 -> {
            18
        }
        217 -> {
            if ((b(((b((f.fVar18) < (13.0))) != 0) || ((run { run { f.dVar15 = DAT_0012ef10; DAT_0012ef10 }; b((f.fVar18) < (14.0)) }) != 0))) != 0) 216 else 215
        }
        218 -> {
            f.dVar15 = DAT_0012ef18
            217
        }
        219 -> {
            11
        }
        220 -> {
            f.fVar10 = f32(-(34.0))
            219
        }
        221 -> {
            if ((b((f.fVar18) < (12.0))) != 0) 16 else 218
        }
        222 -> {
            if ((b(((b(((b((9.0) <= (f.fVar18))) != 0) && ((run { run { f.dVar15 = DAT_0012ef28; DAT_0012ef28 }; b((10.0) <= (f.fVar18)) }) != 0))) != 0) && ((run { run { f.dVar15 = DAT_0012ef20; DAT_0012ef20 }; b((11.0) <= (f.fVar18)) }) != 0))) != 0) 221 else 18
        }
        223 -> {
            f.dVar15 = DAT_0012ef30
            222
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep7(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        224 -> {
            19
        }
        225 -> {
            if ((b((16.0) < (readF32(f.param_1.plus(0x4ec))))) != 0) 224 else 223
        }
        226 -> {
            19
        }
        227 -> {
            if ((b((9.0) <= (f.fVar10))) != 0) 226 else 15
        }
        228 -> {
            19
        }
        229 -> {
            15
        }
        230 -> {
            if ((b(((b((f.fVar10) < (9.0))) != 0) && ((b((readI32(f.param_1.plus(0x4bc))) == (1))) != 0))) != 0) 229 else 228
        }
        231 -> {
            if ((b(((b((11.5) <= (f.fVar20))) != 0) && ((b((5.3) <= (readF32(f.param_1.plus(0x4f4))))) != 0))) != 0) 230 else 227
        }
        232 -> {
            if ((b((f.param_5) < (0x5a5))) != 0) 231 else 19
        }
        233 -> {
            22
        }
        234 -> {
            if ((b(((b((f.fVar20) < (10.5))) != 0) && ((b((8.5) < (f.fVar10))) != 0))) != 0) 233 else 232
        }
        235 -> {
            24
        }
        236 -> {
            f.fVar3 = f32(0.2)
            235
        }
        237 -> {
            if ((b(((b((8.6) < (readF32(f.param_1.plus(0x494))))) != 0) && ((b(((b(((b((9.0) < (f.fVar10))) != 0) || ((b((f32((readF32(f.param_1.plus(0x494))) - (f.fVar10))) < (2.0))) != 0))) != 0) && ((b((10.0) < (readF32(f.param_1.plus(0x4ec))))) != 0))) != 0))) != 0) 14 else 234
        }
        238 -> {
            f.fVar10 = f32(readF32(f.param_1.plus(0x498)))
            237
        }
        239 -> {
            22
        }
        240 -> {
            24
        }
        241 -> {
            f.fVar3 = f32(0.5)
            240
        }
        242 -> {
            if ((b((readI32(f.param_1.plus(0x514))) != (1))) != 0) 241 else 240
        }
        243 -> {
            f.fVar3 = f32(0.3)
            242
        }
        244 -> {
            if ((b((readI32(f.param_1.plus(0x4d0))) < (1))) != 0) 243 else 239
        }
        245 -> {
            if ((b((0x1684) < (f.param_5))) != 0) 244 else 13
        }
        246 -> {
            if ((b((f.param_5) < (0x442))) != 0) 173 else 245
        }
        247 -> {
            24
        }
        248 -> {
            f.fVar3 = f32(0.5)
            247
        }
        249 -> {
            if ((b((f.iVar1) < (5))) != 0) 248 else 246
        }
        250 -> {
            f.iVar1 = ((((f.param_5) / (5))) - (1))
            249
        }
        251 -> {
            f.fVar20 = f32(f32((readF32(f.param_1.plus(0x4c))) / (f32((readI32(f.param_1.plus(0x48))).toDouble()))))
            250
        }
        252 -> {
            if ((b((0) < (readI32(f.param_1.plus(0x48))))) != 0) 251 else 250
        }
        253 -> {
            f.fVar19 = f32(f32((readF32(f.param_1.plus(0x50))) / (f32((readI32(f.param_1.plus(0x44))).toDouble()))))
            252
        }
        254 -> {
            if ((b((0) < (readI32(f.param_1.plus(0x44))))) != 0) 253 else 252
        }
        255 -> {
            f.fVar19 = f32(0.0)
            254
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep8(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        256 -> {
            f.fVar20 = f32(0.0)
            255
        }
        257 -> {
            writeF32(f.param_1.plus(0x50), f32(f32((f.fVar10) + (readF32(f.param_1.plus(0x50))))))
            256
        }
        258 -> {
            writeI32(f.param_1.plus(0x44), ((readI32(f.param_1.plus(0x44))) + (1)))
            257
        }
        259 -> {
            if ((b((0.0) < (f32((f.fVar10) - (f.fVar18))))) != 0) 258 else 256
        }
        260 -> {
            writeF32(f.param_1.plus(0x4c), f32(f32((f.fVar10) + (readF32(f.param_1.plus(0x4c))))))
            256
        }
        261 -> {
            writeI32(f.param_1.plus(0x48), ((readI32(f.param_1.plus(0x48))) + (1)))
            260
        }
        262 -> {
            if ((b((0.0) <= (f32((f.fVar10) - (f.fVar18))))) != 0) 259 else 261
        }
        263 -> {
            f.fVar18 = f32(((readF32(f.param_1.plus(0x9a4))) + (-(20.0))))
            262
        }
        264 -> {
            f.fVar10 = f32(((readF32(f.param_1.plus(0x9a0))) + (-(20.0))))
            263
        }
        265 -> {
            26
        }
        266 -> {
            if ((b((f.param_5) != (((((f.param_5) / (5))) * (5))))) != 0) 265 else 264
        }
        267 -> {
            writeI32(f.param_1.plus(0x2c), readI32(f.param_1.plus(0x28)))
            266
        }
        268 -> {
            f.fVar18 = f32(0.0)
            267
        }
        269 -> {
            f.fVar10 = f32(-(1.0))
            268
        }
        270 -> {
            if ((b((readI32(f.param_1.plus(0x34))) != (0))) != 0) 269 else 268
        }
        271 -> {
            f.fVar10 = f32(0.0)
            270
        }
        272 -> {
            f.fVar11 = f32(f32(temperature_compensation(f.param_1.plus(0x974), f.param_3, f.fVar10, f.iVar2)))
            271
        }
        273 -> {
            f.fVar10 = f32(f32(Kalman_Filter(f.param_1.plus(0x158), f.fVar10, f.param_5)))
            272
        }
        274 -> {
            f.fVar10 = f32(f32(Abnormal_Value_Correction(f.pjVar9, readF32(f.param_1.plus(0xdc)), f.param_2, f.iVar2)))
            273
        }
        275 -> {
            f.pjVar9 = f.param_1.plus(0xdc)
            274
        }
        276 -> {
            writeI32(f.param_1.plus(0x34), f.uVar7)
            275
        }
        277 -> {
            f.uVar7 = 1
            1
        }
        278 -> {
            3
        }
        279 -> {
            if ((b((((f.uVar6).ushr(4)).and(1)) == (0))) != 0) 278 else 0
        }
        280 -> {
            writeI32(f.param_1.plus(0x38), 0)
            279
        }
        281 -> {
            2
        }
        282 -> {
            f.uVar7 = 2
            281
        }
        283 -> {
            if ((b((((f.uVar6).ushr(1)).and(1)) != (0))) != 0) 282 else 280
        }
        284 -> {
            writeI32(f.param_1.plus(0x34), 0)
            275
        }
        285 -> {
            1
        }
        286 -> {
            f.uVar7 = 2
            285
        }
        287 -> {
            if ((b((((f.uVar6).ushr(5)).and(1)) != (0))) != 0) 286 else 284
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithmconvertprocessStep9(f: algorithmconvertprocessFrame, pc: Int): Int {
    return when (pc) {
        288 -> {
            0
        }
        289 -> {
            if ((b((((f.uVar6).ushr(4)).and(1)) != (0))) != 0) 288 else 3
        }
        290 -> {
            writeI32(f.param_1.plus(0x38), f.uVar7)
            289
        }
        291 -> {
            f.uVar7 = 1
            2
        }
        292 -> {
            if ((b(((f.uVar6).and(1)) == (0))) != 0) 283 else 291
        }
        293 -> {
            f.uVar6 = Abnormal_Judgement(f.param_1.plus(0x78), f.param_2, f.param_3, f.iVar2)
            292
        }
        294 -> {
            f.result = f32(0.0)
            C_DONE
        }
        295 -> {
            if ((b((0) == 0 && (f.param_1).bytes.isEmpty())) != 0) 294 else 293
        }
        296 -> {
            f.result = f32(-(1.0))
            C_DONE
        }
        297 -> {
            if ((b((f.param_5) < (1))) != 0) 296 else 295
        }
        298 -> {
            f.iVar2 = ((f.param_5) + (-(1)))
            297
        }
        else -> error("bad C state: $pc")
    }
}

private fun algorithm_convert_process(param_1: Ptr, param_2: Double, param_3: Double, param_4: Double, param_5: Int, param_6: Ptr, param_7: Double, param_8: Double): Double {
    val f = algorithmconvertprocessFrame(param_1, f32(param_2), f32(param_3), f32(param_4), param_5, param_6, f32(param_7), f32(param_8))
    var pc = 298
    while (true) {
        pc = when (pc / 32) {
            0 -> algorithmconvertprocessStep0(f, pc)
            1 -> algorithmconvertprocessStep1(f, pc)
            2 -> algorithmconvertprocessStep2(f, pc)
            3 -> algorithmconvertprocessStep3(f, pc)
            4 -> algorithmconvertprocessStep4(f, pc)
            5 -> algorithmconvertprocessStep5(f, pc)
            6 -> algorithmconvertprocessStep6(f, pc)
            7 -> algorithmconvertprocessStep7(f, pc)
            8 -> algorithmconvertprocessStep8(f, pc)
            9 -> algorithmconvertprocessStep9(f, pc)
            else -> error("bad C chunk: $pc")
        }
        if (pc == C_DONE) {
            return f.result
        }
    }
}

private class CalibrationbaselineFrame(var param_1: Ptr, var param_2: Double, var param_3: Double, var param_4: Double, var param_5: Double, var param_6: Double, var param_7: Double, var param_8: Double, var param_9: Int) {
    var iVar1: Int = 0
    var bVar2: Int = 0
    var fVar3: Double = 0.0
    var fVar4: Double = 0.0
    var fVar5: Double = 0.0
    var fVar6: Double = 0.0
    var result: Int = 0
}

private fun CalibrationbaselineStep0(f: CalibrationbaselineFrame, pc: Int): Int {
    return when (pc) {
        0 -> {
            30
        }
        1 -> {
            24
        }
        2 -> {
            14
        }
        3 -> {
            5
        }
        4 -> {
            f.result = readI32(f.param_1.plus(0x28))
            C_DONE
        }
        5 -> {
            writeF32(f.param_1.plus(4), f32(f.fVar3))
            4
        }
        6 -> {
            writeI32(f.param_1.plus(0x40), 0)
            3
        }
        7 -> {
            writeI32(f.param_1.plus(0x48), ((readI32(f.param_1.plus(0x48))) + (1)))
            6
        }
        8 -> {
            if ((b((4.0) < (readF32(f.param_1.plus(0x40))))) != 0) 7 else 6
        }
        9 -> {
            writeF32(f.param_1.plus(0x40), f32(((f.fVar3) * (30.0))))
            3
        }
        10 -> {
            if ((b((((f.fVar3) * (30.0))) <= (readF32(f.param_1.plus(0x40))))) != 0) 8 else 9
        }
        11 -> {
            if ((b(((b((0.0) < (f.fVar5))) != 0) && ((b((f.fVar5) < (f.fVar3))) != 0))) != 0) 10 else 3
        }
        12 -> {
            f.fVar5 = f32(readF32(f.param_1.plus(4)))
            11
        }
        13 -> {
            3
        }
        14 -> {
            if ((b((f.fVar3) <= (0.0))) != 0) 13 else 12
        }
        15 -> {
            writeI32(f.param_1.plus(0x34), 0)
            2
        }
        16 -> {
            writeI32(f.param_1.plus(0x20), 0)
            15
        }
        17 -> {
            writeI32(f.param_1.plus(0x18), 0)
            16
        }
        18 -> {
            1
        }
        19 -> {
            f.fVar6 = f32(f32((f.param_4) - (readF32(f.param_1.plus(0x20)))))
            18
        }
        20 -> {
            if ((b(((b((0x11f) < (f.param_9))) != 0) && ((b((4.0) < (f32((readF32(f.param_1.plus(0x3c))) - (readF32(f.param_1.plus(0x38))))))) != 0))) != 0) 19 else 17
        }
        21 -> {
            2
        }
        22 -> {
            writeI64(f.param_1.plus(0x30), (0).toLong())
            21
        }
        23 -> {
            writeI32(f.param_1.plus(8), 1)
            22
        }
        24 -> {
            if ((b((f.fVar6) < (1.0))) != 0) 23 else 17
        }
        25 -> {
            f.fVar6 = f32(f32((f.param_3) - (readF32(f.param_1.plus(0x20)))))
            1
        }
        26 -> {
            if ((b(((b((0x11f) < (f.param_9))) != 0) || ((b((f32((readF32(f.param_1.plus(0x3c))) - (readF32(f.param_1.plus(0x38))))) <= (4.0))) != 0))) != 0) 20 else 25
        }
        27 -> {
            if ((b(((b(!((b(((b((0.0) <= (f.fVar3))).or((f.bVar2).xor(1))) != 0)) != 0))) != 0) && ((b((0.0) < (readF32(f.param_1.plus(4))))) != 0))) != 0) 26 else 2
        }
        28 -> {
            writeF32(f.param_1.plus(0x30), f32(((f.fVar3) * (30.0))))
            2
        }
        29 -> {
            if ((b((((f.fVar3) * (30.0))) < (readF32(f.param_1.plus(0x30))))) != 0) 28 else 2
        }
        30 -> {
            if ((b(((b(((b((0.0) <= (f.fVar3))) != 0) || ((b((readI32(f.param_1.plus(8))) != (1))) != 0))) != 0) || ((b((0.0) <= (readF32(f.param_1.plus(4))))) != 0))) != 0) 27 else 29
        }
        31 -> {
            f.bVar2 = b((b((readI32(f.param_1.plus(0x34))) == (1))) != 0)
            0
        }
        else -> error("bad C state: $pc")
    }
}

private fun CalibrationbaselineStep1(f: CalibrationbaselineFrame, pc: Int): Int {
    return when (pc) {
        32 -> {
            writeF32(f.param_1.plus(0x3c), f32(((f.fVar3) * (30.0))))
            11
        }
        33 -> {
            if ((b((readF32(f.param_1.plus(0x3c))) < (((f.fVar3) * (30.0))))) != 0) 32 else 11
        }
        34 -> {
            0
        }
        35 -> {
            if ((b(((b(((b((f.fVar3) <= (0.0))) != 0) || ((b((readI32(f.param_1.plus(0x34))) != (1))) != 0))) != 0) || ((run { run { f.fVar5 = f32(readF32(f.param_1.plus(4))); f32(readF32(f.param_1.plus(4))) }; b((f.fVar5) <= (0.0)) }) != 0))) != 0) 34 else 33
        }
        36 -> {
            f.bVar2 = b((b((readI32(f.param_1.plus(0x34))) == (1))) != 0)
            35
        }
        37 -> {
            writeI32(f.param_1.plus(0x3c), 0)
            11
        }
        38 -> {
            writeI32(f.param_1.plus(0x34), 1)
            37
        }
        39 -> {
            writeI32(f.param_1.plus(0x18), ((f.param_9) + (-(1))))
            38
        }
        40 -> {
            writeF32(f.param_1.plus(0x38), f32(f.fVar5))
            39
        }
        41 -> {
            writeF32(f.param_1.plus(0x20), f32(f.fVar6))
            40
        }
        42 -> {
            if ((b(((b((0.0) < (f.fVar5))) != 0) || ((b((readI32(f.param_1.plus(0x34))) != (0))) != 0))) != 0) 36 else 41
        }
        43 -> {
            writeI32(f.param_1.plus(0x2c), 1)
            11
        }
        44 -> {
            writeF32(f.param_1.plus(0x44), f32(f.fVar6))
            43
        }
        45 -> {
            if ((b(((b((f.param_5) < (f.fVar6))) != 0) && ((b((f.fVar6) < (f.param_8))) != 0))) != 0) 44 else 11
        }
        46 -> {
            writeF32(f.param_1.plus(0x28), f32(f32((f.fVar4) / (f32((((f.iVar1) + (1))).toDouble())))))
            45
        }
        47 -> {
            writeI32(f.param_1.plus(0xc), ((f.iVar1) + (1)))
            46
        }
        48 -> {
            writeI32(f.param_1.plus(8), 0)
            47
        }
        49 -> {
            writeF32(f.param_1.plus(0x1c), f32(f.fVar6))
            48
        }
        50 -> {
            writeF32(f.param_1.plus(0x10), f32(f.fVar4))
            49
        }
        51 -> {
            f.fVar4 = f32(f32((f32((readF32(f.param_1.plus(0x10))) + (f32((((f.param_9) + (-(1)))).toDouble())))) - (f32((readI32(f.param_1.plus(0x18))).toDouble()))))
            50
        }
        52 -> {
            writeI32(f.param_1.plus(0x14), ((f.param_9) + (-(1))))
            51
        }
        53 -> {
            f.iVar1 = readI32(f.param_1.plus(0xc))
            52
        }
        54 -> {
            if ((b(((b(((b((f.fVar4) < (-(5.0)))) != 0) || ((b((kotlin.math.abs(f32((f.fVar6) - (readF32(f.param_1.plus(0x20)))))) < (2.0))) != 0))) != 0) && ((b((f.fVar6) < (((f.param_6) * (DAT_0012ef70))))) != 0))) != 0) 53 else 11
        }
        55 -> {
            writeF32(f.param_1.plus(0x28), f32(f32((f.fVar6) / (f32((((f.iVar1) + (1))).toDouble())))))
            11
        }
        56 -> {
            writeI32(f.param_1.plus(0xc), ((f.iVar1) + (1)))
            55
        }
        57 -> {
            writeI32(f.param_1.plus(8), 0)
            56
        }
        58 -> {
            writeF32(f.param_1.plus(0x10), f32(f.fVar6))
            57
        }
        59 -> {
            f.fVar6 = f32(f32((f32((readF32(f.param_1.plus(0x10))) + (f32((((f.param_9) + (-(1)))).toDouble())))) - (f32((readI32(f.param_1.plus(0x18))).toDouble()))))
            58
        }
        60 -> {
            writeF32(f.param_1.plus(0x1c), f32(f.fVar6))
            59
        }
        61 -> {
            writeI32(f.param_1.plus(0x14), ((f.param_9) + (-(1))))
            60
        }
        62 -> {
            f.iVar1 = readI32(f.param_1.plus(0xc))
            61
        }
        63 -> {
            if ((b(((b(((b((f.fVar4) < (-(5.0)))) != 0) || ((b((kotlin.math.abs(f32((f.fVar6) - (readF32(f.param_1.plus(0x20)))))) < (1.5))) != 0))) != 0) && ((b(((b(((b((f.fVar4) < (-(2.0)))) != 0) && ((b((f.fVar6) < (((f.param_7) * (0.7))))) != 0))) != 0) || ((b(((b(((b((f.fVar4) < (-(5.0)))) != 0) && ((b((f.fVar4) < (-(2.0)))) != 0))) != 0) && ((b((f32((f.fVar6) - (readF32(f.param_1.plus(0x20))))) < (1.5))) != 0))) != 0))) != 0))) != 0) 62 else 11
        }
        else -> error("bad C state: $pc")
    }
}

private fun CalibrationbaselineStep2(f: CalibrationbaselineFrame, pc: Int): Int {
    return when (pc) {
        64 -> {
            if ((b((f.param_9) < (0x240))) != 0) 54 else 63
        }
        65 -> {
            f.fVar4 = f32(f32((readF32(f.param_1.plus(0x30))) - (f.fVar3)))
            64
        }
        66 -> {
            if ((b(((b((0.0) <= (f.fVar5))) != 0) || ((b((readI32(f.param_1.plus(8))) != (1))) != 0))) != 0) 42 else 65
        }
        67 -> {
            f.fVar5 = f32(readF32(f.param_1.plus(4)))
            66
        }
        68 -> {
            if ((b((f.fVar3) <= (0.0))) != 0) 31 else 67
        }
        69 -> {
            3
        }
        70 -> {
            if ((b((f.param_9) < (6))) != 0) 69 else 68
        }
        71 -> {
            writeF32(f.param_1, f32(f.fVar3))
            70
        }
        72 -> {
            f.fVar3 = f32(f32((f.param_2) - (f.fVar6)))
            71
        }
        73 -> {
            f.fVar6 = f32(readF32(f.param_1.plus(0x24)))
            72
        }
        74 -> {
            3
        }
        75 -> {
            writeI32(f.param_1, 0)
            74
        }
        76 -> {
            f.fVar3 = f32(0.0)
            75
        }
        77 -> {
            if ((b((f.param_9) < (1))) != 0) 76 else 73
        }
        78 -> {
            writeI32(f.param_1, 0)
            77
        }
        79 -> {
            writeI32(f.param_1.plus(0x2c), 0)
            78
        }
        else -> error("bad C state: $pc")
    }
}

private fun Calibration_baseline(param_1: Ptr, param_2: Double, param_3: Double, param_4: Double, param_5: Double, param_6: Double, param_7: Double, param_8: Double, param_9: Int): Int {
    val f = CalibrationbaselineFrame(param_1, f32(param_2), f32(param_3), f32(param_4), f32(param_5), f32(param_6), f32(param_7), f32(param_8), param_9)
    var pc = 79
    while (true) {
        pc = when (pc / 32) {
            0 -> CalibrationbaselineStep0(f, pc)
            1 -> CalibrationbaselineStep1(f, pc)
            2 -> CalibrationbaselineStep2(f, pc)
            else -> error("bad C chunk: $pc")
        }
        if (pc == C_DONE) {
            return f.result
        }
    }
}

private class adjustmentrangeFrame(var param_1: Ptr, var param_2: Double, var param_3: Double, var param_4: Double, var param_5: Double, var param_6: Double, var param_7: Double, var param_8: Double, var param_9: Double, var param_10: Double, var param_11: Double, var param_12: Double, var param_13: Double, var param_14: Int, var param_15: Double, var param_16: Double, var param_17: Double, var param_18: Double, var param_19: Double, var param_20: Double, var param_21: Double, var param_22: Double, var param_23: Double, var param_24: Double, var param_25: Double, var param_26: Int, var param_27: Double, var param_28: Double, var param_29: Double, var param_30: Double, var param_31: Double) {
    var iVar1: Int = 0
    var bVar2: Int = 0
    var bVar3: Int = 0
    var bVar4: Int = 0
    var bVar5: Int = 0
    var bVar6: Int = 0
    var bVar7: Int = 0
    var bVar8: Int = 0
    var bVar9: Int = 0
    var fVar10: Double = 0.0
    var dVar11: Double = 0.0
    var fVar12: Double = 0.0
    var fVar13: Double = 0.0
    var fVar14: Double = 0.0
    var fVar15: Double = 0.0
    var fVar16: Double = 0.0
    var fVar17: Double = 0.0
    var fVar18: Double = 0.0
    var fVar19: Double = 0.0
    var fVar20: Double = 0.0
    var fVar21: Double = 0.0
    var fVar22: Double = 0.0
    var fVar23: Double = 0.0
    var fVar24: Double = 0.0
    var fVar25: Double = 0.0
    var fVar26: Double = 0.0
    var fVar27: Double = 0.0
    var fVar28: Double = 0.0
    var fVar29: Double = 0.0
    var fVar30: Double = 0.0
    var dVar31: Double = 0.0
    var dVar32: Double = 0.0
    var dVar33: Double = 0.0
    var fVar34: Double = 0.0
    var dVar35: Double = 0.0
    var fVar36: Double = 0.0
    var fVar37: Double = 0.0
    var dVar38: Double = 0.0
    var fVar39: Double = 0.0
    var fVar40: Double = 0.0
    var fVar41: Double = 0.0
    var dVar42: Double = 0.0
    var fVar43: Double = 0.0
    var dVar44: Double = 0.0
    var dVar45: Double = 0.0
    var dVar46: Double = 0.0
    var dVar47: Double = 0.0
    var dVar48: Double = 0.0
    var fVar49: Double = 0.0
    var dVar50: Double = 0.0
    var fVar51: Double = 0.0
    var fVar52: Double = 0.0
    var dVar53: Double = 0.0
    var fVar54: Double = 0.0
    var fVar55: Double = 0.0
    var fVar56: Double = 0.0
    var dVar57: Double = 0.0
    var fVar58: Double = 0.0
    var dVar59: Double = 0.0
    var result: Double = 0.0
}

private fun adjustmentrangeStep0(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        0 -> {
            1572
        }
        1 -> {
            1581
        }
        2 -> {
            1571
        }
        3 -> {
            1567
        }
        4 -> {
            1320
        }
        5 -> {
            1414
        }
        6 -> {
            1413
        }
        7 -> {
            1412
        }
        8 -> {
            1125
        }
        9 -> {
            1145
        }
        10 -> {
            1187
        }
        11 -> {
            1165
        }
        12 -> {
            1162
        }
        13 -> {
            1154
        }
        14 -> {
            1193
        }
        15 -> {
            1225
        }
        16 -> {
            1246
        }
        17 -> {
            1259
        }
        18 -> {
            1263
        }
        19 -> {
            1273
        }
        20 -> {
            1270
        }
        21 -> {
            1288
        }
        22 -> {
            1297
        }
        23 -> {
            1295
        }
        24 -> {
            1099
        }
        25 -> {
            1077
        }
        26 -> {
            1064
        }
        27 -> {
            1032
        }
        28 -> {
            1031
        }
        29 -> {
            1000
        }
        30 -> {
            998
        }
        31 -> {
            950
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep1(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        32 -> {
            949
        }
        33 -> {
            948
        }
        34 -> {
            947
        }
        35 -> {
            880
        }
        36 -> {
            864
        }
        37 -> {
            862
        }
        38 -> {
            699
        }
        39 -> {
            652
        }
        40 -> {
            638
        }
        41 -> {
            620
        }
        42 -> {
            591
        }
        43 -> {
            590
        }
        44 -> {
            532
        }
        45 -> {
            531
        }
        46 -> {
            530
        }
        47 -> {
            420
        }
        48 -> {
            409
        }
        49 -> {
            369
        }
        50 -> {
            368
        }
        51 -> {
            362
        }
        52 -> {
            299
        }
        53 -> {
            298
        }
        54 -> {
            665
        }
        55 -> {
            231
        }
        56 -> {
            228
        }
        57 -> {
            809
        }
        58 -> {
            174
        }
        59 -> {
            172
        }
        60 -> {
            76
        }
        61 -> {
            67
        }
        62 -> {
            65
        }
        63 -> {
            64
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep2(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        64 -> {
            f.result = f32(f32(f.dVar31))
            C_DONE
        }
        65 -> {
            f.result = f32(f32(((f.dVar57) * (0.25))))
            C_DONE
        }
        66 -> {
            if ((b(((b((f.param_4) < (3.0))) != 0) || ((b(((b((f.param_5) < (3.0))) != 0) && ((b(((b(!((f.bVar6) != 0))) != 0) && ((b((f.bVar4) == (f.bVar2))) != 0))) != 0))) != 0))) != 0) 62 else 63
        }
        67 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            66
        }
        68 -> {
            f.bVar2 = b((0) != 0)
            61
        }
        69 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            68
        }
        70 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            69
        }
        71 -> {
            if ((b(((b(!((b((f.dVar57).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012ee78).isNaN())) != 0))) != 0))) != 0) 70 else 61
        }
        72 -> {
            f.bVar2 = b((1) != 0)
            71
        }
        73 -> {
            f.bVar6 = b((0) != 0)
            72
        }
        74 -> {
            f.bVar4 = b((0) != 0)
            73
        }
        75 -> {
            35
        }
        76 -> {
            if ((b((f.dVar35) < (3.9))) != 0) 75 else 74
        }
        77 -> {
            62
        }
        78 -> {
            if ((b(((b((((f.dVar48) + (f.dVar46))) < (DAT_0012f050))) != 0) && ((b(((b(((b((((f.dVar48) + (f.dVar46))) < (3.0))) != 0) || ((b((((f.dVar48) + (f.dVar38))) < (3.9))) != 0))) != 0) || ((b((((f.dVar48) + (f.dVar53))) < (3.9))) != 0))) != 0))) != 0) 77 else 60
        }
        79 -> {
            f.dVar48 = ((f.dVar57) * (0.25))
            78
        }
        80 -> {
            63
        }
        81 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f050))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0) || ((b((((f.dVar31) + (f.dVar38))) < (3.9))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0))) != 0))) != 0) 80 else 79
        }
        82 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            81
        }
        83 -> {
            63
        }
        84 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f050))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0) || ((b((((f.dVar31) + (f.dVar38))) < (3.9))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0))) != 0))) != 0) 83 else 82
        }
        85 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            84
        }
        86 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        87 -> {
            if ((b((f.fVar30) < (3.9))) != 0) 86 else 85
        }
        88 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        89 -> {
            if ((b((f.fVar58) < (3.0))) != 0) 88 else 87
        }
        90 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        91 -> {
            if ((b((f.fVar24) < (3.5))) != 0) 90 else 89
        }
        92 -> {
            if ((b((f.fVar58) < (DAT_0012f050))) != 0) 91 else 85
        }
        93 -> {
            36
        }
        94 -> {
            f.bVar2 = b((0) != 0)
            93
        }
        95 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            94
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep3(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        96 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            95
        }
        97 -> {
            if ((b(((b(!((b((f.dVar57).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012ee78).isNaN())) != 0))) != 0))) != 0) 96 else 93
        }
        98 -> {
            f.bVar2 = b((1) != 0)
            97
        }
        99 -> {
            f.bVar6 = b((0) != 0)
            98
        }
        100 -> {
            f.bVar4 = b((0) != 0)
            99
        }
        101 -> {
            37
        }
        102 -> {
            if ((b((f.dVar35) < (3.9))) != 0) 101 else 100
        }
        103 -> {
            30
        }
        104 -> {
            if ((b((((f.dVar32) + (f.dVar46))) < (3.5))) != 0) 103 else 102
        }
        105 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            104
        }
        106 -> {
            63
        }
        107 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((((f.dVar31) + (f.dVar46))) < (3.5)) }) != 0))) != 0) 106 else 105
        }
        108 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            107
        }
        109 -> {
            if ((b(((b(((b((f.param_16) <= (f.param_5))) != 0) || ((b((f.param_16) <= (f.param_3))) != 0))) != 0) || ((b((f.param_16) <= (f.param_4))) != 0))) != 0) 108 else 92
        }
        110 -> {
            30
        }
        111 -> {
            62
        }
        112 -> {
            30
        }
        113 -> {
            62
        }
        114 -> {
            if ((b(((b((f.param_4) < (3.0))) != 0) || ((b(((b((f.param_5) < (3.0))) != 0) && ((b((DAT_0012ee78) < (f.dVar57))) != 0))) != 0))) != 0) 113 else 112
        }
        115 -> {
            62
        }
        116 -> {
            if ((b((DAT_0012ee78) <= (f.dVar57))) != 0) 115 else 112
        }
        117 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 114 else 116
        }
        118 -> {
            if ((b(((b((DAT_0012f030) <= (((f.dVar48) + (f.dVar46))))) != 0) || ((b(((b(((b((3.5) <= (((f.dVar48) + (f.dVar38))))) != 0) && ((b((DAT_0012f030) <= (((f.dVar48) + (f.dVar53))))) != 0))) != 0) && ((b((3.0) <= (((f.dVar48) + (f.dVar46))))) != 0))) != 0))) != 0) 117 else 111
        }
        119 -> {
            f.dVar48 = ((f.dVar57) * (0.25))
            118
        }
        120 -> {
            30
        }
        121 -> {
            if ((b(((b((f.dVar31) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar32) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar32) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) || ((b((f.dVar31) < (3.0))) != 0))) != 0))) != 0) 120 else 119
        }
        122 -> {
            f.result = f32(f32(f.dVar42))
            C_DONE
        }
        123 -> {
            if ((b(((b(((b(((b((f.fVar28) <= (DAT_0012ee78))) != 0) && ((b((f.fVar27) <= (DAT_0012ee78))) != 0))) != 0) && ((b(((b((f.fVar23) <= (DAT_0012ee78))) != 0) || ((b(((b((f.fVar22) <= (2.0))) != 0) || ((b((3.9) <= (f.dVar48))) != 0))) != 0))) != 0))) != 0) || ((b(((b((f.dVar50) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar42) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar42) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) || ((b((f.dVar50) < (3.0))) != 0))) != 0))) != 0))) != 0) 122 else 121
        }
        124 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        125 -> {
            if ((b(((b((f.dVar50) < (f.dVar48))) != 0) && ((b((((f.dVar42) + (f.dVar53))) < (f.dVar48))) != 0))) != 0) 124 else 123
        }
        126 -> {
            f.dVar50 = ((f.dVar42) + (f.dVar46))
            125
        }
        127 -> {
            f.dVar42 = ((f.dVar57) * (0.75))
            126
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep4(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        128 -> {
            if ((b(((b((f.dVar31) < (f.dVar48))) != 0) || ((b((((f.dVar32) + (f.dVar53))) < (f.dVar48))) != 0))) != 0) 127 else 110
        }
        129 -> {
            f.dVar31 = ((f.dVar32) + (f.dVar46))
            128
        }
        130 -> {
            f.dVar32 = ((f.dVar57) * (0.5))
            129
        }
        131 -> {
            59
        }
        132 -> {
            if ((b(((b(((b((2.5) <= (f.param_3))) != 0) && ((b((2.5) <= (f.param_4))) != 0))) != 0) && ((b((2.5) <= (f.param_5))) != 0))) != 0) 131 else 130
        }
        133 -> {
            30
        }
        134 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        135 -> {
            if ((b((f.dVar57) < (DAT_0012ef38))) != 0) 134 else 133
        }
        136 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        137 -> {
            if ((b((((f.dVar32) + (f.dVar46))) < (3.0))) != 0) 136 else 133
        }
        138 -> {
            f.dVar32 = ((f.dVar57) * (0.75))
            137
        }
        139 -> {
            63
        }
        140 -> {
            if ((b((3.0) <= (((f.dVar31) + (f.dVar46))))) != 0) 139 else 138
        }
        141 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            140
        }
        142 -> {
            if ((b((DAT_0012f030) <= (((f.dVar32) + (f.dVar46))))) != 0) 135 else 141
        }
        143 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            142
        }
        144 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        145 -> {
            if ((b(((b(((b((f.fVar28) <= (0.7))) != 0) && ((b((f.fVar27) <= (0.7))) != 0))) != 0) && ((b((f.fVar23) <= (0.7))) != 0))) != 0) 144 else 143
        }
        146 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        147 -> {
            if ((b((3.9) <= (f.dVar48))) != 0) 146 else 145
        }
        148 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        149 -> {
            if ((b((f.dVar44) <= (1.2))) != 0) 148 else 147
        }
        150 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        151 -> {
            if ((b((f.dVar48) <= (2.8))) != 0) 150 else 149
        }
        152 -> {
            33
        }
        153 -> {
            if ((b(((b(((b(((b(((b((60.0) < (f.param_20))) != 0) && ((b((3.0) < (f.param_16))) != 0))) != 0) && ((b(((b((DAT_0012f058) < (f.dVar57))) != 0) && ((b(((b(((b((f.param_3) < (2.0))) != 0) || ((b((f.param_4) < (2.0))) != 0))) != 0) || ((b(((b((f.param_5) < (2.0))) != 0) && ((b((3.0) < (f.fVar36))) != 0))) != 0))) != 0))) != 0))) != 0) || ((b(((b((4.5) < (f.param_16))) != 0) && ((b(((b(((b((60.0) < (f.param_20))) != 0) && ((b(((b(((b((f.dVar46) < (2.8))) != 0) || ((b((f.dVar53) < (2.8))) != 0))) != 0) || ((b((f.dVar38) < (2.8))) != 0))) != 0))) != 0) && ((b((3.0) < (f.fVar36))) != 0))) != 0))) != 0))) != 0) || ((b(((b((3.0) < (f.param_16))) != 0) && ((b(((b(((b(((b((9.0) < (f.param_23))) != 0) || ((b(((b((DAT_0012f070) < (f.dVar44))) != 0) && ((b((7.8) < (f.dVar59))) != 0))) != 0))) != 0) && ((b((f.dVar48) < (3.8))) != 0))) != 0) && ((b((DAT_0012ee90) < (f.dVar44))) != 0))) != 0))) != 0))) != 0) 152 else 151
        }
        154 -> {
            if ((b(((b(((b((3.9) <= (f.dVar46))) != 0) || ((b((f.dVar57) <= (DAT_0012f058))) != 0))) != 0) || ((b(((b((DAT_0012f048) <= (f.dVar48))) != 0) || ((b(((b(((b((f.fVar22) <= (1.5))) != 0) || ((b(((b(((b((3.5) <= (f.param_3))) != 0) && ((b((3.5) <= (f.param_4))) != 0))) != 0) && ((b((3.5) <= (f.param_5))) != 0))) != 0))) != 0) || ((b((f.fVar36) <= (3.0))) != 0))) != 0))) != 0))) != 0) 153 else 132
        }
        155 -> {
            63
        }
        156 -> {
            32
        }
        157 -> {
            if ((b((((f.dVar31) + (f.dVar46))) <= (3.5))) != 0) 156 else 155
        }
        158 -> {
            f.dVar31 = ((f.dVar57) * (0.25))
            157
        }
        159 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep5(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        160 -> {
            if ((b((f.fVar58) <= (3.5))) != 0) 159 else 158
        }
        161 -> {
            f.result = f32(0.0)
            C_DONE
        }
        162 -> {
            if ((b(((b((3.5) <= (f.fVar58))) != 0) && ((b((3.5) <= (f.fVar24))) != 0))) != 0) 161 else 160
        }
        163 -> {
            if ((b(((b((DAT_0012f048) < (f.dVar31))) != 0) && ((b(((b((DAT_0012f058) < (f.dVar57))) != 0) && ((b(((b(((b((f.dVar46) < (3.3))) != 0) || ((b((f.dVar53) < (3.3))) != 0))) != 0) || ((b((f.dVar38) < (3.3))) != 0))) != 0))) != 0))) != 0) 162 else 154
        }
        164 -> {
            30
        }
        165 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        166 -> {
            if ((b((((f.dVar32) + (f.dVar46))) <= (3.0))) != 0) 165 else 164
        }
        167 -> {
            f.dVar32 = ((f.dVar57) * (0.75))
            166
        }
        168 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        169 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) <= (DAT_0012f098))) != 0) 168 else 167
        }
        170 -> {
            if ((b(((b((0.35) < (f.dVar47))) != 0) && ((b((f.fVar36) < (4.0))) != 0))) != 0) 169 else 163
        }
        171 -> {
            if ((b(((b(((b((3.9) <= (f.dVar38))) != 0) || ((b((3.9) <= (f.dVar46))) != 0))) != 0) || ((b(((b((3.9) <= (f.dVar53))) != 0) || ((b((f.dVar31) <= (3.9))) != 0))) != 0))) != 0) 170 else 109
        }
        172 -> {
            f.result = f32(((f.fVar54) * (0.75)))
            C_DONE
        }
        173 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        174 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) <= (4.8))) != 0) 173 else 59
        }
        175 -> {
            60
        }
        176 -> {
            62
        }
        177 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (DAT_0012f030))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) || ((b((f.dVar48) < (2.8))) != 0))) != 0))) != 0) 176 else 175
        }
        178 -> {
            f.dVar31 = ((f.dVar57) * (0.25))
            177
        }
        179 -> {
            63
        }
        180 -> {
            if ((b(((b((f.dVar48) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (DAT_0012f030))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) || ((b((f.dVar48) < (2.8))) != 0))) != 0))) != 0) 179 else 178
        }
        181 -> {
            f.dVar48 = ((f.dVar31) + (f.dVar46))
            180
        }
        182 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            181
        }
        183 -> {
            63
        }
        184 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (DAT_0012f030))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (2.8))) != 0))) != 0))) != 0) 183 else 182
        }
        185 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            184
        }
        186 -> {
            31
        }
        187 -> {
            33
        }
        188 -> {
            32
        }
        189 -> {
            if ((b((f.dVar57) < (DAT_0012ee78))) != 0) 188 else 187
        }
        190 -> {
            if ((b((f.dVar35) < (3.9))) != 0) 189 else 186
        }
        191 -> {
            63
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep6(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        192 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0))) != 0))) != 0) 191 else 190
        }
        193 -> {
            f.dVar31 = ((f.dVar57) * (0.25))
            192
        }
        194 -> {
            63
        }
        195 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0))) != 0))) != 0) 194 else 193
        }
        196 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            195
        }
        197 -> {
            63
        }
        198 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0))) != 0))) != 0) 197 else 196
        }
        199 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            198
        }
        200 -> {
            if ((b(((b((f.dVar46) < (DAT_0012f038))) != 0) || ((b((f.param_16) < (2.5))) != 0))) != 0) 199 else 190
        }
        201 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        202 -> {
            if ((b((((((f.dVar57) * (0.75))) + (f.dVar46))) < (3.0))) != 0) 201 else 200
        }
        203 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        204 -> {
            if ((b((f.fVar30) < (3.5))) != 0) 203 else 202
        }
        205 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        206 -> {
            if ((b((f.fVar24) < (3.5))) != 0) 205 else 204
        }
        207 -> {
            if ((b(((b(((b((f.dVar46) < (DAT_0012f038))) != 0) || ((b((f.param_16) < (2.5))) != 0))) != 0) && ((b((f.fVar58) < (3.5))) != 0))) != 0) 206 else 200
        }
        208 -> {
            if ((b((6.0) < (f.param_23))) != 0) 207 else 190
        }
        209 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        210 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) <= (DAT_0012f038))) != 0) 209 else 208
        }
        211 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        212 -> {
            if ((b((f.fVar58) <= (DAT_0012f030))) != 0) 211 else 210
        }
        213 -> {
            32
        }
        214 -> {
            if ((b(((b(((b(((b((2.3) < (f.param_17))) != 0) && ((b((f.dVar48) < (DAT_0012f070))) != 0))) != 0) && ((b((1.0) < (f.fVar22))) != 0))) != 0) && ((b((f.dVar47) < (0.3))) != 0))) != 0) 213 else 212
        }
        215 -> {
            if ((b(((b((f.fVar58) < (3.5))) != 0) || ((b((f.fVar30) < (3.5))) != 0))) != 0) 214 else 185
        }
        216 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        217 -> {
            if ((b((f.dVar57) <= (0.7))) != 0) 216 else 215
        }
        218 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        219 -> {
            if ((b((f.param_23) <= (7.0))) != 0) 218 else 217
        }
        220 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        221 -> {
            if ((b((DAT_0012f070) <= (f.dVar32))) != 0) 220 else 219
        }
        222 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        223 -> {
            if ((b((f.dVar44) <= (DAT_0012ef38))) != 0) 222 else 221
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep7(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        224 -> {
            if ((b((f.dVar44) <= (0.7))) != 0) 223 else 217
        }
        225 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        226 -> {
            if ((b((3.9) <= (f.dVar48))) != 0) 225 else 224
        }
        227 -> {
            if ((b(((b((f.param_20) <= (60.0))) != 0) || ((b((f.dVar47) <= (0.3))) != 0))) != 0) 226 else 58
        }
        228 -> {
            f.result = f32(f32(f.dVar31))
            C_DONE
        }
        229 -> {
            55
        }
        230 -> {
            if ((b(((b((f.param_4) < (3.0))) != 0) || ((b(((b((f.param_5) < (3.0))) != 0) && ((b((DAT_0012ee78) < (f.dVar57))) != 0))) != 0))) != 0) 229 else 56
        }
        231 -> {
            f.result = f32(f32(f.dVar48))
            C_DONE
        }
        232 -> {
            if ((b((DAT_0012ee78) <= (f.dVar57))) != 0) 55 else 56
        }
        233 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 230 else 232
        }
        234 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            233
        }
        235 -> {
            f.dVar48 = ((f.dVar57) * (0.25))
            234
        }
        236 -> {
            55
        }
        237 -> {
            if ((b(((b((f.dVar46) < (3.5))) != 0) && ((b(((b((f.dVar53) < (3.9))) != 0) || ((b((f.dVar46) < (3.3))) != 0))) != 0))) != 0) 236 else 235
        }
        238 -> {
            f.dVar48 = ((f.dVar57) * (0.25))
            237
        }
        239 -> {
            56
        }
        240 -> {
            if ((b(((b((f.dVar53) < (3.9))) != 0) || ((run { run { f.dVar46 = ((((f.dVar57) * (0.25))) + (f.dVar46)); ((((f.dVar57) * (0.25))) + (f.dVar46)) }; b((f.dVar46) < (3.3)) }) != 0))) != 0) 239 else 238
        }
        241 -> {
            f.dVar46 = ((((f.dVar57) * (0.25))) + (f.dVar46))
            238
        }
        242 -> {
            if ((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) 240 else 241
        }
        243 -> {
            55
        }
        244 -> {
            if ((b(((b((((f.dVar48) + (f.dVar46))) < (3.5))) != 0) && ((b(((b((f.dVar53) < (3.9))) != 0) || ((b((((((f.dVar57) * (0.25))) + (f.dVar46))) < (3.3))) != 0))) != 0))) != 0) 243 else 242
        }
        245 -> {
            f.dVar53 = ((f.dVar31) + (f.dVar53))
            244
        }
        246 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            245
        }
        247 -> {
            f.dVar48 = ((f.dVar57) * (0.75))
            246
        }
        248 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        249 -> {
            if ((b((f.fVar58) < (3.3))) != 0) 248 else 247
        }
        250 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        251 -> {
            if ((b((f.fVar30) < (3.9))) != 0) 250 else 249
        }
        252 -> {
            if ((b((f.fVar58) < (3.5))) != 0) 251 else 247
        }
        253 -> {
            52
        }
        254 -> {
            56
        }
        255 -> {
            52
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep8(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        256 -> {
            if ((b((DAT_0012ee78) <= (f.dVar57))) != 0) 255 else 254
        }
        257 -> {
            52
        }
        258 -> {
            56
        }
        259 -> {
            if ((b(((b((3.0) <= (f.param_4))) != 0) && ((b(((b((3.0) <= (f.param_5))) != 0) || ((b((f.dVar57) <= (DAT_0012ee78))) != 0))) != 0))) != 0) 258 else 257
        }
        260 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 259 else 256
        }
        261 -> {
            if ((b(((b((DAT_0012f050) <= (f.dVar38))) != 0) || ((b((3.9) <= (((f.dVar32) + (f.dVar53))))) != 0))) != 0) 260 else 253
        }
        262 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            261
        }
        263 -> {
            56
        }
        264 -> {
            if ((b(((b(((b((f.dVar48) < (DAT_0012f050))) != 0) || ((run { run { f.dVar38 = ((((f.dVar57) * (0.25))) + (f.dVar46)); ((((f.dVar57) * (0.25))) + (f.dVar46)) }; b((f.dVar38) < (DAT_0012f030)) }) != 0))) != 0) && ((b(((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0) || ((run { run { f.dVar38 = ((((f.dVar57) * (0.25))) + (f.dVar46)); ((((f.dVar57) * (0.25))) + (f.dVar46)) }; b((f.dVar38) < (3.0)) }) != 0))) != 0))) != 0) 263 else 262
        }
        265 -> {
            52
        }
        266 -> {
            if ((b(((b(((b((f.dVar38) < (DAT_0012f050))) != 0) || ((b((f.dVar48) < (DAT_0012f030))) != 0))) != 0) && ((b(((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0) || ((b((f.dVar48) < (3.0))) != 0))) != 0))) != 0) 265 else 264
        }
        267 -> {
            f.dVar48 = ((f.dVar31) + (f.dVar46))
            266
        }
        268 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            267
        }
        269 -> {
            f.dVar32 = ((f.dVar57) * (0.75))
            268
        }
        270 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        271 -> {
            if ((b((f.dVar38) < (3.0))) != 0) 270 else 269
        }
        272 -> {
            f.dVar38 = ((((f.dVar57) * (0.75))) + (f.dVar46))
            271
        }
        273 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        274 -> {
            if ((b((((((f.dVar57) * (0.5))) + (f.dVar53))) < (3.9))) != 0) 273 else 272
        }
        275 -> {
            if ((b(((b((f.fVar58) < (DAT_0012f050))) != 0) || ((run { run { f.dVar38 = ((((f.dVar57) * (0.75))) + (f.dVar46)); ((((f.dVar57) * (0.75))) + (f.dVar46)) }; b((f.dVar38) < (DAT_0012f030)) }) != 0))) != 0) 274 else 269
        }
        276 -> {
            if ((b(((b(((b((f.param_5) < (f.param_16))) != 0) && ((b((f.param_3) < (f.param_16))) != 0))) != 0) && ((b((f.param_4) < (f.param_16))) != 0))) != 0) 275 else 252
        }
        277 -> {
            45
        }
        278 -> {
            f.result = f32(f32(f.dVar57))
            C_DONE
        }
        279 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        280 -> {
            if ((f.bVar2) != 0) 279 else 278
        }
        281 -> {
            f.bVar2 = b((b((f.fVar58) < (3.5))) != 0)
            280
        }
        282 -> {
            if ((b(((b((f.fVar30) < (3.5))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar58).isNaN())) != 0)) }) != 0))) != 0) 281 else 280
        }
        283 -> {
            f.bVar2 = b((0) != 0)
            282
        }
        284 -> {
            if ((b(((b((f.dVar48) <= (2.9))) != 0) || ((b(((b((f.dVar45) <= (DAT_0012f058))) != 0) && ((b(((b((f.dVar50) <= (DAT_0012f058))) != 0) && ((b((f.dVar11) <= (DAT_0012f058))) != 0))) != 0))) != 0))) != 0) 283 else 277
        }
        285 -> {
            46
        }
        286 -> {
            if ((b(((b(((b(((b((2.5) <= (f.param_3))) != 0) && ((b((2.5) <= (f.param_4))) != 0))) != 0) && ((b((2.5) <= (f.param_5))) != 0))) != 0) || ((run { run { f.dVar57 = ((f.dVar57) * (0.75)); ((f.dVar57) * (0.75)) }; b(((b((3.5) <= (((f.dVar57) + (f.dVar46))))) != 0) || ((b((4.0) <= (((f.dVar57) + (f.dVar53))))) != 0)) }) != 0))) != 0) 285 else 284
        }
        287 -> {
            if ((b(((b((f.dVar48) <= (4.1))) != 0) || ((b(((b(((b((3.4) <= (f.dVar53))) != 0) && ((b((3.4) <= (f.dVar38))) != 0))) != 0) && ((b((3.4) <= (f.dVar48))) != 0))) != 0))) != 0) 286 else 277
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep9(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        288 -> {
            46
        }
        289 -> {
            if ((b((f.fVar58) < (DAT_0012f050))) != 0) 288 else 277
        }
        290 -> {
            f.result = f32(((f.fVar54) * (0.5)))
            C_DONE
        }
        291 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        292 -> {
            if ((b(((b((f.dVar44) <= (1.2))) != 0) || ((b((f.dVar48) >= (3.9))) != 0))) != 0) 291 else 290
        }
        293 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        294 -> {
            if ((b(((b((f.fVar23) <= (DAT_0012f058))) != 0) && ((b((f.fVar12) <= (DAT_0012f058))) != 0))) != 0) 293 else 292
        }
        295 -> {
            if ((b(((b(((b(((b((2.5) <= (f.param_3))) != 0) || ((b((f.dVar57) <= (DAT_0012ee78))) != 0))) != 0) && ((b(((b((2.5) <= (f.param_4))) != 0) || ((run { run { f.fVar58 = f32(f.fVar30); f32(f.fVar30) }; b((f.dVar57) <= (DAT_0012ee78)) }) != 0))) != 0))) != 0) && ((b(((b((2.5) <= (f.param_5))) != 0) || ((run { run { f.fVar58 = f32(f.fVar24); f32(f.fVar24) }; b((f.dVar57) <= (DAT_0012ee78)) }) != 0))) != 0))) != 0) 294 else 289
        }
        296 -> {
            if ((b(((b((2.8) <= (f.dVar46))) != 0) || ((b(((b(((b(((b((f.param_4) <= (f.param_3))) != 0) || ((b((f.param_5) <= (f.param_4))) != 0))) != 0) || ((b((3.5) <= (f.param_31))) != 0))) != 0) || ((b(((b((3.5) <= (f.fVar58))) != 0) || ((b((f32((f.param_18) - (f.param_17))) <= (DAT_0012f040))) != 0))) != 0))) != 0))) != 0) 287 else 295
        }
        297 -> {
            if ((b(((b((3.9) <= (f.dVar46))) != 0) || ((b(((b((3.9) <= (f.dVar53))) != 0) || ((b((3.9) <= (f.dVar38))) != 0))) != 0))) != 0) 296 else 276
        }
        298 -> {
            f.result = f32(((f.fVar54) * (0.25)))
            C_DONE
        }
        299 -> {
            f.result = f32(f32(f.dVar32))
            C_DONE
        }
        300 -> {
            if ((b(((b(((b((f.dVar38) < (3.9))) != 0) && ((b((f.param_16) < (4.5))) != 0))) != 0) && ((b(((b((3.0) <= (f.param_5))) != 0) || ((b((f.dVar42) <= (6.3))) != 0))) != 0))) != 0) 52 else 53
        }
        301 -> {
            43
        }
        302 -> {
            f.fVar49 = f32(f32(f.dVar31))
            301
        }
        303 -> {
            f.bVar3 = b((0) != 0)
            302
        }
        304 -> {
            f.bVar6 = b((b((f.param_16) == (3.0))) != 0)
            303
        }
        305 -> {
            f.bVar2 = b((b((f.param_16) < (3.0))) != 0)
            304
        }
        306 -> {
            if ((b(!((b((f.param_16).isNaN())) != 0))) != 0) 305 else 302
        }
        307 -> {
            f.bVar3 = b((1) != 0)
            306
        }
        308 -> {
            f.bVar6 = b((0) != 0)
            307
        }
        309 -> {
            f.bVar2 = b((0) != 0)
            308
        }
        310 -> {
            if ((b(((b(!((f.bVar5) != 0))) != 0) && ((b((f.bVar4) == (f.bVar7))) != 0))) != 0) 309 else 302
        }
        311 -> {
            f.bVar3 = b((0) != 0)
            310
        }
        312 -> {
            f.bVar6 = b((1) != 0)
            311
        }
        313 -> {
            f.bVar2 = b((0) != 0)
            312
        }
        314 -> {
            f.bVar7 = b((0) != 0)
            313
        }
        315 -> {
            f.bVar5 = b((b((f.fVar22) == (1.5))) != 0)
            314
        }
        316 -> {
            f.bVar4 = b((b((f.fVar22) < (1.5))) != 0)
            315
        }
        317 -> {
            if ((b(!((b((f.fVar22).isNaN())) != 0))) != 0) 316 else 313
        }
        318 -> {
            f.bVar7 = b((1) != 0)
            317
        }
        319 -> {
            f.bVar5 = b((0) != 0)
            318
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep10(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        320 -> {
            f.bVar4 = b((0) != 0)
            319
        }
        321 -> {
            if ((b((f.param_20) < (42.0))) != 0) 320 else 313
        }
        322 -> {
            f.bVar7 = b((0) != 0)
            321
        }
        323 -> {
            f.bVar5 = b((1) != 0)
            322
        }
        324 -> {
            f.bVar4 = b((0) != 0)
            323
        }
        325 -> {
            51
        }
        326 -> {
            if ((b(((b(((b(((b((3.5) < (f.param_3))) != 0) && ((b((3.5) < (f.param_4))) != 0))) != 0) && ((b(((b((f.param_5) < (3.0))) != 0) && ((b((0.5) < (((kotlin.math.abs(f.fVar39)) - (kotlin.math.abs(f.fVar13)))))) != 0))) != 0))) != 0) || ((b(((b(((b((f.param_5) < (3.0))) != 0) && ((b((0.5) < (f.fVar49))) != 0))) != 0) && ((b(((b(((b((f.fVar12) < (0.3))) != 0) && ((b(((b((f.param_16) < (3.0))) != 0) && ((b((DAT_0012ef38) < (f.fVar18))) != 0))) != 0))) != 0) || ((b(((b(((b((3.0) < (f.param_3))) != 0) && ((b((2.3) < (f.dVar48))) != 0))) != 0) && ((b(((b(((b((1.2) < (f.dVar44))) != 0) || ((b((0.35) < (f.fVar18))) != 0))) != 0) || ((b((8.5) < (f.param_22))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) 325 else 324
        }
        327 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        328 -> {
            if ((b((-(0.5)) <= (f.fVar39))) != 0) 327 else 326
        }
        329 -> {
            49
        }
        330 -> {
            f.result = f32(0.0)
            C_DONE
        }
        331 -> {
            if ((b(((b((3.5) < (f.param_16))) != 0) && ((b((2.0) < (f.fVar22))) != 0))) != 0) 330 else 329
        }
        332 -> {
            52
        }
        333 -> {
            if ((b(((b(((b((2.4) <= (f.dVar53))) != 0) && ((b(((b((2.4) <= (f.dVar46))) != 0) && ((b((2.4) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b((f.dVar48) <= (2.9))) != 0))) != 0) 332 else 331
        }
        334 -> {
            if ((b(((b(((b(((b(((b((f.dVar48) < (3.9))) != 0) && ((b((10.0) < (f.param_19))) != 0))) != 0) && ((b((DAT_0012ee90) < (f.dVar44))) != 0))) != 0) && ((b((7.5) < (f.param_23))) != 0))) != 0) && ((b(((b(((b((f.param_4) < (3.0))) != 0) || ((b((f.param_3) < (3.0))) != 0))) != 0) || ((b((f.param_5) < (3.0))) != 0))) != 0))) != 0) 333 else 324
        }
        335 -> {
            52
        }
        336 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        337 -> {
            if ((b((f.param_19) < (10.0))) != 0) 336 else 335
        }
        338 -> {
            51
        }
        339 -> {
            if ((b(((b(((b((f.param_4) <= (f.param_3))) != 0) || ((b((f.param_5) <= (f.param_4))) != 0))) != 0) || ((b((7.5) <= (f.param_22))) != 0))) != 0) 338 else 337
        }
        340 -> {
            if ((b(((b(((b((f.dVar53) < (2.8))) != 0) && ((b((0.5) < (f.fVar39))) != 0))) != 0) && ((b(((b((0.6) < (f.fVar39))) != 0) && ((b(((b(((b((60.0) < (f.param_20))) != 0) && ((b((f.param_19) < (11.0))) != 0))) != 0) && ((b(((b((3.9) < (f.dVar48))) != 0) && ((b((6.0) < (f.param_22))) != 0))) != 0))) != 0))) != 0))) != 0) 339 else 334
        }
        341 -> {
            51
        }
        342 -> {
            if ((b(((b(((b(((b((0.6) < (f.fVar39))) != 0) && ((b((f.param_16) < (3.0))) != 0))) != 0) && ((b((11.0) < (f.param_19))) != 0))) != 0) && ((b((8.6) < (f.dVar42))) != 0))) != 0) 341 else 340
        }
        343 -> {
            52
        }
        344 -> {
            if ((b(((b(((b((f.param_19) < (9.0))) != 0) && ((b((0.6) < (f.fVar13))) != 0))) != 0) && ((b(((b((f.param_16) < (3.0))) != 0) && ((b((6.0) < (f.param_22))) != 0))) != 0))) != 0) 343 else 342
        }
        345 -> {
            if ((b((0.5) < (f.fVar39))) != 0) 344 else 340
        }
        346 -> {
            52
        }
        347 -> {
            51
        }
        348 -> {
            if ((b(((b((11.0) <= (f.param_19))) != 0) || ((b((7.5) <= (f.param_22))) != 0))) != 0) 347 else 346
        }
        349 -> {
            if ((b(((b(((b((DAT_0012f058) < (f.fVar39))) != 0) && ((b((0.6) < (f.fVar13))) != 0))) != 0) && ((b((3.0) < (f.param_16))) != 0))) != 0) 348 else 345
        }
        350 -> {
            if ((b((f.param_4) < (2.5))) != 0) 349 else 340
        }
        351 -> {
            52
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep11(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        352 -> {
            51
        }
        353 -> {
            if ((b((10.5) <= (f.param_19))) != 0) 352 else 351
        }
        354 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        355 -> {
            if ((b((f.fVar49) <= (1.0))) != 0) 354 else 351
        }
        356 -> {
            if ((b(((b((f.param_5) <= (f.param_4))) != 0) || ((b((2.4) <= (f.dVar38))) != 0))) != 0) 353 else 355
        }
        357 -> {
            if ((b(((b(((b((f.param_4) < (3.0))) != 0) && ((b((f.param_5) < (3.0))) != 0))) != 0) && ((b(((b((0.5) < (f.fVar49))) != 0) && ((b(((b((f.param_16) < (3.0))) != 0) && ((b((DAT_0012ef38) < (f.fVar18))) != 0))) != 0))) != 0))) != 0) 356 else 350
        }
        358 -> {
            51
        }
        359 -> {
            if ((b(((b(((b(((b(((b((3.5) < (f.param_3))) != 0) && ((b((3.5) < (f.param_5))) != 0))) != 0) && ((b((f.param_4) < (3.0))) != 0))) != 0) && ((b((0.5) < (f32((f.fVar49) - (f.fVar27))))) != 0))) != 0) || ((b(((b(((b((DAT_0012ef60) < (f.fVar49))) != 0) && ((b((3.5) < (f.param_3))) != 0))) != 0) && ((b(((b((3.5) < (f.param_5))) != 0) && ((b(((b(((b((f.dVar53) < (DAT_0012f080))) != 0) && ((b((DAT_0012ee88) < (f.dVar44))) != 0))) != 0) && ((b((8.5) < (f.param_23))) != 0))) != 0))) != 0))) != 0))) != 0) 358 else 357
        }
        360 -> {
            if ((b((-(0.5)) <= (f.fVar14))) != 0) 328 else 359
        }
        361 -> {
            f.fVar49 = f32(kotlin.math.abs(f.fVar14))
            360
        }
        362 -> {
            f.result = f32(f32(f.dVar31))
            C_DONE
        }
        363 -> {
            f.result = f32(f32(f.dVar32))
            C_DONE
        }
        364 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        365 -> {
            if ((f.bVar2) != 0) 364 else 363
        }
        366 -> {
            f.bVar2 = b((b((f.param_5) < (2.5))) != 0)
            365
        }
        367 -> {
            if ((b(((b((f.param_20) <= (60.0))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_5).isNaN())) != 0)) }) != 0))) != 0) 366 else 365
        }
        368 -> {
            f.bVar2 = b((1) != 0)
            367
        }
        369 -> {
            if ((b(((b((f.param_3) < (f.param_4))) != 0) && ((b(((b((f.param_4) < (f.param_5))) != 0) && ((b(((b((f.param_5) < (2.5))) != 0) || ((b((f.param_22) < (7.5))) != 0))) != 0))) != 0))) != 0) 50 else 51
        }
        370 -> {
            52
        }
        371 -> {
            50
        }
        372 -> {
            52
        }
        373 -> {
            51
        }
        374 -> {
            if ((b(((b((2.8) <= (f.dVar48))) != 0) || ((b((DAT_0012f030) <= (f.dVar33))) != 0))) != 0) 373 else 372
        }
        375 -> {
            if ((b(((b(((b((f.param_4) <= (f.param_3))) != 0) || ((b((f.param_5) <= (f.param_4))) != 0))) != 0) || ((b(((b((2.5) <= (f.param_5))) != 0) && ((b((7.5) <= (f.param_22))) != 0))) != 0))) != 0) 374 else 371
        }
        376 -> {
            f.result = f32(0.0)
            C_DONE
        }
        377 -> {
            if ((b(((b((3.5) < (f.param_16))) != 0) && ((b((2.0) < (f.fVar22))) != 0))) != 0) 376 else 375
        }
        378 -> {
            if ((b(((b(((b((f.dVar53) < (2.4))) != 0) || ((b((f.dVar46) < (2.4))) != 0))) != 0) || ((b((f.dVar38) < (2.4))) != 0))) != 0) 377 else 370
        }
        379 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        380 -> {
            if ((b(((b(((b((DAT_0012f038) <= (f.dVar53))) != 0) && ((b((DAT_0012f038) <= (f.dVar46))) != 0))) != 0) && ((b((DAT_0012f038) <= (f.dVar38))) != 0))) != 0) 379 else 378
        }
        381 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        382 -> {
            if ((b((f.param_23) <= (7.5))) != 0) 381 else 380
        }
        383 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep12(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        384 -> {
            if ((b((f.dVar44) <= (DAT_0012ef70))) != 0) 383 else 382
        }
        385 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        386 -> {
            if ((b((f.param_19) <= (10.0))) != 0) 385 else 384
        }
        387 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        388 -> {
            if ((b((3.9) <= (f.dVar48))) != 0) 387 else 386
        }
        389 -> {
            51
        }
        390 -> {
            if ((b(((b(((b(((b(((b(((b((DAT_0012f098) < (f.dVar53))) != 0) && ((b((f.dVar46) < (3.9))) != 0))) != 0) && ((b((DAT_0012f098) < (f.dVar38))) != 0))) != 0) && ((b((3.9) < (f.dVar48))) != 0))) != 0) || ((b(((b(((b((3.9) < (f.dVar53))) != 0) && ((b((f.dVar46) < (DAT_0012f050))) != 0))) != 0) && ((b(((b((3.9) < (f.dVar38))) != 0) && ((b((3.9) < (f.dVar48))) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b((3.0) < (f.param_16))) != 0) && ((b((1.5) < (f.fVar22))) != 0))) != 0) && ((b((f.param_20) < (42.0))) != 0))) != 0))) != 0) 389 else 388
        }
        391 -> {
            if ((b(((b(((b((f.param_4) <= (3.5))) != 0) || ((b((f.param_5) <= (3.5))) != 0))) != 0) || ((b(((b((3.0) <= (f.param_3))) != 0) || ((b((((kotlin.math.abs(f.fVar13)) - (f.fVar23))) <= (0.5))) != 0))) != 0))) != 0) 390 else 49
        }
        392 -> {
            if ((b((f.fVar13) < (-(0.5)))) != 0) 391 else 361
        }
        393 -> {
            51
        }
        394 -> {
            52
        }
        395 -> {
            if ((b(((b(((b((2.0) < (f.fVar22))) != 0) && ((b((f.dVar48) < (2.8))) != 0))) != 0) && ((b((42.0) < (f.param_20))) != 0))) != 0) 394 else 393
        }
        396 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        397 -> {
            if ((b(((b(((b((f.param_16) < (3.5))) != 0) && ((b(((b(((b((f.param_3) < (2.5))) != 0) && ((b((f.param_4) < (2.5))) != 0))) != 0) && ((b((f.param_19) < (12.0))) != 0))) != 0))) != 0) && ((b(((b((54.0) < (f.param_20))) != 0) || ((b((((f.dVar31) + (f.dVar35))) < (3.0))) != 0))) != 0))) != 0) 396 else 395
        }
        398 -> {
            53
        }
        399 -> {
            if ((b(((b(((b((f.param_20) <= (42.0))) != 0) || ((b((DAT_0012f030) <= (f.dVar48))) != 0))) != 0) || ((b((f.param_19) <= (11.0))) != 0))) != 0) 398 else 393
        }
        400 -> {
            if ((b(((b((f.dVar46) <= (2.4))) != 0) || ((b((f.param_21) <= (11.0))) != 0))) != 0) 397 else 399
        }
        401 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        402 -> {
            if ((b((f.dVar57) <= (DAT_0012f058))) != 0) 401 else 400
        }
        403 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        404 -> {
            if ((b((48.0) < (f.param_20))) != 0) 403 else 402
        }
        405 -> {
            if ((b(((b((60.0) < (f.param_20))) != 0) || ((b((f.dVar57) <= (DAT_0012ee78))) != 0))) != 0) 404 else 400
        }
        406 -> {
            if ((b((f.param_20) <= (60.0))) != 0) 405 else 393
        }
        407 -> {
            if ((b(((b(((b(((b((DAT_0012f058) < (f.dVar45))) != 0) || ((b((DAT_0012f058) < (f.dVar50))) != 0))) != 0) || ((b((DAT_0012f058) < (f.dVar11))) != 0))) != 0) && ((b(((b(((b((f.dVar46) < (DAT_0012f070))) != 0) || ((b((f.dVar53) < (DAT_0012f070))) != 0))) != 0) || ((b(((b((f.dVar38) < (DAT_0012f070))) != 0) && ((b((2.4) < (f.dVar48))) != 0))) != 0))) != 0))) != 0) 406 else 392
        }
        408 -> {
            53
        }
        409 -> {
            f.result = f32(((f.fVar54) * (0.0)))
            C_DONE
        }
        410 -> {
            if ((b(((b((1.2) < (f.dVar50))) != 0) && ((b((7.0) < (f.param_22))) != 0))) != 0) 48 else 408
        }
        411 -> {
            51
        }
        412 -> {
            if ((b(((b((f.dVar48) <= (4.3))) != 0) || ((b((f.param_19) <= (8.0))) != 0))) != 0) 411 else 410
        }
        413 -> {
            if ((b(((b(((b(((b((5.0) < (f.param_29))) != 0) && ((b(((b(((b((0.6) < (f.dVar45))) != 0) || ((b((0.6) < (f.dVar50))) != 0))) != 0) || ((b((0.6) < (f.dVar11))) != 0))) != 0))) != 0) && ((b(((b(((b((f.dVar46) < (3.9))) != 0) || ((b((f.dVar53) < (3.9))) != 0))) != 0) || ((b((f.dVar38) < (3.9))) != 0))) != 0))) != 0) && ((b(((b(((b((4.1) < (f.dVar48))) != 0) || ((b(((b((DAT_0012f050) < (f.dVar48))) != 0) && ((b((8.0) < (f.param_23))) != 0))) != 0))) != 0) && ((b((60.0) < (f.param_20))) != 0))) != 0))) != 0) 412 else 407
        }
        414 -> {
            47
        }
        415 -> {
            if ((b(((b(((b(((b((f.dVar53) < (2.8))) != 0) && ((b((15.0) < (f.param_21))) != 0))) != 0) && ((b((DAT_0012ee90) < (f.dVar44))) != 0))) != 0) && ((b((8.0) < (f.param_22))) != 0))) != 0) 414 else 413
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep13(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        416 -> {
            52
        }
        417 -> {
            53
        }
        418 -> {
            51
        }
        419 -> {
            if ((b(((b(((b(((b((f.dVar48) < (4.3))) != 0) && ((b((f.dVar46) < (2.8))) != 0))) != 0) && ((b((f.param_19) < (11.0))) != 0))) != 0) || ((b(((b((f.fVar30) < (3.8))) != 0) && ((b((f.fVar58) < (3.8))) != 0))) != 0))) != 0) 418 else 417
        }
        420 -> {
            if ((b((3.5) < (f.param_16))) != 0) 419 else 416
        }
        421 -> {
            if ((b((f.dVar53) < (2.8))) != 0) 47 else 413
        }
        422 -> {
            if ((b((f.dVar48) <= (3.3))) != 0) 415 else 421
        }
        423 -> {
            if ((b((48.0) < (f.param_20))) != 0) 422 else 413
        }
        424 -> {
            51
        }
        425 -> {
            if ((b(((b((8.5) < (f.param_21))) != 0) && ((b(((b((f.dVar53) < (DAT_0012f030))) != 0) && ((b(((b((7.0) < (f.param_22))) != 0) || ((b((7.0) < (f.param_23))) != 0))) != 0))) != 0))) != 0) 424 else 423
        }
        426 -> {
            51
        }
        427 -> {
            53
        }
        428 -> {
            if ((b(((b((3.0) <= (f.param_3))) != 0) || ((b((3.0) <= (f.param_5))) != 0))) != 0) 427 else 426
        }
        429 -> {
            if ((b(((b((8.5) < (f.param_21))) != 0) && ((b(((b(((b(((b((6.0) < (f.param_22))) != 0) && ((b((f.dVar53) < (3.3))) != 0))) != 0) && ((b((6.0) < (f.param_23))) != 0))) != 0) && ((b(((b((2.0) < (f32((f.fVar26) / (f.fVar22))))) != 0) || ((b((DAT_0012ef60) < (f.dVar44))) != 0))) != 0))) != 0))) != 0) 428 else 425
        }
        430 -> {
            if ((b(((b((3.0) < (f.param_16))) != 0) && ((b((72.0) < (f.param_20))) != 0))) != 0) 429 else 423
        }
        431 -> {
            if ((b(((b(((b(((b((f.dVar48) <= (DAT_0012f048))) != 0) || ((b((f.param_20) <= (60.0))) != 0))) != 0) || ((b(((b(((b((3.5) <= (f.param_3))) != 0) && ((b((3.5) <= (f.param_4))) != 0))) != 0) && ((b((3.5) <= (f.param_5))) != 0))) != 0))) != 0) || ((b(((b(((b((0.25) <= (f.fVar18))) != 0) || ((b((10.0) <= (f.param_19))) != 0))) != 0) || ((b(((b((f.dVar59) <= (6.8))) != 0) || ((b((f.dVar42) <= (6.8))) != 0))) != 0))) != 0))) != 0) 430 else 300
        }
        432 -> {
            if ((b(((b((f.dVar48) <= (3.9))) != 0) || ((b(((b(((b((f.param_20) <= (60.0))) != 0) || ((b(((b(((b((DAT_0012f030) <= (f.dVar46))) != 0) && ((b((DAT_0012f030) <= (f.dVar53))) != 0))) != 0) && ((b((DAT_0012f030) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b((0.25) <= (f.fVar18))) != 0))) != 0))) != 0) 431 else 300
        }
        433 -> {
            52
        }
        434 -> {
            51
        }
        435 -> {
            if ((b(((b((DAT_0012f070) <= (f.dVar46))) != 0) || ((b((3.5) <= (f.fVar58))) != 0))) != 0) 434 else 433
        }
        436 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        437 -> {
            if ((b((f.fVar18) <= (0.35))) != 0) 436 else 435
        }
        438 -> {
            if ((b(((b((f.dVar48) < (DAT_0012f030))) != 0) && ((b(((b(((b(((b((f.dVar38) < (DAT_0012f030))) != 0) && ((b((f.dVar46) < (DAT_0012f030))) != 0))) != 0) && ((b((f.dVar53) < (DAT_0012f030))) != 0))) != 0) && ((b(((b((f.param_19) < (11.0))) != 0) && ((b(((b((54.0) < (f.param_20))) != 0) || ((b((((f.dVar31) + (f.dVar35))) < (3.9))) != 0))) != 0))) != 0))) != 0))) != 0) 437 else 432
        }
        439 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            438
        }
        440 -> {
            45
        }
        441 -> {
            f.result = f32(((f.fVar54) * (0.5)))
            C_DONE
        }
        442 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        443 -> {
            if ((f.bVar2) != 0) 442 else 441
        }
        444 -> {
            f.bVar2 = b((b((f.fVar58) < (3.5))) != 0)
            443
        }
        445 -> {
            if ((b(((b((f.fVar30) < (3.5))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar58).isNaN())) != 0)) }) != 0))) != 0) 444 else 443
        }
        446 -> {
            f.bVar2 = b((0) != 0)
            445
        }
        447 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep14(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        448 -> {
            if ((b(((b((f.fVar22) <= (2.0))) != 0) && ((b((f.param_23) <= (9.0))) != 0))) != 0) 447 else 446
        }
        449 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        450 -> {
            if ((b((DAT_0012f030) <= (f.dVar48))) != 0) 449 else 448
        }
        451 -> {
            if ((b((f.fVar58) < (3.9))) != 0) 450 else 440
        }
        452 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        453 -> {
            if ((b(((b(((b((f.param_23) < (8.5))) != 0) && ((b(((b((f.param_19) < (8.0))) != 0) || ((b((f.param_30) < (54.0))) != 0))) != 0))) != 0) && ((b(((b((72.0) < (f.param_20))) != 0) || ((b((f.fVar58) < (4.5))) != 0))) != 0))) != 0) 452 else 451
        }
        454 -> {
            if ((b(((b((f.dVar46) < (DAT_0012f070))) != 0) && ((b(((b((f.dVar53) < (DAT_0012f070))) != 0) && ((b((f.dVar38) < (DAT_0012f070))) != 0))) != 0))) != 0) 453 else 439
        }
        455 -> {
            53
        }
        456 -> {
            45
        }
        457 -> {
            52
        }
        458 -> {
            if ((b(((b((12.0) < (f.param_19))) != 0) && ((b((f.param_23) < (8.0))) != 0))) != 0) 457 else 456
        }
        459 -> {
            if ((b(((b(((b((f.dVar53) < (DAT_0012ee90))) != 0) || ((b((f.dVar46) < (DAT_0012ee90))) != 0))) != 0) || ((b(((b((f.dVar38) < (DAT_0012ee90))) != 0) || ((b((f.dVar48) < (2.8))) != 0))) != 0))) != 0) 458 else 455
        }
        460 -> {
            if ((b(((b(((b((f.param_20) < (42.0))) != 0) && ((b((2.0) < (f.fVar22))) != 0))) != 0) && ((b(((b((0.5) < (f.fVar54))) != 0) && ((b(((b((3.0) < (f.param_16))) != 0) || ((b(((b((2.4) < (f.dVar48))) != 0) && ((b((3.0) < (f.fVar22))) != 0))) != 0))) != 0))) != 0))) != 0) 459 else 454
        }
        461 -> {
            f.fVar49 = f32(f.fVar43)
            460
        }
        462 -> {
            46
        }
        463 -> {
            if ((b(((b(((b(((b(((b((7.0) < (f.param_22))) != 0) && ((b((f.param_16) < (3.5))) != 0))) != 0) && ((b(((b((f.param_19) < (12.0))) != 0) && ((b(((b((3.0) < (f.param_16))) != 0) && ((b((1.0) < (f.fVar22))) != 0))) != 0))) != 0))) != 0) && ((b((kotlin.math.abs(f32((f.param_23) - (f.param_22)))) < (0.3))) != 0))) != 0) || ((b(((b(((b((5.0) < (f.fVar58))) != 0) && ((b((f.dVar46) < (4.1))) != 0))) != 0) && ((b((DAT_0012f058) < (f.dVar57))) != 0))) != 0))) != 0) 462 else 461
        }
        464 -> {
            52
        }
        465 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        466 -> {
            if ((b(((b((f.fVar58) < (3.5))) != 0) && ((b(((b(((b((f.dVar53) < (DAT_0012f070))) != 0) && ((b((f.dVar46) < (DAT_0012f070))) != 0))) != 0) && ((b((f.fVar30) < (3.9))) != 0))) != 0))) != 0) 465 else 464
        }
        467 -> {
            if ((b(((b((f.param_16) < (3.5))) != 0) && ((b((((f.dVar32) + (f.dVar46))) < (3.5))) != 0))) != 0) 466 else 463
        }
        468 -> {
            f.dVar32 = ((f.dVar57) * (0.75))
            467
        }
        469 -> {
            f.result = f32(((f.fVar54) * (0.75)))
            C_DONE
        }
        470 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        471 -> {
            if ((f.bVar2) != 0) 470 else 469
        }
        472 -> {
            f.bVar2 = b((b((f.dVar53) < (DAT_0012f070))) != 0)
            471
        }
        473 -> {
            if ((b(((f.bVar4) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar53).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f070).isNaN())) != 0))) != 0)) }) != 0))) != 0) 472 else 471
        }
        474 -> {
            f.bVar2 = b((0) != 0)
            473
        }
        475 -> {
            f.bVar4 = b((b((f.dVar46) < (DAT_0012f070))) != 0)
            474
        }
        476 -> {
            if ((b(((f.bVar2) != 0) && ((run { run { f.bVar4 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar46).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f070).isNaN())) != 0))) != 0)) }) != 0))) != 0) 475 else 474
        }
        477 -> {
            f.bVar4 = b((0) != 0)
            476
        }
        478 -> {
            f.bVar2 = b((b((f.fVar58) < (3.5))) != 0)
            477
        }
        479 -> {
            if ((b(((b((f.fVar30) < (3.5))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar58).isNaN())) != 0)) }) != 0))) != 0) 478 else 477
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep15(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        480 -> {
            f.bVar2 = b((0) != 0)
            479
        }
        481 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        482 -> {
            if ((b((f.dVar48) < (2.8))) != 0) 481 else 480
        }
        483 -> {
            if ((b(((b(((b((f.fVar58) < (3.9))) != 0) && ((b((f.fVar30) < (3.9))) != 0))) != 0) && ((b((f.fVar24) < (3.9))) != 0))) != 0) 482 else 468
        }
        484 -> {
            56
        }
        485 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        486 -> {
            f.result = f32(f32(f.dVar57))
            C_DONE
        }
        487 -> {
            if ((f.bVar2) != 0) 486 else 485
        }
        488 -> {
            f.bVar2 = b((b((f.dVar46) < (3.0))) != 0)
            487
        }
        489 -> {
            if ((b(((b((DAT_0012f030) <= (((f.dVar57) + (f.dVar53))))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar46).isNaN())) != 0)) }) != 0))) != 0) 488 else 487
        }
        490 -> {
            f.bVar2 = b((1) != 0)
            489
        }
        491 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        492 -> {
            if ((b((f.dVar46) <= (DAT_0012f030))) != 0) 491 else 490
        }
        493 -> {
            f.dVar46 = ((f.dVar57) + (f.dVar46))
            492
        }
        494 -> {
            f.dVar57 = ((f.dVar57) * (0.25))
            493
        }
        495 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) <= (DAT_0012f030))) != 0) || ((b(((b((3.0) <= (((f.dVar31) + (f.dVar46))))) != 0) && ((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar53))))) != 0))) != 0))) != 0) 494 else 484
        }
        496 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            495
        }
        497 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) <= (DAT_0012f030))) != 0) || ((b(((b((3.0) <= (((f.dVar31) + (f.dVar46))))) != 0) && ((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar53))))) != 0))) != 0))) != 0) 496 else 484
        }
        498 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            497
        }
        499 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        500 -> {
            if ((b((f.fVar30) < (DAT_0012f030))) != 0) 499 else 498
        }
        501 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        502 -> {
            if ((b((f.fVar58) < (3.0))) != 0) 501 else 500
        }
        503 -> {
            if ((b((DAT_0012f030) < (f.fVar58))) != 0) 502 else 498
        }
        504 -> {
            if ((b(((b(((b(((b((f.dVar38) < (DAT_0012ee90))) != 0) && ((b((f.dVar46) < (DAT_0012ee90))) != 0))) != 0) && ((b((7.5) < (f.param_22))) != 0))) != 0) && ((b(((b(((b((7.5) < (f.param_23))) != 0) && ((b((f.dVar53) < (DAT_0012ee90))) != 0))) != 0) && ((b((2.0) < (f.fVar54))) != 0))) != 0))) != 0) 503 else 483
        }
        505 -> {
            45
        }
        506 -> {
            46
        }
        507 -> {
            if ((b((f.fVar58) < (4.5))) != 0) 506 else 505
        }
        508 -> {
            if ((b(((b(((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((f.dVar38) < (2.8))) != 0))) != 0) && ((b(((b((DAT_0012f058) < (f.fVar10))) != 0) && ((b((f.param_16) < (3.5))) != 0))) != 0))) != 0) && ((b((7.0) < (f.param_23))) != 0))) != 0) && ((b((3.8) < (f.fVar58))) != 0))) != 0) 507 else 504
        }
        509 -> {
            45
        }
        510 -> {
            46
        }
        511 -> {
            if ((b(((b((f.fVar58) < (4.5))) != 0) && ((b((3.3) <= (f.dVar46))) != 0))) != 0) 510 else 509
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep16(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        512 -> {
            if ((b(((b(((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((f.dVar53) < (2.8))) != 0))) != 0) && ((b((DAT_0012f058) < (f.fVar13))) != 0))) != 0) && ((b((f.param_16) < (3.5))) != 0))) != 0) && ((b(((b((7.0) < (f.param_23))) != 0) && ((b((3.8) < (f.fVar58))) != 0))) != 0))) != 0) 511 else 508
        }
        513 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        514 -> {
            if ((b((1) < (f.bVar9))) != 0) 513 else 512
        }
        515 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        516 -> {
            if ((b((((((f.dVar57) + (0.3))) - (f.param_15))) <= (0.5))) != 0) 515 else 514
        }
        517 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        518 -> {
            if ((b((f.param_14) < (0x241))) != 0) 517 else 516
        }
        519 -> {
            46
        }
        520 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        521 -> {
            if ((b(((b((f.param_17) < (2.5))) != 0) && ((b((f.fVar58) < (3.9))) != 0))) != 0) 520 else 519
        }
        522 -> {
            if ((b(((b(((b(((b(((b((f.dVar46) < (DAT_0012ee90))) != 0) && ((b((f.dVar48) < (DAT_0012f070))) != 0))) != 0) && ((b((6.5) < (f.param_22))) != 0))) != 0) && ((b(((b((60.0) <= (f.param_20))) != 0) && ((b((6.5) < (f.param_23))) != 0))) != 0))) != 0) && ((b((DAT_0012ee78) < (f.dVar57))) != 0))) != 0) 521 else 518
        }
        523 -> {
            if ((b(((b(((b(((b(((b((f.param_20) < (48.0))) != 0) || ((b(((b(((b((DAT_0012f070) <= (f.dVar53))) != 0) && ((b((DAT_0012f070) <= (f.dVar46))) != 0))) != 0) && ((b((f.param_16) <= (3.0))) != 0))) != 0))) != 0) || ((b(((b(((b((3.9) <= (f.dVar46))) != 0) && ((b((3.9) <= (f.dVar53))) != 0))) != 0) && ((b((3.9) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b(((b((f.param_21) <= (8.5))) != 0) || ((b(((b((f.dVar42) <= (5.8))) != 0) && ((b((f.dVar59) <= (5.8))) != 0))) != 0))) != 0))) != 0) || ((b(((b((f.fVar23) <= (DAT_0012ef38))) != 0) && ((b((f.fVar12) <= (DAT_0012ef38))) != 0))) != 0))) != 0) 522 else 297
        }
        524 -> {
            44
        }
        525 -> {
            if ((b(((b(((b(((b((f.param_20) < (45.0))) != 0) && ((b((f.param_16) < (2.5))) != 0))) != 0) && ((b(((b((2.0) < (f.param_16))) != 0) && ((b(((b((0.5) < (f.fVar22))) != 0) && ((b((1.0) < (f.fVar54))) != 0))) != 0))) != 0))) != 0) && ((b(((b((9.0) < (f.param_22))) != 0) || ((b((9.0) < (f.param_23))) != 0))) != 0))) != 0) 524 else 523
        }
        526 -> {
            48
        }
        527 -> {
            46
        }
        528 -> {
            if ((b((1.5) <= (f.param_3))) != 0) 527 else 526
        }
        529 -> {
            if ((b(((b(((b(((b((f.param_20) < (42.0))) != 0) && ((b(((b((0.85) < (f.dVar44))) != 0) && ((b((10.0) < (f.param_23))) != 0))) != 0))) != 0) && ((b((f.param_16) < (3.0))) != 0))) != 0) && ((b(((b(((b((10.0) < (f.param_22))) != 0) && ((b((2.0) < (f.param_16))) != 0))) != 0) && ((b((0.5) < (f.fVar54))) != 0))) != 0))) != 0) 528 else 525
        }
        530 -> {
            f.result = f32(((f.fVar54) * (0.5)))
            C_DONE
        }
        531 -> {
            f.result = f32(f32((f.fVar54) * (f.fVar49)))
            C_DONE
        }
        532 -> {
            if ((b((1.5) <= (f.param_3))) != 0) 45 else 46
        }
        533 -> {
            if ((b(((b(((b(((b(((b((DAT_0012f038) < (f.dVar48))) != 0) && ((b((2.5) < (f.fVar22))) != 0))) != 0) && ((b((f.param_20) < (42.0))) != 0))) != 0) && ((b(((b((0.3) < (f.dVar57))) != 0) && ((b(((b((9.0) < (f.param_22))) != 0) || ((b((9.0) < (f.param_23))) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b((f.param_20) < (39.0))) != 0) && ((b(((b(((b((DAT_0012ee78) < (f.dVar44))) != 0) && ((b((0.3) < (f.dVar57))) != 0))) != 0) && ((b((f.dVar48) < (DAT_0012f050))) != 0))) != 0))) != 0) && ((b(((b((DAT_0012f038) < (f.dVar48))) != 0) && ((b((7.0) < (f.param_22))) != 0))) != 0))) != 0))) != 0) 44 else 529
        }
        534 -> {
            46
        }
        535 -> {
            if ((b(((b(((b(((b(((b((f.param_20) < (48.0))) != 0) && ((b((f.param_16) < (4.5))) != 0))) != 0) && ((b((7.0) < (f.param_23))) != 0))) != 0) && ((b(((b((f.param_30) < (60.0))) != 0) && ((b((f.param_29) < (4.6))) != 0))) != 0))) != 0) && ((b(((b((3.0) < (f.param_16))) != 0) && ((b((DAT_0012f058) < (f.dVar57))) != 0))) != 0))) != 0) 534 else 533
        }
        536 -> {
            38
        }
        537 -> {
            f.dVar46 = ((f.dVar48) + (f.dVar53))
            536
        }
        538 -> {
            f.dVar38 = 3.9
            537
        }
        539 -> {
            46
        }
        540 -> {
            if ((b(((b((3.9) <= (((f.dVar48) + (f.dVar38))))) != 0) || ((b((3.9) <= (((f.dVar48) + (f.dVar46))))) != 0))) != 0) 539 else 538
        }
        541 -> {
            f.dVar48 = ((f.dVar57) * (0.75))
            540
        }
        542 -> {
            45
        }
        543 -> {
            if ((b(((b((f.dVar48) < (DAT_0012f030))) != 0) && ((b(((b((2.4) < (f.dVar46))) != 0) && ((b((1.2) < (f.dVar44))) != 0))) != 0))) != 0) 542 else 541
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep17(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        544 -> {
            56
        }
        545 -> {
            55
        }
        546 -> {
            if ((b(((b(((b((3.0) <= (((f.dVar31) + (f.dVar46))))) != 0) || ((b((3.9) <= (f.dVar53))) != 0))) != 0) && ((b(((b((3.9) <= (((f.dVar48) + (f.dVar38))))) != 0) || ((b(((b((3.5) <= (((f.dVar48) + (f.dVar46))))) != 0) || ((b((3.9) <= (f.dVar53))) != 0))) != 0))) != 0))) != 0) 545 else 544
        }
        547 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            546
        }
        548 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        549 -> {
            if ((b(((b(((b((((f.dVar48) + (f.dVar38))) < (DAT_0012f030))) != 0) && ((b((((f.dVar48) + (f.dVar46))) < (3.0))) != 0))) != 0) && ((b((f.dVar53) < (DAT_0012f030))) != 0))) != 0) 548 else 547
        }
        550 -> {
            f.dVar53 = ((f.dVar48) + (f.dVar53))
            549
        }
        551 -> {
            f.dVar48 = ((f.dVar57) * (0.5))
            550
        }
        552 -> {
            if ((b(((b(((b((f.param_3) < (2.5))) != 0) || ((b((f.param_4) < (2.5))) != 0))) != 0) || ((b((f.param_5) < (2.5))) != 0))) != 0) 551 else 543
        }
        553 -> {
            if ((b(((b(((b((f.param_30) < (60.0))) != 0) && ((b(((b((f.param_19) < (10.0))) != 0) || ((b((f.param_30) < (54.0))) != 0))) != 0))) != 0) && ((b(((b((DAT_0012f068) < (f.dVar48))) != 0) && ((b((DAT_0012ef60) < (f.dVar57))) != 0))) != 0))) != 0) 552 else 535
        }
        554 -> {
            if ((b(((b(((b((f.dVar48) < (3.9))) != 0) && ((b((f.param_20) < (60.0))) != 0))) != 0) && ((b((6.5) < (f.param_23))) != 0))) != 0) 553 else 535
        }
        555 -> {
            42
        }
        556 -> {
            f.bVar3 = b((0) != 0)
            555
        }
        557 -> {
            f.bVar6 = b((b((f.dVar44) == (DAT_0012ee80))) != 0)
            556
        }
        558 -> {
            f.bVar2 = b((b((f.dVar44) < (DAT_0012ee80))) != 0)
            557
        }
        559 -> {
            if ((b(((b(!((b((f.dVar44).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012ee80).isNaN())) != 0))) != 0))) != 0) 558 else 555
        }
        560 -> {
            f.bVar3 = b((1) != 0)
            559
        }
        561 -> {
            f.bVar6 = b((0) != 0)
            560
        }
        562 -> {
            f.bVar2 = b((0) != 0)
            561
        }
        563 -> {
            if ((b((f.param_20) < (42.0))) != 0) 562 else 555
        }
        564 -> {
            f.bVar3 = b((0) != 0)
            563
        }
        565 -> {
            f.bVar6 = b((1) != 0)
            564
        }
        566 -> {
            f.bVar2 = b((0) != 0)
            565
        }
        567 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        568 -> {
            if ((b((f.fVar58) <= (3.5))) != 0) 567 else 566
        }
        569 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        570 -> {
            if ((b(((b((f.param_22) <= (7.0))) != 0) && ((b((f.param_23) <= (7.0))) != 0))) != 0) 569 else 568
        }
        571 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        572 -> {
            if ((b(((b((3.9) <= (f.dVar53))) != 0) && ((b(((b(((b((3.9) <= (f.dVar46))) != 0) && ((b((DAT_0012f030) <= (f.dVar48))) != 0))) != 0) && ((b((DAT_0012f048) <= (f.dVar48))) != 0))) != 0))) != 0) 571 else 570
        }
        573 -> {
            46
        }
        574 -> {
            56
        }
        575 -> {
            if ((b(((b((48.0) < (f.param_20))) != 0) && ((run { run { f.dVar31 = ((f.dVar57) * (0.75)); ((f.dVar57) * (0.75)) }; b((((f.dVar31) + (f.dVar46))) < (3.9)) }) != 0))) != 0) 574 else 573
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep18(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        576 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        577 -> {
            if ((b((f.fVar58) <= (3.5))) != 0) 576 else 575
        }
        578 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        579 -> {
            if ((b(((b((f.param_22) <= (7.0))) != 0) && ((b((f.param_23) <= (7.0))) != 0))) != 0) 578 else 577
        }
        580 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        581 -> {
            if ((b((f.dVar44) <= (DAT_0012ee80))) != 0) 580 else 579
        }
        582 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        583 -> {
            if ((b(((b(((b((3.9) <= (f.dVar53))) != 0) && ((b(((b((3.9) <= (f.dVar46))) != 0) && ((b((DAT_0012f030) <= (f.dVar48))) != 0))) != 0))) != 0) && ((b((DAT_0012f048) <= (f.dVar48))) != 0))) != 0) 582 else 581
        }
        584 -> {
            if ((b(((b((0x59f) < (f.param_14))) != 0) || ((b(((b(((b(((b(((b((3.9) <= (f.dVar53))) != 0) && ((b((3.9) <= (f.dVar46))) != 0))) != 0) && ((b((DAT_0012f030) <= (f.dVar48))) != 0))) != 0) && ((b((DAT_0012f048) <= (f.dVar48))) != 0))) != 0) || ((b(((b(((b((f.param_22) <= (7.0))) != 0) && ((b((f.param_23) <= (7.0))) != 0))) != 0) || ((b((f.fVar58) <= (3.0))) != 0))) != 0))) != 0))) != 0) 583 else 573
        }
        585 -> {
            if ((b(((b(((b((f32((f.param_11) - (f.param_5))) <= (f.dVar38))) != 0) && ((b((f.fVar26) <= (f.dVar38))) != 0))) != 0) && ((b((f32((f.param_9) - (f.param_3))) <= (f.dVar38))) != 0))) != 0) 584 else 572
        }
        586 -> {
            f.dVar38 = ((f.dVar44) + (0.75))
            585
        }
        587 -> {
            if ((b(((b(((b(((b(((b(((b((f.param_16) < (3.5))) != 0) && ((b((f.param_20) < (60.0))) != 0))) != 0) && ((b((DAT_0012f058) < (f.dVar57))) != 0))) != 0) && ((b(((b((0x360) < (f.param_14))) != 0) && ((b((1.5) < (f.fVar22))) != 0))) != 0))) != 0) && ((b(((b((f.param_3) < (3.5))) != 0) || ((b((f.param_4) < (3.5))) != 0))) != 0))) != 0) && ((b((3.9) < (f.fVar58))) != 0))) != 0) 586 else 554
        }
        588 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        589 -> {
            f.result = f32(f.fVar49)
            C_DONE
        }
        590 -> {
            if ((b(((b(!((f.bVar6) != 0))) != 0) && ((b((f.bVar2) == (f.bVar3))) != 0))) != 0) 589 else 588
        }
        591 -> {
            f.fVar49 = f32(((f.fVar54) * (0.5)))
            43
        }
        592 -> {
            f.bVar3 = b((0) != 0)
            42
        }
        593 -> {
            f.bVar6 = b((b((f.fVar22) == (0.5))) != 0)
            592
        }
        594 -> {
            f.bVar2 = b((b((f.fVar22) < (0.5))) != 0)
            593
        }
        595 -> {
            if ((b(!((b((f.fVar22).isNaN())) != 0))) != 0) 594 else 42
        }
        596 -> {
            f.bVar3 = b((1) != 0)
            595
        }
        597 -> {
            f.bVar6 = b((0) != 0)
            596
        }
        598 -> {
            f.bVar2 = b((0) != 0)
            597
        }
        599 -> {
            if ((f.bVar4) != 0) 598 else 42
        }
        600 -> {
            f.bVar3 = b((0) != 0)
            599
        }
        601 -> {
            f.bVar6 = b((1) != 0)
            600
        }
        602 -> {
            f.bVar2 = b((0) != 0)
            601
        }
        603 -> {
            f.bVar4 = b((b((f.dVar46) < (3.3))) != 0)
            602
        }
        604 -> {
            if ((b(((b(!((f.bVar2) != 0))) != 0) && ((run { run { f.bVar4 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar46).isNaN())) != 0)) }) != 0))) != 0) 603 else 602
        }
        605 -> {
            f.bVar4 = b((1) != 0)
            604
        }
        606 -> {
            f.bVar2 = b((b((f.dVar53) < (3.3))) != 0)
            605
        }
        607 -> {
            if ((b(((b((3.3) <= (f.dVar38))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar53).isNaN())) != 0)) }) != 0))) != 0) 606 else 605
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep19(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        608 -> {
            f.bVar2 = b((1) != 0)
            607
        }
        609 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        610 -> {
            if ((b(((b(((b((f.fVar12) <= (DAT_0012f058))) != 0) && ((b((kotlin.math.abs(f32((f.param_5) - (f.param_3)))) <= (DAT_0012f058))) != 0))) != 0) && ((b((f.fVar25) <= (DAT_0012f058))) != 0))) != 0) 609 else 608
        }
        611 -> {
            46
        }
        612 -> {
            if ((b(((b(((b((f.fVar17) < (0.0))) != 0) || ((b((f.fVar15) < (0.0))) != 0))) != 0) || ((b((f.fVar16) < (0.0))) != 0))) != 0) 611 else 610
        }
        613 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        614 -> {
            if ((b(((b(((b((f.param_6) < (f.param_7))) != 0) && ((b((f.param_7) < (f.param_8))) != 0))) != 0) && ((b(((b((0.0) < (f.fVar19))) != 0) && ((b(((b((0.0) < (f.fVar20))) != 0) && ((b((0.0) < (f.fVar21))) != 0))) != 0))) != 0))) != 0) 613 else 612
        }
        615 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        616 -> {
            if ((b(((b(((b((6.0) < (f.param_3))) != 0) && ((b((6.0) < (f.param_4))) != 0))) != 0) && ((b((6.0) < (f.param_5))) != 0))) != 0) 615 else 614
        }
        617 -> {
            46
        }
        618 -> {
            if ((b(((b(((b((f.fVar17) <= (0.0))) != 0) || ((b((f.fVar15) <= (0.0))) != 0))) != 0) || ((b((f.fVar16) <= (0.0))) != 0))) != 0) 617 else 616
        }
        619 -> {
            if ((b(((b(((b(((b((1.0) < (f.fVar54))) != 0) && ((b((f.dVar46) < (3.9))) != 0))) != 0) && ((b((f.param_20) < (48.0))) != 0))) != 0) && ((b(((b(((b((4.5) < (f.param_16))) != 0) && ((b(((b((DAT_0012f058) < (f.dVar45))) != 0) || ((b(((b((DAT_0012f058) < (f.dVar50))) != 0) || ((b((DAT_0012f058) < (f.dVar11))) != 0))) != 0))) != 0))) != 0) && ((b(((b((0x240) < (f.param_14))) != 0) && ((b((3.5) < (f.fVar36))) != 0))) != 0))) != 0))) != 0) 618 else 587
        }
        620 -> {
            f.result = f32(((f.fVar54) * (0.75)))
            C_DONE
        }
        621 -> {
            56
        }
        622 -> {
            if ((b((3.9) <= (((f.dVar31) + (f.dVar46))))) != 0) 621 else 41
        }
        623 -> {
            45
        }
        624 -> {
            if ((b(((b((DAT_0012f070) <= (f.dVar48))) != 0) || ((b((f.param_20) <= (60.0))) != 0))) != 0) 623 else 41
        }
        625 -> {
            if ((b(((b((f.fVar58) < (4.5))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (3.9))) != 0))) != 0) 622 else 624
        }
        626 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            625
        }
        627 -> {
            if ((b(((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((f.dVar38) < (2.8))) != 0))) != 0) && ((b(((b(((b((0.7) < (f.fVar10))) != 0) && ((b((f.param_16) < (3.5))) != 0))) != 0) && ((b((6.5) < (f.param_23))) != 0))) != 0))) != 0) && ((b(((b((3.8) < (f.fVar58))) != 0) || ((b(((b((1.0) < (f.fVar22))) != 0) && ((b((3.5) < (f.fVar58))) != 0))) != 0))) != 0))) != 0) 626 else 619
        }
        628 -> {
            45
        }
        629 -> {
            46
        }
        630 -> {
            if ((b(((b(((b((f.fVar58) < (4.5))) != 0) || ((b((((((f.dVar57) * (0.5))) + (f.dVar46))) < (3.9))) != 0))) != 0) && ((b(((b((3.3) <= (f.dVar46))) != 0) || ((b((((((f.dVar57) * (0.5))) + (f.dVar46))) <= (3.9))) != 0))) != 0))) != 0) 629 else 628
        }
        631 -> {
            if ((b(((b(((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((f.dVar53) < (2.8))) != 0))) != 0) && ((b(((b((DAT_0012f058) < (f.fVar13))) != 0) && ((b((f.param_16) < (3.5))) != 0))) != 0))) != 0) && ((b((7.0) < (f.param_23))) != 0))) != 0) && ((b(((b((3.8) < (f.fVar58))) != 0) || ((b(((b((1.5) < (f.fVar22))) != 0) && ((b((3.5) < (f.fVar58))) != 0))) != 0))) != 0))) != 0) 630 else 627
        }
        632 -> {
            54
        }
        633 -> {
            56
        }
        634 -> {
            if ((b(((b(((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030)) }) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) 633 else 632
        }
        635 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            634
        }
        636 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        637 -> {
            f.result = f32(((f.fVar54) * (0.5)))
            C_DONE
        }
        638 -> {
            if ((f.bVar2) != 0) 637 else 636
        }
        639 -> {
            f.bVar2 = b((b((f.dVar48) < (DAT_0012f070))) != 0)
            40
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep20(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        640 -> {
            if ((b(((b(((b(!((f.bVar6) != 0))) != 0) && ((b((f.bVar4) == (f.bVar3))) != 0))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar48).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f070).isNaN())) != 0))) != 0)) }) != 0))) != 0) 639 else 40
        }
        641 -> {
            f.bVar2 = b((0) != 0)
            640
        }
        642 -> {
            f.bVar3 = b((0) != 0)
            641
        }
        643 -> {
            f.bVar6 = b((b((f.dVar44) == (DAT_0012ef60))) != 0)
            642
        }
        644 -> {
            f.bVar4 = b((b((f.dVar44) < (DAT_0012ef60))) != 0)
            643
        }
        645 -> {
            if ((b(((b(!((b((f.dVar44).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012ef60).isNaN())) != 0))) != 0))) != 0) 644 else 641
        }
        646 -> {
            f.bVar3 = b((1) != 0)
            645
        }
        647 -> {
            f.bVar6 = b((0) != 0)
            646
        }
        648 -> {
            f.bVar4 = b((0) != 0)
            647
        }
        649 -> {
            if ((b((f.dVar47) < (0.3))) != 0) 648 else 641
        }
        650 -> {
            f.bVar3 = b((0) != 0)
            649
        }
        651 -> {
            f.bVar6 = b((1) != 0)
            650
        }
        652 -> {
            f.bVar4 = b((0) != 0)
            651
        }
        653 -> {
            if ((b(((b((f.fVar58) < (3.5))) != 0) || ((b((f.fVar30) < (3.5))) != 0))) != 0) 39 else 635
        }
        654 -> {
            58
        }
        655 -> {
            if ((b(((b((60.0) < (f.param_20))) != 0) && ((b((0.3) < (f.dVar47))) != 0))) != 0) 654 else 653
        }
        656 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        657 -> {
            if ((b((f.dVar57) <= (0.7))) != 0) 656 else 655
        }
        658 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        659 -> {
            if ((b((f.dVar44) <= (0.7))) != 0) 658 else 657
        }
        660 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        661 -> {
            if ((b((3.9) <= (f.dVar48))) != 0) 660 else 659
        }
        662 -> {
            if ((b(((b(((b(((b((3.5) < (f.param_3))) != 0) && ((b((f.param_19) < (9.0))) != 0))) != 0) && ((b((f.fVar51) < (0.5))) != 0))) != 0) && ((b(((b((3.5) < (f.param_4))) != 0) && ((b((3.5) < (f.param_5))) != 0))) != 0))) != 0) 661 else 631
        }
        663 -> {
            55
        }
        664 -> {
            if ((b(((b((((f.dVar48) + (f.dVar46))) < (DAT_0012f030))) != 0) || ((b((((f.dVar48) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0) 663 else 235
        }
        665 -> {
            f.dVar48 = ((f.dVar57) * (0.25))
            664
        }
        666 -> {
            63
        }
        667 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) || ((b(((b(((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030)) }) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (DAT_0012f030))) != 0))) != 0))) != 0) 666 else 54
        }
        668 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            667
        }
        669 -> {
            39
        }
        670 -> {
            if ((b(((b((f.fVar58) < (3.5))) != 0) || ((b((f.fVar30) < (3.5))) != 0))) != 0) 669 else 668
        }
        671 -> {
            57
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep21(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        672 -> {
            f.bVar2 = b((b((f.dVar46) < (3.9))) != 0)
            671
        }
        673 -> {
            if ((b(((f.bVar4) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar46).isNaN())) != 0)) }) != 0))) != 0) 672 else 671
        }
        674 -> {
            f.bVar2 = b((0) != 0)
            673
        }
        675 -> {
            f.bVar4 = b((b((f.dVar53) < (3.9))) != 0)
            674
        }
        676 -> {
            if ((b(((f.bVar2) != 0) && ((run { run { f.bVar4 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar53).isNaN())) != 0)) }) != 0))) != 0) 675 else 674
        }
        677 -> {
            f.bVar4 = b((0) != 0)
            676
        }
        678 -> {
            f.bVar2 = b((b((f.dVar38) < (3.9))) != 0)
            677
        }
        679 -> {
            if ((b(((b((((((f.dVar57) * (0.75))) + (f.dVar46))) < (f.dVar31))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar38).isNaN())) != 0)) }) != 0))) != 0) 678 else 677
        }
        680 -> {
            f.bVar2 = b((0) != 0)
            679
        }
        681 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        682 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) <= (4.8))) != 0) 681 else 680
        }
        683 -> {
            if ((b(((b((60.0) < (f.param_20))) != 0) && ((b((0.3) < (f.dVar47))) != 0))) != 0) 682 else 670
        }
        684 -> {
            40
        }
        685 -> {
            f.bVar2 = b((b((f.fVar15) < (0.5))) != 0)
            684
        }
        686 -> {
            if ((b(((b((0.5) <= (f.fVar16))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar15).isNaN())) != 0)) }) != 0))) != 0) 685 else 684
        }
        687 -> {
            f.bVar2 = b((1) != 0)
            686
        }
        688 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        689 -> {
            if ((b((f.dVar57) <= (0.7))) != 0) 688 else 687
        }
        690 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        691 -> {
            if ((b((0.3) <= (f.dVar44))) != 0) 690 else 689
        }
        692 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        693 -> {
            if ((b((f.dVar48) < (3.9))) != 0) 692 else 691
        }
        694 -> {
            if ((b(((b(((b((3.9) <= (f.dVar48))) != 0) || ((b((f.dVar44) <= (0.7))) != 0))) != 0) || ((b((f.dVar57) <= (0.7))) != 0))) != 0) 693 else 683
        }
        695 -> {
            if ((b(((b(((b(((b((f.fVar36) <= (f.param_31))) != 0) || ((b(((b((4.0) <= (f.fVar36))) != 0) && ((b((DAT_0012f058) <= (f32((f.fVar36) - (f.param_31))))) != 0))) != 0))) != 0) || ((b((9.0) <= (f.param_19))) != 0))) != 0) || ((b((f.dVar31) <= (3.3))) != 0))) != 0) 662 else 694
        }
        696 -> {
            46
        }
        697 -> {
            56
        }
        698 -> {
            if ((b((f.dVar46) < (f.dVar38))) != 0) 697 else 696
        }
        699 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            698
        }
        700 -> {
            f.dVar46 = ((((f.dVar57) * (0.75))) + (f.dVar46))
            38
        }
        701 -> {
            f.dVar38 = 3.3
            700
        }
        702 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        703 -> {
            f.result = f32(((f.fVar54) * (0.75)))
            C_DONE
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep22(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        704 -> {
            if ((f.bVar3) != 0) 703 else 702
        }
        705 -> {
            f.bVar3 = b((b((f.dVar48) < (DAT_0012f068))) != 0)
            704
        }
        706 -> {
            if ((b(((b(((b(!((f.bVar4) != 0))) != 0) && ((b((f.bVar2) == (f.bVar6))) != 0))) != 0) && ((run { run { f.bVar3 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar48).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f068).isNaN())) != 0))) != 0)) }) != 0))) != 0) 705 else 704
        }
        707 -> {
            f.bVar3 = b((0) != 0)
            706
        }
        708 -> {
            f.bVar6 = b((0) != 0)
            707
        }
        709 -> {
            f.bVar4 = b((b((f.dVar44) == (1.2))) != 0)
            708
        }
        710 -> {
            f.bVar2 = b((b((f.dVar44) < (1.2))) != 0)
            709
        }
        711 -> {
            if ((b(!((b((f.dVar44).isNaN())) != 0))) != 0) 710 else 707
        }
        712 -> {
            f.bVar6 = b((1) != 0)
            711
        }
        713 -> {
            f.bVar4 = b((0) != 0)
            712
        }
        714 -> {
            f.bVar2 = b((0) != 0)
            713
        }
        715 -> {
            if ((b((DAT_0012f030) < (f.dVar31))) != 0) 714 else 707
        }
        716 -> {
            f.bVar6 = b((0) != 0)
            715
        }
        717 -> {
            f.bVar4 = b((1) != 0)
            716
        }
        718 -> {
            f.bVar2 = b((0) != 0)
            717
        }
        719 -> {
            if ((b((f.dVar31) < (3.3))) != 0) 718 else 701
        }
        720 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        721 -> {
            if ((b((f.dVar57) <= (DAT_0012f058))) != 0) 720 else 719
        }
        722 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        723 -> {
            if ((b((f.param_23) <= (6.5))) != 0) 722 else 721
        }
        724 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        725 -> {
            if ((b(((b((2.5) <= (f.param_16))) != 0) && ((b((f.dVar44) <= (DAT_0012f058))) != 0))) != 0) 724 else 723
        }
        726 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        727 -> {
            if ((b((f.param_22) <= (6.5))) != 0) 726 else 725
        }
        728 -> {
            56
        }
        729 -> {
            45
        }
        730 -> {
            if ((b(((b(((b(((b(((b((3.5) <= (((f.dVar31) + (f.dVar38))))) != 0) && ((b((3.5) <= (((f.dVar31) + (f.dVar53))))) != 0))) != 0) && ((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar46))))) != 0))) != 0) || ((b((3.5) <= (((f.dVar31) + (f.dVar46))))) != 0))) != 0) && ((b(((b(((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b(((b((3.5) <= (((f.dVar31) + (f.dVar38))))) != 0) && ((b((3.5) <= (((f.dVar31) + (f.dVar53))))) != 0)) }) != 0) && ((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar46))))) != 0))) != 0) || ((b((3.5) <= (((f.dVar31) + (f.dVar46))))) != 0))) != 0))) != 0) 729 else 728
        }
        731 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            730
        }
        732 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        733 -> {
            if ((b(((b((f.fVar58) < (3.5))) != 0) && ((b(((b((f.dVar31) < (DAT_0012f030))) != 0) || ((b(((b((f.fVar24) < (3.5))) != 0) || ((b((f.fVar30) < (3.5))) != 0))) != 0))) != 0))) != 0) 732 else 731
        }
        734 -> {
            if ((b(((b((f.fVar58) >= (3.5))) != 0) || ((b(((b((3.0) <= (f.fVar58))) != 0) && ((b(((b((f.fVar24) >= (3.5))) != 0) && ((b((f.fVar30) >= (3.5))) != 0))) != 0))) != 0))) != 0) 733 else 727
        }
        735 -> {
            f.dVar31 = f.fVar58
            734
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep23(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        736 -> {
            f.result = f32(f32(((f.dVar57) * (0.75))))
            C_DONE
        }
        737 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        738 -> {
            if ((f.bVar4) != 0) 737 else 736
        }
        739 -> {
            f.bVar4 = b((b((f.dVar46) < (3.0))) != 0)
            738
        }
        740 -> {
            if ((b(((b(!((f.bVar2) != 0))) != 0) && ((run { run { f.bVar4 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar46).isNaN())) != 0)) }) != 0))) != 0) 739 else 738
        }
        741 -> {
            f.bVar4 = b((1) != 0)
            740
        }
        742 -> {
            f.bVar2 = b((b((f.param_19) < (10.0))) != 0)
            741
        }
        743 -> {
            if ((b(((b((f.dVar46) < (DAT_0012f030))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_19).isNaN())) != 0)) }) != 0))) != 0) 742 else 741
        }
        744 -> {
            f.bVar2 = b((0) != 0)
            743
        }
        745 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        746 -> {
            if ((b((f.dVar46) <= (DAT_0012f068))) != 0) 745 else 744
        }
        747 -> {
            f.dVar46 = ((((f.dVar57) * (0.75))) + (f.dVar46))
            746
        }
        748 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        749 -> {
            if ((b((f.fVar18) <= (0.35))) != 0) 748 else 747
        }
        750 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        751 -> {
            if ((b((DAT_0012f030) <= (f.param_17))) != 0) 750 else 749
        }
        752 -> {
            f.result = f32(f32(((f.dVar57) * (0.25))))
            C_DONE
        }
        753 -> {
            if ((b(((b(((b((DAT_0012f038) < (f.param_17))) != 0) && ((b((DAT_0012f040) < (f.fVar18))) != 0))) != 0) && ((b((DAT_0012f038) < (((((f.dVar57) * (0.25))) + (f.dVar46))))) != 0))) != 0) 752 else 751
        }
        754 -> {
            if ((b(((b(((b((f.dVar44) <= (DAT_0012ef60))) != 0) || ((b(((b((DAT_0012f070) <= (f.dVar48))) != 0) && ((b((f.dVar44) <= (DAT_0012ee90))) != 0))) != 0))) != 0) || ((b((0.35) <= (f.dVar47))) != 0))) != 0) 753 else 735
        }
        755 -> {
            55
        }
        756 -> {
            f.result = f32(f32(f.dVar31))
            C_DONE
        }
        757 -> {
            55
        }
        758 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        759 -> {
            if ((b(((b((f.fVar30) <= (DAT_0012f030))) != 0) && ((b((f.fVar24) <= (DAT_0012f030))) != 0))) != 0) 758 else 757
        }
        760 -> {
            if ((b(((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar46))))) != 0) || ((b(((b(((b((3.5) <= (((f.dVar31) + (f.dVar38))))) != 0) && ((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar53))))) != 0))) != 0) && ((b((3.0) <= (((f.dVar31) + (f.dVar46))))) != 0))) != 0))) != 0) 759 else 756
        }
        761 -> {
            f.dVar31 = ((f.dVar57) * (0.25))
            760
        }
        762 -> {
            if ((b(((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar46))))) != 0) || ((b(((b(((b((3.5) <= (((f.dVar31) + (f.dVar38))))) != 0) && ((b((DAT_0012f030) <= (((f.dVar31) + (f.dVar53))))) != 0))) != 0) && ((b((3.0) <= (((f.dVar31) + (f.dVar46))))) != 0))) != 0))) != 0) 761 else 756
        }
        763 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            762
        }
        764 -> {
            if ((b(((b((DAT_0012f030) <= (((f.dVar48) + (f.dVar46))))) != 0) || ((b(((b(((b((3.5) <= (((f.dVar48) + (f.dVar38))))) != 0) && ((b((DAT_0012f030) <= (((f.dVar48) + (f.dVar53))))) != 0))) != 0) && ((b((3.0) <= (((f.dVar48) + (f.dVar46))))) != 0))) != 0))) != 0) 763 else 755
        }
        765 -> {
            f.dVar48 = ((f.dVar57) * (0.75))
            764
        }
        766 -> {
            if ((b((3.5) <= (f.fVar30))) != 0) 765 else 754
        }
        767 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep24(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        768 -> {
            if ((b(((b(((b((f.dVar44) < (1.2))) != 0) && ((b((f.fVar30) < (3.5))) != 0))) != 0) && ((b((f.fVar58) < (3.5))) != 0))) != 0) 767 else 754
        }
        769 -> {
            if ((b((3.5) <= (f.fVar58))) != 0) 766 else 768
        }
        770 -> {
            41
        }
        771 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        772 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) <= (4.8))) != 0) 771 else 770
        }
        773 -> {
            if ((b(((b((60.0) < (f.param_20))) != 0) && ((b((0.35) < (f.dVar47))) != 0))) != 0) 772 else 769
        }
        774 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        775 -> {
            if ((b((f.fVar54) <= (0.5))) != 0) 774 else 773
        }
        776 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        777 -> {
            if ((b((f.dVar44) <= (0.7))) != 0) 776 else 775
        }
        778 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        779 -> {
            if ((b((3.9) <= (f.dVar48))) != 0) 778 else 777
        }
        780 -> {
            if ((b(((b(((b((f.dVar33) <= (DAT_0012f030))) != 0) && ((b((f.param_31) < (f.fVar36))) != 0))) != 0) && ((b(((b((f.dVar31) < (2.8))) != 0) || ((b(((b((f.dVar31) < (DAT_0012f030))) != 0) && ((b(((b(((b((f.dVar53) < (DAT_0012f070))) != 0) || ((b((f.dVar46) < (DAT_0012f070))) != 0))) != 0) || ((b((f.dVar38) < (DAT_0012f070))) != 0))) != 0))) != 0))) != 0))) != 0) 779 else 695
        }
        781 -> {
            if ((b(((b(((b((f.fVar36) <= (f.param_31))) != 0) || ((b((3.9) <= (f.dVar33))) != 0))) != 0) || ((b(((b((9.0) <= (f.param_19))) != 0) || ((b((2.8) <= (f.dVar31))) != 0))) != 0))) != 0) 780 else 227
        }
        782 -> {
            37
        }
        783 -> {
            36
        }
        784 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            783
        }
        785 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            784
        }
        786 -> {
            f.bVar2 = b((b(((b((f.dVar57).isNaN())) != 0) || ((b((DAT_0012ee78).isNaN())) != 0))) != 0)
            785
        }
        787 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 786 else 782
        }
        788 -> {
            30
        }
        789 -> {
            if ((b(((b((f.dVar48) < (3.5))) != 0) && ((b(((b(((b((((f.dVar32) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar32) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((f.dVar48) < (3.3))) != 0))) != 0))) != 0) 788 else 787
        }
        790 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            789
        }
        791 -> {
            63
        }
        792 -> {
            if ((b(((b(((b((f.dVar42) < (3.5))) != 0) || ((run { run { f.dVar48 = ((((f.dVar57) * (0.25))) + (f.dVar46)); ((((f.dVar57) * (0.25))) + (f.dVar46)) }; b((f.dVar48) < (3.0)) }) != 0))) != 0) && ((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b(((b(((b((((f.dVar31) + (f.dVar53))) < (3.5))) != 0) || ((b((f.dVar42) < (3.3))) != 0))) != 0) || ((run { run { f.dVar48 = ((((f.dVar57) * (0.25))) + (f.dVar46)); ((((f.dVar57) * (0.25))) + (f.dVar46)) }; b((f.dVar48) < (3.0)) }) != 0))) != 0))) != 0))) != 0) 791 else 790
        }
        793 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            792
        }
        794 -> {
            30
        }
        795 -> {
            if ((b(((b(((b((f.dVar48) < (3.5))) != 0) || ((run { run { f.dVar42 = ((((f.dVar57) * (0.5))) + (f.dVar46)); ((((f.dVar57) * (0.5))) + (f.dVar46)) }; b((f.dVar42) < (3.0)) }) != 0))) != 0) && ((b(((b((((f.dVar32) + (f.dVar38))) < (3.5))) != 0) || ((b(((b(((b((((f.dVar32) + (f.dVar53))) < (3.5))) != 0) || ((b((f.dVar48) < (3.3))) != 0))) != 0) || ((run { run { f.dVar42 = ((((f.dVar57) * (0.5))) + (f.dVar46)); ((((f.dVar57) * (0.5))) + (f.dVar46)) }; b((f.dVar42) < (3.0)) }) != 0))) != 0))) != 0))) != 0) 794 else 793
        }
        796 -> {
            f.dVar32 = ((f.dVar57) * (0.75))
            795
        }
        797 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        798 -> {
            if ((b((f.dVar48) < (3.0))) != 0) 797 else 796
        }
        799 -> {
            f.dVar48 = ((((f.dVar57) * (0.75))) + (f.dVar46))
            798
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep25(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        800 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        801 -> {
            if ((b((f.fVar58) < (3.3))) != 0) 800 else 799
        }
        802 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        803 -> {
            if ((b((f.fVar30) < (3.5))) != 0) 802 else 801
        }
        804 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        805 -> {
            if ((b((f.fVar24) < (3.5))) != 0) 804 else 803
        }
        806 -> {
            if ((b(((b((f.fVar58) < (3.5))) != 0) || ((run { run { f.dVar48 = ((((f.dVar57) * (0.75))) + (f.dVar46)); ((((f.dVar57) * (0.75))) + (f.dVar46)) }; b((f.dVar48) < (3.0)) }) != 0))) != 0) 805 else 796
        }
        807 -> {
            f.result = f32(f32(((f.dVar57) * (0.75))))
            C_DONE
        }
        808 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        809 -> {
            if ((f.bVar2) != 0) 808 else 807
        }
        810 -> {
            f.bVar2 = b((b((f.dVar48) < (3.9))) != 0)
            57
        }
        811 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (f.dVar48))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar48).isNaN())) != 0)) }) != 0))) != 0) 810 else 57
        }
        812 -> {
            f.bVar2 = b((0) != 0)
            811
        }
        813 -> {
            if ((b(((b(((b(((b(((b((f.dVar38) < (3.9))) != 0) && ((b((f.dVar46) < (3.9))) != 0))) != 0) && ((b((f.dVar53) < (3.9))) != 0))) != 0) && ((run { run { f.dVar31 = ((f.dVar57) * (0.75)); ((f.dVar57) * (0.75)) }; b(((b((((f.dVar31) + (f.dVar38))) < (4.0))) != 0) && ((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0)) }) != 0))) != 0) && ((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0))) != 0) 812 else 806
        }
        814 -> {
            f.result = f32(((f.fVar54) * (0.75)))
            C_DONE
        }
        815 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        816 -> {
            if ((f.bVar4) != 0) 815 else 814
        }
        817 -> {
            f.bVar4 = b((b((f.fVar58) < (3.0))) != 0)
            816
        }
        818 -> {
            if ((b(((b(!((f.bVar2) != 0))) != 0) && ((run { run { f.bVar4 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar58).isNaN())) != 0)) }) != 0))) != 0) 817 else 816
        }
        819 -> {
            f.bVar4 = b((1) != 0)
            818
        }
        820 -> {
            f.bVar2 = b((b((f.dVar44) < (0.6))) != 0)
            819
        }
        821 -> {
            if ((b(((b((60.0) < (f.param_20))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar44).isNaN())) != 0)) }) != 0))) != 0) 820 else 819
        }
        822 -> {
            f.bVar2 = b((0) != 0)
            821
        }
        823 -> {
            if ((b(((b(((b((f.dVar53) < (3.9))) != 0) && ((b(((b(((b((f.dVar38) < (3.9))) != 0) && ((b((f.param_5) < (f.param_31))) != 0))) != 0) && ((b((f.param_4) < (f.param_31))) != 0))) != 0))) != 0) && ((b(((b((f.param_3) < (f.param_31))) != 0) && ((b((f.dVar47) < (DAT_0012ef38))) != 0))) != 0))) != 0) 822 else 813
        }
        824 -> {
            if ((b(((b((f.dVar47) <= (0.35))) != 0) || ((b((4.0) <= (f.fVar36))) != 0))) != 0) 823 else 58
        }
        825 -> {
            if ((b(((b(((b(((b((f.param_20) <= (48.0))) != 0) || ((b((f.dVar48) <= (2.8))) != 0))) != 0) || ((b(((b((f.dVar57) <= (0.6))) != 0) || ((b(((b(((b((f.fVar28) <= (0.6))) != 0) && ((b((f.fVar27) <= (0.6))) != 0))) != 0) && ((b((f.fVar23) <= (0.6))) != 0))) != 0))) != 0))) != 0) || ((b((f.fVar36) <= (3.0))) != 0))) != 0) 781 else 824
        }
        826 -> {
            if ((b(((b(((b((f.param_31) < (f.fVar36))) != 0) || ((b((3.9) <= (f.dVar33))) != 0))) != 0) || ((b((f.fVar54) <= (0.5))) != 0))) != 0) 825 else 171
        }
        827 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            61
        }
        828 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            827
        }
        829 -> {
            f.bVar2 = b((b(((b((f.dVar57).isNaN())) != 0) || ((b((DAT_0012ee78).isNaN())) != 0))) != 0)
            828
        }
        830 -> {
            35
        }
        831 -> {
            if ((b((f.dVar35) < (3.9))) != 0) 830 else 829
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep26(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        832 -> {
            62
        }
        833 -> {
            if ((b(((b((((f.dVar48) + (f.dVar46))) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar48) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar48) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((((f.dVar48) + (f.dVar46))) < (2.8))) != 0))) != 0))) != 0) 832 else 831
        }
        834 -> {
            f.dVar48 = ((f.dVar57) * (0.25))
            833
        }
        835 -> {
            63
        }
        836 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (2.8))) != 0))) != 0))) != 0) 835 else 834
        }
        837 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            836
        }
        838 -> {
            63
        }
        839 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.5))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (2.8))) != 0))) != 0))) != 0) 838 else 837
        }
        840 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            839
        }
        841 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        842 -> {
            if ((b((f.fVar58) < (2.8))) != 0) 841 else 840
        }
        843 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        844 -> {
            if ((b((f.fVar30) < (3.5))) != 0) 843 else 842
        }
        845 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        846 -> {
            if ((b((f.fVar24) < (3.5))) != 0) 845 else 844
        }
        847 -> {
            if ((b((f.fVar58) < (DAT_0012f030))) != 0) 846 else 840
        }
        848 -> {
            32
        }
        849 -> {
            63
        }
        850 -> {
            if ((b((((f.dVar31) + (f.dVar48))) <= (2.8))) != 0) 849 else 848
        }
        851 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            850
        }
        852 -> {
            if ((b(((b(((b((DAT_0012ef60) < (f.dVar44))) != 0) && ((b((8.0) < (f.param_21))) != 0))) != 0) && ((b(((b((6.0) < (f.param_22))) != 0) && ((b(((b(((b((6.0) < (f.param_23))) != 0) && ((b((DAT_0012f030) < (f.dVar33))) != 0))) != 0) && ((b((DAT_0012ee78) < (f.dVar57))) != 0))) != 0))) != 0))) != 0) 851 else 847
        }
        853 -> {
            if ((b(((b(((b(((b((2.5) <= (f.param_31))) != 0) || ((b(((b((7.8) <= (f.param_19))) != 0) || ((b((11.0) <= (f.param_21))) != 0))) != 0))) != 0) || ((b((7.0) <= (f.param_22))) != 0))) != 0) || ((b(((b(((b((7.0) <= (f.param_23))) != 0) || ((b((f.param_20) <= (60.0))) != 0))) != 0) || ((b((3.9) <= (f.dVar33))) != 0))) != 0))) != 0) 826 else 852
        }
        854 -> {
            f.fVar24 = f32(f32((f.fVar54) + (f.param_5)))
            853
        }
        855 -> {
            f.dVar33 = f.fVar36
            854
        }
        856 -> {
            33
        }
        857 -> {
            if ((b(((b((DAT_0012f098) < (f.dVar48))) != 0) && ((b(((b(((b(((b((DAT_0012f098) < (f.dVar31))) != 0) && ((b(((b(((b((f.dVar46) < (DAT_0012f050))) != 0) || ((b((f.dVar53) < (DAT_0012f050))) != 0))) != 0) || ((b((f.dVar38) < (DAT_0012f050))) != 0))) != 0))) != 0) && ((b((DAT_0012f058) < (f.dVar57))) != 0))) != 0) && ((b(((b((7.0) < (f.param_22))) != 0) || ((b((7.0) < (f.param_23))) != 0))) != 0))) != 0))) != 0) 856 else 855
        }
        858 -> {
            30
        }
        859 -> {
            63
        }
        860 -> {
            if ((b((f.dVar57) < (DAT_0012ee78))) != 0) 859 else 858
        }
        861 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            860
        }
        862 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            861
        }
        863 -> {
            29
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep27(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        864 -> {
            f.bVar2 = b((b(((b(!((f.bVar6) != 0))) != 0) && ((b((f.bVar4) == (f.bVar2))) != 0))) != 0)
            863
        }
        865 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            36
        }
        866 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            865
        }
        867 -> {
            f.bVar2 = b((b(((b((f.dVar57).isNaN())) != 0) || ((b((DAT_0012ee78).isNaN())) != 0))) != 0)
            866
        }
        868 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 867 else 37
        }
        869 -> {
            30
        }
        870 -> {
            if ((b(((b((((f.dVar32) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar32) + (f.dVar38))) < (3.9))) != 0) || ((b((((f.dVar32) + (f.dVar53))) < (3.9))) != 0))) != 0) || ((b((((f.dVar32) + (f.dVar46))) < (DAT_0012f030))) != 0))) != 0))) != 0) 869 else 868
        }
        871 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            870
        }
        872 -> {
            63
        }
        873 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.9))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0))) != 0))) != 0) 872 else 871
        }
        874 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            873
        }
        875 -> {
            63
        }
        876 -> {
            if ((b(((b((f.dVar48) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.9))) != 0) || ((b((((f.dVar31) + (f.dVar53))) < (3.9))) != 0))) != 0) || ((b((f.dVar48) < (DAT_0012f030))) != 0))) != 0))) != 0) 875 else 874
        }
        877 -> {
            63
        }
        878 -> {
            62
        }
        879 -> {
            if ((b((DAT_0012ee78) <= (f.dVar57))) != 0) 878 else 877
        }
        880 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            879
        }
        881 -> {
            61
        }
        882 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            881
        }
        883 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            882
        }
        884 -> {
            f.bVar2 = b((b(((b((f.dVar57).isNaN())) != 0) || ((b((DAT_0012ee78).isNaN())) != 0))) != 0)
            883
        }
        885 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 884 else 35
        }
        886 -> {
            62
        }
        887 -> {
            if ((b(((b((f.dVar46) < (3.0))) != 0) && ((b(((b(((b((((((f.dVar57) * (0.25))) + (f.dVar38))) < (3.5))) != 0) || ((b((f.fVar30) < (3.9))) != 0))) != 0) || ((b((f.dVar46) < (2.8))) != 0))) != 0))) != 0) 886 else 885
        }
        888 -> {
            f.dVar46 = ((((f.dVar57) * (0.25))) + (f.dVar46))
            887
        }
        889 -> {
            63
        }
        890 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((f.fVar30) < (3.9))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (2.8))) != 0))) != 0))) != 0) 889 else 888
        }
        891 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            890
        }
        892 -> {
            63
        }
        893 -> {
            if ((b(((b((f.dVar48) < (3.0))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.5))) != 0) || ((b((f.fVar30) < (3.9))) != 0))) != 0) || ((b((f.dVar48) < (2.8))) != 0))) != 0))) != 0) 892 else 891
        }
        894 -> {
            if ((b((f.param_16) <= (3.5))) != 0) 893 else 876
        }
        895 -> {
            f.dVar48 = ((f.dVar31) + (f.dVar46))
            894
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep28(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        896 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            895
        }
        897 -> {
            30
        }
        898 -> {
            if ((b((((f.dVar32) + (f.dVar46))) < (f.dVar48))) != 0) 897 else 868
        }
        899 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            898
        }
        900 -> {
            63
        }
        901 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (f.dVar48))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((((f.dVar31) + (f.dVar46))) < (f.dVar48)) }) != 0))) != 0) 900 else 899
        }
        902 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            901
        }
        903 -> {
            if ((b(((b(((b((3.9) <= (f.dVar46))) != 0) || ((b((f.param_20) <= (48.0))) != 0))) != 0) || ((b(((b((3.9) <= (f.dVar53))) != 0) || ((b(((b((3.9) <= (f.dVar38))) != 0) || ((b((0.6) <= (f.fVar24))) != 0))) != 0))) != 0))) != 0) 896 else 902
        }
        904 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        905 -> {
            if ((b((f.fVar58) < (2.8))) != 0) 904 else 903
        }
        906 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        907 -> {
            if ((b((f.fVar30) < (3.9))) != 0) 906 else 905
        }
        908 -> {
            if ((b((f.fVar58) < (3.0))) != 0) 907 else 903
        }
        909 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        910 -> {
            if ((b((f.fVar36) <= (3.0))) != 0) 909 else 908
        }
        911 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        912 -> {
            if ((b(((b(((b((f.fVar28) <= (0.7))) != 0) && ((b((f.fVar27) <= (0.7))) != 0))) != 0) && ((b((f.fVar23) <= (0.7))) != 0))) != 0) 911 else 910
        }
        913 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        914 -> {
            if ((b((f.dVar57) <= (DAT_0012f040))) != 0) 913 else 912
        }
        915 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        916 -> {
            if ((b((f.param_16) <= (3.0))) != 0) 915 else 914
        }
        917 -> {
            f.result = f32(f32(((f.dVar57) * (0.75))))
            C_DONE
        }
        918 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        919 -> {
            if ((f.bVar2) != 0) 918 else 917
        }
        920 -> {
            f.bVar2 = b((b((f.dVar44) < (DAT_0012ef60))) != 0)
            919
        }
        921 -> {
            if ((b(((b((f.dVar48) <= (((((f.dVar57) * (0.75))) + (f.dVar46))))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar44).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012ef60).isNaN())) != 0))) != 0)) }) != 0))) != 0) 920 else 919
        }
        922 -> {
            f.bVar2 = b((1) != 0)
            921
        }
        923 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        924 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) <= (DAT_0012f098))) != 0) 923 else 922
        }
        925 -> {
            if ((b(((b((0.35) < (f.dVar47))) != 0) && ((b((f.fVar36) < (4.0))) != 0))) != 0) 924 else 916
        }
        926 -> {
            36
        }
        927 -> {
            f.bVar4 = b((b((f.dVar57) < (DAT_0012ee78))) != 0)
            926
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep29(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        928 -> {
            f.bVar6 = b((b((f.dVar57) == (DAT_0012ee78))) != 0)
            927
        }
        929 -> {
            f.bVar2 = b((b(((b((f.dVar57).isNaN())) != 0) || ((b((DAT_0012ee78).isNaN())) != 0))) != 0)
            928
        }
        930 -> {
            if ((b((3.9) <= (f.dVar35))) != 0) 929 else 37
        }
        931 -> {
            30
        }
        932 -> {
            if ((b(((b((((f.dVar32) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar32) + (f.dVar38))) < (3.9))) != 0) || ((b((f.fVar30) < (3.9))) != 0))) != 0) || ((b((((f.dVar32) + (f.dVar46))) < (DAT_0012f030))) != 0))) != 0))) != 0) 931 else 930
        }
        933 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            932
        }
        934 -> {
            63
        }
        935 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.9))) != 0) || ((b((f.fVar30) < (3.9))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0))) != 0))) != 0) 934 else 933
        }
        936 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            935
        }
        937 -> {
            63
        }
        938 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.5))) != 0) && ((b(((b(((b((((f.dVar31) + (f.dVar38))) < (3.9))) != 0) || ((b((f.fVar30) < (3.9))) != 0))) != 0) || ((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0))) != 0))) != 0) 937 else 936
        }
        939 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            938
        }
        940 -> {
            33
        }
        941 -> {
            if ((b((f.param_16) <= (3.5))) != 0) 940 else 939
        }
        942 -> {
            59
        }
        943 -> {
            if ((b(((b(((b(((b(((b((f.param_19) < (9.0))) != 0) && ((b((48.0) < (f.param_20))) != 0))) != 0) && ((b((0.3) < (f.dVar50))) != 0))) != 0) && ((b(((b((0.3) < (f.dVar11))) != 0) && ((b((0.3) < (f.dVar45))) != 0))) != 0))) != 0) && ((b(((b((f.dVar46) < (4.1))) != 0) || ((b(((b((f.dVar53) < (4.1))) != 0) || ((b((f.dVar38) < (4.1))) != 0))) != 0))) != 0))) != 0) 942 else 941
        }
        944 -> {
            if ((b(((b(((b(((b((f.dVar48) <= (DAT_0012f048))) != 0) || ((b((f.dVar31) <= (DAT_0012f048))) != 0))) != 0) || ((b(((b(((b((DAT_0012f050) <= (f.dVar46))) != 0) && ((b(((b((DAT_0012f050) <= (f.dVar53))) != 0) && ((b((DAT_0012f050) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b((f.dVar57) <= (0.7))) != 0))) != 0))) != 0) || ((b(((b((f.param_22) <= (6.5))) != 0) && ((b((f.param_23) <= (6.5))) != 0))) != 0))) != 0) 925 else 943
        }
        945 -> {
            if ((b(((b((f.fVar51) < (DAT_0012ee78))) != 0) && ((b(((b((0.0) <= (f.fVar51))) != 0) && ((b((DAT_0012f060) < (f.dVar31))) != 0))) != 0))) != 0) 944 else 857
        }
        946 -> {
            f.fVar30 = f32(f32((f.fVar54) + (f.param_4)))
            945
        }
        947 -> {
            f.fVar51 = f32(f32((f.param_31) - (f.fVar36)))
            946
        }
        948 -> {
            f.result = f32(((f.fVar54) * (0.25)))
            C_DONE
        }
        949 -> {
            f.result = f32(((f.fVar54) * (0.5)))
            C_DONE
        }
        950 -> {
            if ((b(((b((3.0) <= (f.param_4))) != 0) && ((b(((b((3.0) <= (f.param_5))) != 0) || ((b((f.dVar57) <= (DAT_0012ee78))) != 0))) != 0))) != 0) 32 else 33
        }
        951 -> {
            32
        }
        952 -> {
            33
        }
        953 -> {
            if ((b(((b((DAT_0012ee78) <= (f.dVar57))) != 0) && ((b((3.9) <= (((((f.dVar57) * (0.5))) + (f.dVar35))))) != 0))) != 0) 952 else 951
        }
        954 -> {
            if ((b((f.dVar35) < (3.9))) != 0) 953 else 31
        }
        955 -> {
            59
        }
        956 -> {
            if ((b((f.dVar44) < (0.6))) != 0) 955 else 954
        }
        957 -> {
            63
        }
        958 -> {
            if ((b(((b(((b((((f.dVar31) + (f.dVar48))) < (3.9))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((((f.dVar31) + (f.dVar48))) < (3.9)) }) != 0))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.25)); ((f.dVar57) * (0.25)) }; b((((f.dVar31) + (f.dVar48))) < (3.9)) }) != 0))) != 0) 957 else 956
        }
        959 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            958
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep30(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        960 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        961 -> {
            if ((b((f32((f.fVar54) + (f.param_16))) < (3.9))) != 0) 960 else 959
        }
        962 -> {
            if ((b(((b((f.dVar48) < (DAT_0012f060))) != 0) && ((b((DAT_0012ef60) < (f.dVar44))) != 0))) != 0) 961 else 956
        }
        963 -> {
            32
        }
        964 -> {
            if ((b(((b((3.5) <= (f.param_3))) != 0) && ((b(((b((3.5) <= (f.param_4))) != 0) && ((b((3.5) <= (f.param_5))) != 0))) != 0))) != 0) 963 else 962
        }
        965 -> {
            34
        }
        966 -> {
            if ((b(((b((DAT_0012f030) <= (f.dVar53))) != 0) || ((b(((b((f.param_21) <= (8.5))) != 0) || ((b(((b((f.param_22) <= (7.0))) != 0) && ((b((f.param_23) <= (7.0))) != 0))) != 0))) != 0))) != 0) 965 else 964
        }
        967 -> {
            32
        }
        968 -> {
            33
        }
        969 -> {
            if ((b(((b((3.0) <= (f.param_3))) != 0) || ((b((3.0) <= (f.param_5))) != 0))) != 0) 968 else 967
        }
        970 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        971 -> {
            if ((b(((b((f.dVar46) < (3.9))) != 0) && ((b(((b((f.dVar53) < (3.9))) != 0) && ((b((f.dVar38) < (3.9))) != 0))) != 0))) != 0) 970 else 969
        }
        972 -> {
            if ((b(((b(((b((8.5) < (f.param_21))) != 0) && ((b((6.0) < (f.param_22))) != 0))) != 0) && ((b(((b((f.dVar53) < (3.3))) != 0) && ((b(((b((6.0) < (f.param_23))) != 0) && ((b(((b((2.0) < (f32((f.fVar26) / (f.fVar22))))) != 0) || ((b((DAT_0012ef60) < (f.dVar44))) != 0))) != 0))) != 0))) != 0))) != 0) 971 else 966
        }
        973 -> {
            if ((b(((b(((b((3.0) < (f.param_16))) != 0) && ((b((72.0) < (f.param_20))) != 0))) != 0) && ((b((f.dVar47) < (0.35))) != 0))) != 0) 972 else 34
        }
        974 -> {
            f.fVar26 = f32(f32((f.param_10) - (f.param_4)))
            973
        }
        975 -> {
            59
        }
        976 -> {
            33
        }
        977 -> {
            if ((b(((b(((b((3.9) <= (f.dVar38))) != 0) || ((b((4.5) <= (f.param_16))) != 0))) != 0) || ((b(((b((f.param_5) < (3.0))) != 0) && ((b((6.3) < (f.dVar42))) != 0))) != 0))) != 0) 976 else 975
        }
        978 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        979 -> {
            if ((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((f.dVar53) < (3.9))) != 0))) != 0) && ((b((f.dVar38) < (3.9))) != 0))) != 0) 978 else 977
        }
        980 -> {
            if ((b(((b(((b(((b((DAT_0012f048) < (f.dVar48))) != 0) && ((b(((b((60.0) < (f.param_20))) != 0) && ((b((f.dVar47) < (0.35))) != 0))) != 0))) != 0) && ((b(((b(((b((f.param_3) < (3.5))) != 0) || ((b((f.param_4) < (3.5))) != 0))) != 0) || ((b((f.param_5) < (3.5))) != 0))) != 0))) != 0) && ((b(((b((f.fVar18) < (0.25))) != 0) && ((b(((b(((b((f.param_19) < (10.0))) != 0) && ((b((6.8) < (f.dVar59))) != 0))) != 0) && ((b((6.8) < (f.dVar42))) != 0))) != 0))) != 0))) != 0) 979 else 974
        }
        981 -> {
            33
        }
        982 -> {
            63
        }
        983 -> {
            32
        }
        984 -> {
            if ((b((((f.dVar31) + (f.dVar46))) < (DAT_0012f030))) != 0) 983 else 982
        }
        985 -> {
            f.dVar31 = ((f.dVar57) * (0.25))
            984
        }
        986 -> {
            59
        }
        987 -> {
            if ((b(((b(((b((3.5) <= (f.param_3))) != 0) && ((b(((b((3.5) <= (f.param_4))) != 0) && ((b((3.5) <= (f.param_5))) != 0))) != 0))) != 0) || ((b(((b((f.param_22) <= (6.0))) != 0) && ((b((f.param_23) <= (6.0))) != 0))) != 0))) != 0) 986 else 985
        }
        988 -> {
            if ((b(((b(((b((f.dVar53) < (3.9))) != 0) || ((b((f.dVar38) < (3.9))) != 0))) != 0) && ((b((f.param_16) < (4.5))) != 0))) != 0) 987 else 981
        }
        989 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        990 -> {
            if ((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((f.dVar53) < (3.9))) != 0))) != 0) && ((b((f.dVar38) < (3.9))) != 0))) != 0) 989 else 988
        }
        991 -> {
            if ((b(((b(((b((3.9) < (f.dVar48))) != 0) && ((b(((b((60.0) < (f.param_20))) != 0) && ((b((f.dVar47) < (0.35))) != 0))) != 0))) != 0) && ((b(((b(((b((f.param_3) < (3.5))) != 0) || ((b((f.param_4) < (3.5))) != 0))) != 0) || ((b(((b((f.param_5) < (3.5))) != 0) && ((b((f.fVar18) < (0.25))) != 0))) != 0))) != 0))) != 0) 990 else 980
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep31(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        992 -> {
            f.dVar47 = f.fVar26
            991
        }
        993 -> {
            63
        }
        994 -> {
            30
        }
        995 -> {
            if ((b(((b((f.param_5) < (3.0))).and(f.bVar2)) != 0)) != 0) 994 else 993
        }
        996 -> {
            f.dVar31 = ((f.dVar57) * (0.5))
            995
        }
        997 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            996
        }
        998 -> {
            f.result = f32(f32(f.dVar32))
            C_DONE
        }
        999 -> {
            if ((b((f.param_4) < (3.0))) != 0) 30 else 997
        }
        1000 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            999
        }
        1001 -> {
            f.bVar2 = b((b((DAT_0012ee78) < (f.dVar57))) != 0)
            29
        }
        1002 -> {
            37
        }
        1003 -> {
            if ((b((f.dVar35) < (3.9))) != 0) 1002 else 1001
        }
        1004 -> {
            30
        }
        1005 -> {
            if ((b((((f.dVar32) + (f.dVar46))) < (3.0))) != 0) 1004 else 1003
        }
        1006 -> {
            f.dVar32 = ((f.dVar57) * (0.25))
            1005
        }
        1007 -> {
            63
        }
        1008 -> {
            if ((b(((b((((f.dVar31) + (f.dVar46))) < (3.0))) != 0) || ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((((f.dVar31) + (f.dVar46))) < (3.0)) }) != 0))) != 0) 1007 else 1006
        }
        1009 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            1008
        }
        1010 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        1011 -> {
            if ((b((f.fVar58) < (3.0))) != 0) 1010 else 1009
        }
        1012 -> {
            if ((b(((b((3.0) < (f.param_16))) != 0) && ((b(((b(((b(((b(((b((9.0) < (f.param_23))) != 0) || ((b(((b((DAT_0012f070) < (f.dVar44))) != 0) && ((b((7.8) < (f.dVar59))) != 0))) != 0))) != 0) && ((b((DAT_0012ee90) < (f.dVar44))) != 0))) != 0) && ((b(((b(((b((f.dVar48) < (3.8))) != 0) && ((b((f.param_20) < (42.0))) != 0))) != 0) && ((b((13.0) < (f.param_21))) != 0))) != 0))) != 0) && ((b((0.5) < (f.fVar54))) != 0))) != 0))) != 0) 1011 else 992
        }
        1013 -> {
            f.fVar58 = f32(f32((f.fVar54) + (f.param_3)))
            1012
        }
        1014 -> {
            63
        }
        1015 -> {
            32
        }
        1016 -> {
            if ((b((3.9) <= (((f.dVar31) + (f.dVar35))))) != 0) 1015 else 1014
        }
        1017 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            1016
        }
        1018 -> {
            if ((b(((b(((b(((b(((b((f.dVar48) < (2.8))) != 0) && ((b((DAT_0012f058) < (f.dVar44))) != 0))) != 0) && ((b((10.0) < (f.param_21))) != 0))) != 0) && ((b(((b(((b((6.0) < (f.param_22))) != 0) || ((b(((b((6.0) < (f.param_23))) != 0) && ((b((f.fVar26) < (0.35))) != 0))) != 0))) != 0) && ((b((0.6) < (f.dVar57))) != 0))) != 0))) != 0) && ((b(((b(((b((f.dVar35) < (3.9))) != 0) || ((b(((b((f.param_2) < (4.5))) != 0) && ((b((f.param_16) < (2.5))) != 0))) != 0))) != 0) && ((b((3.9) < (f.fVar58))) != 0))) != 0))) != 0) 1017 else 1013
        }
        1019 -> {
            63
        }
        1020 -> {
            33
        }
        1021 -> {
            if ((b(((b((3.9) <= (((f.dVar31) + (f.dVar35))))) != 0) && ((run { run { f.dVar31 = ((f.dVar57) * (0.5)); ((f.dVar57) * (0.5)) }; b((3.9) <= (((f.dVar31) + (f.dVar35)))) }) != 0))) != 0) 1020 else 1019
        }
        1022 -> {
            f.dVar31 = ((f.dVar57) * (0.75))
            1021
        }
        1023 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep32(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1024 -> {
            if ((b((f.fVar58) < (3.9))) != 0) 1023 else 1022
        }
        1025 -> {
            if ((b(((b(((b(((b(((b(((b(((b((f.dVar48) < (3.9))) != 0) && ((b((DAT_0012ef60) < (f.dVar44))) != 0))) != 0) && ((b((8.0) < (f.param_21))) != 0))) != 0) && ((b(((b((6.0) < (f.param_22))) != 0) || ((b(((b((6.0) < (f.param_23))) != 0) && ((b((f.fVar26) < (DAT_0012ef38))) != 0))) != 0))) != 0))) != 0) && ((b(((b((1.5) < (f.fVar54))) != 0) || ((b(((b((f.dVar48) < (DAT_0012f030))) != 0) && ((b((DAT_0012f058) < (f.dVar57))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar35) < (DAT_0012f050))) != 0) || ((b(((b((f.param_2) < (4.5))) != 0) && ((b((DAT_0012ee78) < (f.dVar44))) != 0))) != 0))) != 0))) != 0) && ((b((3.9) < (f.fVar58))) != 0))) != 0) 1024 else 1018
        }
        1026 -> {
            f.fVar58 = f32(f32((f.fVar54) + (f.param_2)))
            1025
        }
        1027 -> {
            f.dVar35 = f.param_2
            1026
        }
        1028 -> {
            f.dVar57 = f.fVar54
            1027
        }
        1029 -> {
            f.dVar44 = f.fVar22
            1028
        }
        1030 -> {
            f.result = f32(f.fVar54)
            C_DONE
        }
        1031 -> {
            if ((b((f.param_14) < (0x241))) != 0) 1030 else 1029
        }
        1032 -> {
            f.bVar9 = 1
            28
        }
        1033 -> {
            f.bVar9 = 1
            28
        }
        1034 -> {
            if ((b(((b(!((f.bVar5) != 0))) != 0) && ((b((f.bVar3) == (f.bVar7))) != 0))) != 0) 1033 else 28
        }
        1035 -> {
            f.bVar7 = b((0) != 0)
            1034
        }
        1036 -> {
            f.bVar5 = b((b((f.fVar22) == (1.0))) != 0)
            1035
        }
        1037 -> {
            f.bVar3 = b((b((f.fVar22) < (1.0))) != 0)
            1036
        }
        1038 -> {
            if ((b(!((b((f.fVar22).isNaN())) != 0))) != 0) 1037 else 1034
        }
        1039 -> {
            f.bVar7 = b((1) != 0)
            1038
        }
        1040 -> {
            f.bVar5 = b((0) != 0)
            1039
        }
        1041 -> {
            f.bVar3 = b((0) != 0)
            1040
        }
        1042 -> {
            if ((b(((b(!((f.bVar4) != 0))) != 0) && ((b((f.bVar2) == (f.bVar6))) != 0))) != 0) 1041 else 1034
        }
        1043 -> {
            f.bVar7 = b((0) != 0)
            1042
        }
        1044 -> {
            f.bVar5 = b((1) != 0)
            1043
        }
        1045 -> {
            f.bVar3 = b((0) != 0)
            1044
        }
        1046 -> {
            f.bVar6 = b((0) != 0)
            1045
        }
        1047 -> {
            f.bVar4 = b((b((f.param_19) == (10.0))) != 0)
            1046
        }
        1048 -> {
            f.bVar2 = b((b((f.param_19) < (10.0))) != 0)
            1047
        }
        1049 -> {
            if ((b(!((b((f.param_19).isNaN())) != 0))) != 0) 1048 else 1045
        }
        1050 -> {
            f.bVar6 = b((1) != 0)
            1049
        }
        1051 -> {
            f.bVar4 = b((0) != 0)
            1050
        }
        1052 -> {
            f.bVar2 = b((0) != 0)
            1051
        }
        1053 -> {
            if ((b((6.5) < (f.param_23))) != 0) 1052 else 1045
        }
        1054 -> {
            f.bVar6 = b((0) != 0)
            1053
        }
        1055 -> {
            f.bVar4 = b((1) != 0)
            1054
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep33(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1056 -> {
            f.bVar2 = b((0) != 0)
            1055
        }
        1057 -> {
            if ((b(((b(((b(((b(((b((f.param_3) < (3.5))) != 0) && ((b((3.9) < (f.dVar48))) != 0))) != 0) && ((b(((b((0.7) < (f.fVar12))) != 0) || ((b((0.7) < (f.fVar23))) != 0))) != 0))) != 0) && ((b(((b((0.35) < (f.fVar18))) != 0) && ((b((f.param_20) < (48.0))) != 0))) != 0))) != 0) && ((b((6.5) < (f.param_22))) != 0))) != 0) 1056 else 28
        }
        1058 -> {
            28
        }
        1059 -> {
            if ((b((f.bVar9) < (2))) != 0) 1058 else 1057
        }
        1060 -> {
            27
        }
        1061 -> {
            if ((b(((b(((b((2.5) < (f.param_3))) != 0) && ((b((DAT_0012f090) < (f.dVar42))) != 0))) != 0) && ((b((6.0) < (f.param_23))) != 0))) != 0) 1060 else 1057
        }
        1062 -> {
            if ((b(((b(((b(((b((f.param_16) <= (4.0))) != 0) || ((b((f.bVar9) < (2))) != 0))) != 0) || ((b(((b((DAT_0012f030) <= (f.dVar46))) != 0) && ((b(((b((DAT_0012f030) <= (f.dVar53))) != 0) && ((b((DAT_0012f030) <= (f.dVar38))) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b((f.fVar12) <= (DAT_0012f058))) != 0) && ((b((f.fVar23) <= (DAT_0012f058))) != 0))) != 0) || ((b(((b((DAT_0012f040) <= (f.fVar18))) != 0) || ((b((f.param_20) <= (60.0))) != 0))) != 0))) != 0))) != 0) 1059 else 1061
        }
        1063 -> {
            if ((b(((b(((b(((b((f.dVar46) < (3.9))) != 0) && ((b((1) < (f.bVar9))) != 0))) != 0) && ((b(((b(((b((0.7) < (f.fVar13))) != 0) && ((b(((b(((b((f.param_4) < (2.5))) != 0) && ((b((0.7) < (f.fVar39))) != 0))) != 0) && ((b((f.dVar48) < (DAT_0012f050))) != 0))) != 0))) != 0) || ((b(((b(((b((0.7) < (f.fVar14))) != 0) && ((b((f.param_5) < (2.5))) != 0))) != 0) && ((b((0.7) < (f.fVar10))) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b((f.param_3) < (3.0))) != 0) && ((b((1) < (f.bVar9))) != 0))) != 0) && ((b(((b((DAT_0012f058) < (f.fVar24))) != 0) && ((b(((b(((b((f.param_4) < (2.5))) != 0) && ((b((DAT_0012f058) < (f32((f.param_5) - (f.param_3))))) != 0))) != 0) && ((b((f.fVar25) < (0.7))) != 0))) != 0))) != 0))) != 0))) != 0) 27 else 1062
        }
        1064 -> {
            f.bVar9 = ((f.bVar9) + (b(((b((f.fVar22) < (1.0))) != 0) && ((b(((b((f.dVar48) < (3.3))) != 0) && ((b(((b((60.0) < (f.param_20))) != 0) && ((b((f.param_21) < (12.0))) != 0))) != 0))) != 0))))
            1063
        }
        1065 -> {
            f.bVar9 = 0
            26
        }
        1066 -> {
            f.bVar9 = 0
            26
        }
        1067 -> {
            if ((b(!((f.bVar2) != 0))) != 0) 1066 else 26
        }
        1068 -> {
            f.bVar2 = b((b((f.fVar18) < (0.25))) != 0)
            1067
        }
        1069 -> {
            if ((b(((b((f.param_19) < (9.0))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar18).isNaN())) != 0)) }) != 0))) != 0) 1068 else 1067
        }
        1070 -> {
            f.bVar2 = b((0) != 0)
            1069
        }
        1071 -> {
            if ((b(((b(((b((DAT_0012f080) < (f.dVar48))) != 0) && ((b(((b(((b((f.dVar48) < (DAT_0012f050))) != 0) && ((b((4.0) < (f.fVar36))) != 0))) != 0) && ((b(((b(((b((f.param_3) < (2.5))) != 0) || ((b((f.param_4) < (2.5))) != 0))) != 0) || ((b((f.param_5) < (2.5))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b((60.0) < (f.param_20))) != 0) && ((b((f.fVar22) < (1.2))) != 0))) != 0) && ((b((6.3) < (f.dVar59))) != 0))) != 0))) != 0) 1070 else 26
        }
        1072 -> {
            f.bVar9 = f.bVar8
            1071
        }
        1073 -> {
            25
        }
        1074 -> {
            if ((b(((b(((b((f.param_16) < (3.0))) != 0) && ((b((f.dVar48) < (2.8))) != 0))) != 0) && ((b((1.2) < (f.fVar22))) != 0))) != 0) 1073 else 1072
        }
        1075 -> {
            26
        }
        1076 -> {
            if ((b(((b((f.dVar46) < (2.8))) != 0) || ((b((f.dVar53) < (2.8))) != 0))) != 0) 1075 else 1072
        }
        1077 -> {
            f.bVar9 = 0
            1076
        }
        1078 -> {
            if ((b((f.param_16) < (3.0))) != 0) 25 else 1072
        }
        1079 -> {
            if ((b((f.fVar22) <= (1.5))) != 0) 1074 else 1078
        }
        1080 -> {
            if ((b(((b(((b((60.0) < (f.param_20))) != 0) && ((b((6.5) < (f.param_22))) != 0))) != 0) && ((b((f.param_30) < (48.0))) != 0))) != 0) 1079 else 1072
        }
        1081 -> {
            if ((b(((b(((b((f.param_4) < (2.5))) != 0) && ((b((((f.fVar27) + (DAT_0012f088))) < (f.fVar23))) != 0))) != 0) || ((b(((b(((b((f.param_5) < (2.5))) != 0) && ((b((((f.fVar12) + (DAT_0012f088))) < (f.fVar25))) != 0))) != 0) || ((b(((b((f.param_3) < (2.5))) != 0) && ((b((((f.fVar23) + (DAT_0012f088))) < (f.fVar28))) != 0))) != 0))) != 0))) != 0) 1065 else 1080
        }
        1082 -> {
            if ((b((1) < (f.bVar8))) != 0) 1081 else 26
        }
        1083 -> {
            f.bVar9 = f.bVar8
            1082
        }
        1084 -> {
            f.bVar8 = ((f.bVar8) + (1))
            1083
        }
        1085 -> {
            if ((b(((b(((b(((b(((b((f.param_5) < (3.5))) != 0) && ((b((0.3) < (f.dVar45))) != 0))) != 0) && ((b(((b((f.fVar22) <= (1.5))) != 0) || ((b(((b(((b((f.param_16) <= (2.5))) != 0) || ((b((2.8) <= (f.dVar38))) != 0))) != 0) || ((b((f.param_30) <= (60.0))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar42) <= (DAT_0012ef68))) != 0) || ((b(((b(((b(((b((3.0) <= (f.param_16))) != 0) || ((b((f.dVar59) <= (DAT_0012ef68))) != 0))) != 0) || ((b(((b((2.8) <= (f.dVar46))) != 0) || ((b((f.param_30) <= (60.0))) != 0))) != 0))) != 0) && ((b(((b(((b(((b(((b((DAT_0012f060) <= (f.param_16))) != 0) || ((b((f.dVar59) <= (DAT_0012ef68))) != 0))) != 0) || ((b((2.4) <= (f.dVar38))) != 0))) != 0) || ((b((72.0) <= (f.param_20))) != 0))) != 0) || ((b(((b((f.param_30) <= (60.0))) != 0) && ((b(((b((DAT_0012f070) <= (f.dVar38))) != 0) || ((b((f.param_22) <= (7.0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b((f.param_16) <= (3.3))) != 0) || ((b((f.param_23) <= (9.0))) != 0))) != 0) && ((b(((b(((b(((b(((b((f.param_16) <= (DAT_0012f060))) != 0) || ((b((f.param_20) <= (54.0))) != 0))) != 0) || ((b((1.2) <= (f.fVar22))) != 0))) != 0) || ((b(((b((f.param_23) <= (7.0))) != 0) || ((b((DAT_0012f080) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b((f.param_30) <= (56.0))) != 0))) != 0))) != 0))) != 0) 1084 else 1083
        }
        1086 -> {
            f.dVar45 = f.fVar17
            1085
        }
        1087 -> {
            f.bVar8 = ((f.bVar8) + (1))
            1086
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep34(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1088 -> {
            if ((b(((b(((b(((b(((b((f.param_4) < (3.5))) != 0) && ((b((0.3) < (f.dVar11))) != 0))) != 0) && ((b(((b((f.fVar22) <= (1.5))) != 0) || ((b(((b(((b((f.param_16) <= (2.5))) != 0) || ((b((2.8) <= (f.dVar53))) != 0))) != 0) || ((b((f.param_30) <= (60.0))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar42) <= (DAT_0012ef68))) != 0) || ((b(((b(((b(((b((3.0) < (f.param_16))) != 0) || ((b((f.dVar59) <= (DAT_0012ef68))) != 0))) != 0) || ((b(((b((2.8) <= (f.dVar46))) != 0) || ((b((f.param_30) <= (60.0))) != 0))) != 0))) != 0) && ((b(((b(((b((f.dVar59) <= (DAT_0012ef68))) != 0) || ((b((f.param_16) <= (3.0))) != 0))) != 0) || ((b(((b((DAT_0012f060) <= (f.param_16))) != 0) || ((b(((b(((b((2.4) <= (f.dVar53))) != 0) || ((b((72.0) <= (f.param_20))) != 0))) != 0) || ((b(((b((f.param_30) <= (60.0))) != 0) && ((b(((b((DAT_0012f070) <= (f.dVar53))) != 0) || ((b((f.param_22) <= (7.0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b((f.param_16) <= (3.3))) != 0) || ((b((f.param_23) <= (9.0))) != 0))) != 0) && ((b(((b(((b(((b(((b((f.param_16) <= (DAT_0012f060))) != 0) || ((b((f.param_20) <= (54.0))) != 0))) != 0) || ((b((1.2) <= (f.fVar22))) != 0))) != 0) || ((b(((b((f.param_23) <= (7.0))) != 0) || ((b((DAT_0012f080) <= (f.dVar53))) != 0))) != 0))) != 0) || ((b((f.param_30) <= (56.0))) != 0))) != 0))) != 0))) != 0) 1087 else 1086
        }
        1089 -> {
            f.dVar11 = f.fVar16
            1088
        }
        1090 -> {
            f.bVar8 = 0
            1089
        }
        1091 -> {
            f.bVar8 = 1
            1089
        }
        1092 -> {
            if ((b(((b(((b(((b(((b((1.5) < (f.fVar22))) != 0) && ((b(((b(((b((2.5) < (f.param_16))) != 0) && ((b((f.dVar46) < (2.8))) != 0))) != 0) && ((b((60.0) < (f.param_30))) != 0))) != 0))) != 0) || ((b(((b((DAT_0012ef68) < (f.dVar42))) != 0) && ((b(((b(((b(((b((f.param_16) <= (3.0))) != 0) && ((b((DAT_0012ef68) < (f.dVar59))) != 0))) != 0) && ((b(((b((f.dVar46) < (2.8))) != 0) && ((b((60.0) < (f.param_30))) != 0))) != 0))) != 0) || ((b(((b(((b((DAT_0012ef68) < (f.dVar59))) != 0) && ((b((3.0) < (f.param_16))) != 0))) != 0) && ((b(((b((f.param_16) < (DAT_0012f060))) != 0) && ((b(((b(((b((f.dVar46) < (2.4))) != 0) && ((b((f.param_20) < (72.0))) != 0))) != 0) && ((b(((b((60.0) < (f.param_30))) != 0) || ((b(((b((f.dVar46) < (DAT_0012f070))) != 0) && ((b((7.0) < (f.param_22))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) || ((b(((b((3.3) < (f.param_16))) != 0) && ((b((9.0) < (f.param_23))) != 0))) != 0))) != 0) || ((b(((b(((b(((b(((b((DAT_0012f060) < (f.param_16))) != 0) && ((b((54.0) < (f.param_20))) != 0))) != 0) && ((b((f.fVar22) < (1.2))) != 0))) != 0) && ((b(((b((7.0) < (f.param_23))) != 0) && ((b((f.dVar46) < (DAT_0012f080))) != 0))) != 0))) != 0) && ((b((56.0) < (f.param_30))) != 0))) != 0))) != 0) 1090 else 1091
        }
        1093 -> {
            if ((b(((b((f.param_3) < (3.5))) != 0) && ((b((0.3) < (f.dVar50))) != 0))) != 0) 1092 else 1089
        }
        1094 -> {
            f.dVar59 = f.param_23
            1093
        }
        1095 -> {
            f.dVar50 = f.fVar15
            1094
        }
        1096 -> {
            f.dVar42 = f.param_22
            1095
        }
        1097 -> {
            f.bVar8 = 0
            1096
        }
        1098 -> {
            f.fVar43 = f32(0.5)
            1097
        }
        1099 -> {
            f.fVar49 = f32(0.25)
            1098
        }
        1100 -> {
            13
        }
        1101 -> {
            f.fVar49 = f32(((((((((f.fVar36) * (3.0))) - (f.param_7))) - (f.param_4))) + (f.param_3)))
            1100
        }
        1102 -> {
            12
        }
        1103 -> {
            f.fVar49 = f32(f32((f32((f.fVar51) - (f.param_7))) - (f.param_4)))
            1102
        }
        1104 -> {
            if ((b((f.param_4) <= (f.param_3))) != 0) 1103 else 1101
        }
        1105 -> {
            21
        }
        1106 -> {
            if ((b(((b((0.5) < (f.fVar13))) != 0) && ((b((f.param_14) < (0x360))) != 0))) != 0) 1105 else 1104
        }
        1107 -> {
            10
        }
        1108 -> {
            if ((b((f.param_13) <= (f.fVar56))) != 0) 1107 else 1106
        }
        1109 -> {
            11
        }
        1110 -> {
            if ((b(((b((f.param_16) <= (f.param_4))) != 0) && ((b((1.0) < (f.param_16))) != 0))) != 0) 1109 else 1108
        }
        1111 -> {
            if ((b(((b((1.0) < (f.param_4))) != 0) && ((b((f.fVar34) < (0.5))) != 0))) != 0) 1110 else 24
        }
        1112 -> {
            f.fVar54 = f32(((f32((f.fVar49) - (f.param_16))) * (0.5)))
            24
        }
        1113 -> {
            24
        }
        1114 -> {
            f.fVar54 = f32(((f.fVar43) * (0.5)))
            1113
        }
        1115 -> {
            if ((b((f.param_4) <= (f.param_16))) != 0) 1114 else 1112
        }
        1116 -> {
            f.fVar49 = f32(f.fVar36)
            1115
        }
        1117 -> {
            8
        }
        1118 -> {
            if ((b(((b((f.fVar13) <= (0.5))) != 0) || ((b((0x35f) < (f.param_14))) != 0))) != 0) 1117 else 1116
        }
        1119 -> {
            f.fVar30 = f32(f.param_4)
            1118
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep35(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1120 -> {
            f.fVar49 = f32(f.fVar43)
            1119
        }
        1121 -> {
            f.fVar49 = f32(f32((f.fVar51) - (f.param_4)))
            1112
        }
        1122 -> {
            if ((b(((b((0.5) <= (f.fVar58))) != 0) || ((b((f.fVar16) <= (DAT_0012ef70))) != 0))) != 0) 1120 else 1121
        }
        1123 -> {
            f.fVar54 = f32(f32((f.fVar36) - (f.param_16)))
            24
        }
        1124 -> {
            if ((b((f.param_16) < (f.fVar30))) != 0) 1123 else 24
        }
        1125 -> {
            f.fVar54 = f32(f.fVar49)
            1124
        }
        1126 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_3))) - (f.param_16))) * (0.5)))
            24
        }
        1127 -> {
            if ((b(((b((0.5) <= (f.fVar58))) != 0) || ((b((f.fVar15) <= (DAT_0012ef70))) != 0))) != 0) 8 else 1126
        }
        1128 -> {
            f.fVar30 = f32(f.param_3)
            1127
        }
        1129 -> {
            if ((b((f.param_4) < (f.param_3))) != 0) 1122 else 1128
        }
        1130 -> {
            if ((b(((b((f.param_4) <= (1.0))) != 0) || ((b((f.fVar34) < (0.5))) != 0))) != 0) 1111 else 1129
        }
        1131 -> {
            if ((b((f.fVar37) < (0.5))) != 0) 1130 else 24
        }
        1132 -> {
            9
        }
        1133 -> {
            f.fVar51 = f32(f32((f.fVar51) - (f.param_3)))
            1132
        }
        1134 -> {
            if ((b((f.param_2) < (f.fVar36))) != 0) 1133 else 24
        }
        1135 -> {
            f.fVar54 = f32(((f32((f32((f.fVar49) - (f.param_16))) - (f.param_2))) / (3.0)))
            24
        }
        1136 -> {
            23
        }
        1137 -> {
            f.fVar49 = f32(f32((f.fVar49) - (f.param_2)))
            1136
        }
        1138 -> {
            if ((b((f.param_3) < (f.fVar36))) != 0) 1137 else 1135
        }
        1139 -> {
            f.fVar49 = f32(((((f.fVar36) * (3.0))) - (f.param_3)))
            1138
        }
        1140 -> {
            16
        }
        1141 -> {
            f.fVar49 = f32(f32((f32((f.fVar51) - (f.param_3))) - (f.param_16)))
            1140
        }
        1142 -> {
            if ((b((f.fVar36) <= (f.param_2))) != 0) 1141 else 1139
        }
        1143 -> {
            if ((b(((b((f.param_16) <= (1.0))) != 0) || ((b((0.5) <= (f.fVar58))) != 0))) != 0) 1134 else 1142
        }
        1144 -> {
            if ((b(((b(((b((0.5) <= (f.fVar52))) != 0) || ((b((f.param_3) <= (1.0))) != 0))) != 0) || ((b((f.fVar40) < (0.5))) != 0))) != 0) 1131 else 1143
        }
        1145 -> {
            f.fVar54 = f32(((f32((f.fVar51) - (f.fVar30))) * (0.5)))
            24
        }
        1146 -> {
            f.fVar30 = f32(f.param_8)
            9
        }
        1147 -> {
            f.fVar51 = f32(f32((f.fVar51) - (f.param_11)))
            1146
        }
        1148 -> {
            if ((b((f32((f.param_11) - (f.fVar36))) < (0.5))) != 0) 1147 else 24
        }
        1149 -> {
            14
        }
        1150 -> {
            f.iVar1 = ((f.param_14) + (-(0x360)))
            1149
        }
        1151 -> {
            f.bVar2 = b((b(sBorrow4(f.param_14, 0x360))) != 0)
            1150
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep36(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1152 -> {
            if ((b(((b((f.fVar29) < (f.param_13))) != 0) && ((b((f32((f.param_5) - (f.fVar36))) < (0.5))) != 0))) != 0) 1151 else 1148
        }
        1153 -> {
            if ((b(((b(((b((f.param_3) <= (f.param_5))) != 0) || ((b((f.param_4) <= (f.param_5))) != 0))) != 0) || ((b((0.5) <= (f.fVar41))) != 0))) != 0) 1144 else 1152
        }
        1154 -> {
            f.fVar54 = f32(((f.fVar49) / (3.0)))
            24
        }
        1155 -> {
            f.fVar49 = f32(f32((f.fVar49) - (f.param_16)))
            13
        }
        1156 -> {
            f.fVar49 = f32(((((((f.fVar36) * (3.0))) - (f.param_10))) - (f.param_7)))
            1155
        }
        1157 -> {
            12
        }
        1158 -> {
            f.fVar49 = f32(f32((f32((f.fVar51) - (f.param_7))) - (f.param_16)))
            1157
        }
        1159 -> {
            if ((b((0.5) <= (f.fVar55))) != 0) 1158 else 1156
        }
        1160 -> {
            f.fVar49 = f32(((((((f.fVar36) * (3.0))) - (f.param_7))) - (f.param_4)))
            1155
        }
        1161 -> {
            24
        }
        1162 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1161
        }
        1163 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_7))) - (f.param_4))) * (0.5)))
            12
        }
        1164 -> {
            if ((b((f.param_14) < (0x360))) != 0) 1163 else 1160
        }
        1165 -> {
            if ((b((f.param_13) <= (f.fVar56))) != 0) 1159 else 1164
        }
        1166 -> {
            24
        }
        1167 -> {
            f.fVar54 = f32(f.fVar49)
            1166
        }
        1168 -> {
            if ((b(((f.bVar4) != 0) || ((b((f.bVar2) != (f.bVar6))) != 0))) != 0) 1167 else 1166
        }
        1169 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1168
        }
        1170 -> {
            f.bVar6 = b((0) != 0)
            1169
        }
        1171 -> {
            f.bVar4 = b((b((f.fVar13) == (0.5))) != 0)
            1170
        }
        1172 -> {
            f.bVar2 = b((b((f.fVar13) < (0.5))) != 0)
            1171
        }
        1173 -> {
            if ((b(!((b((f.fVar13).isNaN())) != 0))) != 0) 1172 else 1169
        }
        1174 -> {
            f.bVar6 = b((1) != 0)
            1173
        }
        1175 -> {
            f.bVar4 = b((0) != 0)
            1174
        }
        1176 -> {
            f.bVar2 = b((0) != 0)
            1175
        }
        1177 -> {
            if ((b((f.param_14) < (0x360))) != 0) 1176 else 1169
        }
        1178 -> {
            f.bVar6 = b((0) != 0)
            1177
        }
        1179 -> {
            f.bVar4 = b((1) != 0)
            1178
        }
        1180 -> {
            f.bVar2 = b((0) != 0)
            1179
        }
        1181 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_7))) - (f.param_4))) * (0.5)))
            1180
        }
        1182 -> {
            f.fVar54 = f32(f.fVar49)
            1166
        }
        1183 -> {
            if ((b((0x35f) < (f.param_14))) != 0) 1182 else 1166
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep37(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1184 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1183
        }
        1185 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_10))) - (f.param_7))) * (0.5)))
            1184
        }
        1186 -> {
            if ((b((f.fVar55) < (0.5))) != 0) 1185 else 1166
        }
        1187 -> {
            f.fVar54 = f32(f32((f.fVar36) - (f.param_7)))
            1186
        }
        1188 -> {
            if ((b((f.fVar56) < (f.param_13))) != 0) 1181 else 10
        }
        1189 -> {
            if ((b(((b((f.param_4) < (f.param_16))) != 0) || ((b((f.param_16) <= (1.0))) != 0))) != 0) 1188 else 11
        }
        1190 -> {
            f.fVar54 = f32(f.fVar49)
            24
        }
        1191 -> {
            if ((b((b((f.iVar1) < (0))) == (f.bVar2))) != 0) 1190 else 24
        }
        1192 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1191
        }
        1193 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_8))) - (f.param_5))) * (0.5)))
            1192
        }
        1194 -> {
            f.iVar1 = ((f.param_14) + (-(0x480)))
            14
        }
        1195 -> {
            f.bVar2 = b((b(sBorrow4(f.param_14, 0x480))) != 0)
            1194
        }
        1196 -> {
            if ((b(((b(((b(((b((f.param_4) <= (f.param_5))) != 0) || ((b((f.param_13) <= (f.fVar29))) != 0))) != 0) || ((b((f.param_7) <= (f.param_8))) != 0))) != 0) || ((b((0.5) <= (f.fVar41))) != 0))) != 0) 1189 else 1195
        }
        1197 -> {
            if ((b(((b(((b((f.param_3) <= (f.param_4))) != 0) || ((b((0.5) <= (f.fVar34))) != 0))) != 0) || ((b((0.5) <= (f.fVar37))) != 0))) != 0) 1153 else 1196
        }
        1198 -> {
            f.fVar54 = f32(f32((f.fVar36) - (f.param_6)))
            24
        }
        1199 -> {
            18
        }
        1200 -> {
            if ((b((0.5) < (f.fVar13))) != 0) 1199 else 24
        }
        1201 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_3))) - (f.param_2))) * (0.5)))
            1200
        }
        1202 -> {
            9
        }
        1203 -> {
            f.fVar51 = f32(f32((f.fVar51) - (f.param_6)))
            1202
        }
        1204 -> {
            if ((b((f.fVar36) <= (f.param_3))) != 0) 1203 else 1201
        }
        1205 -> {
            if ((b((f.fVar36) <= (f.param_2))) != 0) 1198 else 1204
        }
        1206 -> {
            f.fVar54 = f32(((f.fVar49) / (3.0)))
            24
        }
        1207 -> {
            if ((b((f.bVar2) == (f.bVar4))) != 0) 1206 else 24
        }
        1208 -> {
            f.fVar54 = f32(((((f.fVar49) / (3.0))) * (0.5)))
            1207
        }
        1209 -> {
            f.fVar49 = f32(((((((((f.fVar36) * (3.0))) - (f.param_6))) - (f.param_16))) - (f.param_2)))
            1208
        }
        1210 -> {
            f.bVar2 = b((b((((f.param_14) + (-(0x360)))) < (0))) != 0)
            1209
        }
        1211 -> {
            f.bVar4 = b((b(sBorrow4(f.param_14, 0x360))) != 0)
            1210
        }
        1212 -> {
            f.bVar2 = b((b(((b((f.param_16) < (f.param_3))) != 0) && ((b((((f.param_14) + (-(0x360)))) < (0))) != 0))) != 0)
            1208
        }
        1213 -> {
            f.bVar4 = b((b(((b((f.param_16) < (f.param_3))) != 0) && ((b(sBorrow4(f.param_14, 0x360))) != 0))) != 0)
            1212
        }
        1214 -> {
            f.fVar49 = f32(((((((((f.fVar36) * (3.0))) - (f.param_3))) - (f.param_2))) - (f.param_16)))
            1213
        }
        1215 -> {
            if ((b((f.fVar36) <= (f.param_3))) != 0) 1211 else 1214
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep38(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1216 -> {
            20
        }
        1217 -> {
            f.iVar1 = ((f.param_14) + (-(0x360)))
            1216
        }
        1218 -> {
            f.bVar2 = b((b(sBorrow4(f.param_14, 0x360))) != 0)
            1217
        }
        1219 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_6))) - (f.param_16))) * (0.5)))
            1218
        }
        1220 -> {
            if ((b((f.fVar36) <= (f.param_2))) != 0) 1219 else 1215
        }
        1221 -> {
            if ((b(((b(((b((0.5) <= (f.fVar58))) != 0) || ((b((f.param_16) <= (1.0))) != 0))) != 0) || ((b((f.param_13) <= (f.fVar18))) != 0))) != 0) 1205 else 1220
        }
        1222 -> {
            f.fVar54 = f32(((f.fVar54) * (0.5)))
            24
        }
        1223 -> {
            if ((b((DAT_0012f058) < (f.fVar10))) != 0) 1222 else 24
        }
        1224 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_8))) - (f.param_5))) * (0.5)))
            1223
        }
        1225 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_8))) - (f.param_5))) * (0.5)))
            24
        }
        1226 -> {
            f.fVar54 = f32(((((((((((f.fVar36) * (3.0))) - (f.param_8))) - (f.param_5))) - (f.param_3))) / (3.0)))
            24
        }
        1227 -> {
            if ((b((f.param_5) <= (f.param_3))) != 0) 15 else 1226
        }
        1228 -> {
            if ((b((f.param_14) < (0x480))) != 0) 1224 else 1227
        }
        1229 -> {
            if ((b(((b(((b((0.5) <= (f32((f.param_5) - (f.fVar36))))) != 0) || ((b((f.param_13) <= (f.fVar29))) != 0))) != 0) || ((b(((b((f.param_6) <= (f.param_8))) != 0) || ((b((0.5) <= (f.fVar41))) != 0))) != 0))) != 0) 1221 else 1228
        }
        1230 -> {
            f.fVar54 = f32(f.fVar49)
            24
        }
        1231 -> {
            if ((b(((f.bVar4) != 0) || ((b((f.bVar2) != (f.bVar6))) != 0))) != 0) 1230 else 24
        }
        1232 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1231
        }
        1233 -> {
            f.bVar6 = b((0) != 0)
            1232
        }
        1234 -> {
            f.bVar4 = b((b((f.fVar13) == (0.5))) != 0)
            1233
        }
        1235 -> {
            f.bVar2 = b((b((f.fVar13) < (0.5))) != 0)
            1234
        }
        1236 -> {
            if ((b(!((b((f.fVar13).isNaN())) != 0))) != 0) 1235 else 1232
        }
        1237 -> {
            f.bVar6 = b((1) != 0)
            1236
        }
        1238 -> {
            f.bVar4 = b((0) != 0)
            1237
        }
        1239 -> {
            f.bVar2 = b((0) != 0)
            1238
        }
        1240 -> {
            if ((b((f.param_14) < (0x480))) != 0) 1239 else 1232
        }
        1241 -> {
            f.bVar6 = b((0) != 0)
            1240
        }
        1242 -> {
            f.bVar4 = b((1) != 0)
            1241
        }
        1243 -> {
            f.bVar2 = b((0) != 0)
            1242
        }
        1244 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_7))) - (f.param_4))) * (0.5)))
            1243
        }
        1245 -> {
            if ((b(((b(((b((f.param_6) <= (f.param_7))) != 0) || ((b(((b(((b((0.5) <= (f.fVar37))) != 0) || ((b((f.param_13) <= (f.fVar56))) != 0))) != 0) || ((b((f.param_8) <= (f.param_7))) != 0))) != 0))) != 0) || ((b((0.5) <= (f.fVar34))) != 0))) != 0) 1229 else 1244
        }
        1246 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            24
        }
        1247 -> {
            f.fVar49 = f32(f32((f32((f.fVar51) - (f.param_6))) - (f.param_3)))
            16
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep39(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1248 -> {
            if ((b(((b((f.param_6) <= (3.9))) != 0) || ((b(((b((f.fVar40) <= (0.0))) != 0) || ((b((0.0) <= (f.fVar52))) != 0))) != 0))) != 0) 1247 else 16
        }
        1249 -> {
            f.fVar54 = f32(f.fVar49)
            24
        }
        1250 -> {
            if ((b((0x47f) < (f.param_14))) != 0) 1249 else 24
        }
        1251 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1250
        }
        1252 -> {
            f.fVar49 = f32(((((((((((f.fVar36) * (3.0))) - (f.param_16))) - (f.param_6))) - (f.param_3))) / (3.0)))
            1251
        }
        1253 -> {
            if ((b(((b((f.param_3) <= (f.param_16))) != 0) || ((b((f.param_16) <= (1.0))) != 0))) != 0) 1248 else 1252
        }
        1254 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            24
        }
        1255 -> {
            f.fVar49 = f32(f32((f32((f.fVar51) - (f.param_8))) - (f.param_5)))
            1254
        }
        1256 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_8))) - (f.param_5))) * (0.5)))
            1254
        }
        1257 -> {
            if ((b(((b((0x47f) < (f.param_14))) != 0) || ((b((f.fVar10) <= (0.3))) != 0))) != 0) 1255 else 1256
        }
        1258 -> {
            if ((b(((b((f.param_16) <= (f.param_5))) != 0) || ((b(((b(((b((f.param_13) <= (f.fVar29))) != 0) || ((b((f.param_3) <= (f.param_5))) != 0))) != 0) || ((b((0.5) <= (f.fVar41))) != 0))) != 0))) != 0) 1253 else 1257
        }
        1259 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_6))) - (f.param_3))) * (0.5)))
            24
        }
        1260 -> {
            19
        }
        1261 -> {
            f.fVar49 = f32(((((((f.fVar36) * (3.0))) - (f.param_6))) - (f.param_3)))
            1260
        }
        1262 -> {
            if ((b(((b((f.param_16) < (f.param_3))) != 0) && ((b((1.0) < (f.param_16))) != 0))) != 0) 1261 else 17
        }
        1263 -> {
            f.fVar54 = f32(((f.fVar54) * (0.5)))
            24
        }
        1264 -> {
            if ((b((0.5) <= (f32((f.param_6) - (f.param_8))))) != 0) 18 else 24
        }
        1265 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_8))) - (f.param_5))) * (0.5)))
            1264
        }
        1266 -> {
            15
        }
        1267 -> {
            if ((b((0x47f) < (f.param_14))) != 0) 1266 else 1265
        }
        1268 -> {
            f.fVar54 = f32(f.fVar49)
            24
        }
        1269 -> {
            if ((b((b((f.iVar1) < (0))) == (f.bVar2))) != 0) 1268 else 24
        }
        1270 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            1269
        }
        1271 -> {
            f.iVar1 = ((f.param_14) + (-(0x480)))
            20
        }
        1272 -> {
            f.bVar2 = b((b(sBorrow4(f.param_14, 0x480))) != 0)
            1271
        }
        1273 -> {
            f.fVar49 = f32(((f32((f.fVar49) - (f.param_16))) / (3.0)))
            1272
        }
        1274 -> {
            f.fVar49 = f32(((((((f.fVar36) * (3.0))) - (f.param_8))) - (f.param_5)))
            19
        }
        1275 -> {
            if ((b(((b((f.param_5) <= (f.param_16))) != 0) || ((b((f.param_16) <= (1.0))) != 0))) != 0) 1267 else 1274
        }
        1276 -> {
            if ((b(((b((f.param_13) <= (f.fVar29))) != 0) || ((b(((b((f.param_4) <= (f.param_5))) != 0) || ((b((0.5) <= (f.fVar41))) != 0))) != 0))) != 0) 1262 else 1275
        }
        1277 -> {
            f.fVar54 = f32(((f.fVar54) * (0.6)))
            24
        }
        1278 -> {
            if ((b((f.param_14) < (0x360))) != 0) 1277 else 24
        }
        1279 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_10))) - (f.param_7))) * (0.5)))
            1278
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep40(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1280 -> {
            f.fVar54 = f32(((((((((((f.fVar36) * (3.0))) - (f.param_6))) - (f.param_3))) - (f.param_16))) / (3.0)))
            24
        }
        1281 -> {
            17
        }
        1282 -> {
            if ((b(((b((f.param_3) <= (f.param_16))) != 0) || ((b((f.param_16) <= (0.0))) != 0))) != 0) 1281 else 1280
        }
        1283 -> {
            if ((b((f.fVar55) < (0.5))) != 0) 1279 else 1282
        }
        1284 -> {
            f.fVar54 = f32(((f.fVar49) * (0.5)))
            24
        }
        1285 -> {
            f.fVar54 = f32(((f.fVar49) * (0.75)))
            24
        }
        1286 -> {
            if ((b((0.6) <= (f.fVar13))) != 0) 1284 else 1285
        }
        1287 -> {
            f.fVar49 = f32(((f32((f32((f.fVar51) - (f.param_7))) - (f.param_4))) * (0.5)))
            1286
        }
        1288 -> {
            f.fVar54 = f32(((((f32((f32((f.fVar51) - (f.param_7))) - (f.param_4))) * (0.5))) * (0.5)))
            24
        }
        1289 -> {
            f.fVar54 = f32(((((((((((((f.fVar36) * (3.0))) - (f.param_7))) - (f.param_4))) - (f.param_16))) / (3.0))) * (0.5)))
            24
        }
        1290 -> {
            if ((b(((b((f.param_4) <= (f.param_16))) != 0) || ((b((f.param_16) <= (1.0))) != 0))) != 0) 21 else 1289
        }
        1291 -> {
            22
        }
        1292 -> {
            if ((b(((b((0x35f) < (f.param_14))) != 0) || ((b((f32((f.param_6) - (f.param_7))) <= (DAT_0012ef38))) != 0))) != 0) 1291 else 1290
        }
        1293 -> {
            if ((b((DAT_0012eef8) < (f32((f.param_6) - (f.param_7))))) != 0) 1287 else 1292
        }
        1294 -> {
            f.fVar54 = f32(((f32((f32((f.fVar51) - (f.param_7))) - (f.param_4))) * (0.5)))
            24
        }
        1295 -> {
            f.fVar54 = f32(((f32((f.fVar49) - (f.param_16))) / (3.0)))
            24
        }
        1296 -> {
            f.fVar49 = f32(((((((f.fVar36) * (3.0))) - (f.param_7))) - (f.param_4)))
            23
        }
        1297 -> {
            if ((b(((b((f.param_4) <= (f.param_16))) != 0) || ((b((f.param_16) <= (1.0))) != 0))) != 0) 1294 else 1296
        }
        1298 -> {
            if ((b((f.param_14) < (0x480))) != 0) 1293 else 22
        }
        1299 -> {
            if ((b(((b((f.param_13) <= (f.fVar56))) != 0) || ((b((0.5) <= (f.fVar37))) != 0))) != 0) 1283 else 1298
        }
        1300 -> {
            if ((b(((b((f.param_5) <= (f.param_4))) != 0) || ((b((f.param_6) <= (f.param_7))) != 0))) != 0) 1276 else 1299
        }
        1301 -> {
            if ((b(((b((0.5) <= (f.fVar34))) != 0) || ((b(((b((f.param_3) <= (f.param_4))) != 0) && ((b((f.param_6) <= (f.param_7))) != 0))) != 0))) != 0) 1258 else 1300
        }
        1302 -> {
            if ((b(((b((f.param_13) <= (f32((f.param_6) - (f.param_3))))) != 0) || ((b((0.5) <= (f.fVar52))) != 0))) != 0) 1245 else 1301
        }
        1303 -> {
            if ((b((0.5) <= (f.fVar40))) != 0) 1197 else 1302
        }
        1304 -> {
            f.fVar30 = f32(f.param_2)
            1303
        }
        1305 -> {
            f.fVar55 = f32(f32((f.param_10) - (f.fVar36)))
            1304
        }
        1306 -> {
            f.fVar51 = f32(f32((f.fVar36) + (f.fVar36)))
            1305
        }
        1307 -> {
            f.fVar29 = f32(f32((f.param_8) - (f.param_5)))
            1306
        }
        1308 -> {
            f.fVar41 = f32(f32((f.param_8) - (f.fVar36)))
            1307
        }
        1309 -> {
            f.fVar37 = f32(f32((f.param_4) - (f.fVar36)))
            1308
        }
        1310 -> {
            f.fVar56 = f32(f32((f.param_7) - (f.param_4)))
            1309
        }
        1311 -> {
            f.fVar34 = f32(f32((f.param_7) - (f.fVar36)))
            1310
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep41(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1312 -> {
            f.fVar52 = f32(f32((f.param_3) - (f.fVar36)))
            1311
        }
        1313 -> {
            f.fVar40 = f32(f32((f.param_6) - (f.fVar36)))
            1312
        }
        1314 -> {
            24
        }
        1315 -> {
            if ((b(((b(((b((5.5) < (f.fVar36))) != 0) && ((b(((b((1.5) < (f32((f.fVar36) - (f.param_5))))) != 0) || ((b(((b((1.5) < (f.fVar49))) != 0) || ((b((1.5) < (f.fVar43))) != 0))) != 0))) != 0))) != 0) || ((run { run { f.fVar58 = f32(f32((f.param_16) - (f.fVar36))); f32(f32((f.param_16) - (f.fVar36))) }; b(((b((f.param_16) < (0.0))) != 0) && ((b((2.0) < (f.fVar58))) != 0)) }) != 0))) != 0) 1314 else 1313
        }
        1316 -> {
            f.fVar10 = f32(f32((f.param_3) - (f.param_5)))
            1315
        }
        1317 -> {
            f.fVar54 = f32(0.0)
            1316
        }
        1318 -> {
            f.fVar43 = f32(f32((f.fVar36) - (f.param_4)))
            1317
        }
        1319 -> {
            f.fVar49 = f32(f32((f.fVar36) - (f.param_3)))
            1318
        }
        1320 -> {
            f.fVar36 = f32(((f32((f.fVar49) + (f.param_31))) * (0.5)))
            1319
        }
        1321 -> {
            4
        }
        1322 -> {
            if ((b(((b(((b((f.dVar48) <= (4.1))) != 0) || ((b(((b(((b(((b(((b((4.5) <= (f.param_3))) != 0) && ((b((4.5) <= (f.param_4))) != 0))) != 0) && ((b((4.5) <= (f.param_5))) != 0))) != 0) || ((b((f.dVar31) <= (4.1))) != 0))) != 0) || ((b(((b((f.fVar28) <= (1.0))) != 0) && ((b(((b((f.fVar27) <= (1.0))) != 0) && ((b((f.fVar23) <= (1.0))) != 0))) != 0))) != 0))) != 0))) != 0) && ((run { run { f.fVar36 = f32(f.param_31); f32(f.param_31) }; b(((b((8.5) < (f.param_22))) != 0) && ((run { run { f.fVar36 = f32(f.param_31); f32(f.param_31) }; b(((b((2.0) < (f.fVar22))) != 0) && ((run { run { f.fVar36 = f32(f.param_31); f32(f.param_31) }; b((8.5) < (f.param_23)) }) != 0)) }) != 0)) }) != 0))) != 0) 1321 else 1319
        }
        1323 -> {
            if ((b(((b(((b((f.dVar48) <= (4.1))) != 0) || ((b((f.dVar31) <= (4.1))) != 0))) != 0) || ((b(((b((DAT_0012f050) <= (f.dVar46))) != 0) && ((b(((b((DAT_0012f050) <= (f.dVar53))) != 0) && ((b((DAT_0012f050) <= (f.dVar38))) != 0))) != 0))) != 0))) != 0) 1322 else 1319
        }
        1324 -> {
            if ((b((0.5) <= (f.fVar26))) != 0) 4 else 1323
        }
        1325 -> {
            f.fVar36 = f32(f.param_31)
            1319
        }
        1326 -> {
            if ((b((f.dVar31) < (DAT_0012f030))) != 0) 1325 else 1319
        }
        1327 -> {
            f.fVar36 = f32(f.fVar49)
            1319
        }
        1328 -> {
            if ((f.bVar2) != 0) 1327 else 1319
        }
        1329 -> {
            f.fVar36 = f32(f.param_31)
            1328
        }
        1330 -> {
            f.bVar2 = b((b((f.param_5) < (3.5))) != 0)
            1329
        }
        1331 -> {
            if ((b(((b((f.param_31) < (5.0))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_5).isNaN())) != 0)) }) != 0))) != 0) 1330 else 1329
        }
        1332 -> {
            f.bVar2 = b((1) != 0)
            1331
        }
        1333 -> {
            if ((b(((b((3.5) <= (f.param_3))) != 0) && ((b((3.5) <= (f.param_4))) != 0))) != 0) 1332 else 1319
        }
        1334 -> {
            if ((b(((b((f.dVar31) < (3.9))) != 0) || ((b((3.9) <= (f.dVar42))) != 0))) != 0) 1326 else 1333
        }
        1335 -> {
            if ((b(((b(((b((f.param_22) <= (7.0))) != 0) || ((b((54.0) <= (f.param_20))) != 0))) != 0) || ((b(((b((f.param_23) <= (7.0))) != 0) || ((b((f.fVar22) <= (DAT_0012f058))) != 0))) != 0))) != 0) 1324 else 1334
        }
        1336 -> {
            if ((b(((b((f.param_3) < (f.param_31))) != 0) && ((b(((b(((b((0x870) < (f.param_14))) != 0) && ((b((f.fVar49) < (f.param_31))) != 0))) != 0) && ((b((f.fVar26) < (DAT_0012ee78))) != 0))) != 0))) != 0) 1335 else 1319
        }
        1337 -> {
            f.fVar36 = f32(f.fVar49)
            1336
        }
        1338 -> {
            f.fVar36 = f32(3.2)
            1319
        }
        1339 -> {
            if ((b(!((f.bVar3) != 0))) != 0) 1338 else 1319
        }
        1340 -> {
            f.fVar36 = f32(f.fVar49)
            1339
        }
        1341 -> {
            f.bVar3 = b((b((f.param_30) < (48.0))) != 0)
            1340
        }
        1342 -> {
            if ((b(((b(((b(!((f.bVar4) != 0))) != 0) && ((b((f.bVar2) == (f.bVar6))) != 0))) != 0) && ((run { run { f.bVar3 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_30).isNaN())) != 0)) }) != 0))) != 0) 1341 else 1340
        }
        1343 -> {
            f.bVar3 = b((0) != 0)
            1342
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep42(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1344 -> {
            f.bVar6 = b((0) != 0)
            1343
        }
        1345 -> {
            f.bVar4 = b((b((f.param_22) == (9.0))) != 0)
            1344
        }
        1346 -> {
            f.bVar2 = b((b((f.param_22) < (9.0))) != 0)
            1345
        }
        1347 -> {
            if ((b(!((b((f.param_22).isNaN())) != 0))) != 0) 1346 else 1343
        }
        1348 -> {
            f.bVar6 = b((1) != 0)
            1347
        }
        1349 -> {
            f.bVar4 = b((0) != 0)
            1348
        }
        1350 -> {
            f.bVar2 = b((0) != 0)
            1349
        }
        1351 -> {
            if ((b((f.param_23) <= (9.0))) != 0) 1350 else 1343
        }
        1352 -> {
            f.bVar6 = b((0) != 0)
            1351
        }
        1353 -> {
            f.bVar4 = b((0) != 0)
            1352
        }
        1354 -> {
            f.bVar2 = b((0) != 0)
            1353
        }
        1355 -> {
            if ((b(((b(((b(((b((f.param_16) <= (2.5))) != 0) || ((b((f.fVar22) <= (2.0))) != 0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b(((b((f.param_22) <= (9.0))) != 0) && ((b((f.param_23) <= (9.0))) != 0)) }) != 0))) != 0) && ((b(((b(((b(((b((f.dVar48) <= (DAT_0012f070))) != 0) || ((b((3.0) <= (f.param_16))) != 0))) != 0) || ((b((48.0) <= (f.param_20))) != 0))) != 0) || ((b(((b((f.param_22) <= (10.0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((f.param_23) <= (10.0)) }) != 0))) != 0))) != 0))) != 0) 1354 else 1319
        }
        1356 -> {
            if ((b(((b((f.param_20) <= (42.0))) != 0) || ((b((2.8) <= (f.dVar42))) != 0))) != 0) 1337 else 1355
        }
        1357 -> {
            f.fVar36 = f32(3.2)
            1319
        }
        1358 -> {
            f.fVar36 = f32(3.0)
            1319
        }
        1359 -> {
            if ((b(!((f.bVar2) != 0))) != 0) 1358 else 1319
        }
        1360 -> {
            f.fVar36 = f32(3.2)
            1359
        }
        1361 -> {
            f.bVar2 = b((b((f.dVar48) < (DAT_0012f068))) != 0)
            1360
        }
        1362 -> {
            if ((b(((b((3.9) <= (f.dVar32))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar48).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f068).isNaN())) != 0))) != 0)) }) != 0))) != 0) 1361 else 1360
        }
        1363 -> {
            f.bVar2 = b((1) != 0)
            1362
        }
        1364 -> {
            f.fVar36 = f32(2.7)
            1319
        }
        1365 -> {
            if ((b(((b(((b(((b((f.dVar48) <= (DAT_0012f070))) != 0) || ((b((DAT_0012f038) <= (f.dVar48))) != 0))) != 0) || ((b((f.param_22) <= (9.0))) != 0))) != 0) || ((b(((b((f.fVar22) <= (2.0))) != 0) || ((b((f.param_23) <= (9.0))) != 0))) != 0))) != 0) 1363 else 1364
        }
        1366 -> {
            f.fVar36 = f32(2.8)
            1319
        }
        1367 -> {
            if ((b((f.param_20) <= (42.0))) != 0) 1366 else 1319
        }
        1368 -> {
            f.fVar36 = f32(3.0)
            1367
        }
        1369 -> {
            if ((b(((b((DAT_0012f070) <= (f.dVar48))) != 0) || ((b(((b(((b((f.dVar48) <= (2.4))) != 0) || ((b((f.dVar32) <= (3.9))) != 0))) != 0) && ((b(((b((f.dVar50) <= (DAT_0012ee80))) != 0) || ((b((f.param_23) <= (7.0))) != 0))) != 0))) != 0))) != 0) 1365 else 1368
        }
        1370 -> {
            if ((b((72.0) < (f.param_20))) != 0) 1357 else 1369
        }
        1371 -> {
            if ((b(((b(((b((f.param_20) <= (42.0))) != 0) || ((b((0x23f) < (f.param_14))) != 0))) != 0) || ((b((9.5) <= (f.param_19))) != 0))) != 0) 1370 else 1319
        }
        1372 -> {
            if ((b(((b(((b((2.9) <= (f.dVar53))) != 0) || ((b((f.fVar49) < (3.0))) != 0))) != 0) || ((b(((b(((b((DAT_0012f068) <= (f.dVar46))) != 0) && ((b((DAT_0012f068) <= (f.dVar38))) != 0))) != 0) || ((b(((run { run { f.dVar50 = f.fVar22; f.fVar22 }; b(((b((DAT_0012f038) <= (f.dVar48))) != 0) && ((b(((b((3.3) <= (f.dVar48))) != 0) || ((b((f.dVar50) <= (DAT_0012ee80))) != 0))) != 0)) }) != 0) || ((b(((b((f.dVar50) <= (0.85))) != 0) || ((b((f.param_23) <= (6.5))) != 0))) != 0))) != 0))) != 0))) != 0) 1356 else 1371
        }
        1373 -> {
            if ((b(((b(((b(((b(((b((f.fVar49) <= (3.5))) != 0) || ((b(((b(((b((2.9) <= (f.dVar46))) != 0) && ((b((2.9) <= (f.dVar53))) != 0))) != 0) && ((b((2.9) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b(((b(((b((f.param_19) <= (10.0))) != 0) || ((b((45.0) <= (f.param_20))) != 0))) != 0) || ((b((f.param_22) <= (7.0))) != 0))) != 0))) != 0) || ((b((f.param_23) <= (7.0))) != 0))) != 0) || ((b(((b(((b((f.fVar12) <= (DAT_0012f058))) != 0) && ((b((kotlin.math.abs(f32((f.param_5) - (f.param_3)))) <= (DAT_0012f058))) != 0))) != 0) && ((b((f.fVar25) <= (DAT_0012f058))) != 0))) != 0))) != 0) 1372 else 1319
        }
        1374 -> {
            7
        }
        1375 -> {
            f.dVar42 = ((f.dVar31) + (3.5))
            1374
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep43(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1376 -> {
            4
        }
        1377 -> {
            if ((b((f.dVar50) < (DAT_0012ef60))) != 0) 1376 else 1375
        }
        1378 -> {
            if ((b(((b(((b(((b(((b(((b((2.4) <= (f.dVar46))) != 0) && ((b((2.4) <= (f.dVar53))) != 0))) != 0) && ((b((2.4) <= (f.dVar38))) != 0))) != 0) && ((b((f.dVar48) <= (DAT_0012f050))) != 0))) != 0) || ((b(((b((f.param_20) <= (54.0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((7.0) <= (f.param_23)) }) != 0))) != 0))) != 0) && ((b(((b(((b(((b(((b((f.dVar46) <= (3.4))) != 0) && ((b(((b((f.dVar53) <= (3.4))) != 0) && ((b((f.dVar38) <= (3.4))) != 0))) != 0))) != 0) || ((b(((b((f.param_20) <= (84.0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((4.5) <= (f.fVar49)) }) != 0))) != 0))) != 0) && ((run { run { f.fVar36 = f32(3.5); f32(3.5) }; b((3.5) < (f.param_31)) }) != 0))) != 0) && ((b((f.dVar31) <= (DAT_0012f078))) != 0))) != 0))) != 0) 1377 else 1319
        }
        1379 -> {
            6
        }
        1380 -> {
            if ((b(((b(((b(((b(((b((3.9) <= (f.dVar48))) != 0) || ((b(((b((f.dVar48) <= (DAT_0012f030))) != 0) || ((run { run { f.fVar36 = f32(f.param_16); f32(f.param_16) }; b((6.5) <= (f.param_23)) }) != 0))) != 0))) != 0) && ((b(((b((0.5) <= (f.fVar18))) != 0) || ((b(((b((f.param_20) <= (45.0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((DAT_0012f060) <= (f.dVar42)) }) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.param_16) <= (3.5))) != 0) || ((b(((b((f.param_20) <= (48.0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((4.3) <= (f.dVar42)) }) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b(((b((3.9) <= (f.dVar38))) != 0) || ((b(((b(((b(((b((3.9) <= (f.dVar53))) != 0) || ((b((3.9) <= (f.dVar46))) != 0))) != 0) || ((b((DAT_0012f040) <= (f.dVar59))) != 0))) != 0) || ((b(((b((f.param_20) <= (60.0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((DAT_0012f058) <= (f32((f.fVar49) - (f.param_16)))) }) != 0))) != 0))) != 0))) != 0) && ((b(((b((3.9) <= (f.dVar38))) != 0) || ((b(((b(((b(((b((3.9) <= (f.dVar53))) != 0) || ((b((3.9) <= (f.dVar46))) != 0))) != 0) || ((b(((b((f.param_20) <= (60.0))) != 0) || ((b(((b(((b((1.2) <= (f.dVar50))) != 0) || ((b((3.5) < (f.fVar49))) != 0))) != 0) || ((b((11.0) <= (f.param_19))) != 0))) != 0))) != 0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((DAT_0012f058) <= (f32((f.fVar49) - (f.param_16)))) }) != 0))) != 0))) != 0))) != 0) && ((b(((run { run { f.fVar36 = f32(3.2); f32(3.2) }; b(((b((4.3) <= (f.dVar42))) != 0) && ((b((3.5) < (f.param_16))) != 0)) }) != 0) && ((b(((b((48.0) < (f.param_20))) != 0) && ((run { run { f.dVar59 = DAT_0012f030; DAT_0012f030 }; run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((0.6) <= (f.dVar50)) }) != 0))) != 0))) != 0))) != 0))) != 0) 1379 else 1319
        }
        1381 -> {
            f.fVar36 = f32(3.5)
            1319
        }
        1382 -> {
            if ((f.bVar2) != 0) 1381 else 1319
        }
        1383 -> {
            f.fVar36 = f32(f.fVar49)
            1382
        }
        1384 -> {
            f.bVar2 = b((b((f.dVar48) < (DAT_0012f030))) != 0)
            1383
        }
        1385 -> {
            if ((b(((b((DAT_0012f010) < (f.dVar50))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar48).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f030).isNaN())) != 0))) != 0)) }) != 0))) != 0) 1384 else 1383
        }
        1386 -> {
            f.bVar2 = b((0) != 0)
            1385
        }
        1387 -> {
            if ((b(((b((0.35) <= (f.dVar59))) != 0) || ((b(((b((2.8) < (f.dVar48))) != 0) && ((b((1.5) < (f.fVar22))) != 0))) != 0))) != 0) 1380 else 1386
        }
        1388 -> {
            if ((b(((b(((b((0.25) < (f.fVar18))) != 0) && ((b((f.dVar59) <= (0.35))) != 0))) != 0) || ((b(((b((f.param_16) < (3.5))) != 0) && ((b((f.param_19) < (10.0))) != 0))) != 0))) != 0) 1378 else 1387
        }
        1389 -> {
            f.dVar59 = f.fVar18
            1388
        }
        1390 -> {
            if ((b(((b(((b(((b((f.dVar42) <= (3.4))) != 0) || ((b(((b(((b(((b((f.fVar23) <= (0.6))) != 0) && ((b((f.fVar24) <= (0.6))) != 0))) != 0) || ((b(((b((3.3) <= (f.dVar46))) != 0) && ((b(((b((3.3) <= (f.dVar53))) != 0) && ((b((3.3) <= (f.dVar38))) != 0))) != 0))) != 0))) != 0) && ((b(((b((DAT_0012f060) <= (f.dVar46))) != 0) || ((b(((b((DAT_0012f060) <= (f.dVar53))) != 0) || ((b((DAT_0012f060) <= (f.dVar38))) != 0))) != 0))) != 0))) != 0))) != 0) || ((b(((b((DAT_0012f048) <= (f.dVar48))) != 0) || ((run { run { f.dVar50 = f.fVar22; f.fVar22 }; b(((b((f.dVar50) <= (DAT_0012f010))) != 0) && ((b((39.0) <= (f.param_20))) != 0)) }) != 0))) != 0))) != 0) || ((b(((b((f.param_22) < (6.0))) != 0) && ((b((f.param_23) < (6.0))) != 0))) != 0))) != 0) 1373 else 1389
        }
        1391 -> {
            f.fVar36 = f32(2.8)
            1319
        }
        1392 -> {
            5
        }
        1393 -> {
            if ((b((f.param_19) < (10.0))) != 0) 1392 else 1391
        }
        1394 -> {
            6
        }
        1395 -> {
            f.dVar59 = 2.5
            1394
        }
        1396 -> {
            if ((b(((b((10.0) <= (f.param_19))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((3.5) < (f.fVar49)) }) != 0))) != 0) 1395 else 1319
        }
        1397 -> {
            if ((b(((b((f.param_20) <= (38.0))) != 0) || ((b((48.0) <= (f.param_20))) != 0))) != 0) 1393 else 1396
        }
        1398 -> {
            f.fVar36 = f32(f.fVar49)
            1319
        }
        1399 -> {
            if ((b(((f.bVar4) != 0) || ((b((f.bVar2) != (f.bVar6))) != 0))) != 0) 1398 else 1319
        }
        1400 -> {
            f.fVar36 = f32(3.2)
            1399
        }
        1401 -> {
            f.bVar6 = b((0) != 0)
            1400
        }
        1402 -> {
            f.bVar4 = b((b((f.param_20) == (42.0))) != 0)
            1401
        }
        1403 -> {
            f.bVar2 = b((b((f.param_20) < (42.0))) != 0)
            1402
        }
        1404 -> {
            if ((b(!((b((f.param_20).isNaN())) != 0))) != 0) 1403 else 1400
        }
        1405 -> {
            f.bVar6 = b((1) != 0)
            1404
        }
        1406 -> {
            f.bVar4 = b((0) != 0)
            1405
        }
        1407 -> {
            f.bVar2 = b((0) != 0)
            1406
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep44(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1408 -> {
            if ((b((f.fVar49) < (3.0))) != 0) 1407 else 1400
        }
        1409 -> {
            f.bVar6 = b((0) != 0)
            1408
        }
        1410 -> {
            f.bVar4 = b((1) != 0)
            1409
        }
        1411 -> {
            f.bVar2 = b((0) != 0)
            1410
        }
        1412 -> {
            f.fVar36 = f32(f32(((f.dVar42) * (0.5))))
            1319
        }
        1413 -> {
            f.dVar42 = ((f.dVar42) + (f.dVar59))
            7
        }
        1414 -> {
            f.dVar59 = 2.8
            6
        }
        1415 -> {
            if ((b((f.fVar49) < (3.5))) != 0) 1411 else 5
        }
        1416 -> {
            if ((b(((b((f.dVar48) <= (DAT_0012ee90))) != 0) || ((b((10.0) <= (f.param_19))) != 0))) != 0) 1397 else 1415
        }
        1417 -> {
            if ((b(((b(((b((2.4) <= (f.dVar48))) != 0) || ((b(((b(((b((72.0) <= (f.param_20))) != 0) || ((b((f.param_22) <= (7.0))) != 0))) != 0) || ((b((f.fVar22) <= (DAT_0012ef60))) != 0))) != 0))) != 0) || ((b(((b((f.param_23) <= (7.0))) != 0) || ((b((f.fVar49) <= (2.5))) != 0))) != 0))) != 0) 1390 else 1416
        }
        1418 -> {
            f.fVar36 = f32(3.5)
            1319
        }
        1419 -> {
            if ((b(((f.bVar6) != 0) || ((b((f.bVar4) != (f.bVar3))) != 0))) != 0) 1418 else 1319
        }
        1420 -> {
            f.fVar36 = f32(f.param_16)
            1419
        }
        1421 -> {
            f.bVar3 = b((0) != 0)
            1420
        }
        1422 -> {
            f.bVar6 = b((b((f.param_5) == (3.5))) != 0)
            1421
        }
        1423 -> {
            f.bVar4 = b((b((f.param_5) < (3.5))) != 0)
            1422
        }
        1424 -> {
            if ((b(!((b((f.param_5).isNaN())) != 0))) != 0) 1423 else 1420
        }
        1425 -> {
            f.bVar3 = b((1) != 0)
            1424
        }
        1426 -> {
            f.bVar6 = b((0) != 0)
            1425
        }
        1427 -> {
            f.bVar4 = b((0) != 0)
            1426
        }
        1428 -> {
            if ((b(((b(!((f.bVar5) != 0))) != 0) && ((b((f.bVar2) == (f.bVar7))) != 0))) != 0) 1427 else 1420
        }
        1429 -> {
            f.bVar3 = b((0) != 0)
            1428
        }
        1430 -> {
            f.bVar6 = b((1) != 0)
            1429
        }
        1431 -> {
            f.bVar4 = b((0) != 0)
            1430
        }
        1432 -> {
            f.bVar7 = b((0) != 0)
            1431
        }
        1433 -> {
            f.bVar5 = b((b((f.param_4) == (3.5))) != 0)
            1432
        }
        1434 -> {
            f.bVar2 = b((b((f.param_4) < (3.5))) != 0)
            1433
        }
        1435 -> {
            if ((b(!((b((f.param_4).isNaN())) != 0))) != 0) 1434 else 1431
        }
        1436 -> {
            f.bVar7 = b((1) != 0)
            1435
        }
        1437 -> {
            f.bVar5 = b((0) != 0)
            1436
        }
        1438 -> {
            f.bVar2 = b((0) != 0)
            1437
        }
        1439 -> {
            if ((b(((b(!((f.bVar6) != 0))) != 0) && ((b((f.bVar4) == (f.bVar3))) != 0))) != 0) 1438 else 1431
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep45(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1440 -> {
            f.bVar7 = b((0) != 0)
            1439
        }
        1441 -> {
            f.bVar5 = b((1) != 0)
            1440
        }
        1442 -> {
            f.bVar2 = b((0) != 0)
            1441
        }
        1443 -> {
            f.bVar3 = b((0) != 0)
            1442
        }
        1444 -> {
            f.bVar6 = b((b((f.param_3) == (3.5))) != 0)
            1443
        }
        1445 -> {
            f.bVar4 = b((b((f.param_3) < (3.5))) != 0)
            1444
        }
        1446 -> {
            if ((b(!((b((f.param_3).isNaN())) != 0))) != 0) 1445 else 1442
        }
        1447 -> {
            f.bVar3 = b((1) != 0)
            1446
        }
        1448 -> {
            f.bVar6 = b((0) != 0)
            1447
        }
        1449 -> {
            f.bVar4 = b((0) != 0)
            1448
        }
        1450 -> {
            if ((f.bVar2) != 0) 1449 else 1442
        }
        1451 -> {
            f.bVar3 = b((0) != 0)
            1450
        }
        1452 -> {
            f.bVar6 = b((1) != 0)
            1451
        }
        1453 -> {
            f.bVar4 = b((0) != 0)
            1452
        }
        1454 -> {
            f.bVar2 = b((b((f.param_16) < (4.5))) != 0)
            1453
        }
        1455 -> {
            if ((b(((b((3.5) < (f.param_16))) != 0) && ((run { run { f.bVar2 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_16).isNaN())) != 0)) }) != 0))) != 0) 1454 else 1453
        }
        1456 -> {
            f.bVar2 = b((0) != 0)
            1455
        }
        1457 -> {
            if ((b(((b((4.0) <= (f.param_16))) != 0) || ((run { run { f.fVar36 = f32(f.param_16); f32(f.param_16) }; b((f.param_16) <= (3.5)) }) != 0))) != 0) 1456 else 1319
        }
        1458 -> {
            f.fVar36 = f32(4.2)
            1319
        }
        1459 -> {
            if ((b(((b(((b(((b((f.param_5) <= (3.5))) != 0) || ((b((f.param_4) <= (3.5))) != 0))) != 0) || ((b((f.param_3) <= (3.5))) != 0))) != 0) || ((b((0.25) <= (f.fVar18))) != 0))) != 0) 1457 else 1458
        }
        1460 -> {
            if ((b(((b(((b(((b(((b(((b((f.dVar46) <= (2.9))) != 0) || ((b((f.dVar53) <= (2.9))) != 0))) != 0) || ((b((f.dVar38) <= (2.9))) != 0))) != 0) || ((b((7.5) <= (f.param_22))) != 0))) != 0) && ((b(((b(((b((f.dVar46) <= (3.3))) != 0) || ((b((f.dVar53) <= (3.3))) != 0))) != 0) || ((b((f.dVar38) <= (3.3))) != 0))) != 0))) != 0) || ((b(((b((f.param_20) <= (42.0))) != 0) || ((b((3.9) <= (f.dVar42))) != 0))) != 0))) != 0) 1417 else 1459
        }
        1461 -> {
            f.dVar42 = f.fVar49
            1460
        }
        1462 -> {
            f.fVar36 = f32(4.2)
            1319
        }
        1463 -> {
            if ((b((0.6) <= (f.fVar22))) != 0) 1462 else 1319
        }
        1464 -> {
            f.fVar36 = f32(4.7)
            1463
        }
        1465 -> {
            if ((b(((b(((b(((b(((b((DAT_0012f048) <= (f.dVar42))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((f.fVar22) <= (2.0)) }) != 0))) != 0) && ((b(((b((DAT_0012f048) <= (f.dVar42))) != 0) || ((b(((b(((b((f.fVar22) <= (0.85))) != 0) || ((b((10.0) <= (f.param_19))) != 0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((f.param_23) <= (6.5)) }) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b((54.0) <= (f.param_20))) != 0) || ((b((5.0) <= (f.param_16))) != 0))) != 0) || ((run { run { f.fVar36 = f32(f.param_16); f32(f.param_16) }; b((f.dVar42) <= (3.9)) }) != 0))) != 0))) != 0) && ((b(((b(((b((0x23f) < (f.param_14))) != 0) || ((b((f.param_22) <= (8.0))) != 0))) != 0) || ((run { run { f.fVar36 = f32(f.fVar49); f32(f.fVar49) }; b((f.param_23) <= (8.0)) }) != 0))) != 0))) != 0) 1464 else 1319
        }
        1466 -> {
            f.dVar42 = f.param_16
            1465
        }
        1467 -> {
            if ((b(((b(((b((f.dVar46) <= (DAT_0012f060))) != 0) || ((b((f.dVar53) <= (DAT_0012f060))) != 0))) != 0) || ((b(((b((f.dVar38) <= (DAT_0012f060))) != 0) || ((b(((b(((b((f.param_20) <= (54.0))) != 0) && ((b(((b((f.param_16) <= (DAT_0012f060))) != 0) || ((b((f.param_20) <= (42.0))) != 0))) != 0))) != 0) || ((b((4.0) <= (f.fVar49))) != 0))) != 0))) != 0))) != 0) 1461 else 1466
        }
        1468 -> {
            f.fVar28 = f32(kotlin.math.abs(f32((f.param_3) - (f.param_4))))
            1467
        }
        1469 -> {
            f.dVar32 = f.param_18
            1468
        }
        1470 -> {
            f.fVar27 = f32(kotlin.math.abs(f32((f.param_3) - (f.param_5))))
            1469
        }
        1471 -> {
            f.dVar31 = f.param_31
            1470
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep46(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1472 -> {
            f.fVar26 = f32(f32((f.param_31) - (f.param_16)))
            1471
        }
        1473 -> {
            f.fVar25 = f32(kotlin.math.abs(f32((f.param_5) - (f.param_4))))
            1472
        }
        1474 -> {
            f.fVar24 = f32(f32((f.param_4) - (f.param_3)))
            1473
        }
        1475 -> {
            f.fVar23 = f32(kotlin.math.abs(f32((f.param_4) - (f.param_5))))
            1474
        }
        1476 -> {
            f.fVar49 = f32(2.7)
            1475
        }
        1477 -> {
            if ((b((DAT_0012f038) <= (f.fVar49))) != 0) 1476 else 1475
        }
        1478 -> {
            f.fVar49 = f32(((f32((f.param_12) + (f.param_16))) * (0.5)))
            1477
        }
        1479 -> {
            if ((b((15.0) < (f.param_21))) != 0) 1478 else 1475
        }
        1480 -> {
            f.fVar49 = f32(3.0)
            1475
        }
        1481 -> {
            if ((b(((b((f.param_12) <= (3.5))) != 0) || ((b((f.param_12) <= (f.param_16))) != 0))) != 0) 1479 else 1480
        }
        1482 -> {
            if ((b(((b((f.dVar48) < (DAT_0012f030))) != 0) && ((b(((b(((b(((b((1.5) < (f.fVar17))) != 0) || ((b((1.5) < (f.fVar15))) != 0))) != 0) || ((b((1.5) < (f.fVar16))) != 0))) != 0) && ((b(((b((f.param_4) < (f.param_3))) != 0) || ((b(((b((f.param_5) < (f.param_4))) != 0) && ((b((8.0) < (f.param_22))) != 0))) != 0))) != 0))) != 0))) != 0) 1481 else 1475
        }
        1483 -> {
            f.fVar49 = f32(f.param_12)
            1475
        }
        1484 -> {
            if ((b(((f.bVar5) != 0) || ((b((f.bVar3) != (f.bVar7))) != 0))) != 0) 1483 else 1475
        }
        1485 -> {
            f.bVar7 = b((0) != 0)
            1484
        }
        1486 -> {
            f.bVar5 = b((b((f.param_3) == (6.0))) != 0)
            1485
        }
        1487 -> {
            f.bVar3 = b((b((f.param_3) < (6.0))) != 0)
            1486
        }
        1488 -> {
            if ((b(!((b((f.param_3).isNaN())) != 0))) != 0) 1487 else 1484
        }
        1489 -> {
            f.bVar7 = b((1) != 0)
            1488
        }
        1490 -> {
            f.bVar5 = b((0) != 0)
            1489
        }
        1491 -> {
            f.bVar3 = b((0) != 0)
            1490
        }
        1492 -> {
            if ((b(((b(!((f.bVar4) != 0))) != 0) && ((b((f.bVar2) == (f.bVar6))) != 0))) != 0) 1491 else 1484
        }
        1493 -> {
            f.bVar7 = b((0) != 0)
            1492
        }
        1494 -> {
            f.bVar5 = b((1) != 0)
            1493
        }
        1495 -> {
            f.bVar3 = b((0) != 0)
            1494
        }
        1496 -> {
            f.bVar6 = b((0) != 0)
            1495
        }
        1497 -> {
            f.bVar4 = b((b((f.param_4) == (6.0))) != 0)
            1496
        }
        1498 -> {
            f.bVar2 = b((b((f.param_4) < (6.0))) != 0)
            1497
        }
        1499 -> {
            if ((b(!((b((f.param_4).isNaN())) != 0))) != 0) 1498 else 1495
        }
        1500 -> {
            f.bVar6 = b((1) != 0)
            1499
        }
        1501 -> {
            f.bVar4 = b((0) != 0)
            1500
        }
        1502 -> {
            f.bVar2 = b((0) != 0)
            1501
        }
        1503 -> {
            if ((b((6.0) < (f.param_5))) != 0) 1502 else 1495
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep47(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1504 -> {
            f.bVar6 = b((0) != 0)
            1503
        }
        1505 -> {
            f.bVar4 = b((1) != 0)
            1504
        }
        1506 -> {
            f.bVar2 = b((0) != 0)
            1505
        }
        1507 -> {
            f.fVar49 = f32(f.fVar23)
            1475
        }
        1508 -> {
            if ((b(!((f.bVar3) != 0))) != 0) 1507 else 1475
        }
        1509 -> {
            f.fVar49 = f32(2.7)
            1508
        }
        1510 -> {
            f.bVar3 = b((b((f.param_16) < (DAT_0012f038))) != 0)
            1509
        }
        1511 -> {
            if ((b(((b(((b(!((f.bVar4) != 0))) != 0) && ((b((f.bVar2) == (f.bVar6))) != 0))) != 0) && ((run { run { f.bVar3 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.param_16).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f038).isNaN())) != 0))) != 0)) }) != 0))) != 0) 1510 else 1509
        }
        1512 -> {
            f.bVar3 = b((0) != 0)
            1511
        }
        1513 -> {
            f.bVar6 = b((0) != 0)
            1512
        }
        1514 -> {
            f.bVar4 = b((b((f.param_21) == (15.0))) != 0)
            1513
        }
        1515 -> {
            f.bVar2 = b((b((f.param_21) < (15.0))) != 0)
            1514
        }
        1516 -> {
            if ((b(!((b((f.param_21).isNaN())) != 0))) != 0) 1515 else 1512
        }
        1517 -> {
            f.bVar6 = b((1) != 0)
            1516
        }
        1518 -> {
            f.bVar4 = b((0) != 0)
            1517
        }
        1519 -> {
            f.bVar2 = b((0) != 0)
            1518
        }
        1520 -> {
            if ((b((f32((f.param_24) + (f.param_25))) < (0.0))) != 0) 1519 else 1512
        }
        1521 -> {
            f.bVar6 = b((0) != 0)
            1520
        }
        1522 -> {
            f.bVar4 = b((1) != 0)
            1521
        }
        1523 -> {
            f.bVar2 = b((0) != 0)
            1522
        }
        1524 -> {
            3
        }
        1525 -> {
            if ((b(((b((f.param_26) < (1))) != 0) && ((b(((b(((b((60.0) < (f.param_20))) != 0) && ((b((f.param_16) < (f.fVar23))) != 0))) != 0) && ((b((3.0) < (f.fVar23))) != 0))) != 0))) != 0) 1524 else 1523
        }
        1526 -> {
            if ((b(((b((f.fVar39) <= (0.5))) != 0) || ((b((f32((f.param_5) - (f.param_3))) <= (0.5))) != 0))) != 0) 1525 else 1475
        }
        1527 -> {
            if ((b((kotlin.math.abs(f32((f.fVar12) - (f.fVar39)))) <= (0.5))) != 0) 1506 else 1526
        }
        1528 -> {
            f.fVar49 = f32(f.fVar23)
            1475
        }
        1529 -> {
            if ((b(((f.bVar4) != 0) || ((b((f.bVar2) != (f.bVar6))) != 0))) != 0) 1528 else 1475
        }
        1530 -> {
            f.fVar49 = f32(2.8)
            1529
        }
        1531 -> {
            f.bVar6 = b((0) != 0)
            1530
        }
        1532 -> {
            f.bVar4 = b((b((f.dVar46) == (2.8))) != 0)
            1531
        }
        1533 -> {
            f.bVar2 = b((b((f.dVar46) < (2.8))) != 0)
            1532
        }
        1534 -> {
            if ((b(!((b((f.dVar46).isNaN())) != 0))) != 0) 1533 else 1530
        }
        1535 -> {
            f.bVar6 = b((1) != 0)
            1534
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep48(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1536 -> {
            f.bVar4 = b((0) != 0)
            1535
        }
        1537 -> {
            f.bVar2 = b((0) != 0)
            1536
        }
        1538 -> {
            if ((b(((b(!((f.bVar5) != 0))) != 0) && ((b((f.bVar3) == (f.bVar7))) != 0))) != 0) 1537 else 1530
        }
        1539 -> {
            f.bVar6 = b((0) != 0)
            1538
        }
        1540 -> {
            f.bVar4 = b((1) != 0)
            1539
        }
        1541 -> {
            f.bVar2 = b((0) != 0)
            1540
        }
        1542 -> {
            f.bVar7 = b((0) != 0)
            1541
        }
        1543 -> {
            f.bVar5 = b((b((f.dVar53) == (2.8))) != 0)
            1542
        }
        1544 -> {
            f.bVar3 = b((b((f.dVar53) < (2.8))) != 0)
            1543
        }
        1545 -> {
            if ((b(!((b((f.dVar53).isNaN())) != 0))) != 0) 1544 else 1541
        }
        1546 -> {
            f.bVar7 = b((1) != 0)
            1545
        }
        1547 -> {
            f.bVar5 = b((0) != 0)
            1546
        }
        1548 -> {
            f.bVar3 = b((0) != 0)
            1547
        }
        1549 -> {
            if ((b(((b(!((f.bVar4) != 0))) != 0) && ((b((f.bVar2) == (f.bVar6))) != 0))) != 0) 1548 else 1541
        }
        1550 -> {
            f.bVar7 = b((0) != 0)
            1549
        }
        1551 -> {
            f.bVar5 = b((1) != 0)
            1550
        }
        1552 -> {
            f.bVar3 = b((0) != 0)
            1551
        }
        1553 -> {
            f.bVar6 = b((0) != 0)
            1552
        }
        1554 -> {
            f.bVar4 = b((b((f.param_16) == (3.3))) != 0)
            1553
        }
        1555 -> {
            f.bVar2 = b((b((f.param_16) < (3.3))) != 0)
            1554
        }
        1556 -> {
            if ((b(!((b((f.param_16).isNaN())) != 0))) != 0) 1555 else 1552
        }
        1557 -> {
            f.bVar6 = b((1) != 0)
            1556
        }
        1558 -> {
            f.bVar4 = b((0) != 0)
            1557
        }
        1559 -> {
            f.bVar2 = b((0) != 0)
            1558
        }
        1560 -> {
            if ((b((7.5) < (f.param_19))) != 0) 1559 else 1552
        }
        1561 -> {
            f.bVar6 = b((0) != 0)
            1560
        }
        1562 -> {
            f.bVar4 = b((1) != 0)
            1561
        }
        1563 -> {
            f.bVar2 = b((0) != 0)
            1562
        }
        1564 -> {
            if ((b(((b((72.0) < (f.param_20))) != 0) && ((b(((b(((b((1.2) < (f.fVar16))) != 0) || ((b((1.2) < (f.fVar17))) != 0))) != 0) || ((b((1.2) < (f.fVar15))) != 0))) != 0))) != 0) 1563 else 1475
        }
        1565 -> {
            f.fVar49 = f32(2.7)
            1475
        }
        1566 -> {
            if ((b(((b(((b((f.param_21) <= (15.0))) != 0) || ((b((DAT_0012f030) <= (f.param_16))) != 0))) != 0) || ((b((1.0) <= (f32((f32((f32((f.param_28) - (f.param_27))) - (f.param_27))) + (f.param_29))))) != 0))) != 0) 1564 else 1565
        }
        1567 -> {
            f.fVar49 = f32(2.7)
            1475
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep49(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1568 -> {
            if ((b(((b(((b((f.param_5) <= (4.0))) != 0) || ((b((f.fVar23) <= (f.param_16))) != 0))) != 0) || ((b((f.fVar23) <= (3.0))) != 0))) != 0) 1566 else 3
        }
        1569 -> {
            if ((b(((b(((b((f.param_3) <= (f.param_4))) != 0) && ((b((f.param_4) <= (f.param_5))) != 0))) != 0) || ((b(((b((f.fVar13) <= (0.5))) != 0) && ((b((f.fVar14) <= (0.5))) != 0))) != 0))) != 0) 1527 else 1568
        }
        1570 -> {
            if ((b(((b(((b((1.5) < (f.fVar17))) != 0) || ((b((1.5) < (f.fVar15))) != 0))) != 0) || ((b((1.5) < (f.fVar16))) != 0))) != 0) 1569 else 1475
        }
        1571 -> {
            f.fVar49 = f32(f.fVar23)
            1570
        }
        1572 -> {
            f.fVar23 = f32(((f32((f.param_12) + (f.param_16))) * (0.5)))
            2
        }
        1573 -> {
            0
        }
        1574 -> {
            1
        }
        1575 -> {
            f.dVar32 = ((f.dVar32) * (0.25))
            1574
        }
        1576 -> {
            if ((b((DAT_0012f058) < (f.dVar32))) != 0) 1575 else 1573
        }
        1577 -> {
            if ((b(((b((f.dVar31) <= (DAT_0012f050))) != 0) || ((b(((b((f.fVar22) <= (2.0))) != 0) && ((b((42.0) <= (f.param_20))) != 0))) != 0))) != 0) 1576 else 2
        }
        1578 -> {
            0
        }
        1579 -> {
            if ((b(((b(((b(((b(((b((f.param_16) < (f.param_5))) != 0) && ((b((f.dVar53) < (((f.dVar31) + (0.3))))) != 0))) != 0) && ((b((f.param_3) < (f.param_16))) != 0))) != 0) && ((b((42.0) < (f.param_20))) != 0))) != 0) || ((b(((b(((b(((b((f.fVar17) < (1.0))) != 0) && ((b((f.fVar16) < (1.0))) != 0))) != 0) && ((b(((b((0.0) < (f.fVar15))) != 0) && ((b(((b((0.0) < (f.fVar16))) != 0) && ((b((f.fVar15) < (1.0))) != 0))) != 0))) != 0))) != 0) && ((b((0.0) < (f.fVar17))) != 0))) != 0))) != 0) 1578 else 2
        }
        1580 -> {
            if ((b(((b((f.dVar31) <= (DAT_0012f048))) != 0) || ((b(((b((f.param_17) <= (f.param_18))) != 0) && ((b(((b((f.fVar18) <= (0.25))) != 0) || ((b((f.fVar22) <= (1.0))) != 0))) != 0))) != 0))) != 0) 1577 else 1579
        }
        1581 -> {
            f.fVar23 = f32(f32(((f.dVar32) + (f.dVar42))))
            2
        }
        1582 -> {
            f.dVar32 = ((f.dVar32) * (0.75))
            1
        }
        1583 -> {
            if ((b(((b(((b((3.4) < (f.dVar46))) != 0) && ((b(((b((3.4) < (f.dVar53))) != 0) && ((b((3.4) < (f.dVar38))) != 0))) != 0))) != 0) && ((b(((b((f.param_16) <= (5.5))) != 0) || ((b(((b(((b((3.9) <= (f.dVar53))) != 0) && ((b((3.9) <= (f.dVar46))) != 0))) != 0) && ((b((3.9) <= (f.dVar38))) != 0))) != 0))) != 0))) != 0) 1582 else 2
        }
        1584 -> {
            f.fVar23 = f32(f32(((((f.dVar32) * (0.75))) + (f.dVar42))))
            2
        }
        1585 -> {
            if ((b(((b((f.dVar46) <= (3.8))) != 0) || ((b((0x35f) < (f.param_14))) != 0))) != 0) 1583 else 1584
        }
        1586 -> {
            if ((b(((b(((b((f32((f.param_16) - (f.param_12))) <= (1.5))) != 0) || ((b((f.dVar31) <= (3.9))) != 0))) != 0) || ((b((3.9) <= (f.dVar42))) != 0))) != 0) 1580 else 1585
        }
        1587 -> {
            f.dVar42 = f.param_12
            1586
        }
        1588 -> {
            f.dVar32 = f32((f.param_16) - (f.param_12))
            1587
        }
        1589 -> {
            if ((b((f.param_12) < (f.param_16))) != 0) 1588 else 2
        }
        1590 -> {
            0
        }
        1591 -> {
            if ((b(((b((f.param_16) <= (3.0))) != 0) || ((b((f.fVar22) <= (DAT_0012ee90))) != 0))) != 0) 1590 else 2
        }
        1592 -> {
            if ((b(((b(((b(((b((f.fVar17) <= (0.0))) != 0) || ((b((f.fVar16) <= (0.0))) != 0))) != 0) || ((b((f.param_16) <= (f.param_12))) != 0))) != 0) || ((b((DAT_0012f048) <= (f.dVar31))) != 0))) != 0) 1589 else 1591
        }
        1593 -> {
            f.dVar31 = f.param_16
            1592
        }
        1594 -> {
            if ((b(((b(((b(((b(((b(((b((f.param_12) < (f.param_16))) != 0) && ((b((f.fVar18) < (0.5))) != 0))) != 0) && ((b(((b((f.fVar19) < (0.5))) != 0) && ((b(((b(((b((f.fVar20) < (0.5))) != 0) && ((b((0.0) < (f.fVar21))) != 0))) != 0) && ((b((0.0) < (f.fVar20))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.fVar21) < (0.5))) != 0) && ((b((0.0) < (f.fVar19))) != 0))) != 0))) != 0) || ((b(((b((f.fVar21) < (2.0))) != 0) && ((b(((b(((b((f.fVar20) < (2.0))) != 0) && ((b((f.fVar19) < (2.0))) != 0))) != 0) && ((b(((b((5.0) < (f.param_8))) != 0) && ((b(((b(((b((5.0) < (f.param_7))) != 0) && ((b((5.0) < (f.param_6))) != 0))) != 0) && ((b((f.fVar18) < (0.5))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b(((b((f.fVar18) < (DAT_0012f040))) != 0) && ((b((f.fVar19) < (1.0))) != 0))) != 0) && ((b((f.fVar21) < (1.0))) != 0))) != 0) && ((b(((b(((b((f.fVar20) < (1.0))) != 0) && ((b((f.param_12) < (f.param_16))) != 0))) != 0) && ((b(((b((f.param_12) < (f.param_17))) != 0) && ((b((f.param_17) < (4.0))) != 0))) != 0))) != 0))) != 0))) != 0) 0 else 1593
        }
        1595 -> {
            2
        }
        1596 -> {
            if ((b((2.0) < (((kotlin.math.abs(f.param_16)) - (f.param_12))))) != 0) 1595 else 1594
        }
        1597 -> {
            f.fVar23 = f32(f.param_12)
            1596
        }
        1598 -> {
            if ((b((f.param_14) < (0x241))) != 0) 1482 else 1597
        }
        1599 -> {
            f.fVar49 = f32(f.param_12)
            1598
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustmentrangeStep50(f: adjustmentrangeFrame, pc: Int): Int {
    return when (pc) {
        1600 -> {
            f.fVar22 = f32(f32((f.param_18) - (f.param_16)))
            1599
        }
        1601 -> {
            f.dVar48 = f.param_16
            1600
        }
        1602 -> {
            f.fVar39 = f32(f32((f.param_5) - (f.param_4)))
            1601
        }
        1603 -> {
            f.dVar38 = f.param_5
            1602
        }
        1604 -> {
            f.dVar46 = f.param_3
            1603
        }
        1605 -> {
            f.dVar53 = f.param_4
            1604
        }
        1606 -> {
            f.fVar36 = f32(3.5)
            1605
        }
        1607 -> {
            f.fVar21 = f32(f32((f.param_17) - (f.param_8)))
            1606
        }
        1608 -> {
            f.fVar20 = f32(f32((f.param_17) - (f.param_7)))
            1607
        }
        1609 -> {
            f.fVar19 = f32(f32((f.param_17) - (f.param_6)))
            1608
        }
        1610 -> {
            f.fVar18 = f32(f32((f.param_17) - (f.param_16)))
            1609
        }
        1611 -> {
            f.fVar17 = f32(f32((f.param_16) - (f.param_5)))
            1610
        }
        1612 -> {
            f.fVar16 = f32(f32((f.param_16) - (f.param_4)))
            1611
        }
        1613 -> {
            f.fVar15 = f32(f32((f.param_16) - (f.param_3)))
            1612
        }
        1614 -> {
            f.fVar14 = f32(f32((f.param_4) - (f.param_5)))
            1613
        }
        1615 -> {
            f.fVar13 = f32(f32((f.param_3) - (f.param_4)))
            1614
        }
        1616 -> {
            f.fVar12 = f32(kotlin.math.abs(f32((f.param_4) - (f.param_3))))
            1615
        }
        else -> error("bad C state: $pc")
    }
}

private fun adjustment_range(param_1: Ptr, param_2: Double, param_3: Double, param_4: Double, param_5: Double, param_6: Double, param_7: Double, param_8: Double, param_9: Double, param_10: Double, param_11: Double, param_12: Double, param_13: Double, param_14: Int, param_15: Double, param_16: Double, param_17: Double, param_18: Double, param_19: Double, param_20: Double, param_21: Double, param_22: Double, param_23: Double, param_24: Double, param_25: Double, param_26: Int, param_27: Double, param_28: Double, param_29: Double, param_30: Double, param_31: Double): Double {
    val f = adjustmentrangeFrame(param_1, f32(param_2), f32(param_3), f32(param_4), f32(param_5), f32(param_6), f32(param_7), f32(param_8), f32(param_9), f32(param_10), f32(param_11), f32(param_12), f32(param_13), param_14, f32(param_15), f32(param_16), f32(param_17), f32(param_18), f32(param_19), f32(param_20), f32(param_21), f32(param_22), f32(param_23), f32(param_24), f32(param_25), param_26, f32(param_27), f32(param_28), f32(param_29), f32(param_30), f32(param_31))
    var pc = 1616
    while (true) {
        pc = when (pc / 32) {
            0 -> adjustmentrangeStep0(f, pc)
            1 -> adjustmentrangeStep1(f, pc)
            2 -> adjustmentrangeStep2(f, pc)
            3 -> adjustmentrangeStep3(f, pc)
            4 -> adjustmentrangeStep4(f, pc)
            5 -> adjustmentrangeStep5(f, pc)
            6 -> adjustmentrangeStep6(f, pc)
            7 -> adjustmentrangeStep7(f, pc)
            8 -> adjustmentrangeStep8(f, pc)
            9 -> adjustmentrangeStep9(f, pc)
            10 -> adjustmentrangeStep10(f, pc)
            11 -> adjustmentrangeStep11(f, pc)
            12 -> adjustmentrangeStep12(f, pc)
            13 -> adjustmentrangeStep13(f, pc)
            14 -> adjustmentrangeStep14(f, pc)
            15 -> adjustmentrangeStep15(f, pc)
            16 -> adjustmentrangeStep16(f, pc)
            17 -> adjustmentrangeStep17(f, pc)
            18 -> adjustmentrangeStep18(f, pc)
            19 -> adjustmentrangeStep19(f, pc)
            20 -> adjustmentrangeStep20(f, pc)
            21 -> adjustmentrangeStep21(f, pc)
            22 -> adjustmentrangeStep22(f, pc)
            23 -> adjustmentrangeStep23(f, pc)
            24 -> adjustmentrangeStep24(f, pc)
            25 -> adjustmentrangeStep25(f, pc)
            26 -> adjustmentrangeStep26(f, pc)
            27 -> adjustmentrangeStep27(f, pc)
            28 -> adjustmentrangeStep28(f, pc)
            29 -> adjustmentrangeStep29(f, pc)
            30 -> adjustmentrangeStep30(f, pc)
            31 -> adjustmentrangeStep31(f, pc)
            32 -> adjustmentrangeStep32(f, pc)
            33 -> adjustmentrangeStep33(f, pc)
            34 -> adjustmentrangeStep34(f, pc)
            35 -> adjustmentrangeStep35(f, pc)
            36 -> adjustmentrangeStep36(f, pc)
            37 -> adjustmentrangeStep37(f, pc)
            38 -> adjustmentrangeStep38(f, pc)
            39 -> adjustmentrangeStep39(f, pc)
            40 -> adjustmentrangeStep40(f, pc)
            41 -> adjustmentrangeStep41(f, pc)
            42 -> adjustmentrangeStep42(f, pc)
            43 -> adjustmentrangeStep43(f, pc)
            44 -> adjustmentrangeStep44(f, pc)
            45 -> adjustmentrangeStep45(f, pc)
            46 -> adjustmentrangeStep46(f, pc)
            47 -> adjustmentrangeStep47(f, pc)
            48 -> adjustmentrangeStep48(f, pc)
            49 -> adjustmentrangeStep49(f, pc)
            50 -> adjustmentrangeStep50(f, pc)
            else -> error("bad C chunk: $pc")
        }
        if (pc == C_DONE) {
            return f.result
        }
    }
}

private class ClippingfilterFrame(var param_1: Ptr, var param_2: Ptr, var param_3: Ptr, var param_4: Double, var param_5: Double, var param_6: Double, var param_7: Double, var param_8: Double, var param_9: Double, var param_10: Double, var param_11: Double, var param_12: Double, var param_13: Double, var param_14: Int) {
    var bVar1: Int = 0
    var pjVar2: Ptr = Ptr(ByteArray(0), 0)
    var pjVar3: Ptr = Ptr(ByteArray(0), 0)
    var pjVar4: Ptr = Ptr(ByteArray(0), 0)
    var iVar5: Int = 0
    var uVar6: Int = 0
    var iVar7: Int = 0
    var lVar8: Long = 0L
    var lVar9: Long = 0L
    var lVar10: Long = 0L
    var uVar11: Long = 0L
    var bVar12: Int = 0
    var bVar13: Int = 0
    var bVar14: Int = 0
    var bVar15: Int = 0
    var bVar16: Int = 0
    var bVar17: Int = 0
    var lVar18: Long = 0L
    var iVar19: Int = 0
    var iVar20: Int = 0
    var iVar21: Int = 0
    var iVar22: Int = 0
    var iVar23: Int = 0
    var iVar24: Int = 0
    var fVar25: Double = 0.0
    var fVar26: Double = 0.0
    var uVar27: Int = 0
    var dVar28: Double = 0.0
    var dVar29: Double = 0.0
    var dVar30: Double = 0.0
    var fVar31: Double = 0.0
    var fVar32: Double = 0.0
    var fVar33: Double = 0.0
    var fVar34: Double = 0.0
    var fVar35: Double = 0.0
    var dVar36: Double = 0.0
    var fVar37: Double = 0.0
    var dVar38: Double = 0.0
    var dVar39: Double = 0.0
    var fVar40: Double = 0.0
    var dVar41: Double = 0.0
    var fVar42: Double = 0.0
    var fVar43: Double = 0.0
    var fVar44: Double = 0.0
    var dVar45: Double = 0.0
    var fVar46: Double = 0.0
    var fVar47: Double = 0.0
    var fVar48: Double = 0.0
    var dVar49: Double = 0.0
    var auVar50: Double = 0.0
    var dVar51: Double = 0.0
    var dVar52: Double = 0.0
    var auVar53: Double = 0.0
    var auVar54: Double = 0.0
    var auVar55: Double = 0.0
    var auVar56: Double = 0.0
    var auVar57: Double = 0.0
    var auVar58: Double = 0.0
    var auVar59: Double = 0.0
    var auVar60: Double = 0.0
    var fVar61: Double = 0.0
    var fVar62: Double = 0.0
    var fVar63: Double = 0.0
    var fVar64: Double = 0.0
    var fVar65: Double = 0.0
    var fVar66: Double = 0.0
    var fVar67: Double = 0.0
    var fVar68: Double = 0.0
    var fVar69: Double = 0.0
    var fVar70: Double = 0.0
    var fVar71: Double = 0.0
    var fVar72: Double = 0.0
    var fVar73: Double = 0.0
    var fVar74: Double = 0.0
    var dVar75: Double = 0.0
    var fVar76: Double = 0.0
    var fVar77: Double = 0.0
    var dVar78: Double = 0.0
    var fVar79: Double = 0.0
    var fVar80: Double = 0.0
    var fVar81: Double = 0.0
    var dVar82: Double = 0.0
    var fVar83: Double = 0.0
    var fVar84: Double = 0.0
    var fVar85: Double = 0.0
    var fVar86: Double = 0.0
    var dVar87: Double = 0.0
    var fVar88: Double = 0.0
    var dVar89: Double = 0.0
    var fVar90: Double = 0.0
    var fVar91: Double = 0.0
    var fVar93: Double = 0.0
    var fVar94: Double = 0.0
    var local_1a0: Ptr = Ptr(ByteArray(0), 0)
    var local_17c: Double = 0.0
    var result: Int = 0
}

private fun ClippingfilterStep0(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        0 -> {
            4828
        }
        1 -> {
            4824
        }
        2 -> {
            4799
        }
        3 -> {
            4798
        }
        4 -> {
            4789
        }
        5 -> {
            4784
        }
        6 -> {
            4782
        }
        7 -> {
            4775
        }
        8 -> {
            4774
        }
        9 -> {
            4773
        }
        10 -> {
            4771
        }
        11 -> {
            4769
        }
        12 -> {
            4768
        }
        13 -> {
            5159
        }
        14 -> {
            5156
        }
        15 -> {
            5149
        }
        16 -> {
            5148
        }
        17 -> {
            5145
        }
        18 -> {
            5135
        }
        19 -> {
            5127
        }
        20 -> {
            5126
        }
        21 -> {
            5114
        }
        22 -> {
            5090
        }
        23 -> {
            5088
        }
        24 -> {
            5077
        }
        25 -> {
            5074
        }
        26 -> {
            5059
        }
        27 -> {
            5057
        }
        28 -> {
            5048
        }
        29 -> {
            5039
        }
        30 -> {
            5018
        }
        31 -> {
            5010
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep1(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        32 -> {
            5007
        }
        33 -> {
            4972
        }
        34 -> {
            4967
        }
        35 -> {
            4954
        }
        36 -> {
            4950
        }
        37 -> {
            4947
        }
        38 -> {
            4945
        }
        39 -> {
            4942
        }
        40 -> {
            4930
        }
        41 -> {
            4901
        }
        42 -> {
            4883
        }
        43 -> {
            4876
        }
        44 -> {
            4845
        }
        45 -> {
            4767
        }
        46 -> {
            4758
        }
        47 -> {
            4749
        }
        48 -> {
            4407
        }
        49 -> {
            4385
        }
        50 -> {
            4383
        }
        51 -> {
            4379
        }
        52 -> {
            4733
        }
        53 -> {
            4699
        }
        54 -> {
            4687
        }
        55 -> {
            4686
        }
        56 -> {
            4683
        }
        57 -> {
            4672
        }
        58 -> {
            4673
        }
        59 -> {
            4671
        }
        60 -> {
            4645
        }
        61 -> {
            4644
        }
        62 -> {
            4643
        }
        63 -> {
            4641
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep2(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        64 -> {
            4639
        }
        65 -> {
            4610
        }
        66 -> {
            4599
        }
        67 -> {
            4576
        }
        68 -> {
            4569
        }
        69 -> {
            4558
        }
        70 -> {
            4545
        }
        71 -> {
            4536
        }
        72 -> {
            4535
        }
        73 -> {
            4531
        }
        74 -> {
            4527
        }
        75 -> {
            4526
        }
        76 -> {
            4513
        }
        77 -> {
            4495
        }
        78 -> {
            4453
        }
        79 -> {
            4595
        }
        80 -> {
            4604
        }
        81 -> {
            4603
        }
        82 -> {
            4443
        }
        83 -> {
            4705
        }
        84 -> {
            4711
        }
        85 -> {
            4707
        }
        86 -> {
            4724
        }
        87 -> {
            4721
        }
        88 -> {
            4376
        }
        89 -> {
            4743
        }
        90 -> {
            4344
        }
        91 -> {
            4348
        }
        92 -> {
            4355
        }
        93 -> {
            4340
        }
        94 -> {
            4221
        }
        95 -> {
            4218
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep3(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        96 -> {
            4300
        }
        97 -> {
            4275
        }
        98 -> {
            4129
        }
        99 -> {
            4146
        }
        100 -> {
            4108
        }
        101 -> {
            4073
        }
        102 -> {
            4005
        }
        103 -> {
            4004
        }
        104 -> {
            3980
        }
        105 -> {
            3974
        }
        106 -> {
            3972
        }
        107 -> {
            3961
        }
        108 -> {
            3923
        }
        109 -> {
            3920
        }
        110 -> {
            3990
        }
        111 -> {
            3986
        }
        112 -> {
            3985
        }
        113 -> {
            3894
        }
        114 -> {
            3893
        }
        115 -> {
            4099
        }
        116 -> {
            4089
        }
        117 -> {
            4085
        }
        118 -> {
            4084
        }
        119 -> {
            3885
        }
        120 -> {
            3808
        }
        121 -> {
            3807
        }
        122 -> {
            3837
        }
        123 -> {
            3822
        }
        124 -> {
            3790
        }
        125 -> {
            3745
        }
        126 -> {
            3744
        }
        127 -> {
            3695
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep4(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        128 -> {
            3708
        }
        129 -> {
            3675
        }
        130 -> {
            3647
        }
        131 -> {
            3629
        }
        132 -> {
            3628
        }
        133 -> {
            3660
        }
        134 -> {
            3658
        }
        135 -> {
            3669
        }
        136 -> {
            3668
        }
        137 -> {
            3667
        }
        138 -> {
            3619
        }
        139 -> {
            3588
        }
        140 -> {
            3557
        }
        141 -> {
            3548
        }
        142 -> {
            3612
        }
        143 -> {
            3596
        }
        144 -> {
            3543
        }
        145 -> {
            1303
        }
        146 -> {
            1293
        }
        147 -> {
            1252
        }
        148 -> {
            1210
        }
        149 -> {
            1194
        }
        150 -> {
            1282
        }
        151 -> {
            1155
        }
        152 -> {
            1154
        }
        153 -> {
            1140
        }
        154 -> {
            1151
        }
        155 -> {
            1128
        }
        156 -> {
            1119
        }
        157 -> {
            1115
        }
        158 -> {
            1110
        }
        159 -> {
            1099
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep5(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        160 -> {
            1095
        }
        161 -> {
            1090
        }
        162 -> {
            1085
        }
        163 -> {
            1074
        }
        164 -> {
            1072
        }
        165 -> {
            1071
        }
        166 -> {
            1064
        }
        167 -> {
            1060
        }
        168 -> {
            1046
        }
        169 -> {
            1036
        }
        170 -> {
            1010
        }
        171 -> {
            933
        }
        172 -> {
            852
        }
        173 -> {
            834
        }
        174 -> {
            850
        }
        175 -> {
            847
        }
        176 -> {
            898
        }
        177 -> {
            859
        }
        178 -> {
            874
        }
        179 -> {
            935
        }
        180 -> {
            934
        }
        181 -> {
            998
        }
        182 -> {
            967
        }
        183 -> {
            969
        }
        184 -> {
            968
        }
        185 -> {
            966
        }
        186 -> {
            958
        }
        187 -> {
            956
        }
        188 -> {
            950
        }
        189 -> {
            949
        }
        190 -> {
            947
        }
        191 -> {
            792
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep6(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        192 -> {
            1013
        }
        193 -> {
            1011
        }
        194 -> {
            775
        }
        195 -> {
            780
        }
        196 -> {
            779
        }
        197 -> {
            608
        }
        198 -> {
            604
        }
        199 -> {
            623
        }
        200 -> {
            622
        }
        201 -> {
            603
        }
        202 -> {
            695
        }
        203 -> {
            675
        }
        204 -> {
            661
        }
        205 -> {
            657
        }
        206 -> {
            644
        }
        207 -> {
            632
        }
        208 -> {
            765
        }
        209 -> {
            757
        }
        210 -> {
            752
        }
        211 -> {
            750
        }
        212 -> {
            746
        }
        213 -> {
            716
        }
        214 -> {
            722
        }
        215 -> {
            594
        }
        216 -> {
            584
        }
        217 -> {
            583
        }
        218 -> {
            591
        }
        219 -> {
            574
        }
        220 -> {
            572
        }
        221 -> {
            563
        }
        222 -> {
            562
        }
        223 -> {
            545
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep7(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        224 -> {
            544
        }
        225 -> {
            540
        }
        226 -> {
            539
        }
        227 -> {
            538
        }
        228 -> {
            531
        }
        229 -> {
            526
        }
        230 -> {
            525
        }
        231 -> {
            1644
        }
        232 -> {
            1629
        }
        233 -> {
            1611
        }
        234 -> {
            1609
        }
        235 -> {
            1604
        }
        236 -> {
            1593
        }
        237 -> {
            1569
        }
        238 -> {
            1542
        }
        239 -> {
            1486
        }
        240 -> {
            1484
        }
        241 -> {
            1426
        }
        242 -> {
            1409
        }
        243 -> {
            1397
        }
        244 -> {
            1382
        }
        245 -> {
            1359
        }
        246 -> {
            1356
        }
        247 -> {
            1347
        }
        248 -> {
            1332
        }
        249 -> {
            3266
        }
        250 -> {
            1780
        }
        251 -> {
            1772
        }
        252 -> {
            1738
        }
        253 -> {
            1743
        }
        254 -> {
            1712
        }
        255 -> {
            1879
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep8(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        256 -> {
            1894
        }
        257 -> {
            1906
        }
        258 -> {
            1959
        }
        259 -> {
            1822
        }
        260 -> {
            2051
        }
        261 -> {
            2010
        }
        262 -> {
            2038
        }
        263 -> {
            2035
        }
        264 -> {
            2033
        }
        265 -> {
            1805
        }
        266 -> {
            3008
        }
        267 -> {
            2953
        }
        268 -> {
            2952
        }
        269 -> {
            3021
        }
        270 -> {
            2786
        }
        271 -> {
            2823
        }
        272 -> {
            2829
        }
        273 -> {
            2862
        }
        274 -> {
            2858
        }
        275 -> {
            2942
        }
        276 -> {
            3093
        }
        277 -> {
            3092
        }
        278 -> {
            3088
        }
        279 -> {
            3107
        }
        280 -> {
            3145
        }
        281 -> {
            3141
        }
        282 -> {
            3214
        }
        283 -> {
            3171
        }
        284 -> {
            3204
        }
        285 -> {
            3203
        }
        286 -> {
            2751
        }
        287 -> {
            2739
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep9(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        288 -> {
            2736
        }
        289 -> {
            2713
        }
        290 -> {
            2700
        }
        291 -> {
            2702
        }
        292 -> {
            2638
        }
        293 -> {
            2631
        }
        294 -> {
            2617
        }
        295 -> {
            2625
        }
        296 -> {
            2554
        }
        297 -> {
            2530
        }
        298 -> {
            2606
        }
        299 -> {
            2587
        }
        300 -> {
            2525
        }
        301 -> {
            2502
        }
        302 -> {
            2446
        }
        303 -> {
            2438
        }
        304 -> {
            2423
        }
        305 -> {
            2268
        }
        306 -> {
            2262
        }
        307 -> {
            2279
        }
        308 -> {
            2259
        }
        309 -> {
            2295
        }
        310 -> {
            2200
        }
        311 -> {
            2193
        }
        312 -> {
            2182
        }
        313 -> {
            2174
        }
        314 -> {
            2167
        }
        315 -> {
            2144
        }
        316 -> {
            2116
        }
        317 -> {
            2104
        }
        318 -> {
            2385
        }
        319 -> {
            2379
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep10(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        320 -> {
            2377
        }
        321 -> {
            2362
        }
        322 -> {
            2359
        }
        323 -> {
            2416
        }
        324 -> {
            2358
        }
        325 -> {
            2353
        }
        326 -> {
            2333
        }
        327 -> {
            2326
        }
        328 -> {
            2324
        }
        329 -> {
            2323
        }
        330 -> {
            2318
        }
        331 -> {
            2315
        }
        332 -> {
            2074
        }
        333 -> {
            2068
        }
        334 -> {
            2058
        }
        335 -> {
            2061
        }
        336 -> {
            1789
        }
        337 -> {
            3475
        }
        338 -> {
            3462
        }
        339 -> {
            3485
        }
        340 -> {
            3479
        }
        341 -> {
            3440
        }
        342 -> {
            3382
        }
        343 -> {
            3349
        }
        344 -> {
            3344
        }
        345 -> {
            3328
        }
        346 -> {
            3322
        }
        347 -> {
            3303
        }
        348 -> {
            3306
        }
        349 -> {
            3285
        }
        350 -> {
            3283
        }
        351 -> {
            3267
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep11(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        352 -> {
            524
        }
        353 -> {
            429
        }
        354 -> {
            449
        }
        355 -> {
            424
        }
        356 -> {
            423
        }
        357 -> {
            492
        }
        358 -> {
            478
        }
        359 -> {
            461
        }
        360 -> {
            514
        }
        361 -> {
            516
        }
        362 -> {
            515
        }
        363 -> {
            422
        }
        364 -> {
            419
        }
        365 -> {
            C_DONE
        }
        366 -> {
            writeF32(f.param_1.plus(0x274), f32(f.fVar61))
            365
        }
        367 -> {
            writeF32(f.param_3.plus(0x24), f32(f.param_9))
            366
        }
        368 -> {
            writeI32(f.param_1.plus(0x2c), f.uVar27)
            367
        }
        369 -> {
            f.uVar27 = Calibration_baseline(f.param_3, f.param_9, f.fVar37, f.fVar71, readF32(f.param_1.plus(0x19c)), readF32(f.param_1.plus(0x168)), readF32(f.param_1.plus(0x174)), f.fVar26, f.param_14)
            368
        }
        370 -> {
            writeF32(f.pjVar2, f32(((f.fVar42) / (3.0))))
            369
        }
        371 -> {
            if ((b(((b((readI32(f.param_1.plus(0x198))) == (1))) != 0) && ((run { run { f.fVar42 = f32(((((((f.fVar42) + (0.0))) + (f.fVar34))) + (readF32(f.param_1.plus(0x22c))))); f32(((((((f.fVar42) + (0.0))) + (f.fVar34))) + (readF32(f.param_1.plus(0x22c))))) }; b((2.5) < (f.fVar42)) }) != 0))) != 0) 370 else 369
        }
        372 -> {
            writeF32(f.param_1.plus(8), f32(readF32(f.param_1.plus(0x22c))))
            371
        }
        373 -> {
            writeF32(f.param_1.plus(4), f32(f.fVar34))
            372
        }
        374 -> {
            writeF32(f.param_1, f32(f.fVar42))
            373
        }
        375 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(8)))
            374
        }
        376 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(4)))
            375
        }
        377 -> {
            writeI32(f.param_1.plus(0x294), f.iVar24)
            376
        }
        378 -> {
            f.iVar24 = ((readI32(f.param_1.plus(0x294))) + (1))
            377
        }
        379 -> {
            if ((b(((b((f.param_5) < (7.5))) != 0) && ((run { run { f.fVar70 = f32(((((((((((((f.fVar42) + (0.0))) + (f.fVar25))) + (f.fVar34))) + (f.fVar31))) + (f.param_7))) / (5.0))); f32(((((((((((((f.fVar42) + (0.0))) + (f.fVar25))) + (f.fVar34))) + (f.fVar31))) + (f.param_7))) / (5.0))) }; b((((((((((((((((((f32((f32((f.fVar42) - (f.fVar70))) * (f32((f.fVar42) - (f.fVar70))))) + (0.0))) + (f32((f32((f.fVar25) - (f.fVar70))) * (f32((f.fVar25) - (f.fVar70))))))) + (f32((f32((f.fVar34) - (f.fVar70))) * (f32((f.fVar34) - (f.fVar70))))))) + (f32((f32((f.fVar31) - (f.fVar70))) * (f32((f.fVar31) - (f.fVar70))))))) + (f32((f32((f.param_7) - (f.fVar70))) * (f32((f.param_7) - (f.fVar70))))))) / (5.0))) * (100.0))) + (5.0))) <= (5.1)) }) != 0))) != 0) 378 else 377
        }
        380 -> {
            writeF32(f.param_1.plus(0x40), f32(f.param_7))
            379
        }
        381 -> {
            writeF32(f.param_1.plus(0x3c), f32(f.fVar31))
            380
        }
        382 -> {
            writeF32(f.param_1.plus(0x38), f32(f.fVar34))
            381
        }
        383 -> {
            writeF32(f.param_1.plus(0x34), f32(f.fVar25))
            382
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep12(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        384 -> {
            writeF32(f.param_1.plus(0x30), f32(f.fVar42))
            383
        }
        385 -> {
            f.iVar24 = 0
            384
        }
        386 -> {
            f.fVar31 = f32(readF32(f.param_1.plus(0x40)))
            385
        }
        387 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x3c)))
            386
        }
        388 -> {
            f.fVar25 = f32(readF32(f.param_1.plus(0x38)))
            387
        }
        389 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x34)))
            388
        }
        390 -> {
            f.fVar61 = f32(((f.fVar61) + (0.5)))
            389
        }
        391 -> {
            if ((b(((f.bVar1) != 0) && ((b((f32((f.fVar61) + (f.param_4))) < (2.8))) != 0))) != 0) 390 else 389
        }
        392 -> {
            f.fVar61 = f32(f.fVar42)
            391
        }
        393 -> {
            if ((b(((f.bVar13) != 0) || ((b((f.bVar16) != (f.bVar17))) != 0))) != 0) 392 else 391
        }
        394 -> {
            f.fVar61 = f32(((f.fVar42) * (0.75)))
            393
        }
        395 -> {
            f.bVar17 = b((0) != 0)
            394
        }
        396 -> {
            f.bVar13 = b((b((f.dVar41) == (DAT_0012ef70))) != 0)
            395
        }
        397 -> {
            f.bVar16 = b((b((f.dVar41) < (DAT_0012ef70))) != 0)
            396
        }
        398 -> {
            if ((b(((b(!((b((f.dVar41).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012ef70).isNaN())) != 0))) != 0))) != 0) 397 else 394
        }
        399 -> {
            f.bVar17 = b((1) != 0)
            398
        }
        400 -> {
            f.bVar13 = b((0) != 0)
            399
        }
        401 -> {
            f.bVar16 = b((0) != 0)
            400
        }
        402 -> {
            if ((b(((b(!((f.bVar15) != 0))) != 0) && ((b((f.bVar12) == (f.bVar14))) != 0))) != 0) 401 else 394
        }
        403 -> {
            f.bVar17 = b((0) != 0)
            402
        }
        404 -> {
            f.bVar13 = b((1) != 0)
            403
        }
        405 -> {
            f.bVar16 = b((0) != 0)
            404
        }
        406 -> {
            f.bVar14 = b((0) != 0)
            405
        }
        407 -> {
            f.bVar15 = b((b((f.param_4) == (3.0))) != 0)
            406
        }
        408 -> {
            f.bVar12 = b((b((f.param_4) < (3.0))) != 0)
            407
        }
        409 -> {
            if ((b(!((b((f.param_4).isNaN())) != 0))) != 0) 408 else 405
        }
        410 -> {
            f.bVar14 = b((1) != 0)
            409
        }
        411 -> {
            f.bVar15 = b((0) != 0)
            410
        }
        412 -> {
            f.bVar12 = b((0) != 0)
            411
        }
        413 -> {
            if ((b((6.0) < (readF32(f.param_1.plus(0x250))))) != 0) 412 else 405
        }
        414 -> {
            f.bVar14 = b((0) != 0)
            413
        }
        415 -> {
            f.bVar15 = b((1) != 0)
            414
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep13(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        416 -> {
            f.bVar12 = b((0) != 0)
            415
        }
        417 -> {
            f.dVar41 = f.fVar42
            416
        }
        418 -> {
            if ((b(((f.bVar14) != 0) && ((b((readF32(f.param_1.plus(0x19c))) < (1.0))) != 0))) != 0) 417 else 391
        }
        419 -> {
            f.fVar61 = f32(f.fVar42)
            418
        }
        420 -> {
            f.fVar42 = f32(f.fVar66)
            364
        }
        421 -> {
            if ((b(((b(((b((3.5) < (f.param_4))) != 0) && ((b((1.5) < (f.fVar66))) != 0))) != 0) && ((run { run { f.fVar42 = f32(((f.fVar66) * (0.5))); f32(((f.fVar66) * (0.5))) }; b(!((b(((f.bVar1).and(b((5.5) < (f32((f.fVar66) + (readF32(f.param_1.plus(0x1cc)))))))) != 0)) != 0)) }) != 0))) != 0) 420 else 364
        }
        422 -> {
            f.fVar42 = f32(f.fVar66)
            421
        }
        423 -> {
            f.fVar66 = f32(f32(f.dVar28))
            363
        }
        424 -> {
            if ((b((3.0) <= (f.dVar41))) != 0) 356 else 363
        }
        425 -> {
            f.dVar28 = ((f.dVar28) * (0.75))
            355
        }
        426 -> {
            f.dVar41 = ((((f.dVar28) * (0.75))) + (f.dVar89))
            425
        }
        427 -> {
            360
        }
        428 -> {
            if ((b((3.0) <= (((((f.dVar28) * (0.5))) + (f.dVar89))))) != 0) 427 else 426
        }
        429 -> {
            f.dVar38 = ((f.dVar28) * (0.5))
            428
        }
        430 -> {
            364
        }
        431 -> {
            if ((b((f.dVar28) < (0.3))) != 0) 430 else 353
        }
        432 -> {
            f.fVar42 = f32(0.0)
            431
        }
        433 -> {
            363
        }
        434 -> {
            if ((b(((b(((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) || ((b((39.0) <= (f.fVar48))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (6.8))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (6.8))) != 0))) != 0))) != 0) || ((b(((b(((b(!((f.bVar15) != 0))) != 0) || ((b((f.fVar66) <= (0.0))) != 0))) != 0) || ((b((readI32(f.local_1a0)) != (1))) != 0))) != 0))) != 0) 433 else 432
        }
        435 -> {
            f.dVar41 = ((f.dVar28) + (f.dVar89))
            355
        }
        436 -> {
            f.dVar28 = ((f.dVar41) * (0.75))
            435
        }
        437 -> {
            356
        }
        438 -> {
            if ((b((3.0) <= (((f.dVar28) + (f.dVar89))))) != 0) 437 else 436
        }
        439 -> {
            f.dVar28 = ((f.dVar41) * (0.5))
            438
        }
        440 -> {
            363
        }
        441 -> {
            357
        }
        442 -> {
            if ((b(((b(((b(((b(((b((0x11f) < (f.param_14))) != 0) && ((b((f.dVar89) < (DAT_0012f038))) != 0))) != 0) && ((b((1.5) < (f.fVar66))) != 0))) != 0) && ((b((3.9) < (f.fVar61))) != 0))) != 0) && ((b(((b((6.8) < (f.fVar34))) != 0) || ((b((6.8) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) 441 else 440
        }
        443 -> {
            if ((b(((b(((b((1.0) <= (f.fVar37))) != 0) || ((b(((b((f.param_14) < (0x120))) != 0) || ((b((36.0) <= (f.fVar48))) != 0))) != 0))) != 0) || ((b((f.dVar41) <= (DAT_0012ee90))) != 0))) != 0) 442 else 439
        }
        444 -> {
            359
        }
        445 -> {
            if ((b(((b((f.fVar37) < (3.5))) != 0) && ((b(((b(((b((0x11f) < (f.param_14))) != 0) && ((b((36.0) > (f.fVar48))) != 0))) != 0) && ((b((3.9) < (f.fVar61))) != 0))) != 0))) != 0) 444 else 443
        }
        446 -> {
            354
        }
        447 -> {
            if ((b(((b((6.8) < (readF32(f.param_1.plus(0x1f8))))) != 0) && ((b(!((b(((f.bVar14).xor(1)) != 0)) != 0))) != 0))) != 0) 446 else 445
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep14(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        448 -> {
            357
        }
        449 -> {
            if ((b(((b((0.7) < (f.dVar41))) != 0) && ((b((7.8) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) 448 else 445
        }
        450 -> {
            if ((f.bVar14) != 0) 354 else 445
        }
        451 -> {
            if ((b((f.fVar34) <= (6.8))) != 0) 447 else 450
        }
        452 -> {
            if ((b(((b((f.dVar89) < (2.8))) != 0) && ((b((2.9) < (f.fVar61))) != 0))) != 0) 451 else 445
        }
        453 -> {
            f.dVar41 = f.fVar66
            452
        }
        454 -> {
            359
        }
        455 -> {
            if ((b(((b(((b((f.dVar89) < (3.3))) != 0) && ((b((3.9) < (f.fVar61))) != 0))) != 0) && ((b(((b((6.6) < (f.fVar34))) != 0) || ((b((6.6) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) 454 else 453
        }
        456 -> {
            f.fVar61 = f32(f32((f.fVar37) + (f.fVar66)))
            455
        }
        457 -> {
            f.fVar66 = f32(((f.fVar66) * (0.5)))
            456
        }
        458 -> {
            if ((b(((b(((b(((b((7.0) < (f.fVar34))) != 0) || ((b((7.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) && ((b((3.5) < (f.fVar61))) != 0))) != 0) && ((b(((b((5.0) < (f.fVar42))) != 0) && ((b((readF32(f.param_1.plus(0x19c))) < (DAT_0012f030))) != 0))) != 0))) != 0) 457 else 456
        }
        459 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x1fc)))
            458
        }
        460 -> {
            if ((b(((b(((f.bVar1).xor(1)) != 0)) != 0) || ((b((f.dVar28) <= (DAT_0012ef38))) != 0))) != 0) 434 else 459
        }
        461 -> {
            f.fVar66 = f32(((f.fVar66) * (0.5)))
            363
        }
        462 -> {
            357
        }
        463 -> {
            363
        }
        464 -> {
            361
        }
        465 -> {
            if ((b(((b(((b(((b((f.fVar37) < (3.5))) != 0) && ((b(((b((0x11f) < (f.param_14))) != 0) && ((b((3.5) < (f.fVar61))) != 0))) != 0))) != 0) && ((b(((b((DAT_0012f160) < (readF32(f.param_1.plus(0x1fc))))) != 0) || ((b((DAT_0012f160) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) && ((b(((b((0.35) < (f.fVar76))) != 0) && ((b((6.0) < (readF32(f.param_1.plus(0x1cc))))) != 0))) != 0))) != 0) 464 else 463
        }
        466 -> {
            if ((b(((b(((b(((b(((b((f.param_14) < (0x120))) != 0) || ((b((DAT_0012f038) <= (f.dVar89))) != 0))) != 0) || ((b((f.fVar66) <= (1.5))) != 0))) != 0) || ((b((f.dVar38) <= (3.9))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (6.8))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (6.8))) != 0))) != 0))) != 0) 465 else 462
        }
        467 -> {
            353
        }
        468 -> {
            if ((b(((b(((b((f.fVar37) < (1.0))) != 0) && ((b(((b((0x11f) < (f.param_14))) != 0) && ((b((f.fVar48) < (36.0))) != 0))) != 0))) != 0) && ((b((DAT_0012ee90) < (f.dVar28))) != 0))) != 0) 467 else 466
        }
        469 -> {
            if ((b(((b(((b((3.5) <= (f.fVar37))) != 0) || ((b(((b((f.param_14) < (0x120))) != 0) || ((b((f.fVar48) >= (36.0))) != 0))) != 0))) != 0) || ((b((f.dVar38) <= (3.9))) != 0))) != 0) 468 else 359
        }
        470 -> {
            358
        }
        471 -> {
            if ((b(((b((0.7) < (f.dVar28))) != 0) && ((b(((f.bVar14).and(b((6.8) < (readF32(f.param_1.plus(0x1f8)))))) != 0)) != 0))) != 0) 470 else 469
        }
        472 -> {
            363
        }
        473 -> {
            356
        }
        474 -> {
            if ((b((DAT_0012f030) < (((f.dVar28) + (f.dVar41))))) != 0) 473 else 472
        }
        475 -> {
            f.dVar28 = ((f.dVar28) * (0.5))
            474
        }
        476 -> {
            360
        }
        477 -> {
            if ((b(((b(((b((9.0) < (readF32(f.param_1.plus(0x1fc))))) != 0) && ((b((readF32(f.pjVar2)) < (readF32(f.pjVar3)))) != 0))) != 0) && ((run { run { f.dVar38 = ((f.dVar28) * (0.25)); ((f.dVar28) * (0.25)) }; b((3.0) < (((f.dVar38) + (f.dVar89)))) }) != 0))) != 0) 476 else 475
        }
        478 -> {
            if ((b((DAT_0012f0b0) < (readF32(f.param_1.plus(0x250))))) != 0) 477 else 469
        }
        479 -> {
            if ((b(((f.bVar14) != 0) && ((b((0.7) < (f.dVar28))) != 0))) != 0) 358 else 469
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep15(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        480 -> {
            if ((b((readF32(f.param_1.plus(0x1fc))) <= (6.8))) != 0) 471 else 479
        }
        481 -> {
            if ((b(((b(((b((2.8) < (f.dVar38))) != 0) || ((b(((b((DAT_0012f038) < (f.dVar38))) != 0) && ((b((9.0) < (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0))) != 0) && ((b((f.dVar89) < (2.8))) != 0))) != 0) 480 else 469
        }
        482 -> {
            if ((b(((b(((b(((b((2.8) <= (f.dVar89))) != 0) || ((b((f.dVar38) <= (3.9))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (DAT_0012f160))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (DAT_0012f160))) != 0))) != 0))) != 0) || ((b((f.fVar76) <= (0.35))) != 0))) != 0) 481 else 359
        }
        483 -> {
            356
        }
        484 -> {
            if ((b((((f.dVar28) + (f.dVar41))) < (3.5))) != 0) 483 else 359
        }
        485 -> {
            f.dVar28 = ((f.dVar28) * (0.75))
            484
        }
        486 -> {
            if ((b(((b(((b((3.3) <= (f.dVar89))) != 0) || ((b((f.dVar38) <= (3.9))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (DAT_0012ef68))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (DAT_0012ef68))) != 0))) != 0))) != 0) 482 else 485
        }
        487 -> {
            f.dVar38 = f.fVar61
            486
        }
        488 -> {
            if ((b(((b(((b((f.param_4) <= (4.5))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (8.0))) != 0))) != 0) || ((b(((b((f.dVar28) <= (DAT_0012f058))) != 0) || ((b((1.0) <= (readF32(f.param_1.plus(0x19c))))) != 0))) != 0))) != 0) 487 else 359
        }
        489 -> {
            363
        }
        490 -> {
            if ((b((f.dVar28) <= (DAT_0012ef38))) != 0) 489 else 488
        }
        491 -> {
            362
        }
        492 -> {
            f.fVar61 = f32(0.75)
            491
        }
        493 -> {
            355
        }
        494 -> {
            f.dVar41 = ((f.dVar28) + (f.dVar41))
            493
        }
        495 -> {
            f.dVar28 = ((f.dVar28) * (0.25))
            494
        }
        496 -> {
            360
        }
        497 -> {
            if ((b(((b((f.fVar47) < (1.0))) != 0) && ((run { run { f.dVar38 = ((f.dVar28) * (0.5)); ((f.dVar28) * (0.5)) }; b((((f.dVar38) + (f.dVar41))) < (3.5)) }) != 0))) != 0) 496 else 495
        }
        498 -> {
            if ((b(((b((0.6) <= (f.dVar38))) != 0) || ((b((5.8) <= (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) 497 else 357
        }
        499 -> {
            if ((b(((b(((b((DAT_0012ee78) < (f.dVar28))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (7.8))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x19c))) < (DAT_0012f070))) != 0))) != 0) 498 else 490
        }
        500 -> {
            364
        }
        501 -> {
            if ((b((f.fVar66) < (0.0))) != 0) 500 else 499
        }
        502 -> {
            f.fVar42 = f32(0.0)
            501
        }
        503 -> {
            363
        }
        504 -> {
            359
        }
        505 -> {
            356
        }
        506 -> {
            if ((b((((f.dVar28) + (f.dVar89))) < (3.0))) != 0) 505 else 504
        }
        507 -> {
            if ((b(((b((DAT_0012f030) <= (f.fVar61))) != 0) || ((b((3.5) <= (((f.dVar28) + (f.dVar89))))) != 0))) != 0) 506 else 503
        }
        508 -> {
            f.dVar28 = ((f.dVar28) * (0.75))
            507
        }
        509 -> {
            if ((b(((b(((b(((b((f.dVar75) < (DAT_0012f030))) != 0) && ((b((f.fVar48) < (36.0))) != 0))) != 0) && ((b(((b((6.8) < (readF32(f.param_1.plus(0x1fc))))) != 0) || ((b((6.8) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) && ((b(((b((3.5) <= (f.fVar61))) != 0) || ((b((7.8) <= (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0))) != 0) 508 else 503
        }
        510 -> {
            if ((b(((b(((b((f.dVar75) < (DAT_0012f048))) != 0) && ((b((f.fVar48) < (60.0))) != 0))) != 0) && ((b(((b((4.5) < (f.fVar42))) != 0) && ((b((DAT_0012ef70) < (f32((f.fVar42) - (f.fVar71))))) != 0))) != 0))) != 0) 509 else 502
        }
        511 -> {
            if ((b(((b(((b((10.5) <= (f.param_12))) != 0) && ((b((4.3) <= (f.fVar42))) != 0))) != 0) || ((b(((f.bVar1).xor(1)) != 0)) != 0))) != 0) 460 else 510
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep16(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        512 -> {
            f.fVar61 = f32(f32((f.fVar37) + (f.fVar66)))
            511
        }
        513 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(600)))
            512
        }
        514 -> {
            f.fVar66 = f32(f32(f.dVar38))
            363
        }
        515 -> {
            f.fVar66 = f32(f32((f.fVar66) * (f.fVar61)))
            363
        }
        516 -> {
            f.fVar61 = f32(0.25)
            362
        }
        517 -> {
            356
        }
        518 -> {
            if ((b((((f.dVar28) + (f.dVar41))) < (3.9))) != 0) 517 else 361
        }
        519 -> {
            f.dVar28 = ((f.dVar28) * (0.5))
            518
        }
        520 -> {
            if ((b((((f.dVar38) + (f.dVar41))) < (3.9))) != 0) 360 else 519
        }
        521 -> {
            f.dVar38 = ((f.dVar28) * (0.75))
            520
        }
        522 -> {
            if ((b((3.9) <= (f32((f.fVar66) + (f.param_4))))) != 0) 521 else 363
        }
        523 -> {
            if ((b(((b(((b(((b(((b(((b((3.9) <= (f.dVar75))) != 0) || ((b((f.dVar38) <= (DAT_0012ef60))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (6.0))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (6.0))) != 0))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x250))) <= (7.0))) != 0) || ((b((f.dVar28) <= (DAT_0012ef70))) != 0))) != 0))) != 0) || ((b(((b((3.9) <= (f.dVar41))) != 0) && ((b((f32((f.fVar71) + (f.fVar66))) <= (3.5))) != 0))) != 0))) != 0) || ((b(((b(((f.bVar15).xor(1)) != 0)) != 0) || ((b((f32((f.fVar66) + (f.param_4))) <= (3.9))) != 0))) != 0))) != 0) 513 else 522
        }
        524 -> {
            f.dVar28 = f.fVar66
            523
        }
        525 -> {
            f.fVar66 = f32(f32(f.dVar30))
            352
        }
        526 -> {
            f.dVar30 = ((f.dVar78) - (f.dVar87))
            230
        }
        527 -> {
            352
        }
        528 -> {
            if ((b(((b(((b((f.fVar26) <= (f.param_6))) != 0) || ((b((0.0) <= (((f.dVar89) + (DAT_0012f0a8))))) != 0))) != 0) || ((run { run { f.dVar78 = DAT_0012f078; DAT_0012f078 }; b((DAT_0012f040) <= (f.fVar76)) }) != 0))) != 0) 527 else 229
        }
        529 -> {
            f.fVar66 = f32(0.0)
            528
        }
        530 -> {
            230
        }
        531 -> {
            f.dVar30 = ((((f.dVar30) - (f.dVar89))) * (0.5))
            530
        }
        532 -> {
            f.dVar30 = ((DAT_0012f258) - (f.dVar30))
            228
        }
        533 -> {
            230
        }
        534 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar30))
            533
        }
        535 -> {
            if ((b((0.0) <= (((f.dVar89) + (DAT_0012f0a8))))) != 0) 534 else 532
        }
        536 -> {
            if ((b(((b((f.param_6) < (f.fVar26))) != 0) && ((run { run { f.dVar30 = f.param_6; f.param_6 }; b((((f.dVar30) + (DAT_0012f0a8))) < (0.0)) }) != 0))) != 0) 535 else 529
        }
        537 -> {
            266
        }
        538 -> {
            f.fVar42 = f32(f32((f.fVar42) - (f.fVar37)))
            537
        }
        539 -> {
            f.fVar42 = f32(f32((f.fVar42) - (f.param_6)))
            227
        }
        540 -> {
            f.fVar42 = f32(9.0)
            226
        }
        541 -> {
            150
        }
        542 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(4.5)))))) != 0) 541 else 225
        }
        543 -> {
            352
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep17(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        544 -> {
            f.fVar66 = f32(f32((f.fVar61) - (f.fVar37)))
            543
        }
        545 -> {
            f.fVar61 = f32(4.5)
            224
        }
        546 -> {
            352
        }
        547 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(4.5)))))) != 0) 546 else 223
        }
        548 -> {
            f.fVar66 = f32(0.0)
            547
        }
        549 -> {
            if ((b((0.0) <= (((f.param_6) + (-(4.5)))))) != 0) 548 else 542
        }
        550 -> {
            352
        }
        551 -> {
            f.fVar66 = f32(((5.0) - (f.fVar37)))
            550
        }
        552 -> {
            if ((b((((f.fVar37) + (-(5.0)))) < (0.0))) != 0) 551 else 550
        }
        553 -> {
            f.fVar66 = f32(0.0)
            552
        }
        554 -> {
            f.fVar66 = f32(((5.0) - (f.param_6)))
            550
        }
        555 -> {
            226
        }
        556 -> {
            f.fVar42 = f32(10.0)
            555
        }
        557 -> {
            if ((b((((f.fVar37) + (-(5.0)))) < (0.0))) != 0) 556 else 554
        }
        558 -> {
            if ((b((0.0) <= (((f.param_6) + (-(5.0)))))) != 0) 553 else 557
        }
        559 -> {
            if ((b((readI32(f.local_1a0)) == (1))) != 0) 558 else 549
        }
        560 -> {
            if ((b(((b(((b(((b((6.0) <= (f.fVar61))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0xd))) != 0))) != 0) || ((b((DAT_0012ef70) <= (f.fVar76))) != 0))) != 0) || ((b((f.local_17c) <= (8.0))) != 0))) != 0) 559 else 536
        }
        561 -> {
            352
        }
        562 -> {
            f.fVar66 = f32(((f32((f.fVar61) - (f.param_6))) * (0.5)))
            561
        }
        563 -> {
            f.fVar61 = f32(((9.0) - (f.fVar26)))
            222
        }
        564 -> {
            219
        }
        565 -> {
            if ((b((f.fVar26) <= (f.param_6))) != 0) 564 else 221
        }
        566 -> {
            f.fVar61 = f32(((9.0) - (f.fVar37)))
            222
        }
        567 -> {
            221
        }
        568 -> {
            if ((b((f.fVar37) <= (1.0))) != 0) 567 else 566
        }
        569 -> {
            if ((b((DAT_0012f040) <= (f.fVar76))) != 0) 565 else 568
        }
        570 -> {
            196
        }
        571 -> {
            195
        }
        572 -> {
            f.dVar30 = 3.9
            571
        }
        573 -> {
            if ((b((1.0) < (f.dVar30))) != 0) 220 else 570
        }
        574 -> {
            f.dVar30 = ((4.5) - (f.dVar29))
            573
        }
        575 -> {
            if ((b((0.5) <= (((f.dVar49) + (-(4.5)))))) != 0) 219 else 569
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep18(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        576 -> {
            266
        }
        577 -> {
            f.fVar42 = f32(((((9.0) - (f.param_6))) - (f.fVar37)))
            576
        }
        578 -> {
            if ((b(((b(((b((f.fVar37) < (f.param_6))) != 0) && ((b((((f.dVar89) + (-(4.5)))) < (0.0))) != 0))) != 0) && ((b(((b((f.fVar76) < (DAT_0012ef60))) != 0) && ((b((f.fVar35) < (1.0))) != 0))) != 0))) != 0) 577 else 575
        }
        579 -> {
            352
        }
        580 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(4.5)))))) != 0) 579 else 578
        }
        581 -> {
            f.fVar66 = f32(0.0)
            580
        }
        582 -> {
            230
        }
        583 -> {
            f.dVar30 = ((((f.dVar49) - (f.dVar29))) * (0.5))
            582
        }
        584 -> {
            f.dVar49 = ((7.8) - (f.dVar49))
            217
        }
        585 -> {
            220
        }
        586 -> {
            if ((b((0.5) <= (((f.dVar49) + (-(3.9)))))) != 0) 585 else 216
        }
        587 -> {
            f.dVar49 = ((7.8) - (f.dVar89))
            217
        }
        588 -> {
            216
        }
        589 -> {
            if ((b((f.fVar37) <= (1.0))) != 0) 588 else 587
        }
        590 -> {
            if ((b(((b((1.0) <= (f.fVar76))) != 0) || ((b((0.5) <= (((f.dVar49) + (-(3.9)))))) != 0))) != 0) 586 else 589
        }
        591 -> {
            f.dVar30 = ((((((7.8) - (f.dVar29))) - (f.dVar89))) * (0.5))
            582
        }
        592 -> {
            if ((b(((b(((b(((b((f.param_6) <= (f.fVar37))) != 0) || ((b((0.0) <= (((f.dVar89) + (-(3.9)))))) != 0))) != 0) || ((b((0.85) <= (f.fVar76))) != 0))) != 0) || ((b((1.0) <= (f.fVar35))) != 0))) != 0) 590 else 218
        }
        593 -> {
            229
        }
        594 -> {
            f.dVar78 = 3.9
            593
        }
        595 -> {
            352
        }
        596 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(3.9)))))) != 0) 595 else 215
        }
        597 -> {
            f.fVar66 = f32(0.0)
            596
        }
        598 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(3.9)))))) != 0) 597 else 592
        }
        599 -> {
            if ((b(((b((6.5) <= (f.fVar63))) != 0) || ((b((6.5) <= (f.fVar93))) != 0))) != 0) 598 else 581
        }
        600 -> {
            352
        }
        601 -> {
            229
        }
        602 -> {
            if ((b((f.dVar28) < (0.0))) != 0) 601 else 600
        }
        603 -> {
            f.fVar66 = f32(0.0)
            602
        }
        604 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f0a0))
            201
        }
        605 -> {
            195
        }
        606 -> {
            286
        }
        607 -> {
            f.dVar30 = ((((f.dVar51) - (f.dVar29))) - (f.dVar89))
            606
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep19(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        608 -> {
            if ((b((f.dVar28) < (0.0))) != 0) 607 else 605
        }
        609 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a0))) < (0.0))) != 0) 197 else 198
        }
        610 -> {
            f.dVar51 = DAT_0012f260
            609
        }
        611 -> {
            f.dVar30 = DAT_0012f030
            610
        }
        612 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f0a0))
            611
        }
        613 -> {
            201
        }
        614 -> {
            197
        }
        615 -> {
            if ((b((((f.dVar29) + (DAT_0012f270))) < (0.0))) != 0) 614 else 613
        }
        616 -> {
            f.dVar78 = DAT_0012f038
            615
        }
        617 -> {
            f.dVar51 = DAT_0012f278
            616
        }
        618 -> {
            f.dVar30 = DAT_0012f038
            617
        }
        619 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f270))
            618
        }
        620 -> {
            if ((b((1.5) <= (f.fVar37))) != 0) 619 else 612
        }
        621 -> {
            230
        }
        622 -> {
            f.dVar30 = ((((f.dVar51) - (f.dVar89))) * (0.5))
            621
        }
        623 -> {
            f.dVar51 = ((f.dVar51) - (f.dVar29))
            200
        }
        624 -> {
            195
        }
        625 -> {
            if ((b((0.0) <= (f.dVar28))) != 0) 624 else 199
        }
        626 -> {
            f.dVar30 = DAT_0012f070
            625
        }
        627 -> {
            f.dVar51 = DAT_0012f098
            626
        }
        628 -> {
            if ((b((((f.dVar29) + (DAT_0012f268))) < (0.0))) != 0) 627 else 201
        }
        629 -> {
            f.dVar78 = DAT_0012f070
            628
        }
        630 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f268))
            629
        }
        631 -> {
            if ((b(((b((f.fVar63) <= (7.5))) != 0) || ((b((f.fVar93) <= (7.5))) != 0))) != 0) 620 else 630
        }
        632 -> {
            f.fVar66 = f32(((3.0) - (f.param_6)))
            600
        }
        633 -> {
            352
        }
        634 -> {
            f.fVar66 = f32(((((((6.0) - (f.param_6))) - (f.fVar37))) * (0.5)))
            633
        }
        635 -> {
            if ((b(((b((((f.fVar37) + (-(3.0)))) < (0.0))) != 0) && ((b((f.fVar76) < (DAT_0012ef60))) != 0))) != 0) 634 else 207
        }
        636 -> {
            211
        }
        637 -> {
            210
        }
        638 -> {
            352
        }
        639 -> {
            if ((b((3.0) <= (((f.param_4) + (0.0))))) != 0) 638 else 637
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep20(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        640 -> {
            f.fVar66 = f32(0.0)
            639
        }
        641 -> {
            if ((b((0.0) <= (((f.fVar37) + (-(3.0)))))) != 0) 640 else 636
        }
        642 -> {
            if ((b((0.0) <= (((f.param_6) + (-(3.0)))))) != 0) 641 else 635
        }
        643 -> {
            352
        }
        644 -> {
            f.fVar66 = f32(((3.5) - (f.param_4)))
            643
        }
        645 -> {
            229
        }
        646 -> {
            if ((b((2.0) <= (f.fVar37))) != 0) 645 else 206
        }
        647 -> {
            352
        }
        648 -> {
            if ((b((3.5) <= (f.param_4))) != 0) 647 else 646
        }
        649 -> {
            f.fVar66 = f32(0.0)
            648
        }
        650 -> {
            230
        }
        651 -> {
            f.dVar30 = ((DAT_0012f030) - (f.dVar89))
            650
        }
        652 -> {
            if ((b(((b((((f.dVar89) + (DAT_0012f0a0))) < (0.0))) != 0) && ((b((f.fVar76) < (1.0))) != 0))) != 0) 651 else 649
        }
        653 -> {
            230
        }
        654 -> {
            204
        }
        655 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (DAT_0012f0a0))))) != 0) || ((b((0.5) <= (f.fVar76))) != 0))) != 0) 654 else 653
        }
        656 -> {
            f.dVar30 = ((((((DAT_0012f260) - (f.dVar29))) - (f.dVar89))) * (0.5))
            655
        }
        657 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a0))) < (0.0))) != 0) 656 else 652
        }
        658 -> {
            203
        }
        659 -> {
            if ((b((f.fVar80) < (5.5))) != 0) 658 else 205
        }
        660 -> {
            230
        }
        661 -> {
            f.dVar30 = ((f.dVar30) * (0.5))
            660
        }
        662 -> {
            f.dVar30 = ((((((DAT_0012ef68) - (f.dVar29))) - (f.dVar89))) * (0.5))
            204
        }
        663 -> {
            230
        }
        664 -> {
            f.dVar30 = ((((((DAT_0012ef68) - (f.dVar29))) - (f.dVar89))) * (0.5))
            663
        }
        665 -> {
            if ((b(((b((((f.dVar89) + (DAT_0012f198))) < (0.0))) != 0) && ((b((f.fVar76) < (DAT_0012f040))) != 0))) != 0) 664 else 662
        }
        666 -> {
            181
        }
        667 -> {
            230
        }
        668 -> {
            f.dVar30 = ((DAT_0012f050) - (f.dVar41))
            667
        }
        669 -> {
            229
        }
        670 -> {
            if ((b((f.fVar37) < (2.0))) != 0) 669 else 668
        }
        671 -> {
            352
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep21(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        672 -> {
            if ((b(((b((DAT_0012f050) <= (f.dVar41))) != 0) || ((b((7.5) <= (f.fVar63))) != 0))) != 0) 671 else 670
        }
        673 -> {
            f.fVar66 = f32(0.0)
            672
        }
        674 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (DAT_0012f198))))) != 0) || ((run { run { f.dVar28 = DAT_0012f030; DAT_0012f030 }; b((1.0) <= (f.fVar76)) }) != 0))) != 0) 673 else 666
        }
        675 -> {
            if ((b((0.0) <= (((f.dVar29) + (DAT_0012f198))))) != 0) 674 else 665
        }
        676 -> {
            205
        }
        677 -> {
            if ((b(((b(((b((5.5) <= (f.fVar80))) != 0) || ((b((7.0) <= (f.fVar63))) != 0))) != 0) || ((b((7.0) <= (f.fVar93))) != 0))) != 0) 676 else 203
        }
        678 -> {
            if ((b((6.0) <= (f.fVar93))) != 0) 677 else 659
        }
        679 -> {
            f.dVar87 = f.dVar41
            678
        }
        680 -> {
            if ((b(((b((f.fVar63) <= (8.0))) != 0) || ((b((f.fVar76) <= (DAT_0012ef70))) != 0))) != 0) 679 else 642
        }
        681 -> {
            352
        }
        682 -> {
            230
        }
        683 -> {
            f.dVar30 = ((DAT_0012f070) - (f.dVar49))
            682
        }
        684 -> {
            229
        }
        685 -> {
            if ((b(((b((1.0) <= (f.fVar37))) != 0) || ((b((0.0) <= (((f.dVar49) + (DAT_0012f268))))) != 0))) != 0) 684 else 683
        }
        686 -> {
            f.dVar78 = DAT_0012f070
            685
        }
        687 -> {
            if ((b((((f.dVar89) + (DAT_0012f268))) < (0.0))) != 0) 686 else 681
        }
        688 -> {
            f.fVar66 = f32(0.0)
            687
        }
        689 -> {
            if ((b(((b(((b(((b((0) < (f.iVar23))) != 0) && ((b((8.0) < (f.fVar63))) != 0))) != 0) && ((b((0) < (f.iVar22))) != 0))) != 0) && ((b(((b(((b((8.0) < (f.fVar93))) != 0) && ((b((f.fVar93) < (f.fVar63))) != 0))) != 0) && ((b((12.5) < (f.local_17c))) != 0))) != 0))) != 0) 688 else 680
        }
        690 -> {
            352
        }
        691 -> {
            f.fVar66 = f32(((3.8) - (readF32(f.param_1.plus(0x19c)))))
            690
        }
        692 -> {
            if ((b(((b((f.dVar41) < (3.8))) != 0) && ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((((f.param_4) + (0.0))) < (3.8)) }) != 0))) != 0) 691 else 690
        }
        693 -> {
            f.fVar66 = f32(0.0)
            692
        }
        694 -> {
            230
        }
        695 -> {
            f.dVar30 = ((((((7.6) - (f.dVar28))) - (f.dVar49))) * (0.5))
            694
        }
        696 -> {
            200
        }
        697 -> {
            f.dVar51 = 3.8
            696
        }
        698 -> {
            230
        }
        699 -> {
            f.dVar30 = ((((((7.6) - (f.dVar29))) - (f.dVar29))) * (0.5))
            698
        }
        700 -> {
            if ((b((((f.dVar29) + (-(3.8)))) < (0.5))) != 0) 699 else 697
        }
        701 -> {
            if ((b((0.0) <= (((f.dVar49) + (-(3.8)))))) != 0) 700 else 202
        }
        702 -> {
            f.dVar28 = f.dVar89
            701
        }
        703 -> {
            if ((b((((f.dVar89) + (-(3.8)))) < (0.0))) != 0) 702 else 693
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep22(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        704 -> {
            195
        }
        705 -> {
            f.dVar30 = 3.8
            704
        }
        706 -> {
            202
        }
        707 -> {
            if ((b((((f.dVar49) + (-(3.8)))) < (0.5))) != 0) 706 else 705
        }
        708 -> {
            f.dVar28 = f.dVar29
            707
        }
        709 -> {
            199
        }
        710 -> {
            f.dVar51 = 7.6
            709
        }
        711 -> {
            if ((b((f.fVar76) < (1.0))) != 0) 710 else 708
        }
        712 -> {
            if ((b(((b((((f.dVar89) + (-(3.8)))) < (0.0))) != 0) && ((b(((b((0.5) < (f.fVar37))) != 0) || ((b((f.fVar76) < (0.85))) != 0))) != 0))) != 0) 711 else 705
        }
        713 -> {
            if ((b((((f.dVar29) + (-(3.8)))) < (0.0))) != 0) 712 else 703
        }
        714 -> {
            if ((b(((b(((b(((b((f.fVar63) < (8.0))) != 0) && ((b((readI32(f.local_1a0)) == (1))) != 0))) != 0) && ((b((f.fVar63) < (7.0))) != 0))) != 0) && ((b((f32((f.fVar79) - (readF32(f.param_1.plus(0x28c))))) < (4.0))) != 0))) != 0) 713 else 689
        }
        715 -> {
            if ((b((0.35) < (readF32(f.param_1.plus(0x1c4))))) != 0) 631 else 714
        }
        716 -> {
            f.fVar66 = f32(((3.0) - (f.param_6)))
            600
        }
        717 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.0))) != 0) 213 else 600
        }
        718 -> {
            f.fVar66 = f32(0.0)
            717
        }
        719 -> {
            224
        }
        720 -> {
            f.fVar61 = f32(3.5)
            719
        }
        721 -> {
            if ((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) 720 else 600
        }
        722 -> {
            f.fVar66 = f32(0.0)
            721
        }
        723 -> {
            f.fVar66 = f32(((((((7.0) - (f.fVar61))) - (f.param_6))) * (0.5)))
            600
        }
        724 -> {
            f.fVar61 = f32(f.fVar37)
            723
        }
        725 -> {
            if ((b((1.0) < (f.fVar37))) != 0) 724 else 723
        }
        726 -> {
            f.fVar61 = f32(f.fVar26)
            725
        }
        727 -> {
            190
        }
        728 -> {
            if ((b(((b((f.dVar28) <= (DAT_0012ef60))) != 0) || ((b((0.5) <= (((f.dVar49) + (-(3.5)))))) != 0))) != 0) 727 else 726
        }
        729 -> {
            194
        }
        730 -> {
            f.fVar61 = f32(((7.0) - (f.param_6)))
            729
        }
        731 -> {
            if ((b(((b(((b(((b((f.fVar35) < (1.0))) != 0) && ((b((f.fVar37) < (f.param_6))) != 0))) != 0) && ((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0))) != 0) && ((b((f.dVar28) < (DAT_0012ef60))) != 0))) != 0) 730 else 728
        }
        732 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(3.5)))))) != 0) 214 else 731
        }
        733 -> {
            215
        }
        734 -> {
            if ((b((((f.dVar89) + (-(3.9)))) < (0.0))) != 0) 733 else 600
        }
        735 -> {
            f.fVar66 = f32(0.0)
            734
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep23(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        736 -> {
            218
        }
        737 -> {
            217
        }
        738 -> {
            f.dVar49 = ((7.8) - (f.dVar89))
            737
        }
        739 -> {
            216
        }
        740 -> {
            if ((b((f.fVar37) <= (1.0))) != 0) 739 else 738
        }
        741 -> {
            220
        }
        742 -> {
            if ((b(((b((f.dVar28) <= (DAT_0012ef60))) != 0) || ((b((0.5) <= (((f.dVar49) + (-(3.9)))))) != 0))) != 0) 741 else 740
        }
        743 -> {
            if ((b(((b(((b((1.0) <= (f.fVar35))) != 0) || ((b((f.param_6) <= (f.fVar37))) != 0))) != 0) || ((b(((b((0.0) <= (((f.dVar89) + (-(3.9)))))) != 0) || ((b((DAT_0012ef60) <= (f.dVar28))) != 0))) != 0))) != 0) 742 else 736
        }
        744 -> {
            if ((b((((f.dVar29) + (-(3.9)))) < (0.0))) != 0) 743 else 735
        }
        745 -> {
            if ((b(((b((6.0) <= (f.fVar93))) != 0) || ((b((9.0) <= (f.local_17c))) != 0))) != 0) 732 else 744
        }
        746 -> {
            if ((b((1.0) <= (f.fVar76))) != 0) 718 else 745
        }
        747 -> {
            208
        }
        748 -> {
            if ((b((f.fVar37) < (2.0))) != 0) 747 else 212
        }
        749 -> {
            352
        }
        750 -> {
            f.fVar66 = f32(((3.0) - (f.fVar37)))
            749
        }
        751 -> {
            352
        }
        752 -> {
            f.fVar66 = f32(((3.0) - (readF32(f.param_1.plus(0x19c)))))
            751
        }
        753 -> {
            if ((b((((f.param_4) + (0.0))) < (3.0))) != 0) 210 else 751
        }
        754 -> {
            f.fVar66 = f32(0.0)
            753
        }
        755 -> {
            if ((b((0.0) <= (((f.fVar37) + (-(3.0)))))) != 0) 754 else 211
        }
        756 -> {
            352
        }
        757 -> {
            f.fVar66 = f32(((3.0) - (f.fVar26)))
            756
        }
        758 -> {
            207
        }
        759 -> {
            if ((b((0.0) <= (((f.fVar26) + (-(3.0)))))) != 0) 758 else 209
        }
        760 -> {
            194
        }
        761 -> {
            f.fVar61 = f32(((6.0) - (f.param_6)))
            760
        }
        762 -> {
            if ((b(((b((((f.fVar37) + (-(3.0)))) < (0.0))) != 0) && ((b(((b((f.param_4) < (1.0))) != 0) || ((b((f.dVar28) < (DAT_0012f040))) != 0))) != 0))) != 0) 761 else 759
        }
        763 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.0))) != 0) 762 else 755
        }
        764 -> {
            212
        }
        765 -> {
            if ((b(((b((f.dVar39) <= (DAT_0012f160))) != 0) || ((b((1.0) <= (f.fVar85))) != 0))) != 0) 764 else 763
        }
        766 -> {
            if ((b(((b((f.fVar37) < (2.0))) != 0) && ((b((7.0) < (f.fVar93))) != 0))) != 0) 208 else 212
        }
        767 -> {
            if ((b((7.0) < (f.fVar63))) != 0) 748 else 766
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep24(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        768 -> {
            if ((b(((b((10.0) <= (f.fVar61))) != 0) || ((b(((b((readI32(f.param_1.plus(0x21c))) < (0xd))) != 0) || ((run { run { f.dVar28 = f.fVar76; f.fVar76 }; b((1.2) <= (f.dVar28)) }) != 0))) != 0))) != 0) 715 else 767
        }
        769 -> {
            if ((b(((b(((b((4.5) <= (f.fVar80))) != 0) || ((b((readI32(f.param_1.plus(0x1ac))) < (0x19))) != 0))) != 0) || ((b(((b(((b((10.0) <= (f.fVar25))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x37))) != 0))) != 0) && ((b(((b((DAT_0012ef68) <= (f.dVar39))) != 0) || ((b((1.2) <= (f.fVar76))) != 0))) != 0))) != 0))) != 0) 768 else 599
        }
        770 -> {
            352
        }
        771 -> {
            f.fVar66 = f32(((4.0) - (f.fVar37)))
            770
        }
        772 -> {
            if ((b((((f.dVar89) + (-(4.0)))) < (0.0))) != 0) 771 else 770
        }
        773 -> {
            f.fVar66 = f32(0.0)
            772
        }
        774 -> {
            f.fVar66 = f32(((4.0) - (f.param_6)))
            770
        }
        775 -> {
            f.fVar66 = f32(((f32((f.fVar61) - (f.fVar37))) * (0.5)))
            770
        }
        776 -> {
            f.fVar61 = f32(((8.0) - (f.param_6)))
            194
        }
        777 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(4.0)))))) != 0) 774 else 776
        }
        778 -> {
            if ((b(((b((f.param_6) <= (1.0))) != 0) || ((b((0.5) <= (((f.dVar29) + (-(4.0)))))) != 0))) != 0) 773 else 777
        }
        779 -> {
            f.fVar66 = f32(f32(f.dVar30))
            770
        }
        780 -> {
            f.dVar30 = ((f.dVar30) - (f.dVar29))
            196
        }
        781 -> {
            199
        }
        782 -> {
            if ((b((f.dVar28) < (0.0))) != 0) 781 else 195
        }
        783 -> {
            f.dVar30 = DAT_0012f078
            782
        }
        784 -> {
            f.dVar51 = DAT_0012f258
            783
        }
        785 -> {
            201
        }
        786 -> {
            if ((b(((b((f.param_6) <= (1.0))) != 0) || ((b((0.5) <= (((f.dVar29) + (DAT_0012f0a8))))) != 0))) != 0) 785 else 784
        }
        787 -> {
            f.dVar78 = DAT_0012f078
            786
        }
        788 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f0a8))
            787
        }
        789 -> {
            if ((b((DAT_0012ef60) <= (f.fVar76))) != 0) 778 else 788
        }
        790 -> {
            if ((b(((b(((b(((b(((b((f.fVar61) < (6.0))) != 0) && ((b((0x18) < (readI32(f.param_1.plus(0x21c))))) != 0))) != 0) && ((b((f.fVar35) < (0.7))) != 0))) != 0) && ((b(((b((f.dVar39) < (6.8))) != 0) && ((b((f.fVar81) < (0.5))) != 0))) != 0))) != 0) && ((b((f32((f.fVar79) - (readF32(f.param_1.plus(0x28c))))) < (3.0))) != 0))) != 0) 789 else 769
        }
        791 -> {
            230
        }
        792 -> {
            f.dVar30 = ((DAT_0012f048) - (f.dVar49))
            791
        }
        793 -> {
            352
        }
        794 -> {
            if ((b((0.5) <= (((f.dVar49) + (DAT_0012f298))))) != 0) 793 else 191
        }
        795 -> {
            f.fVar66 = f32(0.0)
            794
        }
        796 -> {
            192
        }
        797 -> {
            if ((b((f.fVar76) < (DAT_0012f040))) != 0) 796 else 795
        }
        798 -> {
            f.dVar28 = DAT_0012f2a0
            797
        }
        799 -> {
            169
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep25(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        800 -> {
            if ((b((0.0) <= (((f.dVar89) + (DAT_0012f298))))) != 0) 799 else 798
        }
        801 -> {
            146
        }
        802 -> {
            if ((b((0.5) <= (((f.dVar29) + (DAT_0012f298))))) != 0) 801 else 800
        }
        803 -> {
            352
        }
        804 -> {
            230
        }
        805 -> {
            f.dVar30 = ((((f.dVar29) - (f.dVar89))) * (0.5))
            804
        }
        806 -> {
            f.dVar29 = 4.1
            805
        }
        807 -> {
            f.dVar29 = ((8.2) - (f.dVar29))
            805
        }
        808 -> {
            if ((b((0.5) <= (((f.dVar29) + (-(4.1)))))) != 0) 806 else 807
        }
        809 -> {
            if ((b((((f.dVar89) + (-(4.1)))) < (0.0))) != 0) 808 else 803
        }
        810 -> {
            f.fVar66 = f32(0.0)
            809
        }
        811 -> {
            173
        }
        812 -> {
            f.dVar28 = 8.2
            811
        }
        813 -> {
            176
        }
        814 -> {
            f.dVar30 = 4.1
            813
        }
        815 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(4.1)))))) != 0) 814 else 812
        }
        816 -> {
            if ((b((((f.dVar29) + (-(4.1)))) < (0.0))) != 0) 815 else 810
        }
        817 -> {
            175
        }
        818 -> {
            if ((b((10.0) <= (f.fVar25))) != 0) 817 else 816
        }
        819 -> {
            f.fVar66 = f32(((2.5) - (f.fVar37)))
            803
        }
        820 -> {
            if ((b((((f.dVar89) + (-(2.5)))) < (0.0))) != 0) 819 else 803
        }
        821 -> {
            f.fVar66 = f32(0.0)
            820
        }
        822 -> {
            178
        }
        823 -> {
            if ((b((((f.dVar29) + (-(2.5)))) < (0.0))) != 0) 822 else 821
        }
        824 -> {
            174
        }
        825 -> {
            if ((b((f.fVar25) < (10.0))) != 0) 824 else 823
        }
        826 -> {
            if ((b((readI32(f.local_1a0)) == (1))) != 0) 818 else 825
        }
        827 -> {
            286
        }
        828 -> {
            f.dVar30 = ((3.5) - (f.dVar89))
            827
        }
        829 -> {
            if ((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) 828 else 803
        }
        830 -> {
            f.fVar66 = f32(0.0)
            829
        }
        831 -> {
            188
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep26(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        832 -> {
            if ((b((((f.dVar29) + (-(3.5)))) < (0.0))) != 0) 831 else 830
        }
        833 -> {
            286
        }
        834 -> {
            f.dVar30 = ((((f.dVar28) - (f.dVar29))) - (f.dVar89))
            833
        }
        835 -> {
            230
        }
        836 -> {
            f.dVar30 = ((DAT_0012f030) - (f.dVar29))
            835
        }
        837 -> {
            if ((b((0.0) <= (((f.dVar89) + (DAT_0012f0a0))))) != 0) 836 else 173
        }
        838 -> {
            f.dVar28 = DAT_0012f260
            837
        }
        839 -> {
            198
        }
        840 -> {
            if ((b((0.0) <= (((f.dVar29) + (DAT_0012f0a0))))) != 0) 839 else 838
        }
        841 -> {
            if ((b(((b((f.fVar37) <= (2.5))) != 0) || ((b((3.5) <= (f.fVar37))) != 0))) != 0) 840 else 832
        }
        842 -> {
            if ((b(((b((7.5) <= (f.fVar93))) != 0) || ((b((9.0) <= (f.local_17c))) != 0))) != 0) 826 else 841
        }
        843 -> {
            186
        }
        844 -> {
            if ((b((((f.fVar37) + (-(3.0)))) < (0.0))) != 0) 843 else 803
        }
        845 -> {
            f.fVar66 = f32(0.0)
            844
        }
        846 -> {
            187
        }
        847 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.0))) != 0) 846 else 845
        }
        848 -> {
            188
        }
        849 -> {
            214
        }
        850 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(3.5)))))) != 0) 849 else 848
        }
        851 -> {
            if ((b(((b((f.fVar37) < (3.5))) != 0) && ((b((f.dVar45) < (0.3))) != 0))) != 0) 174 else 175
        }
        852 -> {
            if ((b(((b((7.5) <= (f.fVar93))) != 0) || ((b((f.fVar81) <= (DAT_0012ee90))) != 0))) != 0) 842 else 851
        }
        853 -> {
            185
        }
        854 -> {
            184
        }
        855 -> {
            if ((b((1.0) <= (f.fVar76))) != 0) 854 else 853
        }
        856 -> {
            f.dVar49 = ((3.5) - (f.dVar89))
            855
        }
        857 -> {
            if ((b(((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) && ((b((DAT_0012f280) < (((f.dVar89) + (DAT_0012f0a0))))) != 0))) != 0) 856 else 803
        }
        858 -> {
            f.fVar66 = f32(0.0)
            857
        }
        859 -> {
            f.fVar66 = f32(((3.5) - (f.fVar37)))
            803
        }
        860 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (-(3.5)))))) != 0) || ((b((1.0) <= (f.fVar76))) != 0))) != 0) 858 else 177
        }
        861 -> {
            188
        }
        862 -> {
            if ((b((((f.dVar29) + (-(3.5)))) < (0.0))) != 0) 861 else 860
        }
        863 -> {
            f.fVar66 = f32(((f.fVar66) * (0.5)))
            803
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep27(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        864 -> {
            if ((b(((b((((f.fVar37) + (-(3.0)))) < (0.0))) != 0) && ((b(((b((DAT_0012f280) < (((f.dVar89) + (DAT_0012f0a0))))) != 0) && ((run { run { f.fVar66 = f32(((3.0) - (f.fVar37))); f32(((3.0) - (f.fVar37))) }; b((1.0) <= (f.fVar76)) }) != 0))) != 0))) != 0) 863 else 803
        }
        865 -> {
            f.fVar66 = f32(0.0)
            864
        }
        866 -> {
            211
        }
        867 -> {
            if ((b(((b((((f.fVar37) + (-(3.0)))) < (0.0))) != 0) && ((b((f.fVar76) < (1.0))) != 0))) != 0) 866 else 865
        }
        868 -> {
            187
        }
        869 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.0))) != 0) 868 else 867
        }
        870 -> {
            if ((b(((b(((b((f.fVar63) <= (8.5))) != 0) && ((b((f.fVar93) <= (8.5))) != 0))) != 0) || ((b((60.0) <= (f.fVar31))) != 0))) != 0) 862 else 869
        }
        871 -> {
            f.fVar66 = f32(((2.5) - (f.param_6)))
            803
        }
        872 -> {
            180
        }
        873 -> {
            f.fVar61 = f32(((5.0) - (f.param_6)))
            872
        }
        874 -> {
            if ((b((((f.dVar89) + (-(2.5)))) < (0.0))) != 0) 873 else 871
        }
        875 -> {
            f.fVar66 = f32(0.0)
            803
        }
        876 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 875 else 803
        }
        877 -> {
            f.fVar66 = f32(((2.5) - (f.fVar37)))
            876
        }
        878 -> {
            f.bVar12 = b((b((f.dVar28) < (0.0))) != 0)
            877
        }
        879 -> {
            if ((b(((b((DAT_0012f280) < (f.dVar28))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar28).isNaN())) != 0)) }) != 0))) != 0) 878 else 877
        }
        880 -> {
            f.bVar12 = b((0) != 0)
            879
        }
        881 -> {
            f.fVar66 = f32(((2.5) - (f.fVar37)))
            803
        }
        882 -> {
            if ((b(((b((0.0) <= (f.dVar28))) != 0) || ((b((1.0) <= (f.fVar76))) != 0))) != 0) 880 else 881
        }
        883 -> {
            if ((b((((f.dVar29) + (-(2.5)))) < (0.0))) != 0) 178 else 882
        }
        884 -> {
            f.dVar28 = ((f.dVar89) + (-(2.5)))
            883
        }
        885 -> {
            if ((b(((b((f.fVar63) <= (8.5))) != 0) || ((b((f.fVar93) <= (8.5))) != 0))) != 0) 870 else 884
        }
        886 -> {
            189
        }
        887 -> {
            177
        }
        888 -> {
            if ((b((0.5) <= (((f.dVar29) + (-(3.5)))))) != 0) 887 else 886
        }
        889 -> {
            352
        }
        890 -> {
            206
        }
        891 -> {
            if ((b((((f.param_4) + (0.0))) < (3.5))) != 0) 890 else 889
        }
        892 -> {
            f.fVar66 = f32(0.0)
            891
        }
        893 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (-(3.5)))))) != 0) || ((b((1.0) <= (f.fVar76))) != 0))) != 0) 892 else 888
        }
        894 -> {
            190
        }
        895 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (-(3.5)))))) != 0) || ((b((1.2) <= (f.fVar76))) != 0))) != 0) 894 else 886
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep28(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        896 -> {
            if ((b(((b((0.0) <= (((f.dVar29) + (-(3.5)))))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0) 893 else 895
        }
        897 -> {
            230
        }
        898 -> {
            f.dVar30 = ((f.dVar30) - (f.dVar29))
            897
        }
        899 -> {
            f.dVar30 = 3.9
            176
        }
        900 -> {
            286
        }
        901 -> {
            f.dVar30 = ((((7.8) - (f.dVar29))) - (f.dVar89))
            900
        }
        902 -> {
            if ((b(((b((((f.dVar89) + (-(3.9)))) < (0.0))) != 0) && ((b((f.fVar76) < (0.85))) != 0))) != 0) 901 else 899
        }
        903 -> {
            149
        }
        904 -> {
            f.bVar12 = b((b((f.fVar76) < (1.0))) != 0)
            903
        }
        905 -> {
            f.fVar61 = f32(3.5)
            904
        }
        906 -> {
            f.dVar28 = ((f.dVar89) + (-(3.5)))
            905
        }
        907 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(3.9)))))) != 0) 906 else 902
        }
        908 -> {
            352
        }
        909 -> {
            f.fVar66 = f32(0.0)
            908
        }
        910 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 909 else 908
        }
        911 -> {
            f.fVar66 = f32(((3.0) - (f.fVar37)))
            910
        }
        912 -> {
            f.bVar12 = b((b((f.fVar61) < (0.0))) != 0)
            911
        }
        913 -> {
            if ((b(((b((f.fVar76) < (1.0))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar61).isNaN())) != 0)) }) != 0))) != 0) 912 else 911
        }
        914 -> {
            f.bVar12 = b((0) != 0)
            913
        }
        915 -> {
            207
        }
        916 -> {
            266
        }
        917 -> {
            f.fVar42 = f32(((((6.0) - (f.param_6))) - (f.fVar37)))
            916
        }
        918 -> {
            if ((b(((b((f.fVar61) < (0.0))) != 0) && ((b((f.fVar76) < (0.85))) != 0))) != 0) 917 else 915
        }
        919 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.0))) != 0) 918 else 914
        }
        920 -> {
            f.fVar61 = f32(((f.fVar37) + (-(3.0))))
            919
        }
        921 -> {
            if ((b(((b((6.5) <= (f.fVar93))) != 0) && ((b((f.iVar24) < (0x25))) != 0))) != 0) 920 else 907
        }
        922 -> {
            if ((b((11.0) <= (readF32(f.param_1.plus(0x290))))) != 0) 921 else 896
        }
        923 -> {
            if ((b((f.fVar63) < (7.5))) != 0) 922 else 885
        }
        924 -> {
            190
        }
        925 -> {
            167
        }
        926 -> {
            if ((b(((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) && ((b((f.fVar76) < (DAT_0012f010))) != 0))) != 0) 925 else 924
        }
        927 -> {
            214
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep29(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        928 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(3.5)))))) != 0) 927 else 926
        }
        929 -> {
            172
        }
        930 -> {
            if ((b(((b(((b((4.0) <= (f.fVar79))) != 0) || ((b((f.iVar24) < (0x1f))) != 0))) != 0) || ((b(((b((7.8) < (f.dVar39))) != 0) && ((b(((b((f.iVar24) < (0x31))) != 0) || ((b((0.35) <= (f.fVar76))) != 0))) != 0))) != 0))) != 0) 929 else 928
        }
        931 -> {
            if ((b(((b(((b((f.fVar25) <= (11.0))) != 0) || ((b((f.fVar93) <= (f.fVar63))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1c4))) != (0.0))) != 0))) != 0) 930 else 923
        }
        932 -> {
            if ((b((f.iVar24) < (0xc))) != 0) 172 else 931
        }
        933 -> {
            f.iVar24 = readI32(f.param_1.plus(0x21c))
            932
        }
        934 -> {
            f.fVar66 = f32(((f32((f.fVar61) - (f.fVar37))) * (0.5)))
            803
        }
        935 -> {
            f.fVar61 = f32(f32((f.fVar61) - (f.param_6)))
            180
        }
        936 -> {
            f.fVar61 = f32(9.0)
            179
        }
        937 -> {
            352
        }
        938 -> {
            f.fVar66 = f32(((4.5) - (f.fVar26)))
            937
        }
        939 -> {
            150
        }
        940 -> {
            if ((b((0.5) <= (((f.dVar49) + (-(4.5)))))) != 0) 939 else 938
        }
        941 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (-(4.5)))))) != 0) || ((b((0.5) <= (f.fVar76))) != 0))) != 0) 940 else 936
        }
        942 -> {
            214
        }
        943 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(4.5)))))) != 0) 942 else 941
        }
        944 -> {
            171
        }
        945 -> {
            if ((b(((b((f.fVar66) <= (2.0))) != 0) && ((b(((b((DAT_0012ef68) <= (f.dVar39))) != 0) || ((b((DAT_0012ef68) <= (f.dVar36))) != 0))) != 0))) != 0) 944 else 943
        }
        946 -> {
            if ((b(((b(((b((4.5) <= (f.fVar79))) != 0) || ((b((readI32(f.param_1.plus(0x1ac))) < (0x25))) != 0))) != 0) || ((b((f.param_6) <= (3.5))) != 0))) != 0) 171 else 945
        }
        947 -> {
            f.fVar66 = f32(((3.5) - (f.param_6)))
            803
        }
        948 -> {
            179
        }
        949 -> {
            f.fVar61 = f32(7.0)
            948
        }
        950 -> {
            if ((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) 189 else 190
        }
        951 -> {
            214
        }
        952 -> {
            if ((b(((b((0.0) <= (((f.dVar29) + (-(3.5)))))) != 0) || ((b((DAT_0012ef60) <= (f.dVar28))) != 0))) != 0) 951 else 188
        }
        953 -> {
            180
        }
        954 -> {
            f.fVar61 = f32(((6.0) - (f.param_6)))
            953
        }
        955 -> {
            207
        }
        956 -> {
            if ((b((0.0) <= (((f.fVar37) + (-(3.0)))))) != 0) 955 else 954
        }
        957 -> {
            224
        }
        958 -> {
            f.fVar61 = f32(3.0)
            957
        }
        959 -> {
            352
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep30(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        960 -> {
            if ((b((0.0) <= (((f.fVar37) + (-(3.0)))))) != 0) 959 else 186
        }
        961 -> {
            f.fVar66 = f32(0.0)
            960
        }
        962 -> {
            if ((b((0.0) <= (((f.param_6) + (-(3.0)))))) != 0) 961 else 187
        }
        963 -> {
            if ((b(((b((f.dVar28) < (0.35))) != 0) && ((b((8.0) < (f.local_17c))) != 0))) != 0) 962 else 952
        }
        964 -> {
            174
        }
        965 -> {
            352
        }
        966 -> {
            f.fVar66 = f32(f32(f.dVar49))
            965
        }
        967 -> {
            f.dVar49 = ((f.dVar30) - (f.dVar29))
            185
        }
        968 -> {
            f.dVar49 = ((f.dVar49) * (0.5))
            185
        }
        969 -> {
            f.dVar49 = ((((f.dVar28) - (f.dVar29))) - (f.dVar89))
            184
        }
        970 -> {
            185
        }
        971 -> {
            f.dVar49 = ((DAT_0012f030) - (f.dVar49))
            970
        }
        972 -> {
            182
        }
        973 -> {
            if ((b((0.5) <= (((f.dVar49) + (DAT_0012f0a0))))) != 0) 972 else 971
        }
        974 -> {
            if ((b(((b((f.param_6) <= (f.fVar37))) != 0) || ((run { run { f.dVar28 = DAT_0012f260; DAT_0012f260 }; b((0.5) <= (kotlin.math.abs(f.fVar76))) }) != 0))) != 0) 973 else 183
        }
        975 -> {
            if ((b((0.0) <= (f.dVar28))) != 0) 182 else 974
        }
        976 -> {
            f.dVar30 = DAT_0012f030
            975
        }
        977 -> {
            201
        }
        978 -> {
            if ((b((0.0) <= (((f.dVar29) + (DAT_0012f0a0))))) != 0) 977 else 976
        }
        979 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f0a0))
            978
        }
        980 -> {
            201
        }
        981 -> {
            182
        }
        982 -> {
            185
        }
        983 -> {
            f.dVar49 = ((DAT_0012f060) - (f.dVar49))
            982
        }
        984 -> {
            if ((b((((f.dVar49) + (DAT_0012f288))) < (0.5))) != 0) 983 else 981
        }
        985 -> {
            183
        }
        986 -> {
            if ((b(((b((f.fVar37) < (f.param_6))) != 0) && ((run { run { f.dVar28 = DAT_0012f290; DAT_0012f290 }; b((kotlin.math.abs(f.fVar76)) < (0.5)) }) != 0))) != 0) 985 else 984
        }
        987 -> {
            if ((b((f.dVar28) < (0.0))) != 0) 986 else 981
        }
        988 -> {
            f.dVar30 = DAT_0012f060
            987
        }
        989 -> {
            if ((b((((f.dVar29) + (DAT_0012f288))) < (0.0))) != 0) 988 else 980
        }
        990 -> {
            f.dVar78 = DAT_0012f060
            989
        }
        991 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f288))
            990
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep31(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        992 -> {
            if ((b((f.dVar36) < (6.8))) != 0) 991 else 979
        }
        993 -> {
            if ((b(((b((f.local_17c) <= (10.0))) != 0) || ((b((f.fVar61) <= (8.0))) != 0))) != 0) 992 else 964
        }
        994 -> {
            352
        }
        995 -> {
            f.fVar66 = f32(f32(((DAT_0012f048) - (f.dVar49))))
            994
        }
        996 -> {
            if ((b((((f.dVar49) + (DAT_0012f298))) < (0.0))) != 0) 995 else 994
        }
        997 -> {
            f.fVar66 = f32(0.0)
            996
        }
        998 -> {
            f.fVar66 = f32(f32(((f.dVar28) - (f.dVar89))))
            994
        }
        999 -> {
            if ((b(((b((f.param_6) <= (f.fVar37))) != 0) || ((run { run { f.dVar28 = DAT_0012f048; DAT_0012f048 }; b((0.0) <= (((f.dVar89) + (DAT_0012f298)))) }) != 0))) != 0) 997 else 181
        }
        1000 -> {
            169
        }
        1001 -> {
            230
        }
        1002 -> {
            f.dVar30 = ((((DAT_0012f048) - (f.dVar29))) * (0.5))
            1001
        }
        1003 -> {
            f.dVar30 = ((((((DAT_0012f2a0) - (f.dVar29))) - (f.dVar89))) * (0.5))
            1001
        }
        1004 -> {
            if ((b((1.0) <= (f.fVar35))) != 0) 1002 else 1003
        }
        1005 -> {
            if ((b(((b((((f.dVar89) + (DAT_0012f298))) < (0.0))) != 0) && ((b((f.dVar28) < (DAT_0012ef60))) != 0))) != 0) 1004 else 1000
        }
        1006 -> {
            if ((b((((f.dVar29) + (DAT_0012f298))) < (0.0))) != 0) 1005 else 999
        }
        1007 -> {
            if ((b(((b(((b((f.dVar28) < (DAT_0012f040))) != 0) && ((b((f.fVar63) < (6.5))) != 0))) != 0) && ((b((f.fVar93) < (6.5))) != 0))) != 0) 1006 else 993
        }
        1008 -> {
            if ((b((f.fVar63) <= (f.fVar93))) != 0) 1007 else 963
        }
        1009 -> {
            if ((b(((b(((b(((b((6.0) <= (f32((f.fVar25) - (f.param_6))))) != 0) || ((b(((b((readI32(f.param_1.plus(0x21c))) < (0x21))) != 0) || ((b((7.5) <= (f.fVar63))) != 0))) != 0))) != 0) || ((run { run { f.dVar28 = f.fVar76; f.fVar76 }; b((0.6) <= (f.dVar28)) }) != 0))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0) 946 else 1008
        }
        1010 -> {
            if ((b(((b(((b(((b((2.0) <= (f.fVar79))) != 0) || ((b((7.5) <= (f.fVar63))) != 0))) != 0) || ((b((0.5) <= (f.fVar76))) != 0))) != 0) || ((b(((b((7.5) <= (f.fVar93))) != 0) || ((b((readI32(f.param_1.plus(0x1ac))) < (0x3d))) != 0))) != 0))) != 0) 1009 else 802
        }
        1011 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar29))
            791
        }
        1012 -> {
            230
        }
        1013 -> {
            f.dVar30 = ((((((f.dVar28) - (f.dVar29))) - (f.dVar89))) * (0.5))
            1012
        }
        1014 -> {
            352
        }
        1015 -> {
            158
        }
        1016 -> {
            if ((b((((f.dVar49) + (DAT_0012f0a8))) < (0.5))) != 0) 1015 else 1014
        }
        1017 -> {
            f.fVar66 = f32(0.0)
            1016
        }
        1018 -> {
            if ((b(((b((0.35) <= (f.fVar76))) != 0) || ((run { run { f.dVar28 = DAT_0012f258; DAT_0012f258 }; b((0.85) <= (f.fVar35)) }) != 0))) != 0) 1017 else 192
        }
        1019 -> {
            if ((b((f.dVar28) < (0.0))) != 0) 1018 else 193
        }
        1020 -> {
            201
        }
        1021 -> {
            if ((b((0.5) <= (((f.dVar29) + (DAT_0012f0a8))))) != 0) 1020 else 1019
        }
        1022 -> {
            f.dVar78 = DAT_0012f078
            1021
        }
        1023 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f0a8))
            1022
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep32(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1024 -> {
            170
        }
        1025 -> {
            if ((b(((b((f.fVar48) <= (72.0))) != 0) || ((b((kotlin.math.abs(f32((f.fVar63) - (f.fVar93)))) <= (2.0))) != 0))) != 0) 1024 else 1023
        }
        1026 -> {
            170
        }
        1027 -> {
            if ((b((f.fVar48) <= (72.0))) != 0) 1026 else 1023
        }
        1028 -> {
            if ((b(((b((7.5) <= (f.fVar63))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0) 1025 else 1027
        }
        1029 -> {
            if ((b(((b((DAT_0012f068) <= (f.fVar79))) != 0) || ((b((0.6) <= (f.fVar76))) != 0))) != 0) 170 else 1028
        }
        1030 -> {
            f.fVar79 = f32(f32((f.fVar79) - (readF32(f.param_1.plus(0x28c)))))
            1029
        }
        1031 -> {
            if ((b((2.0) < (f.fVar37))) != 0) 1030 else 790
        }
        1032 -> {
            f.dVar78 = DAT_0012f030
            1031
        }
        1033 -> {
            286
        }
        1034 -> {
            f.dVar30 = ((((DAT_0012f2a0) - (f.dVar29))) - (f.dVar89))
            1033
        }
        1035 -> {
            230
        }
        1036 -> {
            f.dVar30 = ((DAT_0012f048) - (f.dVar29))
            1035
        }
        1037 -> {
            191
        }
        1038 -> {
            if ((b((((f.dVar49) + (DAT_0012f0a8))) < (0.0))) != 0) 1037 else 169
        }
        1039 -> {
            if ((b(((b((0.0) <= (f.dVar28))) != 0) || ((b((DAT_0012ef38) <= (f.fVar76))) != 0))) != 0) 1038 else 1034
        }
        1040 -> {
            168
        }
        1041 -> {
            if ((b((0.0) <= (((f.dVar29) + (DAT_0012f298))))) != 0) 1040 else 1039
        }
        1042 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f298))
            1041
        }
        1043 -> {
            229
        }
        1044 -> {
            352
        }
        1045 -> {
            if ((b(((b((0.0) <= (f.dVar28))) != 0) || ((b(((b((f.param_6) != (25.0))) != 0) && ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((0.85) <= (f.fVar76)) }) != 0))) != 0))) != 0) 1044 else 1043
        }
        1046 -> {
            f.fVar66 = f32(0.0)
            1045
        }
        1047 -> {
            159
        }
        1048 -> {
            193
        }
        1049 -> {
            158
        }
        1050 -> {
            if ((b((((f.dVar49) + (DAT_0012f0a8))) < (0.0))) != 0) 1049 else 1048
        }
        1051 -> {
            if ((b(((b((f.fVar37) <= (3.5))) != 0) || ((b((f.dVar29) <= (DAT_0012f060))) != 0))) != 0) 1050 else 1047
        }
        1052 -> {
            228
        }
        1053 -> {
            f.dVar30 = ((DAT_0012f258) - (f.dVar29))
            1052
        }
        1054 -> {
            if ((b(((b((f.dVar28) < (0.0))) != 0) && ((b((f.fVar76) < (DAT_0012ef38))) != 0))) != 0) 1053 else 1051
        }
        1055 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a8))) < (0.0))) != 0) 1054 else 168
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep33(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1056 -> {
            f.dVar78 = DAT_0012f078
            1055
        }
        1057 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f0a8))
            1056
        }
        1058 -> {
            if ((b((f.fVar93) < (6.0))) != 0) 1057 else 1042
        }
        1059 -> {
            226
        }
        1060 -> {
            f.fVar42 = f32(7.0)
            1059
        }
        1061 -> {
            164
        }
        1062 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (-(3.5)))))) != 0) || ((b((DAT_0012ef38) <= (f.fVar76))) != 0))) != 0) 1061 else 167
        }
        1063 -> {
            162
        }
        1064 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(3.5)))))) != 0) 1063 else 1062
        }
        1065 -> {
            if ((b(((b(((b(((b((9.0) <= (f.fVar25))) != 0) || ((b((f.fVar37) <= (3.5))) != 0))) != 0) || ((b((f.dVar29) <= (DAT_0012f060))) != 0))) != 0) || ((b((6.5) <= (f.fVar93))) != 0))) != 0) 166 else 1058
        }
        1066 -> {
            166
        }
        1067 -> {
            if ((b((9.0) <= (f.fVar25))) != 0) 1066 else 1058
        }
        1068 -> {
            if ((b((f.iVar24) < (0x31))) != 0) 1065 else 1067
        }
        1069 -> {
            190
        }
        1070 -> {
            352
        }
        1071 -> {
            f.fVar66 = f32(((3.5) - (f.fVar26)))
            1070
        }
        1072 -> {
            if ((b((((f.dVar49) + (-(3.5)))) < (0.0))) != 0) 165 else 1069
        }
        1073 -> {
            352
        }
        1074 -> {
            f.fVar66 = f32(((f32((f.fVar61) - (f.fVar37))) * (0.5)))
            1073
        }
        1075 -> {
            f.fVar61 = f32(((7.0) - (f.param_6)))
            163
        }
        1076 -> {
            if ((b(((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) && ((b((f.fVar76) < (DAT_0012ef38))) != 0))) != 0) 1075 else 164
        }
        1077 -> {
            352
        }
        1078 -> {
            f.fVar66 = f32(0.0)
            1077
        }
        1079 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1078 else 1077
        }
        1080 -> {
            f.fVar66 = f32(((3.5) - (f.fVar37)))
            1079
        }
        1081 -> {
            f.bVar12 = b((b((f.param_6) == (25.0))) != 0)
            1080
        }
        1082 -> {
            if ((b(((b((0.85) <= (f.fVar76))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_6).isNaN())) != 0)) }) != 0))) != 0) 1081 else 1080
        }
        1083 -> {
            f.bVar12 = b((1) != 0)
            1082
        }
        1084 -> {
            if ((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) 1083 else 1077
        }
        1085 -> {
            f.fVar66 = f32(0.0)
            1084
        }
        1086 -> {
            if ((b((0.0) <= (((f.param_6) + (-(3.5)))))) != 0) 162 else 1076
        }
        1087 -> {
            if ((b((DAT_0012ef70) < (f.dVar38))) != 0) 1086 else 1068
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep34(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1088 -> {
            169
        }
        1089 -> {
            230
        }
        1090 -> {
            f.dVar30 = ((((f.dVar29) - (f.dVar51))) * (0.5))
            1089
        }
        1091 -> {
            f.dVar51 = f.dVar89
            161
        }
        1092 -> {
            f.dVar29 = ((DAT_0012f2a0) - (f.dVar29))
            1091
        }
        1093 -> {
            if ((b((((f.dVar89) + (DAT_0012f298))) < (0.0))) != 0) 1092 else 1088
        }
        1094 -> {
            201
        }
        1095 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f298))
            1094
        }
        1096 -> {
            if ((b((0.5) <= (((f.dVar29) + (DAT_0012f298))))) != 0) 160 else 1093
        }
        1097 -> {
            if ((b(((b((f.fVar26) <= (f.param_6))) != 0) || ((b((f.fVar34) <= (1.0))) != 0))) != 0) 1096 else 1087
        }
        1098 -> {
            230
        }
        1099 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar89))
            1098
        }
        1100 -> {
            352
        }
        1101 -> {
            266
        }
        1102 -> {
            f.fVar42 = f32(((((7.0) - (f.fVar42))) - (f.fVar71)))
            1101
        }
        1103 -> {
            if ((b(((b(((b((((f.dVar75) + (-(3.5)))) < (0.0))) != 0) && ((b((((f.dVar30) + (-(3.5)))) < (0.0))) != 0))) != 0) && ((b((f.fVar32) < (0.85))) != 0))) != 0) 1102 else 1100
        }
        1104 -> {
            f.fVar66 = f32(f32(((((((DAT_0012f258) - (f.dVar30))) - (f.dVar75))) * (0.5))))
            1100
        }
        1105 -> {
            if ((b(((b(((b((0.0) <= (((f.dVar75) + (DAT_0012f0a8))))) != 0) || ((b((0.0) <= (((f.dVar30) + (DAT_0012f0a8))))) != 0))) != 0) || ((b((0.6) <= (f.fVar32))) != 0))) != 0) 1103 else 1104
        }
        1106 -> {
            if ((b((0.0) < (f.fVar71))) != 0) 1105 else 1100
        }
        1107 -> {
            f.fVar66 = f32(0.0)
            1106
        }
        1108 -> {
            if ((b(((b((0.0) <= (((f.dVar89) + (DAT_0012f0a8))))) != 0) || ((b(((b((f.param_6) != (25.0))) != 0) && ((b((0.85) <= (f.fVar76))) != 0))) != 0))) != 0) 1107 else 159
        }
        1109 -> {
            230
        }
        1110 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar49))
            1109
        }
        1111 -> {
            230
        }
        1112 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar29))
            1111
        }
        1113 -> {
            if ((b(((b((f.fVar26) <= (f.fVar37))) != 0) || ((b((0.0) <= (((f.dVar49) + (DAT_0012f0a8))))) != 0))) != 0) 1112 else 158
        }
        1114 -> {
            228
        }
        1115 -> {
            f.dVar30 = ((f.dVar30) - (f.dVar29))
            1114
        }
        1116 -> {
            if ((b(((b((((f.dVar89) + (DAT_0012f0a8))) < (0.0))) != 0) && ((run { run { f.dVar30 = DAT_0012f258; DAT_0012f258 }; b((f.fVar76) < (DAT_0012f040)) }) != 0))) != 0) 157 else 1113
        }
        1117 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a8))) < (0.0))) != 0) 1116 else 1108
        }
        1118 -> {
            230
        }
        1119 -> {
            f.dVar30 = ((DAT_0012f048) - (f.dVar29))
            1118
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep35(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1120 -> {
            286
        }
        1121 -> {
            f.dVar30 = ((((DAT_0012f2a0) - (f.dVar29))) - (f.dVar89))
            1120
        }
        1122 -> {
            if ((b((((f.dVar89) + (DAT_0012f298))) < (0.0))) != 0) 1121 else 156
        }
        1123 -> {
            160
        }
        1124 -> {
            if ((b((0.0) <= (((f.dVar29) + (DAT_0012f298))))) != 0) 1123 else 1122
        }
        1125 -> {
            if ((b(((b((6.8) <= (f.dVar36))) != 0) || ((b((readF32(f.param_1.plus(0x28c))) <= (1.0))) != 0))) != 0) 1124 else 1117
        }
        1126 -> {
            352
        }
        1127 -> {
            f.fVar66 = f32(0.0)
            1126
        }
        1128 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1127 else 1126
        }
        1129 -> {
            f.bVar12 = b((b((f.param_12) < (11.5))) != 0)
            155
        }
        1130 -> {
            if ((b(((b((f.fVar37) < (4.0))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_12).isNaN())) != 0)) }) != 0))) != 0) 1129 else 155
        }
        1131 -> {
            f.bVar12 = b((0) != 0)
            1130
        }
        1132 -> {
            f.fVar66 = f32(f32((f.fVar66) - (f.fVar37)))
            1131
        }
        1133 -> {
            f.fVar66 = f32(4.0)
            1132
        }
        1134 -> {
            177
        }
        1135 -> {
            if ((b((((f.dVar89) + (-(3.5)))) < (0.0))) != 0) 1134 else 1133
        }
        1136 -> {
            152
        }
        1137 -> {
            f.fVar61 = f32(7.0)
            1136
        }
        1138 -> {
            190
        }
        1139 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(3.5)))))) != 0) 1138 else 1137
        }
        1140 -> {
            if ((b((((f.dVar29) + (-(3.5)))) < (0.0))) != 0) 1139 else 1135
        }
        1141 -> {
            f.fVar66 = f32(4.5)
            1132
        }
        1142 -> {
            230
        }
        1143 -> {
            f.dVar30 = ((DAT_0012f048) - (f.dVar89))
            1142
        }
        1144 -> {
            if ((b((((f.dVar89) + (DAT_0012f298))) < (0.0))) != 0) 1143 else 1141
        }
        1145 -> {
            156
        }
        1146 -> {
            157
        }
        1147 -> {
            if ((b((((f.dVar89) + (DAT_0012f298))) < (0.0))) != 0) 1146 else 1145
        }
        1148 -> {
            f.dVar30 = DAT_0012f2a0
            1147
        }
        1149 -> {
            if ((b((((f.dVar29) + (DAT_0012f298))) < (0.0))) != 0) 1148 else 1144
        }
        1150 -> {
            153
        }
        1151 -> {
            if ((b(((b(((b((9.0) <= (f.local_17c))) != 0) || ((b((f.param_6) == (f.fVar37))) != 0))) != 0) || ((b(((b((0.35) <= (f.fVar76))) != 0) || ((b(((b((7.5) <= (f.fVar63))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0))) != 0))) != 0) 1150 else 1149
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep36(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1152 -> {
            if ((b((f.iVar24) < (0x2b))) != 0) 153 else 154
        }
        1153 -> {
            163
        }
        1154 -> {
            f.fVar61 = f32(f32((f.fVar61) - (f.param_6)))
            1153
        }
        1155 -> {
            f.fVar61 = f32(9.0)
            152
        }
        1156 -> {
            150
        }
        1157 -> {
            if ((b((0.0) <= (((f.dVar89) + (-(4.5)))))) != 0) 1156 else 151
        }
        1158 -> {
            352
        }
        1159 -> {
            f.fVar66 = f32(f.fVar61)
            1158
        }
        1160 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1159 else 1158
        }
        1161 -> {
            f.fVar66 = f32(((4.5) - (f.fVar37)))
            1160
        }
        1162 -> {
            f.bVar12 = b((b((f.param_12) < (11.5))) != 0)
            1161
        }
        1163 -> {
            if ((b(((b((f.fVar37) < (4.0))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_12).isNaN())) != 0)) }) != 0))) != 0) 1162 else 1161
        }
        1164 -> {
            f.bVar12 = b((0) != 0)
            1163
        }
        1165 -> {
            f.fVar66 = f32(((4.5) - (f.fVar37)))
            1158
        }
        1166 -> {
            if ((b((0.0) <= (((f.dVar89) + (DAT_0012f298))))) != 0) 1164 else 1165
        }
        1167 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(4.5)))))) != 0) 1166 else 1157
        }
        1168 -> {
            f.fVar61 = f32(f32(((3.9) - (f.dVar89))))
            1167
        }
        1169 -> {
            if ((b(((b(((b((f.param_6) == (f.fVar37))) != 0) && ((b((1.0) < (((4.5) - (f.dVar89))))) != 0))) != 0) && ((run { run { f.fVar61 = f32(0.0); f32(0.0) }; b((((f.dVar89) + (-(4.5)))) < (0.0)) }) != 0))) != 0) 1168 else 1167
        }
        1170 -> {
            f.fVar61 = f32(0.0)
            1169
        }
        1171 -> {
            154
        }
        1172 -> {
            if ((b(((b((7.5) <= (f.fVar63))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0) 1171 else 1170
        }
        1173 -> {
            if ((b(((b((0x2e) < (f.iVar24))) != 0) && ((b((f.local_17c) < (9.5))) != 0))) != 0) 1172 else 1152
        }
        1174 -> {
            352
        }
        1175 -> {
            286
        }
        1176 -> {
            f.dVar30 = ((3.5) - (f.dVar51))
            1175
        }
        1177 -> {
            if ((b((((f.dVar51) + (-(3.5)))) < (0.0))) != 0) 1176 else 1174
        }
        1178 -> {
            f.fVar66 = f32(0.0)
            1177
        }
        1179 -> {
            f.fVar66 = f32(((((((((7.0) - (f.fVar68))) - (f.fVar40))) * (0.5))) * (0.5)))
            1174
        }
        1180 -> {
            230
        }
        1181 -> {
            f.dVar30 = ((((3.5) - (f.dVar52))) * (0.5))
            1180
        }
        1182 -> {
            if ((b((0.0) <= (((f.dVar51) + (-(3.5)))))) != 0) 1181 else 1179
        }
        1183 -> {
            if ((b((0.0) <= (((f.dVar52) + (-(3.5)))))) != 0) 1178 else 1182
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep37(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1184 -> {
            if ((b(((b((f.fVar68) < (f.param_6))) != 0) && ((b((0.0) < (f.fVar68))) != 0))) != 0) 1183 else 1173
        }
        1185 -> {
            145
        }
        1186 -> {
            if ((b(((b((5.3) <= (f.dVar28))) != 0) || ((b(((b((f.iVar24) < (0x25))) != 0) || ((b((6.0) <= (f.fVar61))) != 0))) != 0))) != 0) 1185 else 1184
        }
        1187 -> {
            if ((b(((b(((b((f.iVar24) < (0x1e))) != 0) || ((b((f.dVar89) <= (DAT_0012f030))) != 0))) != 0) || ((b(((b(((b((5.5) <= (f.fVar61))) != 0) || ((b(((b(((b((DAT_0012f130) <= (f32((f.fVar91) - (f.fVar80))))) != 0) || ((b((6.6) <= (f.dVar28))) != 0))) != 0) || ((b((4.5) <= (f32((readF32(f.param_1.plus(0x290))) - (f.fVar79))))) != 0))) != 0))) != 0) || ((b(((b((f.fVar63) <= (0.0))) != 0) || ((b((f.fVar93) < (f.fVar63))) != 0))) != 0))) != 0))) != 0) 1186 else 1125
        }
        1188 -> {
            352
        }
        1189 -> {
            f.fVar66 = f32(0.0)
            1188
        }
        1190 -> {
            if ((b(!((f.bVar16) != 0))) != 0) 1189 else 1188
        }
        1191 -> {
            f.fVar66 = f32(f32((f.fVar61) - (f.fVar37)))
            1190
        }
        1192 -> {
            f.bVar16 = b((b((f.dVar28) < (0.0))) != 0)
            1191
        }
        1193 -> {
            if ((b(((f.bVar12) != 0) && ((run { run { f.bVar16 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar28).isNaN())) != 0)) }) != 0))) != 0) 1192 else 1191
        }
        1194 -> {
            f.bVar16 = b((0) != 0)
            1193
        }
        1195 -> {
            f.bVar12 = b((b((f.fVar37) < (f.param_4))) != 0)
            149
        }
        1196 -> {
            if ((b(((b((f.fVar25) < (8.0))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.fVar37).isNaN())) != 0))) != 0) && ((b(!((b((f.param_4).isNaN())) != 0))) != 0)) }) != 0))) != 0) 1195 else 149
        }
        1197 -> {
            f.bVar12 = b((0) != 0)
            1196
        }
        1198 -> {
            f.fVar61 = f32(4.5)
            1197
        }
        1199 -> {
            f.dVar28 = ((f.dVar89) + (-(4.5)))
            1198
        }
        1200 -> {
            352
        }
        1201 -> {
            f.fVar66 = f32(f32(((3.9) - (f.dVar89))))
            1200
        }
        1202 -> {
            if ((b(((b((f.fVar37) < (f.param_4))) != 0) && ((b((((f.dVar89) + (-(3.9)))) < (0.0))) != 0))) != 0) 1201 else 1199
        }
        1203 -> {
            352
        }
        1204 -> {
            f.fVar66 = f32(((3.5) - (f.param_4)))
            1203
        }
        1205 -> {
            147
        }
        1206 -> {
            if ((b((f.fVar37) < (f.param_4))) != 0) 1205 else 1204
        }
        1207 -> {
            f.fVar61 = f32(3.5)
            1206
        }
        1208 -> {
            if ((b((((f.dVar41) + (-(3.5)))) < (0.0))) != 0) 1207 else 1202
        }
        1209 -> {
            161
        }
        1210 -> {
            f.dVar29 = 3.5
            1209
        }
        1211 -> {
            if ((b(((b(((b((0.0) < (f.fVar40))) != 0) && ((b(((b((f32((f.fVar37) - (f.fVar40))) < (1.0))) != 0) && ((b((((f.dVar51) + (-(3.5)))) < (0.0))) != 0))) != 0))) != 0) || ((b(((b((0.0) < (f.fVar71))) != 0) && ((b(((b((3.0) < (f.fVar71))) != 0) && ((run { run { f.dVar51 = f.dVar75; f.dVar75 }; b((((f.dVar75) + (-(3.5)))) < (0.0)) }) != 0))) != 0))) != 0))) != 0) 148 else 1208
        }
        1212 -> {
            177
        }
        1213 -> {
            148
        }
        1214 -> {
            if ((b(((b((0.0) < (f.fVar40))) != 0) && ((b(((b((f32((f.fVar37) - (f.fVar40))) < (1.0))) != 0) && ((b((((f.dVar51) + (-(3.5)))) < (0.0))) != 0))) != 0))) != 0) 1213 else 1212
        }
        1215 -> {
            if ((b(((b((f.dVar28) < (0.0))) != 0) && ((b((f.local_17c) < (8.0))) != 0))) != 0) 1214 else 1211
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep38(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1216 -> {
            177
        }
        1217 -> {
            286
        }
        1218 -> {
            f.dVar30 = ((3.5) - (f.dVar49))
            1217
        }
        1219 -> {
            if ((b((f.fVar26) < (f.fVar37))) != 0) 1218 else 1216
        }
        1220 -> {
            352
        }
        1221 -> {
            f.fVar66 = f32(0.0)
            1220
        }
        1222 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1221 else 1220
        }
        1223 -> {
            f.fVar66 = f32(((3.5) - (f.param_4)))
            1222
        }
        1224 -> {
            f.bVar12 = b((b((f.param_4) < (3.5))) != 0)
            1223
        }
        1225 -> {
            if ((b(((b((((f.param_4) + (0.0))) < (3.5))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.param_4).isNaN())) != 0)) }) != 0))) != 0) 1224 else 1223
        }
        1226 -> {
            f.bVar12 = b((0) != 0)
            1225
        }
        1227 -> {
            190
        }
        1228 -> {
            if ((b((f32((f.fVar37) - (f.param_6))) < (0.85))) != 0) 1227 else 1226
        }
        1229 -> {
            if ((b((0.0) <= (f.dVar28))) != 0) 1228 else 1219
        }
        1230 -> {
            165
        }
        1231 -> {
            if ((b(((b((f.fVar37) < (f.fVar26))) != 0) && ((b((((f.dVar49) + (-(3.5)))) < (0.0))) != 0))) != 0) 1230 else 1229
        }
        1232 -> {
            227
        }
        1233 -> {
            f.fVar42 = f32(((7.0) - (f.param_6)))
            1232
        }
        1234 -> {
            if ((b(((b((f.fVar37) < (f.param_6))) != 0) && ((b((f.dVar28) < (0.0))) != 0))) != 0) 1233 else 1231
        }
        1235 -> {
            if ((b((((f.dVar29) + (-(3.5)))) < (0.0))) != 0) 1234 else 1215
        }
        1236 -> {
            f.dVar28 = ((f.dVar89) + (-(3.5)))
            1235
        }
        1237 -> {
            155
        }
        1238 -> {
            f.bVar12 = b((b((f.dVar89) < (3.9))) != 0)
            1237
        }
        1239 -> {
            if ((b(((b((((f.dVar89) + (-(4.5)))) < (0.5))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar89).isNaN())) != 0)) }) != 0))) != 0) 1238 else 1237
        }
        1240 -> {
            f.bVar12 = b((0) != 0)
            1239
        }
        1241 -> {
            f.fVar66 = f32(((4.5) - (f.fVar37)))
            1240
        }
        1242 -> {
            150
        }
        1243 -> {
            151
        }
        1244 -> {
            if ((b((((f.dVar89) + (-(4.5)))) < (0.5))) != 0) 1243 else 1242
        }
        1245 -> {
            if ((b(((b((f.fVar76) < (0.5))) != 0) && ((b((((f.dVar29) + (-(4.5)))) < (0.5))) != 0))) != 0) 1244 else 1241
        }
        1246 -> {
            if ((b(((b(((b(((b((3.5) < (f.fVar37))) != 0) && ((b((f.fVar25) < (10.0))) != 0))) != 0) && ((b((3.5) < (f.param_6))) != 0))) != 0) && ((b((f.fVar93) < (7.0))) != 0))) != 0) 1245 else 1236
        }
        1247 -> {
            213
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep39(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1248 -> {
            352
        }
        1249 -> {
            if ((b((DAT_0012ef70) <= (kotlin.math.abs(f32((f.param_6) - (f.fVar37)))))) != 0) 1248 else 1247
        }
        1250 -> {
            f.fVar66 = f32(0.0)
            1249
        }
        1251 -> {
            352
        }
        1252 -> {
            f.fVar66 = f32(f32((f.fVar61) - (f.fVar37)))
            1251
        }
        1253 -> {
            f.fVar66 = f32(((3.0) - (f.fVar26)))
            1251
        }
        1254 -> {
            if ((b((f.fVar37) <= (f.fVar26))) != 0) 147 else 1253
        }
        1255 -> {
            f.fVar61 = f32(3.0)
            1254
        }
        1256 -> {
            if ((b((f.fVar61) < (0.0))) != 0) 1255 else 1250
        }
        1257 -> {
            209
        }
        1258 -> {
            if ((b(((b((f.fVar37) < (f.fVar26))) != 0) && ((b((((f.dVar49) + (-(3.5)))) < (0.0))) != 0))) != 0) 1257 else 1256
        }
        1259 -> {
            227
        }
        1260 -> {
            f.fVar42 = f32(((6.0) - (f.param_6)))
            1259
        }
        1261 -> {
            if ((b(((b((f.fVar61) < (0.0))) != 0) && ((b((f.fVar37) < (f.param_6))) != 0))) != 0) 1260 else 1258
        }
        1262 -> {
            352
        }
        1263 -> {
            f.fVar66 = f32(0.0)
            1262
        }
        1264 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1263 else 1262
        }
        1265 -> {
            f.fVar66 = f32(((3.0) - (f.fVar37)))
            1264
        }
        1266 -> {
            f.bVar12 = b((b((f.fVar61) < (0.0))) != 0)
            1265
        }
        1267 -> {
            if ((b(((b((-(1.0)) < (f.fVar61))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar61).isNaN())) != 0)) }) != 0))) != 0) 1266 else 1265
        }
        1268 -> {
            f.bVar12 = b((0) != 0)
            1267
        }
        1269 -> {
            if ((b((0.0) <= (((f.param_6) + (-(3.0)))))) != 0) 1268 else 1261
        }
        1270 -> {
            f.fVar61 = f32(((f.fVar37) + (-(3.0))))
            1269
        }
        1271 -> {
            if ((b(((b((7.8) < (f.dVar36))) != 0) && ((b((readI32(f.param_1.plus(0x1ac))) < (0x36))) != 0))) != 0) 1270 else 1246
        }
        1272 -> {
            230
        }
        1273 -> {
            f.dVar30 = ((DAT_0012f030) - (f.dVar89))
            1272
        }
        1274 -> {
            if ((b(((b((f.dVar89) < (DAT_0012f050))) != 0) && ((b((((f.dVar89) + (DAT_0012f0a0))) < (0.5))) != 0))) != 0) 1273 else 1188
        }
        1275 -> {
            f.fVar66 = f32(0.0)
            1274
        }
        1276 -> {
            f.fVar66 = f32(f32(((DAT_0012f030) - (f.dVar29))))
            1188
        }
        1277 -> {
            230
        }
        1278 -> {
            f.dVar30 = ((((((DAT_0012f260) - (f.dVar29))) - (f.dVar89))) * (0.5))
            1277
        }
        1279 -> {
            if ((b((((f.dVar89) + (DAT_0012f0a0))) < (0.5))) != 0) 1278 else 1276
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep40(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1280 -> {
            if ((b((0.5) <= (((f.dVar29) + (DAT_0012f0a0))))) != 0) 1275 else 1279
        }
        1281 -> {
            if ((b(((b(((b((f.fVar63) <= (8.0))) != 0) || ((b((f.fVar93) <= (8.0))) != 0))) != 0) || ((b(((b((f.fVar81) <= (-(0.5)))) != 0) || ((b(((b((0x41) < (readI32(f.param_1.plus(0x1ac))))) != 0) || ((b((DAT_0012f030) <= (readF32(f.param_1.plus(0x28c))))) != 0))) != 0))) != 0))) != 0) 1271 else 1280
        }
        1282 -> {
            f.fVar66 = f32(((4.5) - (f.param_6)))
            1188
        }
        1283 -> {
            225
        }
        1284 -> {
            if ((b(((b((((f.dVar89) + (-(4.5)))) < (0.0))) != 0) && ((b((f.fVar76) < (DAT_0012ef70))) != 0))) != 0) 1283 else 150
        }
        1285 -> {
            352
        }
        1286 -> {
            223
        }
        1287 -> {
            if ((b((((f.dVar89) + (-(4.5)))) < (0.0))) != 0) 1286 else 1285
        }
        1288 -> {
            f.fVar66 = f32(0.0)
            1287
        }
        1289 -> {
            if ((b((0.0) <= (((f.dVar29) + (-(4.5)))))) != 0) 1288 else 1284
        }
        1290 -> {
            if ((b(((b(((b(((b(((b((f.iVar24) < (0x25))) != 0) || ((b((f.dVar29) <= (((f.dVar89) + (DAT_0012f020))))) != 0))) != 0) || ((b((4.0) <= (f32((readF32(f.param_1.plus(0x290))) - (f.fVar79))))) != 0))) != 0) || ((b(((b((7.3) <= (f.dVar36))) != 0) || ((b((DAT_0012ef68) <= (f.dVar39))) != 0))) != 0))) != 0) || ((b((5.0) <= (f.fVar79))) != 0))) != 0) 1281 else 1289
        }
        1291 -> {
            201
        }
        1292 -> {
            f.dVar78 = DAT_0012f048
            1291
        }
        1293 -> {
            f.dVar28 = ((f.dVar89) + (DAT_0012f298))
            1292
        }
        1294 -> {
            156
        }
        1295 -> {
            230
        }
        1296 -> {
            f.dVar30 = ((3.9) - (f.dVar29))
            1295
        }
        1297 -> {
            if ((b(((b((f.dVar29) < (3.9))) != 0) && ((b((5.3) < (f.dVar28))) != 0))) != 0) 1296 else 1294
        }
        1298 -> {
            352
        }
        1299 -> {
            f.fVar66 = f32(f32(((((((DAT_0012f2a0) - (f.dVar29))) - (f.dVar89))) * (0.5))))
            1298
        }
        1300 -> {
            if ((b(((b((3.5) < (f.fVar37))) != 0) && ((b((5.3) < (f.dVar28))) != 0))) != 0) 1299 else 1297
        }
        1301 -> {
            if ((b((((f.dVar89) + (DAT_0012f298))) < (0.0))) != 0) 1300 else 1294
        }
        1302 -> {
            if ((b((((f.dVar29) + (DAT_0012f298))) < (0.0))) != 0) 1301 else 146
        }
        1303 -> {
            if ((b(((b(((b((f.fVar37) <= (f.param_6))) != 0) && ((b(((b(((b((f.fVar76) < (1.0))) != 0) && ((b((0x1d) < (f.iVar24))) != 0))) != 0) && ((b((f.local_17c) < (8.5))) != 0))) != 0))) != 0) && ((b(((b((f.fVar61) < (6.0))) != 0) && ((b((f.dVar28) < (5.8))) != 0))) != 0))) != 0) 1302 else 1290
        }
        1304 -> {
            if ((b((f.param_6) <= (f.fVar37))) != 0) 145 else 1187
        }
        1305 -> {
            if ((b(((b(((b((f.param_6) <= (f.fVar37))) != 0) || ((b((f.iVar24) < (0x19))) != 0))) != 0) || ((b(((b((5.5) <= (f.fVar61))) != 0) || ((b(((b(((run { run { f.fVar34 = f32(readF32(f.param_1.plus(0x28c))); f32(readF32(f.param_1.plus(0x28c))) }; b(((b((3.5) <= (f32((f.fVar79) - (f.fVar34))))) != 0) || ((b((f.fVar93) < (f.fVar63))) != 0)) }) != 0) || ((b((0.5) <= (f32((f.fVar37) - (f.fVar34))))) != 0))) != 0) || ((b(((b((6.6) <= (f.dVar28))) != 0) || ((b((4.0) <= (f32((readF32(f.param_1.plus(0x290))) - (f.fVar79))))) != 0))) != 0))) != 0))) != 0))) != 0) 1304 else 1097
        }
        1306 -> {
            f.iVar24 = readI32(f.param_1.plus(0x21c))
            1305
        }
        1307 -> {
            if ((b((3.0) < (f.fVar37))) != 0) 1306 else 1032
        }
        1308 -> {
            f.dVar78 = DAT_0012f048
            1307
        }
        1309 -> {
            if ((b(((b(((b(((b((f.fVar37) <= (4.0))) != 0) || ((b((f.fVar63) <= (0.0))) != 0))) != 0) || ((b((f.fVar93) <= (0.0))) != 0))) != 0) || ((b(((b(((b((7.5) <= (f.fVar63))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0) || ((b((2.5) <= (f32((f.fVar79) - (readF32(f.param_1.plus(0x28c))))))) != 0))) != 0))) != 0) 1308 else 560
        }
        1310 -> {
            f.dVar87 = f.dVar89
            1309
        }
        1311 -> {
            f.fVar61 = f32(f32((f.local_17c) - (f.param_6)))
            1310
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep41(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1312 -> {
            352
        }
        1313 -> {
            f.fVar66 = f32(((3.0) - (f.fVar37)))
            1312
        }
        1314 -> {
            266
        }
        1315 -> {
            f.fVar42 = f32(((((6.0) - (f.fVar68))) - (f.fVar40)))
            1314
        }
        1316 -> {
            if ((b(((b(((b((((f.fVar40) + (-(3.0)))) < (0.5))) != 0) && ((b((((f.fVar68) + (-(3.0)))) < (0.5))) != 0))) != 0) && ((b((f32((f.fVar68) - (f.fVar40))) < (0.85))) != 0))) != 0) 1315 else 1312
        }
        1317 -> {
            f.fVar66 = f32(0.0)
            1316
        }
        1318 -> {
            if ((b((((f.fVar37) + (-(3.0)))) < (0.5))) != 0) 1313 else 1317
        }
        1319 -> {
            230
        }
        1320 -> {
            f.dVar30 = ((((((((DAT_0012f258) - (f.dVar52))) - (f.dVar51))) * (0.5))) * (0.5))
            1319
        }
        1321 -> {
            if ((b(((b((((f.dVar51) + (DAT_0012f0a8))) < (0.5))) != 0) && ((b((((f.dVar52) + (DAT_0012f0a8))) < (0.5))) != 0))) != 0) 1320 else 1312
        }
        1322 -> {
            f.fVar66 = f32(0.0)
            1321
        }
        1323 -> {
            f.fVar66 = f32(f32(((DAT_0012f078) - (f.dVar89))))
            1312
        }
        1324 -> {
            if ((b((0.5) <= (((f.dVar89) + (DAT_0012f0a8))))) != 0) 1322 else 1323
        }
        1325 -> {
            if ((b(((b(((b(((b((7.5) <= (f.fVar63))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0) || ((b((5.5) <= (f.fVar79))) != 0))) != 0) || ((b((readI32(f.param_1.plus(0x1ac))) < (0x25))) != 0))) != 0) 1318 else 1324
        }
        1326 -> {
            if ((b((5.0) <= (f.fVar37))) != 0) 1325 else 1311
        }
        1327 -> {
            f.fVar66 = f32(f.fVar61)
            352
        }
        1328 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1327 else 352
        }
        1329 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            1328
        }
        1330 -> {
            f.bVar12 = b((b((f.fVar61) == (0.0))) != 0)
            1329
        }
        1331 -> {
            if ((b(((b((3.0) < (f32((readF32(f.pjVar4)) + (f.param_4))))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar61).isNaN())) != 0)) }) != 0))) != 0) 1330 else 1329
        }
        1332 -> {
            f.bVar12 = b((0) != 0)
            1331
        }
        1333 -> {
            352
        }
        1334 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) 1333 else 248
        }
        1335 -> {
            352
        }
        1336 -> {
            writeF32(f.pjVar4, f32(f32(f.dVar28)))
            1335
        }
        1337 -> {
            f.dVar28 = ((((2.5) - (f.dVar89))) * (DAT_0012ee70))
            1336
        }
        1338 -> {
            f.dVar28 = ((((2.8) - (f.dVar89))) * (0.5))
            1336
        }
        1339 -> {
            if ((b((f.dVar89) <= (2.8))) != 0) 1337 else 1338
        }
        1340 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1339
        }
        1341 -> {
            if ((b(((b(((b((f.fVar37) < (3.5))) != 0) && ((b((2.5) < (f.fVar37))) != 0))) != 0) && ((b(((b((f.fVar61) == (0.0))) != 0) && ((b((2.5) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 1340 else 1334
        }
        1342 -> {
            f.fVar66 = f32(f.fVar61)
            1341
        }
        1343 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.6, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1342
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep42(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1344 -> {
            254
        }
        1345 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            1344
        }
        1346 -> {
            246
        }
        1347 -> {
            f.dVar29 = ((f.dVar29) * (f.dVar30))
            1346
        }
        1348 -> {
            245
        }
        1349 -> {
            if ((b((f.dVar28) < (f.dVar51))) != 0) 1348 else 247
        }
        1350 -> {
            f.dVar30 = DAT_0012ee70
            1349
        }
        1351 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1350
        }
        1352 -> {
            f.dVar29 = ((DAT_0012f030) - (f.dVar51))
            1351
        }
        1353 -> {
            if ((b(((b(((b((DAT_0012f030) < (f.dVar51))) != 0) && ((b((f.dVar51) < (DAT_0012f048))) != 0))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 1352 else 1345
        }
        1354 -> {
            352
        }
        1355 -> {
            f.fVar66 = f32(f.fVar61)
            1354
        }
        1356 -> {
            writeF32(f.pjVar4, f32(f32(f.dVar29)))
            1355
        }
        1357 -> {
            f.dVar29 = ((((3.3) - (f.dVar89))) * (DAT_0012ee70))
            246
        }
        1358 -> {
            247
        }
        1359 -> {
            f.dVar30 = 0.5
            1358
        }
        1360 -> {
            f.dVar29 = ((f.dVar28) - (f.dVar89))
            245
        }
        1361 -> {
            if ((b((f.dVar28) < (f.dVar89))) != 0) 1360 else 1357
        }
        1362 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1361
        }
        1363 -> {
            if ((b(((b((f.fVar37) < (4.5))) != 0) && ((b(((b(((b((3.3) < (f.dVar89))) != 0) && ((b((f.fVar61) == (0.0))) != 0))) != 0) && ((b((3.3) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 1362 else 1353
        }
        1364 -> {
            f.dVar28 = DAT_0012f050
            1363
        }
        1365 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.2, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1364
        }
        1366 -> {
            if ((b(((b((f.dVar39) <= (7.8))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) && ((b(((b((f.dVar39) <= (DAT_0012f0b0))) != 0) || ((b((f.dVar36) <= (DAT_0012f0b0))) != 0))) != 0))) != 0))) != 0) 1365 else 1343
        }
        1367 -> {
            352
        }
        1368 -> {
            f.fVar66 = f32(f.fVar61)
            1367
        }
        1369 -> {
            writeF32(f.pjVar4, f32(0.0))
            1368
        }
        1370 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (3.0)) }) != 0))) != 0))) != 0) 1369 else 1367
        }
        1371 -> {
            f.fVar26 = f32(f.fVar67)
            1370
        }
        1372 -> {
            f.fVar66 = f32(f.fVar61)
            1367
        }
        1373 -> {
            writeF32(f.pjVar4, f32(f32(f.dVar28)))
            1372
        }
        1374 -> {
            f.dVar28 = ((((3.3) - (f.dVar89))) * (DAT_0012ee70))
            1373
        }
        1375 -> {
            f.dVar28 = ((((f.dVar28) - (f.dVar89))) * (0.5))
            1373
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep43(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1376 -> {
            if ((b((f.dVar89) <= (f.dVar28))) != 0) 1374 else 1375
        }
        1377 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1376
        }
        1378 -> {
            if ((b(((b(((b((4.5) <= (f.fVar32))) != 0) || ((b((f.dVar89) <= (3.3))) != 0))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((b(((b((f.fVar76) <= (DAT_0012ef70))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030))) != 0))) != 0))) != 0))) != 0) 1371 else 1377
        }
        1379 -> {
            f.dVar28 = DAT_0012f050
            1378
        }
        1380 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 1.0, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1379
        }
        1381 -> {
            f.fVar67 = f32(f.fVar26)
            1380
        }
        1382 -> {
            if ((b(((b(((b((f.fVar85) < (8.0))) != 0) && ((b(((b((36.0) < (f.fVar48))) != 0) || ((b((f.fVar63) < (6.5))) != 0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1c4))) < (0.3))) != 0))) != 0) 1381 else 1366
        }
        1383 -> {
            243
        }
        1384 -> {
            f.fVar67 = f32(f.fVar71)
            1383
        }
        1385 -> {
            f.fVar32 = f32(1.0)
            1384
        }
        1386 -> {
            244
        }
        1387 -> {
            if ((b(((b((6.5) < (f.fVar93))) != 0) || ((b(((b(((b((4.8) <= (f.dVar75))) != 0) || ((b((f.fVar71) <= (3.5))) != 0))) != 0) || ((b((DAT_0012f160) <= (f.dVar39))) != 0))) != 0))) != 0) 1386 else 1385
        }
        1388 -> {
            f.fVar67 = f32(4.3)
            1383
        }
        1389 -> {
            f.fVar32 = f32(0.9)
            1388
        }
        1390 -> {
            if ((b(((b(((b(((b((f.fVar48) < (60.0))) != 0) && ((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((7.0) <= (f.fVar63))) != 0))) != 0))) != 0) || ((b((DAT_0012ef70) <= (f.fVar67))) != 0))) != 0) || ((b(((b((7.0) <= (f.fVar63))) != 0) || ((b((7.5) <= (f.fVar93))) != 0))) != 0))) != 0) 1387 else 1389
        }
        1391 -> {
            if ((f.bVar1) != 0) 1390 else 244
        }
        1392 -> {
            243
        }
        1393 -> {
            f.fVar67 = f32(4.2)
            1392
        }
        1394 -> {
            f.fVar32 = f32(0.8)
            1393
        }
        1395 -> {
            if ((b(((b(((b((3.5) < (f.fVar37))) != 0) && ((b((3.5) < (f.fVar40))) != 0))) != 0) && ((b(((b((3.5) < (f.fVar83))) != 0) && ((b(((b(((b((3.5) < (f.fVar61))) != 0) && ((b((54.0) < (f.fVar48))) != 0))) != 0) && ((b((f.fVar63) < (9.0))) != 0))) != 0))) != 0))) != 0) 1394 else 1391
        }
        1396 -> {
            352
        }
        1397 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1396
        }
        1398 -> {
            f.fVar67 = f32(4.7)
            243
        }
        1399 -> {
            f.fVar32 = f32(0.8)
            1398
        }
        1400 -> {
            if ((b(((b(((b(((b((f.fVar84) < (1.0))) != 0) && ((b((f.fVar67) < (1.0))) != 0))) != 0) && ((b((f.local_17c) < (12.0))) != 0))) != 0) && ((b(((b(((b((f.fVar85) < (6.0))) != 0) && ((b((72.0) < (f.fVar48))) != 0))) != 0) && ((b((f.fVar93) < (7.0))) != 0))) != 0))) != 0) 1399 else 1395
        }
        1401 -> {
            333
        }
        1402 -> {
            f.auVar50 = f32(f.param_5)
            1401
        }
        1403 -> {
            f.fVar81 = f32(3.0)
            1402
        }
        1404 -> {
            f.fVar67 = f32(1.2)
            1403
        }
        1405 -> {
            352
        }
        1406 -> {
            if ((b(((b((f.param_6) <= (f.fVar68))) != 0) || ((b((((f.dVar52) + (DAT_0012f0a0))) == (0.0))) != 0))) != 0) 1405 else 1404
        }
        1407 -> {
            324
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep44(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1408 -> {
            f.fVar62 = f32(3.2)
            1407
        }
        1409 -> {
            f.fVar67 = f32(1.2)
            1408
        }
        1410 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a0))) < (0.5))) != 0) 242 else 1406
        }
        1411 -> {
            324
        }
        1412 -> {
            f.fVar62 = f32(3.0)
            1411
        }
        1413 -> {
            f.fVar67 = f32(1.2)
            1412
        }
        1414 -> {
            242
        }
        1415 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a0))) < (0.5))) != 0) 1414 else 1413
        }
        1416 -> {
            if ((b((f.fVar48) <= (24.0))) != 0) 1415 else 1410
        }
        1417 -> {
            324
        }
        1418 -> {
            f.fVar62 = f32(2.2)
            1417
        }
        1419 -> {
            f.fVar67 = f32(1.3)
            1418
        }
        1420 -> {
            if ((b((13.0) <= (readF32(f.param_1.plus(0x168))))) != 0) 1419 else 1416
        }
        1421 -> {
            241
        }
        1422 -> {
            f.fVar67 = f32(3.5)
            1421
        }
        1423 -> {
            f.fVar62 = f32(1.0)
            1422
        }
        1424 -> {
            if ((b((f.fVar72) < (10.0))) != 0) 1423 else 1420
        }
        1425 -> {
            352
        }
        1426 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar32, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar62, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1425
        }
        1427 -> {
            f.fVar67 = f32(3.0)
            241
        }
        1428 -> {
            f.fVar62 = f32(1.2)
            1427
        }
        1429 -> {
            f.fVar67 = f32(2.5)
            241
        }
        1430 -> {
            f.fVar62 = f32(1.3)
            1429
        }
        1431 -> {
            if ((b(((b((f.fVar76) <= (0.5))) != 0) || ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0))) != 0) 1428 else 1430
        }
        1432 -> {
            f.fVar67 = f32(3.9)
            241
        }
        1433 -> {
            f.fVar62 = f32(0.95)
            1432
        }
        1434 -> {
            if ((b(((b((DAT_0012ef70) <= (f.fVar62))) != 0) || ((b((7.0) <= (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) 1431 else 1433
        }
        1435 -> {
            if ((b((48.0) < (f.fVar48))) != 0) 1434 else 1424
        }
        1436 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 4.7, 0.5, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1435
        }
        1437 -> {
            if ((b(((b(((b(((b((3.0) < (f.fVar80))) != 0) && ((b((f.fVar85) < (6.0))) != 0))) != 0) && ((b((48.0) < (f.fVar48))) != 0))) != 0) && ((b(((b((f.fVar72) < (11.0))) != 0) && ((b((f32((f.fVar33) - (f.fVar42))) < (1.2))) != 0))) != 0))) != 0) 1436 else 1435
        }
        1438 -> {
            f.fVar66 = f32(0.0)
            1437
        }
        1439 -> {
            185
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep45(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1440 -> {
            f.dVar49 = ((2.3) - (f.dVar49))
            1439
        }
        1441 -> {
            352
        }
        1442 -> {
            f.fVar66 = f32(f32(((f.dVar29) + (-(2.3)))))
            1441
        }
        1443 -> {
            if ((b((0.5) <= (((f.dVar49) + (-(2.3)))))) != 0) 1442 else 1440
        }
        1444 -> {
            f.dVar49 = ((((((4.6) - (f.dVar29))) - (f.dVar89))) * (0.5))
            1439
        }
        1445 -> {
            if ((b(((b((0.5) <= (((f.dVar89) + (-(2.3)))))) != 0) || ((b((DAT_0012ef70) <= (f.fVar76))) != 0))) != 0) 1443 else 1444
        }
        1446 -> {
            352
        }
        1447 -> {
            if ((b((0.5) <= (((f.dVar29) + (-(2.3)))))) != 0) 1446 else 1445
        }
        1448 -> {
            324
        }
        1449 -> {
            f.fVar67 = f32(1.2)
            1448
        }
        1450 -> {
            f.fVar62 = f32(3.0)
            1449
        }
        1451 -> {
            f.fVar62 = f32(3.5)
            1449
        }
        1452 -> {
            if ((b((readI32(f.param_1.plus(0x21c))) < (0x19))) != 0) 1450 else 1451
        }
        1453 -> {
            f.fVar62 = f32(3.9)
            1449
        }
        1454 -> {
            if ((b((readI32(f.param_1.plus(0x21c))) < (0x25))) != 0) 1452 else 1453
        }
        1455 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) || ((b(((b((72.0) <= (f.fVar48))) != 0) && ((b((f.fVar67) <= (0.6))) != 0))) != 0))) != 0) 1454 else 1447
        }
        1456 -> {
            241
        }
        1457 -> {
            f.fVar62 = f32(1.2)
            1456
        }
        1458 -> {
            f.fVar67 = f32(3.2)
            1457
        }
        1459 -> {
            f.fVar67 = f32(3.0)
            1457
        }
        1460 -> {
            if ((b((f.local_17c) <= (12.0))) != 0) 1458 else 1459
        }
        1461 -> {
            if ((b(((b((f.fVar33) < (f.param_6))) != 0) && ((b((f.fVar48) < (60.0))) != 0))) != 0) 1460 else 1455
        }
        1462 -> {
            if ((b((f.fVar42) <= (1.0))) != 0) 1461 else 1438
        }
        1463 -> {
            243
        }
        1464 -> {
            f.fVar67 = f32(3.0)
            1463
        }
        1465 -> {
            f.fVar32 = f32(1.2)
            1464
        }
        1466 -> {
            f.fVar67 = f32(4.5)
            1463
        }
        1467 -> {
            f.fVar32 = f32(0.5)
            1466
        }
        1468 -> {
            324
        }
        1469 -> {
            f.fVar62 = f32(3.5)
            1468
        }
        1470 -> {
            f.fVar67 = f32(0.55)
            1469
        }
        1471 -> {
            324
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep46(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1472 -> {
            f.fVar62 = f32(3.2)
            1471
        }
        1473 -> {
            f.fVar67 = f32(0.55)
            1472
        }
        1474 -> {
            if ((b(((b((f.dVar75) < (DAT_0012f030))) != 0) && ((b((f.fVar48) < (36.0))) != 0))) != 0) 1473 else 1470
        }
        1475 -> {
            239
        }
        1476 -> {
            f.fVar67 = f32(0.55)
            1475
        }
        1477 -> {
            if ((b(((b((8.0) <= (f.fVar63))) != 0) || ((b(((b((7.0) <= (f.fVar63))) != 0) && ((b((8.0) <= (f.fVar93))) != 0))) != 0))) != 0) 1476 else 1474
        }
        1478 -> {
            240
        }
        1479 -> {
            324
        }
        1480 -> {
            f.fVar62 = f32(4.0)
            1479
        }
        1481 -> {
            f.fVar67 = f32(1.0)
            1480
        }
        1482 -> {
            if ((b((1.5) < (f.fVar81))) != 0) 1481 else 1478
        }
        1483 -> {
            if ((b(((b((30.0) < (f.fVar48))) != 0) && ((b((f.fVar25) < (10.5))) != 0))) != 0) 1482 else 1477
        }
        1484 -> {
            f.fVar67 = f32(1.0)
            1469
        }
        1485 -> {
            324
        }
        1486 -> {
            f.fVar62 = f32(3.0)
            1485
        }
        1487 -> {
            f.fVar67 = f32(1.2)
            239
        }
        1488 -> {
            if ((b(((b((1.5) < (f.fVar86))) != 0) && ((b((7.0) < (f.fVar63))) != 0))) != 0) 1487 else 240
        }
        1489 -> {
            if ((b(((b((8.0) <= (f.fVar85))) != 0) || ((b((f.fVar86) <= (1.0))) != 0))) != 0) 1483 else 1488
        }
        1490 -> {
            241
        }
        1491 -> {
            f.fVar67 = f32(4.2)
            1490
        }
        1492 -> {
            f.fVar62 = f32(0.55)
            1491
        }
        1493 -> {
            if ((b(((b(((b((f.fVar35) < (1.0))) != 0) && ((b((f.fVar85) < (6.5))) != 0))) != 0) && ((b(((b((60.0) < (f.fVar48))) != 0) && ((b((f.fVar25) < (9.0))) != 0))) != 0))) != 0) 1492 else 1489
        }
        1494 -> {
            if ((b(((b(((b(((b((6.5) <= (f.fVar85))) != 0) || ((b((1.0) <= (f.fVar84))) != 0))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) || ((b((DAT_0012ef68) <= (f.dVar39))) != 0))) != 0) 1493 else 1467
        }
        1495 -> {
            if ((b((12.0) < (f.local_17c))) != 0) 1465 else 1494
        }
        1496 -> {
            if ((b((2.0) < (f.fVar42))) != 0) 1495 else 1462
        }
        1497 -> {
            241
        }
        1498 -> {
            f.fVar62 = f32(1.0)
            1497
        }
        1499 -> {
            f.fVar67 = f32(3.5)
            1498
        }
        1500 -> {
            f.fVar67 = f32(3.0)
            1498
        }
        1501 -> {
            if ((b(((b(((b((f.fVar63) <= (9.0))) != 0) || ((b((3.0) <= (f.fVar71))) != 0))) != 0) || ((b((f.fVar72) <= (14.5))) != 0))) != 0) 1499 else 1500
        }
        1502 -> {
            243
        }
        1503 -> {
            f.fVar67 = f32(3.2)
            1502
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep47(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1504 -> {
            f.fVar32 = f32(1.2)
            1503
        }
        1505 -> {
            if ((b(((b(((b((f.fVar48) < (48.0))) != 0) && ((b((6.5) < (f.fVar63))) != 0))) != 0) && ((b(((b((8.0) < (f.fVar72))) != 0) || ((b((7.0) < (f.fVar93))) != 0))) != 0))) != 0) 1504 else 1501
        }
        1506 -> {
            f.fVar67 = f32(3.5)
            1498
        }
        1507 -> {
            f.fVar67 = f32(4.5)
            1498
        }
        1508 -> {
            if ((b(((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((7.0) <= (f.fVar63))) != 0))) != 0) || ((b(((b((f.fVar48) <= (72.0))) != 0) || ((b((60.0) <= (f.fVar31))) != 0))) != 0))) != 0) 1506 else 1507
        }
        1509 -> {
            352
        }
        1510 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 1.0, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1509
        }
        1511 -> {
            if ((b((10.0) <= (f.local_17c))) != 0) 1510 else 1508
        }
        1512 -> {
            324
        }
        1513 -> {
            f.fVar62 = f32(4.3)
            1512
        }
        1514 -> {
            f.fVar67 = f32(0.55)
            1513
        }
        1515 -> {
            f.fVar62 = f32(4.7)
            1512
        }
        1516 -> {
            f.fVar67 = f32(0.5)
            1515
        }
        1517 -> {
            if ((b(((b(((b((DAT_0012f160) < (f.dVar39))) != 0) || ((b((f.fVar48) < (66.0))) != 0))) != 0) || ((b((DAT_0012f160) < (f.dVar36))) != 0))) != 0) 1514 else 1516
        }
        1518 -> {
            if ((b(((b(((b((f.fVar72) < (8.0))) != 0) && ((b((f.fVar63) < (7.5))) != 0))) != 0) && ((b((f.fVar93) < (7.5))) != 0))) != 0) 1517 else 1511
        }
        1519 -> {
            if ((b(((b(((b((1.0) <= (f32((f.fVar68) - (f.fVar40))))) != 0) || ((b((6.0) <= (f.fVar85))) != 0))) != 0) || ((b((f.fVar48) < (48.0))) != 0))) != 0) 1505 else 1518
        }
        1520 -> {
            if ((b((3.0) < (f.fVar42))) != 0) 1519 else 1496
        }
        1521 -> {
            if ((b((f.fVar42) <= (4.0))) != 0) 1520 else 1400
        }
        1522 -> {
            243
        }
        1523 -> {
            f.fVar67 = f32(3.2)
            1522
        }
        1524 -> {
            f.fVar32 = f32(1.2)
            1523
        }
        1525 -> {
            241
        }
        1526 -> {
            f.fVar62 = f32(1.2)
            1525
        }
        1527 -> {
            f.fVar67 = f32(3.5)
            1526
        }
        1528 -> {
            f.fVar67 = f32(3.9)
            1526
        }
        1529 -> {
            if ((b(((b((f.dVar75) <= (DAT_0012f038))) != 0) || ((b((f.fVar48) <= (48.0))) != 0))) != 0) 1527 else 1528
        }
        1530 -> {
            if ((b(((b(((b((42.0) < (f.fVar48))) != 0) && ((b((f.fVar72) < (9.0))) != 0))) != 0) && ((b((f.dVar36) < (7.8))) != 0))) != 0) 1529 else 1524
        }
        1531 -> {
            f.fVar67 = f32(5.0)
            1522
        }
        1532 -> {
            f.fVar32 = f32(0.75)
            1531
        }
        1533 -> {
            238
        }
        1534 -> {
            if ((b((f.fVar72) < (13.0))) != 0) 1533 else 1532
        }
        1535 -> {
            f.fVar67 = f32(4.2)
            1522
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep48(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1536 -> {
            f.fVar32 = f32(0.9)
            1535
        }
        1537 -> {
            if ((b((12.0) <= (f.fVar72))) != 0) 1534 else 1536
        }
        1538 -> {
            if ((b(((b(((b((DAT_0012ef68) <= (f.dVar39))) != 0) || ((b((DAT_0012ef70) <= (f.fVar62))) != 0))) != 0) || ((b(((b((f.fVar48) <= (36.0))) != 0) || ((b((DAT_0012ef68) <= (f.dVar36))) != 0))) != 0))) != 0) 1530 else 1537
        }
        1539 -> {
            f.fVar67 = f32(5.0)
            1522
        }
        1540 -> {
            f.fVar32 = f32(0.75)
            1539
        }
        1541 -> {
            230
        }
        1542 -> {
            f.dVar30 = ((DAT_0012f150) - (f.dVar30))
            1541
        }
        1543 -> {
            if ((b((f.fVar72) < (13.0))) != 0) 238 else 1540
        }
        1544 -> {
            241
        }
        1545 -> {
            f.fVar67 = f32(4.7)
            1544
        }
        1546 -> {
            f.fVar62 = f32(1.0)
            1545
        }
        1547 -> {
            f.fVar67 = f32(4.3)
            1544
        }
        1548 -> {
            f.fVar62 = f32(0.9)
            1547
        }
        1549 -> {
            if ((b((f.fVar63) <= (6.0))) != 0) 1546 else 1548
        }
        1550 -> {
            if ((b((f.fVar72) < (12.0))) != 0) 1549 else 1543
        }
        1551 -> {
            if ((b(((b(((b(((b((6.0) <= (f.fVar85))) != 0) || ((b((DAT_0012ef70) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (48.0))) != 0))) != 0) || ((b((7.5) <= (f.fVar63))) != 0))) != 0) 1538 else 1550
        }
        1552 -> {
            if ((b((5.0) < (f.fVar42))) != 0) 1551 else 1521
        }
        1553 -> {
            230
        }
        1554 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar28))
            1553
        }
        1555 -> {
            352
        }
        1556 -> {
            if ((b((0.5) <= (((f.dVar28) + (DAT_0012f0a8))))) != 0) 1555 else 1554
        }
        1557 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar29))
            1553
        }
        1558 -> {
            f.dVar30 = ((((((DAT_0012f258) - (f.dVar29))) - (f.dVar89))) * (0.5))
            1553
        }
        1559 -> {
            if ((b((0.5) <= (((f.dVar89) + (DAT_0012f0a8))))) != 0) 1557 else 1558
        }
        1560 -> {
            if ((b((0.6) <= (((f.dVar29) + (DAT_0012f0a8))))) != 0) 1556 else 1559
        }
        1561 -> {
            352
        }
        1562 -> {
            f.fVar66 = f32(((5.5) - (f.param_6)))
            1561
        }
        1563 -> {
            if ((b(((b((f.fVar72) < (12.0))) != 0) && ((b((((f.dVar29) + (-(5.5)))) < (0.6))) != 0))) != 0) 1562 else 1561
        }
        1564 -> {
            237
        }
        1565 -> {
            f.dVar30 = 5.3
            1564
        }
        1566 -> {
            if ((b((((f.dVar29) + (-(5.3)))) < (0.6))) != 0) 1565 else 1561
        }
        1567 -> {
            if ((b((11.0) <= (f.fVar72))) != 0) 1563 else 1566
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep49(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1568 -> {
            230
        }
        1569 -> {
            f.dVar30 = ((f.dVar30) - (f.dVar29))
            1568
        }
        1570 -> {
            f.dVar30 = 5.1
            237
        }
        1571 -> {
            if ((b((((f.dVar29) + (-(5.1)))) < (0.6))) != 0) 1570 else 1561
        }
        1572 -> {
            if ((b((10.0) <= (f.fVar72))) != 0) 1567 else 1571
        }
        1573 -> {
            f.fVar66 = f32(((5.5) - (f.fVar42)))
            1561
        }
        1574 -> {
            230
        }
        1575 -> {
            f.dVar30 = ((DAT_0012f170) - (f.dVar29))
            1574
        }
        1576 -> {
            if ((b((((f.dVar29) + (DAT_0012f180))) < (0.6))) != 0) 1575 else 1573
        }
        1577 -> {
            if ((b((9.0) <= (f.fVar72))) != 0) 1572 else 1576
        }
        1578 -> {
            if ((b((8.0) <= (f.fVar72))) != 0) 1577 else 1560
        }
        1579 -> {
            235
        }
        1580 -> {
            f.fVar67 = f32(3.9)
            1579
        }
        1581 -> {
            241
        }
        1582 -> {
            f.fVar62 = f32(1.2)
            1581
        }
        1583 -> {
            f.fVar67 = f32(3.0)
            1582
        }
        1584 -> {
            f.fVar67 = f32(3.5)
            1582
        }
        1585 -> {
            if ((b(((b((8.0) <= (f.fVar93))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x31))) != 0))) != 0) 1583 else 1584
        }
        1586 -> {
            if ((b(((b(((b(((b((7.0) <= (f.fVar63))) != 0) || ((b((7.0) <= (f.fVar93))) != 0))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x31))) != 0))) != 0) 1585 else 1580
        }
        1587 -> {
            if ((b(((b((DAT_0012ef70) <= (f.fVar62))) != 0) || ((b(((b(((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x31))) != 0))) != 0) || ((b((f.dVar89) <= (3.9))) != 0))) != 0))) != 0) 1586 else 1578
        }
        1588 -> {
            if ((b(((b((6.0) < (f.fVar42))) != 0) && ((b((f.fVar42) < (7.0))) != 0))) != 0) 1587 else 1552
        }
        1589 -> {
            352
        }
        1590 -> {
            236
        }
        1591 -> {
            if ((b((((f.dVar29) + (f.dVar28))) < (0.6))) != 0) 1590 else 1589
        }
        1592 -> {
            286
        }
        1593 -> {
            f.dVar30 = ((f.dVar30) - (f.dVar29))
            1592
        }
        1594 -> {
            f.dVar30 = 5.1
            236
        }
        1595 -> {
            if ((b(((b((f.fVar72) < (12.0))) != 0) && ((b((((f.dVar29) + (-(5.1)))) < (0.6))) != 0))) != 0) 1594 else 1589
        }
        1596 -> {
            if ((b(((b((f.fVar72) < (10.0))) != 0) || ((run { run { f.dVar30 = DAT_0012f170; DAT_0012f170 }; run { f.dVar28 = DAT_0012f180; DAT_0012f180 }; b((f.fVar72) < (11.0)) }) != 0))) != 0) 1591 else 1595
        }
        1597 -> {
            f.dVar28 = DAT_0012f0a8
            1596
        }
        1598 -> {
            f.dVar30 = DAT_0012f078
            1597
        }
        1599 -> {
            236
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep50(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1600 -> {
            f.dVar30 = 4.5
            1599
        }
        1601 -> {
            if ((b((((f.dVar29) + (-(4.5)))) < (0.6))) != 0) 1600 else 1589
        }
        1602 -> {
            if ((b((9.0) <= (f.fVar72))) != 0) 1598 else 1601
        }
        1603 -> {
            243
        }
        1604 -> {
            f.fVar32 = f32(1.2)
            1603
        }
        1605 -> {
            f.fVar67 = f32(3.0)
            235
        }
        1606 -> {
            if ((b(((b((1.0) <= (f.fVar84))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x31))) != 0))) != 0) 1605 else 1602
        }
        1607 -> {
            if ((b((7.0) < (f.fVar42))) != 0) 1606 else 1588
        }
        1608 -> {
            230
        }
        1609 -> {
            f.dVar30 = ((f.dVar30) * (f.dVar28))
            1608
        }
        1610 -> {
            f.dVar30 = ((f.dVar30) - (f.dVar29))
            234
        }
        1611 -> {
            f.dVar28 = 0.7
            1610
        }
        1612 -> {
            f.dVar30 = 4.5
            233
        }
        1613 -> {
            352
        }
        1614 -> {
            if ((b((0.5) <= (((f.dVar29) + (-(4.5)))))) != 0) 1613 else 1612
        }
        1615 -> {
            352
        }
        1616 -> {
            233
        }
        1617 -> {
            f.dVar30 = 3.5
            1616
        }
        1618 -> {
            if ((b((((f.dVar29) + (-(3.5)))) < (0.5))) != 0) 1617 else 1615
        }
        1619 -> {
            if ((b((1.2) <= (kotlin.math.abs(f32((f.fVar33) - (f.fVar42)))))) != 0) 1618 else 1614
        }
        1620 -> {
            352
        }
        1621 -> {
            266
        }
        1622 -> {
            f.fVar42 = f32(((((9.0) - (f.fVar33))) - (f.fVar68)))
            1621
        }
        1623 -> {
            352
        }
        1624 -> {
            f.fVar66 = f32(((3.0) - (f.fVar33)))
            1623
        }
        1625 -> {
            if ((b((f.fVar33) < (3.0))) != 0) 1624 else 1623
        }
        1626 -> {
            if ((b((0.5) <= (((f.dVar52) + (-(4.5)))))) != 0) 1625 else 1622
        }
        1627 -> {
            if ((b((f.fVar84) < (1.2))) != 0) 1626 else 1620
        }
        1628 -> {
            231
        }
        1629 -> {
            f.dVar28 = 9.6
            1628
        }
        1630 -> {
            if ((b((((f.dVar30) + (-(4.8)))) < (0.5))) != 0) 232 else 1620
        }
        1631 -> {
            if ((b(((b((5.5) <= (f.param_6))) != 0) || ((b((f.fVar42) <= (0.0))) != 0))) != 0) 1627 else 1630
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep51(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1632 -> {
            230
        }
        1633 -> {
            f.dVar30 = ((((((((7.8) - (f.dVar29))) - (f.dVar28))) * (0.5))) * (DAT_0012ef70))
            1632
        }
        1634 -> {
            f.dVar30 = ((((((((DAT_0012f258) - (f.dVar29))) - (f.dVar28))) * (0.5))) * (DAT_0012ef70))
            1632
        }
        1635 -> {
            352
        }
        1636 -> {
            286
        }
        1637 -> {
            f.dVar30 = ((((DAT_0012f258) - (f.dVar29))) - (f.dVar89))
            1636
        }
        1638 -> {
            if ((b((((f.dVar29) + (DAT_0012f0a8))) < (0.5))) != 0) 1637 else 1635
        }
        1639 -> {
            if ((b((f.fVar33) < (1.0))) != 0) 1638 else 1634
        }
        1640 -> {
            if ((b((1.0) <= (kotlin.math.abs(f32((f.fVar42) - (f.fVar33)))))) != 0) 1633 else 1639
        }
        1641 -> {
            if ((b((f.param_6) < (4.5))) != 0) 1640 else 1631
        }
        1642 -> {
            352
        }
        1643 -> {
            286
        }
        1644 -> {
            f.dVar30 = ((((f.dVar28) - (f.dVar29))) - (f.dVar30))
            1643
        }
        1645 -> {
            f.dVar28 = 10.6
            231
        }
        1646 -> {
            352
        }
        1647 -> {
            if ((b((6.5) <= (f.param_6))) != 0) 1646 else 1645
        }
        1648 -> {
            f.dVar28 = 10.2
            231
        }
        1649 -> {
            if ((b((6.0) <= (f.param_6))) != 0) 1647 else 1648
        }
        1650 -> {
            232
        }
        1651 -> {
            if ((b((f.param_6) < (5.5))) != 0) 1650 else 1649
        }
        1652 -> {
            if ((b(((b((0.0) < (f.fVar42))) != 0) && ((b((kotlin.math.abs(f32((f.fVar42) - (f.param_6)))) < (DAT_0012ef70))) != 0))) != 0) 1651 else 1642
        }
        1653 -> {
            266
        }
        1654 -> {
            f.fVar42 = f32(((6.0) - (f.param_6)))
            1653
        }
        1655 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.5))) != 0) 1654 else 1642
        }
        1656 -> {
            230
        }
        1657 -> {
            f.dVar30 = ((((3.9) - (f.dVar29))) * (0.5))
            1656
        }
        1658 -> {
            if ((b(((b(((b((((f.dVar29) + (-(3.9)))) < (0.5))) != 0) && ((b((f.local_17c) < (12.0))) != 0))) != 0) && ((b((((f.dVar89) + (-(3.9)))) < (0.5))) != 0))) != 0) 1657 else 1655
        }
        1659 -> {
            if ((b((1.2) < (kotlin.math.abs(f32((f.fVar42) - (f.fVar33)))))) != 0) 1658 else 1642
        }
        1660 -> {
            if ((b((4.5) <= (f.param_6))) != 0) 1652 else 1659
        }
        1661 -> {
            if ((b(((b((7.0) <= (f.fVar33))) != 0) || ((b((f.dVar28) <= (DAT_0012f018))) != 0))) != 0) 1660 else 1641
        }
        1662 -> {
            if ((b(((b(((b((3.9) <= (f.dVar28))) != 0) || ((b((f.local_17c) <= (8.0))) != 0))) != 0) || ((b(((b((f.fVar33) <= (2.0))) != 0) && ((b((f.fVar42) <= (2.0))) != 0))) != 0))) != 0) 1661 else 1619
        }
        1663 -> {
            230
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep52(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1664 -> {
            f.dVar30 = ((((3.9) - (f.dVar28))) * (0.5))
            1663
        }
        1665 -> {
            f.dVar30 = ((((((DAT_0012f258) - (f.dVar29))) - (f.dVar49))) * (0.5))
            1663
        }
        1666 -> {
            if ((b(((b((1.0) <= (f.fVar33))) != 0) || ((b((1.5) <= (f.fVar86))) != 0))) != 0) 1664 else 1665
        }
        1667 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0xa0))) < (4.0))) != 0) && ((b((1.2) < (readF32(f.param_1.plus(0xa0))))) != 0))) != 0) && ((b(((b((6.0) < (f.local_17c))) != 0) && ((b((f.dVar28) < (3.9))) != 0))) != 0))) != 0) 1666 else 1662
        }
        1668 -> {
            if ((b((7.5) <= (f.fVar42))) != 0) 1667 else 1607
        }
        1669 -> {
            f.dVar28 = f.fVar33
            1668
        }
        1670 -> {
            243
        }
        1671 -> {
            f.fVar67 = f32(4.5)
            1670
        }
        1672 -> {
            f.fVar32 = f32(0.5)
            1671
        }
        1673 -> {
            241
        }
        1674 -> {
            f.fVar62 = f32(1.2)
            1673
        }
        1675 -> {
            f.fVar67 = f32(3.0)
            1674
        }
        1676 -> {
            f.fVar67 = f32(3.5)
            1674
        }
        1677 -> {
            if ((b(((b((8.0) <= (f.fVar63))) != 0) || ((b((8.0) <= (f.fVar93))) != 0))) != 0) 1675 else 1676
        }
        1678 -> {
            if ((b(((b((8.0) <= (f.fVar72))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) 1677 else 1672
        }
        1679 -> {
            352
        }
        1680 -> {
            230
        }
        1681 -> {
            f.dVar30 = ((4.8) - (f.dVar29))
            1680
        }
        1682 -> {
            if ((b((((f.dVar29) + (-(4.8)))) < (0.0))) != 0) 1681 else 1679
        }
        1683 -> {
            if ((b((4.5) <= (f.param_6))) != 0) 1682 else 1678
        }
        1684 -> {
            f.fVar67 = f32(4.7)
            1670
        }
        1685 -> {
            f.fVar32 = f32(0.5)
            1684
        }
        1686 -> {
            241
        }
        1687 -> {
            f.fVar67 = f32(3.0)
            1686
        }
        1688 -> {
            f.fVar62 = f32(1.2)
            1687
        }
        1689 -> {
            f.fVar67 = f32(2.5)
            1686
        }
        1690 -> {
            f.fVar62 = f32(1.3)
            1689
        }
        1691 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) || ((b(((b((f.fVar67) <= (1.0))) != 0) && ((b((f.fVar85) <= (8.0))) != 0))) != 0))) != 0) 1688 else 1690
        }
        1692 -> {
            f.fVar67 = f32(3.2)
            1686
        }
        1693 -> {
            f.fVar62 = f32(1.2)
            1692
        }
        1694 -> {
            f.fVar67 = f32(3.5)
            1686
        }
        1695 -> {
            f.fVar62 = f32(1.0)
            1694
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep53(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1696 -> {
            if ((b((10.0) <= (f.local_17c))) != 0) 1693 else 1695
        }
        1697 -> {
            if ((b((readI32(f.param_1.plus(0x21c))) < (0x19))) != 0) 1691 else 1696
        }
        1698 -> {
            if ((b(((b(((b((1.2) <= (f32((f.fVar33) - (f.fVar42))))) != 0) || ((b((11.0) <= (f.local_17c))) != 0))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x31))) != 0))) != 0) 1697 else 1685
        }
        1699 -> {
            if ((b((f.local_17c) <= (12.0))) != 0) 1683 else 1698
        }
        1700 -> {
            if ((b((100.0) <= (f.fVar31))) != 0) 1699 else 1669
        }
        1701 -> {
            f.fVar32 = f32(f.fVar37)
            1700
        }
        1702 -> {
            f.fVar85 = f32(f32((f.fVar72) - (f.fVar42)))
            1701
        }
        1703 -> {
            352
        }
        1704 -> {
            if ((b((f.param_14) < (0x120))) != 0) 1703 else 1702
        }
        1705 -> {
            f.fVar66 = f32(0.0)
            1704
        }
        1706 -> {
            if ((b(uLt(((f.param_14) - (6)), 0x11a))) != 0) 1326 else 1705
        }
        1707 -> {
            f.fVar35 = f32(f32((f.fVar26) - (f.fVar37)))
            1706
        }
        1708 -> {
            f.dVar49 = f.fVar26
            1707
        }
        1709 -> {
            f.dVar29 = f.param_6
            1708
        }
        1710 -> {
            248
        }
        1711 -> {
            if ((b((f.iVar24) == (1))) != 0) 1710 else 352
        }
        1712 -> {
            f.fVar66 = f32(f.fVar61)
            1711
        }
        1713 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            254
        }
        1714 -> {
            352
        }
        1715 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1714
        }
        1716 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            1715
        }
        1717 -> {
            f.fVar66 = f32(f32(((((DAT_0012f030) - (f.dVar28))) * (ARR_0012f2d8[b((DAT_0012f050) < (f.dVar28))]))))
            1716
        }
        1718 -> {
            if ((b(((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b((DAT_0012f030) < (f.dVar28))) != 0))) != 0) && ((b((f.dVar28) < (4.3))) != 0))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 1717 else 1713
        }
        1719 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1718
        }
        1720 -> {
            f.dVar28 = f.dVar75
            1719
        }
        1721 -> {
            f.fVar67 = f32(3.2)
            1720
        }
        1722 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 1721 else 1720
        }
        1723 -> {
            f.fVar67 = f32(2.8)
            1722
        }
        1724 -> {
            f.bVar12 = b((b((f.dVar75) < (2.8))) != 0)
            1723
        }
        1725 -> {
            if ((b(((b((12.0) < (f.fVar72))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar75).isNaN())) != 0)) }) != 0))) != 0) 1724 else 1723
        }
        1726 -> {
            f.bVar12 = b((0) != 0)
            1725
        }
        1727 -> {
            336
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep54(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1728 -> {
            f.auVar53 = f32(f.param_5)
            1727
        }
        1729 -> {
            f.fVar77 = f32(4.7)
            1728
        }
        1730 -> {
            f.fVar67 = f32(0.5)
            1729
        }
        1731 -> {
            253
        }
        1732 -> {
            f.fVar67 = f32(0.5)
            1731
        }
        1733 -> {
            if ((b(((b(((b((f.dVar29) < (4.3))) != 0) && ((b((7.0) < (f.fVar25))) != 0))) != 0) && ((b(((b((0.35) < (f.dVar38))) != 0) || ((b((5.8) <= (f.dVar36))) != 0))) != 0))) != 0) 1732 else 1730
        }
        1734 -> {
            252
        }
        1735 -> {
            if ((b(((b(((b((f.fVar71) < (4.0))) != 0) && ((b((0.5) < (f.fVar47))) != 0))) != 0) && ((b((7.0) < (f.fVar63))) != 0))) != 0) 1734 else 1733
        }
        1736 -> {
            260
        }
        1737 -> {
            f.fVar81 = f32(4.2)
            1736
        }
        1738 -> {
            f.fVar67 = f32(0.5)
            1737
        }
        1739 -> {
            if ((b((f.fVar71) < (4.0))) != 0) 252 else 1733
        }
        1740 -> {
            if ((b((f.dVar38) <= (DAT_0012ef60))) != 0) 1735 else 1739
        }
        1741 -> {
            f.fVar77 = f32(3.2)
            1728
        }
        1742 -> {
            f.fVar67 = f32(0.55)
            1741
        }
        1743 -> {
            f.fVar77 = f32(4.2)
            1728
        }
        1744 -> {
            f.fVar67 = f32(0.55)
            253
        }
        1745 -> {
            if ((b(((b((f.fVar48) <= (72.0))) != 0) || ((b((f.dVar49) <= (DAT_0012f240))) != 0))) != 0) 1742 else 1744
        }
        1746 -> {
            if ((b(((b(((b((3.9) <= (f.dVar29))) != 0) || ((b((f.fVar25) <= (7.5))) != 0))) != 0) || ((b((f.fVar63) <= (6.5))) != 0))) != 0) 1740 else 1745
        }
        1747 -> {
            336
        }
        1748 -> {
            f.fVar77 = f32(3.9)
            1747
        }
        1749 -> {
            f.fVar67 = f32(0.45)
            1748
        }
        1750 -> {
            f.fVar77 = f32(3.5)
            1747
        }
        1751 -> {
            f.fVar67 = f32(0.5)
            1750
        }
        1752 -> {
            if ((b(((b((DAT_0012f050) <= (f.dVar75))) != 0) || ((b(((b((3.9) <= (f.dVar29))) != 0) && ((b((f.dVar38) <= (DAT_0012ef60))) != 0))) != 0))) != 0) 1749 else 1751
        }
        1753 -> {
            f.auVar53 = f32(f.param_5)
            1752
        }
        1754 -> {
            if ((b(((b(((b(((b(((b(((b((f.fVar80) < (4.5))) != 0) && ((b((1.0) < (f.fVar35))) != 0))) != 0) || ((b(((b((f.dVar29) < (DAT_0012f098))) != 0) && ((b((0.7) < (f.fVar35))) != 0))) != 0))) != 0) || ((b(((b((f.dVar29) < (DAT_0012f048))) != 0) && ((b((0.6) < (f.fVar35))) != 0))) != 0))) != 0) && ((b((8.2) < (f.dVar78))) != 0))) != 0) && ((b(((b((f.dVar75) < (DAT_0012f060))) != 0) && ((b((DAT_0012ef38) < (f.dVar38))) != 0))) != 0))) != 0) 1753 else 1746
        }
        1755 -> {
            260
        }
        1756 -> {
            f.fVar81 = f32(3.0)
            1755
        }
        1757 -> {
            f.fVar67 = f32(0.45)
            1756
        }
        1758 -> {
            if ((b(((b(((b((f.fVar80) < (3.5))) != 0) && ((b((5.0) <= (f.fVar93))) != 0))) != 0) && ((b(((b((f.fVar48) < (72.0))) != 0) && ((b((7.0) < (f.fVar25))) != 0))) != 0))) != 0) 1757 else 1754
        }
        1759 -> {
            if ((b(((b(((b((f.fVar67) < (1.2))) != 0) && ((b((f.dVar29) < (DAT_0012f150))) != 0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (5.5))) != 0))) != 0) 1758 else 1726
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep55(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1760 -> {
            260
        }
        1761 -> {
            f.fVar67 = f32(1.0)
            1760
        }
        1762 -> {
            f.fVar81 = f32(3.5)
            1761
        }
        1763 -> {
            336
        }
        1764 -> {
            f.auVar53 = f32(f.param_5)
            1763
        }
        1765 -> {
            f.fVar77 = f32(4.2)
            1764
        }
        1766 -> {
            f.fVar67 = f32(0.55)
            1765
        }
        1767 -> {
            251
        }
        1768 -> {
            f.fVar67 = f32(0.55)
            1767
        }
        1769 -> {
            if ((b((7.8) < (f.dVar39))) != 0) 1768 else 1766
        }
        1770 -> {
            250
        }
        1771 -> {
            if ((b(((b(((b((f.fVar72) < (9.5))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b((f.fVar63) < (6.5))) != 0))) != 0) 1770 else 1769
        }
        1772 -> {
            f.fVar77 = f32(3.5)
            1764
        }
        1773 -> {
            f.fVar67 = f32(1.0)
            251
        }
        1774 -> {
            f.fVar77 = f32(3.0)
            1764
        }
        1775 -> {
            f.fVar67 = f32(1.0)
            1774
        }
        1776 -> {
            if ((b(((b((8.5) <= (f.fVar72))) != 0) || ((b((f.fVar93) <= (8.0))) != 0))) != 0) 1773 else 1775
        }
        1777 -> {
            if ((b(((b(((b((3.0) <= (f.fVar80))) != 0) || ((b((f.dVar38) <= (DAT_0012f010))) != 0))) != 0) || ((b((f.fVar63) <= (6.0))) != 0))) != 0) 1771 else 1776
        }
        1778 -> {
            260
        }
        1779 -> {
            f.fVar81 = f32(4.7)
            1778
        }
        1780 -> {
            f.fVar67 = f32(0.5)
            1779
        }
        1781 -> {
            if ((b((0.5) <= (((f.param_6) + (-(3.5)))))) != 0) 250 else 1777
        }
        1782 -> {
            if ((b(((b(((b((4.3) <= (f.dVar29))) != 0) || ((b(((b((f.fVar63) <= (6.0))) != 0) && ((b((f.fVar93) <= (6.0))) != 0))) != 0))) != 0) || ((b(((b((f.fVar80) <= (2.0))) != 0) || ((b(((b((f.dVar38) <= (DAT_0012f040))) != 0) || ((b((f.fVar72) <= (6.8))) != 0))) != 0))) != 0))) != 0) 1781 else 1762
        }
        1783 -> {
            f.fVar81 = f32(3.0)
            1761
        }
        1784 -> {
            if ((b(((b(((b(((b((3.0) <= (f.fVar80))) != 0) || ((b(((b((f.fVar63) <= (6.0))) != 0) && ((b((f.fVar93) <= (6.0))) != 0))) != 0))) != 0) || ((b((f.dVar38) <= (0.3))) != 0))) != 0) || ((b(((b(((b((f.dVar78) <= (6.8))) != 0) || ((b((f.fVar71) <= (2.5))) != 0))) != 0) || ((b((f.dVar29) <= (DAT_0012ee90))) != 0))) != 0))) != 0) 1782 else 1783
        }
        1785 -> {
            if ((b(((b(((b((f.fVar33) < (3.0))) != 0) && ((b((f.fVar42) < (3.0))) != 0))) != 0) && ((b((f.fVar63) < (8.5))) != 0))) != 0) 1784 else 1759
        }
        1786 -> {
            if ((b((f.fVar68) <= (8.0))) != 0) 1785 else 352
        }
        1787 -> {
            f.fVar66 = f32(0.0)
            1786
        }
        1788 -> {
            f.param_5 = f32(f.auVar53)
            352
        }
        1789 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar77, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1788
        }
        1790 -> {
            f.fVar77 = f32(3.2)
            336
        }
        1791 -> {
            f.fVar67 = f32(1.2)
            1790
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep56(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1792 -> {
            f.fVar77 = f32(2.5)
            336
        }
        1793 -> {
            f.fVar67 = f32(1.3)
            1792
        }
        1794 -> {
            if ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) 1791 else 1793
        }
        1795 -> {
            f.auVar53 = f32(f.param_5)
            1794
        }
        1796 -> {
            f.fVar67 = f32(0.5)
            336
        }
        1797 -> {
            f.fVar77 = f32(4.5)
            1796
        }
        1798 -> {
            f.fVar77 = f32(4.2)
            1796
        }
        1799 -> {
            if ((b(((b((f.fVar25) <= (10.0))) != 0) || ((b((f.dVar38) <= (0.6))) != 0))) != 0) 1797 else 1798
        }
        1800 -> {
            f.auVar53 = f32(f.param_5)
            1799
        }
        1801 -> {
            if ((b(((b(((b(((b((DAT_0012ef60) <= (f.fVar32))) != 0) || ((b((DAT_0012ef70) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (36.0))) != 0))) != 0) || ((b(((b((f.dVar29) <= (4.6))) != 0) && ((b((8.5) <= (f.fVar72))) != 0))) != 0))) != 0) 1795 else 1800
        }
        1802 -> {
            352
        }
        1803 -> {
            if ((b((f.fVar31) < (200.0))) != 0) 1802 else 1801
        }
        1804 -> {
            f.fVar66 = f32(0.0)
            1803
        }
        1805 -> {
            f.auVar53 = f32(f.param_5)
            336
        }
        1806 -> {
            f.fVar77 = f32(3.0)
            265
        }
        1807 -> {
            f.fVar67 = f32(1.2)
            1806
        }
        1808 -> {
            f.fVar77 = f32(2.5)
            265
        }
        1809 -> {
            f.fVar67 = f32(1.3)
            1808
        }
        1810 -> {
            if ((b(((b(((b((f.fVar35) <= (1.2))) != 0) || ((b((DAT_0012f030) <= (f.dVar75))) != 0))) != 0) || ((b(((b((48.0) <= (f.fVar48))) != 0) || ((b(((b((4.5) <= (f.fVar80))) != 0) || ((b((f.fVar25) <= (13.0))) != 0))) != 0))) != 0))) != 0) 1807 else 1809
        }
        1811 -> {
            352
        }
        1812 -> {
            if ((b((f.fVar62) <= (1.0))) != 0) 1811 else 1810
        }
        1813 -> {
            f.fVar66 = f32(0.0)
            1812
        }
        1814 -> {
            f.fVar67 = f32(0.5)
            336
        }
        1815 -> {
            f.fVar77 = f32(4.2)
            1814
        }
        1816 -> {
            f.fVar77 = f32(4.7)
            1814
        }
        1817 -> {
            if ((b(((b(((b((0.3) < (f.dVar45))) != 0) && ((b((f.dVar49) < (-(0.3)))) != 0))) != 0) || ((b(((b((DAT_0012f200) < (f.dVar45))) != 0) && ((b((f.fVar44) < (f.fVar64))) != 0))) != 0))) != 0) 1815 else 1816
        }
        1818 -> {
            f.auVar53 = f32(f.param_5)
            1817
        }
        1819 -> {
            if ((b(((b(((b((5.5) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) 1813 else 1818
        }
        1820 -> {
            if ((b(((b((f.fVar31) <= (180.0))) != 0) || ((b((200.0) <= (f.fVar31))) != 0))) != 0) 1804 else 1819
        }
        1821 -> {
            352
        }
        1822 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar81, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1821
        }
        1823 -> {
            f.fVar67 = f32(0.65)
            259
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep57(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1824 -> {
            f.fVar81 = f32(4.2)
            1823
        }
        1825 -> {
            f.fVar81 = f32(4.5)
            1823
        }
        1826 -> {
            if ((b(((b(((b((f.dVar89) <= (DAT_0012f060))) != 0) || ((b((f.dVar51) <= (DAT_0012f060))) != 0))) != 0) || ((b((f.dVar82) <= (DAT_0012f060))) != 0))) != 0) 1824 else 1825
        }
        1827 -> {
            352
        }
        1828 -> {
            336
        }
        1829 -> {
            f.auVar53 = f32(f.param_5)
            1828
        }
        1830 -> {
            f.fVar77 = f32(3.5)
            1829
        }
        1831 -> {
            f.fVar67 = f32(1.0)
            1830
        }
        1832 -> {
            324
        }
        1833 -> {
            f.fVar67 = f32(0.55)
            1832
        }
        1834 -> {
            f.fVar62 = f32(3.9)
            1833
        }
        1835 -> {
            f.fVar62 = f32(3.5)
            1833
        }
        1836 -> {
            if ((b(((b(((b((f.fVar31) <= (84.0))) != 0) || ((b((f.dVar38) <= (DAT_0012ef60))) != 0))) != 0) || ((b(((b((DAT_0012f060) <= (f.dVar75))) != 0) || ((b((f.fVar63) <= (6.5))) != 0))) != 0))) != 0) 1834 else 1835
        }
        1837 -> {
            if ((b(((b(((b((4.5) <= (f.fVar80))) != 0) || ((b((f.fVar35) <= (DAT_0012ef70))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012ef60))) != 0))) != 0) 1836 else 1831
        }
        1838 -> {
            260
        }
        1839 -> {
            f.fVar81 = f32(4.7)
            1838
        }
        1840 -> {
            f.fVar67 = f32(0.4)
            1839
        }
        1841 -> {
            352
        }
        1842 -> {
            if ((b(((b((8.0) <= (f.fVar25))) != 0) && ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((DAT_0012ef68) <= (f.dVar36)) }) != 0))) != 0) 1841 else 1840
        }
        1843 -> {
            f.fVar81 = f32(4.2)
            1838
        }
        1844 -> {
            f.fVar67 = f32(0.55)
            1843
        }
        1845 -> {
            if ((b(((b((f.fVar47) <= (1.0))) != 0) || ((b(((b((f.fVar93) <= (7.0))) != 0) && ((b(((b((f.fVar72) <= (8.0))) != 0) || ((b((f.fVar63) <= (6.5))) != 0))) != 0))) != 0))) != 0) 1842 else 1844
        }
        1846 -> {
            if ((b(((b(((b((5.3) <= (f.dVar29))) != 0) || ((b(((b((f.dVar38) <= (0.7))) != 0) && ((b((f.fVar93) <= (7.0))) != 0))) != 0))) != 0) || ((b((f.fVar25) <= (9.5))) != 0))) != 0) 1845 else 1837
        }
        1847 -> {
            f.fVar67 = f32(1.2)
            1829
        }
        1848 -> {
            f.fVar77 = f32(3.0)
            1847
        }
        1849 -> {
            f.fVar77 = f32(3.5)
            1847
        }
        1850 -> {
            if ((b(((b((DAT_0012f230) <= (f.dVar45))) != 0) || ((b((f.dVar49) <= (-(0.3)))) != 0))) != 0) 1848 else 1849
        }
        1851 -> {
            if ((b(((b(((b((3.0) <= (f.fVar71))) != 0) || ((b((f.fVar25) <= (9.0))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012f010))) != 0))) != 0) 1846 else 1850
        }
        1852 -> {
            352
        }
        1853 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1852
        }
        1854 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            1853
        }
        1855 -> {
            f.fVar66 = f32(f32(((((f.dVar28) - (f.dVar75))) * (ARR_0012f2d8[b((DAT_0012f050) < (f.dVar75))]))))
            1854
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep58(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1856 -> {
            254
        }
        1857 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            1856
        }
        1858 -> {
            if ((b(((b(((b((4.3) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (f.dVar28))) != 0))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.0))) != 0))) != 0))) != 0) 1857 else 1855
        }
        1859 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.2, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1858
        }
        1860 -> {
            f.dVar28 = DAT_0012f030
            1859
        }
        1861 -> {
            257
        }
        1862 -> {
            f.fVar67 = f32(3.5)
            1861
        }
        1863 -> {
            f.fVar32 = f32(1.0)
            1862
        }
        1864 -> {
            if ((b(((b(((b(((b((f.fVar72) < (12.0))) != 0) && ((b((3.5) < (f.fVar80))) != 0))) != 0) && ((b(((b((f.fVar72) < (10.0))) != 0) || ((b((54.0) < (f.fVar48))) != 0))) != 0))) != 0) && ((b((f.fVar63) < (7.5))) != 0))) != 0) 1863 else 1860
        }
        1865 -> {
            303
        }
        1866 -> {
            f.bVar16 = b((b((f.fVar42) < (3.0))) != 0)
            1865
        }
        1867 -> {
            f.bVar13 = b((b((f.fVar42) == (3.0))) != 0)
            1866
        }
        1868 -> {
            f.bVar12 = b((b((f.fVar42).isNaN())) != 0)
            1867
        }
        1869 -> {
            f.fVar42 = f32(f32((f.fVar66) + (f.param_4)))
            1868
        }
        1870 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            1869
        }
        1871 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) 1870 else 1852
        }
        1872 -> {
            f.fVar66 = f32(f.fVar61)
            1871
        }
        1873 -> {
            256
        }
        1874 -> {
            f.dVar28 = ((ARR_0012f2d8[b((3.3) < (f.dVar75))]) * (((3.0) - (f.fVar67))))
            1873
        }
        1875 -> {
            if ((b(((b(((b(((b((f.dVar75) < (4.6))) != 0) && ((b((3.0) < (f.fVar67))) != 0))) != 0) && ((b((f.fVar61) == (0.0))) != 0))) != 0) && ((b((3.0) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 1874 else 1872
        }
        1876 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1875
        }
        1877 -> {
            f.fVar67 = f32(f.fVar71)
            1876
        }
        1878 -> {
            if ((b(((b(((b((f.fVar93) <= (8.0))) != 0) || ((b(((b((f.fVar35) <= (DAT_0012ee90))) != 0) && ((b((f.dVar39) <= (DAT_0012f0b0))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.0))) != 0))) != 0) 1864 else 1877
        }
        1879 -> {
            if ((b(((b(((b(((b(((b(((b((f.fVar35) <= (1.5))) != 0) || ((b((f.dVar82) <= (DAT_0012f070))) != 0))) != 0) || ((b((f.dVar51) <= (DAT_0012f070))) != 0))) != 0) || ((b(((b((0.75) <= (f.fVar32))) != 0) || ((b((f.fVar71) <= (2.5))) != 0))) != 0))) != 0) || ((b((3.0) <= (f.fVar71))) != 0))) != 0) || ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((f.dVar38) <= (DAT_0012ee90)) }) != 0))) != 0) 1878 else 1852
        }
        1880 -> {
            257
        }
        1881 -> {
            f.fVar32 = f32(1.2)
            1880
        }
        1882 -> {
            f.fVar67 = f32(3.2)
            1881
        }
        1883 -> {
            f.fVar67 = f32(2.5)
            1881
        }
        1884 -> {
            if ((b(((b(((b((f.dVar45) < (0.3))) != 0) && ((b((-(0.3)) < (f.dVar49))) != 0))) != 0) || ((b(((b((f.dVar45) < (0.35))) != 0) && ((b((f.fVar69) < (DAT_0012f178))) != 0))) != 0))) != 0) 1882 else 1883
        }
        1885 -> {
            255
        }
        1886 -> {
            if ((b(((b(((b((54.0) <= (f.fVar48))) != 0) || ((b((f.dVar39) <= (8.2))) != 0))) != 0) || ((b((f.dVar38) <= (1.2))) != 0))) != 0) 1885 else 1884
        }
        1887 -> {
            f.fVar67 = f32(3.0)
            1881
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep59(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1888 -> {
            f.fVar67 = f32(3.5)
            1881
        }
        1889 -> {
            if ((b(((b((f.fVar48) <= (60.0))) != 0) || ((b((DAT_0012ef68) <= (f.dVar39))) != 0))) != 0) 1887 else 1888
        }
        1890 -> {
            if ((b(((b((f.fVar47) <= (1.0))) != 0) || ((b(((b((f.fVar48) <= (60.0))) != 0) && ((b((8.2) <= (f.dVar39))) != 0))) != 0))) != 0) 1886 else 1889
        }
        1891 -> {
            if ((b(((b((f.fVar71) < (3.0))) != 0) && ((b((9.0) < (f.fVar25))) != 0))) != 0) 1890 else 255
        }
        1892 -> {
            f.fVar66 = f32(f32(f.dVar28))
            1852
        }
        1893 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1892
        }
        1894 -> {
            writeF32(f.param_1.plus(400), f32(f32(f.dVar28)))
            1893
        }
        1895 -> {
            f.dVar28 = ((((2.5) - (f.dVar75))) * (ARR_0012f2e8[b((2.8) < (f.dVar75))]))
            256
        }
        1896 -> {
            262
        }
        1897 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            1896
        }
        1898 -> {
            if ((b(((b(((b((3.8) <= (f.dVar75))) != 0) || ((b((f.fVar71) <= (2.5))) != 0))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((b(((b((f.fVar76) <= (DAT_0012ef70))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (2.5))) != 0))) != 0))) != 0))) != 0) 1897 else 1895
        }
        1899 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1898
        }
        1900 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) || ((b((60.0) <= (f.fVar48))) != 0))) != 0) || ((b(((b((f.fVar62) <= (6.0))) != 0) && ((b((f.fVar32) <= (0.6))) != 0))) != 0))) != 0) 1891 else 1899
        }
        1901 -> {
            352
        }
        1902 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 0.75, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1901
        }
        1903 -> {
            if ((b(((b(((b(((b((4.3) <= (f.dVar29))) != 0) || ((b((3.5) <= (f.fVar71))) != 0))) != 0) || ((b((f.fVar71) <= (3.0))) != 0))) != 0) || ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((f.fVar35) <= (1.2)) }) != 0))) != 0) 1902 else 1901
        }
        1904 -> {
            if ((b(((b((0.75) < (f.fVar47))) != 0) && ((b(((b((66.0) < (f.fVar48))) != 0) || ((b(((b((f.fVar72) < (10.0))) != 0) || ((b((f.fVar63) < (7.0))) != 0))) != 0))) != 0))) != 0) 1903 else 1900
        }
        1905 -> {
            if ((b(((b((f.fVar62) < (7.0))) != 0) && ((b(((b((f.fVar67) < (1.0))) != 0) || ((b((f.fVar72) < (10.0))) != 0))) != 0))) != 0) 1904 else 1900
        }
        1906 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1852
        }
        1907 -> {
            f.fVar67 = f32(4.2)
            257
        }
        1908 -> {
            f.fVar32 = f32(0.5)
            1907
        }
        1909 -> {
            if ((b(((b(((b((f.fVar71) <= (3.5))) != 0) && ((b((6.5) <= (f.fVar62))) != 0))) != 0) || ((b(((b((0.5) <= (f.fVar32))) != 0) || ((b(((b((DAT_0012ef60) <= (f.fVar67))) != 0) || ((b((f.fVar48) <= (54.0))) != 0))) != 0))) != 0))) != 0) 1905 else 1908
        }
        1910 -> {
            f.fVar62 = f32(f32((f.fVar72) - (f.fVar42)))
            1909
        }
        1911 -> {
            if ((b(((b(((b(((b((5.1) <= (f.dVar29))) != 0) || ((b((0.85) <= (f.fVar32))) != 0))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) 1910 else 1851
        }
        1912 -> {
            260
        }
        1913 -> {
            f.fVar81 = f32(4.5)
            1912
        }
        1914 -> {
            f.fVar67 = f32(0.55)
            1913
        }
        1915 -> {
            f.fVar81 = f32(3.5)
            1912
        }
        1916 -> {
            f.fVar67 = f32(0.55)
            1915
        }
        1917 -> {
            336
        }
        1918 -> {
            f.fVar67 = f32(0.55)
            1917
        }
        1919 -> {
            f.fVar77 = f32(3.5)
            1918
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep60(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1920 -> {
            if ((b(((b((f.fVar71) <= (3.5))) != 0) || ((run { run { f.fVar77 = f32(f.fVar71); f32(f.fVar71) }; b((4.5) <= (f.fVar71)) }) != 0))) != 0) 1919 else 1918
        }
        1921 -> {
            f.auVar53 = f32(f.param_5)
            1920
        }
        1922 -> {
            if ((b((DAT_0012f108) <= (f.dVar49))) != 0) 1921 else 1916
        }
        1923 -> {
            if ((b(((b((f.dVar38) <= (0.85))) != 0) || ((b((f.dVar39) <= (6.8))) != 0))) != 0) 1914 else 1922
        }
        1924 -> {
            352
        }
        1925 -> {
            if ((b(((b((5.5) < (f.fVar80))) != 0) && ((b(((b((6.0) < (f.fVar63))) != 0) && ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((6.5) < (f.fVar93)) }) != 0))) != 0))) != 0) 1924 else 1923
        }
        1926 -> {
            if ((b(((b(((b((DAT_0012f060) < (f.dVar75))) != 0) && ((b((4.55) < (f.dVar29))) != 0))) != 0) && ((b(((b((54.0) < (f.fVar48))) != 0) || ((b(((b((f.dVar45) < (0.3))) != 0) && ((b((-(0.3)) < (f.dVar49))) != 0))) != 0))) != 0))) != 0) 1925 else 1911
        }
        1927 -> {
            f.fVar67 = f32(1.0)
            1829
        }
        1928 -> {
            f.fVar77 = f32(3.5)
            1927
        }
        1929 -> {
            f.fVar77 = f32(3.0)
            1927
        }
        1930 -> {
            if ((b(((b(((b((3.3) <= (f.dVar29))) != 0) || ((b(((b((f.fVar63) <= (6.0))) != 0) && ((b((f.fVar93) <= (6.0))) != 0))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012f010))) != 0))) != 0) 1928 else 1929
        }
        1931 -> {
            265
        }
        1932 -> {
            f.fVar77 = f32(4.2)
            1931
        }
        1933 -> {
            f.fVar67 = f32(0.4)
            1932
        }
        1934 -> {
            265
        }
        1935 -> {
            f.fVar77 = f32(4.7)
            1934
        }
        1936 -> {
            f.fVar67 = f32(0.45)
            1935
        }
        1937 -> {
            if ((b(((b(((b(((b((f.fVar71) <= (3.0))) != 0) || ((b((f.fVar25) <= (8.5))) != 0))) != 0) || ((b((4.0) <= (f.fVar71))) != 0))) != 0) || ((b(((b((f.fVar47) <= (0.5))) != 0) || ((b((DAT_0012f1f8) <= (f.dVar49))) != 0))) != 0))) != 0) 1936 else 1933
        }
        1938 -> {
            f.fVar67 = f32(0.45)
            1932
        }
        1939 -> {
            324
        }
        1940 -> {
            f.fVar67 = f32(0.45)
            1939
        }
        1941 -> {
            f.fVar62 = f32(4.7)
            1940
        }
        1942 -> {
            f.fVar62 = f32(4.2)
            1940
        }
        1943 -> {
            if ((b(((b((f.dVar38) <= (DAT_0012f040))) != 0) || ((b((f.fVar69) <= (DAT_0012f2b0))) != 0))) != 0) 1941 else 1942
        }
        1944 -> {
            if ((b(((b(((b((f.dVar45) <= (0.3))) != 0) || ((b((-(0.3)) <= (f.dVar49))) != 0))) != 0) && ((b(((b((f.dVar45) <= (DAT_0012f200))) != 0) || ((b((f.fVar64) <= (f.fVar44))) != 0))) != 0))) != 0) 1943 else 1938
        }
        1945 -> {
            f.fVar67 = f32(0.5)
            1932
        }
        1946 -> {
            if ((b(((b(((b((f.fVar47) <= (0.5))) != 0) && ((b(((b((DAT_0012f050) <= (f.dVar75))) != 0) || ((b((f.dVar38) <= (DAT_0012f040))) != 0))) != 0))) != 0) || ((b((f.dVar36) <= (DAT_0012f160))) != 0))) != 0) 1944 else 1945
        }
        1947 -> {
            258
        }
        1948 -> {
            f.fVar67 = f32(1.0)
            1947
        }
        1949 -> {
            if ((b(((b(((b(((b((f.fVar80) < (4.5))) != 0) && ((b((1.0) < (f.fVar35))) != 0))) != 0) || ((b(((b(((b((f.dVar29) < (DAT_0012f098))) != 0) && ((b((0.7) < (f.fVar35))) != 0))) != 0) || ((b(((b((f.dVar29) < (DAT_0012f048))) != 0) && ((b((DAT_0012f010) < (f.fVar35))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar75) < (DAT_0012f060))) != 0) && ((b(((b((8.2) < (f.dVar78))) != 0) && ((b((0.35) < (f.dVar38))) != 0))) != 0))) != 0))) != 0) 1948 else 1946
        }
        1950 -> {
            if ((b(((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((f.fVar25) <= (8.0))) != 0))) != 0) || ((b(((b(((b((f.fVar35) <= (0.5))) != 0) && ((b((f.fVar63) <= (7.0))) != 0))) != 0) && ((b((f.dVar36) <= (DAT_0012ef68))) != 0))) != 0))) != 0) 1937 else 1949
        }
        1951 -> {
            if ((b(((b(((b(((b((f.fVar25) <= (8.5))) != 0) || ((b((4.6) <= (f.dVar29))) != 0))) != 0) || ((b((f.fVar63) < (6.0))) != 0))) != 0) || ((b(((b((3.9) <= (f.dVar75))) != 0) || ((b((f.fVar31) <= (84.0))) != 0))) != 0))) != 0) 1950 else 1930
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep61(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1952 -> {
            if ((b(((b(((b((0.6) <= (f.fVar32))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) || ((b(((b((DAT_0012ef70) <= (f.fVar67))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0))) != 0) 1926 else 1951
        }
        1953 -> {
            260
        }
        1954 -> {
            f.fVar81 = f32(3.2)
            1953
        }
        1955 -> {
            f.fVar67 = f32(0.45)
            1954
        }
        1956 -> {
            if ((b(((b(((b((f.dVar29) < (DAT_0012f030))) != 0) && ((b((f.fVar71) < (3.0))) != 0))) != 0) && ((b((6.5) < (f.fVar93))) != 0))) != 0) 1955 else 1952
        }
        1957 -> {
            if ((b(((b(((b((f.fVar71) <= (4.0))) != 0) || ((b((f.fVar43) <= (0.3))) != 0))) != 0) || ((b(((b((-(0.7)) <= (f.fVar90))) != 0) || ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((-(0.7)) <= (f.fVar73)) }) != 0))) != 0))) != 0) 1956 else 1827
        }
        1958 -> {
            260
        }
        1959 -> {
            f.fVar81 = f32(3.5)
            1958
        }
        1960 -> {
            f.fVar67 = f32(0.65)
            258
        }
        1961 -> {
            if ((b(((b((f.fVar72) < (9.0))) != 0) && ((b((60.0) < (f.fVar48))) != 0))) != 0) 1960 else 1827
        }
        1962 -> {
            f.fVar66 = f32(0.0)
            1961
        }
        1963 -> {
            if ((b(((b((f.dVar75) <= (DAT_0012f048))) != 0) || ((b(((b(((b((f.fVar43) <= (1.2))) != 0) && ((b((f.fVar90) <= (1.2))) != 0))) != 0) && ((b((f.fVar73) <= (1.2))) != 0))) != 0))) != 0) 1957 else 1962
        }
        1964 -> {
            if ((b(((b((f.dVar75) <= (DAT_0012f060))) != 0) || ((b(((b(((b(((b((f.fVar48) <= (54.0))) != 0) || ((b((f.dVar89) <= (3.3))) != 0))) != 0) || ((b((f.dVar51) <= (3.3))) != 0))) != 0) || ((b((f.dVar82) <= (3.3))) != 0))) != 0))) != 0) 1963 else 1826
        }
        1965 -> {
            f.fVar67 = f32(0.55)
            259
        }
        1966 -> {
            f.fVar81 = f32(4.7)
            1965
        }
        1967 -> {
            if ((b(((b(((b(((b((f.dVar75) < (DAT_0012f078))) != 0) && ((b((3.9) < (f.dVar89))) != 0))) != 0) && ((b((3.9) < (f.dVar51))) != 0))) != 0) && ((b((3.9) < (f.dVar82))) != 0))) != 0) 1966 else 1965
        }
        1968 -> {
            260
        }
        1969 -> {
            f.fVar81 = f32(3.0)
            1968
        }
        1970 -> {
            f.fVar67 = f32(0.85)
            1969
        }
        1971 -> {
            if ((b((f.fVar71) < (2.5))) != 0) 1970 else 1967
        }
        1972 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x24c))) < (1))) != 0) || ((b((((f.param_14) - (readI32(f.param_1.plus(0x24c))))) < (0x241))) != 0))) != 0) || ((b(((b((DAT_0012f058) <= (f.fVar43))) != 0) || ((b(((b((DAT_0012f058) <= (f.fVar90))) != 0) || ((b((DAT_0012f058) <= (f.fVar73))) != 0))) != 0))) != 0))) != 0) 1964 else 1971
        }
        1973 -> {
            264
        }
        1974 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((b(((b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (3.0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((0.5) <= (f32((f32((readF32(f.param_1.plus(0x254))) - (readF32(f.param_1.plus(0x1cc))))) - (f32((readF32(f.param_1.plus(0x1cc))) - (readF32(f.param_1.plus(600)))))))) }) != 0))) != 0))) != 0) 1973 else 1821
        }
        1975 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            1821
        }
        1976 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            1975
        }
        1977 -> {
            f.fVar66 = f32(f32(((((3.3) - (f.dVar75))) * (f.dVar28))))
            1976
        }
        1978 -> {
            f.dVar28 = DAT_0012ee70
            1977
        }
        1979 -> {
            if ((b((f.dVar75) <= (DAT_0012f050))) != 0) 1978 else 1977
        }
        1980 -> {
            f.dVar28 = 0.5
            1979
        }
        1981 -> {
            if ((b(((b(((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((f.dVar75) <= (3.3))) != 0))) != 0) || ((b((DAT_0012f098) <= (f.dVar75))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x26c))) <= (DAT_0012f218))) != 0) || ((b((((readF32(f.param_1.plus(0x26c))) - (kotlin.math.abs(readF32(f.param_1.plus(0x270)))))) <= (DAT_0012f1a8))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0) 1974 else 1980
        }
        1982 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.0, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            1981
        }
        1983 -> {
            f.fVar67 = f32(3.0)
            1982
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep62(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        1984 -> {
            if ((b(((b((DAT_0012f1c8) <= (f.dVar45))) != 0) || ((run { run { f.fVar67 = f32(3.5); f32(3.5) }; b(((b((1.5) <= (f.fVar47))) != 0) && ((run { run { f.fVar67 = f32(3.5); f32(3.5) }; b((DAT_0012ef68) <= (f.dVar39)) }) != 0)) }) != 0))) != 0) 1983 else 1982
        }
        1985 -> {
            f.fVar67 = f32(3.0)
            1982
        }
        1986 -> {
            if ((b(((f.bVar13) != 0) || ((b((f.bVar16) != (f.bVar17))) != 0))) != 0) 1985 else 1982
        }
        1987 -> {
            f.fVar67 = f32(3.5)
            1986
        }
        1988 -> {
            f.bVar17 = b((0) != 0)
            1987
        }
        1989 -> {
            f.bVar13 = b((b((f.dVar75) == (DAT_0012f080))) != 0)
            1988
        }
        1990 -> {
            f.bVar16 = b((b((f.dVar75) < (DAT_0012f080))) != 0)
            1989
        }
        1991 -> {
            if ((b(((b(!((b((f.dVar75).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f080).isNaN())) != 0))) != 0))) != 0) 1990 else 1987
        }
        1992 -> {
            f.bVar17 = b((1) != 0)
            1991
        }
        1993 -> {
            f.bVar13 = b((0) != 0)
            1992
        }
        1994 -> {
            f.bVar16 = b((0) != 0)
            1993
        }
        1995 -> {
            if ((f.bVar12) != 0) 1994 else 1987
        }
        1996 -> {
            f.bVar17 = b((0) != 0)
            1995
        }
        1997 -> {
            f.bVar13 = b((1) != 0)
            1996
        }
        1998 -> {
            f.bVar16 = b((0) != 0)
            1997
        }
        1999 -> {
            f.bVar12 = b((b((f.dVar45) < (0.35))) != 0)
            1998
        }
        2000 -> {
            if ((b(((b((f.fVar69) < (DAT_0012f178))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(!((b((f.dVar45).isNaN())) != 0)) }) != 0))) != 0) 1999 else 1998
        }
        2001 -> {
            f.bVar12 = b((0) != 0)
            2000
        }
        2002 -> {
            if ((b(((b(((b((f.dVar28) <= (DAT_0012ee90))) != 0) && ((b((-(0.35)) <= (f.dVar49))) != 0))) != 0) || ((b((DAT_0012f080) <= (f.dVar75))) != 0))) != 0) 1984 else 2001
        }
        2003 -> {
            if ((b(((b((84.0) < (f.fVar31))) != 0) && ((b(((b((f.fVar63) < (8.0))) != 0) || ((run { run { f.fVar67 = f32(3.0); f32(3.0) }; b((f.fVar93) < (8.0)) }) != 0))) != 0))) != 0) 2002 else 1982
        }
        2004 -> {
            f.fVar67 = f32(3.0)
            2003
        }
        2005 -> {
            261
        }
        2006 -> {
            if ((b(((b((54.0) < (f.fVar48))) != 0) && ((b((f.dVar39) < (8.2))) != 0))) != 0) 2005 else 2004
        }
        2007 -> {
            259
        }
        2008 -> {
            f.fVar67 = f32(1.0)
            2007
        }
        2009 -> {
            f.fVar81 = f32(3.5)
            2008
        }
        2010 -> {
            if ((b(((b(((b(((b((f.fVar71) <= (6.0))) != 0) || ((b((DAT_0012ee80) <= (f.fVar43))) != 0))) != 0) || ((b((DAT_0012ee80) <= (f.fVar90))) != 0))) != 0) || ((b(((b((DAT_0012ee80) <= (f.fVar73))) != 0) || ((b((0.3) <= (f.fVar32))) != 0))) != 0))) != 0) 2009 else 2008
        }
        2011 -> {
            if ((b((54.0) < (f.fVar48))) != 0) 261 else 2004
        }
        2012 -> {
            if ((b((DAT_0012ef38) <= (f.dVar28))) != 0) 2006 else 2011
        }
        2013 -> {
            259
        }
        2014 -> {
            f.fVar67 = f32(1.0)
            2013
        }
        2015 -> {
            f.fVar81 = f32(2.5)
            2014
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep63(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2016 -> {
            f.fVar81 = f32(3.0)
            2014
        }
        2017 -> {
            if ((b((f.fVar71) <= (2.5))) != 0) 2015 else 2016
        }
        2018 -> {
            275
        }
        2019 -> {
            f.fVar62 = f32(3.2)
            2018
        }
        2020 -> {
            f.fVar67 = f32(1.0)
            2019
        }
        2021 -> {
            if ((b((f.fVar69) < (DAT_0012f178))) != 0) 2020 else 2017
        }
        2022 -> {
            f.fVar81 = f32(3.5)
            2014
        }
        2023 -> {
            f.fVar81 = f32(3.2)
            2014
        }
        2024 -> {
            if ((b((f.fVar63) <= (f.fVar93))) != 0) 2022 else 2023
        }
        2025 -> {
            if ((b(((b(((b((8.0) <= (f.fVar63))) != 0) || ((b((f.fVar31) <= (84.0))) != 0))) != 0) || ((b(((b((f.dVar75) <= (2.8))) != 0) || ((b((f.fVar48) <= (48.0))) != 0))) != 0))) != 0) 2021 else 2024
        }
        2026 -> {
            if ((b(((b(((b(((b((1.2) < (f.dVar28))) != 0) && ((b((DAT_0012ee88) < (f.dVar38))) != 0))) != 0) && ((b((f.dVar75) < (DAT_0012f080))) != 0))) != 0) && ((b((f.dVar29) < (4.6))) != 0))) != 0) 2025 else 2012
        }
        2027 -> {
            f.dVar28 = f.fVar35
            2026
        }
        2028 -> {
            260
        }
        2029 -> {
            f.fVar81 = f32(2.7)
            2028
        }
        2030 -> {
            f.fVar67 = f32(1.0)
            2029
        }
        2031 -> {
            if ((b(((b((f.dVar49) < (DAT_0012f2b8))) != 0) && ((b(((b((f.dVar75) < (DAT_0012f080))) != 0) && ((b((0.35) < (f.dVar45))) != 0))) != 0))) != 0) 2030 else 2027
        }
        2032 -> {
            f.fVar66 = f32(f.fVar61)
            1821
        }
        2033 -> {
            writeF32(f.pjVar4, f32(0.0))
            2032
        }
        2034 -> {
            352
        }
        2035 -> {
            if ((b((2.5) < (f32((f.fVar66) + (f.param_4))))) != 0) 2034 else 264
        }
        2036 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            263
        }
        2037 -> {
            264
        }
        2038 -> {
            if ((b(((b((f.iVar24) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) 2037 else 2036
        }
        2039 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            262
        }
        2040 -> {
            352
        }
        2041 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2040
        }
        2042 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2041
        }
        2043 -> {
            f.fVar66 = f32(f32(((((2.8) - (f.dVar75))) * (ARR_0012f2d8[b((3.0) < (f.fVar32))]))))
            2042
        }
        2044 -> {
            if ((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b((2.5) < (f.fVar32))) != 0))) != 0) && ((b(((b((f.fVar32) < (3.5))) != 0) && ((b(((b((DAT_0012ef70) < (f.fVar76))) != 0) && ((b((2.8) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0))) != 0) 2043 else 2039
        }
        2045 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2044
        }
        2046 -> {
            f.fVar32 = f32(f.fVar71)
            2045
        }
        2047 -> {
            f.fVar67 = f32(2.5)
            2046
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep64(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2048 -> {
            if ((b(((b(((b(((b(((b((9.0) <= (f.fVar93))) != 0) || ((b((f.dVar49) <= (DAT_0012f0d0))) != 0))) != 0) || ((b((f.fVar71) <= (2.0))) != 0))) != 0) || ((b((f.fVar31) <= (90.0))) != 0))) != 0) && ((run { run { f.fVar67 = f32(2.8); f32(2.8) }; b((f.fVar85) <= (2.0)) }) != 0))) != 0) 2047 else 2046
        }
        2049 -> {
            if ((b(((b(((b((f.fVar63) <= (8.0))) != 0) || ((b(((b((f.fVar47) <= (1.5))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) <= (8.0))) != 0))) != 0))) != 0) || ((b(((b((f.fVar93) <= (8.5))) != 0) || ((run { run { f.fVar67 = f32(3.0); f32(3.0) }; b(((b((3.0) <= (f.fVar71))) != 0) && ((b((f.fVar31) <= (108.0))) != 0)) }) != 0))) != 0))) != 0) 2031 else 2048
        }
        2050 -> {
            333
        }
        2051 -> {
            f.auVar50 = f32(f.param_5)
            2050
        }
        2052 -> {
            f.fVar81 = f32(4.2)
            260
        }
        2053 -> {
            f.fVar67 = f32(0.55)
            2052
        }
        2054 -> {
            if ((b(((b(((b((f.fVar62) < (1.0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (8.0))) != 0))) != 0) && ((b(((b((f.fVar67) < (1.0))) != 0) && ((b((60.0) <= (f.fVar48))) != 0))) != 0))) != 0) 2053 else 2049
        }
        2055 -> {
            if ((b((f.fVar72) <= (12.0))) != 0) 1972 else 2054
        }
        2056 -> {
            if ((b(((b((84.0) <= (f.fVar31))) != 0) && ((b((f.fVar31) <= (180.0))) != 0))) != 0) 2055 else 1820
        }
        2057 -> {
            f.fVar67 = f32(1.0)
            336
        }
        2058 -> {
            f.fVar77 = f32(3.5)
            2057
        }
        2059 -> {
            335
        }
        2060 -> {
            if ((b(((b((f.fVar48) <= (54.0))) != 0) || ((b((6.5) <= (f.fVar63))) != 0))) != 0) 2059 else 334
        }
        2061 -> {
            f.fVar77 = f32(3.0)
            2057
        }
        2062 -> {
            334
        }
        2063 -> {
            if ((b((54.0) < (f.fVar48))) != 0) 2062 else 335
        }
        2064 -> {
            if ((b((6.5) <= (f.fVar93))) != 0) 2060 else 2063
        }
        2065 -> {
            f.auVar53 = f32(f.param_5)
            2064
        }
        2066 -> {
            352
        }
        2067 -> {
            f.param_5 = f32(f.auVar50)
            2066
        }
        2068 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar81, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2067
        }
        2069 -> {
            f.auVar50 = f32(f.param_5)
            333
        }
        2070 -> {
            f.fVar81 = f32(4.7)
            2069
        }
        2071 -> {
            f.fVar67 = f32(0.4)
            2070
        }
        2072 -> {
            if ((b(((b(((b(((b((f.fVar84) < (1.2))) != 0) && ((b((f.fVar67) < (1.0))) != 0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b((0x3c) < (readI32(f.param_1.plus(0x21c))))) != 0))) != 0) 2071 else 2065
        }
        2073 -> {
            352
        }
        2074 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar32, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2073
        }
        2075 -> {
            f.fVar32 = f32(f.fVar71)
            332
        }
        2076 -> {
            f.fVar67 = f32(1.0)
            2075
        }
        2077 -> {
            352
        }
        2078 -> {
            316
        }
        2079 -> {
            f.fVar67 = f32(3.5)
            2078
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep65(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2080 -> {
            f.fVar32 = f32(1.0)
            2079
        }
        2081 -> {
            if ((b(((b((48.0) < (f.fVar48))) != 0) && ((b((f.dVar36) < (7.8))) != 0))) != 0) 2080 else 2077
        }
        2082 -> {
            f.fVar66 = f32(0.0)
            2081
        }
        2083 -> {
            if ((b(((b(((b((DAT_0012f058) <= (f.fVar43))) != 0) || ((b((DAT_0012f058) <= (f.fVar90))) != 0))) != 0) || ((b((DAT_0012f058) <= (f.fVar73))) != 0))) != 0) 2082 else 2076
        }
        2084 -> {
            f.fVar32 = f32(3.5)
            332
        }
        2085 -> {
            f.fVar67 = f32(1.0)
            2084
        }
        2086 -> {
            if ((b(((b((f.fVar48) <= (60.0))) != 0) || ((b(((b(((b((DAT_0012ef68) <= (f.dVar39))) != 0) && ((b((DAT_0012f178) <= (f32((f.fVar64) - (f.fVar44))))) != 0))) != 0) || ((b((0.85) <= (f.fVar67))) != 0))) != 0))) != 0) 2083 else 2085
        }
        2087 -> {
            317
        }
        2088 -> {
            314
        }
        2089 -> {
            f.bVar16 = b((b((f.dVar75) < (f.dVar28))) != 0)
            2088
        }
        2090 -> {
            f.bVar13 = b((b((f.dVar75) == (f.dVar28))) != 0)
            2089
        }
        2091 -> {
            f.bVar12 = b((b(((b((f.dVar75).isNaN())) != 0) || ((b((f.dVar28).isNaN())) != 0))) != 0)
            2090
        }
        2092 -> {
            f.dVar30 = ((2.3) - (f.dVar75))
            2091
        }
        2093 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2092
        }
        2094 -> {
            if ((b(((b(((b((2.3) < (f.dVar30))) != 0) && ((b((f.fVar71) < (3.5))) != 0))) != 0) && ((b(((b((f.fVar61) == (0.0))) != 0) && ((b(((b((2.3) < (readF32(f.param_1.plus(0x240))))) != 0) && ((b((2.3) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0))) != 0) 2093 else 2087
        }
        2095 -> {
            f.dVar29 = DAT_0012ee70
            2094
        }
        2096 -> {
            f.dVar28 = DAT_0012f050
            2095
        }
        2097 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2096
        }
        2098 -> {
            f.dVar30 = f.dVar75
            2097
        }
        2099 -> {
            if ((b(((b(((b((0.5) < (readF32(f.param_1.plus(0x1c4))))) != 0) && ((b((0.6) < (f.fVar67))) != 0))) != 0) && ((b((f.fVar48) < (72.0))) != 0))) != 0) 2098 else 2086
        }
        2100 -> {
            f.fVar32 = f32(4.2)
            332
        }
        2101 -> {
            f.fVar67 = f32(0.45)
            2100
        }
        2102 -> {
            if ((b(((b(((b(((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b((f.fVar84) <= (0.6))) != 0))) != 0) || ((b((DAT_0012ef70) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar80) <= (4.5))) != 0))) != 0) 2099 else 2101
        }
        2103 -> {
            302
        }
        2104 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2103
        }
        2105 -> {
            313
        }
        2106 -> {
            if ((b(((b((DAT_0012f030) < (f.dVar28))) != 0) && ((b(((b((f.dVar28) < (4.3))) != 0) && ((b((f.fVar61) == (0.0))) != 0))) != 0))) != 0) 2105 else 317
        }
        2107 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2106
        }
        2108 -> {
            if ((b(((b((f.fVar48) < (36.0))) != 0) && ((b((7.0) < (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) 2107 else 2102
        }
        2109 -> {
            f.fVar32 = f32(4.8)
            332
        }
        2110 -> {
            f.fVar67 = f32(0.4)
            2109
        }
        2111 -> {
            if ((b(((b(((b((1.2) <= (f.fVar84))) != 0) || ((b(((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b((0.6) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) 2108 else 2110
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep66(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2112 -> {
            f.fVar32 = f32(4.5)
            332
        }
        2113 -> {
            f.fVar67 = f32(0.4)
            2112
        }
        2114 -> {
            if ((b(((b(((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((f.fVar80) <= (4.5))) != 0))) != 0) || ((b((DAT_0012f010) <= (f.dVar38))) != 0))) != 0) || ((b(((b((f.fVar48) <= (72.0))) != 0) && ((b(((b((f.fVar71) <= (4.0))) != 0) || ((b((f.fVar48) <= (54.0))) != 0))) != 0))) != 0))) != 0) 2111 else 2113
        }
        2115 -> {
            352
        }
        2116 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2115
        }
        2117 -> {
            f.fVar67 = f32(f.fVar71)
            316
        }
        2118 -> {
            f.fVar32 = f32(0.4)
            2117
        }
        2119 -> {
            312
        }
        2120 -> {
            f.fVar67 = f32(3.5)
            2119
        }
        2121 -> {
            f.fVar32 = f32(0.8)
            2120
        }
        2122 -> {
            f.fVar67 = f32(4.5)
            2119
        }
        2123 -> {
            f.fVar32 = f32(0.4)
            2122
        }
        2124 -> {
            if ((b(((b(((b(((b((f.fVar71) < (4.5))) != 0) && ((b((f.fVar48) < (45.0))) != 0))) != 0) && ((b((10.0) < (f.fVar25))) != 0))) != 0) || ((b(((b((f.dVar75) < (DAT_0012f048))) != 0) && ((b((f.fVar48) < (42.0))) != 0))) != 0))) != 0) 2121 else 2123
        }
        2125 -> {
            if ((b(((b(((b((f.dVar36) <= (6.8))) != 0) || ((b(((b(((b((4.6) <= (f.dVar29))) != 0) || ((b((4.5) <= (f.fVar71))) != 0))) != 0) || ((b((f.fVar72) <= (8.5))) != 0))) != 0))) != 0) || ((b((f.fVar63) <= (7.0))) != 0))) != 0) 2124 else 2118
        }
        2126 -> {
            if ((b(((b((f.fVar80) <= (5.5))) != 0) || ((b(((b((f.fVar63) <= (6.0))) != 0) || ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((f.fVar93) <= (6.5)) }) != 0))) != 0))) != 0) 2125 else 2115
        }
        2127 -> {
            if ((b(((b((DAT_0012f060) < (f.dVar75))) != 0) && ((b(((b(((b((f.dVar45) < (0.3))) != 0) && ((b((-(0.3)) < (f.dVar49))) != 0))) != 0) || ((b(((b((f.dVar45) < (0.3))) != 0) && ((b(((b((DAT_0012f108) < (f.dVar49))) != 0) && ((b((f.fVar69) < (DAT_0012f178))) != 0))) != 0))) != 0))) != 0))) != 0) 2126 else 2114
        }
        2128 -> {
            352
        }
        2129 -> {
            f.fVar66 = f32(f32(((((5.0) - (f.param_6))) * (DAT_0012ef70))))
            2128
        }
        2130 -> {
            if ((b((f.fVar86) < (1.0))) != 0) 2129 else 2128
        }
        2131 -> {
            f.fVar66 = f32(0.0)
            2130
        }
        2132 -> {
            315
        }
        2133 -> {
            if ((b((0.5) <= (((f.param_6) + (-(5.0)))))) != 0) 2132 else 2131
        }
        2134 -> {
            286
        }
        2135 -> {
            f.dVar30 = ((DAT_0012f078) - (f.dVar30))
            2134
        }
        2136 -> {
            if ((b((((f.dVar30) + (DAT_0012f0a8))) < (0.5))) != 0) 2135 else 2128
        }
        2137 -> {
            f.fVar66 = f32(0.0)
            2136
        }
        2138 -> {
            f.fVar66 = f32(f32(((DAT_0012f078) - (f.dVar28))))
            2128
        }
        2139 -> {
            f.fVar66 = f32(f32(((((((DAT_0012f258) - (f.dVar28))) - (f.dVar30))) * (0.5))))
            2128
        }
        2140 -> {
            if ((b((0.5) <= (((f.dVar30) + (DAT_0012f0a8))))) != 0) 2138 else 2139
        }
        2141 -> {
            if ((b((0.5) <= (((f.dVar28) + (DAT_0012f0a8))))) != 0) 2137 else 2140
        }
        2142 -> {
            f.dVar28 = f.param_6
            2141
        }
        2143 -> {
            if ((b(((b((1.2) <= (f.fVar84))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) 2133 else 2142
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep67(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2144 -> {
            f.fVar66 = f32(0.0)
            2128
        }
        2145 -> {
            f.fVar66 = f32(((5.0) - (f.fVar42)))
            2128
        }
        2146 -> {
            f.fVar66 = f32(((((((10.0) - (f.fVar42))) - (f.param_6))) * (0.5)))
            2128
        }
        2147 -> {
            if ((b((DAT_0012ef70) <= (kotlin.math.abs(f32((f.param_6) - (f.fVar42)))))) != 0) 2145 else 2146
        }
        2148 -> {
            if ((b((((f.fVar42) + (-(5.0)))) < (0.5))) != 0) 2147 else 2128
        }
        2149 -> {
            315
        }
        2150 -> {
            if ((b((0.5) <= (((f.param_6) + (-(5.0)))))) != 0) 2149 else 2148
        }
        2151 -> {
            270
        }
        2152 -> {
            f.dVar28 = 4.8
            2151
        }
        2153 -> {
            if ((b((((f.dVar30) + (-(4.8)))) < (0.5))) != 0) 2152 else 2128
        }
        2154 -> {
            if ((b((9.0) <= (f.fVar72))) != 0) 2150 else 2153
        }
        2155 -> {
            f.fVar66 = f32(0.0)
            2154
        }
        2156 -> {
            if ((b(((b(((b((1.2) <= (f.fVar84))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar72) < (8.0))) != 0))) != 0) 315 else 2155
        }
        2157 -> {
            if ((b((7.0) <= (f.fVar42))) != 0) 2143 else 2156
        }
        2158 -> {
            312
        }
        2159 -> {
            f.fVar67 = f32(4.7)
            2158
        }
        2160 -> {
            f.fVar32 = f32(0.8)
            2159
        }
        2161 -> {
            317
        }
        2162 -> {
            352
        }
        2163 -> {
            f.fVar66 = f32(f32(((f.dVar30) * (f.dVar28))))
            2162
        }
        2164 -> {
            writeF32(f.param_1.plus(400), f32(f32(((f.dVar30) * (f.dVar28)))))
            2163
        }
        2165 -> {
            f.dVar28 = f.dVar29
            2164
        }
        2166 -> {
            if ((b(((f.bVar13) != 0) || ((b((f.bVar16) != (f.bVar12))) != 0))) != 0) 2165 else 2164
        }
        2167 -> {
            f.dVar28 = 0.5
            2166
        }
        2168 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            314
        }
        2169 -> {
            f.bVar16 = b((b((f.dVar28) < (DAT_0012f050))) != 0)
            2168
        }
        2170 -> {
            f.bVar13 = b((b((f.dVar28) == (DAT_0012f050))) != 0)
            2169
        }
        2171 -> {
            f.bVar12 = b((b(((b((f.dVar28).isNaN())) != 0) || ((b((DAT_0012f050).isNaN())) != 0))) != 0)
            2170
        }
        2172 -> {
            f.dVar30 = ((3.3) - (f.dVar28))
            2171
        }
        2173 -> {
            if ((b(((b((3.3) < (readF32(f.param_1.plus(0x240))))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 2172 else 2161
        }
        2174 -> {
            f.dVar29 = DAT_0012ee70
            2173
        }
        2175 -> {
            if ((b(((b((f.fVar61) == (0.0))) != 0) && ((b(((b((f.dVar28) < (4.3))) != 0) && ((b((DAT_0012f030) < (f.dVar28))) != 0))) != 0))) != 0) 313 else 2161
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep68(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2176 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2175
        }
        2177 -> {
            if ((b(((b((1.2) <= (f.fVar84))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) 2176 else 2160
        }
        2178 -> {
            if ((b((f.fVar42) < (6.0))) != 0) 2177 else 2157
        }
        2179 -> {
            if ((b((5.0) <= (f.fVar42))) != 0) 2178 else 2127
        }
        2180 -> {
            f.dVar28 = f.dVar75
            2179
        }
        2181 -> {
            352
        }
        2182 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2181
        }
        2183 -> {
            f.fVar67 = f32(4.2)
            312
        }
        2184 -> {
            f.fVar32 = f32(0.5)
            2183
        }
        2185 -> {
            f.fVar67 = f32(3.2)
            312
        }
        2186 -> {
            f.fVar32 = f32(1.1)
            2185
        }
        2187 -> {
            if ((b(((b(((b(((b((f.fVar72) <= (7.5))) != 0) || ((b((f.fVar25) <= (10.0))) != 0))) != 0) || ((b((3.0) <= (f.fVar71))) != 0))) != 0) || ((b((f.fVar93) <= (7.0))) != 0))) != 0) 2184 else 2186
        }
        2188 -> {
            324
        }
        2189 -> {
            f.fVar67 = f32(0.85)
            2188
        }
        2190 -> {
            f.fVar62 = f32(3.0)
            2189
        }
        2191 -> {
            311
        }
        2192 -> {
            if ((b(((b((3.0) <= (f.fVar71))) != 0) || ((b((f.dVar45) <= (DAT_0012f190))) != 0))) != 0) 2191 else 2190
        }
        2193 -> {
            f.fVar62 = f32(3.5)
            2189
        }
        2194 -> {
            324
        }
        2195 -> {
            f.fVar62 = f32(4.2)
            2194
        }
        2196 -> {
            f.fVar67 = f32(0.45)
            2195
        }
        2197 -> {
            if ((b(((b((3.5) <= (f.fVar80))) != 0) || ((b((f.fVar63) <= (6.5))) != 0))) != 0) 2196 else 311
        }
        2198 -> {
            if ((b(((b(((b((DAT_0012f048) <= (f.dVar29))) != 0) || ((b((5.0) <= (f.fVar79))) != 0))) != 0) || ((b((0.85) <= (f.dVar38))) != 0))) != 0) 2192 else 2197
        }
        2199 -> {
            312
        }
        2200 -> {
            f.fVar67 = f32(3.0)
            2199
        }
        2201 -> {
            f.fVar32 = f32(0.55)
            310
        }
        2202 -> {
            if ((b(((b((f.fVar80) < (4.0))) != 0) && ((b((1.0) < (f.fVar35))) != 0))) != 0) 2201 else 2198
        }
        2203 -> {
            352
        }
        2204 -> {
            303
        }
        2205 -> {
            f.bVar16 = b((b((f.dVar28) < (DAT_0012f068))) != 0)
            2204
        }
        2206 -> {
            f.bVar13 = b((b((f.dVar28) == (DAT_0012f068))) != 0)
            2205
        }
        2207 -> {
            f.bVar12 = b((b(((b((f.dVar28).isNaN())) != 0) || ((b((DAT_0012f068).isNaN())) != 0))) != 0)
            2206
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep69(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2208 -> {
            f.dVar28 = f32((f.fVar66) + (f.param_4))
            2207
        }
        2209 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2208
        }
        2210 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) 2209 else 2203
        }
        2211 -> {
            f.fVar66 = f32(f.fVar61)
            2210
        }
        2212 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2203
        }
        2213 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2212
        }
        2214 -> {
            f.fVar66 = f32(f32(((((2.3) - (f.dVar28))) * (f.dVar29))))
            2213
        }
        2215 -> {
            f.dVar29 = DAT_0012ee70
            2214
        }
        2216 -> {
            if ((b((f.dVar28) <= (DAT_0012f068))) != 0) 2215 else 2214
        }
        2217 -> {
            f.dVar29 = 0.5
            2216
        }
        2218 -> {
            if ((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((3.8) <= (f.dVar28))) != 0))) != 0) || ((b(((b((f.dVar28) <= (2.3))) != 0) || ((b(((b((readF32(f.param_1.plus(0x240))) <= (2.3))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (2.3))) != 0))) != 0))) != 0))) != 0) 2211 else 2217
        }
        2219 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.3, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2218
        }
        2220 -> {
            f.dVar28 = f.dVar75
            2219
        }
        2221 -> {
            312
        }
        2222 -> {
            f.fVar67 = f32(3.5)
            2221
        }
        2223 -> {
            f.fVar32 = f32(0.85)
            2222
        }
        2224 -> {
            310
        }
        2225 -> {
            f.fVar32 = f32(1.2)
            2224
        }
        2226 -> {
            if ((b(((b((f.dVar29) < (3.9))) != 0) && ((b((7.0) < (f.fVar63))) != 0))) != 0) 2225 else 2223
        }
        2227 -> {
            332
        }
        2228 -> {
            f.fVar32 = f32(3.0)
            2227
        }
        2229 -> {
            f.fVar67 = f32(0.65)
            2228
        }
        2230 -> {
            if ((b(((b((f.dVar49) <= (-(0.3)))) != 0) && ((b(((b((DAT_0012f140) <= (f.dVar39))) != 0) || ((b((0.3) <= (f.dVar45))) != 0))) != 0))) != 0) 2229 else 2226
        }
        2231 -> {
            f.fVar32 = f32(1.3)
            2221
        }
        2232 -> {
            f.fVar67 = f32(3.5)
            2231
        }
        2233 -> {
            f.fVar67 = f32(3.0)
            2231
        }
        2234 -> {
            if ((b(((b((3.9) <= (f.dVar29))) != 0) || ((b((f.fVar63) <= (7.0))) != 0))) != 0) 2232 else 2233
        }
        2235 -> {
            f.fVar67 = f32(3.9)
            2231
        }
        2236 -> {
            if ((b(((b(((b((0.3) <= (f.dVar45))) != 0) || ((b((f.dVar49) <= (-(0.3)))) != 0))) != 0) || ((b((DAT_0012ef88) <= (f.fVar69))) != 0))) != 0) 2234 else 2235
        }
        2237 -> {
            if ((b(((b(((b((8.2) <= (f.dVar36))) != 0) || ((b((f.fVar48) <= (48.0))) != 0))) != 0) || ((b((8.5) <= (f.fVar63))) != 0))) != 0) 2230 else 2236
        }
        2238 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) || ((b((f.fVar72) <= (11.0))) != 0))) != 0) || ((b((f.fVar63) <= (8.0))) != 0))) != 0) 2237 else 2220
        }
        2239 -> {
            if ((b(((b(((b((9.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b(((b(((b((1.0) <= (f.fVar67))) != 0) && ((b((f.fVar48) <= (56.0))) != 0))) != 0) || ((b((8.0) <= (f.fVar63))) != 0))) != 0))) != 0) || ((b(((b(((b((1.0) <= (f.fVar32))) != 0) || ((b((f.fVar48) <= (36.0))) != 0))) != 0) || ((b((8.0) <= (f.fVar93))) != 0))) != 0))) != 0) 2238 else 2202
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep70(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2240 -> {
            332
        }
        2241 -> {
            f.fVar32 = f32(3.9)
            2240
        }
        2242 -> {
            f.fVar67 = f32(0.95)
            2241
        }
        2243 -> {
            if ((b(((b(((b(((b((f.fVar32) < (DAT_0012ef38))) != 0) && ((b(((b((f.dVar38) < (DAT_0012ef70))) != 0) && ((b((f.fVar47) < (1.0))) != 0))) != 0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b((48.0) < (f.fVar48))) != 0))) != 0) 2242 else 2239
        }
        2244 -> {
            if ((b(((b(((b(((b((DAT_0012f010) <= (f.fVar67))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) || ((b((f.fVar48) <= (54.0))) != 0))) != 0) || ((b(((b((0.25) <= (f.fVar32))) != 0) && ((b((DAT_0012f160) <= (f.dVar39))) != 0))) != 0))) != 0) 2243 else 2187
        }
        2245 -> {
            f.fVar67 = f32(4.2)
            312
        }
        2246 -> {
            f.fVar32 = f32(0.45)
            2245
        }
        2247 -> {
            f.fVar67 = f32(3.2)
            312
        }
        2248 -> {
            f.fVar32 = f32(0.55)
            2247
        }
        2249 -> {
            if ((b(((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) || ((b((DAT_0012f048) <= (f.dVar29))) != 0))) != 0) || ((b(((b((f.fVar93) <= (7.0))) != 0) || ((b(((b((f.fVar72) <= (8.0))) != 0) || ((b((f.dVar38) <= (0.6))) != 0))) != 0))) != 0))) != 0) 2246 else 2248
        }
        2250 -> {
            332
        }
        2251 -> {
            f.fVar32 = f32(3.5)
            2250
        }
        2252 -> {
            f.fVar67 = f32(0.45)
            2251
        }
        2253 -> {
            if ((b(((b(((b((f.dVar29) < (3.3))) != 0) && ((b((6.0) < (f.fVar93))) != 0))) != 0) && ((b((42.0) < (f.fVar31))) != 0))) != 0) 2252 else 2249
        }
        2254 -> {
            if ((b(((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((f.fVar48) <= (72.0))) != 0))) != 0) || ((b((60.0) <= (f.fVar31))) != 0))) != 0) 2244 else 2253
        }
        2255 -> {
            324
        }
        2256 -> {
            f.fVar62 = f32(3.0)
            2255
        }
        2257 -> {
            f.fVar67 = f32(0.5)
            2256
        }
        2258 -> {
            324
        }
        2259 -> {
            f.fVar62 = f32(3.5)
            2258
        }
        2260 -> {
            f.fVar67 = f32(0.8)
            308
        }
        2261 -> {
            324
        }
        2262 -> {
            f.fVar62 = f32(4.3)
            2261
        }
        2263 -> {
            f.fVar67 = f32(0.8)
            306
        }
        2264 -> {
            if ((b(((b(((b((60.0) <= (f.fVar48))) != 0) || ((b((3.9) <= (f.dVar75))) != 0))) != 0) || ((b((f.fVar25) <= (11.0))) != 0))) != 0) 2263 else 2260
        }
        2265 -> {
            324
        }
        2266 -> {
            f.fVar62 = f32(4.7)
            2265
        }
        2267 -> {
            f.fVar67 = f32(0.8)
            2266
        }
        2268 -> {
            if ((b(((b(((b((f.dVar45) < (0.3))) != 0) && ((b((-(0.3)) < (f.dVar49))) != 0))) != 0) || ((b(((b(((b(((b((f.fVar25) < (10.0))) != 0) && ((b((f.fVar93) < (7.0))) != 0))) != 0) && ((b((f.dVar45) < (0.3))) != 0))) != 0) && ((b((f.fVar69) < (DAT_0012f178))) != 0))) != 0))) != 0) 2267 else 2264
        }
        2269 -> {
            307
        }
        2270 -> {
            if ((b(((b((7.0) < (f.fVar72))) != 0) && ((b((9.5) < (f.fVar25))) != 0))) != 0) 2269 else 305
        }
        2271 -> {
            f.fVar67 = f32(1.0)
            308
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep71(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2272 -> {
            306
        }
        2273 -> {
            f.fVar67 = f32(0.45)
            2272
        }
        2274 -> {
            308
        }
        2275 -> {
            f.fVar67 = f32(0.55)
            2274
        }
        2276 -> {
            if ((b(((b((f.dVar29) < (4.1))) != 0) && ((b(((b((f.fVar48) < (72.0))) != 0) && ((b((7.0) < (f.fVar93))) != 0))) != 0))) != 0) 2275 else 2273
        }
        2277 -> {
            if ((b(((b(((b((f.fVar25) <= (9.5))) != 0) || ((b((3.5) <= (f.fVar71))) != 0))) != 0) || ((b(((b((f.fVar93) <= (7.0))) != 0) || ((b(((b((3.0) <= (f.fVar71))) != 0) && ((b((f.fVar47) <= (0.75))) != 0))) != 0))) != 0))) != 0) 2276 else 2271
        }
        2278 -> {
            305
        }
        2279 -> {
            if ((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) && ((b((f.dVar38) <= (DAT_0012f010))) != 0))) != 0) 2278 else 2277
        }
        2280 -> {
            305
        }
        2281 -> {
            if ((b((f.fVar72) <= (7.0))) != 0) 2280 else 307
        }
        2282 -> {
            if ((b((f.fVar63) <= (7.0))) != 0) 2270 else 2281
        }
        2283 -> {
            352
        }
        2284 -> {
            if ((b(((b(((b(((b((8.0) < (f.fVar72))) != 0) && ((b((72.0) < (f.fVar48))) != 0))) != 0) && ((b((3.0) < (f.fVar37))) != 0))) != 0) && ((run { run { f.fVar66 = f32(0.0); f32(0.0) }; b((f.dVar39) < (DAT_0012ef68)) }) != 0))) != 0) 2283 else 2282
        }
        2285 -> {
            308
        }
        2286 -> {
            f.fVar67 = f32(0.65)
            2285
        }
        2287 -> {
            309
        }
        2288 -> {
            f.fVar67 = f32(0.5)
            2287
        }
        2289 -> {
            if ((b(((b(((b((-(0.3)) < (f.dVar49))) != 0) || ((b((readI32(f.param_1.plus(0x278))) == (1))) != 0))) != 0) && ((b(((b((3.9) < (f.fVar70))) != 0) || ((b(((b((f.dVar45) < (0.3))) != 0) && ((b((f.fVar69) < (DAT_0012f1e0))) != 0))) != 0))) != 0))) != 0) 2288 else 2286
        }
        2290 -> {
            if ((b(((b((f.fVar80) < (5.0))) != 0) && ((b(((b(((b(((b((0.5) < (f.fVar35))) != 0) && ((b((DAT_0012eef8) < (f.dVar28))) != 0))) != 0) && ((b((2.8) < (f.dVar75))) != 0))) != 0) && ((b(((b((DAT_0012ef60) < (f.dVar38))) != 0) && ((b((6.8) < (f.fVar72))) != 0))) != 0))) != 0))) != 0) 2289 else 2284
        }
        2291 -> {
            if ((b(((b(((b((3.5) <= (f.fVar80))) != 0) || ((b((f.fVar35) <= (DAT_0012ef38))) != 0))) != 0) || ((b(((b((f.dVar38) <= (DAT_0012ef60))) != 0) || ((b(((b((f.fVar72) <= (6.8))) != 0) || ((b((f.fVar93) <= (6.0))) != 0))) != 0))) != 0))) != 0) 2290 else 2257
        }
        2292 -> {
            308
        }
        2293 -> {
            f.fVar67 = f32(1.2)
            2292
        }
        2294 -> {
            324
        }
        2295 -> {
            f.fVar62 = f32(4.2)
            2294
        }
        2296 -> {
            f.fVar67 = f32(0.6)
            309
        }
        2297 -> {
            if ((b(((b(((b((f.fVar64) < (f.fVar44))) != 0) && ((b((f.dVar45) < (0.3))) != 0))) != 0) && ((b(((b((f.dVar28) < (0.35))) != 0) && ((b((-(0.3)) < (f.dVar49))) != 0))) != 0))) != 0) 2296 else 2293
        }
        2298 -> {
            if ((b(((b(((b(((b((3.5) <= (f.fVar80))) != 0) || ((b((f.fVar72) <= (8.5))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012ef60))) != 0))) != 0) || ((b(((b((3.0) <= (f.fVar71))) != 0) && ((b((f.dVar38) <= (0.85))) != 0))) != 0))) != 0) 2297 else 2257
        }
        2299 -> {
            if ((b(((b(((b((f.dVar78) <= (DAT_0012f140))) != 0) || ((b(((b((f.fVar63) <= (7.0))) != 0) && ((b((f.dVar38) <= (0.7))) != 0))) != 0))) != 0) || ((b((DAT_0012f050) <= (f.dVar75))) != 0))) != 0) 2291 else 2298
        }
        2300 -> {
            312
        }
        2301 -> {
            f.fVar67 = f32(3.5)
            2300
        }
        2302 -> {
            f.fVar32 = f32(1.2)
            2301
        }
        2303 -> {
            if ((b(((b(((b((6.0) < (f.fVar63))) != 0) && ((b(((b((f.fVar71) < (3.5))) != 0) && ((b((f.dVar29) < (3.9))) != 0))) != 0))) != 0) && ((b(((b((8.5) < (f.fVar25))) != 0) && ((b(((b((6.0) < (f.fVar93))) != 0) && ((b((DAT_0012f010) < (f.dVar38))) != 0))) != 0))) != 0))) != 0) 2302 else 2299
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep72(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2304 -> {
            if ((b(((b(((b(((b((f.fVar67) < (DAT_0012ef70))) != 0) && ((run { run { f.dVar28 = f.fVar32; f.fVar32 }; b((f.dVar28) < (0.6)) }) != 0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b(((b((60.0) < (f.fVar48))) != 0) || ((b(((b((9.0) < (f.fVar25))) != 0) && ((b((readF32(f.param_1.plus(0x1c4))) == (0.0))) != 0))) != 0))) != 0))) != 0) 2303 else 2254
        }
        2305 -> {
            312
        }
        2306 -> {
            f.fVar32 = f32(0.45)
            2305
        }
        2307 -> {
            f.fVar67 = f32(3.2)
            2306
        }
        2308 -> {
            f.fVar67 = f32(2.8)
            2306
        }
        2309 -> {
            if ((b((f.fVar25) <= (11.0))) != 0) 2307 else 2308
        }
        2310 -> {
            if ((b(((b(((b((f.dVar29) < (DAT_0012f030))) != 0) && ((b((f.fVar48) < (84.0))) != 0))) != 0) && ((b(((b((f.fVar71) < (3.0))) != 0) && ((b((6.0) < (f.fVar93))) != 0))) != 0))) != 0) 2309 else 2304
        }
        2311 -> {
            304
        }
        2312 -> {
            f.fVar67 = f32(0.65)
            2311
        }
        2313 -> {
            if ((b(((b(((b((f.dVar29) < (3.9))) != 0) && ((b((8.0) < (f.fVar25))) != 0))) != 0) && ((b(((b((DAT_0012ef70) < (f.dVar38))) != 0) && ((b((6.0) < (f.fVar93))) != 0))) != 0))) != 0) 2312 else 2310
        }
        2314 -> {
            if ((b((f.fVar42) < (4.0))) != 0) 2313 else 2180
        }
        2315 -> {
            f.fVar32 = f32(3.5)
            332
        }
        2316 -> {
            f.fVar67 = f32(0.75)
            331
        }
        2317 -> {
            325
        }
        2318 -> {
            if ((b(((b(((b((DAT_0012f060) <= (f.dVar75))) != 0) || ((b((f.dVar78) <= (8.2))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012ef38))) != 0))) != 0) 2317 else 2316
        }
        2319 -> {
            312
        }
        2320 -> {
            f.fVar67 = f32(4.7)
            2319
        }
        2321 -> {
            f.fVar32 = f32(0.4)
            2320
        }
        2322 -> {
            324
        }
        2323 -> {
            f.fVar62 = f32(4.2)
            2322
        }
        2324 -> {
            f.fVar67 = f32(0.45)
            329
        }
        2325 -> {
            322
        }
        2326 -> {
            f.fVar62 = f32(4.5)
            2325
        }
        2327 -> {
            if ((b(((b(((b((3.9) < (f.dVar75))) != 0) && ((b((DAT_0012f098) < (f.dVar29))) != 0))) != 0) && ((b(((b((f.dVar45) < (0.3))) != 0) && ((b((f.fVar69) < (DAT_0012f1a0))) != 0))) != 0))) != 0) 327 else 328
        }
        2328 -> {
            if ((b(((b(((b((0.25) < (f.fVar32))) != 0) || ((b(((b((f.fVar32) < (f.fVar67))) != 0) && ((b((8.2) < (f.dVar78))) != 0))) != 0))) != 0) || ((b(((b((9.0) < (f.fVar25))) != 0) && ((b((DAT_0012ef68) < (f.dVar39))) != 0))) != 0))) != 0) 2327 else 2321
        }
        2329 -> {
            if ((b((f.dVar75) < (4.3))) != 0) 2328 else 2321
        }
        2330 -> {
            326
        }
        2331 -> {
            if ((b(((b(((b((f.dVar30) < (4.1))) != 0) && ((b((f.fVar71) < (3.75))) != 0))) != 0) && ((b((7.5) < (f.fVar93))) != 0))) != 0) 2330 else 2329
        }
        2332 -> {
            331
        }
        2333 -> {
            f.fVar67 = f32(1.0)
            2332
        }
        2334 -> {
            if ((b((f.dVar30) < (4.1))) != 0) 326 else 2329
        }
        2335 -> {
            if ((b((DAT_0012f050) <= (f.dVar75))) != 0) 2331 else 2334
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep73(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2336 -> {
            if ((b(((b((8.5) < (f.fVar25))) != 0) && ((b(((b((7.0) <= (f.fVar63))) != 0) || ((b(((b((DAT_0012f010) < (f.dVar38))) != 0) && ((b((6.0) < (f.fVar63))) != 0))) != 0))) != 0))) != 0) 2335 else 2329
        }
        2337 -> {
            312
        }
        2338 -> {
            f.fVar67 = f32(4.2)
            2337
        }
        2339 -> {
            f.fVar32 = f32(0.5)
            2338
        }
        2340 -> {
            f.fVar67 = f32(4.7)
            2337
        }
        2341 -> {
            f.fVar32 = f32(0.4)
            2340
        }
        2342 -> {
            if ((b(((b((6.5) < (f.fVar63))) != 0) || ((b((6.5) < (f.fVar93))) != 0))) != 0) 2339 else 2341
        }
        2343 -> {
            332
        }
        2344 -> {
            f.fVar32 = f32(3.6)
            2343
        }
        2345 -> {
            f.fVar67 = f32(0.4)
            2344
        }
        2346 -> {
            if ((b(((b((f.fVar71) < (3.5))) != 0) && ((b(((b((DAT_0012ef88) < (f32((f.fVar64) - (f.fVar44))))) != 0) && ((b((f.dVar29) < (DAT_0012f048))) != 0))) != 0))) != 0) 2345 else 2342
        }
        2347 -> {
            f.fVar67 = f32(4.2)
            2337
        }
        2348 -> {
            f.fVar32 = f32(0.45)
            2347
        }
        2349 -> {
            f.fVar67 = f32(3.5)
            2337
        }
        2350 -> {
            f.fVar32 = f32(0.75)
            2349
        }
        2351 -> {
            if ((b(((b((3.5) <= (f.fVar71))) != 0) || ((b((f.dVar38) <= (0.85))) != 0))) != 0) 2348 else 2350
        }
        2352 -> {
            if ((b(((b(((b((f.fVar25) <= (8.0))) != 0) || ((b(((b(((b((78.0) <= (f.fVar48))) != 0) || ((b((f.dVar38) <= (DAT_0012ef38))) != 0))) != 0) && ((b(((b((f.dVar38) <= (DAT_0012ef60))) != 0) || ((b((f.fVar63) <= (6.5))) != 0))) != 0))) != 0))) != 0) || ((b(((b((f.fVar67) <= (f.fVar32))) != 0) && ((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) || ((b((f.dVar38) <= (DAT_0012f010))) != 0))) != 0))) != 0))) != 0) 2346 else 2351
        }
        2353 -> {
            if ((b(((b((1.5) <= (f.fVar32))) != 0) || ((b(((b(((b(((b((90.0) <= (f.fVar48))) != 0) && ((b(((b((f.fVar47) <= (0.5))) != 0) || ((b((f.fVar63) <= (7.0))) != 0))) != 0))) != 0) && ((b(((b((f.fVar93) <= (7.0))) != 0) || ((b((f.fVar63) <= (7.0))) != 0))) != 0))) != 0) || ((b(((b(((b((f.dVar78) <= (8.2))) != 0) || ((b((f.dVar39) <= (DAT_0012f160))) != 0))) != 0) && ((b(((b((f.fVar25) <= (8.5))) != 0) || ((b((f.dVar38) <= (DAT_0012f040))) != 0))) != 0))) != 0))) != 0))) != 0) 2352 else 2336
        }
        2354 -> {
            330
        }
        2355 -> {
            if ((b(((b(((b((f.dVar29) < (DAT_0012f098))) != 0) && ((b((0.7) < (f.dVar51))) != 0))) != 0) || ((b(((b((f.dVar29) < (DAT_0012f048))) != 0) && ((b((DAT_0012f010) < (f.dVar51))) != 0))) != 0))) != 0) 2354 else 325
        }
        2356 -> {
            if ((b(((b((4.5) <= (f.fVar80))) != 0) || ((b((f.fVar35) <= (1.0))) != 0))) != 0) 2355 else 330
        }
        2357 -> {
            352
        }
        2358 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar62, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2357
        }
        2359 -> {
            f.fVar67 = f32(0.45)
            324
        }
        2360 -> {
            f.fVar62 = f32(4.7)
            322
        }
        2361 -> {
            328
        }
        2362 -> {
            if ((b(((b((f.fVar71) < (4.5))) != 0) && ((b((9.5) < (f.fVar25))) != 0))) != 0) 2361 else 2360
        }
        2363 -> {
            319
        }
        2364 -> {
            if ((b((DAT_0012f010) <= (f.dVar38))) != 0) 2363 else 321
        }
        2365 -> {
            319
        }
        2366 -> {
            321
        }
        2367 -> {
            if ((b(((b((f.dVar38) < (DAT_0012f010))) != 0) && ((b((f32((f.fVar64) - (f.fVar44))) < (DAT_0012f178))) != 0))) != 0) 2366 else 2365
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep74(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2368 -> {
            if ((b((f.dVar49) <= (-(0.3)))) != 0) 2367 else 2364
        }
        2369 -> {
            329
        }
        2370 -> {
            f.fVar67 = f32(0.65)
            2369
        }
        2371 -> {
            320
        }
        2372 -> {
            f.fVar67 = f32(0.85)
            2371
        }
        2373 -> {
            if ((b(((b(((b((f.fVar71) < (4.0))) != 0) && ((b((0.5) < (f.fVar47))) != 0))) != 0) && ((b(((b((6.0) < (f.fVar63))) != 0) && ((b(((b((1.0) < (f.fVar35))) != 0) || ((b((f.dVar49) < (-(0.3)))) != 0))) != 0))) != 0))) != 0) 2372 else 2370
        }
        2374 -> {
            327
        }
        2375 -> {
            if ((b(((b(((b((3.8) < (f.dVar75))) != 0) && ((b((f.dVar45) < (0.3))) != 0))) != 0) && ((b(((b((DAT_0012f108) < (f.dVar49))) != 0) && ((b((f.fVar69) < (DAT_0012f1a0))) != 0))) != 0))) != 0) 2374 else 2373
        }
        2376 -> {
            324
        }
        2377 -> {
            f.fVar62 = f32(3.5)
            2376
        }
        2378 -> {
            f.fVar67 = f32(0.45)
            320
        }
        2379 -> {
            if ((b(((b(((b((6.0) < (f.fVar63))) != 0) && ((b((f.dVar75) < (DAT_0012f050))) != 0))) != 0) && ((b((DAT_0012ef88) < (f32((f.fVar64) - (f.fVar44))))) != 0))) != 0) 2378 else 2375
        }
        2380 -> {
            if ((b(((b((f.dVar29) <= (4.6))) != 0) || ((b((0.3) <= (f.dVar45))) != 0))) != 0) 319 else 2368
        }
        2381 -> {
            312
        }
        2382 -> {
            f.fVar67 = f32(4.8)
            2381
        }
        2383 -> {
            f.fVar32 = f32(0.45)
            2382
        }
        2384 -> {
            324
        }
        2385 -> {
            f.fVar62 = f32(4.2)
            2384
        }
        2386 -> {
            f.fVar67 = f32(0.55)
            318
        }
        2387 -> {
            324
        }
        2388 -> {
            f.fVar62 = f32(4.7)
            2387
        }
        2389 -> {
            f.fVar67 = f32(0.45)
            2388
        }
        2390 -> {
            if ((b(((b(((b(((b((f.fVar47) <= (0.5))) != 0) || ((b((f.fVar69) <= (DAT_0012f2b0))) != 0))) != 0) && ((b(((b(((b((f.dVar78) <= (7.8))) != 0) || ((b((4.3) <= (f.dVar29))) != 0))) != 0) || ((b((f.dVar36) <= (DAT_0012f160))) != 0))) != 0))) != 0) && ((b(((b(((b((DAT_0012f078) <= (f.dVar75))) != 0) || ((b(((b((f.fVar47) <= (0.25))) != 0) && ((b((f.fVar25) <= (8.5))) != 0))) != 0))) != 0) || ((b(((b((f.fVar63) <= (6.0))) != 0) && ((b((f.fVar93) <= (6.5))) != 0))) != 0))) != 0))) != 0) 2389 else 2386
        }
        2391 -> {
            if ((b(((b((f.fVar37) <= (3.9))) != 0) || ((b(((b((f.fVar40) <= (3.9))) != 0) || ((b((f.fVar61) <= (3.9))) != 0))) != 0))) != 0) 2390 else 2383
        }
        2392 -> {
            f.fVar67 = f32(4.2)
            2381
        }
        2393 -> {
            f.fVar32 = f32(0.55)
            2392
        }
        2394 -> {
            f.fVar67 = f32(4.5)
            2381
        }
        2395 -> {
            f.fVar32 = f32(0.45)
            2394
        }
        2396 -> {
            if ((b(((b(((b((f.dVar75) <= (DAT_0012f048))) != 0) || ((b((f.dVar29) <= (4.8))) != 0))) != 0) || ((b((f.dVar49) <= (DAT_0012f1f8))) != 0))) != 0) 2393 else 2395
        }
        2397 -> {
            if ((b(((b(((b((-(0.3)) <= (f.dVar49))) != 0) && ((b((f.dVar45) <= (0.3))) != 0))) != 0) || ((b((4.5) <= (f.fVar71))) != 0))) != 0) 2391 else 2396
        }
        2398 -> {
            if ((b(((b(((b((f.dVar51) <= (DAT_0012ef38))) != 0) || ((b((f.dVar38) <= (DAT_0012ef38))) != 0))) != 0) || ((b(((b((DAT_0012f048) <= (f.dVar75))) != 0) || ((b((f.fVar93) <= (6.0))) != 0))) != 0))) != 0) 2397 else 2380
        }
        2399 -> {
            f.fVar62 = f32(3.5)
            324
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep75(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2400 -> {
            f.fVar67 = f32(1.0)
            2399
        }
        2401 -> {
            f.fVar67 = f32(0.5)
            2399
        }
        2402 -> {
            318
        }
        2403 -> {
            f.fVar67 = f32(0.5)
            2402
        }
        2404 -> {
            if ((b(((b(((b(((b((3.8) <= (f.dVar75))) != 0) || ((b((f.fVar32) <= (0.25))) != 0))) != 0) || ((b((f.dVar28) <= (4.6))) != 0))) != 0) || ((b(((b((f.dVar45) < (0.3))) != 0) && ((b(((b((-(0.3)) < (f.dVar49))) != 0) || ((b((f32((f.fVar64) - (f.fVar44))) < (DAT_0012f178))) != 0))) != 0))) != 0))) != 0) 2403 else 2401
        }
        2405 -> {
            if ((b((0.7) <= (f.fVar67))) != 0) 2400 else 2404
        }
        2406 -> {
            323
        }
        2407 -> {
            if ((b(((b((f.fVar47) < (0.5))) != 0) && ((b((DAT_0012f078) < (f.dVar28))) != 0))) != 0) 2406 else 2405
        }
        2408 -> {
            323
        }
        2409 -> {
            if ((b((f.fVar47) < (0.5))) != 0) 2408 else 2405
        }
        2410 -> {
            if ((b(((b((66.0) <= (f.fVar31))) != 0) || ((b(((b((f.dVar75) <= (DAT_0012f060))) != 0) || ((b((f.fVar93) <= (f.fVar63))) != 0))) != 0))) != 0) 2407 else 2409
        }
        2411 -> {
            324
        }
        2412 -> {
            f.fVar62 = f32(4.5)
            2411
        }
        2413 -> {
            f.fVar67 = f32(0.4)
            2412
        }
        2414 -> {
            f.fVar62 = f32(3.9)
            2411
        }
        2415 -> {
            f.fVar67 = f32(0.4)
            2414
        }
        2416 -> {
            if ((b(((b((4.0) <= (f.fVar80))) != 0) || ((b((f.dVar38) <= (0.6))) != 0))) != 0) 2413 else 2415
        }
        2417 -> {
            if ((b(((b((f.param_12) < (11.0))) != 0) && ((b((f.fVar79) < (5.0))) != 0))) != 0) 323 else 2410
        }
        2418 -> {
            if ((b(((b(((b(((b((4.5) <= (f.fVar80))) != 0) || ((b((f.fVar35) <= (1.0))) != 0))) != 0) && ((b(((b(((b((DAT_0012f098) <= (f.dVar29))) != 0) || ((b((f.dVar51) <= (DAT_0012ef70))) != 0))) != 0) && ((b(((b((4.3) <= (f.dVar29))) != 0) || ((b((f.dVar51) <= (DAT_0012f010))) != 0))) != 0))) != 0))) != 0) || ((b(((b((f.dVar78) <= (8.2))) != 0) || ((b(((b(((b((DAT_0012f060) <= (f.dVar75))) != 0) || ((b((f.dVar38) <= (DAT_0012ef38))) != 0))) != 0) || ((b((f.dVar39) < (DAT_0012f090))) != 0))) != 0))) != 0))) != 0) 2398 else 2417
        }
        2419 -> {
            if ((b(((b(((b((f.dVar29) < (DAT_0012f150))) != 0) && ((b((66.0) < (f.fVar48))) != 0))) != 0) && ((b((DAT_0012f040) < (f.dVar51))) != 0))) != 0) 2418 else 2356
        }
        2420 -> {
            f.dVar51 = f.fVar35
            2419
        }
        2421 -> {
            if ((b(((b(((b((1.0) <= (f.fVar84))) != 0) || ((b((f.dVar30) < (DAT_0012f030))) != 0))) != 0) || ((b(((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b(((b(((b((DAT_0012ef70) <= (f.fVar67))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) || ((b((DAT_0012ef70) <= (f.fVar32))) != 0))) != 0))) != 0))) != 0) 2314 else 2420
        }
        2422 -> {
            332
        }
        2423 -> {
            f.fVar32 = f32(3.5)
            2422
        }
        2424 -> {
            f.fVar67 = f32(0.8)
            304
        }
        2425 -> {
            if ((b(((b(((b(((b((f.dVar29) < (DAT_0012f048))) != 0) && ((b(((b(((b((72.0) < (f.fVar48))) != 0) || ((b((1.2) < (f.dVar38))) != 0))) != 0) && ((b((f.fVar71) < (3.5))) != 0))) != 0))) != 0) && ((b(((b((8.0) < (f.fVar25))) != 0) && ((b((6.8) < (f.dVar36))) != 0))) != 0))) != 0) && ((b(((b((0.3) < (f.fVar32))) != 0) || ((b((7.5) < (f.fVar93))) != 0))) != 0))) != 0) 2424 else 2421
        }
        2426 -> {
            259
        }
        2427 -> {
            f.fVar81 = f32(3.5)
            2426
        }
        2428 -> {
            f.fVar67 = f32(0.8)
            2427
        }
        2429 -> {
            f.fVar81 = f32(3.0)
            2426
        }
        2430 -> {
            f.fVar67 = f32(1.0)
            2429
        }
        2431 -> {
            if ((b(((b((2.9) <= (f.dVar75))) != 0) || ((b(((b(((b((2.5) <= (f.fVar37))) != 0) && ((b((2.5) <= (f.fVar40))) != 0))) != 0) && ((b((2.5) <= (f.fVar61))) != 0))) != 0))) != 0) 2428 else 2430
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep76(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2432 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x19c))) < (2.5))) != 0) && ((b(((b(((b((6.8) < (f.dVar39))) != 0) || ((b((6.8) < (f.dVar36))) != 0))) != 0) && ((b((f.fVar40) < (3.5))) != 0))) != 0))) != 0) && ((b(((b(((b((f.fVar37) < (3.5))) != 0) && ((b((f.dVar75) < (3.9))) != 0))) != 0) && ((b((60.0) < (f.fVar48))) != 0))) != 0))) != 0) 2431 else 2425
        }
        2433 -> {
            352
        }
        2434 -> {
            f.fVar66 = f32(f.fVar61)
            2433
        }
        2435 -> {
            if ((b(!((f.bVar17) != 0))) != 0) 2434 else 2433
        }
        2436 -> {
            f.bVar17 = b((b((f.fVar61) == (0.0))) != 0)
            2435
        }
        2437 -> {
            if ((b(((b(((b(!((f.bVar13) != 0))) != 0) && ((b((f.bVar16) == (f.bVar12))) != 0))) != 0) && ((run { run { f.bVar17 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar61).isNaN())) != 0)) }) != 0))) != 0) 2436 else 2435
        }
        2438 -> {
            f.bVar17 = b((0) != 0)
            2437
        }
        2439 -> {
            f.bVar16 = b((b((f.fVar42) < (2.8))) != 0)
            303
        }
        2440 -> {
            f.bVar13 = b((b((f.fVar42) == (2.8))) != 0)
            2439
        }
        2441 -> {
            f.bVar12 = b((b((f.fVar42).isNaN())) != 0)
            2440
        }
        2442 -> {
            f.fVar42 = f32(f32((f.fVar66) + (f.param_4)))
            2441
        }
        2443 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2442
        }
        2444 -> {
            352
        }
        2445 -> {
            if ((b((f.iVar24) != (1))) != 0) 2444 else 2443
        }
        2446 -> {
            f.fVar66 = f32(f.fVar61)
            2445
        }
        2447 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            302
        }
        2448 -> {
            352
        }
        2449 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2448
        }
        2450 -> {
            f.fVar66 = f32(f32(((((3.3) - (f.dVar75))) * (ARR_0012f2d8[b((f.dVar28) < (f.dVar75))]))))
            2449
        }
        2451 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2450
        }
        2452 -> {
            if ((b(((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b((f.dVar75) < (4.3))) != 0))) != 0) && ((b((DAT_0012f030) < (f.dVar75))) != 0))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 2451 else 2447
        }
        2453 -> {
            f.param_5 = f32(f.auVar57)
            2452
        }
        2454 -> {
            f.dVar28 = DAT_0012f050
            2453
        }
        2455 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2454
        }
        2456 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            302
        }
        2457 -> {
            272
        }
        2458 -> {
            f.dVar28 = ((((3.3) - (f.dVar75))) * (f.dVar28))
            2457
        }
        2459 -> {
            f.dVar28 = DAT_0012ee70
            2458
        }
        2460 -> {
            if ((b((f.dVar75) <= (DAT_0012f050))) != 0) 2459 else 2458
        }
        2461 -> {
            f.dVar28 = 0.5
            2460
        }
        2462 -> {
            if ((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b((f.dVar75) < (4.3))) != 0))) != 0) && ((b(((b((DAT_0012f030) < (f.dVar75))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 2461 else 2456
        }
        2463 -> {
            f.param_5 = f32(f.auVar57)
            2462
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep77(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2464 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 1.1, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2463
        }
        2465 -> {
            300
        }
        2466 -> {
            f.fVar77 = f32(4.5)
            2465
        }
        2467 -> {
            f.fVar67 = f32(1.2)
            2466
        }
        2468 -> {
            if ((b((48.0) <= (f.fVar48))) != 0) 2467 else 2464
        }
        2469 -> {
            if ((b((f.fVar48) < (36.0))) != 0) 2455 else 2468
        }
        2470 -> {
            f.auVar57 = f32(f.param_5)
            2469
        }
        2471 -> {
            288
        }
        2472 -> {
            f.fVar81 = f32(4.7)
            2471
        }
        2473 -> {
            if ((b(((b(((b((f.fVar84) < (DAT_0012ef70))) != 0) && ((b((f.fVar67) < (0.6))) != 0))) != 0) && ((b(((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0) && ((b((0x30) < (readI32(f.param_1.plus(0x21c))))) != 0))) != 0))) != 0) 2472 else 2470
        }
        2474 -> {
            260
        }
        2475 -> {
            f.fVar67 = f32(0.5)
            2474
        }
        2476 -> {
            352
        }
        2477 -> {
            301
        }
        2478 -> {
            281
        }
        2479 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2478
        }
        2480 -> {
            if ((b(((b(((b((4.3) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (DAT_0012f030))) != 0))) != 0) || ((run { run { f.dVar28 = DAT_0012f050; DAT_0012f050 }; run { f.dVar29 = DAT_0012f030; DAT_0012f030 }; b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030)) }) != 0))) != 0) 2479 else 2477
        }
        2481 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 2480 else 2476
        }
        2482 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar32, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2481
        }
        2483 -> {
            f.fVar32 = f32(3.5)
            2482
        }
        2484 -> {
            if ((b(((f.bVar16) != 0) || ((b((f.bVar12) != (f.bVar13))) != 0))) != 0) 2483 else 2482
        }
        2485 -> {
            f.fVar32 = f32(f.fVar71)
            2484
        }
        2486 -> {
            f.bVar13 = b((0) != 0)
            2485
        }
        2487 -> {
            f.bVar16 = b((b((f.fVar71) == (3.5))) != 0)
            2486
        }
        2488 -> {
            f.bVar12 = b((b((f.fVar71) < (3.5))) != 0)
            2487
        }
        2489 -> {
            if ((b(!((b((f.fVar71).isNaN())) != 0))) != 0) 2488 else 2485
        }
        2490 -> {
            f.bVar13 = b((1) != 0)
            2489
        }
        2491 -> {
            f.bVar16 = b((0) != 0)
            2490
        }
        2492 -> {
            f.bVar12 = b((0) != 0)
            2491
        }
        2493 -> {
            if ((b((f.dVar75) < (3.9))) != 0) 2492 else 2485
        }
        2494 -> {
            f.bVar13 = b((0) != 0)
            2493
        }
        2495 -> {
            f.bVar16 = b((1) != 0)
            2494
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep78(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2496 -> {
            f.bVar12 = b((0) != 0)
            2495
        }
        2497 -> {
            if ((b(((b((48.0) < (f.fVar48))) != 0) && ((run { run { f.fVar32 = f32(3.0); f32(3.0) }; b((f.fVar67) < (DAT_0012ef70)) }) != 0))) != 0) 2496 else 2482
        }
        2498 -> {
            f.fVar32 = f32(3.0)
            2497
        }
        2499 -> {
            296
        }
        2500 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2499
        }
        2501 -> {
            f.dVar29 = ((f.dVar29) - (f.dVar75))
            2500
        }
        2502 -> {
            f.dVar28 = ARR_0012f2d8[b((f.dVar28) < (f.dVar75))]
            2501
        }
        2503 -> {
            281
        }
        2504 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2503
        }
        2505 -> {
            if ((b(((b(((b((DAT_0012f050) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (DAT_0012f070))) != 0))) != 0) || ((run { run { f.dVar28 = DAT_0012f068; DAT_0012f068 }; run { f.dVar29 = DAT_0012f070; DAT_0012f070 }; b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f070)) }) != 0))) != 0) 2504 else 301
        }
        2506 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 2505 else 2476
        }
        2507 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2506
        }
        2508 -> {
            if ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) 2498 else 2507
        }
        2509 -> {
            324
        }
        2510 -> {
            f.fVar67 = f32(0.65)
            2509
        }
        2511 -> {
            f.fVar62 = f32(4.3)
            2510
        }
        2512 -> {
            f.fVar62 = f32(4.9)
            2510
        }
        2513 -> {
            if ((b(((b(((b((f.fVar37) <= (3.9))) != 0) || ((b((f.fVar40) <= (3.9))) != 0))) != 0) || ((b((f.fVar61) <= (3.9))) != 0))) != 0) 2511 else 2512
        }
        2514 -> {
            if ((b(((b(((b(((b((f.fVar84) < (1.2))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b((f.fVar67) < (1.0))) != 0))) != 0) && ((b((60.0) < (f.fVar48))) != 0))) != 0) 2513 else 2508
        }
        2515 -> {
            if ((b(((b((f.param_14) < (0x6c1))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) 2514 else 2475
        }
        2516 -> {
            f.fVar67 = f32(0.5)
            2474
        }
        2517 -> {
            if ((b(((b(((b((4.3) <= (f.dVar75))) != 0) || ((b((f.fVar61) <= (3.3))) != 0))) != 0) || ((b(((b((f.fVar40) <= (3.3))) != 0) || ((b(((b((f.fVar37) <= (3.3))) != 0) || ((b((readI32(f.param_1.plus(0x278))) < (1))) != 0))) != 0))) != 0))) != 0) 2515 else 2516
        }
        2518 -> {
            287
        }
        2519 -> {
            f.fVar67 = f32(0.5)
            2518
        }
        2520 -> {
            f.fVar77 = f32(4.5)
            2519
        }
        2521 -> {
            if ((b(((b(((b((1.0) <= (f.fVar43))) != 0) || ((b((1.0) <= (f.fVar90))) != 0))) != 0) || ((run { run { f.fVar77 = f32(f.fVar71); f32(f.fVar71) }; b((1.0) <= (f.fVar73)) }) != 0))) != 0) 2520 else 2519
        }
        2522 -> {
            if ((b(((b(((b(((b((48.0) < (f.fVar48))) != 0) && ((b((3.9) < (f.fVar61))) != 0))) != 0) && ((b(((b((3.9) < (f.fVar40))) != 0) && ((b(((b((3.9) < (f.fVar37))) != 0) && ((b((3.9) < (f.fVar83))) != 0))) != 0))) != 0))) != 0) && ((b((f.fVar63) < (8.0))) != 0))) != 0) 2521 else 2517
        }
        2523 -> {
            if ((f.bVar16) != 0) 2522 else 2473
        }
        2524 -> {
            336
        }
        2525 -> {
            f.auVar53 = f32(f.param_5)
            2524
        }
        2526 -> {
            f.fVar77 = f32(4.5)
            300
        }
        2527 -> {
            f.fVar67 = f32(0.55)
            2526
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep79(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2528 -> {
            324
        }
        2529 -> {
            f.fVar62 = f32(3.5)
            2528
        }
        2530 -> {
            f.fVar67 = f32(1.0)
            2529
        }
        2531 -> {
            298
        }
        2532 -> {
            f.fVar67 = f32(0.95)
            2531
        }
        2533 -> {
            if ((b(((b(((b((f.dVar45) < (0.3))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (7.0))) != 0))) != 0) && ((b((-(0.3)) < (f.dVar49))) != 0))) != 0) 2532 else 297
        }
        2534 -> {
            if ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) 2533 else 2527
        }
        2535 -> {
            291
        }
        2536 -> {
            f.fVar67 = f32(1.1)
            2535
        }
        2537 -> {
            290
        }
        2538 -> {
            f.fVar67 = f32(1.1)
            2537
        }
        2539 -> {
            if ((b(((b((f.dVar75) < (DAT_0012f030))) != 0) && ((b(((b((1.5) < (f.fVar47))) != 0) && ((b((DAT_0012f040) < (f.fVar32))) != 0))) != 0))) != 0) 2538 else 2536
        }
        2540 -> {
            if ((b(((b((0.5) <= (f.fVar32))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) 2539 else 2534
        }
        2541 -> {
            352
        }
        2542 -> {
            f.fVar66 = f32(f.fVar61)
            2541
        }
        2543 -> {
            writeI64(f.param_1.plus(0x18c), (0).toLong())
            2542
        }
        2544 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (f.fVar62)) }) != 0))) != 0))) != 0) 2543 else 2541
        }
        2545 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2541
        }
        2546 -> {
            f.fVar66 = f32(f32(((f.dVar29) * (f.dVar28))))
            2545
        }
        2547 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2546
        }
        2548 -> {
            f.dVar28 = ARR_0012f2d8[b((DAT_0012f050) < (f.dVar28))]
            2547
        }
        2549 -> {
            f.dVar29 = ((DAT_0012f030) - (f.dVar28))
            2548
        }
        2550 -> {
            if ((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((4.3) <= (f.dVar28))) != 0))) != 0) || ((b(((b((f.dVar28) <= (DAT_0012f030))) != 0) || ((b(((b((f.fVar76) <= (f.dVar87))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030))) != 0))) != 0))) != 0))) != 0) 2544 else 2549
        }
        2551 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.2, 1.4, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2550
        }
        2552 -> {
            f.dVar28 = f.dVar75
            2551
        }
        2553 -> {
            f.fVar66 = f32(f32(((f.dVar29) * (f.dVar28))))
            2541
        }
        2554 -> {
            writeF32(f.param_1.plus(400), f32(f32(((f.dVar29) * (f.dVar28)))))
            2553
        }
        2555 -> {
            f.dVar29 = ((2.5) - (f.dVar75))
            296
        }
        2556 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2555
        }
        2557 -> {
            f.dVar28 = ARR_0012f2d8[b((2.8) < (f.dVar75))]
            2556
        }
        2558 -> {
            264
        }
        2559 -> {
            292
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep80(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2560 -> {
            f.fVar42 = f32(2.5)
            2559
        }
        2561 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2560
        }
        2562 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) && ((b((f.fVar61) == (0.0))) != 0))) != 0) 2561 else 2558
        }
        2563 -> {
            if ((b(((b(((b(((b((DAT_0012f050) <= (f.dVar28))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((b((f.dVar75) <= (DAT_0012f070))) != 0))) != 0) || ((b(((b((60.0) <= (f.fVar48))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (2.3))) != 0))) != 0))) != 0) 2562 else 2557
        }
        2564 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2563
        }
        2565 -> {
            f.dVar28 = f.dVar75
            2564
        }
        2566 -> {
            if ((b(((b(((b((f.fVar63) <= (10.0))) != 0) && ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0))) != 0) || ((b((2.8) <= (f.dVar75))) != 0))) != 0) 2552 else 2565
        }
        2567 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2541
        }
        2568 -> {
            if ((b(((b(((b(((b((f.fVar48) <= (54.0))) != 0) || ((b(((b((1.5) <= (f.fVar47))) != 0) && ((b((11.0) <= (f.fVar72))) != 0))) != 0))) != 0) || ((b(((b((7.5) <= (f.fVar63))) != 0) && ((b((DAT_0012f178) <= (f32((f.fVar64) - (f.fVar44))))) != 0))) != 0))) != 0) || ((b(((b((2.0) <= (f.fVar35))) != 0) || ((b((9.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0))) != 0) 2566 else 2567
        }
        2569 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.9, 0.6, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2541
        }
        2570 -> {
            if ((b(((b(((b(((b((0.25) <= (f.fVar32))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) || ((b((1.0) <= (f.fVar47))) != 0))) != 0) || ((b((7.8) <= (f.dVar36))) != 0))) != 0) 2568 else 2569
        }
        2571 -> {
            260
        }
        2572 -> {
            f.fVar81 = f32(4.5)
            2571
        }
        2573 -> {
            f.fVar67 = f32(0.55)
            2572
        }
        2574 -> {
            if ((b(((b(((b((f.fVar84) < (DAT_0012ef70))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b(((b((f.fVar67) < (DAT_0012ef70))) != 0) && ((b((72.0) < (f.fVar48))) != 0))) != 0))) != 0) 2573 else 2570
        }
        2575 -> {
            if ((b((0x35f) < (f.param_14))) != 0) 2574 else 2540
        }
        2576 -> {
            f.fVar77 = f32(3.2)
            300
        }
        2577 -> {
            f.fVar67 = f32(1.1)
            2576
        }
        2578 -> {
            f.fVar77 = f32(3.9)
            300
        }
        2579 -> {
            f.fVar67 = f32(0.55)
            2578
        }
        2580 -> {
            if ((b(((b(((b((1.0) <= (f.fVar84))) != 0) || ((b((DAT_0012ef70) <= (f.fVar67))) != 0))) != 0) || ((b((7.5) <= (f.fVar63))) != 0))) != 0) 2577 else 2579
        }
        2581 -> {
            352
        }
        2582 -> {
            f.param_5 = f32(f.auVar60)
            2581
        }
        2583 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2582
        }
        2584 -> {
            f.fVar67 = f32(2.5)
            2583
        }
        2585 -> {
            299
        }
        2586 -> {
            if ((b(((b((f.dVar45) < (0.3))) != 0) && ((b(((b((f.dVar39) < (7.8))) != 0) || ((b((f.dVar49) <= (-(0.3)))) != 0))) != 0))) != 0) 2585 else 2584
        }
        2587 -> {
            f.fVar67 = f32(3.0)
            2583
        }
        2588 -> {
            if ((b(((b((0.3) <= (f.dVar45))) != 0) || ((b((f.dVar49) <= (-(0.3)))) != 0))) != 0) 2586 else 299
        }
        2589 -> {
            f.auVar60 = f32(f.param_5)
            2588
        }
        2590 -> {
            352
        }
        2591 -> {
            301
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep81(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2592 -> {
            281
        }
        2593 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2592
        }
        2594 -> {
            if ((b(((b(((b((4.3) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (DAT_0012f030))) != 0))) != 0) || ((run { run { f.dVar28 = DAT_0012f050; DAT_0012f050 }; run { f.dVar29 = DAT_0012f030; DAT_0012f030 }; b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030)) }) != 0))) != 0) 2593 else 2591
        }
        2595 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 2594 else 2590
        }
        2596 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2595
        }
        2597 -> {
            if ((b(((b(((b((f.fVar48) < (48.0))) != 0) && ((b((f.fVar63) < (8.5))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1c4))) < (0.5))) != 0))) != 0) 2596 else 2589
        }
        2598 -> {
            324
        }
        2599 -> {
            f.fVar62 = f32(3.0)
            2598
        }
        2600 -> {
            f.fVar67 = f32(1.2)
            2599
        }
        2601 -> {
            f.fVar62 = f32(2.5)
            2598
        }
        2602 -> {
            f.fVar67 = f32(1.2)
            2601
        }
        2603 -> {
            if ((b((f.fVar63) <= (8.5))) != 0) 2600 else 2602
        }
        2604 -> {
            297
        }
        2605 -> {
            if ((b(((b(((b((48.0) < (f.fVar48))) != 0) && ((b((f.fVar63) < (7.5))) != 0))) != 0) && ((b((f.fVar93) < (7.5))) != 0))) != 0) 2604 else 2603
        }
        2606 -> {
            f.fVar62 = f32(3.9)
            2598
        }
        2607 -> {
            f.fVar67 = f32(0.9)
            298
        }
        2608 -> {
            if ((b(((b((f.fVar48) <= (54.0))) != 0) || ((b((f.dVar49) <= (-(0.35)))) != 0))) != 0) 2605 else 2607
        }
        2609 -> {
            f.fVar62 = f32(4.5)
            2598
        }
        2610 -> {
            f.fVar67 = f32(0.55)
            2609
        }
        2611 -> {
            if ((b(((b((DAT_0012ef70) <= (f.fVar84))) != 0) || ((b(((b((DAT_0012ef70) <= (f.fVar67))) != 0) || ((b((f.fVar48) <= (66.0))) != 0))) != 0))) != 0) 2608 else 2610
        }
        2612 -> {
            if ((b(((b((f.fVar84) <= (1.0))) != 0) && ((b((f.fVar67) <= (1.0))) != 0))) != 0) 2611 else 2597
        }
        2613 -> {
            if ((b((2.0) < (f.fVar42))) != 0) 2612 else 2580
        }
        2614 -> {
            if ((b((3.0) < (f.fVar42))) != 0) 2575 else 2613
        }
        2615 -> {
            f.fVar62 = f32(3.0)
            2614
        }
        2616 -> {
            324
        }
        2617 -> {
            f.fVar62 = f32(4.2)
            2616
        }
        2618 -> {
            f.fVar67 = f32(0.55)
            294
        }
        2619 -> {
            f.fVar62 = f32(4.7)
            2616
        }
        2620 -> {
            f.fVar67 = f32(0.5)
            2619
        }
        2621 -> {
            295
        }
        2622 -> {
            if ((b(((b((f.fVar71) < (4.5))) != 0) && ((b((f.dVar49) < (DAT_0012f1f8))) != 0))) != 0) 2621 else 2620
        }
        2623 -> {
            294
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep82(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2624 -> {
            f.fVar67 = f32(0.5)
            2623
        }
        2625 -> {
            if ((b(((b((9.0) < (f.fVar72))) != 0) && ((b((6.5) < (f.fVar63))) != 0))) != 0) 2624 else 2620
        }
        2626 -> {
            if ((b((f.fVar71) < (4.5))) != 0) 295 else 2620
        }
        2627 -> {
            if ((b((f.dVar45) <= (0.3))) != 0) 2622 else 2626
        }
        2628 -> {
            if ((b((108.0) < (f.fVar48))) != 0) 2618 else 2627
        }
        2629 -> {
            352
        }
        2630 -> {
            f.fVar66 = f32(f32(f.dVar28))
            2629
        }
        2631 -> {
            writeF32(f.param_1.plus(400), f32(f32(f.dVar28)))
            2630
        }
        2632 -> {
            f.dVar28 = ((3.3) - (f.dVar75))
            293
        }
        2633 -> {
            if ((b((f.dVar75) <= (f.dVar29))) != 0) 2632 else 293
        }
        2634 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2633
        }
        2635 -> {
            f.dVar28 = ((((3.3) - (f.dVar75))) * (DAT_0012ef38))
            2634
        }
        2636 -> {
            264
        }
        2637 -> {
            352
        }
        2638 -> {
            if ((b((f.fVar42) < (f32((f.fVar66) + (f.param_4))))) != 0) 2637 else 2636
        }
        2639 -> {
            f.fVar42 = f32(3.0)
            292
        }
        2640 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2639
        }
        2641 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) && ((b((f.fVar61) == (0.0))) != 0))) != 0) 2640 else 2636
        }
        2642 -> {
            if ((b(((b(((b((f.dVar75) <= (DAT_0012f030))) != 0) || ((b((DAT_0012f048) <= (f.dVar75))) != 0))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((b(((b((f.fVar76) <= (f.dVar28))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030))) != 0))) != 0))) != 0))) != 0) 2641 else 2635
        }
        2643 -> {
            f.dVar29 = DAT_0012f050
            2642
        }
        2644 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2643
        }
        2645 -> {
            f.dVar28 = DAT_0012ef70
            2644
        }
        2646 -> {
            352
        }
        2647 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2646
        }
        2648 -> {
            f.fVar67 = f32(f.fVar71)
            2647
        }
        2649 -> {
            f.fVar32 = f32(0.9)
            2648
        }
        2650 -> {
            352
        }
        2651 -> {
            f.fVar66 = f32(0.0)
            2650
        }
        2652 -> {
            writeF32(f.pjVar4, f32(0.0))
            2651
        }
        2653 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (DAT_0012f030))) != 0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((readF32(f.param_1.plus(0x298))) <= (3.0)) }) != 0))) != 0) 2652 else 2650
        }
        2654 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2650
        }
        2655 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2654
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep83(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2656 -> {
            f.fVar66 = f32(f32(((ARR_0012f2d8[b((DAT_0012f050) < (f.dVar75))]) * (((3.0) - (f.fVar71))))))
            2655
        }
        2657 -> {
            if ((b(((b(((b((4.5) <= (f.fVar71))) != 0) || ((b(((b((f.dVar75) <= (DAT_0012f030))) != 0) || ((b((f.dVar38) <= (1.2))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.0))) != 0))) != 0) 2653 else 2656
        }
        2658 -> {
            if ((b(((b((3.9) <= (f.dVar75))) != 0) || ((b((7.0) <= (f.fVar93))) != 0))) != 0) 2657 else 2649
        }
        2659 -> {
            f.fVar32 = f32(1.5)
            2647
        }
        2660 -> {
            f.fVar67 = f32(3.5)
            2659
        }
        2661 -> {
            f.fVar67 = f32(4.2)
            2659
        }
        2662 -> {
            if ((b(((b(((b((f.dVar75) <= (DAT_0012f050))) != 0) || ((b((1.0) <= (f.fVar47))) != 0))) != 0) || ((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((f.fVar91) <= (6.5))) != 0))) != 0))) != 0) 2660 else 2661
        }
        2663 -> {
            if ((b((DAT_0012ee90) <= (f.fVar35))) != 0) 2658 else 2662
        }
        2664 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar71, 0.9, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2646
        }
        2665 -> {
            if ((b(((b((f.param_14) < (0x6c1))) != 0) || ((b(((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((DAT_0012f098) <= (f.dVar75))) != 0))) != 0) || ((b((1.0) <= (f.fVar47))) != 0))) != 0))) != 0) 2663 else 2664
        }
        2666 -> {
            if ((b(((b((32.0) <= (f.fVar48))) != 0) || ((b((f.fVar67) <= (DAT_0012ef70))) != 0))) != 0) 2665 else 2645
        }
        2667 -> {
            f.dVar28 = ((((2.5) - (f.dVar75))) * (f.dVar29))
            293
        }
        2668 -> {
            f.dVar29 = f.dVar28
            2667
        }
        2669 -> {
            if ((b((f.dVar75) <= (2.8))) != 0) 2668 else 2667
        }
        2670 -> {
            f.dVar29 = 0.5
            2669
        }
        2671 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2670
        }
        2672 -> {
            352
        }
        2673 -> {
            264
        }
        2674 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (f.fVar32)) }) != 0))) != 0) 2673 else 2672
        }
        2675 -> {
            if ((b(((b(((b(((b((f.dVar75) <= (DAT_0012f070))) != 0) || ((b((3.5) <= (f.fVar71))) != 0))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (2.3))) != 0))) != 0) 2674 else 2671
        }
        2676 -> {
            f.dVar28 = DAT_0012ee70
            2675
        }
        2677 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2676
        }
        2678 -> {
            f.fVar67 = f32(2.5)
            2677
        }
        2679 -> {
            if ((b(((f.bVar16) != 0) || ((b((f.bVar12) != (f.bVar13))) != 0))) != 0) 2678 else 2677
        }
        2680 -> {
            f.fVar67 = f32(3.5)
            2679
        }
        2681 -> {
            f.fVar32 = f32(2.5)
            2680
        }
        2682 -> {
            f.bVar13 = b((0) != 0)
            2681
        }
        2683 -> {
            f.bVar16 = b((b((f.fVar71) == (3.5))) != 0)
            2682
        }
        2684 -> {
            f.bVar12 = b((b((f.fVar71) < (3.5))) != 0)
            2683
        }
        2685 -> {
            if ((b(!((b((f.fVar71).isNaN())) != 0))) != 0) 2684 else 2681
        }
        2686 -> {
            f.bVar13 = b((1) != 0)
            2685
        }
        2687 -> {
            f.bVar16 = b((0) != 0)
            2686
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep84(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2688 -> {
            f.bVar12 = b((0) != 0)
            2687
        }
        2689 -> {
            if ((b((48.0) < (f.fVar48))) != 0) 2688 else 2681
        }
        2690 -> {
            f.bVar13 = b((0) != 0)
            2689
        }
        2691 -> {
            f.bVar16 = b((1) != 0)
            2690
        }
        2692 -> {
            f.bVar12 = b((0) != 0)
            2691
        }
        2693 -> {
            if ((b(((b(((b((72.0) <= (f.fVar48))) != 0) || ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0))) != 0) || ((b((f.fVar67) <= (0.6))) != 0))) != 0) 2666 else 2692
        }
        2694 -> {
            260
        }
        2695 -> {
            f.fVar81 = f32(4.2)
            2694
        }
        2696 -> {
            f.fVar67 = f32(0.65)
            2695
        }
        2697 -> {
            if ((b(((b(((b(((b((DAT_0012f060) < (f.dVar75))) != 0) && ((b((3.3) < (f.dVar51))) != 0))) != 0) && ((b((3.3) < (f.dVar82))) != 0))) != 0) && ((b(((b((3.3) < (f.dVar89))) != 0) && ((b((54.0) < (f.fVar48))) != 0))) != 0))) != 0) 2696 else 2693
        }
        2698 -> {
            if ((b(((b(((b((1.0) <= (f.fVar84))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b(((b((f.fVar48) <= (72.0))) != 0) || ((b((5.8) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0))) != 0) 2697 else 2628
        }
        2699 -> {
            300
        }
        2700 -> {
            f.fVar77 = f32(3.0)
            2699
        }
        2701 -> {
            f.fVar67 = f32(1.0)
            290
        }
        2702 -> {
            f.fVar77 = f32(3.5)
            2699
        }
        2703 -> {
            f.fVar67 = f32(1.0)
            291
        }
        2704 -> {
            if ((b(((b(((b((11.5) <= (f.fVar72))) != 0) || ((b((f.fVar48) <= (54.0))) != 0))) != 0) || ((b(((b((7.5) <= (f.fVar63))) != 0) || ((b((1.2) <= (f.dVar38))) != 0))) != 0))) != 0) 2701 else 2703
        }
        2705 -> {
            if ((b(!((f.bVar16) != 0))) != 0) 2704 else 2698
        }
        2706 -> {
            if ((b((4.0) < (f.fVar42))) != 0) 2705 else 2615
        }
        2707 -> {
            if ((b((f.fVar42) <= (4.5))) != 0) 2706 else 2523
        }
        2708 -> {
            352
        }
        2709 -> {
            303
        }
        2710 -> {
            f.bVar16 = b((b((f.fVar34) < (f.fVar42))) != 0)
            2709
        }
        2711 -> {
            f.bVar13 = b((b((f.fVar34) == (f.fVar42))) != 0)
            2710
        }
        2712 -> {
            f.bVar12 = b((b(((b((f.fVar34).isNaN())) != 0) || ((b((f.fVar42).isNaN())) != 0))) != 0)
            2711
        }
        2713 -> {
            f.fVar34 = f32(f32((f.fVar66) + (f.param_4)))
            2712
        }
        2714 -> {
            f.fVar42 = f32(3.0)
            289
        }
        2715 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2714
        }
        2716 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) 2715 else 2708
        }
        2717 -> {
            f.fVar66 = f32(f.fVar61)
            2716
        }
        2718 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2708
        }
        2719 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2718
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep85(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2720 -> {
            f.fVar66 = f32(f32(((((3.3) - (f.dVar28))) * (f.dVar29))))
            2719
        }
        2721 -> {
            f.dVar29 = DAT_0012ee70
            2720
        }
        2722 -> {
            if ((b((f.dVar28) <= (DAT_0012f050))) != 0) 2721 else 2720
        }
        2723 -> {
            f.dVar29 = 0.5
            2722
        }
        2724 -> {
            if ((b(((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((4.3) <= (f.dVar28))) != 0))) != 0) || ((b((f.dVar28) <= (DAT_0012f030))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.0))) != 0))) != 0) 2717 else 2723
        }
        2725 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2724
        }
        2726 -> {
            f.dVar28 = f.dVar75
            2725
        }
        2727 -> {
            336
        }
        2728 -> {
            f.fVar77 = f32(4.7)
            2727
        }
        2729 -> {
            f.fVar67 = f32(0.5)
            2728
        }
        2730 -> {
            f.fVar77 = f32(3.7)
            2727
        }
        2731 -> {
            f.fVar67 = f32(0.65)
            2730
        }
        2732 -> {
            if ((b(((b((f.fVar25) <= (9.0))) != 0) || ((b((f.fVar63) <= (6.0))) != 0))) != 0) 2729 else 2731
        }
        2733 -> {
            f.auVar53 = f32(f.param_5)
            2732
        }
        2734 -> {
            if ((b(((b(((b((f.fVar84) < (1.5))) != 0) && ((b((f.fVar67) < (DAT_0012ee80))) != 0))) != 0) && ((b(((b((54.0) < (f.fVar48))) != 0) && ((b((f.fVar63) < (8.5))) != 0))) != 0))) != 0) 2733 else 2726
        }
        2735 -> {
            260
        }
        2736 -> {
            f.fVar67 = f32(0.5)
            2735
        }
        2737 -> {
            f.fVar81 = f32(4.8)
            288
        }
        2738 -> {
            336
        }
        2739 -> {
            f.auVar53 = f32(f.param_5)
            2738
        }
        2740 -> {
            f.fVar67 = f32(0.8)
            287
        }
        2741 -> {
            f.fVar77 = f32(4.2)
            2740
        }
        2742 -> {
            if ((b(((b(((b((0.5) <= (f.fVar43))) != 0) || ((b((0.5) <= (f.fVar90))) != 0))) != 0) || ((run { run { f.fVar77 = f32(f.fVar71); f32(f.fVar71) }; b((0.5) <= (f.fVar73)) }) != 0))) != 0) 2741 else 2740
        }
        2743 -> {
            if ((b(((b(((b(((b((DAT_0012ef70) <= (f.fVar32))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (72.0))) != 0))) != 0) 2742 else 2737
        }
        2744 -> {
            if ((f.bVar16) != 0) 2743 else 2734
        }
        2745 -> {
            if ((b((DAT_0012f150) < (f.dVar30))) != 0) 2744 else 2707
        }
        2746 -> {
            352
        }
        2747 -> {
            286
        }
        2748 -> {
            f.dVar30 = ((5.5) - (f.dVar30))
            2747
        }
        2749 -> {
            if ((b((((f.dVar30) + (-(5.5)))) < (0.6))) != 0) 2748 else 2746
        }
        2750 -> {
            230
        }
        2751 -> {
            f.dVar30 = ((f.dVar30) * (0.5))
            2750
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep86(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2752 -> {
            f.dVar30 = ((DAT_0012f150) - (f.dVar30))
            286
        }
        2753 -> {
            if ((b((((f.dVar30) + (DAT_0012f158))) < (0.6))) != 0) 2752 else 2746
        }
        2754 -> {
            if ((b((11.0) <= (f.fVar72))) != 0) 2749 else 2753
        }
        2755 -> {
            f.dVar30 = f.param_6
            2754
        }
        2756 -> {
            if ((b((f.fVar42) < (7.0))) != 0) 2755 else 2746
        }
        2757 -> {
            f.fVar66 = f32(0.0)
            2756
        }
        2758 -> {
            f.fVar66 = f32(((((5.0) - (f.fVar42))) * (0.5)))
            2746
        }
        2759 -> {
            f.fVar66 = f32(((((5.0) - (f.param_6))) * (0.5)))
            2746
        }
        2760 -> {
            if ((b((0.6) <= (((f.param_6) + (-(5.0)))))) != 0) 2758 else 2759
        }
        2761 -> {
            230
        }
        2762 -> {
            f.dVar30 = ((((f.dVar28) - (f.dVar30))) * (0.5))
            2761
        }
        2763 -> {
            352
        }
        2764 -> {
            286
        }
        2765 -> {
            f.dVar30 = ((5.5) - (f.dVar30))
            2764
        }
        2766 -> {
            if ((b((((f.dVar30) + (-(5.5)))) < (0.5))) != 0) 2765 else 2763
        }
        2767 -> {
            f.fVar66 = f32(0.0)
            2766
        }
        2768 -> {
            if ((b((0.6) <= (((f.dVar30) + (DAT_0012f158))))) != 0) 2767 else 2762
        }
        2769 -> {
            f.dVar28 = DAT_0012f150
            2768
        }
        2770 -> {
            f.dVar28 = 5.1
            2762
        }
        2771 -> {
            if ((b((0.6) <= (((f.dVar30) + (-(5.1)))))) != 0) 2769 else 2770
        }
        2772 -> {
            if ((b((11.0) <= (f.fVar72))) != 0) 2771 else 2760
        }
        2773 -> {
            if ((b(((b((1.5) <= (f.fVar62))) != 0) || ((b((DAT_0012ee80) <= (f.fVar67))) != 0))) != 0) 2757 else 2772
        }
        2774 -> {
            if ((b((6.0) < (f.fVar42))) != 0) 2773 else 2745
        }
        2775 -> {
            if ((b((10.0) <= (f.fVar72))) != 0) 2774 else 2432
        }
        2776 -> {
            if ((b(((b((0x360) < (f.param_14))) != 0) || ((b((DAT_0012ee90) <= (f.dVar30))) != 0))) != 0) 2775 else 2072
        }
        2777 -> {
            352
        }
        2778 -> {
            f.fVar66 = f32(((4.5) - (f.param_6)))
            2777
        }
        2779 -> {
            if ((b(((b((((f.param_6) + (-(4.5)))) < (0.5))) != 0) && ((b((((f.dVar89) + (-(4.5)))) < (0.0))) != 0))) != 0) 2778 else 2777
        }
        2780 -> {
            286
        }
        2781 -> {
            f.dVar30 = ((DAT_0012f030) - (f.dVar30))
            2780
        }
        2782 -> {
            if ((b((((f.param_6) + (DAT_0012f0a0))) < (0.6))) != 0) 2781 else 2777
        }
        2783 -> {
            if ((b((f.fVar84) <= (1.0))) != 0) 2779 else 2782
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep87(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2784 -> {
            f.fVar66 = f32(f32(((((((6.6) - (f.dVar28))) - (f.dVar52))) / (3.0))))
            2777
        }
        2785 -> {
            if ((b((((f.dVar28) + (-(3.3)))) < (0.5))) != 0) 2784 else 2777
        }
        2786 -> {
            f.fVar66 = f32(f32(((f.dVar28) - (f.dVar30))))
            2777
        }
        2787 -> {
            f.dVar28 = 3.3
            270
        }
        2788 -> {
            f.fVar66 = f32(f32(((((((6.6) - (f.dVar30))) - (f.dVar28))) * (0.5))))
            2777
        }
        2789 -> {
            if ((b((0.6) <= (((f.dVar28) + (-(3.3)))))) != 0) 2787 else 2788
        }
        2790 -> {
            if ((b((0.0) <= (((((6.6) - (f.dVar30))) - (f.dVar52))))) != 0) 2785 else 2789
        }
        2791 -> {
            f.dVar28 = f.param_6
            2790
        }
        2792 -> {
            if ((b((kotlin.math.abs(f32((f.fVar42) - (f.fVar68)))) < (0.5))) != 0) 2783 else 2791
        }
        2793 -> {
            if ((b((DAT_0012f070) < (f.dVar30))) != 0) 2792 else 2777
        }
        2794 -> {
            f.fVar66 = f32(0.0)
            2793
        }
        2795 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2777
        }
        2796 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2795
        }
        2797 -> {
            f.fVar66 = f32(f32(((((DAT_0012f030) - (f.dVar75))) * (ARR_0012f2d8[b((DAT_0012f050) < (f.dVar75))]))))
            2796
        }
        2798 -> {
            277
        }
        2799 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2798
        }
        2800 -> {
            if ((b(((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((f.dVar75) <= (DAT_0012f030))) != 0))) != 0) || ((b((DAT_0012f048) <= (f.dVar75))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030))) != 0))) != 0) 2799 else 2797
        }
        2801 -> {
            f.param_5 = f32(f.auVar59)
            2800
        }
        2802 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2801
        }
        2803 -> {
            f.auVar59 = f32(f.param_5)
            2802
        }
        2804 -> {
            f.fVar67 = f32(3.2)
            2803
        }
        2805 -> {
            f.fVar67 = f32(2.8)
            2803
        }
        2806 -> {
            if ((b(((b(((b((f.dVar38) <= (DAT_0012ee90))) != 0) || ((b((f.dVar36) <= (8.2))) != 0))) != 0) || ((b(((b((f.dVar75) <= (DAT_0012f038))) != 0) || ((b((f.dVar39) <= (8.2))) != 0))) != 0))) != 0) 2804 else 2805
        }
        2807 -> {
            271
        }
        2808 -> {
            if ((b(((b((f.fVar31) < (60.0))) != 0) && ((b((f32((f.fVar80) + (f32((f32((f.fVar91) - (f.fVar79))) - (f.fVar79))))) < (0.3))) != 0))) != 0) 2807 else 2806
        }
        2809 -> {
            352
        }
        2810 -> {
            289
        }
        2811 -> {
            f.fVar42 = f32(2.5)
            2810
        }
        2812 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2811
        }
        2813 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) 2812 else 2809
        }
        2814 -> {
            f.fVar66 = f32(f.fVar61)
            2813
        }
        2815 -> {
            267
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep88(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2816 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2815
        }
        2817 -> {
            f.dVar29 = ((2.5) - (f.dVar75))
            2816
        }
        2818 -> {
            f.dVar28 = ARR_0012f2d8[b((2.8) < (f.dVar75))]
            2817
        }
        2819 -> {
            if ((b(((b(((b(((b((f.fVar32) < (f.fVar76))) != 0) && ((b((f.fVar67) < (3.5))) != 0))) != 0) && ((b((2.5) < (f.fVar67))) != 0))) != 0) && ((b(((b((f.fVar61) == (0.0))) != 0) && ((b((2.5) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 2818 else 2814
        }
        2820 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2819
        }
        2821 -> {
            f.fVar67 = f32(f.fVar71)
            2820
        }
        2822 -> {
            f.fVar32 = f32(1.0)
            2821
        }
        2823 -> {
            if ((b((2.4) < (readF32(f.param_1.plus(0x298))))) != 0) 2822 else 2806
        }
        2824 -> {
            if ((b((f.fVar31) < (60.0))) != 0) 271 else 2806
        }
        2825 -> {
            if ((b((f.dVar39) <= (DAT_0012f2a0))) != 0) 2808 else 2824
        }
        2826 -> {
            if ((b(((b((DAT_0012ef38) < (readF32(f.param_1.plus(0x1c4))))) != 0) && ((b((f.dVar75) < (3.3))) != 0))) != 0) 2825 else 2806
        }
        2827 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2777
        }
        2828 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2827
        }
        2829 -> {
            f.fVar66 = f32(f32(f.dVar28))
            2828
        }
        2830 -> {
            f.dVar28 = ((((3.3) - (f.dVar75))) * (DAT_0012ee70))
            272
        }
        2831 -> {
            if ((b((f.dVar75) <= (DAT_0012f050))) != 0) 2830 else 272
        }
        2832 -> {
            f.dVar28 = ((((3.5) - (f.dVar75))) * (0.5))
            2831
        }
        2833 -> {
            276
        }
        2834 -> {
            if ((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((f.fVar32) <= (3.5))) != 0))) != 0) || ((b(((b((4.5) <= (f.fVar32))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0))) != 0) 2833 else 2832
        }
        2835 -> {
            f.param_5 = f32(f.auVar56)
            2834
        }
        2836 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 0.55, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2835
        }
        2837 -> {
            f.fVar32 = f32(f.fVar71)
            2836
        }
        2838 -> {
            f.auVar56 = f32(f.param_5)
            2837
        }
        2839 -> {
            f.fVar67 = f32(3.6)
            2838
        }
        2840 -> {
            if ((b(((f.bVar16) != 0) || ((b((f.bVar12) != (f.bVar13))) != 0))) != 0) 2839 else 2838
        }
        2841 -> {
            f.fVar67 = f32(3.0)
            2840
        }
        2842 -> {
            f.bVar13 = b((0) != 0)
            2841
        }
        2843 -> {
            f.bVar16 = b((b((f.dVar45) == (DAT_0012f218))) != 0)
            2842
        }
        2844 -> {
            f.bVar12 = b((b((f.dVar45) < (DAT_0012f218))) != 0)
            2843
        }
        2845 -> {
            if ((b(((b(!((b((f.dVar45).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f218).isNaN())) != 0))) != 0))) != 0) 2844 else 2841
        }
        2846 -> {
            f.bVar13 = b((1) != 0)
            2845
        }
        2847 -> {
            f.bVar16 = b((0) != 0)
            2846
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep89(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2848 -> {
            f.bVar12 = b((0) != 0)
            2847
        }
        2849 -> {
            if ((b((f.dVar49) < (DAT_0012f1f8))) != 0) 2848 else 2841
        }
        2850 -> {
            f.bVar13 = b((0) != 0)
            2849
        }
        2851 -> {
            f.bVar16 = b((1) != 0)
            2850
        }
        2852 -> {
            f.bVar12 = b((0) != 0)
            2851
        }
        2853 -> {
            if ((b(((b(((b((f.fVar48) <= (54.0))) != 0) || ((b((1.2) <= (f.dVar28))) != 0))) != 0) || ((b(((b((0.85) <= (f.fVar32))) != 0) && ((b((((((f.dVar30) + (DAT_0012f018))) - (f.dVar75))) <= (f.dVar28))) != 0))) != 0))) != 0) 2826 else 2852
        }
        2854 -> {
            281
        }
        2855 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2854
        }
        2856 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 2855 else 2777
        }
        2857 -> {
            f.fVar66 = f32(f32(f.dVar28))
            2777
        }
        2858 -> {
            writeF32(f.param_1.plus(400), f32(f32(f.dVar28)))
            2857
        }
        2859 -> {
            f.dVar28 = ((((3.3) - (f.dVar75))) * (f.dVar30))
            274
        }
        2860 -> {
            f.dVar30 = f.dVar28
            2859
        }
        2861 -> {
            if ((b((f.dVar75) <= (f.dVar29))) != 0) 2860 else 2859
        }
        2862 -> {
            f.dVar30 = 0.5
            2861
        }
        2863 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            273
        }
        2864 -> {
            if ((b(((b(((b((4.5) <= (f.fVar32))) != 0) || ((b((f.dVar75) <= (3.3))) != 0))) != 0) || ((b(((b((f.fVar66) != (0.0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0))) != 0) 2856 else 2863
        }
        2865 -> {
            f.dVar28 = DAT_0012ee70
            2864
        }
        2866 -> {
            f.dVar29 = DAT_0012f050
            2865
        }
        2867 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.0, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2866
        }
        2868 -> {
            f.fVar32 = f32(f.fVar71)
            2867
        }
        2869 -> {
            f.fVar67 = f32(3.2)
            2868
        }
        2870 -> {
            f.fVar67 = f32(2.7)
            2868
        }
        2871 -> {
            if ((b(((b(((b(((b((f.fVar47) <= (2.0))) != 0) || ((b((f.fVar71) <= (3.0))) != 0))) != 0) || ((b((3.9) <= (f.dVar75))) != 0))) != 0) || ((b((39.0) <= (f.fVar48))) != 0))) != 0) 2869 else 2870
        }
        2872 -> {
            263
        }
        2873 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2872
        }
        2874 -> {
            264
        }
        2875 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) 2874 else 2873
        }
        2876 -> {
            293
        }
        2877 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2876
        }
        2878 -> {
            f.dVar28 = ((DAT_0012f068) - (f.dVar75))
            2877
        }
        2879 -> {
            if ((b((f.dVar75) <= (2.8))) != 0) 2878 else 2877
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep90(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2880 -> {
            f.dVar28 = ((((DAT_0012f068) - (f.dVar75))) * (DAT_0012ef38))
            2879
        }
        2881 -> {
            if ((b(((b(((b((f.fVar62) < (3.5))) != 0) && ((b((2.5) < (f.fVar71))) != 0))) != 0) && ((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b(((b((DAT_0012ef70) < (f.fVar76))) != 0) && ((b((0.5) < (f.fVar32))) != 0))) != 0))) != 0) && ((b((DAT_0012f068) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 2880 else 2875
        }
        2882 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2881
        }
        2883 -> {
            f.fVar62 = f32(f.fVar71)
            2882
        }
        2884 -> {
            f.fVar67 = f32(2.5)
            2883
        }
        2885 -> {
            f.fVar67 = f32(3.2)
            2883
        }
        2886 -> {
            if ((b(((b(((b((f.fVar93) <= (f.fVar63))) != 0) || ((b((9.0) <= (f.fVar93))) != 0))) != 0) || ((b((f.fVar66) <= (DAT_0012ef70))) != 0))) != 0) 2884 else 2885
        }
        2887 -> {
            if ((b(((b(((b((f.dVar75) < (DAT_0012f030))) != 0) && ((b((0.35) < (readF32(f.param_1.plus(0x1c4))))) != 0))) != 0) && ((b(((b((0.5) < (f.fVar32))) != 0) || ((b((8.0) < (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0))) != 0) 2886 else 2871
        }
        2888 -> {
            260
        }
        2889 -> {
            f.fVar81 = f32(3.5)
            2888
        }
        2890 -> {
            f.fVar67 = f32(1.0)
            2889
        }
        2891 -> {
            if ((b(((b((f.fVar47) <= (2.0))) != 0) && ((b(((b((f.fVar63) <= (8.5))) != 0) || ((b((f.fVar93) <= (9.0))) != 0))) != 0))) != 0) 2890 else 2887
        }
        2892 -> {
            if ((b((f.fVar72) <= (12.0))) != 0) 2891 else 2887
        }
        2893 -> {
            260
        }
        2894 -> {
            f.fVar81 = f32(3.5)
            2893
        }
        2895 -> {
            f.fVar67 = f32(0.6)
            2894
        }
        2896 -> {
            352
        }
        2897 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2896
        }
        2898 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) 2897 else 2896
        }
        2899 -> {
            274
        }
        2900 -> {
            f.dVar28 = ((2.5) - (f.dVar75))
            2899
        }
        2901 -> {
            if ((b((f.dVar75) <= (2.8))) != 0) 2900 else 2899
        }
        2902 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2901
        }
        2903 -> {
            f.dVar28 = ((((2.5) - (f.dVar75))) * (DAT_0012ef38))
            2902
        }
        2904 -> {
            if ((b(((b(((b(((b((f.fVar67) < (3.5))) != 0) && ((b((2.3) < (f.dVar75))) != 0))) != 0) && ((b((DAT_0012ef70) < (f.fVar76))) != 0))) != 0) && ((b((2.5) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 2903 else 2898
        }
        2905 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 2904 else 2896
        }
        2906 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.3, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2905
        }
        2907 -> {
            f.fVar67 = f32(f.fVar71)
            2906
        }
        2908 -> {
            273
        }
        2909 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2908
        }
        2910 -> {
            352
        }
        2911 -> {
            264
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep91(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2912 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((readF32(f.pjVar4)) + (f.param_4))) < (f.fVar62)) }) != 0))) != 0))) != 0) 2911 else 2910
        }
        2913 -> {
            if ((b(((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((4.5) <= (f.fVar67))) != 0))) != 0) || ((b((f.dVar75) <= (3.3))) != 0))) != 0) || ((b(((b((f32((readF32(f.param_1.plus(0x26c))) + (readF32(f.param_1.plus(0x270))))) <= (DAT_0012f1e0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0))) != 0) 2912 else 2909
        }
        2914 -> {
            f.dVar28 = DAT_0012ee70
            2913
        }
        2915 -> {
            f.dVar29 = DAT_0012f050
            2914
        }
        2916 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2915
        }
        2917 -> {
            f.fVar67 = f32(f.fVar71)
            2916
        }
        2918 -> {
            259
        }
        2919 -> {
            f.fVar81 = f32(3.5)
            2918
        }
        2920 -> {
            f.fVar67 = f32(1.0)
            2919
        }
        2921 -> {
            if ((b(((b((78.0) < (f.fVar31))) != 0) && ((b(((b((f.fVar63) < (8.0))) != 0) || ((b((f.fVar93) < (8.0))) != 0))) != 0))) != 0) 2920 else 2917
        }
        2922 -> {
            if ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) 2921 else 2907
        }
        2923 -> {
            281
        }
        2924 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2923
        }
        2925 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 2924 else 2896
        }
        2926 -> {
            293
        }
        2927 -> {
            f.dVar28 = ((2.5) - (f.dVar75))
            2926
        }
        2928 -> {
            if ((b((f.dVar75) <= (2.8))) != 0) 2927 else 2926
        }
        2929 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2928
        }
        2930 -> {
            f.dVar28 = ((((2.5) - (f.dVar75))) * (DAT_0012ef38))
            2929
        }
        2931 -> {
            if ((b(((b(((b((f.fVar67) < (3.5))) != 0) && ((b((2.3) < (f.dVar75))) != 0))) != 0) && ((b(((b((DAT_0012ef70) < (f.fVar32))) != 0) && ((b((2.5) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 2930 else 2925
        }
        2932 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2931
        }
        2933 -> {
            f.fVar67 = f32(f.fVar71)
            2932
        }
        2934 -> {
            if ((b(((b(((b((f.fVar35) <= (2.0))) != 0) || ((b(((b((f.fVar72) <= (12.0))) != 0) || ((b((f.dVar39) <= (DAT_0012f0b0))) != 0))) != 0))) != 0) || ((b((DAT_0012f068) <= (f.dVar75))) != 0))) != 0) 2922 else 2933
        }
        2935 -> {
            if ((b(((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b((0.6) <= (f.dVar28))) != 0))) != 0) 2934 else 2895
        }
        2936 -> {
            f.fVar81 = f32(4.7)
            2893
        }
        2937 -> {
            f.fVar67 = f32(0.5)
            2936
        }
        2938 -> {
            if ((b(((b(((b(((b((1.0) <= (f.fVar86))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (64.0))) != 0))) != 0) 2935 else 2937
        }
        2939 -> {
            if ((b(((b(((b((f.fVar86) <= (1.0))) != 0) || ((b((f.fVar67) <= (1.0))) != 0))) != 0) || ((b((48.0) <= (f.fVar48))) != 0))) != 0) 2938 else 2892
        }
        2940 -> {
            if ((b(((b((60.0) < (f.fVar48))) != 0) || ((b((f.fVar31) < (66.0))) != 0))) != 0) 2853 else 2939
        }
        2941 -> {
            f.dVar28 = f.fVar67
            2940
        }
        2942 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar62, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2777
        }
        2943 -> {
            f.fVar62 = f32(4.7)
            275
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep92(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2944 -> {
            f.fVar67 = f32(0.5)
            2943
        }
        2945 -> {
            if ((b(((b(((b((1.0) <= (f.fVar86))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b(((b(uLt(0x2a, ((readI32(f.param_1.plus(0x21c))) - (0x41))))) != 0) || ((b(((b((7.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0) || ((b((60.0) <= (f.fVar31))) != 0))) != 0))) != 0))) != 0) 2941 else 2944
        }
        2946 -> {
            if ((b((f.fVar72) < (12.0))) != 0) 2794 else 2945
        }
        2947 -> {
            260
        }
        2948 -> {
            f.fVar81 = f32(4.7)
            2947
        }
        2949 -> {
            f.fVar67 = f32(0.55)
            2948
        }
        2950 -> {
            352
        }
        2951 -> {
            f.fVar66 = f32(f32(f.dVar29))
            2950
        }
        2952 -> {
            writeF32(f.param_1.plus(400), f32(f32(f.dVar29)))
            2951
        }
        2953 -> {
            f.dVar29 = ((f.dVar29) * (f.dVar28))
            268
        }
        2954 -> {
            f.dVar29 = ((3.3) - (f.dVar75))
            267
        }
        2955 -> {
            f.dVar28 = ARR_0012f2d8[b((f.dVar28) < (f.dVar75))]
            2954
        }
        2956 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2955
        }
        2957 -> {
            269
        }
        2958 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            2957
        }
        2959 -> {
            if ((b(((b(((b((f.dVar75) <= (3.3))) != 0) || ((b((4.3) <= (f.dVar75))) != 0))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((b(((b((f.fVar76) <= (f.dVar29))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0))) != 0))) != 0) 2958 else 2956
        }
        2960 -> {
            f.dVar28 = DAT_0012f050
            2959
        }
        2961 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2960
        }
        2962 -> {
            f.dVar29 = DAT_0012ef70
            2961
        }
        2963 -> {
            f.fVar67 = f32(3.2)
            2962
        }
        2964 -> {
            if ((b((f.fVar71) <= (5.0))) != 0) 2963 else 2962
        }
        2965 -> {
            f.fVar67 = f32(3.5)
            2964
        }
        2966 -> {
            352
        }
        2967 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2966
        }
        2968 -> {
            if ((b(((b(((b(((b((DAT_0012f0b0) < (f.dVar36))) != 0) && ((b((0.5) < (f.fVar32))) != 0))) != 0) && ((b((15.0) < (f.fVar72))) != 0))) != 0) && ((b((9.0) < (f.fVar63))) != 0))) != 0) 2967 else 2965
        }
        2969 -> {
            275
        }
        2970 -> {
            f.fVar67 = f32(0.8)
            2969
        }
        2971 -> {
            if ((b(((b(((b(((b((4.5) < (f.fVar71))) != 0) && ((b(((b((f.fVar37) < (f.fVar71))) != 0) || ((b((f.fVar40) < (f.fVar71))) != 0))) != 0))) != 0) && ((b((0x6c0) < (f.param_14))) != 0))) != 0) && ((b(((b(((b((60.0) < (f.fVar48))) != 0) && ((b((f.fVar63) < (7.5))) != 0))) != 0) && ((b((f.fVar93) < (8.0))) != 0))) != 0))) != 0) 2970 else 2968
        }
        2972 -> {
            if ((b(((b(((b(((b((DAT_0012ef70) <= (f.fVar84))) != 0) || ((b((9.0) <= (f32((f.fVar72) - (f.param_6))))) != 0))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((f.fVar48) <= (72.0))) != 0))) != 0) 2971 else 2949
        }
        2973 -> {
            352
        }
        2974 -> {
            260
        }
        2975 -> {
            f.fVar67 = f32(0.45)
            2974
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep93(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        2976 -> {
            if ((b(((b(((b(((b((f.fVar73) < (2.0))) != 0) && ((b((f.fVar90) < (2.0))) != 0))) != 0) && ((b((f.fVar43) < (2.0))) != 0))) != 0) && ((b((54.0) < (f.fVar48))) != 0))) != 0) 2975 else 2973
        }
        2977 -> {
            f.fVar66 = f32(0.0)
            2976
        }
        2978 -> {
            260
        }
        2979 -> {
            f.fVar81 = f32(3.0)
            2978
        }
        2980 -> {
            f.fVar67 = f32(1.0)
            2979
        }
        2981 -> {
            if ((b(((b((((f.fVar37) + (-(3.0)))) < (0.5))) != 0) && ((b((((f.param_6) + (-(3.0)))) < (0.5))) != 0))) != 0) 2980 else 2973
        }
        2982 -> {
            265
        }
        2983 -> {
            f.fVar77 = f32(3.6)
            2982
        }
        2984 -> {
            f.fVar67 = f32(1.0)
            2983
        }
        2985 -> {
            if ((b(((b((f.fVar37) < (4.5))) != 0) && ((b((3.8) < (f.dVar89))) != 0))) != 0) 2984 else 2981
        }
        2986 -> {
            if ((b((1.0) < (f.fVar84))) != 0) 2985 else 2973
        }
        2987 -> {
            f.fVar66 = f32(0.0)
            2986
        }
        2988 -> {
            if ((b((3.9) < (f.dVar52))) != 0) 2977 else 2987
        }
        2989 -> {
            266
        }
        2990 -> {
            f.fVar42 = f32(((((12.0) - (f.fVar42))) - (f.fVar68)))
            2989
        }
        2991 -> {
            286
        }
        2992 -> {
            f.dVar30 = ((6.5) - (f.dVar30))
            2991
        }
        2993 -> {
            if ((b((13.5) <= (f.fVar72))) != 0) 2992 else 2990
        }
        2994 -> {
            if ((b(((b(((b(((b((f.fVar84) < (1.0))) != 0) && ((b((f.fVar67) < (1.0))) != 0))) != 0) && ((b((12.0) < (f.fVar72))) != 0))) != 0) && ((b((f.fVar31) < (96.0))) != 0))) != 0) 2993 else 2973
        }
        2995 -> {
            f.fVar66 = f32(0.0)
            2994
        }
        2996 -> {
            if ((b(((b((f.fVar42) < (6.5))) != 0) || ((b((7.0) <= (f.fVar42))) != 0))) != 0) 2988 else 2995
        }
        2997 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            2973
        }
        2998 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2997
        }
        2999 -> {
            f.fVar66 = f32(f32(((((3.3) - (f.dVar75))) * (0.6))))
            2998
        }
        3000 -> {
            281
        }
        3001 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            3000
        }
        3002 -> {
            if ((b(((b(((b((4.5) <= (f.fVar67))) != 0) || ((b((f.dVar75) <= (3.3))) != 0))) != 0) || ((b((f.fVar76) <= (DAT_0012ef70))) != 0))) != 0) 3001 else 2999
        }
        3003 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 3002 else 2973
        }
        3004 -> {
            f.param_5 = f32(f.auVar58)
            3003
        }
        3005 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.2, 1.35, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3004
        }
        3006 -> {
            f.fVar67 = f32(f.fVar71)
            3005
        }
        3007 -> {
            f.auVar58 = f32(f.param_5)
            3006
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep94(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3008 -> {
            f.fVar66 = f32(((f.fVar42) * (0.5)))
            2973
        }
        3009 -> {
            f.fVar42 = f32(((6.0) - (f.fVar42)))
            266
        }
        3010 -> {
            if ((b(((b((12.0) < (f.fVar72))) != 0) && ((b((f.fVar31) < (96.0))) != 0))) != 0) 3009 else 2973
        }
        3011 -> {
            f.fVar66 = f32(0.0)
            3010
        }
        3012 -> {
            if ((b(((b(((b((1.0) <= (f.fVar84))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) 3007 else 3011
        }
        3013 -> {
            if ((b(((b((f.fVar42) < (6.0))) != 0) || ((b((6.5) <= (f.fVar42))) != 0))) != 0) 2996 else 3012
        }
        3014 -> {
            if ((b(((b((6.0) <= (f.fVar42))) != 0) || ((b((f.dVar30) < (DAT_0012f150))) != 0))) != 0) 3013 else 2972
        }
        3015 -> {
            f.fVar67 = f32(0.5)
            2948
        }
        3016 -> {
            259
        }
        3017 -> {
            f.fVar67 = f32(1.2)
            3016
        }
        3018 -> {
            f.fVar81 = f32(2.8)
            3017
        }
        3019 -> {
            352
        }
        3020 -> {
            264
        }
        3021 -> {
            if ((b(((b(((b((f.iVar24) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (DAT_0012f030)) }) != 0))) != 0) 3020 else 3019
        }
        3022 -> {
            f.fVar26 = f32(f.fVar32)
            269
        }
        3023 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            3022
        }
        3024 -> {
            f.fVar66 = f32(f32(((f.dVar28) * (f.dVar30))))
            3019
        }
        3025 -> {
            writeF32(f.pjVar4, f32(f32(((f.dVar28) * (f.dVar30)))))
            3024
        }
        3026 -> {
            f.dVar30 = DAT_0012ef38
            3025
        }
        3027 -> {
            if ((b((DAT_0012f050) < (f.dVar29))) != 0) 3026 else 3025
        }
        3028 -> {
            f.dVar30 = DAT_0012ee70
            3027
        }
        3029 -> {
            f.dVar29 = f.dVar89
            3028
        }
        3030 -> {
            f.dVar28 = ((3.5) - (f.dVar89))
            3029
        }
        3031 -> {
            f.dVar29 = f.dVar75
            3028
        }
        3032 -> {
            f.dVar28 = ((DAT_0012f030) - (f.dVar75))
            3031
        }
        3033 -> {
            if ((b(((b((4.5) <= (f.fVar67))) != 0) || ((b((f.dVar75) <= (DAT_0012f030))) != 0))) != 0) 3030 else 3032
        }
        3034 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3033
        }
        3035 -> {
            if ((b(((b((f.fVar61) != (0.0))) != 0) || ((b(((b(((b(((b((DAT_0012f048) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (DAT_0012f030))) != 0))) != 0) && ((b(((b((4.3) <= (f.dVar89))) != 0) || ((b((f.dVar89) <= (DAT_0012f030))) != 0))) != 0))) != 0) || ((b(((b(((b((66.0) <= (f.fVar48))) != 0) || ((b((-(0.35)) <= (readF32(f.param_1.plus(0x270))))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0))) != 0))) != 0) 3023 else 3034
        }
        3036 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.2, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3035
        }
        3037 -> {
            f.fVar32 = f32(f.fVar26)
            3036
        }
        3038 -> {
            f.fVar67 = f32(f.fVar71)
            3037
        }
        3039 -> {
            if ((b(((b((f.fVar47) <= (2.0))) != 0) || ((b((f.fVar93) <= (8.5))) != 0))) != 0) 3038 else 3018
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep95(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3040 -> {
            275
        }
        3041 -> {
            f.fVar67 = f32(1.2)
            3040
        }
        3042 -> {
            if ((b(((b(((b(((b((f.fVar71) < (5.0))) != 0) && ((b((f.fVar43) < (1.5))) != 0))) != 0) && ((b((f.fVar90) < (1.5))) != 0))) != 0) && ((b(((b((f.fVar73) < (1.5))) != 0) && ((b((f.fVar32) < (DAT_0012f200))) != 0))) != 0))) != 0) 3041 else 3039
        }
        3043 -> {
            f.fVar81 = f32(3.5)
            3017
        }
        3044 -> {
            if ((b(((b(((b(((b((DAT_0012f060) < (f.dVar75))) != 0) && ((b((DAT_0012ee90) < (f.dVar38))) != 0))) != 0) && ((b((DAT_0012f040) < (f.fVar32))) != 0))) != 0) && ((b((7.0) < (f.fVar63))) != 0))) != 0) 3043 else 3017
        }
        3045 -> {
            if ((b(((b(((b(((b((1.0) <= (f.fVar43))) != 0) || ((b((1.0) <= (f.fVar90))) != 0))) != 0) || ((b(((b((f.fVar61) <= (3.0))) != 0) || ((b(((b((f.fVar40) <= (3.0))) != 0) || ((b((f.fVar37) <= (3.0))) != 0))) != 0))) != 0))) != 0) || ((b((1.0) <= (f.fVar73))) != 0))) != 0) 3042 else 3044
        }
        3046 -> {
            if ((b(((b(((b(((b((1.0) <= (f.fVar67))) != 0) || ((b((6.0) <= (f32((f.fVar72) - (f.fVar42))))) != 0))) != 0) || ((b((1.0) <= (f.fVar84))) != 0))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0) 3045 else 3015
        }
        3047 -> {
            if ((b(((b((f.fVar42) <= (4.5))) != 0) || ((b((DAT_0012f150) <= (f.dVar30))) != 0))) != 0) 3014 else 3046
        }
        3048 -> {
            f.fVar62 = f32(f.fVar71)
            3047
        }
        3049 -> {
            if ((b(((b((f.fVar42) < (3.0))) != 0) || ((b((3.8) < (f.dVar30))) != 0))) != 0) 3048 else 2946
        }
        3050 -> {
            286
        }
        3051 -> {
            f.dVar30 = ((3.8) - (f.dVar30))
            3050
        }
        3052 -> {
            if ((b((((f.dVar30) + (-(3.8)))) < (0.5))) != 0) 3051 else 2777
        }
        3053 -> {
            f.fVar66 = f32(0.0)
            3052
        }
        3054 -> {
            264
        }
        3055 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((readF32(f.pjVar4)) + (f.param_4))) <= (f.fVar62)) }) != 0))) != 0) 3054 else 2777
        }
        3056 -> {
            268
        }
        3057 -> {
            f.dVar29 = ((f.dVar29) * (f.dVar28))
            3056
        }
        3058 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3057
        }
        3059 -> {
            f.dVar29 = ((DAT_0012f030) - (f.dVar75))
            3058
        }
        3060 -> {
            f.dVar28 = ARR_0012f2d8[b((DAT_0012f050) < (f.dVar75))]
            3059
        }
        3061 -> {
            if ((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b((f.fVar32) < (4.5))) != 0))) != 0) && ((b(((b((DAT_0012f030) < (f.dVar75))) != 0) && ((b(((b(((b((11.0) < (f.param_12))) != 0) && ((b((1.0) < (f.fVar47))) != 0))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0))) != 0) 3060 else 3055
        }
        3062 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3061
        }
        3063 -> {
            f.fVar32 = f32(f.fVar71)
            3062
        }
        3064 -> {
            f.fVar67 = f32(3.2)
            3063
        }
        3065 -> {
            if ((b((f.fVar48) <= (48.0))) != 0) 3064 else 3063
        }
        3066 -> {
            f.fVar67 = f32(3.5)
            3065
        }
        3067 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.8, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            2777
        }
        3068 -> {
            if ((b(((b(((b(((b((f.fVar63) <= (8.5))) != 0) && ((b((f.fVar93) <= (8.5))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012ee90))) != 0))) != 0) || ((b(((b((DAT_0012f048) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (DAT_0012f068))) != 0))) != 0))) != 0) 3066 else 3067
        }
        3069 -> {
            259
        }
        3070 -> {
            f.fVar67 = f32(1.0)
            3069
        }
        3071 -> {
            f.fVar81 = f32(3.5)
            3070
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep96(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3072 -> {
            f.fVar81 = f32(3.2)
            3070
        }
        3073 -> {
            if ((b(((b(((b((f.dVar38) <= (1.2))) != 0) || ((b(((b((42.0) <= (f.fVar48))) != 0) && ((b((0.3) <= (f.fVar85))) != 0))) != 0))) != 0) || ((b(((b((f.fVar63) <= (7.0))) != 0) && ((b((f.fVar93) <= (7.0))) != 0))) != 0))) != 0) 3071 else 3072
        }
        3074 -> {
            f.fVar81 = f32(3.0)
            3070
        }
        3075 -> {
            259
        }
        3076 -> {
            f.fVar81 = f32(2.8)
            3075
        }
        3077 -> {
            f.fVar67 = f32(1.2)
            3076
        }
        3078 -> {
            if ((b(((b((2.0) < (f.fVar47))) != 0) && ((b(((b((f.dVar75) < (DAT_0012f030))) != 0) || ((b(((b(((b((9.0) < (f.fVar63))) != 0) && ((b((f.fVar71) < (3.5))) != 0))) != 0) && ((b((9.0) < (f.fVar93))) != 0))) != 0))) != 0))) != 0) 3077 else 3074
        }
        3079 -> {
            if ((b(((b((f.dVar39) <= (8.2))) != 0) || ((b(((b((f.dVar36) <= (8.2))) != 0) || ((b((72.0) <= (f.fVar31))) != 0))) != 0))) != 0) 3073 else 3078
        }
        3080 -> {
            if ((b(((b((54.0) < (f.fVar48))) != 0) || ((b(((b((f.fVar63) < (7.5))) != 0) || ((b((f.fVar93) < (7.5))) != 0))) != 0))) != 0) 3079 else 3068
        }
        3081 -> {
            303
        }
        3082 -> {
            f.bVar12 = b((0) != 0)
            3081
        }
        3083 -> {
            f.bVar13 = b((b((f.fVar42) == (f.fVar62))) != 0)
            3082
        }
        3084 -> {
            f.bVar16 = b((b((f.fVar42) < (f.fVar62))) != 0)
            3083
        }
        3085 -> {
            if ((b(((b(!((b((f.fVar42).isNaN())) != 0))) != 0) && ((b(!((b((f.fVar62).isNaN())) != 0))) != 0))) != 0) 3084 else 3081
        }
        3086 -> {
            f.bVar12 = b((1) != 0)
            3085
        }
        3087 -> {
            f.bVar13 = b((0) != 0)
            3086
        }
        3088 -> {
            f.bVar16 = b((0) != 0)
            3087
        }
        3089 -> {
            f.fVar42 = f32(f32((f.fVar66) + (f.param_4)))
            278
        }
        3090 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            3089
        }
        3091 -> {
            if ((b((f.iVar24) == (1))) != 0) 3090 else 2777
        }
        3092 -> {
            f.fVar66 = f32(f.fVar61)
            3091
        }
        3093 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            277
        }
        3094 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            2777
        }
        3095 -> {
            writeF32(f.param_1.plus(400), f32(f.fVar66))
            3094
        }
        3096 -> {
            f.fVar66 = f32(f32(((f.dVar28) * (f32((f.fVar62) - (f.fVar67))))))
            3095
        }
        3097 -> {
            f.dVar28 = DAT_0012ee70
            3096
        }
        3098 -> {
            if ((b((f.dVar75) <= (3.3))) != 0) 3097 else 3096
        }
        3099 -> {
            f.dVar28 = 0.5
            3098
        }
        3100 -> {
            if ((b(((b(((b((f.fVar67) <= (f.fVar62))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((b(((b((3.9) <= (f.dVar75))) != 0) || ((b(((b((f.fVar76) <= (DAT_0012ef70))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (f.fVar62))) != 0))) != 0))) != 0))) != 0) 276 else 3099
        }
        3101 -> {
            f.param_5 = f32(f.auVar55)
            3100
        }
        3102 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3101
        }
        3103 -> {
            f.fVar67 = f32(f.fVar71)
            3102
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep97(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3104 -> {
            f.auVar55 = f32(f.param_5)
            3103
        }
        3105 -> {
            if ((b(((b((f.fVar93) <= (8.5))) != 0) || ((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0))) != 0) 3080 else 3104
        }
        3106 -> {
            f.fVar66 = f32(f32(f.dVar28))
            2777
        }
        3107 -> {
            writeF32(f.param_1.plus(400), f32(f32(f.dVar28)))
            3106
        }
        3108 -> {
            f.dVar28 = ((((3.3) - (f.dVar75))) * (f.dVar28))
            279
        }
        3109 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3108
        }
        3110 -> {
            f.dVar28 = DAT_0012ee70
            3109
        }
        3111 -> {
            if ((b((f.dVar75) <= (DAT_0012f050))) != 0) 3110 else 3109
        }
        3112 -> {
            f.dVar28 = 0.5
            3111
        }
        3113 -> {
            281
        }
        3114 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            3113
        }
        3115 -> {
            if ((b(((b(((b(((b((f.dVar75) <= (3.3))) != 0) || ((b((4.3) <= (f.dVar75))) != 0))) != 0) || ((b((DAT_0012f048) <= (f.dVar30))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0) 3114 else 3112
        }
        3116 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 3115 else 2777
        }
        3117 -> {
            f.param_5 = f32(f.auVar53)
            3116
        }
        3118 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 1.0, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3117
        }
        3119 -> {
            336
        }
        3120 -> {
            f.fVar77 = f32(4.3)
            3119
        }
        3121 -> {
            f.fVar67 = f32(0.55)
            3120
        }
        3122 -> {
            if ((b(((b(((b((f.fVar72) < (13.0))) != 0) && ((b((f.fVar31) < (54.0))) != 0))) != 0) && ((b(((b((f.fVar72) < (10.0))) != 0) || ((b((f.fVar63) < (7.5))) != 0))) != 0))) != 0) 3121 else 3118
        }
        3123 -> {
            f.auVar53 = f32(f.param_5)
            3122
        }
        3124 -> {
            260
        }
        3125 -> {
            f.fVar81 = f32(4.7)
            3124
        }
        3126 -> {
            f.fVar67 = f32(0.5)
            3125
        }
        3127 -> {
            if ((b(((b((72.0) < (f.fVar48))) != 0) && ((b(((b((f.fVar72) < (10.0))) != 0) || ((b((f.fVar63) < (7.5))) != 0))) != 0))) != 0) 3126 else 3123
        }
        3128 -> {
            if ((b(((b((f32((f.fVar72) - (f.fVar42))) < (8.0))) != 0) && ((b((f.fVar31) < (72.0))) != 0))) != 0) 3127 else 3123
        }
        3129 -> {
            if ((b(((b(((b((1.0) <= (f.fVar86))) != 0) || ((b((1.0) <= (f.fVar67))) != 0))) != 0) || ((b((readI32(f.param_1.plus(0x21c))) < (0x3d))) != 0))) != 0) 3105 else 3128
        }
        3130 -> {
            if ((b((f.fVar72) <= (12.0))) != 0) 3053 else 3129
        }
        3131 -> {
            if ((b(((b((4.5) <= (f.fVar42))) != 0) || ((b((f.dVar30) <= (3.8))) != 0))) != 0) 3049 else 3130
        }
        3132 -> {
            281
        }
        3133 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            3132
        }
        3134 -> {
            280
        }
        3135 -> {
            f.dVar28 = 3.3
            3134
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep98(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3136 -> {
            if ((b(((b(((b((3.3) < (f.dVar75))) != 0) && ((b((f.dVar75) < (4.6))) != 0))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 3135 else 3133
        }
        3137 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 3136 else 2777
        }
        3138 -> {
            f.param_5 = f32(f.auVar54)
            3137
        }
        3139 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3138
        }
        3140 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            2777
        }
        3141 -> {
            if ((b((f.iVar24) == (1))) != 0) 3140 else 2777
        }
        3142 -> {
            f.iVar24 = readI32(f.param_1.plus(0x18c))
            281
        }
        3143 -> {
            279
        }
        3144 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3143
        }
        3145 -> {
            f.dVar28 = ((((f.dVar28) - (f.dVar75))) * (0.6))
            3144
        }
        3146 -> {
            f.dVar28 = 2.3
            280
        }
        3147 -> {
            if ((b(((b(((b((2.3) < (f.dVar75))) != 0) && ((b((f.dVar75) < (DAT_0012f050))) != 0))) != 0) && ((b((2.3) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 3146 else 3142
        }
        3148 -> {
            if ((b((f.fVar66) == (0.0))) != 0) 3147 else 2777
        }
        3149 -> {
            f.param_5 = f32(f.auVar54)
            3148
        }
        3150 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3149
        }
        3151 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x1c4))) <= (0.5))) != 0) || ((b(((b((f32((f.fVar72) - (f.fVar42))) <= (8.0))) != 0) && ((b((f.fVar32) <= (DAT_0012ef70))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (2.3))) != 0))) != 0) 3139 else 3150
        }
        3152 -> {
            f.auVar54 = f32(f.param_5)
            3151
        }
        3153 -> {
            if ((b((10.0) < (f.fVar72))) != 0) 3152 else 2777
        }
        3154 -> {
            f.fVar66 = f32(0.0)
            3153
        }
        3155 -> {
            260
        }
        3156 -> {
            f.fVar81 = f32(4.7)
            3155
        }
        3157 -> {
            f.fVar67 = f32(0.5)
            3156
        }
        3158 -> {
            if ((b(((b(((b(((b((f.fVar86) < (1.0))) != 0) && ((b((f.fVar67) < (1.0))) != 0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (6.0))) != 0))) != 0) && ((b((0x3c) < (readI32(f.param_1.plus(0x21c))))) != 0))) != 0) 3157 else 3154
        }
        3159 -> {
            278
        }
        3160 -> {
            f.fVar42 = f32(f32((f.fVar42) + (f.fVar66)))
            3159
        }
        3161 -> {
            f.fVar66 = f32(readF32(f.pjVar4))
            3160
        }
        3162 -> {
            if ((b((readI32(f.param_1.plus(0x18c))) == (1))) != 0) 3161 else 2777
        }
        3163 -> {
            f.fVar66 = f32(f.fVar61)
            3162
        }
        3164 -> {
            285
        }
        3165 -> {
            f.dVar28 = ((((3.3) - (f.dVar75))) * (f.dVar28))
            3164
        }
        3166 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3165
        }
        3167 -> {
            f.dVar28 = DAT_0012ee70
            3166
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep99(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3168 -> {
            if ((b((f.dVar75) <= (DAT_0012f050))) != 0) 3167 else 3166
        }
        3169 -> {
            f.dVar28 = 0.5
            3168
        }
        3170 -> {
            if ((b(((b((f.fVar61) == (0.0))) != 0) && ((b(((b(((b((3.5) < (f.fVar71))) != 0) && ((b((f.dVar75) < (4.6))) != 0))) != 0) && ((b((3.3) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 3169 else 3163
        }
        3171 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.2, 1.1, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3170
        }
        3172 -> {
            284
        }
        3173 -> {
            f.dVar51 = f.dVar29
            3172
        }
        3174 -> {
            if ((b((f.dVar75) <= (f.dVar30))) != 0) 3173 else 3172
        }
        3175 -> {
            f.dVar51 = 0.6
            3174
        }
        3176 -> {
            f.dVar28 = ((2.3) - (f.dVar75))
            3175
        }
        3177 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3176
        }
        3178 -> {
            if ((b(((b(((b((f.fVar71) < (4.0))) != 0) && ((b((f.fVar61) == (0.0))) != 0))) != 0) && ((b(((b((f.fVar32) < (f.fVar71))) != 0) && ((b((2.3) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 3177 else 3163
        }
        3179 -> {
            f.dVar29 = DAT_0012ee70
            3178
        }
        3180 -> {
            f.dVar30 = DAT_0012f068
            3179
        }
        3181 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.3, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3180
        }
        3182 -> {
            283
        }
        3183 -> {
            if ((b(((b((f.fVar63) <= (8.0))) != 0) || ((b(((b((42.0) <= (f.fVar48))) != 0) && ((b(((b((54.0) <= (f.fVar48))) != 0) || ((b((f.fVar63) <= (9.0))) != 0))) != 0))) != 0))) != 0) 3182 else 3181
        }
        3184 -> {
            if ((b((readF32(f.param_1.plus(0x1c4))) <= (DAT_0012ef38))) != 0) 283 else 3183
        }
        3185 -> {
            282
        }
        3186 -> {
            f.fVar67 = f32(4.2)
            3185
        }
        3187 -> {
            f.fVar32 = f32(0.55)
            3186
        }
        3188 -> {
            if ((b(((b(((b((f.fVar86) < (1.0))) != 0) && ((b((f32((f.fVar72) - (f.fVar42))) < (7.0))) != 0))) != 0) && ((b(((b((f.fVar67) < (1.0))) != 0) && ((b((72.0) < (f.fVar48))) != 0))) != 0))) != 0) 3187 else 3184
        }
        3189 -> {
            352
        }
        3190 -> {
            f.fVar66 = f32(((((((6.0) - (f.fVar42))) - (f.fVar68))) * (0.5)))
            3189
        }
        3191 -> {
            if ((b((f.param_14) < (0x360))) != 0) 3190 else 3188
        }
        3192 -> {
            264
        }
        3193 -> {
            if ((b(((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b((f.fVar61) != (0.0))) != 0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((f.fVar42) + (readF32(f.pjVar4)))) <= (f.fVar32)) }) != 0))) != 0) 3192 else 2777
        }
        3194 -> {
            284
        }
        3195 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3194
        }
        3196 -> {
            f.dVar28 = ((2.5) - (f.dVar75))
            3195
        }
        3197 -> {
            f.dVar51 = ARR_0012f2d8[b((2.8) < (f.dVar75))]
            3196
        }
        3198 -> {
            if ((b(((b(((b((f.fVar61) == (0.0))) != 0) && ((b((f.fVar32) < (f.fVar71))) != 0))) != 0) && ((b(((b((f.dVar75) < (DAT_0012f050))) != 0) && ((b((f.fVar32) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 3197 else 3193
        }
        3199 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 2.5, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3198
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep100(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3200 -> {
            264
        }
        3201 -> {
            if ((b(((b((readI32(f.param_1.plus(0x18c))) != (1))) != 0) || ((b(((b((f.fVar61) != (0.0))) != 0) || ((run { run { f.fVar66 = f32(readF32(f.pjVar4)); f32(readF32(f.pjVar4)) }; b((f32((f.fVar42) + (readF32(f.pjVar4)))) <= (f.fVar62)) }) != 0))) != 0))) != 0) 3200 else 2777
        }
        3202 -> {
            f.fVar66 = f32(f32(f.dVar28))
            2777
        }
        3203 -> {
            writeF32(f.param_1.plus(400), f32(f32(f.dVar28)))
            3202
        }
        3204 -> {
            f.dVar28 = ((f.dVar28) * (f.dVar51))
            285
        }
        3205 -> {
            f.dVar51 = ARR_0012f2d8[b((f.dVar29) < (f.dVar75))]
            284
        }
        3206 -> {
            f.dVar28 = ((3.3) - (f.dVar75))
            3205
        }
        3207 -> {
            writeI32(f.param_1.plus(0x18c), 1)
            3206
        }
        3208 -> {
            if ((b(((b(((b((f.fVar61) != (0.0))) != 0) || ((b((4.6) <= (f.dVar75))) != 0))) != 0) || ((b(((b((f.dVar75) <= (DAT_0012f030))) != 0) || ((b(((b((f.fVar76) <= (DAT_0012ef70))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.3))) != 0))) != 0))) != 0))) != 0) 3201 else 3207
        }
        3209 -> {
            f.dVar29 = DAT_0012f050
            3208
        }
        3210 -> {
            f.fVar61 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.0, 1.2, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3209
        }
        3211 -> {
            if ((b((0.5) < (readF32(f.param_1.plus(0x1c4))))) != 0) 3199 else 3210
        }
        3212 -> {
            if ((b(((b(((b(((b((f.fVar93) <= (9.0))) != 0) || ((b((f32((f.fVar72) - (f.fVar42))) <= (6.0))) != 0))) != 0) || ((b((60.0) <= (f.fVar48))) != 0))) != 0) || ((b((f.fVar63) <= (9.0))) != 0))) != 0) 3191 else 3211
        }
        3213 -> {
            352
        }
        3214 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3213
        }
        3215 -> {
            f.fVar67 = f32(2.8)
            282
        }
        3216 -> {
            f.fVar32 = f32(1.3)
            3215
        }
        3217 -> {
            if ((b(((b(((b((2.5) < (f.fVar35))) != 0) && ((b((DAT_0012f0b0) < (f.dVar39))) != 0))) != 0) && ((b((0.6) < (readF32(f.param_1.plus(0x1c4))))) != 0))) != 0) 3216 else 3212
        }
        3218 -> {
            f.fVar32 = f32(2.5)
            3217
        }
        3219 -> {
            336
        }
        3220 -> {
            f.fVar67 = f32(1.3)
            3219
        }
        3221 -> {
            f.fVar77 = f32(3.0)
            3220
        }
        3222 -> {
            f.fVar77 = f32(3.3)
            3220
        }
        3223 -> {
            if ((b(((b((f.fVar48) <= (48.0))) != 0) || ((b((f.fVar66) <= (1.0))) != 0))) != 0) 3221 else 3222
        }
        3224 -> {
            f.auVar53 = f32(f.param_5)
            3223
        }
        3225 -> {
            if ((b(((b((DAT_0012f218) < (f.dVar45))) != 0) && ((b((f.dVar49) < (DAT_0012f2b8))) != 0))) != 0) 3224 else 3218
        }
        3226 -> {
            234
        }
        3227 -> {
            f.dVar30 = ((3.5) - (f.param_6))
            3226
        }
        3228 -> {
            f.dVar28 = 0.6
            3227
        }
        3229 -> {
            if ((b((((f.param_6) + (-(3.5)))) < (0.5))) != 0) 3228 else 2777
        }
        3230 -> {
            f.fVar66 = f32(0.0)
            3229
        }
        3231 -> {
            f.fVar66 = f32(f32(((DAT_0012f060) - (f.param_6))))
            2777
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep101(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3232 -> {
            if ((b((((f.param_6) + (DAT_0012f288))) < (0.5))) != 0) 3231 else 2777
        }
        3233 -> {
            f.fVar66 = f32(0.0)
            3232
        }
        3234 -> {
            f.fVar66 = f32(((f32((f.fVar61) - (f.fVar68))) * (0.5)))
            2777
        }
        3235 -> {
            if ((b((3.0) <= (f.param_6))) != 0) 3234 else 2777
        }
        3236 -> {
            f.fVar66 = f32(0.0)
            3235
        }
        3237 -> {
            266
        }
        3238 -> {
            f.fVar42 = f32(((3.0) - (f.fVar42)))
            3237
        }
        3239 -> {
            if ((b((((f.fVar42) + (-(3.0)))) < (0.5))) != 0) 3238 else 2777
        }
        3240 -> {
            f.fVar66 = f32(0.0)
            3239
        }
        3241 -> {
            222
        }
        3242 -> {
            if ((b((((f.param_6) + (-(3.0)))) < (0.5))) != 0) 3241 else 3240
        }
        3243 -> {
            if ((b((f32((f.fVar61) - (f.fVar68))) <= (0.0))) != 0) 3236 else 3242
        }
        3244 -> {
            f.fVar61 = f32(((6.0) - (f.fVar42)))
            3243
        }
        3245 -> {
            if ((b((f.fVar84) <= (1.0))) != 0) 3233 else 3244
        }
        3246 -> {
            230
        }
        3247 -> {
            f.dVar30 = ((((4.5) - (f.dVar52))) * (0.5))
            3246
        }
        3248 -> {
            f.dVar30 = ((((DAT_0012f030) - (f.dVar30))) * (0.5))
            3246
        }
        3249 -> {
            266
        }
        3250 -> {
            f.fVar42 = f32(((((6.0) - (f.param_6))) - (f.fVar42)))
            3249
        }
        3251 -> {
            if ((b((((f.param_6) + (DAT_0012f0a0))) < (0.5))) != 0) 3250 else 3248
        }
        3252 -> {
            if ((b((f.fVar84) < (1.0))) != 0) 3247 else 3251
        }
        3253 -> {
            if ((b((0.5) <= (kotlin.math.abs(f32((f.fVar68) - (f.fVar42)))))) != 0) 3252 else 3245
        }
        3254 -> {
            if ((b((2.5) <= (f.fVar42))) != 0) 3230 else 3253
        }
        3255 -> {
            if ((b((12.0) < (f.fVar72))) != 0) 3225 else 3254
        }
        3256 -> {
            if ((b(uLt(((f.param_14) - (0x241)), 0x11f))) != 0) 3158 else 3255
        }
        3257 -> {
            if ((b((3.0) <= (f.fVar42))) != 0) 3131 else 3256
        }
        3258 -> {
            f.fVar86 = f32(kotlin.math.abs(f32((f.fVar68) - (f.fVar33))))
            3257
        }
        3259 -> {
            f.fVar62 = f32(3.0)
            3258
        }
        3260 -> {
            if ((b((12.0) <= (f.fVar72))) != 0) 3259 else 2776
        }
        3261 -> {
            if ((b((84.0) <= (f.fVar31))) != 0) 2056 else 3260
        }
        3262 -> {
            f.fVar81 = f32(f.fVar71)
            3261
        }
        3263 -> {
            f.dVar82 = f.fVar61
            3262
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep102(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3264 -> {
            if ((b((f.fVar31) <= (0.0))) != 0) 1787 else 3263
        }
        3265 -> {
            f.dVar78 = f.fVar25
            3264
        }
        3266 -> {
            f.dVar87 = DAT_0012ef70
            3265
        }
        3267 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar77, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            352
        }
        3268 -> {
            f.fVar77 = f32(3.2)
            351
        }
        3269 -> {
            f.fVar67 = f32(1.2)
            3268
        }
        3270 -> {
            f.fVar67 = f32(1.0)
            351
        }
        3271 -> {
            f.fVar67 = f32(1.2)
            351
        }
        3272 -> {
            336
        }
        3273 -> {
            f.fVar67 = f32(1.0)
            3272
        }
        3274 -> {
            f.fVar77 = f32(f32(((f.dVar75) + (0.3))))
            3273
        }
        3275 -> {
            f.fVar77 = f32(3.5)
            3272
        }
        3276 -> {
            f.fVar67 = f32(1.2)
            3275
        }
        3277 -> {
            if ((b((3.0) <= (f.fVar71))) != 0) 3274 else 3276
        }
        3278 -> {
            f.auVar53 = f32(f.param_5)
            3277
        }
        3279 -> {
            if ((b(((b(((b((f.dVar45) <= (DAT_0012f230))) != 0) || ((b((-(0.3)) <= (f.dVar49))) != 0))) != 0) || ((b((DAT_0012f228) <= (f.fVar69))) != 0))) != 0) 3278 else 3271
        }
        3280 -> {
            if ((b(((b((f.fVar88) < (1.5))) != 0) || ((b(((b((f.dVar45) < (0.3))) != 0) && ((b((f.fVar69) < (DAT_0012f178))) != 0))) != 0))) != 0) 3270 else 3279
        }
        3281 -> {
            if ((b((f.fVar77) < (3.0))) != 0) 3269 else 3280
        }
        3282 -> {
            249
        }
        3283 -> {
            if ((b(((b(((b((0.35) <= (f.dVar45))) != 0) || ((b((f.fVar91) <= (7.5))) != 0))) != 0) || ((b((DAT_0012f2a8) <= (f.fVar91))) != 0))) != 0) 3282 else 3281
        }
        3284 -> {
            336
        }
        3285 -> {
            f.auVar53 = f32(f.param_5)
            3284
        }
        3286 -> {
            f.fVar67 = f32(1.0)
            349
        }
        3287 -> {
            f.fVar77 = f32(3.5)
            3286
        }
        3288 -> {
            if ((b(((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) || ((b((DAT_0012f048) <= (f.dVar29))) != 0))) != 0) || ((b(((b((f.dVar38) <= (0.7))) != 0) || ((b(((b(((b((10.0) <= (f.fVar72))) != 0) || ((b((f.fVar93) <= (7.0))) != 0))) != 0) || ((run { run { f.fVar77 = f32(f.fVar71); f32(f.fVar71) }; b((60.0) <= (f.fVar31)) }) != 0))) != 0))) != 0))) != 0) 3287 else 3286
        }
        3289 -> {
            342
        }
        3290 -> {
            f.fVar32 = f32(0.85)
            3289
        }
        3291 -> {
            if ((b(((b(((b((3.5) < (f.fVar71))) != 0) && ((b((f.fVar43) < (0.5))) != 0))) != 0) && ((b(((b((f.fVar90) < (0.5))) != 0) && ((b((f.fVar73) < (0.5))) != 0))) != 0))) != 0) 3290 else 3288
        }
        3292 -> {
            f.fVar67 = f32(0.85)
            349
        }
        3293 -> {
            347
        }
        3294 -> {
            f.fVar67 = f32(0.85)
            3293
        }
        3295 -> {
            if ((b((4.1) <= (f.dVar87))) != 0) 3294 else 3292
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep103(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3296 -> {
            344
        }
        3297 -> {
            f.fVar67 = f32(0.85)
            3296
        }
        3298 -> {
            if ((b(((b(((b(((b((f.fVar73) < (0.5))) != 0) && ((b((f.fVar90) < (0.5))) != 0))) != 0) && ((b((f.fVar43) < (0.5))) != 0))) != 0) && ((b((f.fVar77) < (f.fVar71))) != 0))) != 0) 3297 else 3295
        }
        3299 -> {
            f.fVar67 = f32(1.0)
            349
        }
        3300 -> {
            348
        }
        3301 -> {
            if ((b(((b(((b((f.fVar73) < (0.5))) != 0) && ((b((f.fVar90) < (0.5))) != 0))) != 0) && ((b(((b((f.fVar43) < (0.5))) != 0) && ((b(((b((1.5) < (f.fVar47))) != 0) || ((b((f.fVar77) < (f.fVar71))) != 0))) != 0))) != 0))) != 0) 3300 else 3299
        }
        3302 -> {
            if ((b(((b(((b((DAT_0012ee78) <= (f.fVar43))) != 0) || ((b((DAT_0012ee78) <= (f.fVar73))) != 0))) != 0) || ((b((DAT_0012ee78) <= (f.fVar90))) != 0))) != 0) 3298 else 3301
        }
        3303 -> {
            f.fVar77 = f32(3.5)
            349
        }
        3304 -> {
            f.fVar67 = f32(1.0)
            347
        }
        3305 -> {
            f.fVar77 = f32(f.fVar71)
            349
        }
        3306 -> {
            f.fVar67 = f32(0.85)
            3305
        }
        3307 -> {
            if ((b(((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((0.5) <= (f.fVar43))) != 0))) != 0) || ((b(((b((0.5) <= (f.fVar90))) != 0) || ((b((0.5) <= (f.fVar73))) != 0))) != 0))) != 0) 3304 else 348
        }
        3308 -> {
            if ((b(((b(((b((DAT_0012f098) <= (f.dVar75))) != 0) || ((b((f.dVar38) <= (1.2))) != 0))) != 0) || ((b((f.dVar87) <= (DAT_0012f048))) != 0))) != 0) 3302 else 3307
        }
        3309 -> {
            if ((b(((b(((b((f.dVar49) <= (-(0.35)))) != 0) || ((b((8.0) <= (f.fVar63))) != 0))) != 0) || ((b((1.0) <= (f.fVar88))) != 0))) != 0) 3291 else 3308
        }
        3310 -> {
            352
        }
        3311 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3310
        }
        3312 -> {
            f.fVar32 = f32(0.55)
            3311
        }
        3313 -> {
            349
        }
        3314 -> {
            f.fVar77 = f32(3.2)
            3313
        }
        3315 -> {
            f.fVar67 = f32(0.55)
            3314
        }
        3316 -> {
            f.fVar77 = f32(3.5)
            3313
        }
        3317 -> {
            f.fVar67 = f32(0.55)
            3316
        }
        3318 -> {
            if ((b(((b((10.0) <= (f.fVar72))) != 0) || ((b((0.35) <= (f.fVar32))) != 0))) != 0) 3315 else 3317
        }
        3319 -> {
            if ((b((f.fVar71) <= (3.0))) != 0) 3318 else 3312
        }
        3320 -> {
            352
        }
        3321 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3320
        }
        3322 -> {
            f.fVar67 = f32(0.55)
            3321
        }
        3323 -> {
            349
        }
        3324 -> {
            f.fVar77 = f32(3.9)
            3323
        }
        3325 -> {
            f.fVar67 = f32(0.55)
            3324
        }
        3326 -> {
            f.fVar77 = f32(3.5)
            3323
        }
        3327 -> {
            f.fVar67 = f32(0.85)
            3326
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep104(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3328 -> {
            if ((b(((b(((b((f.dVar38) <= (DAT_0012ee80))) != 0) || ((b((3.9) <= (f.dVar75))) != 0))) != 0) || ((b((f.fVar35) <= (DAT_0012ee90))) != 0))) != 0) 3325 else 3327
        }
        3329 -> {
            if ((b(((b(((b((f.dVar75) <= (3.8))) != 0) || ((b((f.fVar47) <= (1.0))) != 0))) != 0) || ((b((f.fVar93) <= (7.5))) != 0))) != 0) 345 else 346
        }
        3330 -> {
            f.fVar67 = f32(0.65)
            3321
        }
        3331 -> {
            345
        }
        3332 -> {
            if ((b(((b(((b(((b((f.dVar36) <= (DAT_0012ef68))) != 0) || ((b((f.dVar39) <= (DAT_0012ef68))) != 0))) != 0) || ((b(((b((3.5) <= (f.fVar71))) != 0) || ((b((f.fVar48) <= (54.0))) != 0))) != 0))) != 0) || ((b(((b(((b((3.0) <= (f.fVar37))) != 0) && ((b((3.0) <= (f.fVar40))) != 0))) != 0) && ((b((3.0) <= (f.fVar61))) != 0))) != 0))) != 0) 3331 else 3330
        }
        3333 -> {
            346
        }
        3334 -> {
            if ((b(((b((3.8) < (f.dVar75))) != 0) && ((b((1.0) < (f.fVar47))) != 0))) != 0) 3333 else 3332
        }
        3335 -> {
            if ((b((f.dVar39) <= (DAT_0012ef68))) != 0) 3329 else 3334
        }
        3336 -> {
            324
        }
        3337 -> {
            f.fVar67 = f32(0.85)
            3336
        }
        3338 -> {
            f.fVar62 = f32(3.5)
            3337
        }
        3339 -> {
            if ((b(((b((60.0) < (f.fVar48))) != 0) && ((b((7.5) < (f.fVar93))) != 0))) != 0) 3338 else 3337
        }
        3340 -> {
            if ((b(((b(((b((3.9) < (f.dVar75))) != 0) && ((b((f.fVar43) < (0.85))) != 0))) != 0) && ((b(((b((f.fVar90) < (0.85))) != 0) && ((b((f.fVar73) < (0.85))) != 0))) != 0))) != 0) 3339 else 3335
        }
        3341 -> {
            352
        }
        3342 -> {
            f.param_5 = f32(f.auVar50)
            3341
        }
        3343 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar71, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3342
        }
        3344 -> {
            f.auVar50 = f32(f.param_5)
            3343
        }
        3345 -> {
            f.fVar67 = f32(0.85)
            344
        }
        3346 -> {
            f.fVar67 = f32(1.0)
            344
        }
        3347 -> {
            336
        }
        3348 -> {
            f.auVar53 = f32(f.param_5)
            3347
        }
        3349 -> {
            f.fVar67 = f32(1.0)
            3348
        }
        3350 -> {
            f.fVar77 = f32(f.fVar71)
            343
        }
        3351 -> {
            if ((b(((b((f.dVar75) < (DAT_0012f080))) != 0) && ((b(((b(((b(((b((9.0) < (f.fVar63))) != 0) && ((b((DAT_0012eef8) < (f.fVar32))) != 0))) != 0) && ((b((9.0) < (f.fVar93))) != 0))) != 0) && ((b((3.0) < (f.fVar77))) != 0))) != 0))) != 0) 3350 else 343
        }
        3352 -> {
            if ((b(((b((f.fVar71) <= (3.0))) != 0) || ((b(((b((f.fVar32) <= (DAT_0012ef38))) != 0) && ((b(((b((f.dVar38) <= (DAT_0012ee90))) != 0) || ((b((f.fVar32) <= (0.35))) != 0))) != 0))) != 0))) != 0) 3351 else 3346
        }
        3353 -> {
            if ((b(((b((f.iVar20) == (1))) != 0) || ((b(((b((f.fVar73) < (0.5))) != 0) && ((b(((b((f.fVar90) < (0.5))) != 0) && ((b(((b((f.fVar43) < (0.5))) != 0) && ((b((f.fVar77) < (f.fVar71))) != 0))) != 0))) != 0))) != 0))) != 0) 3345 else 3352
        }
        3354 -> {
            352
        }
        3355 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar77, f.fVar67, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3354
        }
        3356 -> {
            f.fVar67 = f32(1.8)
            3355
        }
        3357 -> {
            324
        }
        3358 -> {
            f.fVar67 = f32(1.0)
            3357
        }
        3359 -> {
            f.fVar62 = f32(((f32((f.fVar71) + (f.fVar77))) * (0.5)))
            3357
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep105(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3360 -> {
            f.fVar67 = f32(1.8)
            3359
        }
        3361 -> {
            if ((b(((b((2.8) <= (f.dVar75))) != 0) || ((b((4.0) <= (f.fVar77))) != 0))) != 0) 3358 else 3360
        }
        3362 -> {
            if ((b(((b((DAT_0012f038) <= (f.dVar75))) != 0) || ((b(((b((9.5) <= (f.fVar72))) != 0) || ((b((3.5) <= (f.fVar77))) != 0))) != 0))) != 0) 3361 else 3356
        }
        3363 -> {
            f.fVar67 = f32(1.0)
            3355
        }
        3364 -> {
            f.fVar77 = f32(3.5)
            3363
        }
        3365 -> {
            f.fVar77 = f32(3.0)
            3363
        }
        3366 -> {
            if ((b(((b(((b(((b((3.3) <= (f.dVar75))) != 0) || ((b((f.fVar63) <= (8.0))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012ee90))) != 0))) != 0) || ((b((f.fVar93) <= (8.0))) != 0))) != 0) 3364 else 3365
        }
        3367 -> {
            if ((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) || ((b((f.fVar48) <= (48.0))) != 0))) != 0) 3362 else 3366
        }
        3368 -> {
            342
        }
        3369 -> {
            f.fVar67 = f32(2.8)
            3368
        }
        3370 -> {
            f.fVar32 = f32(1.0)
            3369
        }
        3371 -> {
            if ((b((3.9) <= (f.dVar75))) != 0) 3370 else 3367
        }
        3372 -> {
            f.fVar77 = f32(f.fVar32)
            3355
        }
        3373 -> {
            f.fVar67 = f32(1.0)
            3372
        }
        3374 -> {
            f.fVar32 = f32(f.fVar77)
            3373
        }
        3375 -> {
            if ((b(((b(((b(((b((f.dVar38) <= (1.2))) != 0) && ((b((f.fVar93) <= (8.5))) != 0))) != 0) || ((b(((b((0.0) <= (f32((f.fVar61) - (f.fVar40))))) != 0) && ((b(((b((f.fVar37) <= (DAT_0012f070))) != 0) || ((b((kotlin.math.abs(f32((f.fVar37) - (f.fVar40)))) <= (0.5))) != 0))) != 0))) != 0))) != 0) && ((b(((b((3.5) <= (f.fVar71))) != 0) || ((b(((b(((b((f.fVar63) <= (8.0))) != 0) || ((b((f.fVar47) <= (1.5))) != 0))) != 0) || ((b((f.fVar93) <= (8.0))) != 0))) != 0))) != 0))) != 0) 3374 else 3373
        }
        3376 -> {
            f.fVar32 = f32(f.fVar71)
            3375
        }
        3377 -> {
            343
        }
        3378 -> {
            f.fVar77 = f32(3.5)
            3377
        }
        3379 -> {
            if ((b(((b((f.dVar75) <= (DAT_0012f038))) != 0) || ((b(((b((3.0) <= (f.fVar71))) != 0) || ((run { run { f.fVar77 = f32(f.fVar71); f32(f.fVar71) }; b((f.fVar47) <= (1.5)) }) != 0))) != 0))) != 0) 3378 else 3377
        }
        3380 -> {
            if ((b(((b((f.dVar87) < (DAT_0012f030))) != 0) || ((b(((b((f.dVar75) < (DAT_0012f030))) != 0) && ((b((60.0) < (f.fVar48))) != 0))) != 0))) != 0) 3379 else 3376
        }
        3381 -> {
            if ((b(((b(((b(((b((f.fVar43) <= (0.0))) != 0) || ((b((f.fVar90) <= (0.0))) != 0))) != 0) || ((b((f.fVar73) <= (0.0))) != 0))) != 0) || ((b((1.0) <= (f.fVar88))) != 0))) != 0) 3371 else 3380
        }
        3382 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3354
        }
        3383 -> {
            f.fVar32 = f32(0.85)
            342
        }
        3384 -> {
            if ((b(((b(((b((0.5) <= (f.fVar73))) != 0) || ((b((0.5) <= (f.fVar90))) != 0))) != 0) || ((b(((b((0.5) <= (f.fVar43))) != 0) || ((b((f.fVar71) <= (f.fVar77))) != 0))) != 0))) != 0) 3381 else 3383
        }
        3385 -> {
            if ((b(((b(((b((DAT_0012ee78) <= (f.fVar43))) != 0) || ((b((DAT_0012ee78) <= (f.fVar73))) != 0))) != 0) || ((b((DAT_0012ee78) <= (f.fVar90))) != 0))) != 0) 3384 else 3353
        }
        3386 -> {
            if ((b(((b(((b((f.dVar87) <= (3.9))) != 0) || ((b((4.5) <= (f.fVar71))) != 0))) != 0) || ((b((f.dVar38) <= (0.85))) != 0))) != 0) 3385 else 3340
        }
        3387 -> {
            if ((b((f.fVar88) <= (1.5))) != 0) 3386 else 3319
        }
        3388 -> {
            f.fVar32 = f32(0.85)
            3311
        }
        3389 -> {
            352
        }
        3390 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.5, 0.8, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3389
        }
        3391 -> {
            f.param_5 = f32(f.auVar50)
            3389
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep106(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3392 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, f.fVar67, f.fVar32, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3391
        }
        3393 -> {
            f.fVar67 = f32(3.2)
            3392
        }
        3394 -> {
            f.fVar32 = f32(0.65)
            3393
        }
        3395 -> {
            f.fVar67 = f32(3.0)
            3392
        }
        3396 -> {
            f.fVar32 = f32(0.85)
            3395
        }
        3397 -> {
            if ((b(((b(((b((f.dVar36) <= (DAT_0012f0b0))) != 0) || ((b((f.fVar47) <= (2.5))) != 0))) != 0) || ((b((f.dVar39) <= (DAT_0012f0b0))) != 0))) != 0) 3394 else 3396
        }
        3398 -> {
            f.auVar50 = f32(f.param_5)
            3397
        }
        3399 -> {
            352
        }
        3400 -> {
            f.fVar66 = f32(f32(adjustment_range(f.param_2, f.param_4, f.fVar37, f.fVar40, f.fVar61, f.param_6, f.fVar68, f.fVar74, f.fVar26, f.fVar33, f.fVar34, 3.6, 1.3, f.param_14, f.param_13, f.fVar71, f.fVar42, f.fVar83, f.fVar72, f.fVar48, f.fVar25, f.fVar63, f.fVar93, f.fVar64, f.fVar46, f.iVar21, f.fVar79, f.fVar91, f.fVar80, f.fVar31, f.fVar70)))
            3399
        }
        3401 -> {
            if ((b(((b((DAT_0012f2c0) < (f.dVar49))) != 0) && ((b(((b((f.fVar72) < (10.5))) != 0) || ((b(((b((f.fVar80) < (5.0))) != 0) || ((b((f.dVar45) < (0.3))) != 0))) != 0))) != 0))) != 0) 3400 else 3398
        }
        3402 -> {
            if ((b((f.fVar69) < (DAT_0012f1a0))) != 0) 3401 else 3398
        }
        3403 -> {
            if ((b(((b((DAT_0012f030) <= (f.dVar75))) != 0) || ((b((f.fVar35) <= (1.0))) != 0))) != 0) 3390 else 3402
        }
        3404 -> {
            if ((b(((b(((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((0.5) <= (f.fVar43))) != 0))) != 0) || ((b((0.5) <= (f.fVar90))) != 0))) != 0) || ((b((0.5) <= (f.fVar73))) != 0))) != 0) 3403 else 3388
        }
        3405 -> {
            if ((b(((b(((b((5.3) <= (f.dVar29))) != 0) || ((b((f.fVar35) <= (0.85))) != 0))) != 0) || ((b(((b(((b((86.0) <= (f.fVar48))) != 0) || ((b(((b((f.fVar31) <= (66.0))) != 0) || ((b((4.0) <= (f.fVar42))) != 0))) != 0))) != 0) || ((b((f.fVar25) <= (10.0))) != 0))) != 0))) != 0) 3387 else 3404
        }
        3406 -> {
            f.fVar67 = f32(3.2)
            3311
        }
        3407 -> {
            f.fVar32 = f32(1.0)
            3406
        }
        3408 -> {
            349
        }
        3409 -> {
            f.fVar77 = f32(f.fVar71)
            3408
        }
        3410 -> {
            f.fVar67 = f32(1.0)
            3409
        }
        3411 -> {
            f.fVar77 = f32(3.0)
            3408
        }
        3412 -> {
            f.fVar67 = f32(1.0)
            3411
        }
        3413 -> {
            if ((b(((b(((b((f.fVar48) <= (40.0))) != 0) || ((b(((b((f.fVar31) <= (60.0))) != 0) || ((b((3.0) <= (f.fVar71))) != 0))) != 0))) != 0) || ((b((DAT_0012ef38) <= (f.fVar32))) != 0))) != 0) 3410 else 3412
        }
        3414 -> {
            if ((b(((b((3.9) <= (f.dVar29))) != 0) || ((b((f.fVar63) <= (9.0))) != 0))) != 0) 3413 else 3407
        }
        3415 -> {
            if ((b(((b((4.5) <= (f.fVar80))) != 0) || ((b(((b(((b((f.fVar72) <= (10.0))) != 0) || ((b((f.fVar63) <= (8.0))) != 0))) != 0) || ((b((f.fVar81) <= (1.0))) != 0))) != 0))) != 0) 3405 else 3414
        }
        3416 -> {
            f.fVar32 = f32(1.0)
            3311
        }
        3417 -> {
            if ((b(((b(((b((f.fVar93) <= (8.0))) != 0) || ((b((f.dVar87) <= (3.9))) != 0))) != 0) || ((b(((b((3.5) <= (f.fVar71))) != 0) || ((b(((b((f.fVar72) <= (10.0))) != 0) || ((b((f.fVar48) <= (60.0))) != 0))) != 0))) != 0))) != 0) 3415 else 3416
        }
        3418 -> {
            349
        }
        3419 -> {
            f.fVar67 = f32(1.0)
            3418
        }
        3420 -> {
            f.fVar77 = f32(f.fVar71)
            3418
        }
        3421 -> {
            f.fVar67 = f32(0.85)
            3420
        }
        3422 -> {
            if ((b(((b(((b(((b((0.5) <= (f.fVar73))) != 0) || ((b((0.5) <= (f.fVar90))) != 0))) != 0) || ((b((0.5) <= (f.fVar43))) != 0))) != 0) || ((b((f.fVar71) <= (f.fVar77))) != 0))) != 0) 3419 else 3421
        }
        3423 -> {
            344
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep107(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3424 -> {
            f.fVar67 = f32(1.0)
            3423
        }
        3425 -> {
            if ((b(((b((f.dVar75) < (DAT_0012f048))) != 0) && ((b((DAT_0012ee88) < (f.dVar38))) != 0))) != 0) 3424 else 3422
        }
        3426 -> {
            f.fVar67 = f32(0.85)
            3418
        }
        3427 -> {
            f.fVar77 = f32(3.5)
            3426
        }
        3428 -> {
            if ((b(((b(((b((0.5) <= (f.fVar43))) != 0) || ((b((0.5) <= (f.fVar90))) != 0))) != 0) || ((run { run { f.fVar77 = f32(f.fVar71); f32(f.fVar71) }; b((0.5) <= (f.fVar73)) }) != 0))) != 0) 3427 else 3426
        }
        3429 -> {
            if ((b((f.fVar88) < (1.0))) != 0) 3425 else 3428
        }
        3430 -> {
            if ((b(((b((f.fVar48) < (48.0))) != 0) && ((b((3.9) < (f.dVar87))) != 0))) != 0) 3429 else 3417
        }
        3431 -> {
            324
        }
        3432 -> {
            f.fVar67 = f32(1.0)
            3431
        }
        3433 -> {
            f.fVar62 = f32(2.8)
            3432
        }
        3434 -> {
            if ((b(((b((f.dVar75) < (DAT_0012f038))) != 0) && ((b((readF32(f.param_1.plus(0x298))) < (2.4))) != 0))) != 0) 3433 else 3432
        }
        3435 -> {
            if ((b(((b((2.0) < (f.fVar47))) != 0) && ((b(((b(((b(((b((f.fVar71) < (3.5))) != 0) && ((b((2.5) < (f.fVar71))) != 0))) != 0) && ((b((DAT_0012f040) < (f.fVar32))) != 0))) != 0) && ((b((7.5) < (f.fVar93))) != 0))) != 0))) != 0) 3434 else 3430
        }
        3436 -> {
            f.fVar62 = f32(f.fVar71)
            3435
        }
        3437 -> {
            if ((b(((b((f.dVar87) < (3.9))) != 0) || ((b((kotlin.math.abs(f32((f.fVar65) - (f.fVar94)))) < (DAT_0012ee80))) != 0))) != 0) 3436 else 3309
        }
        3438 -> {
            f.fVar67 = f32(f.fVar71)
            3437
        }
        3439 -> {
            351
        }
        3440 -> {
            f.fVar67 = f32(1.2)
            3439
        }
        3441 -> {
            f.fVar77 = f32(f.fVar71)
            3439
        }
        3442 -> {
            f.fVar67 = f32(1.0)
            3441
        }
        3443 -> {
            341
        }
        3444 -> {
            if ((b(((b(((b((f.fVar25) <= (8.0))) != 0) || ((b(((b(((b((3.9) <= (f.dVar29))) != 0) || ((b((9.0) <= (f.fVar72))) != 0))) != 0) || ((b((f.fVar63) <= (6.0))) != 0))) != 0))) != 0) || ((b(((b((f.dVar38) <= (0.85))) != 0) || ((b((f.fVar48) <= (70.0))) != 0))) != 0))) != 0) 3443 else 3442
        }
        3445 -> {
            f.fVar77 = f32(f.fVar71)
            3439
        }
        3446 -> {
            f.fVar67 = f32(1.2)
            3445
        }
        3447 -> {
            if ((b(((b((f.fVar35) <= (1.0))) != 0) || ((b(((b((10.0) <= (f.fVar72))) != 0) || ((b((f.fVar93) <= (7.0))) != 0))) != 0))) != 0) 3444 else 3446
        }
        3448 -> {
            if ((b(((b((f.fVar71) <= (3.0))) != 0) || ((b((f.fVar77) <= (f.fVar71))) != 0))) != 0) 341 else 3447
        }
        3449 -> {
            f.fVar77 = f32(f.fVar71)
            3439
        }
        3450 -> {
            f.fVar67 = f32(1.0)
            3449
        }
        3451 -> {
            if ((b(((b(((b(((b((0.5) <= (f.fVar73))) != 0) || ((b((0.5) <= (f.fVar90))) != 0))) != 0) || ((b((0.5) <= (f.fVar43))) != 0))) != 0) || ((b((f.fVar71) <= (f.fVar77))) != 0))) != 0) 3448 else 3450
        }
        3452 -> {
            340
        }
        3453 -> {
            if ((b(((b((72.0) <= (f.fVar48))) != 0) || ((run { run { f.fVar67 = f32(4.3); f32(4.3) }; b((f.fVar93) <= (6.0)) }) != 0))) != 0) 3452 else 3451
        }
        3454 -> {
            f.fVar67 = f32(4.3)
            3453
        }
        3455 -> {
            340
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep108(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3456 -> {
            f.fVar67 = f32(4.3)
            3455
        }
        3457 -> {
            if ((b((f.fVar35) <= (0.6))) != 0) 3456 else 3454
        }
        3458 -> {
            338
        }
        3459 -> {
            if ((b(((b((4.1) < (f.dVar30))) != 0) && ((b(((b((f.dVar38) < (0.85))) != 0) && ((b((f.dVar39) < (DAT_0012ef68))) != 0))) != 0))) != 0) 3458 else 3457
        }
        3460 -> {
            340
        }
        3461 -> {
            f.fVar67 = f32(4.7)
            3460
        }
        3462 -> {
            if ((b(((b((4.5) < (f.fVar80))) != 0) && ((b((f.dVar29) < (DAT_0012f150))) != 0))) != 0) 3461 else 3457
        }
        3463 -> {
            if ((b((f.dVar38) < (0.85))) != 0) 338 else 3457
        }
        3464 -> {
            if ((b(((b((6.6) <= (f.dVar39))) != 0) || ((b((6.6) <= (f.dVar36))) != 0))) != 0) 3459 else 3463
        }
        3465 -> {
            340
        }
        3466 -> {
            f.fVar67 = f32(4.2)
            3465
        }
        3467 -> {
            if ((b(((b(((b(((b((9.0) < (f.fVar25))) != 0) && ((b((0.35) < (f.dVar38))) != 0))) != 0) && ((b((DAT_0012f160) < (f.dVar36))) != 0))) != 0) && ((b(((b((4.5) < (f.fVar80))) != 0) && ((b((0.5) < (f.fVar35))) != 0))) != 0))) != 0) 3466 else 3464
        }
        3468 -> {
            340
        }
        3469 -> {
            f.fVar67 = f32(3.5)
            3468
        }
        3470 -> {
            if ((b(((b(((b((f.fVar80) < (4.0))) != 0) && ((b((0.75) < (f.fVar47))) != 0))) != 0) && ((b(((b((60.0) < (f.fVar48))) != 0) && ((b(((b(((b((84.0) < (f.fVar31))) != 0) && ((b((f.dVar75) < (DAT_0012f030))) != 0))) != 0) && ((b((8.0) < (f.fVar25))) != 0))) != 0))) != 0))) != 0) 3469 else 3467
        }
        3471 -> {
            340
        }
        3472 -> {
            if ((b(((b(((b((f.dVar29) < (5.3))) != 0) && ((b((0.85) < (f.fVar35))) != 0))) != 0) && ((b(((b((f.fVar48) < (78.0))) != 0) && ((b(((b(((b((84.0) < (f.fVar31))) != 0) && ((b((f.fVar71) < (3.5))) != 0))) != 0) && ((run { run { f.fVar67 = f32(3.5); f32(3.5) }; b((8.0) < (f.fVar25)) }) != 0))) != 0))) != 0))) != 0) 3471 else 3470
        }
        3473 -> {
            340
        }
        3474 -> {
            f.fVar67 = f32(3.9)
            3473
        }
        3475 -> {
            if ((b(((b(((b(((b(((b((f.dVar29) < (DAT_0012f078))) != 0) && ((b((f.fVar79) < (5.5))) != 0))) != 0) && ((b((f.dVar75) < (3.9))) != 0))) != 0) && ((b(((b((DAT_0012ef70) < (f.fVar35))) != 0) && ((b((f.fVar48) < (78.0))) != 0))) != 0))) != 0) && ((b((8.5) < (f.fVar25))) != 0))) != 0) 3474 else 3472
        }
        3476 -> {
            339
        }
        3477 -> {
            if ((b((f.fVar71) < (3.5))) != 0) 3476 else 337
        }
        3478 -> {
            writeF32(f.param_1.plus(0x238), f32(f.fVar77))
            3451
        }
        3479 -> {
            f.fVar77 = f32(f.fVar67)
            3478
        }
        3480 -> {
            f.fVar67 = f32(3.5)
            340
        }
        3481 -> {
            if ((b(!((f.bVar12) != 0))) != 0) 3480 else 340
        }
        3482 -> {
            f.fVar67 = f32(4.2)
            3481
        }
        3483 -> {
            f.bVar12 = b((b((f.dVar39) < (DAT_0012f160))) != 0)
            3482
        }
        3484 -> {
            if ((b(((b((DAT_0012f160) <= (f.dVar36))) != 0) && ((run { run { f.bVar12 = b((0) != 0); b((0) != 0) }; b(((b(!((b((f.dVar39).isNaN())) != 0))) != 0) && ((b(!((b((DAT_0012f160).isNaN())) != 0))) != 0)) }) != 0))) != 0) 3483 else 3482
        }
        3485 -> {
            f.bVar12 = b((1) != 0)
            3484
        }
        3486 -> {
            337
        }
        3487 -> {
            if ((b(((b(((b((DAT_0012f048) <= (f.dVar29))) != 0) || ((b((3.5) <= (f.fVar71))) != 0))) != 0) || ((b((f32((f.fVar64) - (f.fVar44))) <= (0.0))) != 0))) != 0) 3486 else 339
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep109(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3488 -> {
            if ((b((DAT_0012f1c0) < (f32((f.fVar64) - (f.fVar44))))) != 0) 3477 else 3487
        }
        3489 -> {
            if ((b(((b(((b(((b((f.dVar28) < (DAT_0012ee90))) != 0) && ((b((f.dVar38) < (1.2))) != 0))) != 0) || ((b(((b(((b(((b((f.fVar80) < (4.5))) != 0) && ((b((f.fVar91) < (7.0))) != 0))) != 0) || ((b((f.dVar28) < (2.3))) != 0))) != 0) && ((b((f.fVar32) < (DAT_0012ef38))) != 0))) != 0))) != 0) && ((b((f.dVar75) < (DAT_0012f098))) != 0))) != 0) 3488 else 3438
        }
        3490 -> {
            f.dVar28 = f32((f.fVar91) - (f.fVar80))
            3489
        }
        3491 -> {
            350
        }
        3492 -> {
            if ((b(((b(((b((2.5) <= (f.fVar94))) != 0) || ((b((DAT_0012f068) <= (f.fVar65))) != 0))) != 0) && ((b(((b(((b((9.0) <= (f.fVar72))) != 0) || ((b((DAT_0012f078) <= (f.dVar87))) != 0))) != 0) && ((b(((b(((b(((b((f.fVar48) <= (54.0))) != 0) || ((b((2.5) <= (f.fVar65))) != 0))) != 0) || ((b((6.0) <= (f.fVar77))) != 0))) != 0) || ((b((DAT_0012f030) <= (f.fVar94))) != 0))) != 0))) != 0))) != 0) 3491 else 3490
        }
        3493 -> {
            f.fVar65 = f32(f32((f.fVar77) - (f.fVar37)))
            3492
        }
        3494 -> {
            f.fVar94 = f32(f32((f.fVar77) - (f.fVar40)))
            3493
        }
        3495 -> {
            if ((b((2.8) < (f.dVar87))) != 0) 3494 else 350
        }
        3496 -> {
            f.fVar88 = f32(f32((f.fVar77) - (f.fVar71)))
            3495
        }
        3497 -> {
            f.dVar87 = f.fVar77
            3496
        }
        3498 -> {
            writeF32(f.param_1.plus(0x238), f32(f.fVar77))
            3497
        }
        3499 -> {
            f.fVar77 = f32(((f32((f.fVar71) + (f.fVar42))) * (0.5)))
            3498
        }
        3500 -> {
            if ((b((f.fVar77) < (f.fVar42))) != 0) 3499 else 3497
        }
        3501 -> {
            f.fVar77 = f32(readF32(f.param_1.plus(0x238)))
            3500
        }
        3502 -> {
            if ((b((f.iVar21) < (2))) != 0) 249 else 3501
        }
        3503 -> {
            f.fVar44 = f32(kotlin.math.abs(f.fVar46))
            3502
        }
        3504 -> {
            f.fVar35 = f32(f32((f.fVar80) - (f.fVar71)))
            3503
        }
        3505 -> {
            f.fVar73 = f32(f32((f.fVar71) - (f.fVar61)))
            3504
        }
        3506 -> {
            f.fVar90 = f32(f32((f.fVar71) - (f.fVar40)))
            3505
        }
        3507 -> {
            f.fVar43 = f32(f32((f.fVar71) - (f.fVar37)))
            3506
        }
        3508 -> {
            f.dVar49 = f.fVar46
            3507
        }
        3509 -> {
            f.fVar69 = f32(f32((f.fVar64) + (f.fVar46)))
            3508
        }
        3510 -> {
            if ((b((f.param_14) < (0x240))) != 0) 1709 else 3509
        }
        3511 -> {
            f.fVar81 = f32(f32((f.fVar63) - (f.fVar93)))
            3510
        }
        3512 -> {
            f.fVar84 = f32(f32((f.fVar33) - (f.fVar68)))
            3511
        }
        3513 -> {
            f.dVar52 = f.fVar68
            3512
        }
        3514 -> {
            f.fVar66 = f32(f32((f.fVar93) - (f.fVar63)))
            3513
        }
        3515 -> {
            f.dVar30 = f.fVar42
            3514
        }
        3516 -> {
            f.fVar85 = f32(kotlin.math.abs(f32((f.fVar93) - (f.fVar63))))
            3515
        }
        3517 -> {
            f.fVar62 = f32(kotlin.math.abs(f32((f.fVar33) - (f.fVar68))))
            3516
        }
        3518 -> {
            f.fVar86 = f32(f32((f.fVar26) - (f.param_6)))
            3517
        }
        3519 -> {
            f.dVar45 = f.fVar64
            3518
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep110(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3520 -> {
            f.dVar51 = f.fVar40
            3519
        }
        3521 -> {
            f.fVar32 = f32(f32((f.fVar42) - (f.fVar71)))
            3520
        }
        3522 -> {
            f.dVar39 = f.fVar63
            3521
        }
        3523 -> {
            f.dVar36 = f.fVar93
            3522
        }
        3524 -> {
            f.fVar67 = f32(f32((f.fVar83) - (f.fVar42)))
            3523
        }
        3525 -> {
            f.pjVar4 = f.param_1.plus(400)
            3524
        }
        3526 -> {
            f.dVar89 = f.fVar37
            3525
        }
        3527 -> {
            f.fVar76 = f32(f32((f.param_6) - (f.fVar37)))
            3526
        }
        3528 -> {
            f.fVar46 = f32(readF32(f.param_1.plus(0x270)))
            3527
        }
        3529 -> {
            f.fVar48 = f32(readF32(f.param_1.plus(0x1b0)))
            3528
        }
        3530 -> {
            f.fVar74 = f32(readF32(f.param_1.plus(0x184)))
            3529
        }
        3531 -> {
            f.fVar70 = f32(readF32(f.param_1.plus(0x1c)))
            3530
        }
        3532 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x188)))
            3531
        }
        3533 -> {
            f.fVar91 = f32(readF32(f.param_1.plus(0x254)))
            3532
        }
        3534 -> {
            f.fVar33 = f32(readF32(f.param_1.plus(0x164)))
            3533
        }
        3535 -> {
            f.fVar31 = f32(readF32(f.param_1.plus(0x2c)))
            3534
        }
        3536 -> {
            f.fVar40 = f32(readF32(f.param_1.plus(0x28)))
            3535
        }
        3537 -> {
            f.fVar68 = f32(readF32(f.param_1.plus(0x17c)))
            3536
        }
        3538 -> {
            f.fVar64 = f32(readF32(f.param_1.plus(0x26c)))
            3537
        }
        3539 -> {
            f.fVar63 = f32(readF32(f.param_1.plus(0x1f8)))
            3538
        }
        3540 -> {
            f.fVar25 = f32(readF32(f.param_1.plus(0x250)))
            3539
        }
        3541 -> {
            f.fVar93 = f32(readF32(f.param_1.plus(0x1fc)))
            3540
        }
        3542 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x178)))
            3541
        }
        3543 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x180)))
            3542
        }
        3544 -> {
            139
        }
        3545 -> {
            if ((b(((b((0x480) < (f.param_14))) != 0) && ((b((f32((readF32(f.param_1.plus(0x254))) - (f.fVar79))) < (0.7))) != 0))) != 0) 3544 else 144
        }
        3546 -> {
            writeF32(f.param_1.plus(0x22c), f32(f.fVar61))
            144
        }
        3547 -> {
            writeF32(f.param_1.plus(0x230), f32(f.fVar61))
            3546
        }
        3548 -> {
            f.fVar61 = f32(f32(((((f.dVar30) + (1.0))) * (f.dVar36))))
            3547
        }
        3549 -> {
            f.dVar36 = readF32(f.param_1.plus(0x230))
            141
        }
        3550 -> {
            f.dVar30 = ((((0.85) - (f.dVar51))) * (-(0.3)))
            3549
        }
        3551 -> {
            writeI32(f.param_1.plus(0x18), 1)
            3550
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep111(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3552 -> {
            143
        }
        3553 -> {
            writeF32(f.pjVar3, f32(f.fVar61))
            3552
        }
        3554 -> {
            f.fVar61 = f32(f32(((((1.0) - (((((0.85) - (f.dVar51))) * (f.dVar30))))) * (readF32(f.pjVar3)))))
            3553
        }
        3555 -> {
            140
        }
        3556 -> {
            if ((b(((b((3.5) <= (f.fVar71))) != 0) || ((run { run { f.dVar30 = 0.25; 0.25 }; b((readF32(f.param_1.plus(0x1f8))) <= (6.0)) }) != 0))) != 0) 3555 else 3554
        }
        3557 -> {
            f.dVar30 = f.dVar36
            3554
        }
        3558 -> {
            if ((b((3.5) <= (f.fVar71))) != 0) 140 else 3554
        }
        3559 -> {
            f.dVar30 = 0.25
            3558
        }
        3560 -> {
            if ((b((readF32(f.param_1.plus(0x1fc))) <= (6.0))) != 0) 3556 else 3559
        }
        3561 -> {
            143
        }
        3562 -> {
            writeF32(f.pjVar3, f32(f.fVar61))
            3561
        }
        3563 -> {
            f.fVar61 = f32(f32(((((((((0.85) - (f.dVar51))) * (DAT_0012ef78))) + (1.0))) * (readF32(f.pjVar3)))))
            3562
        }
        3564 -> {
            if ((b(((b((5.0) <= (f.fVar42))) != 0) && ((b(((b((2.8) <= (f.dVar75))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (60.0))) != 0))) != 0))) != 0) 3563 else 3560
        }
        3565 -> {
            writeI32(f.param_1.plus(0x18), 1)
            3564
        }
        3566 -> {
            if ((b(((b((DAT_0012f040) < (f.dVar51))) != 0) || ((b((f.fVar42) < (6.0))) != 0))) != 0) 3565 else 3551
        }
        3567 -> {
            f.dVar30 = ((((0.85) - (f.dVar51))) * (f.dVar30))
            141
        }
        3568 -> {
            f.dVar36 = readF32(f.param_1.plus(0x230))
            3567
        }
        3569 -> {
            writeI32(f.param_1.plus(0x18), 1)
            3568
        }
        3570 -> {
            if ((b(((b(((b(((b((f.dVar75) <= (DAT_0012f060))) != 0) || ((b((0.25) <= (f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))))) != 0))) != 0) || ((b((DAT_0012ee88) <= (f.dVar39))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (9.0))) != 0))) != 0) 3566 else 3569
        }
        3571 -> {
            if ((b(((b(((b(((b(((b(((b(((b((DAT_0012f018) <= (f.dVar45))) != 0) || ((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((f.dVar39) <= (1.2))) != 0))) != 0))) != 0) && ((b(((b(((b((f.dVar39) <= (1.2))) != 0) || ((b(((b((DAT_0012ee90) <= (f.dVar39))) != 0) || ((b(((b(((b((f.dVar75) <= (3.9))) != 0) && ((b((f.fVar80) <= (4.5))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (72.0))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b(((b(((b((10.0) <= (f.fVar72))) != 0) || ((b((8.1) <= (f.fVar42))) != 0))) != 0) || ((b((f.dVar29) <= (DAT_0012f150))) != 0))) != 0) || ((b((f.dVar39) <= (DAT_0012ee90))) != 0))) != 0) && ((b(((b(((b((9.0) <= (f.fVar72))) != 0) || ((b((7.0) <= (f.fVar42))) != 0))) != 0) || ((b(((b((f.dVar29) <= (DAT_0012f150))) != 0) || ((b((DAT_0012ee88) <= (f.dVar39))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b(((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((8.5) <= (f.fVar72))) != 0))) != 0) || ((b((6.5) <= (f.fVar42))) != 0))) != 0) || ((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((DAT_0012ee88) <= (f.dVar39))) != 0))) != 0))) != 0) && ((b(((b((f.dVar29) <= (4.8))) != 0) || ((b((f.iVar24) < (1))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b((f.fVar47) <= (f.fVar34))) != 0) || ((b((9.0) <= (f.fVar72))) != 0))) != 0) || ((b(((b((f.dVar28) <= (5.3))) != 0) || ((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (56.0))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b((readF32(f.param_1.plus(0x1b0))) <= (60.0))) != 0) || ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) <= (DAT_0012eef8))) != 0))) != 0) || ((b(((b((f.fVar79) <= (5.0))) != 0) && ((b(((b((f.fVar79) <= (4.5))) != 0) || ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) <= (DAT_0012f040))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar29) <= (5.8))) != 0) || ((b((9.0) <= (f.fVar42))) != 0))) != 0))) != 0) 3570 else 144
        }
        3572 -> {
            writeI32(f.param_1.plus(0x18), 1)
            144
        }
        3573 -> {
            writeF32(f.param_1.plus(0x22c), f32(f.fVar61))
            3572
        }
        3574 -> {
            writeF32(f.param_1.plus(0x230), f32(f.fVar61))
            3573
        }
        3575 -> {
            f.fVar61 = f32(f32(((((((((DAT_0012ee70) - (f.dVar51))) * (DAT_0012f250))) + (1.0))) * (readF32(f.param_1.plus(0x230))))))
            3574
        }
        3576 -> {
            if ((b((f.fVar61) < (1.0))) != 0) 3575 else 144
        }
        3577 -> {
            141
        }
        3578 -> {
            writeI32(f.param_1.plus(0x18), 1)
            3577
        }
        3579 -> {
            f.dVar30 = ((((DAT_0012ee70) - (f.dVar51))) * (-(0.3)))
            3578
        }
        3580 -> {
            f.dVar36 = readF32(f.param_1.plus(0x230))
            3579
        }
        3581 -> {
            if ((b((f.fVar61) < (1.0))) != 0) 3580 else 144
        }
        3582 -> {
            if ((b(((b((DAT_0012f040) < (f.dVar51))) != 0) || ((b((f.fVar42) < (6.0))) != 0))) != 0) 3576 else 3581
        }
        3583 -> {
            if ((b(((b(((b(((b(((b((DAT_0012f018) <= (f.dVar45))) != 0) || ((b((f.fVar71) <= (3.5))) != 0))) != 0) || ((b((f.dVar39) <= (1.2))) != 0))) != 0) && ((b(((b(((b(((b(((b(((b(((b((f.dVar39) <= (1.2))) != 0) || ((b((DAT_0012ee90) <= (f.dVar39))) != 0))) != 0) || ((b(((b((f.dVar75) <= (3.9))) != 0) && ((b((f.fVar80) <= (4.5))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (72.0))) != 0))) != 0) && ((b(((b(((b((10.0) <= (f.fVar72))) != 0) || ((b((8.1) <= (f.fVar42))) != 0))) != 0) || ((b(((b((f.dVar29) <= (DAT_0012f150))) != 0) || ((b((f.dVar39) <= (DAT_0012ee90))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b(((b((9.0) <= (f.fVar72))) != 0) || ((b((7.0) <= (f.fVar42))) != 0))) != 0) || ((b((f.dVar29) <= (DAT_0012f150))) != 0))) != 0) || ((b((DAT_0012ee88) <= (f.dVar39))) != 0))) != 0))) != 0) && ((b(((b(((b((f.fVar71) <= (3.5))) != 0) || ((b((8.0) <= (f.fVar72))) != 0))) != 0) || ((b(((b((6.5) <= (f.fVar42))) != 0) || ((b(((b((5.0) <= (f.fVar80))) != 0) || ((b((1.5) <= (f32((f.fVar42) - (f.fVar80))))) != 0))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b(((b(((b((readF32(f.param_1.plus(0x1b0))) <= (60.0))) != 0) || ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) <= (DAT_0012eef8))) != 0))) != 0) || ((b(((b((f.fVar79) <= (5.0))) != 0) && ((b(((b((f.fVar79) <= (4.5))) != 0) || ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) <= (DAT_0012f040))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar29) <= (5.8))) != 0) || ((b((9.0) <= (f.fVar42))) != 0))) != 0))) != 0))) != 0) 3582 else 144
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep112(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3584 -> {
            if ((b(((b((f.dVar51) < (0.7))) != 0) || ((b((0.7) <= (f32((f.fVar42) - (f.fVar79))))) != 0))) != 0) 3571 else 3583
        }
        3585 -> {
            f.dVar45 = f32((f.fVar80) + (f32((f.fVar42) - (f32((f.fVar79) + (f.fVar79))))))
            3584
        }
        3586 -> {
            f.dVar39 = f32((f.fVar42) - (f.fVar80))
            3585
        }
        3587 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x254)))
            3586
        }
        3588 -> {
            if ((b((readI32(f.param_1.plus(0x18))) == (0))) != 0) 3587 else 144
        }
        3589 -> {
            if ((b((0x480) < (f.param_14))) != 0) 139 else 144
        }
        3590 -> {
            if ((b((0.7) <= (f.dVar51))) != 0) 3545 else 3589
        }
        3591 -> {
            142
        }
        3592 -> {
            if ((b(((b((f.fVar47) <= (1.0))) != 0) || ((b(((b(((b(((b(((b((readF32(f.param_1.plus(0x1fc))) <= (6.0))) != 0) || ((b((f.fVar80) <= (3.5))) != 0))) != 0) || ((b((f.fVar72) <= (9.0))) != 0))) != 0) || ((b(((b((2.5) <= (f.fVar71))) != 0) || ((b((72.0) <= (f.fVar61))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (6.0))) != 0))) != 0))) != 0) 3591 else 144
        }
        3593 -> {
            142
        }
        3594 -> {
            if ((b(((b(((b(((b((f.fVar80) <= (3.5))) != 0) || ((b((f.fVar72) <= (9.0))) != 0))) != 0) || ((b((2.5) <= (f.fVar71))) != 0))) != 0) || ((b((72.0) <= (f.fVar61))) != 0))) != 0) 3593 else 144
        }
        3595 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (7.0))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (7.0))) != 0))) != 0) 3592 else 3594
        }
        3596 -> {
            writeF32(f.pjVar2, f32(f.fVar61))
            144
        }
        3597 -> {
            writeF32(f.pjVar3, f32(f.fVar61))
            143
        }
        3598 -> {
            f.fVar61 = f32(f32(((((((((((5.0) - (f.fVar80))) * (f.dVar30))) / (5.0))) + (1.0))) * (readF32(f.param_1.plus(0x230))))))
            3597
        }
        3599 -> {
            f.dVar30 = -(0.15)
            3598
        }
        3600 -> {
            if ((f.bVar12) != 0) 3599 else 3598
        }
        3601 -> {
            f.dVar30 = DAT_0012f020
            3600
        }
        3602 -> {
            writeI32(f.param_1.plus(0x18), 1)
            3601
        }
        3603 -> {
            f.bVar12 = b((b((DAT_0012f048) <= (f.dVar29))) != 0)
            3602
        }
        3604 -> {
            141
        }
        3605 -> {
            f.dVar30 = ((((f.dVar30) * (((5.0) - (f.fVar80))))) / (-(5.0)))
            3604
        }
        3606 -> {
            f.dVar36 = readF32(f.param_1.plus(0x230))
            3605
        }
        3607 -> {
            writeI32(f.param_1.plus(0x18), 1)
            3606
        }
        3608 -> {
            f.dVar30 = 0.25
            3607
        }
        3609 -> {
            if ((b((f.dVar38) <= (DAT_0012ef60))) != 0) 3608 else 3607
        }
        3610 -> {
            f.dVar30 = 0.15
            3609
        }
        3611 -> {
            if ((b(((b((f.dVar75) < (3.9))) != 0) && ((b(((b((f.dVar28) < (4.6))) != 0) || ((b((readF32(f.param_1.plus(0x254))) < (DAT_0012f160))) != 0))) != 0))) != 0) 3610 else 3603
        }
        3612 -> {
            if ((b(((b((f.dVar29) <= (5.8))) != 0) || ((b((9.0) <= (readF32(f.param_1.plus(0x254))))) != 0))) != 0) 3611 else 144
        }
        3613 -> {
            if ((b((0.7) < (f.dVar38))) != 0) 3595 else 142
        }
        3614 -> {
            144
        }
        3615 -> {
            if ((b(((b(((b((60.0) < (f.fVar61))) != 0) && ((b((DAT_0012eef8) < (f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))))) != 0))) != 0) && ((b(((b((5.0) < (f.fVar79))) != 0) || ((b(((b((4.5) < (f.fVar79))) != 0) && ((b((DAT_0012f040) < (f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))))) != 0))) != 0))) != 0))) != 0) 3614 else 3613
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep113(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3616 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x1b0)))
            3615
        }
        3617 -> {
            if ((b(((b(((b((f.param_14) < (0x5a1))) != 0) || ((b((4.5) <= (f.fVar80))) != 0))) != 0) || ((b(((b((readI32(f.param_1.plus(0x18))) != (0))) != 0) || ((b(((b(((b((5.0) <= (f.fVar79))) != 0) && ((b((3.9) <= (f.dVar29))) != 0))) != 0) || ((b((f.dVar51) < (0.7))) != 0))) != 0))) != 0))) != 0) 3590 else 3616
        }
        3618 -> {
            f.dVar30 = DAT_0012f020
            3617
        }
        3619 -> {
            f.dVar36 = DAT_0012f040
            3618
        }
        3620 -> {
            129
        }
        3621 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x1fc))) <= (6.0))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (6.0))) != 0))) != 0) || ((b(((b((10.0) <= (f.fVar72))) != 0) || ((b(((b(((b((f.dVar28) <= (5.3))) != 0) || ((b((f.fVar71) <= (3.5))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (60.0))) != 0))) != 0))) != 0))) != 0) 3620 else 138
        }
        3622 -> {
            134
        }
        3623 -> {
            if ((b(((b((13.0) < (f.fVar72))) != 0) && ((b((f32((readF32(f.param_1.plus(0x250))) / (f.fVar72))) < (1.2))) != 0))) != 0) 3622 else 138
        }
        3624 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x1fc))) <= (7.0))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (7.0))) != 0))) != 0) || ((b(((b((60.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) || ((b(((b(((b((4.0) <= (f.fVar71))) != 0) || ((b((f.fVar71) <= (3.5))) != 0))) != 0) || ((b((f.iVar24) != (1))) != 0))) != 0))) != 0))) != 0) 3621 else 3623
        }
        3625 -> {
            writeF32(f.pjVar2, f32(f.fVar42))
            138
        }
        3626 -> {
            f.fVar42 = f32(f32((f.fVar42) * (f.fVar25)))
            3625
        }
        3627 -> {
            f.fVar42 = f32(((f32((f32((f.fVar67) * (f.fVar42))) / (f.fVar76))) + (1.0)))
            3626
        }
        3628 -> {
            f.fVar25 = f32(readF32(f.pjVar3))
            3627
        }
        3629 -> {
            f.fVar42 = f32(-(2.0))
            132
        }
        3630 -> {
            134
        }
        3631 -> {
            if ((b(((b(((b(((b((readF32(f.param_1.plus(0x298))) < (DAT_0012f068))) != 0) || ((b((0.75) <= (f.fVar47))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (60.0))) != 0))) != 0) || ((b((0.3) <= (f.dVar30))) != 0))) != 0) 3630 else 131
        }
        3632 -> {
            134
        }
        3633 -> {
            137
        }
        3634 -> {
            f.fVar42 = f32(readF32(f.pjVar3))
            3633
        }
        3635 -> {
            f.dVar30 = ((f.dVar30) * (DAT_0012f178))
            3634
        }
        3636 -> {
            if ((b(((b(((b((0.6) < (f.dVar38))) != 0) && ((b(((b((7.0) < (readF32(f.param_1.plus(0x1fc))))) != 0) || ((b((7.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) && ((b(((b((f.fVar67) < (0.25))) != 0) && ((b(((b((f.fVar71) < (4.5))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (60.0))) != 0))) != 0))) != 0))) != 0) 3635 else 3632
        }
        3637 -> {
            132
        }
        3638 -> {
            f.fVar42 = f32(-(3.0))
            3637
        }
        3639 -> {
            133
        }
        3640 -> {
            if ((b(((b(((b(((b((0.6) < (f.dVar38))) != 0) && ((b((f.fVar72) < (10.0))) != 0))) != 0) && ((b((5.3) < (f.dVar28))) != 0))) != 0) && ((b(((b((3.9) < (f.dVar75))) != 0) && ((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) 3639 else 3638
        }
        3641 -> {
            if ((b((f.iVar24) != (1))) != 0) 3640 else 3636
        }
        3642 -> {
            if ((b(((b(((b((f.fVar79) <= (5.0))) != 0) || ((b((f.fVar71) <= (3.5))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1b0))) <= (72.0))) != 0) || ((b(((b((f.dVar30) <= (DAT_0012eef8))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (8.0))) != 0))) != 0))) != 0))) != 0) 3641 else 3632
        }
        3643 -> {
            131
        }
        3644 -> {
            if ((b((0.3) < (f.dVar30))) != 0) 3643 else 3642
        }
        3645 -> {
            if ((b((f.fVar67) <= (f.fVar34))) != 0) 3644 else 3632
        }
        3646 -> {
            135
        }
        3647 -> {
            if ((b((1.0) < (f.fVar67))) != 0) 3646 else 3645
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep114(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3648 -> {
            if ((b((0xa1f) < (f.iVar5))) != 0) 130 else 3631
        }
        3649 -> {
            134
        }
        3650 -> {
            135
        }
        3651 -> {
            if ((b((0.7) < (f.dVar30))) != 0) 3650 else 3649
        }
        3652 -> {
            130
        }
        3653 -> {
            if ((b(((b(((b(((b((0x7df) < (f.iVar5))) != 0) || ((b((f.fVar79) <= (6.0))) != 0))) != 0) || ((b((f.fVar67) <= (f.fVar34))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (11.0))) != 0))) != 0) 3652 else 3651
        }
        3654 -> {
            if ((b((f.iVar20) != (1))) != 0) 3653 else 3648
        }
        3655 -> {
            134
        }
        3656 -> {
            if ((b(((b(((b(((b((7.1) < (f.dVar28))) != 0) && ((b((f.fVar71) < (4.5))) != 0))) != 0) && ((b(((b((3.5) < (f.fVar71))) != 0) && ((b(((b((DAT_0012ee88) < (f.dVar38))) != 0) && ((b((12.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.iVar5) < (0xc60))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (48.0))) != 0))) != 0))) != 0) 3655 else 3654
        }
        3657 -> {
            f.fVar25 = f32(readF32(f.pjVar3))
            3626
        }
        3658 -> {
            f.fVar42 = f32(((1.0) - (f32((f.fVar67) / (f.fVar76)))))
            3657
        }
        3659 -> {
            136
        }
        3660 -> {
            f.dVar30 = ((f.dVar30) * (-(1.5)))
            3659
        }
        3661 -> {
            if ((b(((b(((b((9.5) < (f.fVar72))) != 0) && ((b((f.fVar37) < (DAT_0012f070))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x28))) < (DAT_0012f070))) != 0) && ((b(((b((42.0) < (readF32(f.param_1.plus(0x1b0))))) != 0) && ((b((readF32(f.param_1.plus(0x188))) < (DAT_0012f070))) != 0))) != 0))) != 0))) != 0) 133 else 134
        }
        3662 -> {
            if ((b(((b(((b(((b((f.dVar28) <= (5.1))) != 0) || ((b((3.5) <= (f.fVar71))) != 0))) != 0) || ((b(((b((f.dVar75) <= (DAT_0012f130))) != 0) || ((b(((b(((b((f.dVar38) <= (1.2))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (12.0))) != 0))) != 0) || ((b((0x7df) < (f.iVar5))) != 0))) != 0))) != 0))) != 0) || ((b((60.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 3656 else 3661
        }
        3663 -> {
            137
        }
        3664 -> {
            f.dVar30 = ((f.dVar30) * (-(0.3)))
            3663
        }
        3665 -> {
            f.fVar42 = f32(readF32(f.pjVar3))
            3664
        }
        3666 -> {
            if ((b(((b(((b((6.0) < (f.fVar79))) != 0) && ((b(((b(((b((f.fVar34) < (f.fVar67))) != 0) && ((b((f.dVar75) < (3.9))) != 0))) != 0) && ((b((3.0) < (f.fVar71))) != 0))) != 0))) != 0) && ((b(((b((1.5) < (f.fVar47))) != 0) && ((b((13.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0))) != 0) 3665 else 3662
        }
        3667 -> {
            f.fVar42 = f32(f32(((((((f.dVar30) / (f.fVar76))) + (1.0))) * (f.fVar42))))
            3625
        }
        3668 -> {
            f.fVar42 = f32(readF32(f.pjVar3))
            137
        }
        3669 -> {
            f.dVar30 = ((f.dVar30) * (-(0.5)))
            136
        }
        3670 -> {
            134
        }
        3671 -> {
            if ((b(((b(((b(((b((f.fVar34) < (f.fVar67))) != 0) && ((b((readF32(f.param_1.plus(0x298))) < (3.0))) != 0))) != 0) && ((b((f.fVar47) < (1.0))) != 0))) != 0) && ((b((42.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 3670 else 135
        }
        3672 -> {
            138
        }
        3673 -> {
            if ((b(((b(((b((DAT_0012f050) < (f.dVar75))) != 0) && ((b((DAT_0012ee90) < (f.dVar38))) != 0))) != 0) && ((b(((b(((b((6.5) < (readF32(f.param_1.plus(0x1fc))))) != 0) || ((b((6.5) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (60.0))) != 0))) != 0))) != 0) 3672 else 3671
        }
        3674 -> {
            if ((b(((b((f.dVar28) <= (6.8))) != 0) || ((b(((b((f.dVar30) <= (0.3))) != 0) && ((b((f.fVar47) <= (1.5))) != 0))) != 0))) != 0) 3666 else 3673
        }
        3675 -> {
            if ((b(((b((f.dVar38) <= (DAT_0012ee90))) != 0) || ((b(((b(((b(((b(((b((readF32(f.param_1.plus(0x1fc))) <= (7.5))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (7.5))) != 0))) != 0) || ((b((72.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) || ((b(((b((DAT_0012f060) <= (f.dVar75))) != 0) || ((b((f.dVar75) <= (DAT_0012f038))) != 0))) != 0))) != 0) || ((b((f.fVar72) <= (11.0))) != 0))) != 0))) != 0) 3674 else 138
        }
        3676 -> {
            if ((b((0.6) < (f.dVar38))) != 0) 3624 else 129
        }
        3677 -> {
            138
        }
        3678 -> {
            if ((b(((b(((b(((b(((b(((b((4.5) <= (f.fVar71))) != 0) && ((b((f.iVar20) != (0))) != 0))) != 0) && ((b((f.fVar67) <= (0.3))) != 0))) != 0) || ((run { run { f.iVar5 = readI32(f.param_1.plus(0x24c)); readI32(f.param_1.plus(0x24c)) }; b(((b((((f.param_14) - (f.iVar5))) < (0x121))) != 0) || ((b((f.fVar67) <= (0.0))) != 0)) }) != 0))) != 0) || ((run { run { f.dVar30 = f.fVar67; f.fVar67 }; b(((b((f.iVar5) < (0x480))) != 0) && ((b(((b((f.dVar30) < (0.35))) != 0) && ((b((1.5) < (f32((readF32(f.param_1.plus(0x250))) / (f.fVar72))))) != 0))) != 0)) }) != 0))) != 0) || ((b(((b(((b((5.5) < (f.fVar79))) != 0) && ((b(((b(((b((3.9) < (f.dVar75))) != 0) && ((b((f.iVar20) == (1))) != 0))) != 0) && ((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) || ((b(((b(((b(((b(((b((f.fVar71) < (3.5))) != 0) && ((b((1.2) < (f.dVar38))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1b0))) < (48.0))) != 0) && ((b(((b((DAT_0012ee90) < (f.fVar37))) != 0) && ((b((DAT_0012ee90) < (readF32(f.param_1.plus(0x28))))) != 0))) != 0))) != 0))) != 0) && ((b((DAT_0012ee90) < (readF32(f.param_1.plus(0x188))))) != 0))) != 0) || ((b(((b(((b(((b(((b((0.7) < (f.dVar38))) != 0) && ((b((7.0) < (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) && ((b((7.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) && ((b(((b((f.fVar67) < (0.25))) != 0) && ((b((f.dVar75) < (2.4))) != 0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (72.0))) != 0))) != 0))) != 0))) != 0))) != 0) 3677 else 3676
        }
        3679 -> {
            f.dVar28 = f.fVar79
            3678
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep115(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3680 -> {
            127
        }
        3681 -> {
            if ((b(((b((f.fVar79) < (5.0))) != 0) && ((b((readF32(f.param_1.plus(0x14))) < (2.8))) != 0))) != 0) 3680 else 3679
        }
        3682 -> {
            128
        }
        3683 -> {
            f.fVar37 = f32(f32(((f.fVar42) * (DAT_0012ef70))))
            3682
        }
        3684 -> {
            f.fVar42 = f32(f32((f32((f.fVar80) + (f.fVar42))) * (f.fVar34)))
            3683
        }
        3685 -> {
            f.fVar42 = f32(((f32((f.fVar42) + (f.param_4))) / (3.0)))
            3683
        }
        3686 -> {
            if ((b((f.fVar37) <= (f.param_4))) != 0) 3684 else 3685
        }
        3687 -> {
            f.fVar42 = f32(f32((readF32(f.param_1.plus(0x298))) + (f.fVar37)))
            3686
        }
        3688 -> {
            if ((b((readF32(f.param_1.plus(0x298))) < (f.fVar37))) != 0) 3687 else 3679
        }
        3689 -> {
            f.param_6 = f32(f.fVar37)
            3688
        }
        3690 -> {
            writeF32(f.param_1.plus(0x158), f32(f.fVar37))
            3689
        }
        3691 -> {
            writeF32(f.param_1.plus(0x24), f32(f.fVar37))
            3690
        }
        3692 -> {
            f.fVar37 = f32(((((f.fVar79) * (((11.0) - (f.fVar79))))) / (10.0)))
            3691
        }
        3693 -> {
            f.fVar37 = f32(f32((f.fVar80) * (f.fVar34)))
            3691
        }
        3694 -> {
            if ((b(((b(((b((f.param_14) < (7))) != 0) || ((b((f.fVar79) <= (7.0))) != 0))) != 0) || ((b((f.fVar80) <= (0.0))) != 0))) != 0) 3692 else 3693
        }
        3695 -> {
            if ((b(((b(((b((f.fVar72) < (7.5))) != 0) && ((b((readF32(f.param_1.plus(0x254))) < (7.0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (9.5))) != 0))) != 0) 3694 else 3679
        }
        3696 -> {
            if ((b((f.fVar79) < (5.0))) != 0) 127 else 3679
        }
        3697 -> {
            if ((b((2.8) <= (readF32(f.param_1.plus(0x27c))))) != 0) 3681 else 3696
        }
        3698 -> {
            if ((f.bVar12) != 0) 3697 else 3679
        }
        3699 -> {
            128
        }
        3700 -> {
            f.fVar37 = f32(f32((f32((f.fVar37) + (f.fVar42))) * (f.fVar34)))
            3699
        }
        3701 -> {
            if ((b((f.fVar42) < (f.fVar37))) != 0) 3700 else 3679
        }
        3702 -> {
            f.param_6 = f32(f.fVar37)
            3701
        }
        3703 -> {
            writeF32(f.param_1.plus(0x158), f32(f.fVar37))
            3702
        }
        3704 -> {
            writeF32(f.param_1.plus(0x24), f32(f.fVar37))
            3703
        }
        3705 -> {
            f.fVar37 = f32(((((f.fVar79) * (((11.0) - (f.fVar79))))) / (10.0)))
            3704
        }
        3706 -> {
            f.param_6 = f32(f.fVar37)
            3679
        }
        3707 -> {
            writeF32(f.param_1.plus(0x158), f32(f.fVar37))
            3706
        }
        3708 -> {
            writeF32(f.param_1.plus(0x24), f32(f.fVar37))
            3707
        }
        3709 -> {
            f.fVar37 = f32(f32((f.fVar37) + (f.fVar34)))
            128
        }
        3710 -> {
            if ((b(((b((f.fVar42) < (2.5))) != 0) && ((b((9.0) < (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) 3709 else 128
        }
        3711 -> {
            f.fVar37 = f32(f32((f32((f.fVar37) + (f.fVar42))) * (f.fVar34)))
            3710
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep116(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3712 -> {
            f.fVar37 = f32(((f32((f32((f.fVar37) + (f.fVar42))) + (f.param_4))) / (3.0)))
            128
        }
        3713 -> {
            if ((b((f.fVar37) <= (f.param_4))) != 0) 3711 else 3712
        }
        3714 -> {
            if ((b((f.fVar42) < (f.fVar37))) != 0) 3713 else 3679
        }
        3715 -> {
            f.param_6 = f32(f.fVar37)
            3714
        }
        3716 -> {
            writeF32(f.param_1.plus(0x158), f32(f.fVar37))
            3715
        }
        3717 -> {
            writeF32(f.param_1.plus(0x24), f32(f.fVar37))
            3716
        }
        3718 -> {
            f.fVar37 = f32(f32((f.fVar80) * (f.fVar34)))
            3717
        }
        3719 -> {
            if ((b(((b(((b((f.param_14) < (7))) != 0) || ((b((f.fVar79) <= (6.0))) != 0))) != 0) || ((b((f.fVar80) <= (0.0))) != 0))) != 0) 3705 else 3718
        }
        3720 -> {
            if ((b(((b(!((f.bVar14) != 0))) != 0) || ((run { run { f.fVar42 = f32(readF32(f.param_1.plus(0x19c))); f32(readF32(f.param_1.plus(0x19c))) }; b((3.0) <= (f.fVar42)) }) != 0))) != 0) 3698 else 3719
        }
        3721 -> {
            writeF32(f.pjVar2, f32(f.fVar42))
            3720
        }
        3722 -> {
            writeF32(f.pjVar3, f32(f.fVar42))
            3721
        }
        3723 -> {
            f.fVar34 = f32(0.5)
            3722
        }
        3724 -> {
            f.fVar42 = f32(f32(((((((((((f.fVar32) + (0.0))) * (DAT_0012f248))) + (((((f32((f32((f32((f32((f.fVar31) + (f.fVar34))) + (f.fVar42))) + (f.fVar70))) + (f.fVar25))) + (0.0))) * (DAT_0012f178))))) + (1.0))) * (readF32(f.pjVar3)))))
            3723
        }
        3725 -> {
            f.fVar25 = f32(f32(((1.0) / (((f.dVar28) + (1.0))))))
            3724
        }
        3726 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3725
        }
        3727 -> {
            f.iVar24 = 1
            3726
        }
        3728 -> {
            f.dVar28 = kotlin.math.exp(((f.dVar30) + (DAT_0012f0a0)))
            3727
        }
        3729 -> {
            if ((b(((b(((b(((b((f.fVar40) < (2.5))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) < (7.0))) != 0))) != 0) && ((run { run { f.fVar25 = f32(0.0); f32(0.0) }; b((13.0) < (f.fVar33)) }) != 0))) != 0) && ((run { run { f.fVar25 = f32(f.fVar46); f32(f.fVar46) }; b((1.0) < (f.fVar40)) }) != 0))) != 0) 3728 else 3724
        }
        3730 -> {
            f.fVar25 = f32(f.fVar46)
            3729
        }
        3731 -> {
            f.fVar46 = f32(0.0)
            3730
        }
        3732 -> {
            f.fVar25 = f32(f32(((1.0) / (((f.dVar28) + (1.0))))))
            3724
        }
        3733 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3732
        }
        3734 -> {
            f.iVar24 = 1
            3733
        }
        3735 -> {
            f.dVar28 = kotlin.math.exp(((f.dVar30) + (-(4.5))))
            3734
        }
        3736 -> {
            if ((b(((b(((b((2.8) <= (f.dVar30))) != 0) || ((b((7.5) <= (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) || ((b(((b(((b((8.0) <= (f.fVar33))) != 0) || ((b(((b((11.2) <= (f.dVar28))) != 0) || ((b((f.fVar40) <= (1.0))) != 0))) != 0))) != 0) && ((b(((b((1.5) <= (f.fVar25))) != 0) || ((b((13.0) <= (f.fVar33))) != 0))) != 0))) != 0))) != 0) 3731 else 3735
        }
        3737 -> {
            f.dVar30 = f.fVar40
            3736
        }
        3738 -> {
            f.fVar40 = f32(readF32(f.param_1.plus(0x19c)))
            3737
        }
        3739 -> {
            f.fVar25 = f32(((1.0) / (((f.fVar25) + (1.0)))))
            3724
        }
        3740 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3739
        }
        3741 -> {
            f.iVar24 = 1
            3740
        }
        3742 -> {
            f.fVar25 = f32(kotlin.math.exp(((f.fVar33) + (-(6.0)))))
            3741
        }
        3743 -> {
            if ((b((5.0) <= (f.fVar33))) != 0) 3738 else 3742
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep117(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3744 -> {
            f.fVar33 = f32(readF32(f.param_1.plus(0x250)))
            3743
        }
        3745 -> {
            f.fVar32 = f32(0.0)
            126
        }
        3746 -> {
            126
        }
        3747 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3746
        }
        3748 -> {
            f.fVar32 = f32(((1.0) / (((f.fVar32) + (1.0)))))
            3747
        }
        3749 -> {
            f.iVar24 = 1
            3748
        }
        3750 -> {
            f.fVar32 = f32(kotlin.math.exp(((12.0) - (f.param_10))))
            3749
        }
        3751 -> {
            if ((b((f.fVar32) <= (readF32(f.param_1.plus(0x298))))) != 0) 3750 else 125
        }
        3752 -> {
            f.fVar32 = f32(readF32(f.param_1.plus(0x19c)))
            3751
        }
        3753 -> {
            125
        }
        3754 -> {
            if ((b(((b((readF32(f.param_1.plus(0x250))) <= (15.0))) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) <= (8.0))) != 0))) != 0) 3753 else 3752
        }
        3755 -> {
            if ((b(((b(((b(!((f.bVar12) != 0))) != 0) || ((run { run { f.fVar32 = f32(readF32(f.param_1.plus(0x19c))); f32(readF32(f.param_1.plus(0x19c))) }; b((f.fVar32) <= (3.0)) }) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) <= (9.0))) != 0))) != 0) 3754 else 3751
        }
        3756 -> {
            if ((b((12.0) < (readF32(f.param_1.plus(0x10))))) != 0) 3755 else 125
        }
        3757 -> {
            f.fVar70 = f32(0.0)
            3756
        }
        3758 -> {
            f.fVar32 = f32(0.0)
            126
        }
        3759 -> {
            f.fVar70 = f32(f32(((1.0) / (((f.dVar30) + (1.0))))))
            3758
        }
        3760 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3759
        }
        3761 -> {
            f.iVar24 = 1
            3760
        }
        3762 -> {
            f.dVar30 = kotlin.math.exp(((f.dVar30) + (-(11.5))))
            3761
        }
        3763 -> {
            if ((b(((b(((b((11.2) <= (f.dVar30))) != 0) || ((b((6.0) <= (readF32(f.param_1.plus(0x250))))) != 0))) != 0) || ((b((3.5) <= (readF32(f.param_1.plus(0x19c))))) != 0))) != 0) 3757 else 3762
        }
        3764 -> {
            f.dVar30 = readF32(f.param_1.plus(0x10))
            3763
        }
        3765 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3764
        }
        3766 -> {
            f.fVar42 = f32(((f.fVar42) / (((f.fVar70) + (1.0)))))
            3765
        }
        3767 -> {
            f.iVar24 = 1
            3766
        }
        3768 -> {
            f.fVar42 = f32(2.0)
            3767
        }
        3769 -> {
            f.fVar70 = f32(kotlin.math.exp(f.fVar42))
            3768
        }
        3770 -> {
            f.fVar42 = f32(1.0)
            3767
        }
        3771 -> {
            f.fVar70 = f32(kotlin.math.exp(f.fVar42))
            3770
        }
        3772 -> {
            if ((b(((b(((b((3.9) <= (f.fVar70))) != 0) || ((b((3.9) <= (f.fVar32))) != 0))) != 0) || ((b(((b((60.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (9.0))) != 0))) != 0))) != 0) 3769 else 3771
        }
        3773 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3764
        }
        3774 -> {
            f.fVar42 = f32(((1.0) / (((f.fVar42) + (1.0)))))
            3773
        }
        3775 -> {
            f.iVar24 = 1
            3774
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep118(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3776 -> {
            f.fVar42 = f32(kotlin.math.exp(f.fVar42))
            3775
        }
        3777 -> {
            if ((b((readF32(f.param_1.plus(0x250))) <= (13.0))) != 0) 3772 else 3776
        }
        3778 -> {
            f.fVar42 = f32(f32((f.fVar70) - (f.fVar32)))
            3777
        }
        3779 -> {
            if ((b(((b(((b(((b(((f.bVar12) != 0) && ((run { run { f.fVar70 = f32(readF32(f.param_1.plus(0x298))); f32(readF32(f.param_1.plus(0x298))) }; b((2.8) < (f.fVar70)) }) != 0))) != 0) && ((run { run { f.fVar32 = f32(readF32(f.param_1.plus(0x19c))); f32(readF32(f.param_1.plus(0x19c))) }; b((f.fVar70) < (((f.fVar32) + (-(0.3))))) }) != 0))) != 0) && ((b(((b((4.5) < (f.fVar70))) != 0) || ((b((readF32(f.param_1.plus(0x250))) < (15.0))) != 0))) != 0))) != 0) && ((b(((b((f.dVar29) <= (5.8))) != 0) || ((b((9.0) <= (readF32(f.param_1.plus(0x254))))) != 0))) != 0))) != 0) 3778 else 3764
        }
        3780 -> {
            f.fVar42 = f32(0.0)
            3779
        }
        3781 -> {
            124
        }
        3782 -> {
            f.fVar31 = f32(((1.0) / (((f.fVar42) + (1.0)))))
            3781
        }
        3783 -> {
            f.fVar42 = f32(kotlin.math.exp(f32((f.fVar42) - (readF32(f.param_1.plus(0x298))))))
            3782
        }
        3784 -> {
            if ((b(((b(((b((DAT_0012ee78) < (f.fVar70))) != 0) && ((b((0x240) < (f.param_14))) != 0))) != 0) && ((b((0.3) < (f32((f.fVar70) / (f.fVar42))))) != 0))) != 0) 3783 else 3780
        }
        3785 -> {
            f.fVar70 = f32(f32((readF32(f.param_1.plus(0x298))) - (f.fVar42)))
            3784
        }
        3786 -> {
            if ((b((f.fVar42) < (3.9))) != 0) 3785 else 3780
        }
        3787 -> {
            f.fVar31 = f32(0.0)
            3786
        }
        3788 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x19c)))
            3787
        }
        3789 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3780
        }
        3790 -> {
            f.iVar24 = 1
            3789
        }
        3791 -> {
            f.fVar31 = f32(f32(((1.0) / (((f.dVar30) + (1.0))))))
            124
        }
        3792 -> {
            f.dVar30 = kotlin.math.exp(((f32((readF32(f.param_1.plus(0x254))) - (f.fVar80))) + (-(1.2))))
            3791
        }
        3793 -> {
            if ((b(((b((f.param_14) < (0x241))) != 0) || ((b((1.0) <= (f32((readF32(f.param_1.plus(0x254))) - (f.fVar80))))) != 0))) != 0) 3788 else 3792
        }
        3794 -> {
            f.iVar24 = 0
            3793
        }
        3795 -> {
            writeI32(f.param_1.plus(0xc), 1)
            3793
        }
        3796 -> {
            f.fVar34 = f32(((1.0) / (((f.fVar42) + (1.0)))))
            3795
        }
        3797 -> {
            f.iVar24 = 1
            3796
        }
        3798 -> {
            f.fVar42 = f32(kotlin.math.exp(((f.fVar25) + (-(2.0)))))
            3797
        }
        3799 -> {
            if ((b(((b(!((f.bVar12) != 0))) != 0) || ((b(((b(((b(((b((f.param_14) < (0x241))) != 0) || ((b(((b((0.7) <= (f.fVar70))) != 0) && ((b((0.7) <= (f.fVar31))) != 0))) != 0))) != 0) && ((b((1.5) <= (f.fVar25))) != 0))) != 0) || ((b(((b(((b((9.0) < (readF32(f.param_1.plus(0x250))))) != 0) && ((b((f.fVar42) < (14.0))) != 0))) != 0) && ((b(((f.bVar1).and(b((7.5) < (readF32(f.param_1.plus(0x1fc)))))) != 0)) != 0))) != 0))) != 0))) != 0) 3794 else 3798
        }
        3800 -> {
            f.fVar34 = f32(0.0)
            3799
        }
        3801 -> {
            if ((b(((b(uLt(((f.param_14) - (0xc)), 0x354))) != 0) && ((b((f.iVar24) == (0))) != 0))) != 0) 3800 else 3720
        }
        3802 -> {
            f.iVar24 = readI32(f.param_1.plus(0xc))
            3801
        }
        3803 -> {
            writeF32(f.param_1.plus(0x250), f32(f32(((f.dVar41) + (0.6)))))
            3802
        }
        3804 -> {
            if ((b(((b((-(1)) < (f.param_14))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (((f.dVar41) + (0.6))))) != 0))) != 0) 3803 else 3802
        }
        3805 -> {
            writeI32(f.pjVar4, 0)
            3804
        }
        3806 -> {
            f.iVar21 = 0
            3805
        }
        3807 -> {
            if ((b(((b(((b((f.iVar5) == (0))) != 0) && ((b((f32((f.fVar80) + (f32((f32((readF32(f.param_1.plus(0x254))) - (f.fVar79))) - (f.fVar79))))) < (0.0))) != 0))) != 0) && ((b((11.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) 3806 else 3804
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep119(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3808 -> {
            if ((b((f.iVar21) == (1))) != 0) 121 else 3804
        }
        3809 -> {
            122
        }
        3810 -> {
            if ((b((f.iVar21) == (2))) != 0) 3809 else 120
        }
        3811 -> {
            f.iVar21 = 2
            3804
        }
        3812 -> {
            123
        }
        3813 -> {
            if ((b(((b((1.5) < (f.fVar47))) != 0) && ((b((DAT_0012f030) < (f.dVar75))) != 0))) != 0) 3812 else 3804
        }
        3814 -> {
            f.iVar21 = 2
            3813
        }
        3815 -> {
            if ((b((readF32(f.param_1.plus(0x250))) <= (11.0))) != 0) 3811 else 3814
        }
        3816 -> {
            f.iVar21 = 2
            3804
        }
        3817 -> {
            if ((b((f.iVar5) == (0))) != 0) 3815 else 3816
        }
        3818 -> {
            writeF32(f.pjVar2, f32(readF32(f.pjVar3)))
            3804
        }
        3819 -> {
            f.iVar21 = 1
            3818
        }
        3820 -> {
            if ((b((f.iVar24) == (0))) != 0) 3819 else 3804
        }
        3821 -> {
            writeI32(f.pjVar4, 1)
            3820
        }
        3822 -> {
            f.iVar21 = 1
            3821
        }
        3823 -> {
            if ((b(((b((0.3) <= (f.fVar32))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (11.0))) != 0))) != 0) 3817 else 123
        }
        3824 -> {
            120
        }
        3825 -> {
            writeI32(f.pjVar4, 1)
            3824
        }
        3826 -> {
            f.iVar21 = 1
            3825
        }
        3827 -> {
            if ((b(((b(((b((f.fVar32) < (f.fVar34))) != 0) && ((b((10.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) && ((b((f.iVar20) == (1))) != 0))) != 0) 3826 else 3823
        }
        3828 -> {
            121
        }
        3829 -> {
            f.iVar21 = 1
            3828
        }
        3830 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3829
        }
        3831 -> {
            if ((b((readF32(f.pjVar3)) <= (readF32(f.pjVar2)))) != 0) 3830 else 3829
        }
        3832 -> {
            120
        }
        3833 -> {
            if ((b((f.iVar20) != (0))) != 0) 3832 else 3831
        }
        3834 -> {
            writeI32(f.pjVar4, 1)
            3833
        }
        3835 -> {
            f.iVar21 = 1
            3834
        }
        3836 -> {
            if ((b(((b(((b(((b((f.fVar32) < (0.3))) != 0) && ((b((11.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) && ((b((DAT_0012f098) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) || ((b(((b(((b((f.iVar5) == (0))) != 0) && ((b((11.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) && ((b(((b((1.5) < (f.fVar47))) != 0) && ((b(((b((DAT_0012f030) < (f.dVar75))) != 0) && ((b((DAT_0012f048) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0))) != 0))) != 0) 3835 else 3827
        }
        3837 -> {
            f.fVar32 = f32(f32((f.fVar80) + (f32((f32((readF32(f.param_1.plus(0x254))) - (f.fVar79))) - (f.fVar79)))))
            3836
        }
        3838 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            122
        }
        3839 -> {
            if ((b((f.iVar20) == (0))) != 0) 3838 else 122
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep120(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3840 -> {
            writeI32(f.pjVar4, 2)
            3839
        }
        3841 -> {
            if ((b(((b(((b(((b((f.param_14) < (0x5a1))) != 0) || ((b((1.5) <= (f.fVar37))) != 0))) != 0) || ((b((1.5) <= (readF32(f.param_1.plus(0x28))))) != 0))) != 0) || ((b((readF32(f.pjVar2)) < (readF32(f.pjVar3)))) != 0))) != 0) 3810 else 3840
        }
        3842 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (f.dVar30)))))
            3841
        }
        3843 -> {
            writeI32(f.pjVar4, 2)
            3842
        }
        3844 -> {
            f.iVar21 = 2
            3843
        }
        3845 -> {
            if ((b(((b(((b(((b(((b((13.0) < (f.fVar72))) != 0) && ((b((8.5) < (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) && ((b((8.5) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) && ((b(((b(((b((f.dVar29) < (DAT_0012f1d0))) != 0) && ((b((f.fVar71) < (3.0))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1b0))) < (48.0))) != 0) && ((b(((b((f.iVar20) == (0))) != 0) && ((b((readF32(f.param_1.plus(0x254))) < (10.5))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b((48.0) < (readF32(f.param_1.plus(0x2c))))) != 0) && ((b(((b((readF32(f.param_1.plus(0x298))) < (2.5))) != 0) && ((b((readF32(f.pjVar3)) <= (readF32(f.pjVar2)))) != 0))) != 0))) != 0))) != 0) 3844 else 3841
        }
        3846 -> {
            f.dVar30 = DAT_0012ee70
            3845
        }
        3847 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3846
        }
        3848 -> {
            f.iVar21 = 2
            3847
        }
        3849 -> {
            if ((b((f.iVar20) == (0))) != 0) 3848 else 3846
        }
        3850 -> {
            writeI32(f.pjVar4, 2)
            3849
        }
        3851 -> {
            f.iVar21 = 2
            3850
        }
        3852 -> {
            if ((b(((b(((b(((b(((b((11.0) < (f.fVar72))) != 0) && ((b((8.0) < (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) && ((b((8.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) && ((b(((b(((b((f.fVar80) < (5.0))) != 0) && ((b((f.dVar75) < (DAT_0012f068))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1b0))) < (48.0))) != 0) && ((b(((b((f.fVar37) < (DAT_0012f070))) != 0) && ((b((readF32(f.param_1.plus(0x28))) < (DAT_0012f070))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x188))) < (DAT_0012f070))) != 0) && ((b((readF32(f.pjVar3)) <= (readF32(f.pjVar2)))) != 0))) != 0))) != 0) 3851 else 3846
        }
        3853 -> {
            f.fVar72 = f32(readF32(f.param_1.plus(0x174)))
            3852
        }
        3854 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (f.dVar30)))))
            3853
        }
        3855 -> {
            writeI32(f.pjVar4, 2)
            3854
        }
        3856 -> {
            f.iVar21 = 2
            3855
        }
        3857 -> {
            if ((b(((b(((b(((b(((b(((b((f.dVar51) < (1.2))) != 0) && ((b((f.fVar80) < (5.0))) != 0))) != 0) && ((b((f32((readF32(f.param_1.plus(0x254))) - (f.fVar80))) < (2.5))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x298))) < (2.0))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (60.0))) != 0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1fc))) < (6.5))) != 0))) != 0) && ((b(((b((f.iVar20) == (0))) != 0) && ((b((readF32(f.pjVar3)) <= (readF32(f.pjVar2)))) != 0))) != 0))) != 0) 3856 else 3853
        }
        3858 -> {
            f.dVar51 = f.fVar61
            3857
        }
        3859 -> {
            f.dVar30 = DAT_0012ee70
            3858
        }
        3860 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3859
        }
        3861 -> {
            writeI32(f.pjVar4, 2)
            3860
        }
        3862 -> {
            f.iVar21 = 2
            3861
        }
        3863 -> {
            writeI32(f.pjVar4, 1)
            3859
        }
        3864 -> {
            f.iVar21 = 1
            3863
        }
        3865 -> {
            if ((b(((b((48.0) <= (readF32(f.param_1.plus(0x2c))))) != 0) || ((b(((b((54.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) && ((b((f.dVar75) <= (3.9))) != 0))) != 0))) != 0) 3862 else 3864
        }
        3866 -> {
            if ((b(((b(((b(((b((f.fVar61) < (1.0))) != 0) && ((b((5.0) < (f.fVar80))) != 0))) != 0) && ((b((f32((readF32(f.param_1.plus(0x254))) - (f.fVar80))) < (DAT_0012f070))) != 0))) != 0) && ((b(((b(((b((readF32(f.param_1.plus(0x298))) < (4.0))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (66.0))) != 0))) != 0) && ((b(((b((f.iVar20) == (0))) != 0) && ((b((10.0) < (readF32(f.param_1.plus(0x174))))) != 0))) != 0))) != 0))) != 0) 3865 else 3859
        }
        3867 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3866
        }
        3868 -> {
            f.iVar21 = 2
            3867
        }
        3869 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1b0))) <= (48.0))) != 0) || ((b((f.dVar38) <= (1.2))) != 0))) != 0) 3868 else 3866
        }
        3870 -> {
            writeI32(f.pjVar4, 2)
            3869
        }
        3871 -> {
            f.iVar21 = 2
            3870
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep121(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3872 -> {
            if ((b(((b(((b(((b((5.5) < (f.fVar80))) != 0) && ((b((f.fVar61) < (1.5))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x298))) < (DAT_0012f030))) != 0))) != 0) && ((b(((b(((b((54.0) < (readF32(f.param_1.plus(0x1b0))))) != 0) && ((b((10.0) < (readF32(f.param_1.plus(0x174))))) != 0))) != 0) && ((b(((b((((f.fVar61) + (1.0))) < (f32((readF32(f.param_1.plus(0x254))) - (f.fVar79))))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) < (DAT_0012ef68))) != 0))) != 0))) != 0))) != 0) 3871 else 3866
        }
        3873 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3872
        }
        3874 -> {
            writeI32(f.pjVar4, 2)
            3873
        }
        3875 -> {
            f.iVar21 = 2
            3874
        }
        3876 -> {
            if ((b(((b(((b((f.fVar61) < (1.0))) != 0) && ((b((readF32(f.param_1.plus(0x298))) < (DAT_0012f070))) != 0))) != 0) && ((b(((b(((b(((b((readF32(f.param_1.plus(0x2c))) < (48.0))) != 0) && ((b(((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0) && ((b((5.3) < (f.dVar29))) != 0))) != 0))) != 0) && ((b((f.fVar71) < (3.0))) != 0))) != 0) && ((b((f.iVar20) == (0))) != 0))) != 0))) != 0) 3875 else 3872
        }
        3877 -> {
            f.dVar29 = f.fVar80
            3876
        }
        3878 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3877
        }
        3879 -> {
            f.iVar21 = 2
            3878
        }
        3880 -> {
            if ((b((f.iVar20) == (0))) != 0) 3879 else 3877
        }
        3881 -> {
            writeI32(f.pjVar4, 2)
            3880
        }
        3882 -> {
            f.iVar21 = 2
            3881
        }
        3883 -> {
            if ((b(((b(((b((f.fVar61) < (2.0))) != 0) && ((b((5.5) < (f.fVar80))) != 0))) != 0) && ((b(((b((((f.fVar61) + (1.0))) < (f32((readF32(f.param_1.plus(0x254))) - (f.fVar79))))) != 0) && ((b(((b(((b(((b((readF32(f.param_1.plus(0x298))) < (3.3))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (54.0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1fc))) < (9.0))) != 0))) != 0) && ((b((f.dVar38) <= (DAT_0012ee88))) != 0))) != 0))) != 0))) != 0) 3882 else 3877
        }
        3884 -> {
            f.fVar61 = f32(f32((f.fVar79) - (f.fVar80)))
            3883
        }
        3885 -> {
            f.fVar80 = f32(readF32(f.param_1.plus(600)))
            3884
        }
        3886 -> {
            f.iVar21 = 1
            119
        }
        3887 -> {
            118
        }
        3888 -> {
            f.iVar21 = 1
            3887
        }
        3889 -> {
            117
        }
        3890 -> {
            if ((b(((b((f.iVar20) != (1))) != 0) && ((b(((b(((b(((b(((b((readF32(f.param_1.plus(0x1fc))) < (9.0))) != 0) && ((b((f32((readF32(f.param_1.plus(0x1fc))) - (readF32(f.param_1.plus(0x1f8))))) < (1.5))) != 0))) != 0) && ((b((readF32(f.param_1.plus(600))) < (9.0))) != 0))) != 0) && ((b(((b((DAT_0012f1b0) <= (readF32(f.param_1.plus(0x270))))) != 0) || ((b((DAT_0012f1c0) <= (((readF32(f.param_1.plus(0x26c))) - (kotlin.math.abs(readF32(f.param_1.plus(0x270)))))))) != 0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(600))) <= (5.8))) != 0))) != 0))) != 0) 3889 else 3888
        }
        3891 -> {
            if ((b(((b(((b((DAT_0012eef8) < (f32((f.fVar71) - (readF32(f.param_1.plus(0x28))))))) != 0) && ((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1f8))) < (8.0))) != 0) && ((b(((b((DAT_0012f030) < (readF32(f.param_1.plus(0x178))))) != 0) && ((b((DAT_0012eef8) < (f32((readF32(f.param_1.plus(0x188))) - (readF32(f.param_1.plus(0x28))))))) != 0))) != 0))) != 0))) != 0) 3890 else 3886
        }
        3892 -> {
            if ((b(((b(((b((0) < (f.iVar19))) != 0) && ((b((0x240) < (((f.param_14) - (f.iVar19))))) != 0))) != 0) && ((b((f.fVar34) < (f.fVar67))) != 0))) != 0) 3891 else 119
        }
        3893 -> {
            f.iVar21 = 1
            3892
        }
        3894 -> {
            writeI32(f.pjVar4, 1)
            114
        }
        3895 -> {
            117
        }
        3896 -> {
            if ((b(((b(((b((DAT_0012f188) < (f.dVar30))) != 0) || ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) < (0.3))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(600))) < (DAT_0012f1d0))) != 0) && ((b(((b((f.fVar72) <= (9.0))) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) <= (9.0))) != 0))) != 0))) != 0))) != 0) 3895 else 113
        }
        3897 -> {
            118
        }
        3898 -> {
            writeF32(f.pjVar2, f32(f32(((readF32(f.pjVar3)) * (DAT_0012ee70)))))
            3897
        }
        3899 -> {
            f.iVar21 = 2
            3898
        }
        3900 -> {
            117
        }
        3901 -> {
            if ((b(((b(((b((4.3) < (f.dVar29))) != 0) && ((b((readF32(f.param_1.plus(0x254))) < (6.5))) != 0))) != 0) || ((b((f32((f32((readF32(f.param_1.plus(0x254))) - (f32((f.fVar79) + (f.fVar79))))) - (f.fVar61))) < (DAT_0012f010))) != 0))) != 0) 3900 else 3899
        }
        3902 -> {
            113
        }
        3903 -> {
            if ((b(((b(((b(((b((f.dVar36) < (DAT_0012f028))) != 0) || ((b(((b(((b((f.dVar51) < (DAT_0012f238))) != 0) && ((b((f.dVar30) < (DAT_0012f240))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x174))) < (9.5))) != 0))) != 0))) != 0) || ((b(((b(((b((DAT_0012ee80) < (f.dVar38))) != 0) && ((b((DAT_0012f030) < (f.dVar75))) != 0))) != 0) && ((b((f.dVar75) < (DAT_0012f048))) != 0))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1b0))) < (60.0))) != 0) && ((b(((b(((b((3.9) < (f.dVar75))) != 0) && ((b((DAT_0012f150) < (f.dVar29))) != 0))) != 0) || ((b(((b((3.0) < (f.fVar71))) != 0) && ((b((f.dVar75) < (3.9))) != 0))) != 0))) != 0))) != 0))) != 0) 3902 else 3901
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep122(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3904 -> {
            113
        }
        3905 -> {
            100
        }
        3906 -> {
            if ((b(((b((f.fVar61) <= (6.0))) != 0) && ((b((8.0) <= (readF32(f.param_1.plus(0x174))))) != 0))) != 0) 3905 else 3904
        }
        3907 -> {
            109
        }
        3908 -> {
            if ((b(((b((DAT_0012f230) <= (f.dVar51))) != 0) || ((b((f.dVar36) <= (DAT_0012f1c0))) != 0))) != 0) 3907 else 3906
        }
        3909 -> {
            if ((b(((b(((b((0.3) <= (f.dVar51))) != 0) && ((b((f.dVar36) <= (DAT_0012f1c0))) != 0))) != 0) || ((b((6.0) <= (f.fVar61))) != 0))) != 0) 3908 else 3904
        }
        3910 -> {
            if ((b(((b(((b((f.dVar38) <= (DAT_0012ef60))) != 0) || ((b((5.8) <= (f.dVar29))) != 0))) != 0) || ((b((84.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 3909 else 3903
        }
        3911 -> {
            113
        }
        3912 -> {
            if ((b(((b((f.dVar36) < (DAT_0012f228))) != 0) && ((b(((b(((b((DAT_0012f1b8) < (f.dVar51))) != 0) && ((b((6.5) < (f.fVar72))) != 0))) != 0) && ((b((f.fVar61) < (4.5))) != 0))) != 0))) != 0) 3911 else 3910
        }
        3913 -> {
            115
        }
        3914 -> {
            if ((b(((b((5.3) < (f.dVar29))) != 0) && ((b((readF32(f.param_1.plus(0x254))) < (7.5))) != 0))) != 0) 3913 else 3912
        }
        3915 -> {
            f.dVar29 = f.fVar61
            3914
        }
        3916 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(600)))
            3915
        }
        3917 -> {
            114
        }
        3918 -> {
            f.iVar19 = readI32(f.param_1.plus(0x24c))
            3917
        }
        3919 -> {
            119
        }
        3920 -> {
            if ((b((f.iVar21) != (1))) != 0) 3919 else 3918
        }
        3921 -> {
            113
        }
        3922 -> {
            if ((b((DAT_0012f1b0) < (f.dVar30))) != 0) 3921 else 109
        }
        3923 -> {
            f.fVar42 = f32(f.param_12)
            3922
        }
        3924 -> {
            109
        }
        3925 -> {
            if ((b((DAT_0012f200) <= (f.dVar51))) != 0) 3924 else 108
        }
        3926 -> {
            f.fVar42 = f32(f.param_12)
            3925
        }
        3927 -> {
            112
        }
        3928 -> {
            writeI32(f.pjVar4, 1)
            3927
        }
        3929 -> {
            103
        }
        3930 -> {
            writeI32(f.pjVar4, 2)
            3929
        }
        3931 -> {
            writeF32(f.pjVar2, f32(f32(((f.fVar61) * (f.dVar29)))))
            3930
        }
        3932 -> {
            f.dVar29 = DAT_0012ee70
            3931
        }
        3933 -> {
            f.fVar61 = f32(readF32(f.pjVar3))
            3932
        }
        3934 -> {
            102
        }
        3935 -> {
            if ((b(((b(((b((3.5) <= (f.fVar32))) != 0) || ((b((6.0) <= (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) || ((b((8.0) <= (readF32(f.param_1.plus(0x250))))) != 0))) != 0) 3934 else 3933
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep123(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3936 -> {
            f.dVar29 = 0.95
            3931
        }
        3937 -> {
            f.fVar61 = f32(readF32(f.pjVar3))
            3936
        }
        3938 -> {
            if ((b(((b((5.0) <= (f.fVar32))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (10.0))) != 0))) != 0) 3935 else 3937
        }
        3939 -> {
            if ((b(((b((-(0.35)) <= (f.dVar30))) != 0) && ((b((f.fVar32) <= (6.0))) != 0))) != 0) 3938 else 3928
        }
        3940 -> {
            113
        }
        3941 -> {
            if ((b(((b((f.iVar20) == (1))) != 0) || ((b(((b(((b((DAT_0012f208) < (f.dVar28))) != 0) && ((b((8.0) < (f.fVar79))) != 0))) != 0) && ((b((f.dVar39) < (DAT_0012ef70))) != 0))) != 0))) != 0) 3940 else 3939
        }
        3942 -> {
            f.fVar42 = f32(f.param_12)
            3941
        }
        3943 -> {
            105
        }
        3944 -> {
            if ((b(((b((f.dVar39) <= (0.3))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (48.0))) != 0))) != 0) 3943 else 3942
        }
        3945 -> {
            f.bVar13 = b((1) != 0)
            3944
        }
        3946 -> {
            109
        }
        3947 -> {
            113
        }
        3948 -> {
            if ((b(((b((0x240) < (f.param_14))) != 0) && ((b(((b(((b(((b((f.fVar32) < (5.5))) != 0) && ((b((10.0) < (f.fVar48))) != 0))) != 0) && ((b((f.dVar36) < (DAT_0012ef88))) != 0))) != 0) && ((b((84.0) < (readF32(f.param_1.plus(0x2c))))) != 0))) != 0))) != 0) 3947 else 3946
        }
        3949 -> {
            100
        }
        3950 -> {
            if ((b(((b((f.dVar51) < (DAT_0012f190))) != 0) && ((b(((b(((b((DAT_0012f1f8) < (f.dVar30))) != 0) && ((b((f.fVar40) < (DAT_0012f178))) != 0))) != 0) && ((b((f.fVar32) < (6.0))) != 0))) != 0))) != 0) 3949 else 3946
        }
        3951 -> {
            113
        }
        3952 -> {
            if ((b(((b((f.dVar51) < (DAT_0012f190))) != 0) && ((b((DAT_0012f188) < (f.dVar30))) != 0))) != 0) 3951 else 3950
        }
        3953 -> {
            if ((b(((b(((b(((b((4.1) <= (f.dVar45))) != 0) || ((b((DAT_0012f080) <= (f.dVar75))) != 0))) != 0) || ((b((f.fVar46) <= (DAT_0012ee78))) != 0))) != 0) && ((b(((b(((b((readF32(f.param_1.plus(0x1b0))) <= (66.0))) != 0) || ((b((DAT_0012f048) <= (f.dVar75))) != 0))) != 0) || ((b(((b((f.fVar72) <= (7.0))) != 0) && ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) <= (DAT_0012f040))) != 0))) != 0))) != 0))) != 0) 3952 else 3946
        }
        3954 -> {
            if ((b(((b((5.0) <= (f.fVar32))) != 0) || ((b(((b((f.param_14) < (0x241))) != 0) || ((b((f.fVar48) <= (7.0))) != 0))) != 0))) != 0) 3948 else 3953
        }
        3955 -> {
            104
        }
        3956 -> {
            111
        }
        3957 -> {
            if ((b(((b(((b(((b((84.0) < (f.fVar61))) != 0) && ((b(((b((DAT_0012ee70) < (f.fVar46))) != 0) || ((b((DAT_0012ef70) < (f32((f.fVar72) - (readF32(f.param_1.plus(0x1fc))))))) != 0))) != 0))) != 0) && ((b((f.dVar75) < (3.9))) != 0))) != 0) || ((b((0) < (f.iVar20))) != 0))) != 0) 3956 else 3955
        }
        3958 -> {
            if ((b(((b(((b(((b(((b((108.0) < (f.fVar61))) != 0) || ((b(((b((72.0) < (f.fVar61))) != 0) && ((b(((f.bVar13) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) < (7.0))) != 0))) != 0))) != 0))) != 0) && ((b((7.5) < (f.fVar48))) != 0))) != 0) && ((b(((b(((b((f.fVar48) < (10.0))) != 0) && ((b((4.5) < (f.fVar32))) != 0))) != 0) && ((b((f.dVar45) < (DAT_0012f150))) != 0))) != 0))) != 0) && ((b(((b((f.fVar47) < (2.0))) != 0) && ((b((f.fVar46) < (1.5))) != 0))) != 0))) != 0) 3957 else 3954
        }
        3959 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x2c)))
            3958
        }
        3960 -> {
            if ((b((0x240) < (f.param_14))) != 0) 3959 else 3946
        }
        3961 -> {
            f.fVar42 = f32(f.param_12)
            3960
        }
        3962 -> {
            106
        }
        3963 -> {
            if ((b(((b(((b(((b((0.3) < (f.dVar39))) != 0) && ((b((0) < (f.iVar19))) != 0))) != 0) && ((b((f.fVar32) < (4.5))) != 0))) != 0) && ((b((7.0) < (f.fVar48))) != 0))) != 0) 3962 else 107
        }
        3964 -> {
            109
        }
        3965 -> {
            108
        }
        3966 -> {
            if ((b((f.dVar51) < (DAT_0012f200))) != 0) 3965 else 3964
        }
        3967 -> {
            113
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep124(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        3968 -> {
            100
        }
        3969 -> {
            if ((b(((b(((b((f.dVar51) <= (DAT_0012f200))) != 0) && ((b((f.iVar20) == (0))) != 0))) != 0) || ((b(((b((DAT_0012f1b0) <= (f.dVar30))) != 0) && ((b((f.fVar32) < (6.0))) != 0))) != 0))) != 0) 3968 else 3967
        }
        3970 -> {
            if ((b((54.0) < (readF32(f.param_1.plus(0x1b0))))) != 0) 3969 else 3966
        }
        3971 -> {
            if ((b((4.3) <= (f.dVar45))) != 0) 3970 else 3964
        }
        3972 -> {
            f.fVar42 = f32(f.param_12)
            3971
        }
        3973 -> {
            if ((b(((b((0) < (f.iVar19))) != 0) && ((b((0.3) < (f.dVar39))) != 0))) != 0) 106 else 107
        }
        3974 -> {
            if ((b(((b((5.1) <= (f.dVar45))) != 0) || ((b((f.fVar48) <= (6.5))) != 0))) != 0) 3963 else 3973
        }
        3975 -> {
            f.bVar13 = b((1) != 0)
            105
        }
        3976 -> {
            if ((b((f.fVar42) <= (DAT_0012ee90))) != 0) 3975 else 3945
        }
        3977 -> {
            if ((b(((b(((b((f.dVar39) <= (0.3))) != 0) || ((b((f.dVar75) <= (DAT_0012f060))) != 0))) != 0) || ((b(((b((f.fVar33) <= (0.3))) != 0) || ((b((f32((readF32(f.param_1.plus(0x188))) - (f.fVar80))) <= (0.3))) != 0))) != 0))) != 0) 3976 else 3926
        }
        3978 -> {
            111
        }
        3979 -> {
            102
        }
        3980 -> {
            if ((b(((b((f.dVar51) < (DAT_0012f200))) != 0) && ((b((DAT_0012f1b0) < (f.dVar30))) != 0))) != 0) 3979 else 3978
        }
        3981 -> {
            111
        }
        3982 -> {
            if ((b((f.iVar20) == (1))) != 0) 3981 else 104
        }
        3983 -> {
            if ((b(((b(((b(((b((f.fVar71) < (3.5))) != 0) && ((b((DAT_0012f018) < (f.dVar39))) != 0))) != 0) && ((b(((b((f.fVar72) < (8.0))) != 0) && ((b(((b(((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0) && ((b((f.fVar32) < (5.0))) != 0))) != 0) && ((b((DAT_0012f030) < (readF32(f.param_1.plus(0x178))))) != 0))) != 0))) != 0))) != 0) && ((b((f.fVar61) < (readF32(f.param_1.plus(0x240))))) != 0))) != 0) 3982 else 3977
        }
        3984 -> {
            f.fVar42 = f32(f.param_12)
            3917
        }
        3985 -> {
            f.fVar34 = f32(0.5)
            3984
        }
        3986 -> {
            writeI32(f.pjVar4, 1)
            112
        }
        3987 -> {
            110
        }
        3988 -> {
            if ((b(((b((DAT_0012f210) < (f.dVar30))) != 0) && ((b((f.fVar40) < (DAT_0012f178))) != 0))) != 0) 3987 else 111
        }
        3989 -> {
            102
        }
        3990 -> {
            if ((b((f.fVar32) < (6.0))) != 0) 3989 else 111
        }
        3991 -> {
            if ((b((DAT_0012f210) < (f.dVar30))) != 0) 110 else 111
        }
        3992 -> {
            if ((b((f.dVar30) <= (-(0.3)))) != 0) 3988 else 3991
        }
        3993 -> {
            if ((b(((b((f.dVar51) < (0.3))) != 0) && ((b((f.dVar36) < (DAT_0012f1e0))) != 0))) != 0) 3992 else 111
        }
        3994 -> {
            102
        }
        3995 -> {
            if ((b((f.dVar38) <= (DAT_0012f040))) != 0) 3994 else 111
        }
        3996 -> {
            if ((b(((b(((b((f.dVar30) < (-(0.3)))) != 0) || ((b((0.3) < (f.dVar51))) != 0))) != 0) || ((b((f.iVar20) == (1))) != 0))) != 0) 3993 else 3995
        }
        3997 -> {
            if ((b(((b(((b(((b((f.fVar33) <= (0.0))) != 0) || ((b((f.dVar39) <= (DAT_0012eef8))) != 0))) != 0) || ((b((8.0) <= (f.fVar72))) != 0))) != 0) || ((b(((b(((b((readF32(f.param_1.plus(0x1b0))) <= (54.0))) != 0) || ((b((readF32(f.param_1.plus(0x178))) <= (DAT_0012f030))) != 0))) != 0) || ((b((f32((readF32(f.param_1.plus(0x188))) - (f.fVar80))) <= (DAT_0012f018))) != 0))) != 0))) != 0) 3983 else 3996
        }
        3998 -> {
            105
        }
        3999 -> {
            f.bVar13 = b((0) != 0)
            3998
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep125(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4000 -> {
            if ((b((f.iVar19) < (1))) != 0) 3999 else 3997
        }
        4001 -> {
            119
        }
        4002 -> {
            f.fVar42 = f32(f.param_12)
            4001
        }
        4003 -> {
            f.fVar34 = f32(0.5)
            4002
        }
        4004 -> {
            f.iVar21 = 2
            4003
        }
        4005 -> {
            writeI32(f.pjVar4, 2)
            103
        }
        4006 -> {
            111
        }
        4007 -> {
            if ((b(((b((6.0) < (f.fVar32))) != 0) || ((b((f.iVar20) == (1))) != 0))) != 0) 4006 else 102
        }
        4008 -> {
            if ((b(((b(((b(((b(((b(((b(((b((0.15) < (f.fVar33))) != 0) && ((b((f.dVar29) < (8.1))) != 0))) != 0) && ((b((0.15) < (f32((f.fVar71) - (readF32(f.param_1.plus(0x188))))))) != 0))) != 0) && ((b(((b((DAT_0012f030) < (f.dVar75))) != 0) && ((b((readF32(f.param_1.plus(0x1fc))) < (8.5))) != 0))) != 0))) != 0) && ((b((0.15) < (f32((readF32(f.param_1.plus(0x188))) - (f.fVar80))))) != 0))) != 0) && ((b(((b((0.3) < (f.dVar39))) != 0) && ((b((0) < (f.iVar19))) != 0))) != 0))) != 0) && ((b((48.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 4007 else 4000
        }
        4009 -> {
            f.fVar33 = f32(f32((f.fVar71) - (f.fVar80)))
            4008
        }
        4010 -> {
            107
        }
        4011 -> {
            f.bVar13 = b((0) != 0)
            4010
        }
        4012 -> {
            if ((b((f.iVar7) < (0x241))) != 0) 4011 else 4009
        }
        4013 -> {
            113
        }
        4014 -> {
            117
        }
        4015 -> {
            if ((b(((b(((b((f.dVar51) < (0.3))) != 0) && ((b((DAT_0012f138) < (f.dVar30))) != 0))) != 0) && ((b(((b((f.fVar40) < (DAT_0012f1a0))) != 0) && ((b(((b((f.fVar32) < (9.0))) != 0) && ((b((readF32(f.param_1.plus(0x1fc))) < (9.0))) != 0))) != 0))) != 0))) != 0) 4014 else 4013
        }
        4016 -> {
            f.fVar42 = f32(f.param_12)
            4015
        }
        4017 -> {
            100
        }
        4018 -> {
            if ((b(((b(((b((f.dVar30) <= (0.3))) != 0) || ((b((DAT_0012f220) <= (f.fVar40))) != 0))) != 0) && ((b((f.dVar45) <= (DAT_0012f1d0))) != 0))) != 0) 4017 else 4013
        }
        4019 -> {
            109
        }
        4020 -> {
            if ((b(((b((DAT_0012f218) < (f.dVar51))) != 0) && ((b((f.dVar30) < (DAT_0012f210))) != 0))) != 0) 4019 else 4018
        }
        4021 -> {
            if ((b(((b((f.iVar20) != (1))) != 0) && ((b((f32((readF32(f.param_1.plus(0x1fc))) - (f.fVar72))) <= (1.2))) != 0))) != 0) 4020 else 4013
        }
        4022 -> {
            f.fVar42 = f32(f.param_12)
            4021
        }
        4023 -> {
            if ((b(((b((f.iVar7) < (0x241))) != 0) || ((b(((b(((b((8.0) <= (f.fVar32))) != 0) || ((b((f.param_14) < (0x241))) != 0))) != 0) || ((b((f.fVar42) <= (2.0))) != 0))) != 0))) != 0) 4016 else 4022
        }
        4024 -> {
            if ((b(((b(((b((0x240) < (f.iVar7))) != 0) && ((b((DAT_0012ef70) < (f.dVar39))) != 0))) != 0) || ((b(((b((0x240) < (f.param_14))) != 0) && ((b((f.fVar42) < (-(2.0)))) != 0))) != 0))) != 0) 4023 else 4012
        }
        4025 -> {
            113
        }
        4026 -> {
            109
        }
        4027 -> {
            if ((b((f.dVar30) < (-(0.35)))) != 0) 4026 else 4025
        }
        4028 -> {
            f.fVar42 = f32(f.param_12)
            4027
        }
        4029 -> {
            117
        }
        4030 -> {
            if ((b(((b((f32((readF32(f.param_1.plus(0x1fc))) - (f.fVar72))) <= (DAT_0012ef70))) != 0) && ((b((DAT_0012f1f8) <= (f.dVar30))) != 0))) != 0) 4029 else 4025
        }
        4031 -> {
            f.fVar42 = f32(f.param_12)
            4030
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep126(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4032 -> {
            if ((b(((b(((b((DAT_0012f1a8) <= (f.fVar40))) != 0) && ((b(((b((0.3) <= (f.dVar51))) != 0) && ((b(((b((5.5) <= (f.fVar32))) != 0) || ((b((f.fVar48) <= (9.2))) != 0))) != 0))) != 0))) != 0) || ((b((f.dVar30) <= (-(0.35)))) != 0))) != 0) 4028 else 4031
        }
        4033 -> {
            if ((b(((b(((b(((b((f.dVar51) < (0.35))) != 0) && ((b((0x7e0) < (f.param_14))) != 0))) != 0) && ((b((0x120) < (f.iVar7))) != 0))) != 0) && ((b(((b((0.15) < (f.dVar39))) != 0) && ((b((f.fVar40) < (DAT_0012f178))) != 0))) != 0))) != 0) 4032 else 4024
        }
        4034 -> {
            f.fVar40 = f32(f32((f.fVar40) + (f.fVar33)))
            4033
        }
        4035 -> {
            117
        }
        4036 -> {
            113
        }
        4037 -> {
            if ((b(((b((10.0) < (f.fVar48))) != 0) || ((b(((b(((b((f.iVar20) != (0))) != 0) || ((b((f.dVar30) <= (DAT_0012f1f8))) != 0))) != 0) || ((b((6.0) <= (f.fVar32))) != 0))) != 0))) != 0) 4036 else 4035
        }
        4038 -> {
            f.fVar42 = f32(f.param_12)
            4037
        }
        4039 -> {
            109
        }
        4040 -> {
            if ((b(((b((0.3) <= (f.dVar51))) != 0) && ((run { run { f.fVar42 = f32(f.param_12); f32(f.param_12) }; b((f.dVar30) <= (-(0.3))) }) != 0))) != 0) 4039 else 4038
        }
        4041 -> {
            if ((b(((b((2.4) < (f.fVar42))) != 0) && ((b(((b(((b((readF32(f.param_1.plus(0x1fc))) < (9.0))) != 0) && ((b((f.fVar72) < (8.5))) != 0))) != 0) && ((b((DAT_0012ef60) < (f32((f.fVar79) - (f.fVar32))))) != 0))) != 0))) != 0) 4040 else 4034
        }
        4042 -> {
            f.fVar42 = f32(f32((f32((f.fVar48) - (f.fVar79))) / (f32((f.fVar79) - (f.fVar32)))))
            4041
        }
        4043 -> {
            f.fVar48 = f32(readF32(f.param_1.plus(0x254)))
            4042
        }
        4044 -> {
            109
        }
        4045 -> {
            if ((b(((b(((b(((b((f.dVar45) < (4.8))) != 0) && ((b((f.fVar71) < (3.5))) != 0))) != 0) && ((b((1.2) < (f.fVar46))) != 0))) != 0) && ((b(((b((8.5) < (f.fVar72))) != 0) && ((b((6.5) < (readF32(f.param_1.plus(0x254))))) != 0))) != 0))) != 0) 4044 else 4043
        }
        4046 -> {
            f.fVar46 = f32(f32((f.fVar32) - (f.fVar71)))
            4045
        }
        4047 -> {
            f.dVar45 = f.fVar32
            4046
        }
        4048 -> {
            f.fVar32 = f32(readF32(f.param_1.plus(600)))
            4047
        }
        4049 -> {
            113
        }
        4050 -> {
            if ((b(((b((0x240) < (f.iVar7))) != 0) && ((b(((b(((b(((b((0.15) < (f.dVar39))) != 0) && ((b((-(0.3)) < (f.dVar30))) != 0))) != 0) && ((b((f.iVar21) == (0))) != 0))) != 0) && ((b((f.fVar61) < (readF32(f.param_1.plus(0x240))))) != 0))) != 0))) != 0) 4049 else 4048
        }
        4051 -> {
            f.dVar39 = f.fVar67
            4050
        }
        4052 -> {
            f.iVar7 = ((f.param_14) - (f.iVar19))
            4051
        }
        4053 -> {
            if ((b(((b((f32((f.fVar40) - (f.fVar32))) <= (0.0))) != 0) || ((b((0.3) <= (f.fVar32))) != 0))) != 0) 4052 else 3916
        }
        4054 -> {
            113
        }
        4055 -> {
            100
        }
        4056 -> {
            if ((b(((b(((b((DAT_0012f1b0) < (f.dVar30))) != 0) || ((b((f32((readF32(f.param_1.plus(0x178))) - (f.fVar71))) < (0.3))) != 0))) != 0) && ((b((readF32(f.param_1.plus(600))) < (DAT_0012f1d0))) != 0))) != 0) 4055 else 4054
        }
        4057 -> {
            if ((b(((b((f.dVar51) < (DAT_0012f1c8))) != 0) && ((b(((b((DAT_0012f1f0) < (f32((f.fVar32) - (f.fVar40))))) != 0) && ((b((DAT_0012f108) < (f.dVar30))) != 0))) != 0))) != 0) 4056 else 4053
        }
        4058 -> {
            if ((b(((b((f.dVar51) <= (DAT_0012f1c8))) != 0) || ((b((f.dVar36) <= (DAT_0012f1e8))) != 0))) != 0) 4057 else 3896
        }
        4059 -> {
            f.dVar30 = f.fVar33
            4058
        }
        4060 -> {
            f.dVar36 = f32((f.fVar40) - (f.fVar32))
            4059
        }
        4061 -> {
            f.dVar51 = f.fVar40
            4060
        }
        4062 -> {
            f.fVar32 = f32(kotlin.math.abs(f.fVar33))
            4061
        }
        4063 -> {
            f.fVar40 = f32(readF32(f.param_1.plus(0x26c)))
            4062
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep127(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4064 -> {
            f.fVar33 = f32(readF32(f.param_1.plus(0x270)))
            4063
        }
        4065 -> {
            101
        }
        4066 -> {
            if ((b(((b((f.dVar29) < (7.8))) != 0) && ((b(((b(((b((5.5) < (f.fVar71))) != 0) && ((b((f.fVar72) < (7.5))) != 0))) != 0) && ((b((48.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) 4065 else 4064
        }
        4067 -> {
            109
        }
        4068 -> {
            100
        }
        4069 -> {
            113
        }
        4070 -> {
            if ((b(((b(((b(((b((6.0) <= (f.fVar61))) != 0) || ((b((readF32(f.param_1.plus(0x270))) <= (-(0.3)))) != 0))) != 0) || ((b((0.3) <= (readF32(f.param_1.plus(0x26c))))) != 0))) != 0) || ((b((DAT_0012f1e0) <= (((readF32(f.param_1.plus(0x26c))) - (kotlin.math.abs(readF32(f.param_1.plus(0x270)))))))) != 0))) != 0) 4069 else 4068
        }
        4071 -> {
            if ((b(((b((f.fVar61) <= (5.0))) != 0) || ((b((2.0) <= (f32((readF32(f.param_1.plus(0x254))) - (f.fVar61))))) != 0))) != 0) 4070 else 4067
        }
        4072 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(600)))
            4071
        }
        4073 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1fc))) < (7.8))) != 0) && ((b((f.fVar47) < (1.0))) != 0))) != 0) 4072 else 4064
        }
        4074 -> {
            if ((b((f.dVar29) < (7.8))) != 0) 101 else 4064
        }
        4075 -> {
            if ((b(((b((f.fVar67) <= (0.25))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (60.0))) != 0))) != 0) 4066 else 4074
        }
        4076 -> {
            if ((b(((b(((b((1.0) < (f.fVar61))) != 0) && ((b((0x480) < (f.param_14))) != 0))) != 0) && ((b((f.fVar61) < (((readF32(f.param_1.plus(0x240))) + (-(0.3)))))) != 0))) != 0) 4075 else 4064
        }
        4077 -> {
            109
        }
        4078 -> {
            if ((b(((b(((b((DAT_0012f0b0) < (f.dVar29))) != 0) && ((b((f.fVar71) < (3.5))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x2c))) < (72.0))) != 0) && ((b((f32((f.fVar72) - (readF32(f.param_1.plus(0x1fc))))) < (1.0))) != 0))) != 0))) != 0) 4077 else 4076
        }
        4079 -> {
            f.dVar29 = f.fVar72
            4078
        }
        4080 -> {
            f.fVar72 = f32(readF32(f.param_1.plus(0x1f8)))
            4079
        }
        4081 -> {
            113
        }
        4082 -> {
            if ((b(((b(((b((f.fVar37) < (f.fVar80))) != 0) && ((b((f.fVar80) < (readF32(f.param_1.plus(0x188))))) != 0))) != 0) || ((b((f32((f.fVar37) / (f.fVar71))) < (0.6))) != 0))) != 0) 4081 else 4080
        }
        4083 -> {
            f.fVar80 = f32(readF32(f.param_1.plus(0x28)))
            4082
        }
        4084 -> {
            writeI32(f.pjVar4, f.iVar21)
            119
        }
        4085 -> {
            f.iVar21 = 2
            118
        }
        4086 -> {
            writeF32(f.pjVar2, f32(((readF32(f.pjVar3)) * (0.95))))
            117
        }
        4087 -> {
            if ((b((readF32(f.pjVar2)) == (readF32(f.pjVar3)))) != 0) 4086 else 117
        }
        4088 -> {
            113
        }
        4089 -> {
            if ((b(((b(((b((7.0) < (f.fVar61))) != 0) && ((b((f.fVar71) < (3.0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x174))) < (12.0))) != 0))) != 0) 4088 else 4087
        }
        4090 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x1f8)))
            116
        }
        4091 -> {
            113
        }
        4092 -> {
            if ((b((3.5) < (f.fVar47))) != 0) 4091 else 4090
        }
        4093 -> {
            113
        }
        4094 -> {
            116
        }
        4095 -> {
            if ((b(((b((f.fVar47) <= (3.5))) != 0) && ((run { run { f.fVar61 = f32(readF32(f.param_1.plus(0x1f8))); f32(readF32(f.param_1.plus(0x1f8))) }; b((f.fVar61) <= (9.0)) }) != 0))) != 0) 4094 else 4093
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep128(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4096 -> {
            if ((b((9.0) < (readF32(f.param_1.plus(0x1fc))))) != 0) 4095 else 4092
        }
        4097 -> {
            119
        }
        4098 -> {
            writeI32(f.pjVar4, 0)
            4097
        }
        4099 -> {
            f.iVar21 = 0
            4098
        }
        4100 -> {
            if ((b(((b((((f.param_14) - (f.iVar19))) < (0x241))) != 0) || ((b(((b(((b(((b((f32((f.fVar71) - (f.fVar37))) <= (f.fVar34))) != 0) || ((b((f32((f.fVar71) - (readF32(f.param_1.plus(0x28))))) <= (f.fVar34))) != 0))) != 0) || ((b((f32((f.fVar71) - (readF32(f.param_1.plus(0x188))))) <= (f.fVar34))) != 0))) != 0) || ((b((9.5) <= (readF32(f.param_1.plus(0x254))))) != 0))) != 0))) != 0) 115 else 4096
        }
        4101 -> {
            if ((b(((b(((b(((b((f.iVar19) < (1))) != 0) || ((b((((f.param_14) - (f.iVar19))) < (0x191))) != 0))) != 0) || ((b((DAT_0012f058) <= (f32((f.fVar71) - (f.fVar37))))) != 0))) != 0) || ((b(((b((DAT_0012f058) <= (f32((f.fVar71) - (readF32(f.param_1.plus(0x28))))))) != 0) || ((b((DAT_0012f058) <= (f32((f.fVar71) - (readF32(f.param_1.plus(0x188))))))) != 0))) != 0))) != 0) 4083 else 4100
        }
        4102 -> {
            writeF32(f.param_1.plus(0x238), f32(f.fVar71))
            4101
        }
        4103 -> {
            f.iVar19 = readI32(f.param_1.plus(0x24c))
            4102
        }
        4104 -> {
            109
        }
        4105 -> {
            if ((b(((b(!((f.bVar16) != 0))) != 0) || ((b((1) < (f.iVar21))) != 0))) != 0) 4104 else 4103
        }
        4106 -> {
            119
        }
        4107 -> {
            writeI32(f.pjVar4, 2)
            4106
        }
        4108 -> {
            f.iVar21 = 2
            4107
        }
        4109 -> {
            if ((b(((b(((b(((b(((b((f.fVar47) < (1.0))) != 0) && ((b((0.35) < (f.fVar67))) != 0))) != 0) && ((b((0x120) < (((f.param_14) - (readI32(f.param_1.plus(0x24c))))))) != 0))) != 0) && ((b(((b(((b((f.fVar61) < (readF32(f.param_1.plus(0x240))))) != 0) && ((b((f.iVar21) == (0))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1f8))) < (7.8))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1fc))) < (7.8))) != 0) && ((b((readF32(f.param_1.plus(600))) < (7.5))) != 0))) != 0))) != 0))) != 0))) != 0) && ((b(((f.bVar16).and(b((f.iVar20) == (0)))) != 0)) != 0))) != 0) 100 else 4105
        }
        4110 -> {
            f.dVar75 = f.fVar71
            4109
        }
        4111 -> {
            f.dVar38 = f.fVar47
            4110
        }
        4112 -> {
            f.fVar47 = f32(f32((f.fVar83) - (f.fVar71)))
            4111
        }
        4113 -> {
            f.fVar67 = f32(f32((f.fVar76) - (f.fVar79)))
            4112
        }
        4114 -> {
            f.fVar71 = f32(readF32(f.param_1.plus(0x194)))
            4113
        }
        4115 -> {
            f.fVar83 = f32(readF32(f.param_1.plus(0x160)))
            4114
        }
        4116 -> {
            f.iVar21 = 2
            4115
        }
        4117 -> {
            writeI32(f.pjVar4, 1)
            4115
        }
        4118 -> {
            f.iVar21 = 1
            4117
        }
        4119 -> {
            if ((b(((b((f.iVar20) != (1))) != 0) || ((b((3.5) <= (readF32(f.param_1.plus(0x194))))) != 0))) != 0) 4116 else 4118
        }
        4120 -> {
            if ((b((f.iVar21) == (2))) != 0) 4119 else 4115
        }
        4121 -> {
            f.pjVar4 = f.param_1.plus(0x234)
            4120
        }
        4122 -> {
            f.iVar21 = readI32(f.param_1.plus(0x234))
            4121
        }
        4123 -> {
            f.fVar76 = f32(f.fVar79)
            4122
        }
        4124 -> {
            writeI32(f.param_1.plus(0x24c), f.param_14)
            4123
        }
        4125 -> {
            writeF32(f.param_1.plus(0x248), f32(f.fVar79))
            4124
        }
        4126 -> {
            if ((b(((f.bVar16) != 0) && ((b((0.0) < (f32((f.fVar79) - (readF32(f.param_1.plus(0x248))))))) != 0))) != 0) 4125 else 4122
        }
        4127 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x248)))
            4126
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep129(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4128 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x1cc)))
            4127
        }
        4129 -> {
            writeI64(f.param_1.plus(0x254), (0).toLong())
            4128
        }
        4130 -> {
            99
        }
        4131 -> {
            if ((b((0xc) < (f.param_14))) != 0) 4130 else 98
        }
        4132 -> {
            writeI32(f.param_1.plus(0x224), 0)
            4131
        }
        4133 -> {
            writeI32(f.param_1.plus(0x228), readI32(f.param_1.plus(0x224)))
            4132
        }
        4134 -> {
            if ((b((readI32(f.param_1.plus(0x228))) < (readI32(f.param_1.plus(0x224))))) != 0) 4133 else 4132
        }
        4135 -> {
            writeF32(f.param_1.plus(600), f32(f32((f32((f.fVar76) + (f.param_4))) / (f32((((f.iVar21) + (1))).toDouble())))))
            4127
        }
        4136 -> {
            writeI32(f.param_1.plus(0x264), ((f.iVar21) + (1)))
            4135
        }
        4137 -> {
            writeF32(f.param_1.plus(0x268), f32(f32((f.fVar76) + (f.param_4))))
            4136
        }
        4138 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x268)))
            4137
        }
        4139 -> {
            f.iVar21 = readI32(f.param_1.plus(0x264))
            4138
        }
        4140 -> {
            writeF32(f.param_1.plus(0x254), f32(f32((f32((f.fVar76) + (f.param_4))) / (f32((((f.iVar21) + (1))).toDouble())))))
            4127
        }
        4141 -> {
            writeI32(f.param_1.plus(0x25c), ((f.iVar21) + (1)))
            4140
        }
        4142 -> {
            writeF32(f.param_1.plus(0x260), f32(f32((f.fVar76) + (f.param_4))))
            4141
        }
        4143 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x260)))
            4142
        }
        4144 -> {
            f.iVar21 = readI32(f.param_1.plus(0x25c))
            4143
        }
        4145 -> {
            if ((b((f.param_4) <= (f.fVar79))) != 0) 4139 else 4144
        }
        4146 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x1cc)))
            4145
        }
        4147 -> {
            98
        }
        4148 -> {
            if ((b((f.param_14) < (0xd))) != 0) 4147 else 99
        }
        4149 -> {
            writeI32(f.param_1.plus(0x224), ((readI32(f.param_1.plus(0x224))) + (1)))
            4148
        }
        4150 -> {
            if ((b((2.8) <= (f.dVar41))) != 0) 4134 else 4149
        }
        4151 -> {
            f.fVar37 = f32(f.param_5)
            4150
        }
        4152 -> {
            writeF32(f.param_1.plus(0x27c), f32(f.param_5))
            4151
        }
        4153 -> {
            writeF32(f.param_1.plus(0x24), f32(f.param_5))
            4152
        }
        4154 -> {
            if ((b(((b((f.param_5) < (f.fVar37))) != 0) && ((b((0.0) < (f.fVar37))) != 0))) != 0) 4153 else 4150
        }
        4155 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x24)))
            4154
        }
        4156 -> {
            writeF32(f.param_1.plus(0x1a4), f32(f.param_8))
            4155
        }
        4157 -> {
            writeF32(f.param_1.plus(0x1a8), f32(f.param_8))
            4156
        }
        4158 -> {
            writeI32(f.param_1.plus(0x1a0), 0)
            4157
        }
        4159 -> {
            writeI32(f.param_1.plus(0x21c), f.iVar19)
            4158
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep130(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4160 -> {
            if ((b((readI32(f.param_1.plus(0x21c))) < (f.iVar19))) != 0) 4159 else 4158
        }
        4161 -> {
            f.iVar19 = f.iVar21
            4160
        }
        4162 -> {
            writeI32(f.param_1.plus(0x1ac), f.iVar21)
            4161
        }
        4163 -> {
            if ((b((readI32(f.param_1.plus(0x1ac))) < (f.iVar21))) != 0) 4162 else 4160
        }
        4164 -> {
            f.iVar19 = readI32(f.param_1.plus(0x1ac))
            4163
        }
        4165 -> {
            f.iVar21 = readI32(f.param_1.plus(0x1a0))
            4164
        }
        4166 -> {
            writeI32(f.param_1.plus(0x1a0), ((readI32(f.param_1.plus(0x1a0))) + (1)))
            4155
        }
        4167 -> {
            if ((b((DAT_0012ef70) <= (f32((f.fVar79) - (f.fVar37))))) != 0) 4165 else 4166
        }
        4168 -> {
            f.fVar79 = f32(f.param_8)
            4167
        }
        4169 -> {
            writeF32(f.param_1.plus(0x1a8), f32(f.param_8))
            4168
        }
        4170 -> {
            if ((b((readF32(f.param_1.plus(0x1a8))) < (f.param_8))) != 0) 4169 else 4167
        }
        4171 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x1a8)))
            4170
        }
        4172 -> {
            f.fVar37 = f32(f.param_8)
            4171
        }
        4173 -> {
            writeF32(f.param_1.plus(0x1a4), f32(f.param_8))
            4172
        }
        4174 -> {
            if ((b((f.param_8) < (readF32(f.param_1.plus(0x1a4))))) != 0) 4173 else 4171
        }
        4175 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x1a4)))
            4174
        }
        4176 -> {
            f.fVar37 = f32(f.param_5)
            4150
        }
        4177 -> {
            writeI32(f.param_1.plus(0x14), f.uVar6)
            4176
        }
        4178 -> {
            writeF32(f.param_1.plus(0x27c), f32(f.param_5))
            4177
        }
        4179 -> {
            writeI32(f.param_1.plus(0x28), f.uVar27)
            4178
        }
        4180 -> {
            f.uVar6 = readI32(f.param_1.plus(0x27c))
            4179
        }
        4181 -> {
            writeI32(f.param_1.plus(0x188), readI32(f.param_1.plus(0x28)))
            4180
        }
        4182 -> {
            writeI32(f.param_1.plus(0x1ac), 0)
            4181
        }
        4183 -> {
            writeF32(f.param_1.plus(0x24), f32(f.param_5))
            4182
        }
        4184 -> {
            f.uVar27 = readI32(f.param_1.plus(0x24))
            4183
        }
        4185 -> {
            writeI64(f.param_1.plus(0x1a4), f.uVar11)
            4184
        }
        4186 -> {
            writeF32(f.param_1.plus(0x1b0), f32(f32((f32((f.fVar37) + (f32((f.iVar21).toDouble())))) / (f32((((f.param_14) / (0x120))).toDouble())))))
            4185
        }
        4187 -> {
            writeI32(f.param_1.plus(0x1a0), 0)
            4186
        }
        4188 -> {
            writeF32(f.param_1.plus(0x1b4), f32(f32((f.fVar37) + (f32((f.iVar21).toDouble())))))
            4187
        }
        4189 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x1b4)))
            4188
        }
        4190 -> {
            f.uVar11 = DAT_0012f1d8
            4189
        }
        4191 -> {
            writeI32(f.param_1.plus(0x21c), f.iVar21)
            4190
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep131(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4192 -> {
            if ((b((readI32(f.param_1.plus(0x21c))) < (f.iVar21))) != 0) 4191 else 4190
        }
        4193 -> {
            f.iVar21 = readI32(f.param_1.plus(0x1ac))
            4192
        }
        4194 -> {
            if ((b(((b((f.param_14) < (5))) != 0) || ((b((((f.param_14) % (0x120))) != (0))) != 0))) != 0) 4175 else 4193
        }
        4195 -> {
            writeF32(f.param_1.plus(0x168), f32(f.local_17c))
            4194
        }
        4196 -> {
            writeI32(f.param_1.plus(0x16c), f.param_14)
            4195
        }
        4197 -> {
            f.local_17c = f32(f32(((f.dVar41) + (0.6))))
            4196
        }
        4198 -> {
            if ((b(((b((3) < (f.param_14))) != 0) && ((b((f.local_17c) < (f.param_4))) != 0))) != 0) 4197 else 4194
        }
        4199 -> {
            f.local_17c = f32(readF32(f.param_1.plus(0x168)))
            4198
        }
        4200 -> {
            f.fVar26 = f32(f.param_7)
            4199
        }
        4201 -> {
            writeF32(f.param_1.plus(0x284), f32(f.param_7))
            4200
        }
        4202 -> {
            writeF32(f.param_1.plus(0x15c), f32(f.param_7))
            4201
        }
        4203 -> {
            if ((b(((b(((b((0.0) < (f.param_7))) != 0) && ((b((0x4f) < (f.param_14))) != 0))) != 0) && ((b((f.param_7) < (f.fVar26))) != 0))) != 0) 4202 else 4199
        }
        4204 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(0x15c)))
            4203
        }
        4205 -> {
            f.param_6 = f32(f.fVar26)
            4204
        }
        4206 -> {
            f.fVar26 = f32(f.param_6)
            4205
        }
        4207 -> {
            writeF32(f.param_1.plus(0x280), f32(f.param_6))
            4206
        }
        4208 -> {
            writeF32(f.param_1.plus(0x158), f32(f.param_6))
            4207
        }
        4209 -> {
            if ((b(((b(((b((0.0) < (f.param_6))) != 0) && ((b((0x17) < (f.param_14))) != 0))) != 0) && ((b((f.param_6) < (f.fVar26))) != 0))) != 0) 4208 else 4205
        }
        4210 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(0x158)))
            4209
        }
        4211 -> {
            writeF32(f.param_1.plus(0x298), f32(f.param_4))
            4210
        }
        4212 -> {
            if ((b(((b((f.param_4) < (readF32(f.param_1.plus(0x298))))) != 0) && ((b(((f.bVar12).and(b((0.0) < (readF32(f.param_1.plus(0x298)))))) != 0)) != 0))) != 0) 4211 else 4210
        }
        4213 -> {
            writeF32(f.param_1.plus(0x19c), f32(f.param_4))
            4212
        }
        4214 -> {
            if ((b(((b((0.0) < (readF32(f.param_1.plus(0x19c))))) != 0) && ((b(((f.bVar14).and(b((f.param_4) < (readF32(f.param_1.plus(0x19c)))))) != 0)) != 0))) != 0) 4213 else 4212
        }
        4215 -> {
            f.fVar61 = f32(f.param_4)
            4214
        }
        4216 -> {
            writeF32(f.param_1.plus(0x23c), f32(f.param_4))
            4215
        }
        4217 -> {
            if ((b((f.param_4) < (readF32(f.param_1.plus(0x23c))))) != 0) 4216 else 4214
        }
        4218 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x23c)))
            4217
        }
        4219 -> {
            97
        }
        4220 -> {
            if ((b(((b(!((f.bVar13) != 0))) != 0) && ((b(!((f.bVar17) != 0))) != 0))) != 0) 4219 else 95
        }
        4221 -> {
            writeF32(f.param_1.plus(0x218), f32(f.param_4))
            4220
        }
        4222 -> {
            96
        }
        4223 -> {
            writeF32(f.param_1.plus(0x290), f32(f.fVar26))
            4222
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep132(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4224 -> {
            writeF32(f.param_1.plus(0x28c), f32(f.fVar61))
            4223
        }
        4225 -> {
            if ((b((0xc) < (f.param_14))) != 0) 4224 else 94
        }
        4226 -> {
            f.fVar26 = f32(f.param_7)
            4194
        }
        4227 -> {
            f.fVar61 = f32(50.0)
            4226
        }
        4228 -> {
            f.local_17c = f32(0.0)
            4227
        }
        4229 -> {
            writeI32(f.param_1.plus(0x244), f.uVar27)
            4228
        }
        4230 -> {
            writeI32(f.param_1.plus(0x23c), 0x42480000)
            4229
        }
        4231 -> {
            f.uVar27 = readI32(f.param_1.plus(0x23c))
            4230
        }
        4232 -> {
            writeF32(f.param_1.plus(0x240), f32(readF32(f.param_1.plus(0x244))))
            4231
        }
        4233 -> {
            if ((b(((b(uLt(((f.param_14) - (0x480)), 0x6c0))) != 0) && ((b((readF32(f.param_1.plus(0x244))) < (readF32(f.param_1.plus(0x240))))) != 0))) != 0) 4232 else 4231
        }
        4234 -> {
            writeF32(f.param_1.plus(0x284), f32(f.param_7))
            4233
        }
        4235 -> {
            writeI32(f.param_1.plus(0x164), f.uVar27)
            4234
        }
        4236 -> {
            writeI32(f.param_1.plus(0x180), readI32(f.param_1.plus(0x164)))
            4235
        }
        4237 -> {
            writeF32(f.param_1.plus(0x15c), f32(f.param_7))
            4236
        }
        4238 -> {
            f.uVar27 = readI32(f.param_1.plus(0x15c))
            4237
        }
        4239 -> {
            writeF32(f.param_1.plus(0x1c), f32(f32((f.fVar26) / (f.fVar61))))
            4238
        }
        4240 -> {
            if ((b((f.param_14) < (0x7e1))) != 0) 4239 else 4238
        }
        4241 -> {
            writeF32(f.param_1.plus(0x160), f32(f32((f.fVar37) / (f.fVar61))))
            4240
        }
        4242 -> {
            writeF32(f.param_1.plus(0x178), f32(f32((f.fVar79) / (f.fVar61))))
            4241
        }
        4243 -> {
            writeF32(f.param_1.plus(0x194), f32(f32((f.fVar26) / (f.fVar61))))
            4242
        }
        4244 -> {
            if ((b(((f.lVar18).toInt()) != (0x5c))) != 0) 4251 else 4243
        }
        4245 -> {
            f.fVar37 = f32(f32((f.fVar37) + (readF32(f.param_1.plus((f.lVar10).toInt())))))
            4244
        }
        4246 -> {
            f.fVar79 = f32(f32((f.fVar79) + (readF32(f.param_1.plus((f.lVar9).toInt())))))
            4245
        }
        4247 -> {
            f.fVar26 = f32(f32((f.fVar26) + (readF32(f.param_1.plus((f.lVar8).toInt())))))
            4246
        }
        4248 -> {
            f.lVar18 = ((f.lVar18) + ((4).toLong()))
            4247
        }
        4249 -> {
            f.lVar10 = ((f.lVar18) + ((0xfc).toLong()))
            4248
        }
        4250 -> {
            f.lVar9 = ((f.lVar18) + ((0xa0).toLong()))
            4249
        }
        4251 -> {
            f.lVar8 = ((f.lVar18) + ((0x44).toLong()))
            4250
        }
        4252 -> {
            writeI32(f.param_1.plus(0x17c), f.uVar27)
            4251
        }
        4253 -> {
            writeF32(f.param_1.plus(0x280), f32(f.param_6))
            4252
        }
        4254 -> {
            writeF32(f.param_1.plus(0x158), f32(f.param_6))
            4253
        }
        4255 -> {
            writeI32(f.param_1.plus(0x168), 0)
            4254
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep133(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4256 -> {
            writeI32(f.param_1.plus(0x154), readI32(f.param_1.plus(0x284)))
            4255
        }
        4257 -> {
            writeI32(f.param_1.plus(0xf8), readI32(f.param_1.plus(0x280)))
            4256
        }
        4258 -> {
            writeI32(f.param_1.plus(0x9c), readI32(f.param_1.plus(0x27c)))
            4257
        }
        4259 -> {
            f.fVar37 = f32(0.0)
            4258
        }
        4260 -> {
            writeF32(f.param_1.plus(0x174), f32(f32((f32((f.fVar37) + (readF32(f.param_1.plus(0x168))))) / (f.fVar61))))
            4259
        }
        4261 -> {
            f.fVar79 = f32(0.0)
            4260
        }
        4262 -> {
            f.fVar26 = f32(0.0)
            4261
        }
        4263 -> {
            f.lVar18 = 0
            4262
        }
        4264 -> {
            writeF32(f.param_1.plus(0x170), f32(f32((f.fVar37) + (readF32(f.param_1.plus(0x168))))))
            4263
        }
        4265 -> {
            f.uVar27 = readI32(f.param_1.plus(0x158))
            4264
        }
        4266 -> {
            writeI32(f.param_1.plus(0x184), readI32(f.param_1.plus(0x17c)))
            4265
        }
        4267 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x170)))
            4266
        }
        4268 -> {
            f.fVar61 = f32(f32((((f.param_14) / (0x120))).toDouble()))
            4267
        }
        4269 -> {
            if ((b(((f.lVar18).toInt()) != (0))) != 0) 4274 else 4268
        }
        4270 -> {
            writeI32(f.pjVar4.plus(0xb4), readI32(f.pjVar4.plus(0xb8)))
            4269
        }
        4271 -> {
            writeI32(f.pjVar4.plus(0x58), readI32(f.pjVar4.plus(0x5c)))
            4270
        }
        4272 -> {
            writeI32(f.pjVar4.plus(-(4)), readI32(f.pjVar4))
            4271
        }
        4273 -> {
            f.lVar18 = ((f.lVar18) + ((4).toLong()))
            4272
        }
        4274 -> {
            f.pjVar4 = f.param_1.plus((f.lVar18).toInt()).plus(0xa0)
            4273
        }
        4275 -> {
            f.lVar18 = -(0x58)
            4274
        }
        4276 -> {
            95
        }
        4277 -> {
            if ((b(((f.bVar13) != 0) || ((f.bVar17) != 0))) != 0) 4276 else 97
        }
        4278 -> {
            writeF32(f.param_1.plus(0x218), f32(f.param_4))
            4277
        }
        4279 -> {
            writeF32(f.param_1.plus(0x1f0), f32(f32((f32((f.fVar61) + (readF32(f.param_1.plus(0x1f0))))) - (f.param_4))))
            4278
        }
        4280 -> {
            writeI32(f.param_1.plus(500), ((f.iVar21) + (1)))
            4279
        }
        4281 -> {
            94
        }
        4282 -> {
            writeI64(f.param_1.plus(0x1f0), (0).toLong())
            4281
        }
        4283 -> {
            writeF32(f.param_1.plus(0x1d4), f32(f32((readF32(f.param_1.plus(0x1d4))) + (readF32(f.param_1.plus(0x1f0))))))
            4282
        }
        4284 -> {
            writeF32(f.param_1.plus(0x1e4), f32(f32((readF32(f.param_1.plus(0x1e4))) + (f32((f.iVar21).toDouble())))))
            4283
        }
        4285 -> {
            writeI32(f.param_1.plus(0x204), f.iVar22)
            4284
        }
        4286 -> {
            f.iVar22 = ((f.iVar22) + (1))
            4285
        }
        4287 -> {
            if ((b((4) < (f.iVar21))) != 0) 4286 else 4282
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep134(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4288 -> {
            if ((b((DAT_0012f020) < (f32((f.fVar61) - (f.param_4))))) != 0) 4287 else 4280
        }
        4289 -> {
            f.iVar21 = readI32(f.param_1.plus(500))
            4288
        }
        4290 -> {
            writeI64(f.param_1.plus(0x1e8), (0).toLong())
            4289
        }
        4291 -> {
            writeF32(f.param_1.plus(0x1e0), f32(f32((readF32(f.param_1.plus(0x1e0))) + (f32((f.iVar21).toDouble())))))
            4290
        }
        4292 -> {
            writeF32(f.param_1.plus(0x1d0), f32(f32((readF32(f.param_1.plus(0x1d0))) + (readF32(f.param_1.plus(0x1e8))))))
            4291
        }
        4293 -> {
            writeI32(f.param_1.plus(0x200), f.iVar23)
            4292
        }
        4294 -> {
            f.iVar23 = ((f.iVar23) + (1))
            4293
        }
        4295 -> {
            if ((b((4) < (f.iVar21))) != 0) 4294 else 4290
        }
        4296 -> {
            writeF32(f.param_1.plus(0x1e8), f32(f32((f32((f.fVar61) + (readF32(f.param_1.plus(0x1e8))))) - (f.param_4))))
            4289
        }
        4297 -> {
            writeI32(f.param_1.plus(0x1ec), ((f.iVar21) + (1)))
            4296
        }
        4298 -> {
            if ((b((f32((f.fVar61) - (f.param_4))) <= (DAT_0012f018))) != 0) 4295 else 4297
        }
        4299 -> {
            f.iVar21 = readI32(f.param_1.plus(0x1ec))
            4298
        }
        4300 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x218)))
            4299
        }
        4301 -> {
            94
        }
        4302 -> {
            if ((b((f.param_14) < (0xd))) != 0) 4301 else 96
        }
        4303 -> {
            writeF32(f.param_1.plus(0x290), f32(f32((readF32(f.param_1.plus(0x214))) / (f.fVar61))))
            4302
        }
        4304 -> {
            writeF32(f.param_1.plus(0x28c), f32(f32((readF32(f.param_1.plus(0x20c))) / (f.fVar61))))
            4303
        }
        4305 -> {
            f.fVar61 = f32(f32((readI32(f.param_1.plus(0x288))).toDouble()))
            4304
        }
        4306 -> {
            if ((b((readI32(f.param_1.plus(0x288))) < (1))) != 0) 4225 else 4305
        }
        4307 -> {
            f.fVar26 = f32(f.param_4)
            4306
        }
        4308 -> {
            writeF32(f.param_1.plus(0x210), f32(f.param_4))
            4307
        }
        4309 -> {
            if ((b((f.fVar26) < (f.param_4))) != 0) 4308 else 4306
        }
        4310 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(0x210)))
            4309
        }
        4311 -> {
            f.fVar61 = f32(f.param_4)
            4310
        }
        4312 -> {
            writeF32(f.param_1.plus(0x208), f32(f.param_4))
            4311
        }
        4313 -> {
            if ((b((f.param_4) < (readF32(f.param_1.plus(0x208))))) != 0) 4312 else 4310
        }
        4314 -> {
            f.fVar61 = f32(readF32(f.param_1.plus(0x208)))
            4313
        }
        4315 -> {
            f.fVar26 = f32(0.0)
            4306
        }
        4316 -> {
            writeI32(f.param_1.plus(0x208), 0x42c80000)
            4315
        }
        4317 -> {
            writeF32(f.param_1.plus(0x214), f32(f32((readF32(f.param_1.plus(0x214))) + (f.fVar26))))
            4316
        }
        4318 -> {
            writeI32(f.param_1.plus(0x210), 0)
            4317
        }
        4319 -> {
            f.fVar61 = f32(100.0)
            4318
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep135(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4320 -> {
            writeF32(f.param_1.plus(0x20c), f32(f.fVar61))
            4319
        }
        4321 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(0x210)))
            4320
        }
        4322 -> {
            f.fVar61 = f32(f32((readF32(f.param_1.plus(0x20c))) + (readF32(f.param_1.plus(0x208)))))
            4321
        }
        4323 -> {
            f.fVar61 = f32(f32((readF32(f.param_1.plus(0x20c))) + (f32((f32((readF32(f.param_1.plus(0x208))) + (f.fVar61))) * (f.fVar34)))))
            4321
        }
        4324 -> {
            if ((b(((b((0x240) < (f.param_14))) != 0) || ((b((2.5) <= (readF32(f.param_1.plus(0x19c))))) != 0))) != 0) 4322 else 4323
        }
        4325 -> {
            writeF32(f.param_1.plus(0x1dc), f32(f32((readF32(f.param_1.plus(0x1e4))) / (f32((f.iVar22).toDouble())))))
            4324
        }
        4326 -> {
            if ((b((0) < (f.iVar22))) != 0) 4325 else 4324
        }
        4327 -> {
            writeF32(f.param_1.plus(0x1d8), f32(f32((readF32(f.param_1.plus(0x1e0))) / (f32((f.iVar23).toDouble())))))
            4326
        }
        4328 -> {
            if ((b((0) < (f.iVar23))) != 0) 4327 else 4326
        }
        4329 -> {
            writeI32(f.param_1.plus(0x288), ((readI32(f.param_1.plus(0x288))) + (1)))
            4328
        }
        4330 -> {
            if ((b(((f.bVar13) != 0) || ((f.bVar17) != 0))) != 0) 4314 else 4329
        }
        4331 -> {
            f.bVar13 = b((b((f.param_14) < (1))) != 0)
            4330
        }
        4332 -> {
            f.bVar17 = b((b((((f.param_14) % (0x120))) != (0))) != 0)
            4331
        }
        4333 -> {
            writeF32(f.param_1.plus(0x270), f32(f32((readF32(f.param_1.plus(0x1d4))) / (readF32(f.param_1.plus(0x1e4))))))
            4332
        }
        4334 -> {
            writeF32(f.param_1.plus(0x1fc), f32(f32((readF32(f.param_1.plus(0x1e4))) / (f32((f.iVar22).toDouble())))))
            4333
        }
        4335 -> {
            if ((b((0) < (f.iVar22))) != 0) 4334 else 4332
        }
        4336 -> {
            f.iVar22 = readI32(f.param_1.plus(0x204))
            4335
        }
        4337 -> {
            writeF32(f.param_1.plus(0x26c), f32(f32((readF32(f.param_1.plus(0x1d0))) / (readF32(f.param_1.plus(0x1e0))))))
            4336
        }
        4338 -> {
            writeF32(f.param_1.plus(0x1f8), f32(f32((readF32(f.param_1.plus(0x1e0))) / (f32((f.iVar23).toDouble())))))
            4337
        }
        4339 -> {
            if ((b((0) < (f.iVar23))) != 0) 4338 else 4336
        }
        4340 -> {
            f.iVar23 = readI32(f.param_1.plus(0x200))
            4339
        }
        4341 -> {
            92
        }
        4342 -> {
            91
        }
        4343 -> {
            if ((b((readI32(f.param_1.plus(0x18))) == (0))) != 0) 4342 else 4341
        }
        4344 -> {
            if ((b(((b((f.iVar20) == (1))) != 0) && ((b(((b(((b((readF32(f.param_1.plus(600))) < (7.5))) != 0) && ((b((12.0) < (readF32(f.param_1.plus(0x254))))) != 0))) != 0) && ((b((0.6) < (f32((readF32(f.param_1.plus(600))) + (f32((f32((readF32(f.param_1.plus(0x254))) - (f.fVar61))) - (f.fVar61))))))) != 0))) != 0))) != 0) 4343 else 93
        }
        4345 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x20)))
            93
        }
        4346 -> {
            writeI32(f.param_1.plus(0x230), readI32(f.param_1.plus(0x20)))
            4345
        }
        4347 -> {
            writeI32(f.param_1.plus(0x198), 0)
            4346
        }
        4348 -> {
            f.iVar20 = 0
            4347
        }
        4349 -> {
            93
        }
        4350 -> {
            if ((b(((b(((b((4.8) < (readF32(f.param_1.plus(600))))) != 0) && ((b((readF32(f.param_1.plus(0x254))) < (7.8))) != 0))) != 0) && ((b(((b((72.0) < (readF32(f.param_1.plus(0x1b0))))) != 0) || ((b(((b((DAT_0012f1d0) < (readF32(f.param_1.plus(600))))) != 0) && ((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0))) != 0) 4349 else 91
        }
        4351 -> {
            90
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep136(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4352 -> {
            if ((b(((b((DAT_0012f1c8) <= (f.fVar79))) != 0) || ((b(((b((f.fVar76) <= (-(0.3)))) != 0) || ((b((readI32(f.param_1.plus(0x18))) != (0))) != 0))) != 0))) != 0) 4351 else 4350
        }
        4353 -> {
            if ((b(((b((f.iVar20) == (1))) != 0) || ((b((f.fVar37) < (f.fVar26))) != 0))) != 0) 4352 else 93
        }
        4354 -> {
            if ((b((f.dVar38) <= (DAT_0012f1c0))) != 0) 90 else 4353
        }
        4355 -> {
            f.iVar20 = 1
            93
        }
        4356 -> {
            93
        }
        4357 -> {
            writeI32(f.param_1.plus(0x230), readI32(f.param_1.plus(0x20)))
            4356
        }
        4358 -> {
            writeF32(f.param_1.plus(0x22c), f32(f.fVar37))
            4357
        }
        4359 -> {
            writeI32(f.param_1.plus(0x198), 0)
            4358
        }
        4360 -> {
            f.iVar20 = 0
            4359
        }
        4361 -> {
            if ((b(((b(((b(((b(((b(((b((f.fVar26) <= (5.3))) != 0) || ((b((8.2) <= (readF32(f.param_1.plus(0x254))))) != 0))) != 0) || ((b((60.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) && ((b(((b((f.fVar26) <= (6.0))) != 0) || ((b((8.6) <= (readF32(f.param_1.plus(0x254))))) != 0))) != 0))) != 0) && ((b(((b((f.fVar26) <= (6.5))) != 0) || ((b(((b((11.0) <= (readF32(f.param_1.plus(0x254))))) != 0) || ((b((f32((readF32(f.param_1.plus(0x160))) - (readF32(f.param_1.plus(0x194))))) <= (1.2))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.fVar26) <= (5.3))) != 0) || ((b(((b((8.0) <= (readF32(f.param_1.plus(0x254))))) != 0) || ((b((DAT_0012ef60) <= (f32((readF32(f.param_1.plus(0x160))) - (readF32(f.param_1.plus(0x194))))))) != 0))) != 0))) != 0))) != 0) 4360 else 92
        }
        4362 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(600)))
            4361
        }
        4363 -> {
            if ((b(((b(((b(((b((f.fVar26) < (f.fVar37))) != 0) || ((b((DAT_0012f1b8) <= (kotlin.math.abs(f.fVar76)))) != 0))) != 0) || ((b((f.dVar38) <= (DAT_0012f028))) != 0))) != 0) || ((b(((b((f.iVar20) != (1))) != 0) || ((b((readI32(f.param_1.plus(0x18))) != (0))) != 0))) != 0))) != 0) 4354 else 4362
        }
        4364 -> {
            f.dVar38 = ((f.fVar79) - (kotlin.math.abs(f.fVar76)))
            4363
        }
        4365 -> {
            f.fVar37 = f32(f.fVar26)
            4364
        }
        4366 -> {
            writeF32(f.param_1.plus(0x22c), f32(f.fVar26))
            4365
        }
        4367 -> {
            writeF32(f.param_1.plus(0x230), f32(f.fVar26))
            4366
        }
        4368 -> {
            writeI32(f.param_1.plus(0x198), 0)
            4367
        }
        4369 -> {
            f.iVar20 = 0
            4368
        }
        4370 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(0x20)))
            4369
        }
        4371 -> {
            f.iVar20 = 1
            4364
        }
        4372 -> {
            if ((b((readI32(f.param_1.plus(0x18))) == (0))) != 0) 4370 else 4371
        }
        4373 -> {
            if ((b(((b(((b(((b((f.fVar79) < (0.3))) != 0) && ((b((f32((f.fVar79) + (f.fVar76))) < (DAT_0012f1a0))) != 0))) != 0) || ((b((f32((f.fVar79) + (f.fVar76))) < (DAT_0012f1a8))) != 0))) != 0) && ((b((DAT_0012f1b0) < (f.fVar76))) != 0))) != 0) 4372 else 4364
        }
        4374 -> {
            f.iVar20 = 1
            4373
        }
        4375 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x270)))
            4374
        }
        4376 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x26c)))
            4375
        }
        4377 -> {
            f.fVar42 = f32(f.param_12)
            88
        }
        4378 -> {
            f.fVar26 = f32(f.fVar37)
            4377
        }
        4379 -> {
            f.fVar34 = f32(0.5)
            4378
        }
        4380 -> {
            writeF32(f.pjVar2, f32(f.fVar37))
            51
        }
        4381 -> {
            writeI32(f.pjVar4, 1)
            4380
        }
        4382 -> {
            63
        }
        4383 -> {
            writeI32(f.pjVar4, 1)
            4382
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep137(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4384 -> {
            writeF32(f.pjVar3, f32(f.fVar37))
            50
        }
        4385 -> {
            f.fVar37 = f32(f32(((((((f.dVar75) + (f.dVar29))) / (f.dVar75))) + (f.dVar38))))
            4384
        }
        4386 -> {
            if ((b((6.8) < (f.fVar83))) != 0) 49 else 4381
        }
        4387 -> {
            f.dVar29 = DAT_0012f180
            4386
        }
        4388 -> {
            82
        }
        4389 -> {
            if ((b(((b(((b(((b((7.8) <= (f.fVar83))) != 0) || ((run { run { f.dVar75 = readF32(f.param_1.plus(600)); readF32(f.param_1.plus(600)) }; b((f.dVar75) <= (DAT_0012f150)) }) != 0))) != 0) || ((b(((b((f.fVar67) <= (48.0))) != 0) || ((b(((b((7.8) <= (readF32(f.param_1.plus(0x1fc))))) != 0) || ((b((0.3) < (f.fVar47))) != 0))) != 0))) != 0))) != 0) || ((b((7.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) 4388 else 4387
        }
        4390 -> {
            88
        }
        4391 -> {
            f.fVar42 = f32(f.param_12)
            4390
        }
        4392 -> {
            f.fVar26 = f32(f.fVar37)
            4391
        }
        4393 -> {
            writeF32(f.pjVar2, f32(f.fVar37))
            4392
        }
        4394 -> {
            writeI32(f.pjVar4, 1)
            4393
        }
        4395 -> {
            if ((b(((b(((b(((b((f.fVar71) < (4.5))) != 0) && ((b((3.8) < (f.fVar71))) != 0))) != 0) && ((b((DAT_0012ef70) < (f32((f.fVar79) - (f.fVar71))))) != 0))) != 0) && ((b((f.fVar67) < (66.0))) != 0))) != 0) 4394 else 4389
        }
        4396 -> {
            88
        }
        4397 -> {
            f.fVar42 = f32(f.param_12)
            4396
        }
        4398 -> {
            f.fVar37 = f32(f.fVar26)
            4397
        }
        4399 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4398
        }
        4400 -> {
            writeI32(f.pjVar4, 1)
            4399
        }
        4401 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4400
        }
        4402 -> {
            f.fVar26 = f32(f32(((((((f.fVar76) + (-(4.5)))) / (f.fVar76))) + (f.dVar38))))
            4401
        }
        4403 -> {
            if ((b(((b((5.0) < (f.fVar76))) != 0) && ((b((5.6) < (f.dVar75))) != 0))) != 0) 4402 else 4395
        }
        4404 -> {
            88
        }
        4405 -> {
            f.fVar42 = f32(f.param_12)
            4404
        }
        4406 -> {
            f.fVar37 = f32(f.fVar26)
            4405
        }
        4407 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4406
        }
        4408 -> {
            writeI32(f.pjVar4, 1)
            48
        }
        4409 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4408
        }
        4410 -> {
            f.fVar26 = f32(((f.fVar37) + (((2.0) / (f.fVar71)))))
            4409
        }
        4411 -> {
            82
        }
        4412 -> {
            48
        }
        4413 -> {
            writeI32(f.pjVar4, 1)
            4412
        }
        4414 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4413
        }
        4415 -> {
            f.fVar26 = f32(f32(((((((f.dVar29) + (f.dVar75))) / (f.dVar75))) + (f.dVar38))))
            4414
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep138(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4416 -> {
            f.dVar29 = -(5.3)
            4415
        }
        4417 -> {
            if ((b(!((f.bVar13) != 0))) != 0) 4416 else 4415
        }
        4418 -> {
            f.bVar13 = b((b((f.fVar42) < (0.6))) != 0)
            4417
        }
        4419 -> {
            if ((b(((b((f.fVar47) < (f.fVar34))) != 0) && ((run { run { f.bVar13 = b((0) != 0); b((0) != 0) }; b(!((b((f.fVar42).isNaN())) != 0)) }) != 0))) != 0) 4418 else 4417
        }
        4420 -> {
            f.bVar13 = b((0) != 0)
            4419
        }
        4421 -> {
            f.dVar29 = -(5.1)
            4420
        }
        4422 -> {
            if ((b((f.fVar61) <= (6.0))) != 0) 4421 else 4420
        }
        4423 -> {
            f.dVar29 = -(5.5)
            4422
        }
        4424 -> {
            if ((b(((b(((b((5.0) <= (f.fVar71))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (7.0))) != 0))) != 0) && ((b(((b((f32((f.fVar79) - (f.fVar71))) <= (0.6))) != 0) || ((b((f.fVar61) <= (6.0))) != 0))) != 0))) != 0) 4423 else 4411
        }
        4425 -> {
            if ((b(((b((readF32(f.param_1.plus(0x174))) <= (15.0))) != 0) || ((b((f.fVar71) <= (6.0))) != 0))) != 0) 4424 else 4410
        }
        4426 -> {
            if ((b(((b((DAT_0012f078) < (f.fVar71))) != 0) && ((b(((b(((b((kotlin.math.abs(f.fVar47)) < (0.6))) != 0) && ((b((f.fVar83) < (8.0))) != 0))) != 0) && ((b((5.6) < (f.dVar75))) != 0))) != 0))) != 0) 4425 else 4403
        }
        4427 -> {
            f.dVar75 = f.fVar61
            4426
        }
        4428 -> {
            52
        }
        4429 -> {
            if ((b(((b(((b(((b((DAT_0012f010) <= (f.fVar47))) != 0) || ((b(((b((f.fVar83) <= (6.6))) != 0) || ((b((f.fVar79) <= (f.fVar76))) != 0))) != 0))) != 0) || ((b((6.0) <= (f.fVar76))) != 0))) != 0) || ((run { run { f.fVar67 = f32(readF32(f.param_1.plus(0x1b0))); f32(readF32(f.param_1.plus(0x1b0))) }; b(((b((f.fVar67) <= (66.0))) != 0) || ((b((10.0) <= (readF32(f.param_1.plus(0x250))))) != 0)) }) != 0))) != 0) 4428 else 4427
        }
        4430 -> {
            f.fVar47 = f32(f32((f.fVar76) - (f.fVar71)))
            4429
        }
        4431 -> {
            f.fVar71 = f32(readF32(f.param_1.plus(0x194)))
            4430
        }
        4432 -> {
            52
        }
        4433 -> {
            if ((b(((b((2.0) <= (f32((f.fVar83) - (readF32(f.param_1.plus(600))))))) != 0) || ((b((5.5) <= (f32((readF32(f.param_1.plus(0x174))) - (f.fVar76))))) != 0))) != 0) 4432 else 4431
        }
        4434 -> {
            f.fVar83 = f32(readF32(f.param_1.plus(0x254)))
            4433
        }
        4435 -> {
            f.fVar42 = f32(f.param_12)
            88
        }
        4436 -> {
            f.fVar37 = f32(f.fVar26)
            4435
        }
        4437 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4436
        }
        4438 -> {
            writeI32(f.pjVar4, 1)
            4437
        }
        4439 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4438
        }
        4440 -> {
            f.fVar26 = f32(f32(((f.dVar38) * (DAT_0012ee78))))
            4439
        }
        4441 -> {
            89
        }
        4442 -> {
            f.fVar42 = f32(f.param_12)
            4441
        }
        4443 -> {
            f.iVar20 = 0
            4442
        }
        4444 -> {
            88
        }
        4445 -> {
            f.fVar42 = f32(f.param_12)
            4444
        }
        4446 -> {
            f.fVar37 = f32(f.fVar26)
            4445
        }
        4447 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4446
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep139(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4448 -> {
            writeI32(f.pjVar4, 1)
            4447
        }
        4449 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4448
        }
        4450 -> {
            f.fVar26 = f32(((f.fVar37) + (((((f.fVar67) + (-(6.0)))) / (f.fVar67)))))
            4449
        }
        4451 -> {
            78
        }
        4452 -> {
            if ((b((f.dVar75) < (DAT_0012f160))) != 0) 4451 else 4450
        }
        4453 -> {
            f.fVar26 = f32(f32(((((((f.dVar75) + (f.dVar29))) / (f.dVar75))) + (f.dVar38))))
            4449
        }
        4454 -> {
            f.dVar29 = -(5.5)
            78
        }
        4455 -> {
            if ((b((f.fVar67) < (6.5))) != 0) 4454 else 78
        }
        4456 -> {
            if ((b((9.0) <= (f.fVar71))) != 0) 4452 else 4455
        }
        4457 -> {
            f.dVar29 = DAT_0012f168
            4456
        }
        4458 -> {
            77
        }
        4459 -> {
            if ((b(((b(((b(((b((f.dVar51) <= (DAT_0012ef60))) != 0) && ((b((f.fVar47) <= (8.5))) != 0))) != 0) || ((b((f.fVar67) <= (6.0))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x298))) <= (DAT_0012f030))) != 0) || ((b((13.0) <= (readF32(f.param_1.plus(0x250))))) != 0))) != 0))) != 0) 4458 else 4457
        }
        4460 -> {
            88
        }
        4461 -> {
            f.fVar42 = f32(f.param_12)
            4460
        }
        4462 -> {
            f.fVar37 = f32(f.fVar26)
            4461
        }
        4463 -> {
            writeI32(f.pjVar4, 1)
            4462
        }
        4464 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4463
        }
        4465 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4464
        }
        4466 -> {
            f.fVar26 = f32(f32(((((((f.dVar75) + (f.dVar29))) / (f.dVar75))) + (f.dVar38))))
            4465
        }
        4467 -> {
            f.dVar29 = -(6.3)
            4466
        }
        4468 -> {
            f.dVar29 = -(6.6)
            4466
        }
        4469 -> {
            if ((b((f.dVar75) <= (6.8))) != 0) 4467 else 4468
        }
        4470 -> {
            f.fVar26 = f32(((f.fVar37) + (((((f.fVar67) + (-(7.0)))) / (f.fVar67)))))
            4465
        }
        4471 -> {
            if ((b((f.fVar67) <= (7.0))) != 0) 4469 else 4470
        }
        4472 -> {
            if ((b(((b(((b((DAT_0012ef60) < (f.dVar51))) != 0) && ((b((6.5) < (f.fVar67))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1b0))) < (60.0))) != 0))) != 0) 4471 else 4459
        }
        4473 -> {
            82
        }
        4474 -> {
            70
        }
        4475 -> {
            f.dVar36 = DAT_0012f158
            4474
        }
        4476 -> {
            if ((b((f.fVar67) <= (5.5))) != 0) 4475 else 4474
        }
        4477 -> {
            f.dVar36 = -(5.5)
            4476
        }
        4478 -> {
            66
        }
        4479 -> {
            f.dVar36 = ((f.dVar75) + (-(5.5)))
            4478
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep140(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4480 -> {
            if ((b(((b((5.5) < (f.fVar67))) != 0) && ((b(((b((DAT_0012f0b0) < (f.dVar39))) != 0) || ((b((48.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) 4479 else 4477
        }
        4481 -> {
            if ((b(((b(((b(((b(((b((DAT_0012ee80) < (f.dVar51))) != 0) && ((b((DAT_0012f150) < (f.dVar75))) != 0))) != 0) && ((b((f.fVar67) < (6.0))) != 0))) != 0) && ((b(((b((7.0) < (readF32(f.param_1.plus(0x1f8))))) != 0) && ((b((f.fVar71) < (8.0))) != 0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (10.0))) != 0))) != 0) 4480 else 4473
        }
        4482 -> {
            88
        }
        4483 -> {
            f.fVar42 = f32(f.param_12)
            4482
        }
        4484 -> {
            f.fVar37 = f32(f.fVar26)
            4483
        }
        4485 -> {
            writeI32(f.pjVar4, 1)
            4484
        }
        4486 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4485
        }
        4487 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4486
        }
        4488 -> {
            f.fVar26 = f32(f32(((((((f.dVar29) + (f.dVar75))) / (f.dVar75))) + (f.dVar38))))
            4487
        }
        4489 -> {
            f.dVar29 = -(6.5)
            4488
        }
        4490 -> {
            if ((b((f.fVar67) <= (7.0))) != 0) 4489 else 4488
        }
        4491 -> {
            f.dVar29 = -(6.8)
            4490
        }
        4492 -> {
            if ((b(((b(((b((1.0) < (f.fVar79))) != 0) || ((b((8.5) < (f.fVar47))) != 0))) != 0) && ((b(((b((f.fVar71) < (11.0))) != 0) && ((b((6.5) < (f.fVar67))) != 0))) != 0))) != 0) 4491 else 4481
        }
        4493 -> {
            83
        }
        4494 -> {
            f.dVar30 = ((((f.dVar75) + (-(7.3)))) / (f.dVar75))
            4493
        }
        4495 -> {
            if ((b(((b(((b(((b((1.0) < (f.fVar79))) != 0) || ((b((8.5) < (f.fVar47))) != 0))) != 0) && ((b((f.fVar71) < (11.5))) != 0))) != 0) && ((b((7.5) < (f.fVar67))) != 0))) != 0) 4494 else 4492
        }
        4496 -> {
            if ((b((10.0) <= (f.fVar71))) != 0) 77 else 4472
        }
        4497 -> {
            68
        }
        4498 -> {
            f.dVar29 = -(5.8)
            4497
        }
        4499 -> {
            if ((b((8.5) <= (f.fVar71))) != 0) 4498 else 4497
        }
        4500 -> {
            f.dVar29 = -(5.5)
            4499
        }
        4501 -> {
            if ((b(((b(((b((1.0) < (f.fVar79))) != 0) && ((b((f.fVar71) < (9.0))) != 0))) != 0) && ((b(((b((6.0) < (f.fVar67))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1b0))) < (60.0))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (13.0))) != 0))) != 0))) != 0))) != 0) 4500 else 4496
        }
        4502 -> {
            82
        }
        4503 -> {
            76
        }
        4504 -> {
            if ((b((f.fVar67) <= (6.0))) != 0) 4503 else 4502
        }
        4505 -> {
            67
        }
        4506 -> {
            75
        }
        4507 -> {
            f.dVar29 = -(5.5)
            4506
        }
        4508 -> {
            74
        }
        4509 -> {
            if ((b((0.3) <= (readF32(f.param_1.plus(0x26c))))) != 0) 4508 else 4507
        }
        4510 -> {
            73
        }
        4511 -> {
            if ((b(((b(((b(((b((2.0) < (f.fVar79))) != 0) && ((b((f.dVar30) < (DAT_0012f170))) != 0))) != 0) && ((b((8.0) < (f.fVar47))) != 0))) != 0) && ((b((8.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) 4510 else 4509
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep141(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4512 -> {
            if ((b((5.5) < (f.fVar67))) != 0) 4511 else 4505
        }
        4513 -> {
            if ((b(((b(((b((2.0) <= (f.fVar79))) != 0) || ((b(((b((f.dVar29) <= (8.2))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (8.2))) != 0))) != 0))) != 0) && ((b(((b((DAT_0012f0b0) <= (f.dVar29))) != 0) || ((b(((b((3.5) <= (readF32(f.param_1.plus(0x298))))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (39.0))) != 0))) != 0))) != 0))) != 0) 4512 else 4502
        }
        4514 -> {
            if ((b(((b((f.fVar67) <= (6.0))) != 0) && ((b(((b((f.dVar36) <= (-(0.35)))) != 0) || ((b((readF32(f.param_1.plus(0x240))) <= (readF32(f.param_1.plus(0x23c))))) != 0))) != 0))) != 0) 76 else 4502
        }
        4515 -> {
            if ((b((((f.param_14) - (readI32(f.param_1.plus(0x24c))))) < (0x241))) != 0) 4504 else 4514
        }
        4516 -> {
            if ((b((f.fVar67) <= (7.0))) != 0) 4515 else 4502
        }
        4517 -> {
            if ((b(((b(((b(((b((DAT_0012ee90) < (f.dVar51))) != 0) && ((b(((b((f.dVar36) < (DAT_0012f148))) != 0) || ((b((kotlin.math.abs(f.fVar42)) < (readF32(f.param_1.plus(0x26c))))) != 0))) != 0))) != 0) && ((b((5.1) < (f.dVar75))) != 0))) != 0) && ((b((2.9) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) 4516 else 4501
        }
        4518 -> {
            85
        }
        4519 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4518
        }
        4520 -> {
            f.fVar26 = f32(f32(((((((f.dVar75) + (DAT_0012f158))) / (f.dVar75))) + (f.dVar38))))
            4519
        }
        4521 -> {
            f.fVar26 = f32(((f.fVar37) + (((((f.fVar67) + (-(5.0)))) / (f.fVar67)))))
            4519
        }
        4522 -> {
            if ((b(((b((f.dVar30) <= (3.3))) != 0) || ((b((54.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 4520 else 4521
        }
        4523 -> {
            if ((b(((b(((b((f.dVar39) < (DAT_0012f140))) != 0) && ((b((DAT_0012ee80) < (f.dVar51))) != 0))) != 0) && ((b(((b((5.1) < (f.dVar75))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (10.0))) != 0))) != 0))) != 0) 4522 else 4517
        }
        4524 -> {
            82
        }
        4525 -> {
            61
        }
        4526 -> {
            f.dVar36 = ((((f.dVar75) + (f.dVar29))) / (f.dVar75))
            4525
        }
        4527 -> {
            f.dVar29 = -(5.3)
            75
        }
        4528 -> {
            f.dVar29 = -(5.6)
            75
        }
        4529 -> {
            if ((b((f.dVar75) <= (5.6))) != 0) 74 else 4528
        }
        4530 -> {
            62
        }
        4531 -> {
            f.fVar37 = f32(((f.fVar37) + (((((f.fVar67) + (-(5.0)))) / (f.fVar67)))))
            4530
        }
        4532 -> {
            if ((b((readF32(f.param_1.plus(0x2c))) <= (60.0))) != 0) 73 else 4529
        }
        4533 -> {
            if ((b(((b(((b(((b((-(0.3)) <= (f.dVar36))) != 0) || ((b((0.3) <= (readF32(f.param_1.plus(0x26c))))) != 0))) != 0) || ((b((DAT_0012f178) <= (f32((f.fVar42) + (readF32(f.param_1.plus(0x26c))))))) != 0))) != 0) && ((b(((b((3.9) <= (readF32(f.param_1.plus(0x298))))) != 0) && ((b(((b(((b((f.fVar76) <= (10.5))) != 0) || ((b((f.fVar83) <= (60.0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (6.5))) != 0))) != 0))) != 0))) != 0) 4532 else 4524
        }
        4534 -> {
            50
        }
        4535 -> {
            writeF32(f.pjVar3, f32(f.fVar37))
            4534
        }
        4536 -> {
            f.fVar37 = f32(f32(((((((f.dVar75) + (f.dVar29))) / (f.dVar75))) + (f.dVar38))))
            72
        }
        4537 -> {
            f.dVar29 = -(5.8)
            71
        }
        4538 -> {
            69
        }
        4539 -> {
            f.fVar42 = f32(-(6.0))
            4538
        }
        4540 -> {
            if ((b(((b((8.5) <= (f.fVar71))) != 0) || ((b((6.5) <= (f.fVar67))) != 0))) != 0) 4539 else 4537
        }
        4541 -> {
            if ((b((6.0) < (f.fVar67))) != 0) 4540 else 4533
        }
        4542 -> {
            if ((b(((b(((b((f.fVar83) <= (60.0))) != 0) || ((b((f.fVar76) <= (11.0))) != 0))) != 0) || ((b(((b((f.fVar47) <= (6.5))) != 0) && ((b((readF32(f.param_1.plus(0x1f8))) <= (6.5))) != 0))) != 0))) != 0) 4541 else 4524
        }
        4543 -> {
            if ((b(((b(((b(((b(((b((f.dVar39) < (DAT_0012f0b0))) != 0) && ((b((5.3) < (f.dVar75))) != 0))) != 0) && ((b((f.fVar47) < (8.5))) != 0))) != 0) && ((run { run { f.fVar83 = f32(readF32(f.param_1.plus(0x1b0))); f32(readF32(f.param_1.plus(0x1b0))) }; b(((b((48.0) < (f.fVar83))) != 0) && ((b((f32((f32((f.fVar71) - (f.fVar61))) / (f.fVar76))) < (DAT_0012ee90))) != 0)) }) != 0))) != 0) && ((run { run { f.fVar76 = f32(readF32(f.param_1.plus(0x250))); f32(readF32(f.param_1.plus(0x250))) }; b((f.fVar76) < (14.0)) }) != 0))) != 0) 4542 else 4523
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep142(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4544 -> {
            66
        }
        4545 -> {
            f.dVar36 = ((f.dVar36) + (f.dVar75))
            4544
        }
        4546 -> {
            f.dVar36 = -(5.0)
            70
        }
        4547 -> {
            if ((b((DAT_0012ef68) <= (f.fVar42))) != 0) 4546 else 70
        }
        4548 -> {
            f.dVar36 = DAT_0012f180
            4547
        }
        4549 -> {
            f.fVar34 = f32(0.5)
            4548
        }
        4550 -> {
            82
        }
        4551 -> {
            if ((b(((b((f.dVar51) < (DAT_0012ef60))) != 0) || ((run { run { f.fVar42 = f32(readF32(f.param_1.plus(0x1f8))); f32(readF32(f.param_1.plus(0x1f8))) }; b((f.fVar42) < (7.0)) }) != 0))) != 0) 4550 else 4549
        }
        4552 -> {
            f.fVar34 = f32(0.5)
            4551
        }
        4553 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x1f8)))
            4549
        }
        4554 -> {
            if ((b((f.dVar30) < (DAT_0012f078))) != 0) 4552 else 4553
        }
        4555 -> {
            49
        }
        4556 -> {
            if ((b(((b((f.fVar71) < (6.5))) != 0) && ((run { run { f.dVar29 = DAT_0012f0a8; DAT_0012f0a8 }; b((72.0) < (readF32(f.param_1.plus(0x1b0)))) }) != 0))) != 0) 4555 else 4554
        }
        4557 -> {
            72
        }
        4558 -> {
            f.fVar37 = f32(f32((f.fVar37) + (f32((f32((f.fVar67) + (f.fVar42))) / (f.fVar67)))))
            4557
        }
        4559 -> {
            f.fVar42 = f32(-(5.0))
            69
        }
        4560 -> {
            71
        }
        4561 -> {
            f.dVar29 = -(5.5)
            4560
        }
        4562 -> {
            if ((b(((b(((b((8.0) <= (f.fVar71))) != 0) && ((b((DAT_0012f188) <= (f.dVar36))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x26c))) <= (DAT_0012f190))) != 0))) != 0) 4561 else 4559
        }
        4563 -> {
            if ((b((5.5) < (f.fVar67))) != 0) 4562 else 4556
        }
        4564 -> {
            82
        }
        4565 -> {
            if ((b(((b(((b((0.85) < (f.dVar51))) != 0) && ((b((10.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x19c))) < (3.5))) != 0))) != 0) 4564 else 4563
        }
        4566 -> {
            48
        }
        4567 -> {
            writeI32(f.pjVar4, 1)
            4566
        }
        4568 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4567
        }
        4569 -> {
            f.fVar26 = f32(f32(((((((f.dVar75) + (f.dVar29))) / (f.dVar75))) + (f.dVar38))))
            4568
        }
        4570 -> {
            f.dVar29 = -(5.8)
            68
        }
        4571 -> {
            if ((b((6.0) < (f.fVar67))) != 0) 4570 else 4565
        }
        4572 -> {
            if ((b(((b(((b((f.dVar39) < (7.8))) != 0) && ((b((5.1) < (f.dVar75))) != 0))) != 0) && ((b(((b(((b((f.fVar47) < (8.0))) != 0) && ((b((48.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) && ((b(((b(((b((f.fVar76) < (DAT_0012ef70))) != 0) || ((b((f32((f32((f.fVar71) - (f.fVar61))) / (f.fVar76))) < (DAT_0012ee90))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (11.0))) != 0))) != 0))) != 0))) != 0) 4571 else 4543
        }
        4573 -> {
            f.fVar76 = f32(f32((f.fVar61) - (f.fVar67)))
            4572
        }
        4574 -> {
            f.dVar39 = f.fVar71
            4573
        }
        4575 -> {
            84
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep143(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4576 -> {
            f.fVar83 = f32(((((f.fVar67) + (-(5.0)))) / (f.fVar67)))
            4575
        }
        4577 -> {
            82
        }
        4578 -> {
            if ((b(((b(((b(((b((f.fVar67) <= (5.0))) != 0) || ((b((f.fVar67) <= (5.5))) != 0))) != 0) || ((b((2.3) <= (f.fVar80))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.5))) != 0))) != 0) 4577 else 67
        }
        4579 -> {
            59
        }
        4580 -> {
            f.fVar26 = f32(f32(((((((f.dVar75) + (-(5.6)))) / (f.dVar75))) + (f.dVar38))))
            4579
        }
        4581 -> {
            f.fVar26 = f32(((f.fVar37) + (((((f.fVar67) + (-(6.0)))) / (f.fVar67)))))
            4579
        }
        4582 -> {
            if ((b((f.dVar75) <= (6.3))) != 0) 4580 else 4581
        }
        4583 -> {
            if ((b((6.0) < (f.fVar67))) != 0) 4582 else 4578
        }
        4584 -> {
            66
        }
        4585 -> {
            f.dVar36 = ((f.dVar75) + (-(6.5)))
            4584
        }
        4586 -> {
            if ((b((7.0) < (f.fVar67))) != 0) 4585 else 4583
        }
        4587 -> {
            if ((b(((b((f.fVar71) < (10.0))) != 0) && ((b((f.dVar36) < (DAT_0012f138))) != 0))) != 0) 4586 else 4574
        }
        4588 -> {
            if ((b(((b((f.param_12) <= (13.5))) != 0) || ((b(((b((0.6) <= (f.fVar70))) != 0) && ((b((0.6) <= (f.fVar31))) != 0))) != 0))) != 0) 4587 else 82
        }
        4589 -> {
            79
        }
        4590 -> {
            if ((b(((b((f.fVar83) < (4.5))) != 0) && ((run { run { f.dVar39 = DAT_0012f198; DAT_0012f198 }; b((3.9) < (f.dVar30)) }) != 0))) != 0) 4589 else 82
        }
        4591 -> {
            if ((b(((b(((b((DAT_0012f0d0) <= (f.dVar36))) != 0) || ((b((kotlin.math.abs(f.fVar42)) <= (readF32(f.param_1.plus(0x26c))))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x26c))) <= (DAT_0012ef38))) != 0))) != 0) 4588 else 4590
        }
        4592 -> {
            f.dVar36 = f.fVar42
            4591
        }
        4593 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x270)))
            4592
        }
        4594 -> {
            80
        }
        4595 -> {
            f.dVar39 = ((f.dVar30) + (f.dVar39))
            4594
        }
        4596 -> {
            if ((b(((b((DAT_0012f078) < (f.dVar30))) != 0) && ((b(((b(((b((f.dVar30) < (5.3))) != 0) && ((b((DAT_0012f010) < (f.dVar45))) != 0))) != 0) && ((b((1.0) < (f32((f.fVar67) - (f.fVar83))))) != 0))) != 0))) != 0) 79 else 82
        }
        4597 -> {
            if ((b(((b(((b((f.fVar76) <= (5.5))) != 0) || ((b((10.5) <= (readF32(f.param_1.plus(0x174))))) != 0))) != 0) || ((b(((b((5.5) <= (f32((readF32(f.param_1.plus(0x174))) - (f.fVar83))))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (66.0))) != 0))) != 0))) != 0) 4593 else 4596
        }
        4598 -> {
            81
        }
        4599 -> {
            f.dVar36 = ((f.dVar36) / (f.dVar75))
            4598
        }
        4600 -> {
            f.dVar36 = ((f.dVar75) + (DAT_0012f0a8))
            66
        }
        4601 -> {
            if ((b(((b((5.0) < (f.fVar67))) != 0) && ((b(((b(((b(((b((f.fVar80) < (1.5))) != 0) && ((b((3.9) < (readF32(f.param_1.plus(0x298))))) != 0))) != 0) && ((b((f.fVar71) < (7.0))) != 0))) != 0) && ((b((60.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) 4600 else 4597
        }
        4602 -> {
            54
        }
        4603 -> {
            f.dVar38 = ((f.dVar36) + (f.dVar38))
            4602
        }
        4604 -> {
            f.dVar36 = ((f.dVar39) / (f.dVar30))
            81
        }
        4605 -> {
            f.dVar39 = ((f.dVar30) + (-(4.5)))
            80
        }
        4606 -> {
            if ((b(((b(((b((4.8) <= (f.dVar30))) != 0) || ((b((readF32(f.param_1.plus(0x2c))) <= (48.0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (10.0))) != 0))) != 0) 4605 else 82
        }
        4607 -> {
            if ((b(((b(((b((1.0) <= (kotlin.math.abs(f.fVar42)))) != 0) || ((b(((b(((b((2.0) <= (f.fVar80))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (72.0))) != 0))) != 0) || ((b((8.0) <= (f32((readF32(f.param_1.plus(0x174))) - (f.fVar76))))) != 0))) != 0))) != 0) || ((b(((b(((b(((b((f.fVar83) <= (4.5))) != 0) || ((b((DAT_0012f130) <= (f.fVar80))) != 0))) != 0) || ((b((f.dVar75) <= (5.3))) != 0))) != 0) || ((b(((b((60.0) <= (readF32(f.param_1.plus(0x2c))))) != 0) || ((b((13.0) <= (readF32(f.param_1.plus(0x250))))) != 0))) != 0))) != 0))) != 0) 4601 else 4606
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep144(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4608 -> {
            f.dVar75 = f.fVar67
            4607
        }
        4609 -> {
            f.fVar80 = f32(f32((f.fVar71) - (f.fVar67)))
            4608
        }
        4610 -> {
            f.fVar67 = f32(readF32(f.param_1.plus(600)))
            4609
        }
        4611 -> {
            81
        }
        4612 -> {
            f.dVar36 = ((((f.dVar36) + (DAT_0012f0a8))) / (f.dVar36))
            4611
        }
        4613 -> {
            if ((b(((b(((b((5.5) <= (f.fVar67))) != 0) || ((b((84.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x250))) <= (10.0))) != 0) || ((b((4.5) <= (readF32(f.param_1.plus(0x298))))) != 0))) != 0))) != 0) 4612 else 82
        }
        4614 -> {
            79
        }
        4615 -> {
            82
        }
        4616 -> {
            if ((b(((b(((b(((b((f.dVar30) <= (DAT_0012f078))) != 0) || ((b((5.3) <= (f.dVar30))) != 0))) != 0) || ((b((f.dVar45) <= (DAT_0012f010))) != 0))) != 0) || ((b((f32((f.fVar67) - (f.fVar83))) <= (1.0))) != 0))) != 0) 4615 else 4614
        }
        4617 -> {
            86
        }
        4618 -> {
            f.dVar75 = -(4.3)
            4617
        }
        4619 -> {
            if ((b((readF32(f.param_1.plus(0x2c))) < (60.0))) != 0) 4618 else 4616
        }
        4620 -> {
            82
        }
        4621 -> {
            if ((b(((b((f.dVar30) < (4.3))) != 0) || ((b((9.0) < (f32((readF32(f.param_1.plus(0x174))) - (f.fVar76))))) != 0))) != 0) 4620 else 4619
        }
        4622 -> {
            if ((b(((b((f.dVar36) <= (DAT_0012f150))) != 0) || ((b((DAT_0012ee90) <= (f32((f.fVar71) - (f.fVar67))))) != 0))) != 0) 4621 else 4613
        }
        4623 -> {
            88
        }
        4624 -> {
            f.fVar42 = f32(f.param_12)
            4623
        }
        4625 -> {
            f.fVar26 = f32(f.fVar37)
            4624
        }
        4626 -> {
            writeF32(f.pjVar2, f32(f.fVar37))
            4625
        }
        4627 -> {
            writeI32(f.pjVar4, 1)
            4626
        }
        4628 -> {
            writeF32(f.pjVar3, f32(f.fVar37))
            4627
        }
        4629 -> {
            f.fVar37 = f32(f32(((((((f.dVar30) + (-(3.5)))) / (f.dVar30))) + (f.dVar38))))
            4628
        }
        4630 -> {
            writeF32(f.pjVar2, f32(f32(((((((f.dVar30) + (-(3.3)))) / (f.dVar30))) + (f.dVar38)))))
            4627
        }
        4631 -> {
            if ((b((f.fVar79) <= (1.0))) != 0) 4629 else 4630
        }
        4632 -> {
            if ((b(((b(((b((3.5) < (f.fVar83))) != 0) && ((b((f.dVar30) < (4.3))) != 0))) != 0) && ((b((DAT_0012f150) < (f.dVar36))) != 0))) != 0) 4631 else 4622
        }
        4633 -> {
            f.dVar36 = f.fVar67
            4632
        }
        4634 -> {
            65
        }
        4635 -> {
            if ((b(((b(((b(((b((3.0) <= (f32((f.fVar71) - (f.fVar67))))) != 0) || ((b(((b((7.8) <= (f.dVar29))) != 0) && ((b((7.8) <= (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) || ((b((f.fVar71) <= (6.5))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x174))) <= (7.8))) != 0) || ((b((11.0) <= (readF32(f.param_1.plus(0x250))))) != 0))) != 0))) != 0) 4634 else 4633
        }
        4636 -> {
            f.fVar67 = f32(readF32(f.param_1.plus(600)))
            4635
        }
        4637 -> {
            if ((b(((b(((b((0.6) <= (f.dVar45))) != 0) || ((b((0.7) <= (kotlin.math.abs(f.fVar42)))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (66.0))) != 0))) != 0) 65 else 4636
        }
        4638 -> {
            f.dVar39 = DAT_0012f0a8
            4637
        }
        4639 -> {
            f.dVar45 = f32((f.fVar76) - (f.fVar83))
            4638
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep145(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4640 -> {
            51
        }
        4641 -> {
            writeF32(f.pjVar2, f32(f.fVar37))
            4640
        }
        4642 -> {
            writeI32(f.pjVar4, 1)
            63
        }
        4643 -> {
            writeF32(f.pjVar3, f32(f.fVar37))
            4642
        }
        4644 -> {
            f.fVar37 = f32(f32(((f.dVar36) + (f.dVar38))))
            62
        }
        4645 -> {
            f.dVar36 = ((((f.dVar36) + (f.dVar75))) / (f.dVar36))
            61
        }
        4646 -> {
            f.dVar75 = -(5.5)
            60
        }
        4647 -> {
            if ((b((5.5) <= (f.fVar67))) != 0) 4646 else 60
        }
        4648 -> {
            f.dVar75 = DAT_0012f158
            4647
        }
        4649 -> {
            f.fVar37 = f32(((f.fVar37) + (((((f.fVar67) + (-(5.0)))) / (f.fVar67)))))
            62
        }
        4650 -> {
            60
        }
        4651 -> {
            f.dVar75 = -(5.3)
            4650
        }
        4652 -> {
            if ((b((5.5) <= (f.fVar67))) != 0) 4651 else 4649
        }
        4653 -> {
            if ((b((60.0) < (readF32(f.param_1.plus(0x2c))))) != 0) 4648 else 4652
        }
        4654 -> {
            82
        }
        4655 -> {
            if ((b(((b(((b(((b(((b(((b((f.fVar47) <= (6.0))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (6.0))) != 0))) != 0) || ((b((f.dVar36) <= (5.3))) != 0))) != 0) || ((b((DAT_0012ee90) <= (f32((f.fVar71) - (f.fVar67))))) != 0))) != 0) || ((b(((b(((b((0.85) < (f.dVar51))) != 0) && ((b((11.0) < (f.fVar80))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x19c))) < (3.5))) != 0))) != 0))) != 0) || ((b(((b(((b((60.0) < (f.fVar42))) != 0) && ((b((10.5) < (f.fVar80))) != 0))) != 0) && ((b((6.5) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0) 4654 else 4653
        }
        4656 -> {
            58
        }
        4657 -> {
            f.dVar75 = -(4.5)
            4656
        }
        4658 -> {
            57
        }
        4659 -> {
            if ((b((DAT_0012f150) < (f.dVar36))) != 0) 4658 else 4657
        }
        4660 -> {
            if ((b((f.fVar80) < (10.0))) != 0) 4659 else 4655
        }
        4661 -> {
            81
        }
        4662 -> {
            f.dVar36 = ((((ARR_0012f2c8[b((f.fVar67) < (5.5))]) + (f.dVar36))) / (f.dVar36))
            4661
        }
        4663 -> {
            82
        }
        4664 -> {
            if ((b(((b((10.5) < (f.fVar80))) != 0) && ((b((6.5) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) 4663 else 4662
        }
        4665 -> {
            if ((b(((b(((b((f32((f.fVar71) - (f.fVar67))) < (DAT_0012f070))) != 0) && ((b((72.0) < (f.fVar42))) != 0))) != 0) && ((b(((b((f32((f.fVar61) - (f.fVar67))) < (1.0))) != 0) && ((b((readF32(f.param_1.plus(0x2c))) < (60.0))) != 0))) != 0))) != 0) 4664 else 4660
        }
        4666 -> {
            88
        }
        4667 -> {
            f.fVar42 = f32(f.param_12)
            4666
        }
        4668 -> {
            f.fVar37 = f32(f.fVar26)
            4667
        }
        4669 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4668
        }
        4670 -> {
            writeI32(f.pjVar4, 1)
            4669
        }
        4671 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4670
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep146(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4672 -> {
            f.fVar26 = f32(((f.fVar37) + (((((f.fVar67) + (-(5.0)))) / (f.fVar67)))))
            59
        }
        4673 -> {
            f.fVar26 = f32(f32(((((((f.dVar36) + (f.dVar75))) / (f.dVar36))) + (f.dVar38))))
            59
        }
        4674 -> {
            f.dVar75 = -(5.3)
            58
        }
        4675 -> {
            if ((b(((b(((b((f.fVar47) <= (6.0))) != 0) || ((b((f.fVar67) <= (5.5))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x174))) <= (10.0))) != 0))) != 0) 57 else 4674
        }
        4676 -> {
            if ((b(((b((5.0) < (f.fVar67))) != 0) && ((b((f.fVar42) < (60.0))) != 0))) != 0) 4675 else 4665
        }
        4677 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x1b0)))
            4676
        }
        4678 -> {
            64
        }
        4679 -> {
            if ((b(((b((f.dVar36) <= (DAT_0012f078))) != 0) || ((b(((b((readF32(f.param_1.plus(0x298))) <= (4.3))) != 0) || ((run { run { f.fVar80 = f32(readF32(f.param_1.plus(0x250))); f32(readF32(f.param_1.plus(0x250))) }; b((13.0) <= (f.fVar80)) }) != 0))) != 0))) != 0) 4678 else 4677
        }
        4680 -> {
            f.dVar36 = f.fVar67
            4679
        }
        4681 -> {
            f.fVar67 = f32(readF32(f.param_1.plus(600)))
            4680
        }
        4682 -> {
            if ((b(((b((f.fVar71) < (8.0))) != 0) && ((b((4.5) < (f.fVar76))) != 0))) != 0) 4681 else 64
        }
        4683 -> {
            f.fVar71 = f32(readF32(f.param_1.plus(0x254)))
            4682
        }
        4684 -> {
            87
        }
        4685 -> {
            writeI32(f.pjVar4, 1)
            4684
        }
        4686 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4685
        }
        4687 -> {
            f.fVar26 = f32(f32(f.dVar38))
            55
        }
        4688 -> {
            f.dVar38 = ((f.dVar38) * (DAT_0012ee78))
            54
        }
        4689 -> {
            82
        }
        4690 -> {
            if ((b(((b(((b(((b((f.fVar67) < (3.9))) != 0) && ((b(((b((f.dVar75) < (7.8))) != 0) && ((b(((b((8.5) < (f.fVar47))) != 0) || ((b((f.fVar67) < (3.0))) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b(((b((f.fVar47) < (8.5))) != 0) || ((b((readF32(f.param_1.plus(0x298))) < (3.5))) != 0))) != 0) && ((b(((b((39.0) < (f.fVar71))) != 0) && ((b((f.fVar67) < (3.9))) != 0))) != 0))) != 0) || ((b(((b((8.2) < (f.dVar29))) != 0) && ((b(((b((8.2) < (f.dVar75))) != 0) && ((b((f.fVar79) < (2.0))) != 0))) != 0))) != 0))) != 0))) != 0) || ((b(((b((f.dVar30) < (DAT_0012f030))) != 0) && ((b(((b(((b((7.0) < (f.fVar47))) != 0) && ((b((7.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x298))) < (2.9))) != 0))) != 0))) != 0))) != 0) 4689 else 4688
        }
        4691 -> {
            82
        }
        4692 -> {
            if ((b(((b((f.fVar79) < (2.0))) != 0) && ((b(((b((39.0) < (f.fVar71))) != 0) && ((b((f.fVar67) < (3.9))) != 0))) != 0))) != 0) 4691 else 4690
        }
        4693 -> {
            56
        }
        4694 -> {
            if ((b(((b(((b((f.dVar75) <= (DAT_0012ef68))) != 0) || ((b(((b(((b((f.fVar83) <= (3.0))) != 0) || ((run { run { f.fVar71 = f32(readF32(f.param_1.plus(0x1b0))); f32(readF32(f.param_1.plus(0x1b0))) }; b((48.0) <= (f.fVar71)) }) != 0))) != 0) || ((b((DAT_0012f048) <= (f.dVar30))) != 0))) != 0))) != 0) || ((run { run { f.fVar67 = f32(readF32(f.param_1.plus(0x19c))); f32(readF32(f.param_1.plus(0x19c))) }; b(((b((f.fVar67) <= (2.4))) != 0) || ((b((f32((f.fVar76) - (f.fVar83))) <= (DAT_0012f040))) != 0)) }) != 0))) != 0) 4693 else 4692
        }
        4695 -> {
            f.dVar75 = readF32(f.param_1.plus(0x1f8))
            4694
        }
        4696 -> {
            if ((b(((b((DAT_0012ee88) < (f.dVar51))) != 0) && ((b((DAT_0012ef68) < (f.dVar29))) != 0))) != 0) 4695 else 56
        }
        4697 -> {
            f.dVar29 = f.fVar47
            4696
        }
        4698 -> {
            if ((b(((b(((b(((b(((b((f.fVar47) <= (8.5))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (8.5))) != 0))) != 0) || ((b(((b((f.dVar51) <= (DAT_0012ee88))) != 0) || ((b(((b((readF32(f.param_1.plus(600))) <= (7.8))) != 0) || ((b((f.fVar83) <= (6.0))) != 0))) != 0))) != 0))) != 0) || ((b(((b((72.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (3.9))) != 0))) != 0))) != 0) && ((b(((b(((b(((b(((b((f.fVar47) <= (7.5))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (7.5))) != 0))) != 0) || ((b((readF32(f.param_1.plus(600))) <= (8.0))) != 0))) != 0) || ((b(((b((f.fVar83) <= (9.0))) != 0) || ((b((42.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x298))) <= (4.5))) != 0))) != 0))) != 0) 4697 else 4440
        }
        4699 -> {
            f.fVar47 = f32(readF32(f.param_1.plus(0x1fc)))
            4698
        }
        4700 -> {
            f.fVar42 = f32(f.param_12)
            88
        }
        4701 -> {
            f.fVar37 = f32(f.fVar26)
            4700
        }
        4702 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4701
        }
        4703 -> {
            writeI32(f.pjVar4, 1)
            4702
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep147(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4704 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4703
        }
        4705 -> {
            f.fVar26 = f32(f32(((f.dVar30) + (f.dVar38))))
            4704
        }
        4706 -> {
            f.dVar30 = ((((f.dVar30) + (-(3.9)))) / (f.dVar30))
            83
        }
        4707 -> {
            writeI32(f.pjVar4, 1)
            4702
        }
        4708 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            85
        }
        4709 -> {
            f.fVar26 = f32(f32(((((((f.dVar30) + (-(3.5)))) / (f.dVar30))) + (f.dVar38))))
            4708
        }
        4710 -> {
            55
        }
        4711 -> {
            f.fVar26 = f32(f32((f.fVar37) + (f.fVar83)))
            4710
        }
        4712 -> {
            f.fVar83 = f32(((((f.fVar83) + (-(3.0)))) / (f.fVar83)))
            84
        }
        4713 -> {
            82
        }
        4714 -> {
            if ((b((f.fVar83) <= (3.0))) != 0) 4713 else 4712
        }
        4715 -> {
            if ((b((f.fVar83) <= (3.5))) != 0) 4714 else 4709
        }
        4716 -> {
            if ((b((3.9) < (f.dVar30))) != 0) 4706 else 4715
        }
        4717 -> {
            53
        }
        4718 -> {
            if ((b(((b(((b(((b((84.0) <= (readF32(f.param_1.plus(0x2c))))) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) <= (8.0))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1f8))) <= (8.0))) != 0) || ((b(((b(((b((4.5) <= (f.fVar83))) != 0) || ((b((f.dVar30) <= (2.8))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x19c))) <= (DAT_0012ee90))) != 0))) != 0))) != 0))) != 0) || ((b((39.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 4717 else 4716
        }
        4719 -> {
            f.fVar42 = f32(f.param_12)
            88
        }
        4720 -> {
            f.fVar37 = f32(f.fVar26)
            4719
        }
        4721 -> {
            writeF32(f.pjVar2, f32(f.fVar26))
            4720
        }
        4722 -> {
            writeI32(f.pjVar4, 1)
            87
        }
        4723 -> {
            writeF32(f.pjVar3, f32(f.fVar26))
            4722
        }
        4724 -> {
            f.fVar26 = f32(f32(((((((f.dVar30) + (f.dVar75))) / (f.dVar30))) + (f.dVar38))))
            4723
        }
        4725 -> {
            f.dVar75 = -(2.8)
            86
        }
        4726 -> {
            82
        }
        4727 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1fc))) < (8.5))) != 0) && ((b((39.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 4726 else 4725
        }
        4728 -> {
            if ((b(((b(((b(((b(((b((60.0) <= (readF32(f.param_1.plus(0x2c))))) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) <= (7.8))) != 0))) != 0) || ((b((3.5) <= (f.fVar83))) != 0))) != 0) || ((b(((b((f.fVar83) <= (3.0))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (7.8))) != 0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x19c))) <= (2.8))) != 0))) != 0) 4718 else 4727
        }
        4729 -> {
            if ((b((f.dVar51) <= (DAT_0012f070))) != 0) 53 else 4728
        }
        4730 -> {
            f.dVar30 = f.fVar83
            4729
        }
        4731 -> {
            f.dVar51 = f.fVar79
            4730
        }
        4732 -> {
            f.fVar79 = f32(f32((f.fVar79) - (f.fVar83)))
            4731
        }
        4733 -> {
            f.fVar83 = f32(readF32(f.param_1.plus(0x194)))
            4732
        }
        4734 -> {
            if ((b((f.fVar42) < (1.0))) != 0) 4434 else 52
        }
        4735 -> {
            f.fVar42 = f32(f32((f.fVar79) - (f.fVar76)))
            4734
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep148(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4736 -> {
            f.dVar38 = f.fVar37
            4735
        }
        4737 -> {
            f.pjVar4 = f.param_1.plus(0x198)
            4736
        }
        4738 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x178)))
            4737
        }
        4739 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x160)))
            4738
        }
        4740 -> {
            82
        }
        4741 -> {
            if ((b(((b(((b((f.dVar28) <= (11.2))) != 0) && ((b((f.param_12) <= (10.5))) != 0))) != 0) || ((b((2) < (readI32(f.param_1.plus(0x234))))) != 0))) != 0) 4740 else 4739
        }
        4742 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x270)))
            4364
        }
        4743 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x26c)))
            4742
        }
        4744 -> {
            88
        }
        4745 -> {
            if ((b((f.iVar20) == (1))) != 0) 4744 else 89
        }
        4746 -> {
            if ((b(((b(((b((f.iVar5) == (0))) != 0) && ((b((f.iVar20) == (0))) != 0))) != 0) && ((b(uLt(((f.param_14) - (0x361)), 0x7e0))) != 0))) != 0) 4741 else 4745
        }
        4747 -> {
            f.iVar20 = readI32(f.param_1.plus(0x198))
            4746
        }
        4748 -> {
            f.iVar5 = readI32(f.param_1.plus(0x278))
            4747
        }
        4749 -> {
            f.pjVar3 = f.param_1.plus(0x230)
            4748
        }
        4750 -> {
            f.fVar26 = f32(f.fVar37)
            47
        }
        4751 -> {
            writeI32(f.param_1.plus(0x234), 1)
            4750
        }
        4752 -> {
            writeF32(f.param_1.plus(0x22c), f32(f.fVar37))
            4751
        }
        4753 -> {
            46
        }
        4754 -> {
            if ((b(((b(((b(((b((5.3) <= (f.fVar76))) != 0) || ((b((f.fVar79) <= (f.fVar34))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (10.0))) != 0))) != 0) && ((b(((b(((b((5.3) <= (f.fVar76))) != 0) || ((b((f.fVar79) <= (0.3))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x250))) <= (12.0))) != 0))) != 0))) != 0) 4753 else 4752
        }
        4755 -> {
            f.fVar79 = f32(f32((f.fVar76) + (f32((f32((readF32(f.param_1.plus(0x254))) - (f.fVar61))) - (f.fVar61)))))
            4754
        }
        4756 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(600)))
            4755
        }
        4757 -> {
            47
        }
        4758 -> {
            if ((b(((b((3.0) <= (readF32(f.param_1.plus(0x194))))) != 0) || ((b((readF32(f.param_1.plus(0x1b0))) <= (66.0))) != 0))) != 0) 4757 else 4752
        }
        4759 -> {
            if ((b((readI32(f.param_1.plus(0x198))) == (0))) != 0) 4756 else 46
        }
        4760 -> {
            f.fVar26 = f32(f.fVar37)
            47
        }
        4761 -> {
            writeF32(f.pjVar2, f32(f.fVar37))
            4760
        }
        4762 -> {
            if ((b(((b((readF32(f.param_1.plus(0x1fc))) <= (8.5))) != 0) || ((b(((b((readF32(f.param_1.plus(0x1b0))) <= (72.0))) != 0) || ((b((readI32(f.param_1.plus(0x198))) != (0))) != 0))) != 0))) != 0) 4759 else 4761
        }
        4763 -> {
            if ((b(((b((f.fVar37) < (f.fVar26))) != 0) && ((b((readI32(f.param_1.plus(0x234))) < (2))) != 0))) != 0) 4762 else 47
        }
        4764 -> {
            f.pjVar2 = f.param_1.plus(0x22c)
            4763
        }
        4765 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x230)))
            4764
        }
        4766 -> {
            f.fVar26 = f32(readF32(f.param_1.plus(0x22c)))
            4765
        }
        4767 -> {
            f.local_1a0 = f.param_1.plus(0x220)
            4766
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep149(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4768 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            45
        }
        4769 -> {
            if ((b((f.iVar5) < (1))) != 0) 12 else 45
        }
        4770 -> {
            if ((b((readI32(f.param_1.plus(0x198))) == (0))) != 0) 11 else 45
        }
        4771 -> {
            f.iVar5 = f.iVar24
            4770
        }
        4772 -> {
            21
        }
        4773 -> {
            if ((b((0xb3) < (f.param_14))) != 0) 4772 else 10
        }
        4774 -> {
            f.bVar16 = b((0) != 0)
            9
        }
        4775 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.fVar37) * (f.dVar38)))))
            8
        }
        4776 -> {
            f.dVar38 = DAT_0012eeb0
            7
        }
        4777 -> {
            f.bVar14 = b((1) != 0)
            4776
        }
        4778 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x230)))
            4777
        }
        4779 -> {
            17
        }
        4780 -> {
            if ((b(((b((0x11f) < (f.param_14))) != 0) || ((b((f.iVar24) != (1))) != 0))) != 0) 4779 else 4778
        }
        4781 -> {
            f.bVar14 = b((b((f.param_14) < (0x120))) != 0)
            4780
        }
        4782 -> {
            f.bVar16 = b((b((f.iVar24) == (1))) != 0)
            4781
        }
        4783 -> {
            writeI32(f.param_1.plus(0x220), 1)
            6
        }
        4784 -> {
            f.iVar24 = 1
            4783
        }
        4785 -> {
            18
        }
        4786 -> {
            f.iVar24 = 0
            4785
        }
        4787 -> {
            f.bVar14 = b((b((f.param_14) < (0x120))) != 0)
            4786
        }
        4788 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            4787
        }
        4789 -> {
            writeI32(f.param_1.plus(0x220), 0)
            4788
        }
        4790 -> {
            6
        }
        4791 -> {
            f.iVar24 = readI32(f.param_1.plus(0x220))
            4790
        }
        4792 -> {
            if ((b(((b((1.0) < (f32((readF32(f.param_1.plus(0x1fc))) - (readF32(f.param_1.plus(0x1f8))))))) != 0) && ((b((readF32(f.param_1.plus(0x250))) < (16.0))) != 0))) != 0) 4791 else 4
        }
        4793 -> {
            6
        }
        4794 -> {
            15
        }
        4795 -> {
            if ((b((0x35f) < (f.param_14))) != 0) 4794 else 4793
        }
        4796 -> {
            4
        }
        4797 -> {
            if ((b(((b((f.iVar24) == (1))) != 0) && ((b((16.0) < (readF32(f.param_1.plus(0x250))))) != 0))) != 0) 4796 else 4795
        }
        4798 -> {
            f.iVar24 = readI32(f.param_1.plus(0x220))
            4797
        }
        4799 -> {
            if ((b((f.fVar37) <= (3.5))) != 0) 3 else 4792
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep150(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4800 -> {
            1
        }
        4801 -> {
            if ((b((0x240) < (f.param_14))) != 0) 4800 else 2
        }
        4802 -> {
            if ((b(((b(((b(((b((f.fVar25) <= (DAT_0012ee90))) != 0) || ((b((11.5) <= (f.param_12))) != 0))) != 0) || ((b((f.param_12) <= (0.0))) != 0))) != 0) || ((b((0x35f) < (f.param_14))) != 0))) != 0) 4801 else 5
        }
        4803 -> {
            16
        }
        4804 -> {
            writeI32(f.param_1.plus(0x234), 1)
            4803
        }
        4805 -> {
            writeI32(f.param_1.plus(0x278), 1)
            4804
        }
        4806 -> {
            if ((b(((b((f32((readF32(f.param_1.plus(0x160))) - (readF32(f.param_1.plus(0x194))))) <= (2.0))) != 0) || ((b((60.0) <= (f.fVar76))) != 0))) != 0) 4805 else 4803
        }
        4807 -> {
            writeI32(f.param_1.plus(0x234), 2)
            4803
        }
        4808 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((((f.dVar38) + (1.0))) * (readF32(f.param_1.plus(0x230)))))))
            4803
        }
        4809 -> {
            writeI32(f.param_1.plus(0x234), f.uVar27)
            4808
        }
        4810 -> {
            f.dVar38 = (((((f.param_14) / (0x120))).toDouble()) * (DAT_0012f0b8))
            4809
        }
        4811 -> {
            f.uVar27 = 2
            4810
        }
        4812 -> {
            f.uVar27 = 1
            4810
        }
        4813 -> {
            if ((b((36.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) 4811 else 4812
        }
        4814 -> {
            if ((f.bVar14) != 0) 4807 else 4813
        }
        4815 -> {
            writeI32(f.param_1.plus(0x278), 1)
            4814
        }
        4816 -> {
            f.bVar14 = b((b((DAT_0012ef60) <= (f32((readF32(f.param_1.plus(0x28))) / (f.fVar37))))) != 0)
            4815
        }
        4817 -> {
            if ((b(((b((0.75) <= (f32((f.fVar79) / (f.fVar37))))) != 0) || ((b((readI32(f.param_1.plus(0x198))) != (0))) != 0))) != 0) 4806 else 4816
        }
        4818 -> {
            if ((b(((b(((b(((b(((b((readF32(f.param_1.plus(0x1fc))) <= (8.5))) != 0) && ((b((48.0) <= (f.fVar76))) != 0))) != 0) || ((b((84.0) <= (f.fVar76))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x194))) <= (DAT_0012f030))) != 0) || ((b((42.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) && ((b(((b(((b(((b((readF32(f.param_1.plus(0x1fc))) <= (8.0))) != 0) || ((b((60.0) <= (f.fVar76))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x194))) <= (DAT_0012f050))) != 0))) != 0) || ((b(((b((48.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) || ((b((f32((readF32(f.param_1.plus(0x160))) - (readF32(f.param_1.plus(0x194))))) <= (DAT_0012ee90))) != 0))) != 0))) != 0))) != 0) 4817 else 4803
        }
        4819 -> {
            f.fVar76 = f32(readF32(f.param_1.plus(0x2c)))
            4818
        }
        4820 -> {
            3
        }
        4821 -> {
            2
        }
        4822 -> {
            if ((b((f.param_14) < (0x360))) != 0) 4821 else 4820
        }
        4823 -> {
            if ((b(((b(((b((f.fVar37) <= (f.fVar79))) != 0) || ((b((f32((f.fVar37) - (f.fVar79))) <= (0.5))) != 0))) != 0) || ((b((f32((f32((f.fVar37) - (f.fVar79))) / (f.fVar37))) <= (DAT_0012eef8))) != 0))) != 0) 4822 else 4819
        }
        4824 -> {
            f.fVar79 = f32(readF32(f.param_1.plus(0x24)))
            4823
        }
        4825 -> {
            5
        }
        4826 -> {
            if ((b((f.param_14) < (0x360))) != 0) 4825 else 1
        }
        4827 -> {
            if ((b((f.fVar37) < (3.5))) != 0) 4826 else 4802
        }
        4828 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x19c)))
            4827
        }
        4829 -> {
            13
        }
        4830 -> {
            if ((b((5) < (f.param_14))) != 0) 4829 else 0
        }
        4831 -> {
            f.bVar15 = b((0) != 0)
            4830
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep151(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4832 -> {
            16
        }
        4833 -> {
            writeI32(f.param_1.plus(0x220), 1)
            4832
        }
        4834 -> {
            writeI32(f.param_1.plus(0x234), 1)
            4833
        }
        4835 -> {
            writeI32(f.param_1.plus(0x278), 1)
            4834
        }
        4836 -> {
            if ((b((42.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0) 4835 else 4832
        }
        4837 -> {
            f.bVar15 = b((1) != 0)
            4836
        }
        4838 -> {
            13
        }
        4839 -> {
            f.bVar15 = b((1) != 0)
            4838
        }
        4840 -> {
            if ((b(((b(((b((readF32(f.param_1.plus(0x28))) <= (readF32(f.param_1.plus(0x24))))) != 0) || ((b((f32((readF32(f.param_1.plus(0x28))) / (readF32(f.param_1.plus(0x24))))) <= (1.2))) != 0))) != 0) || ((b((9.0) <= (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) 4839 else 4837
        }
        4841 -> {
            if ((b(uLt(((f.param_14) - (0x121)), 0x11f))) != 0) 4840 else 4831
        }
        4842 -> {
            f.bVar12 = b((b((0x120) < (f.param_14))) != 0)
            4841
        }
        4843 -> {
            f.bVar1 = b((b((f.param_14) < (0x240))) != 0)
            4842
        }
        4844 -> {
            12
        }
        4845 -> {
            if ((b((f.iVar24) == (0))) != 0) 4844 else 45
        }
        4846 -> {
            11
        }
        4847 -> {
            f.iVar5 = readI32(f.param_1.plus(0x278))
            4846
        }
        4848 -> {
            45
        }
        4849 -> {
            if ((b(((b(((b((0) < (f.iVar24))) != 0) || ((b((f.iVar5) != (0))) != 0))) != 0) || ((b(((b((readI32(f.param_1.plus(0x234))) == (2))) != 0) && ((b((readF32(f.param_1.plus(0x22c))) < (readF32(f.param_1.plus(0x230))))) != 0))) != 0))) != 0) 4848 else 4847
        }
        4850 -> {
            45
        }
        4851 -> {
            43
        }
        4852 -> {
            41
        }
        4853 -> {
            if ((b((f.param_12) <= (11.0))) != 0) 4852 else 4851
        }
        4854 -> {
            if ((b(((b((f.iVar5) == (0))) != 0) && ((b(((b((f.fVar61) <= (8.0))) != 0) || ((b((readF32(f.param_1.plus(0x194))) <= (8.0))) != 0))) != 0))) != 0) 4853 else 4850
        }
        4855 -> {
            if ((b(((b(((b((f.param_13) == (0.5))) != 0) && ((b((f.param_12) < (11.0))) != 0))) != 0) && ((b((0xc60) < (f.param_14))) != 0))) != 0) 4854 else 4849
        }
        4856 -> {
            42
        }
        4857 -> {
            f.fVar37 = f32(((f.param_12) + (-(9.0))))
            4856
        }
        4858 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4857
        }
        4859 -> {
            f.fVar37 = f32(((f.param_12) + (-(8.0))))
            4856
        }
        4860 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4859
        }
        4861 -> {
            45
        }
        4862 -> {
            f.fVar34 = f32(0.5)
            4861
        }
        4863 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            4862
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep152(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4864 -> {
            if ((b((f.param_12) <= (7.0))) != 0) 4863 else 4860
        }
        4865 -> {
            if ((b((8.0) < (f.param_12))) != 0) 4858 else 4864
        }
        4866 -> {
            32
        }
        4867 -> {
            f.dVar38 = ((f.param_12) + (-(10.0)))
            4866
        }
        4868 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4867
        }
        4869 -> {
            if ((b((9.0) < (f.param_12))) != 0) 4868 else 4865
        }
        4870 -> {
            31
        }
        4871 -> {
            f.fVar37 = f32(((f.param_12) + (-(11.0))))
            4870
        }
        4872 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4871
        }
        4873 -> {
            if ((b((10.0) < (f.param_12))) != 0) 4872 else 4869
        }
        4874 -> {
            32
        }
        4875 -> {
            f.dVar38 = ((f.dVar38) + (-(11.5)))
            4874
        }
        4876 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4875
        }
        4877 -> {
            if ((b((11.0) < (f.param_12))) != 0) 43 else 4873
        }
        4878 -> {
            if ((b(((b(((b(((b((3.0) < (f.fVar25))) != 0) && ((b((f.param_11) < (14.5))) != 0))) != 0) && ((b((f.param_12) < (11.5))) != 0))) != 0) && ((b(((b((f.param_13) == (0.5))) != 0) && ((b((f.iVar5) == (0))) != 0))) != 0))) != 0) 4877 else 4855
        }
        4879 -> {
            45
        }
        4880 -> {
            f.fVar34 = f32(f.fVar26)
            4879
        }
        4881 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            4880
        }
        4882 -> {
            f.dVar38 = kotlin.math.exp(((((f.fVar37) * (DAT_0012f0d8))) * (f.dVar75)))
            4881
        }
        4883 -> {
            f.dVar75 = f.fVar34
            4882
        }
        4884 -> {
            f.fVar37 = f32(((f.param_12) + (-(10.0))))
            42
        }
        4885 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4884
        }
        4886 -> {
            25
        }
        4887 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            4886
        }
        4888 -> {
            f.dVar38 = kotlin.math.exp(((((f.fVar26) * (DAT_0012f0d8))) * (f.dVar75)))
            4887
        }
        4889 -> {
            f.dVar75 = f.fVar34
            4888
        }
        4890 -> {
            f.fVar26 = f32(((f.param_12) + (-(8.0))))
            4889
        }
        4891 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4890
        }
        4892 -> {
            25
        }
        4893 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            4892
        }
        4894 -> {
            if ((b((f.param_12) <= (7.0))) != 0) 4893 else 4891
        }
        4895 -> {
            f.fVar26 = f32(((f.param_12) + (-(9.0))))
            4889
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep153(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4896 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4895
        }
        4897 -> {
            if ((b((f.param_12) <= (8.0))) != 0) 4894 else 4896
        }
        4898 -> {
            if ((b((f.param_12) <= (9.0))) != 0) 4897 else 4885
        }
        4899 -> {
            f.fVar37 = f32(((f.param_12) + (-(11.0))))
            42
        }
        4900 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4899
        }
        4901 -> {
            if ((b((f.param_12) <= (10.0))) != 0) 4898 else 4900
        }
        4902 -> {
            39
        }
        4903 -> {
            if ((b((11.0) < (f.param_12))) != 0) 4902 else 41
        }
        4904 -> {
            45
        }
        4905 -> {
            if ((b(((b((8.0) < (f.fVar61))) != 0) && ((b((8.0) < (readF32(f.param_1.plus(0x194))))) != 0))) != 0) 4904 else 4903
        }
        4906 -> {
            if ((b(((b(((b(((b((f.param_12) < (10.5))) != 0) && ((b((DAT_0012f068) < (f.dVar30))) != 0))) != 0) && ((b((f.param_11) < (14.5))) != 0))) != 0) && ((b(((b(((b((DAT_0012eef8) < (kotlin.math.abs(f32((f.fVar76) + (f.param_12)))))) != 0) && ((b((f.param_13) == (0.5))) != 0))) != 0) && ((b(((b((readI32(f.param_1.plus(0x234))) == (0))) != 0) && ((b((f.iVar5) == (0))) != 0))) != 0))) != 0))) != 0) 4905 else 4878
        }
        4907 -> {
            45
        }
        4908 -> {
            f.fVar34 = f32(f.fVar26)
            4907
        }
        4909 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            4908
        }
        4910 -> {
            f.dVar38 = kotlin.math.exp(((((f.dVar38) * (DAT_0012f0d8))) * (f.dVar75)))
            4909
        }
        4911 -> {
            f.dVar75 = f.fVar34
            4910
        }
        4912 -> {
            f.dVar38 = ((f.dVar38) + (-(11.5)))
            4911
        }
        4913 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4912
        }
        4914 -> {
            f.dVar38 = ((f.param_12) + (-(11.0)))
            4911
        }
        4915 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4914
        }
        4916 -> {
            if ((b((10.5) <= (f.param_12))) != 0) 4913 else 4915
        }
        4917 -> {
            28
        }
        4918 -> {
            f.dVar38 = ((f.dVar38) + (f.dVar75))
            4917
        }
        4919 -> {
            f.dVar75 = -(9.2)
            4918
        }
        4920 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4919
        }
        4921 -> {
            40
        }
        4922 -> {
            f.dVar75 = -(8.2)
            4921
        }
        4923 -> {
            37
        }
        4924 -> {
            if ((b((f.param_12) <= (7.0))) != 0) 4923 else 4922
        }
        4925 -> {
            if ((b((f.param_12) <= (8.0))) != 0) 4924 else 4920
        }
        4926 -> {
            f.dVar75 = -(9.5)
            4918
        }
        4927 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4926
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep154(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4928 -> {
            24
        }
        4929 -> {
            f.dVar75 = ((f.dVar38) + (f.dVar75))
            4928
        }
        4930 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4929
        }
        4931 -> {
            f.dVar75 = -(10.2)
            40
        }
        4932 -> {
            25
        }
        4933 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            4932
        }
        4934 -> {
            f.dVar38 = kotlin.math.exp(((((((f.param_12) + (-(10.0)))) * (DAT_0012f0d8))) * (f.dVar75)))
            4933
        }
        4935 -> {
            f.dVar75 = readF32(f.param_1.plus(0x230))
            4934
        }
        4936 -> {
            if ((b(((b((DAT_0012f100) < (f.dVar28))) != 0) || ((b((DAT_0012f108) < (f.dVar75))) != 0))) != 0) 4935 else 4931
        }
        4937 -> {
            if ((b((9.5) <= (f.param_12))) != 0) 4936 else 4927
        }
        4938 -> {
            if ((b((f.param_12) <= (9.0))) != 0) 4925 else 4937
        }
        4939 -> {
            if ((b((f.param_12) <= (10.0))) != 0) 4938 else 4916
        }
        4940 -> {
            31
        }
        4941 -> {
            f.fVar37 = f32(((f.param_12) + (-(12.0))))
            4940
        }
        4942 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4941
        }
        4943 -> {
            if ((b((11.0) < (f.param_12))) != 0) 39 else 4939
        }
        4944 -> {
            if ((b(((b((f.fVar61) <= (8.0))) != 0) || ((b((readF32(f.param_1.plus(0x194))) <= (8.0))) != 0))) != 0) 4943 else 4907
        }
        4945 -> {
            if ((b(((b(((b(((b((f.param_12) < (11.0))) != 0) && ((b((3.0) < (f.fVar25))) != 0))) != 0) && ((b(((b((f.param_11) < (14.5))) != 0) || ((b(((b((f.dVar29) < (6.3))) != 0) && ((b((7.5) < (f.fVar83))) != 0))) != 0))) != 0))) != 0) && ((b(((b((f.param_13) == (0.5))) != 0) && ((b((f.iVar5) == (0))) != 0))) != 0))) != 0) 4944 else 4906
        }
        4946 -> {
            25
        }
        4947 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            4946
        }
        4948 -> {
            22
        }
        4949 -> {
            f.dVar38 = ((((f.fVar79) * (DAT_0012f0d8))) * (f.dVar75))
            4948
        }
        4950 -> {
            f.dVar75 = f.fVar34
            4949
        }
        4951 -> {
            f.fVar79 = f32(((f.param_10) + (-(10.0))))
            36
        }
        4952 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4951
        }
        4953 -> {
            24
        }
        4954 -> {
            f.dVar75 = ((f.dVar28) + (f.dVar75))
            4953
        }
        4955 -> {
            f.dVar75 = -(10.5)
            35
        }
        4956 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4955
        }
        4957 -> {
            if ((b((9.5) < (f.param_10))) != 0) 4956 else 4952
        }
        4958 -> {
            if ((b((9.0) < (f.param_10))) != 0) 4957 else 37
        }
        4959 -> {
            27
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep155(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4960 -> {
            f.dVar38 = ((((((f.param_10) + (-(11.0)))) * (DAT_0012f0d8))) * (f.dVar75))
            4959
        }
        4961 -> {
            f.dVar75 = readF32(f.param_1.plus(0x230))
            4960
        }
        4962 -> {
            30
        }
        4963 -> {
            if ((b((10.5) < (f.param_10))) != 0) 4962 else 4961
        }
        4964 -> {
            if ((b((10.0) < (f.param_10))) != 0) 4963 else 4958
        }
        4965 -> {
            24
        }
        4966 -> {
            f.dVar75 = ((f.dVar28) + (f.dVar75))
            4965
        }
        4967 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4966
        }
        4968 -> {
            f.dVar75 = f.dVar29
            34
        }
        4969 -> {
            if ((b(((b((DAT_0012f0f8) < (f.dVar28))) != 0) && ((run { run { f.dVar75 = DAT_0012f0e0; DAT_0012f0e0 }; b((10.5) <= (f.param_12)) }) != 0))) != 0) 4968 else 34
        }
        4970 -> {
            36
        }
        4971 -> {
            f.fVar79 = f32(f32((f.param_10) + (f.fVar79)))
            4970
        }
        4972 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4971
        }
        4973 -> {
            35
        }
        4974 -> {
            f.dVar75 = -(12.2)
            4973
        }
        4975 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4974
        }
        4976 -> {
            if ((b((11.5) <= (f.param_10))) != 0) 4975 else 33
        }
        4977 -> {
            34
        }
        4978 -> {
            if ((b((11.5) <= (f.param_10))) != 0) 4977 else 33
        }
        4979 -> {
            if ((b((10.2) <= (f.dVar38))) != 0) 4976 else 4978
        }
        4980 -> {
            if ((b((f.param_10) <= (12.0))) != 0) 4979 else 4969
        }
        4981 -> {
            23
        }
        4982 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            4981
        }
        4983 -> {
            f.dVar38 = kotlin.math.exp(((((((f.param_10) + (-(12.0)))) * (DAT_0012f0d8))) * (f.dVar75)))
            4982
        }
        4984 -> {
            f.dVar75 = readF32(f.param_1.plus(0x230))
            4983
        }
        4985 -> {
            33
        }
        4986 -> {
            24
        }
        4987 -> {
            f.dVar75 = ((f.dVar28) + (-(11.5)))
            4986
        }
        4988 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            4987
        }
        4989 -> {
            if ((b(((b((5.0) <= (f.fVar37))) != 0) || ((b((f.fVar83) <= (6.5))) != 0))) != 0) 4988 else 4985
        }
        4990 -> {
            if ((b(((b((f.param_10) <= (11.5))) != 0) && ((b((10.5) <= (f.param_12))) != 0))) != 0) 4989 else 4984
        }
        4991 -> {
            if ((b(((b((f.dVar28) <= (DAT_0012f0f0))) != 0) || ((b((kotlin.math.abs(f32((f.fVar76) + (f.param_12)))) <= (0.3))) != 0))) != 0) 4990 else 4980
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep156(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        4992 -> {
            33
        }
        4993 -> {
            if ((b((f.param_10) <= (11.5))) != 0) 4992 else 34
        }
        4994 -> {
            if ((b(((b((10.5) <= (f.param_12))) != 0) || ((b((DAT_0012ef60) <= (f32((f.fVar37) - (readF32(f.param_1.plus(0x194))))))) != 0))) != 0) 4991 else 4993
        }
        4995 -> {
            f.dVar75 = f.dVar29
            4994
        }
        4996 -> {
            f.dVar29 = -(12.5)
            4995
        }
        4997 -> {
            if ((b((11.0) < (f.param_10))) != 0) 4996 else 4964
        }
        4998 -> {
            45
        }
        4999 -> {
            if ((b(((b(((b((12.0) < (f.param_10))) != 0) && ((b((10.5) < (f.param_12))) != 0))) != 0) || ((b(((b((8.0) < (f.fVar61))) != 0) && ((b((8.0) < (readF32(f.param_1.plus(0x194))))) != 0))) != 0))) != 0) 4998 else 4997
        }
        5000 -> {
            38
        }
        5001 -> {
            if ((b(((b(((b(((b((9.0) <= (f.fVar61))) != 0) || ((b(((b((13.0) <= (f.param_11))) != 0) && ((b((DAT_0012f0e8) <= (f.dVar38))) != 0))) != 0))) != 0) || ((b((14.5) <= (f.param_11))) != 0))) != 0) || ((b(((b(((b((11.0) <= (f.param_12))) != 0) || ((b(((b(((b((DAT_0012f0e8) <= (f.dVar38))) != 0) && ((b((f32((f.fVar76) + (f.param_12))) <= (DAT_0012f018))) != 0))) != 0) && ((b((5.3) <= (f.dVar29))) != 0))) != 0))) != 0) || ((b(((b((readI32(f.param_1.plus(0x234))) != (0))) != 0) || ((f.bVar13) != 0))) != 0))) != 0))) != 0) 5000 else 4999
        }
        5002 -> {
            if ((b((2.3) < (f.dVar30))) != 0) 5001 else 38
        }
        5003 -> {
            12
        }
        5004 -> {
            if ((b(((b(((b(((b(((b((5.5) < (f.fVar37))) != 0) && ((b((f.param_12) < (10.5))) != 0))) != 0) && ((b((f.fVar83) < (8.0))) != 0))) != 0) && ((b(((b((readF32(f.param_1.plus(0x1f8))) < (6.8))) != 0) && ((b(!((f.bVar13) != 0))) != 0))) != 0))) != 0) || ((b(((b(((b((f.param_12) < (11.0))) != 0) && ((b((readF32(f.param_1.plus(0x194))) < (4.0))) != 0))) != 0) && ((b(((b((2.0) < (f32((readF32(f.param_1.plus(0x160))) - (readF32(f.param_1.plus(0x194))))))) != 0) && ((b(((b((10.0) < (f.param_12))) != 0) && ((b((11.0) < (readF32(f.param_1.plus(0x174))))) != 0))) != 0))) != 0))) != 0))) != 0) 5003 else 5002
        }
        5005 -> {
            29
        }
        5006 -> {
            f.dVar38 = ((((f.dVar38) * (DAT_0012f0d8))) * (f.dVar75))
            5005
        }
        5007 -> {
            f.dVar75 = f.fVar34
            5006
        }
        5008 -> {
            f.dVar38 = ((f.dVar28) + (DAT_0012f0e0))
            32
        }
        5009 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5008
        }
        5010 -> {
            f.dVar38 = f.fVar37
            32
        }
        5011 -> {
            f.fVar37 = f32(((f.param_10) + (-(12.0))))
            31
        }
        5012 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5011
        }
        5013 -> {
            45
        }
        5014 -> {
            f.fVar34 = f32(0.5)
            5013
        }
        5015 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            5014
        }
        5016 -> {
            28
        }
        5017 -> {
            f.dVar38 = ((f.dVar28) + (-(11.5)))
            5016
        }
        5018 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5017
        }
        5019 -> {
            if ((b((10.0) < (f.param_10))) != 0) 30 else 5015
        }
        5020 -> {
            26
        }
        5021 -> {
            if ((b((11.0) < (f.param_10))) != 0) 5020 else 5019
        }
        5022 -> {
            if ((b((f.param_10) <= (12.0))) != 0) 5021 else 5012
        }
        5023 -> {
            if ((b((13.0) < (f.param_10))) != 0) 5009 else 5022
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep157(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        5024 -> {
            f.dVar38 = ((f.dVar28) + (-(13.5)))
            32
        }
        5025 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5024
        }
        5026 -> {
            if ((b((f.param_10) <= (14.0))) != 0) 5023 else 5025
        }
        5027 -> {
            45
        }
        5028 -> {
            if ((b((15.0) < (f.param_10))) != 0) 5027 else 5026
        }
        5029 -> {
            if ((b(((b((4.0) < (f.fVar25))) != 0) && ((b(((b(((b((f.param_11) < (13.2))) != 0) && ((b((0.5) < (f32((f.fVar76) + (f.param_12))))) != 0))) != 0) && ((b(!((f.bVar13) != 0))) != 0))) != 0))) != 0) 5028 else 5004
        }
        5030 -> {
            f.fVar79 = f32(-(12.0))
            5029
        }
        5031 -> {
            44
        }
        5032 -> {
            if ((b(((b(((b((5.6) < (f.dVar29))) != 0) && ((b((f.fVar83) < (8.2))) != 0))) != 0) && ((b(!((f.bVar13) != 0))) != 0))) != 0) 5031 else 5030
        }
        5033 -> {
            f.fVar83 = f32(readF32(f.param_1.plus(0x254)))
            5032
        }
        5034 -> {
            f.bVar13 = b((b((f.iVar5) != (0))) != 0)
            5033
        }
        5035 -> {
            if ((b(((b(((b((1.5) <= (f.fVar25))) != 0) || ((b((7.0) <= (readF32(f.param_1.plus(0x1fc))))) != 0))) != 0) || ((b((f.iVar5) != (0))) != 0))) != 0) 5034 else 44
        }
        5036 -> {
            45
        }
        5037 -> {
            f.fVar34 = f32(f.fVar26)
            5036
        }
        5038 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            5037
        }
        5039 -> {
            f.dVar38 = kotlin.math.exp(f.dVar38)
            5038
        }
        5040 -> {
            f.dVar38 = ((((f.dVar38) * (DAT_0012f0d8))) * (f.dVar75))
            29
        }
        5041 -> {
            f.dVar75 = f.fVar34
            5040
        }
        5042 -> {
            f.dVar38 = ((f.dVar28) + (-(14.5)))
            5041
        }
        5043 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5042
        }
        5044 -> {
            45
        }
        5045 -> {
            f.fVar34 = f32(f.fVar26)
            5044
        }
        5046 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            5045
        }
        5047 -> {
            f.dVar38 = kotlin.math.exp(((((f.dVar38) * (DAT_0012f0d8))) * (f.dVar75)))
            5046
        }
        5048 -> {
            f.dVar75 = f.fVar34
            5047
        }
        5049 -> {
            f.dVar38 = ((f.dVar28) + (f.dVar38))
            28
        }
        5050 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5049
        }
        5051 -> {
            f.dVar38 = DAT_0012f110
            5050
        }
        5052 -> {
            if ((b((13.5) < (f.param_10))) != 0) 5051 else 5050
        }
        5053 -> {
            f.dVar38 = DAT_0012f0e0
            5052
        }
        5054 -> {
            45
        }
        5055 -> {
            f.fVar34 = f32(f.fVar26)
            5054
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep158(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        5056 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            5055
        }
        5057 -> {
            f.dVar38 = kotlin.math.exp(f.dVar38)
            5056
        }
        5058 -> {
            f.dVar38 = ((((f32((f.param_10) + (f.fVar79))) * (DAT_0012f0d8))) * (f.dVar75))
            27
        }
        5059 -> {
            f.dVar75 = readF32(f.param_1.plus(0x230))
            5058
        }
        5060 -> {
            if ((b((14.0) < (f.param_10))) != 0) 26 else 5053
        }
        5061 -> {
            if ((b((f.param_10) <= (14.5))) != 0) 5060 else 5043
        }
        5062 -> {
            f.dVar38 = ((f.param_10) + (-(15.0)))
            5041
        }
        5063 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5062
        }
        5064 -> {
            if ((b((f.param_10) <= (15.0))) != 0) 5061 else 5063
        }
        5065 -> {
            12
        }
        5066 -> {
            if ((b(((b(((b(((b((readF32(f.param_1.plus(0x26c))) < (0.3))) != 0) && ((b(((b((f32((readF32(f.param_1.plus(0x270))) + (readF32(f.param_1.plus(0x26c))))) < (0.0))) != 0) && ((b((-(0.35)) < (f.dVar75))) != 0))) != 0))) != 0) && ((b((readF32(f.param_1.plus(0x1fc))) < (7.3))) != 0))) != 0) || ((b(((b((0) < (readI32(f.param_1.plus(0x25c))))) != 0) && ((b((DAT_0012ee90) < (f32((f32((readI32(f.param_1.plus(0x264))).toDouble())) / (f32((readI32(f.param_1.plus(0x25c))).toDouble())))))) != 0))) != 0))) != 0) 5065 else 5064
        }
        5067 -> {
            45
        }
        5068 -> {
            if ((b(((b((f.param_14) < (0x360))) != 0) && ((b((f.iVar24) == (1))) != 0))) != 0) 5067 else 5066
        }
        5069 -> {
            if ((b(((b(((b(((b(((b((0x240) < (f.param_14))) != 0) && ((b((14.0) < (f.param_11))) != 0))) != 0) && ((b(((b((13.0) < (f.param_10))) != 0) && ((b(((b((f.dVar30) < (2.3))) != 0) && ((b((f.iVar5) == (0))) != 0))) != 0))) != 0))) != 0) && ((b((1.5) < (f.fVar25))) != 0))) != 0) && ((b(((b(((b((f.param_10) < (14.0))) != 0) && ((b((1.2) < (f.dVar30))) != 0))) != 0) && ((b((readI32(f.param_1.plus(0x278))) == (0))) != 0))) != 0))) != 0) 5068 else 5035
        }
        5070 -> {
            f.dVar30 = f.fVar25
            5069
        }
        5071 -> {
            45
        }
        5072 -> {
            writeF32(f.param_1.plus(0x22c), f32(readF32(f.param_1.plus(0x230))))
            5071
        }
        5073 -> {
            if ((b((readF32(f.param_1.plus(0x230))) <= (readF32(f.param_1.plus(0x22c))))) != 0) 5072 else 5071
        }
        5074 -> {
            f.fVar34 = f32(0.5)
            5071
        }
        5075 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar29)))))
            25
        }
        5076 -> {
            f.dVar38 = kotlin.math.exp(((((f.dVar75) * (DAT_0012f0d8))) * (f.dVar29)))
            5075
        }
        5077 -> {
            f.dVar29 = f.fVar34
            5076
        }
        5078 -> {
            f.dVar75 = ((f.dVar28) + (-(14.5)))
            24
        }
        5079 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x230)))
            5078
        }
        5080 -> {
            34
        }
        5081 -> {
            f.dVar75 = DAT_0012f120
            5080
        }
        5082 -> {
            if ((b(((b(((b((f.param_10) <= (13.5))) != 0) && ((run { run { f.dVar75 = DAT_0012f0e0; DAT_0012f0e0 }; b((f.param_10) <= (13.0)) }) != 0))) != 0) && ((run { run { f.dVar75 = DAT_0012f118; DAT_0012f118 }; b((12.5) < (f.param_10)) }) != 0))) != 0) 5081 else 5080
        }
        5083 -> {
            f.dVar75 = DAT_0012f128
            5082
        }
        5084 -> {
            33
        }
        5085 -> {
            if ((b((14.0) < (f.param_10))) != 0) 5084 else 5083
        }
        5086 -> {
            if ((b((f.param_10) <= (14.5))) != 0) 5085 else 5079
        }
        5087 -> {
            45
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep159(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        5088 -> {
            f.fVar34 = f32(0.5)
            5087
        }
        5089 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((f.dVar38) * (f.dVar75)))))
            23
        }
        5090 -> {
            f.dVar38 = kotlin.math.exp(f.dVar38)
            5089
        }
        5091 -> {
            f.dVar38 = ((((((f.param_10) + (-(15.0)))) * (DAT_0012f0d8))) * (f.dVar75))
            22
        }
        5092 -> {
            f.dVar75 = readF32(f.param_1.plus(0x230))
            5091
        }
        5093 -> {
            if ((b((15.0) < (f.param_10))) != 0) 5092 else 5086
        }
        5094 -> {
            12
        }
        5095 -> {
            if ((b(((b((0) < (readI32(f.param_1.plus(0x25c))))) != 0) && ((b((DAT_0012ee90) < (f32((f32((readI32(f.param_1.plus(0x264))).toDouble())) / (f32((readI32(f.param_1.plus(0x25c))).toDouble())))))) != 0))) != 0) 5094 else 5093
        }
        5096 -> {
            if ((b((f.iVar24) != (1))) != 0) 5095 else 5071
        }
        5097 -> {
            if ((b(((b((readF32(f.param_1.plus(0x298))) < (2.8))) != 0) || ((b(((b((readF32(f.param_1.plus(0x298))) < (3.5))) != 0) && ((b((48.0) < (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0))) != 0) 5073 else 5096
        }
        5098 -> {
            if ((b(((b(((b(((b(((b(((b((f.iVar5) == (0))) != 0) && ((b((13.1) < (f.param_11))) != 0))) != 0) && ((b((12.0) < (f.param_10))) != 0))) != 0) && ((b((f.fVar25) < (2.5))) != 0))) != 0) && ((b(((b((f.iVar24) == (0))) != 0) || ((b((1.0) < (f32((f.fVar37) - (readF32(f.param_1.plus(0x194))))))) != 0))) != 0))) != 0) && ((b(((b((0x240) < (f.param_14))) != 0) && ((b(((b((f32((f.fVar76) + (f.param_12))) < (DAT_0012eef8))) != 0) && ((b((readI32(f.param_1.plus(0x278))) == (0))) != 0))) != 0))) != 0))) != 0) 5097 else 5070
        }
        5099 -> {
            f.fVar79 = f32(-(14.0))
            5098
        }
        5100 -> {
            f.fVar76 = f32(f32((f.fVar70) - (f.param_10)))
            5099
        }
        5101 -> {
            f.iVar5 = readI32(f.param_1.plus(0x198))
            5100
        }
        5102 -> {
            12
        }
        5103 -> {
            45
        }
        5104 -> {
            if ((b((readI32(f.param_1.plus(0x234))) < (2))) != 0) 5103 else 5102
        }
        5105 -> {
            if ((b(((b((13.0) < (f.param_12))) != 0) && ((b(((b((f.fVar70) < (0.6))) != 0) || ((b((f.fVar31) < (0.6))) != 0))) != 0))) != 0) 5104 else 5101
        }
        5106 -> {
            45
        }
        5107 -> {
            if ((b(((b(((b((f.dVar29) < (DAT_0012f078))) != 0) && ((run { run { f.fVar79 = f32(readF32(f.param_1.plus(0x194))); f32(readF32(f.param_1.plus(0x194))) }; b((f.fVar79) < (3.5)) }) != 0))) != 0) && ((b(((b((1.2) < (f32((f.fVar37) - (f.fVar79))))) != 0) && ((b(((b((3.0) < (f.fVar79))) != 0) && ((b((6.5) < (readF32(f.param_1.plus(0x254))))) != 0))) != 0))) != 0))) != 0) 5106 else 5105
        }
        5108 -> {
            f.dVar29 = f.fVar37
            5107
        }
        5109 -> {
            10
        }
        5110 -> {
            if ((b((f.dVar75) <= (DAT_0012f0d0))) != 0) 5109 else 5108
        }
        5111 -> {
            f.dVar75 = readF32(f.param_1.plus(0x270))
            5110
        }
        5112 -> {
            10
        }
        5113 -> {
            if ((b(((b(((b((DAT_0012f0c8) <= (f.dVar38))) != 0) && ((b((9.0) <= (f.fVar61))) != 0))) != 0) || ((run { run { f.fVar37 = f32(readF32(f.param_1.plus(600))); f32(readF32(f.param_1.plus(600))) }; b((9.5) <= (f.fVar37)) }) != 0))) != 0) 5112 else 5111
        }
        5114 -> {
            f.dVar38 = f.param_12
            5113
        }
        5115 -> {
            f.bVar16 = b((1) != 0)
            21
        }
        5116 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(f.dVar38)))
            21
        }
        5117 -> {
            writeI32(f.param_1.plus(0x234), 2)
            5116
        }
        5118 -> {
            writeI32(f.param_1.plus(0x220), 1)
            5117
        }
        5119 -> {
            f.dVar38 = ((readF32(f.param_1.plus(0x230))) * (DAT_0012ee70))
            5118
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep160(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        5120 -> {
            f.iVar24 = 1
            5119
        }
        5121 -> {
            if ((b((readF32(f.param_1.plus(0x194))) < (3.0))) != 0) 5120 else 21
        }
        5122 -> {
            f.bVar16 = b((1) != 0)
            5121
        }
        5123 -> {
            if ((b((readF32(f.param_1.plus(0x2c))) <= (108.0))) != 0) 5115 else 5122
        }
        5124 -> {
            9
        }
        5125 -> {
            if ((b(((b((0.5) <= (f.fVar31))) != 0) || ((b(!((f.bVar16) != 0))) != 0))) != 0) 5124 else 5123
        }
        5126 -> {
            f.bVar16 = b((b((0x360) < (f.param_14))) != 0)
            5125
        }
        5127 -> {
            writeI32(f.param_1.plus(0x22c), f.uVar27)
            20
        }
        5128 -> {
            writeI32(f.param_1.plus(0x220), 0)
            19
        }
        5129 -> {
            f.iVar24 = 0
            5128
        }
        5130 -> {
            f.uVar27 = readI32(f.param_1.plus(0x230))
            5129
        }
        5131 -> {
            8
        }
        5132 -> {
            writeI32(f.param_1.plus(0x22c), readI32(f.param_1.plus(0x230)))
            5131
        }
        5133 -> {
            f.iVar24 = 0
            5132
        }
        5134 -> {
            20
        }
        5135 -> {
            if ((b(((b(((b(((b((f.param_11) <= (13.2))) != 0) || ((b((f.iVar24) != (0))) != 0))) != 0) || ((b((f.param_12) <= (10.5))) != 0))) != 0) || ((b((0x35f) < (f.param_14))) != 0))) != 0) 5134 else 5133
        }
        5136 -> {
            if ((b(((b(((b((f.param_14) < (0x361))).or((f.bVar16).xor(1))) != 0)) != 0) || ((b((readI32(f.param_1.plus(0x198))) != (0))) != 0))) != 0) 18 else 5130
        }
        5137 -> {
            writeF32(f.param_1.plus(0x22c), f32(f32(((readF32(f.param_1.plus(0x230))) * (DAT_0012f0c0)))))
            20
        }
        5138 -> {
            19
        }
        5139 -> {
            f.uVar27 = readI32(f.param_1.plus(0x230))
            5138
        }
        5140 -> {
            if ((b(((b((9.0) < (readF32(f.param_1.plus(0x1fc))))) != 0) && ((b((9.0) < (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0) 5139 else 5137
        }
        5141 -> {
            7
        }
        5142 -> {
            f.dVar38 = 0.95
            5141
        }
        5143 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(0x230)))
            5142
        }
        5144 -> {
            if ((b((f.param_14) < (0x241))) != 0) 5143 else 5140
        }
        5145 -> {
            if ((b(((b((0x360) < (f.param_14))) != 0) || ((b(((f.bVar16).xor(1)) != 0)) != 0))) != 0) 5136 else 5144
        }
        5146 -> {
            f.bVar16 = b((b((f.iVar24) == (1))) != 0)
            17
        }
        5147 -> {
            f.bVar14 = b((0) != 0)
            5146
        }
        5148 -> {
            f.iVar24 = readI32(f.param_1.plus(0x220))
            5147
        }
        5149 -> {
            writeI32(f.param_1.plus(0x220), 0)
            16
        }
        5150 -> {
            5
        }
        5151 -> {
            if ((b(((b(((b(((b(((b((f.param_14) < (0x241))) != 0) || ((b((readF32(f.param_1.plus(0x1fc))) <= (9.0))) != 0))) != 0) || ((b((readF32(f.param_1.plus(0x1f8))) <= (9.0))) != 0))) != 0) || ((b(((b((f32((readF32(f.param_1.plus(0x160))) - (readF32(f.param_1.plus(0x194))))) <= (2.0))) != 0) || ((b((3.9) <= (readF32(f.param_1.plus(0x194))))) != 0))) != 0))) != 0) || ((b((48.0) <= (readF32(f.param_1.plus(0x1b0))))) != 0))) != 0) 5150 else 15
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep161(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        5152 -> {
            14
        }
        5153 -> {
            if ((b(((b((f32((f.fVar61) - (f.fVar37))) <= (0.7))) != 0) || ((b(((b(((b(((b((0x35f) < (f.param_14))) != 0) || ((b((f.fVar37) <= (0.0))) != 0))) != 0) || ((b((6.0) <= (f.fVar37))) != 0))) != 0) || ((b((f32((f32((readF32(f.param_1.plus(0x254))) - (f.fVar61))) / (f32((f.fVar61) - (f.fVar37))))) <= (2.0))) != 0))) != 0))) != 0) 5152 else 5151
        }
        5154 -> {
            5
        }
        5155 -> {
            0
        }
        5156 -> {
            if ((b(((b(((b((f.fVar37) <= (0.0))) != 0) || ((b((5.8) <= (f.fVar37))) != 0))) != 0) || ((b(((b((readF32(f.param_1.plus(0x250))) <= (13.0))) != 0) || ((b(((b((0x35f) < (f.param_14))) != 0) || ((b((DAT_0012f0b0) <= (readF32(f.param_1.plus(0x1f8))))) != 0))) != 0))) != 0))) != 0) 5155 else 5154
        }
        5157 -> {
            if ((b((0x35f) < (f.param_14))) != 0) 14 else 5151
        }
        5158 -> {
            if ((b(((b((5.0) <= (f.fVar37))) != 0) || ((b((f.fVar37) <= (0.0))) != 0))) != 0) 5153 else 5157
        }
        5159 -> {
            f.fVar37 = f32(readF32(f.param_1.plus(600)))
            5158
        }
        5160 -> {
            f.bVar12 = b((1) != 0)
            13
        }
        5161 -> {
            f.bVar1 = b((0) != 0)
            5160
        }
        5162 -> {
            f.bVar15 = b((b(uLt(((f.param_14) - (0x121)), 0x11f))) != 0)
            5161
        }
        5163 -> {
            writeI32(f.param_1.plus(0x278), 1)
            5162
        }
        5164 -> {
            if ((b(((b((readF32(f.param_1.plus(600))) < (5.0))) != 0) || ((b((0.5) < (f32((f32((f32((readF32(f.param_1.plus(0x254))) - (f.fVar61))) - (f.fVar61))) - (readF32(f.param_1.plus(600))))))) != 0))) != 0) 5163 else 5162
        }
        5165 -> {
            if ((b((f.param_14) < (0xc61))) != 0) 4843 else 5164
        }
        5166 -> {
            f.fVar42 = f32(f.param_12)
            5165
        }
        5167 -> {
            writeF32(f.param_1.plus(0x1b8), f32(f.param_4))
            5166
        }
        5168 -> {
            f.fVar31 = f32(f32((f.param_10) - (f.param_12)))
            5167
        }
        5169 -> {
            f.fVar70 = f32(f32((f.param_11) - (f.param_10)))
            5168
        }
        5170 -> {
            f.dVar28 = f.param_10
            5169
        }
        5171 -> {
            f.fVar25 = f32(f32((f.param_11) - (f.param_12)))
            5170
        }
        5172 -> {
            f.fVar34 = f32(0.5)
            5171
        }
        5173 -> {
            f.fVar26 = f32(0.5)
            5172
        }
        5174 -> {
            writeI32(f.param_1.plus(0x1bc), 0)
            5173
        }
        5175 -> {
            writeF32(f.param_1.plus(0x1c0), f32(f.param_4))
            5174
        }
        5176 -> {
            writeF32(f.param_1.plus(0x1c4), f32(f.fVar42))
            5175
        }
        5177 -> {
            if ((b(((b((6.0) < (f32((f.fVar42) - (f.fVar34))))) != 0) && ((run { run { f.fVar42 = f32(f32((f32((readF32(f.param_1.plus(0x1c0))) - (f.param_4))) / (f32((readI32(f.param_1.plus(0x1bc))).toDouble())))); f32(f32((f32((readF32(f.param_1.plus(0x1c0))) - (f.param_4))) / (f32((readI32(f.param_1.plus(0x1bc))).toDouble())))) }; b((readF32(f.param_1.plus(0x1c4))) < (f.fVar42)) }) != 0))) != 0) 5176 else 5175
        }
        5178 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x158)))
            5177
        }
        5179 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x168)))
            5178
        }
        5180 -> {
            f.fVar34 = f32(readF32(f.param_1.plus(0x178)))
            5177
        }
        5181 -> {
            f.fVar42 = f32(readF32(f.param_1.plus(0x174)))
            5180
        }
        5182 -> {
            if ((b((f.param_14) < (0x121))) != 0) 5179 else 5181
        }
        5183 -> {
            if ((b(((b((f.dVar41) < (DAT_0012f030))) != 0) && ((b(((b((0.3) < (f.dVar41))) != 0) && ((b((4.5) < (f32((readF32(f.param_1.plus(0x1c0))) - (f.param_4))))) != 0))) != 0))) != 0) 5182 else 5175
        }
        else -> error("bad C state: $pc")
    }
}

private fun ClippingfilterStep162(f: ClippingfilterFrame, pc: Int): Int {
    return when (pc) {
        5184 -> {
            writeI32(f.param_1.plus(0x1bc), ((readI32(f.param_1.plus(0x1bc))) + (1)))
            5173
        }
        5185 -> {
            if ((b(((b((f.param_14) < (0x1f))) != 0) || ((b((0.0) <= (f32((f.param_4) - (readF32(f.param_1.plus(0x1b8))))))) != 0))) != 0) 5183 else 5184
        }
        5186 -> {
            f.dVar41 = f.param_4
            5185
        }
        5187 -> {
            writeF32(f.param_1.plus(0x10), f32(f.param_10))
            5186
        }
        5188 -> {
            if ((b((f.param_10) < (readF32(f.param_1.plus(0x10))))) != 0) 5187 else 5186
        }
        5189 -> {
            writeF32(f.param_1.plus(0x1cc), f32(f.fVar61))
            5188
        }
        5190 -> {
            writeF32(f.param_1.plus(0x1c8), f32(f32((readF32(f.param_1.plus(0x1c8))) + (f.param_4))))
            5189
        }
        5191 -> {
            f.fVar61 = f32(f32((f32((readF32(f.param_1.plus(0x1c8))) + (f.param_4))) / (f32((((f.param_14) + (1))).toDouble()))))
            5190
        }
        else -> error("bad C state: $pc")
    }
}

private fun Clipping_filter(param_1: Ptr, param_2: Ptr, param_3: Ptr, param_4: Double, param_5: Double, param_6: Double, param_7: Double, param_8: Double, param_9: Double, param_10: Double, param_11: Double, param_12: Double, param_13: Double, param_14: Int): Int {
    val f = ClippingfilterFrame(param_1, param_2, param_3, f32(param_4), f32(param_5), f32(param_6), f32(param_7), f32(param_8), f32(param_9), f32(param_10), f32(param_11), f32(param_12), f32(param_13), param_14)
    var pc = 5191
    while (true) {
        pc = when (pc / 32) {
            0 -> ClippingfilterStep0(f, pc)
            1 -> ClippingfilterStep1(f, pc)
            2 -> ClippingfilterStep2(f, pc)
            3 -> ClippingfilterStep3(f, pc)
            4 -> ClippingfilterStep4(f, pc)
            5 -> ClippingfilterStep5(f, pc)
            6 -> ClippingfilterStep6(f, pc)
            7 -> ClippingfilterStep7(f, pc)
            8 -> ClippingfilterStep8(f, pc)
            9 -> ClippingfilterStep9(f, pc)
            10 -> ClippingfilterStep10(f, pc)
            11 -> ClippingfilterStep11(f, pc)
            12 -> ClippingfilterStep12(f, pc)
            13 -> ClippingfilterStep13(f, pc)
            14 -> ClippingfilterStep14(f, pc)
            15 -> ClippingfilterStep15(f, pc)
            16 -> ClippingfilterStep16(f, pc)
            17 -> ClippingfilterStep17(f, pc)
            18 -> ClippingfilterStep18(f, pc)
            19 -> ClippingfilterStep19(f, pc)
            20 -> ClippingfilterStep20(f, pc)
            21 -> ClippingfilterStep21(f, pc)
            22 -> ClippingfilterStep22(f, pc)
            23 -> ClippingfilterStep23(f, pc)
            24 -> ClippingfilterStep24(f, pc)
            25 -> ClippingfilterStep25(f, pc)
            26 -> ClippingfilterStep26(f, pc)
            27 -> ClippingfilterStep27(f, pc)
            28 -> ClippingfilterStep28(f, pc)
            29 -> ClippingfilterStep29(f, pc)
            30 -> ClippingfilterStep30(f, pc)
            31 -> ClippingfilterStep31(f, pc)
            32 -> ClippingfilterStep32(f, pc)
            33 -> ClippingfilterStep33(f, pc)
            34 -> ClippingfilterStep34(f, pc)
            35 -> ClippingfilterStep35(f, pc)
            36 -> ClippingfilterStep36(f, pc)
            37 -> ClippingfilterStep37(f, pc)
            38 -> ClippingfilterStep38(f, pc)
            39 -> ClippingfilterStep39(f, pc)
            40 -> ClippingfilterStep40(f, pc)
            41 -> ClippingfilterStep41(f, pc)
            42 -> ClippingfilterStep42(f, pc)
            43 -> ClippingfilterStep43(f, pc)
            44 -> ClippingfilterStep44(f, pc)
            45 -> ClippingfilterStep45(f, pc)
            46 -> ClippingfilterStep46(f, pc)
            47 -> ClippingfilterStep47(f, pc)
            48 -> ClippingfilterStep48(f, pc)
            49 -> ClippingfilterStep49(f, pc)
            50 -> ClippingfilterStep50(f, pc)
            51 -> ClippingfilterStep51(f, pc)
            52 -> ClippingfilterStep52(f, pc)
            53 -> ClippingfilterStep53(f, pc)
            54 -> ClippingfilterStep54(f, pc)
            55 -> ClippingfilterStep55(f, pc)
            56 -> ClippingfilterStep56(f, pc)
            57 -> ClippingfilterStep57(f, pc)
            58 -> ClippingfilterStep58(f, pc)
            59 -> ClippingfilterStep59(f, pc)
            60 -> ClippingfilterStep60(f, pc)
            61 -> ClippingfilterStep61(f, pc)
            62 -> ClippingfilterStep62(f, pc)
            63 -> ClippingfilterStep63(f, pc)
            64 -> ClippingfilterStep64(f, pc)
            65 -> ClippingfilterStep65(f, pc)
            66 -> ClippingfilterStep66(f, pc)
            67 -> ClippingfilterStep67(f, pc)
            68 -> ClippingfilterStep68(f, pc)
            69 -> ClippingfilterStep69(f, pc)
            70 -> ClippingfilterStep70(f, pc)
            71 -> ClippingfilterStep71(f, pc)
            72 -> ClippingfilterStep72(f, pc)
            73 -> ClippingfilterStep73(f, pc)
            74 -> ClippingfilterStep74(f, pc)
            75 -> ClippingfilterStep75(f, pc)
            76 -> ClippingfilterStep76(f, pc)
            77 -> ClippingfilterStep77(f, pc)
            78 -> ClippingfilterStep78(f, pc)
            79 -> ClippingfilterStep79(f, pc)
            80 -> ClippingfilterStep80(f, pc)
            81 -> ClippingfilterStep81(f, pc)
            82 -> ClippingfilterStep82(f, pc)
            83 -> ClippingfilterStep83(f, pc)
            84 -> ClippingfilterStep84(f, pc)
            85 -> ClippingfilterStep85(f, pc)
            86 -> ClippingfilterStep86(f, pc)
            87 -> ClippingfilterStep87(f, pc)
            88 -> ClippingfilterStep88(f, pc)
            89 -> ClippingfilterStep89(f, pc)
            90 -> ClippingfilterStep90(f, pc)
            91 -> ClippingfilterStep91(f, pc)
            92 -> ClippingfilterStep92(f, pc)
            93 -> ClippingfilterStep93(f, pc)
            94 -> ClippingfilterStep94(f, pc)
            95 -> ClippingfilterStep95(f, pc)
            96 -> ClippingfilterStep96(f, pc)
            97 -> ClippingfilterStep97(f, pc)
            98 -> ClippingfilterStep98(f, pc)
            99 -> ClippingfilterStep99(f, pc)
            100 -> ClippingfilterStep100(f, pc)
            101 -> ClippingfilterStep101(f, pc)
            102 -> ClippingfilterStep102(f, pc)
            103 -> ClippingfilterStep103(f, pc)
            104 -> ClippingfilterStep104(f, pc)
            105 -> ClippingfilterStep105(f, pc)
            106 -> ClippingfilterStep106(f, pc)
            107 -> ClippingfilterStep107(f, pc)
            108 -> ClippingfilterStep108(f, pc)
            109 -> ClippingfilterStep109(f, pc)
            110 -> ClippingfilterStep110(f, pc)
            111 -> ClippingfilterStep111(f, pc)
            112 -> ClippingfilterStep112(f, pc)
            113 -> ClippingfilterStep113(f, pc)
            114 -> ClippingfilterStep114(f, pc)
            115 -> ClippingfilterStep115(f, pc)
            116 -> ClippingfilterStep116(f, pc)
            117 -> ClippingfilterStep117(f, pc)
            118 -> ClippingfilterStep118(f, pc)
            119 -> ClippingfilterStep119(f, pc)
            120 -> ClippingfilterStep120(f, pc)
            121 -> ClippingfilterStep121(f, pc)
            122 -> ClippingfilterStep122(f, pc)
            123 -> ClippingfilterStep123(f, pc)
            124 -> ClippingfilterStep124(f, pc)
            125 -> ClippingfilterStep125(f, pc)
            126 -> ClippingfilterStep126(f, pc)
            127 -> ClippingfilterStep127(f, pc)
            128 -> ClippingfilterStep128(f, pc)
            129 -> ClippingfilterStep129(f, pc)
            130 -> ClippingfilterStep130(f, pc)
            131 -> ClippingfilterStep131(f, pc)
            132 -> ClippingfilterStep132(f, pc)
            133 -> ClippingfilterStep133(f, pc)
            134 -> ClippingfilterStep134(f, pc)
            135 -> ClippingfilterStep135(f, pc)
            136 -> ClippingfilterStep136(f, pc)
            137 -> ClippingfilterStep137(f, pc)
            138 -> ClippingfilterStep138(f, pc)
            139 -> ClippingfilterStep139(f, pc)
            140 -> ClippingfilterStep140(f, pc)
            141 -> ClippingfilterStep141(f, pc)
            142 -> ClippingfilterStep142(f, pc)
            143 -> ClippingfilterStep143(f, pc)
            144 -> ClippingfilterStep144(f, pc)
            145 -> ClippingfilterStep145(f, pc)
            146 -> ClippingfilterStep146(f, pc)
            147 -> ClippingfilterStep147(f, pc)
            148 -> ClippingfilterStep148(f, pc)
            149 -> ClippingfilterStep149(f, pc)
            150 -> ClippingfilterStep150(f, pc)
            151 -> ClippingfilterStep151(f, pc)
            152 -> ClippingfilterStep152(f, pc)
            153 -> ClippingfilterStep153(f, pc)
            154 -> ClippingfilterStep154(f, pc)
            155 -> ClippingfilterStep155(f, pc)
            156 -> ClippingfilterStep156(f, pc)
            157 -> ClippingfilterStep157(f, pc)
            158 -> ClippingfilterStep158(f, pc)
            159 -> ClippingfilterStep159(f, pc)
            160 -> ClippingfilterStep160(f, pc)
            161 -> ClippingfilterStep161(f, pc)
            162 -> ClippingfilterStep162(f, pc)
            else -> error("bad C chunk: $pc")
        }
        if (pc == C_DONE) {
            return f.result
        }
    }
}



private const val V116_TEMPLATE_HEX = "0100000001000000414c474f524954484d2056312e312e364128323032355f30335f313729000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000976e9a3f000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000009a99993f0000003f17b7d1380000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000a04100000000000000000000000000000000000000000000dc420000000000000000000000000000c03f0000c842000020410000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c841000020410000000000002041000000000000000000000000000000000000000000002041000020410000204100002041000000000000000000000000000000000000204100000000000020410000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c84200000000000000000000000000000000000000000000000000000000000000000000c03f0000c03f000000000000000000004842000048420000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000c842000000000000000000002041000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000a041000000000000c8420000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

private fun rf(p: Ptr, offset: Int): Float = readF32(p.plus(offset)).toFloat()
private fun wf(p: Ptr, offset: Int, value: Float) = writeF32(p.plus(offset), value.toDouble())
private fun ri(p: Ptr, offset: Int): Int = readI32(p.plus(offset))
private fun wi(p: Ptr, offset: Int, value: Int) = writeI32(p.plus(offset), value)

private fun Judgment_of_current_abnormality(p: Ptr, rawValue: Double, temperature: Double, index: Int): Int {
    if (p.bytes.isEmpty()) return 0
    val raw = rawValue.toFloat()
    val temp = temperature.toFloat()
    var flag = if (raw > 70f && index < 60) 1 else 0
    val previous = rf(p, 0x0c)
    val mean = (previous + raw) * 0.5f
    val a = previous - mean
    val b = raw - mean
    val variance = (a * a + b * b) * 0.5f
    wi(p, 0x04, flag)
    wf(p, 0x08, previous)
    wf(p, 0x0c, raw)
    wf(p, 0x10, variance)
    if (
        (kotlin.math.abs(rf(p, 0) - raw) > 3.5f && variance > 1f && index > 20 && flag == 0) ||
        (raw < 1f && index < 60) ||
        (raw.toDouble() <= 0.11 && index < 2880) ||
        (temp < 29f && raw.toDouble() <= 0.3 && index < 2880) ||
        (raw < 1f && index > 2879) ||
        (ri(p, 0x18) == 1 && rf(p, 0x1c) > 0f && index > 20 && rf(p, 0x1c) < raw)
    ) {
        flag = 1
        wi(p, 0x04, 1)
    }
    if (
        (rf(p, 0x1c) < raw && index > 20 && flag == 0) ||
        (index < 21 && raw < 10f && rf(p, 0x1c) < raw)
    ) wf(p, 0x1c, raw)
    wf(p, 0, raw)
    wi(p, 0x18, flag)
    return flag
}

private fun Abnormal_Judgement(p: Ptr, rawValue: Double, temperature: Double, index: Int): Int {
    if (p.bytes.isEmpty()) return 0
    for (offset in 0..0x34 step 4) writeI32(p.plus(offset), readI32(p.plus(offset + 4)))
    val current = Judgment_of_current_abnormality(p.plus(0x44), rawValue, temperature, index)
    wf(p, 0x38, current.toFloat())
    wf(p, 0x3c, 0f)
    var abnormalCount = 0f
    if (index > 14) {
        for (offset in 0..0x34 step 4) if (rf(p, offset) == 1f) abnormalCount += 1f
        if (current == 1) abnormalCount += 1f
        wf(p, 0x3c, abnormalCount)
    }
    val zeroStreak = if (rawValue.toFloat() == 0f) ri(p, 0x40) + 1 else 0
    wi(p, 0x40, zeroStreak)
    var result = when {
        temperature.toFloat() < 10f -> 1
        temperature.toFloat() > 42f -> 2
        else -> 0
    }
    if (zeroStreak >= 2) return result or 0x10
    if (abnormalCount >= 7f) result = result or 0x20
    return result
}

private fun Abnormal_Value_Correction(p: Ptr, sensitivity: Double, rawValue: Double, index: Int): Double {
    if (p.bytes.isEmpty()) return 0.0
    val raw = rawValue.toFloat()
    val old = FloatArray(19) { rf(p, 0x10 + it * 4) }
    for (i in old.indices) wf(p, 0x0c + i * 4, old[i])
    var result = raw
    if (index > 20) {
        val previous = rf(p, 0x08)
        val delta = raw - previous
        if (delta >= 1f && delta < 2f) {
            result = (previous + raw) * 0.5f
        } else if (delta <= -1f) {
            var average = old[0] + 0f
            for (i in 1 until old.size) average += old[i]
            average = (average + raw) / 20f
            result = when {
                average > previous -> previous - 0.5f
                average - raw < 3f && kotlin.math.abs(raw - rf(p, 0x04)) < 0.5f -> (average + raw) * 0.5f
                else -> average
            }
        } else if (delta >= 2f) {
            if (delta > 8f && raw >= 35f) {
                result = raw
            } else {
                var average = old[0] + 0f
                for (i in 1 until old.size) average += old[i]
                average = (average + raw) / 20f
                result = if (average < previous) previous + 0.3f else average
            }
        }
    }
    wf(p, 0x58, raw)
    wf(p, 0x04, raw)
    wf(p, 0x08, result)
    return result.toDouble()
}

private fun Kalman_Filter(p: Ptr, measurementValue: Double, index: Int): Double {
    if (p.bytes.isEmpty()) return 0.0
    val measurement = measurementValue.toFloat()
    if (index <= 1) {
        wf(p, 0, measurement)
        return measurement.toDouble()
    }
    val state = rf(p, 0)
    val pMinus = rf(p, 0x18) + rf(p, 0x14)
    val gain = pMinus / (pMinus + rf(p, 0x10))
    wf(p, 0x08, pMinus)
    wf(p, 0x0c, gain)
    val next = gain * (measurement - state) + state
    wf(p, 0x04, state)
    wf(p, 0, next)
    wf(p, 0x18, pMinus * (1f - gain))
    return next.toDouble()
}

private fun temperature_compensation(p: Ptr, temperatureValue: Double, inputValue: Double, index: Int): Double {
    val temperature = temperatureValue.toFloat()
    val input = inputValue.toFloat()
    val buffer = FloatArray(10) { rf(p, it * 4) }
    val lastAverage = rf(p, 0x28)
    for (i in 0 until 9) wf(p, i * 4, buffer[i + 1])

    val selectedTemperature = when {
        temperature >= 42f -> if (lastAverage.isFinite() && lastAverage in 10f..42f) lastAverage else 32.5f
        kotlin.math.abs(lastAverage - rf(p, 0x2c)) <= 5f -> maxOf(temperature, 10f)
        else -> 31.5f
    }
    wf(p, 0x24, selectedTemperature)
    wf(p, 0x28, rf(p, 0x2c))

    val average = if (index < 9) {
        if (!temperature.isFinite() || temperature < 10f || temperature > 42f) 32.5f else temperature
    } else {
        var total = buffer[1] + 0f
        for (i in 2..9) total += buffer[i]
        (total + selectedTemperature) / 10f
    }
    val runningSum = average + rf(p, 0x34)
    wf(p, 0x34, runningSum)
    wf(p, 0x2c, average)
    wf(p, 0x30, runningSum / (index + 1).toFloat())

    val x = average.toDouble()
    val polynomial = (
        Math.pow(x, 5.0) * 0.00002677628435776569 +
            Math.pow(x, 3.0) * 0.061271119862794876 +
            average * -0.03786363f +
            x * x * -0.7637396454811096 +
            Math.pow(x, 4.0) * -0.0019132299348711967 +
            Math.pow(x, 6.0) * -0.00000013804908860493015 +
            65.21018525
        ).toFloat()
    return ((input.toDouble() - 0.5) * (34.2 - polynomial.toDouble()) * 0.04 + input.toDouble()).toFloat().toDouble()
}

private fun functionFilter(inputValue: Double, y: Ptr, x: Ptr, a: Ptr, b: Ptr, index: Int): Double {
    val input = inputValue.toFloat()
    if (index == 0) {
        val output = rf(b, 0) * input
        wf(y, 0, output)
        wf(x, 0, input)
        return output.toDouble()
    }
    if (index == 1) {
        val output = (rf(b, 0) * input + rf(b, 4) * rf(x, 0)) - rf(a, 4) * rf(y, 0)
        wf(y, 4, output)
        wf(x, 4, input)
        return output.toDouble()
    }
    val output = ((rf(b, 0) * input + rf(b, 4) * rf(x, 4) + rf(b, 8) * rf(x, 0)) -
        rf(a, 4) * rf(y, 4)) - rf(a, 8) * rf(y, 0)
    val previousX = rf(x, 4)
    wf(y, 8, output)
    wf(x, 4, input)
    wf(x, 0, previousX)
    wf(y, 0, rf(y, 4))
    wf(y, 4, output)
    return output.toDouble()
}

private fun ESA_Compensate(p: Ptr, currentValue: Double, compensationValue: Double, index: Int): Double {
    val current = currentValue.toFloat()
    val compensation = compensationValue.toFloat()
    val a = rf(p, 4)
    val b = rf(p, 8)
    val c = rf(p, 0x0c)
    val d = rf(p, 0x10)
    wf(p, 0, a)
    wf(p, 4, b)
    wf(p, 8, c)
    wf(p, 0x0c, d)
    wf(p, 0x10, compensation)
    return ((a + 0f + b + c + d + compensation) / 5f + current).toDouble()
}

private val V116_DECONVOLUTION = doubleArrayOf(
    0.212986954, -0.0156069197, -0.0116342456, -0.00867279838, -0.00646517451,
    -0.00481949149, -0.00359270964, -0.00267819938, -0.00199647318, -0.0014882767,
    -0.00110943843, -0.00082703048, -0.000616506464, -0.000459568035, -0.000342574398,
    -0.000255356686, -0.000190334098, -0.000141854993, -0.000105705658, -0.0000787440606,
    -0.0000586267495, -0.0000436051657, -0.0000323736611, -0.0000239560144, -0.0000176205797,
    -0.0000128166926, -0.00000912680852, -0.00000623020949, -0.0000038751286, -0.00000185686662,
)

private fun Regular_deconvolution(p: Ptr, inputValue: Double, index: Int): Double {
    val input = inputValue.toFloat()
    for (offset in 0x08 until 0xf0 step 8) writeI64(p.plus(offset), readI64(p.plus(offset + 8)))
    writeF64(p.plus(0xf0), input.toDouble())
    var sum = 0f
    var offset = 0xf0
    for (coefficient in V116_DECONVOLUTION) {
        sum = (sum.toDouble() + readF64(p.plus(offset)) * coefficient).toFloat()
        offset -= 8
    }
    return if (input < 3.5f) input.toDouble() else (sum.toDouble() * if (input > 15f) 6.65 else 6.7).toFloat().toDouble()
}

private fun Arrow_direction(p: Ptr, inputValue: Double, index: Int): Int {
    if (p.bytes.isEmpty()) return 0
    val rounded = ((inputValue.toFloat() * 10f + 0.5f).toInt() / 10f)
    val previousValue = rf(p, 0x0c)
    val delta = rounded - previousValue
    val absolute = kotlin.math.abs(delta.toDouble())
    wf(p, 0x08, rounded)
    var direction = when {
        absolute >= 0.3 -> {
            if (absolute < 0.55) {
                if (delta >= 0f) 1 else -1
            } else if (delta >= 0f) {
                val previous = ri(p, 4)
                if (Integer.compareUnsigned(previous - 1, 2) < 0) 2 else 1
            } else {
                val previous = ri(p, 4)
                if (Integer.compareUnsigned(previous, -2) < 0) -1 else -2
            }
        }
        rounded < 4.5f && absolute >= 0.1 -> if (delta >= 0f) 1 else -1
        else -> 0
    }
    wi(p, 0, direction)
    val downCount = if (delta < -0.1f) ri(p, 0x18) + 1 else 0
    wi(p, 0x18, downCount)
    var upCount = if (delta > 0.1f) ri(p, 0x14) + 1 else 0
    wi(p, 0x14, upCount)
    var steadyCount = if (direction == 0) ri(p, 0x1c) + 1 else 0
    wi(p, 0x1c, steadyCount)
    if (upCount >= 2 && direction == 0) {
        direction = 1
        wi(p, 0, 1)
    }
    if (downCount >= 2 && direction == 0) {
        direction = -1
        wi(p, 0, -1)
    }
    var previousDirection = ri(p, 4)
    var stable = ri(p, 0x20)
    if (steadyCount >= 2) {
        direction = when {
            previousDirection < -1 -> -1
            previousDirection > 1 -> 1
            else -> direction
        }
        wi(p, 0, direction)
    }
    if (previousDirection == direction) {
        stable += 1
    } else if (stable < 3) {
        stable = 0
        previousDirection = direction
    } else {
        direction = previousDirection
        wi(p, 0, direction)
        stable = 0
    }
    wi(p, 0x20, stable)
    if (index == 0) {
        direction = 5
        wi(p, 0, 5)
    }
    wi(p, 4, direction)
    wf(p, 0x0c, rounded)
    wf(p, 0x10, previousValue)
    return direction
}

private fun changeV116Sensitivity(decodedValue: Float): Float {
    val decoded = decodedValue.toDouble()
    return when {
        decoded < 0.85 -> 1.35f
        decodedValue > 2.5f -> 1.45f
        decoded < 0.9 -> 0.93f
        decodedValue < 1f -> 1.02f
        decoded < 1.1 -> 1.12f
        decoded < 1.2 -> 1.22f
        else -> {
            val multiplier = when {
                decoded < 1.3 -> 0.95
                decoded < 1.4 -> 0.93
                decoded < 1.5 -> 0.92
                decoded < 1.6 -> 0.92
                decoded < 1.7 -> 0.92
                decoded < 1.8 -> 0.91
                decoded < 1.9 -> 0.9
                else -> 0.89
            }
            (decoded * multiplier).toFloat()
        }
    }
}

/** Exact managed port of the proprietary Sibionics Algorithm V1.1.6A state machine. */
internal class SibionicsExactV116ACore(decodedSensitivity: Float = 1.27f) {
    private var decodedSensitivity = decodedSensitivity
    private var context = newContext(decodedSensitivity)

    var latestChemicalSignal: SibionicsChemicalSignal? = null
        private set

    /**
     * Vendor sensor-state observation on the absolute glucose scale.
     *
     * The V1.1.6A pipeline has the same shape as V1.1.5G:
     *
     * ```
     *   ESA_Compensate(ctx+0x538, adjusted, ctx[0x510]) -> adjusted + mean(five compensations)
     *   Regular_deconvolution(ctx+0x858, esaOutput)
     * ```
     *
     * The ESA output *is* the calibrated observation — sensor-state
     * compensation applied, deconvolution not yet — and the deconvolution
     * stores its own input at offset 0xf0 of its context, so it can be read
     * back at `ctx + 0x858 + 0xf0` without re-deriving `adjusted`.
     *
     * That slot is only *written* on the vendor's five-sample correction
     * stage, so reading it directly gives a sample-and-held value that stands
     * still for four minutes and then jumps — it moved on 1 minute in 5, and
     * hid every turning point for up to four minutes. The front end behind it
     * runs every minute, so this republishes the held sensor-state terms
     * against the current one-minute chemical signal, exactly as V1.1.5G's
     * core does with `heldBase`/`heldEsaCompensation`. Within a stage the
     * vendor's own arithmetic is `chemical` plus a constant, so carrying that
     * constant is the identity, not an approximation.
     */
    var latestSensorObservation: SibionicsSensorObservation? = null
        private set

    /**
     * `esaOutput - chemical` as captured on the most recent correction stage:
     * the vendor's sensitivity/base/ESA sensor-state terms collapsed into the
     * one constant that separates the two within a stage.
     */
    private var heldCalibrationOffsetMmol = 0f

    /** Raw bits of the last ESA output seen, used to detect a stage refresh. */
    private var lastStageEsaBits = 0L

    /** False until the first correction stage has produced sensor-state terms. */
    private var hasStageState = false

    fun configure(value: Float) {
        val normalized = value.takeIf { it.isFinite() } ?: 1.27f
        if (normalized.toRawBits() == decodedSensitivity.toRawBits()) return
        decodedSensitivity = normalized
        reset()
    }

    fun reset() {
        context = newContext(decodedSensitivity)
        latestChemicalSignal = null
        latestSensorObservation = null
        heldCalibrationOffsetMmol = 0f
        lastStageEsaBits = 0L
        hasStageState = false
    }

    fun process(rawMmol: Float, temperatureC: Float, index: Int): Float? {
        if (!rawMmol.isFinite() || rawMmol <= 0f || !temperatureC.isFinite() || index < 1) {
            latestChemicalSignal = null
            latestSensorObservation = null
            return null
        }
        val trend = Ptr(ByteArray(4))
        val output = algorithm_convert_process(
            Ptr(context), rawMmol.toDouble(), temperatureC.toDouble(), 0.0,
            index, trend, 4.4, 11.1,
        ).toFloat()
        val pointer = Ptr(context)
        val filtered = readF32(pointer.plus(0x158)).toFloat()
        val averageTemperature = readF32(pointer.plus(0x9a0)).toFloat()
        val compensated = temperatureCompensatedValue(filtered, averageTemperature)
        val activeSensitivity = readF32(pointer.plus(0xdc)).toFloat()
        val qualityFlags = readI32(pointer.plus(0x34))
        val chemical = if (activeSensitivity.isFinite() && activeSensitivity > 0f) {
            (compensated / activeSensitivity).coerceAtLeast(0f)
        } else {
            Float.NaN
        }
        latestChemicalSignal = SibionicsChemicalSignal(
            mmol = chemical,
            qualityFlags = qualityFlags,
        )
        publishSensorObservation(pointer, chemical, activeSensitivity, qualityFlags, index)
        return output.takeIf { it.isFinite() && it > 0f }
    }

    private fun publishSensorObservation(
        pointer: Ptr,
        chemical: Float,
        activeSensitivity: Float,
        qualityFlags: Int,
        index: Int,
    ) {
        // Last value handed to the deconvolution, i.e. the ESA output. This
        // slot only changes on a correction stage; the minutes in between must
        // reuse its sensor-state terms rather than its stale absolute value.
        val stageEsa = readF64(pointer.plus(DECONVOLUTION_INPUT_OFFSET))
        val stageEsaBits = stageEsa.toRawBits()
        val stageCalibrated = stageEsa.toFloat()
        if (stageEsaBits != lastStageEsaBits &&
            stageCalibrated.isFinite() && stageCalibrated > 0f && chemical.isFinite()
        ) {
            lastStageEsaBits = stageEsaBits
            heldCalibrationOffsetMmol = stageCalibrated - chemical
            hasStageState = true
        }
        if (!hasStageState || !chemical.isFinite() ||
            !activeSensitivity.isFinite() || activeSensitivity <= 0f
        ) {
            latestSensorObservation = null
            return
        }
        val calibrated = chemical + heldCalibrationOffsetMmol
        if (!calibrated.isFinite() || calibrated <= 0f) {
            latestSensorObservation = null
            return
        }
        var compensationSum = 0f
        for (slot in 0 until ESA_BUFFER_SLOTS) {
            compensationSum += readF32(pointer.plus(ESA_BUFFER_OFFSET + slot * 4)).toFloat()
        }
        latestSensorObservation = SibionicsSensorObservation(
            calibratedMmol = calibrated,
            chemicalMmol = chemical,
            sensorStateCompensationMmol = compensationSum / ESA_BUFFER_SLOTS,
            qualityFlags = qualityFlags,
            factorySensitivity = changeV116Sensitivity(decodedSensitivity).toFloat(),
            activeSensitivity = activeSensitivity,
            sensorAgeMinutes = index,
            family = FAMILY_V116A,
        )
    }

    private fun temperatureCompensatedValue(input: Float, averageTemperature: Float): Float {
        if (!input.isFinite() || !averageTemperature.isFinite()) return Float.NaN
        val x = averageTemperature.toDouble()
        val polynomial =
            x * -0.03786363 +
                x * x * -0.7637396454811096 +
                Math.pow(x, 3.0) * 0.061271119862794876 +
                Math.pow(x, 4.0) * -0.0019132299348711967 +
                Math.pow(x, 5.0) * 0.00002677628435776569 +
                Math.pow(x, 6.0) * -0.00000013804908860493015 +
                65.21018525
        return ((input - 0.5) * (34.2 - polynomial) * 0.04 + input).toFloat()
    }

    fun snapshot(): ByteArray {
        val output = ByteArray(HEADER_SIZE + context.size + HELD_STATE_SIZE)
        val header = Ptr(output)
        writeI32(header, SNAPSHOT_MAGIC)
        writeI32(header.plus(4), SNAPSHOT_VERSION)
        writeI32(header.plus(8), decodedSensitivity.toRawBits())
        context.copyInto(output, HEADER_SIZE)
        val held = Ptr(output).plus(HEADER_SIZE + context.size)
        writeI32(held, heldCalibrationOffsetMmol.toRawBits())
        writeI64(held.plus(4), lastStageEsaBits)
        writeI32(held.plus(12), if (hasStageState) 1 else 0)
        return output
    }

    internal fun stateHash(): String {
        var hash = 1469598103934665603L
        context.forEach { byte ->
            hash = hash xor (byte.toLong() and 0xffL)
            hash *= 1099511628211L
        }
        return "%016x".format(hash)
    }

    fun restore(snapshot: ByteArray?): Boolean {
        if (snapshot == null || snapshot.size != HEADER_SIZE + CONTEXT_SIZE + HELD_STATE_SIZE) {
            return false
        }
        val header = Ptr(snapshot)
        if (readI32(header) != SNAPSHOT_MAGIC || readI32(header.plus(4)) != SNAPSHOT_VERSION) {
            return false
        }
        if (readI32(header.plus(8)) != decodedSensitivity.toRawBits()) return false
        context = snapshot.copyOfRange(HEADER_SIZE, HEADER_SIZE + CONTEXT_SIZE)
        val held = Ptr(snapshot).plus(HEADER_SIZE + CONTEXT_SIZE)
        heldCalibrationOffsetMmol = Float.fromBits(readI32(held))
        lastStageEsaBits = readI64(held.plus(4))
        hasStageState = readI32(held.plus(12)) != 0
        return true
    }

    private companion object {
        private const val CONTEXT_SIZE = 0x9ac
        const val FAMILY_V116A = 116

        private const val SNAPSHOT_MAGIC = 0x5331_3136

        /**
         * 2 adds the held sensor-state terms. A version-1 snapshot restores as
         * false, which is the existing signal to rebuild rather than continue.
         */
        private const val SNAPSHOT_VERSION = 2
        private const val HEADER_SIZE = 12

        /** offset (f32) + last ESA bits (i64) + hasStageState (i32). */
        private const val HELD_STATE_SIZE = 16

        /** ESA_Compensate's five-slot compensation history: ctx + 0x538, four bytes per slot. */
        private const val ESA_BUFFER_OFFSET = 0x538
        private const val ESA_BUFFER_SLOTS = 5

        /** Regular_deconvolution stores its input at 0xf0 of its own context at ctx + 0x858. */
        private const val DECONVOLUTION_INPUT_OFFSET = 0x858 + 0xf0

        private fun newContext(decodedSensitivity: Float): ByteArray {
            val template = V116_TEMPLATE_HEX
            check(template.length == CONTEXT_SIZE * 2)
            val bytes = ByteArray(CONTEXT_SIZE) { index ->
                template.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            writeF32(Ptr(bytes).plus(0xdc), changeV116Sensitivity(decodedSensitivity).toDouble())
            return bytes
        }
    }
}
