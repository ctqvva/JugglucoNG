package tk.glucodata.drivers.ottai

import java.io.DataInputStream
import java.io.DataOutputStream

/** Retain the experiment's mode/baseline across an interrupted flash, including app restart. */
internal object OttaiFirmwareJournal {
    fun write(output: DataOutputStream, sensorId: String, address: String, image: OttaiFirmwareImage) {
        output.writeInt(2)
        output.writeUTF(sensorId)
        output.writeUTF(address)
        output.writeUTF(image.source)
        output.writeUTF(image.target)
        output.writeUTF(image.image.sha256)
        output.writeInt(image.image.size)
        output.write(image.image.bytes())
        output.writeUTF(image.mode.name)
        output.writeBoolean(image.before != null)
        image.before?.let { before ->
            output.writeInt(before.commandStatus)
            output.writeLong(before.maxActiveSeconds ?: -1L)
            output.writeInt(before.latestDataNo ?: -1)
            output.writeLong(before.rtcSeconds ?: -1L)
        }
        output.flush()
    }

    fun read(input: DataInputStream, sensorId: String, address: String): OttaiFirmwareImage {
        val format = input.readInt()
        require(format in 1..2 && input.readUTF() == sensorId) { "recovery_identity" }
        val storedAddress = input.readUTF()
        require(address == storedAddress) { "recovery_identity" }
        val source = OttaiOtaProtocol.version(input.readUTF()) ?: error("source")
        val target = OttaiOtaProtocol.version(input.readUTF()) ?: error("target")
        val sha = input.readUTF()
        val size = input.readInt()
        require(size in 28..OttaiOtaPackage.MAX_BYTES)
        val bytes = ByteArray(size).also { input.readFully(it) }
        val mode = if (format == 1) OttaiFirmwareMode.PREACTIVATION else OttaiFirmwareMode.valueOf(input.readUTF())
        val before = if (format == 2 && input.readBoolean()) OttaiFirmwareObservation(
            input.readInt(), input.readLong().takeIf { it >= 0 }, input.readInt().takeIf { it >= 0 },
            input.readLong().takeIf { it >= 0 },
        ) else null
        require(input.read() == -1)
        val image = OttaiOtaPackage.parse(bytes)
        require(image.sha256 == sha)
        return OttaiFirmwareImage(source, target, image, mode, before)
    }
}
