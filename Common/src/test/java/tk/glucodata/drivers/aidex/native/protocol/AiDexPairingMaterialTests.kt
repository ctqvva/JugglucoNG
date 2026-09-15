package tk.glucodata.drivers.aidex.native.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.AesCfb128
import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim

class AiDexPairingMaterialTests {

    @After
    fun clearRegistry() {
        AiDexPairingMaterialRegistry.clearForTests()
    }

    @Test
    fun materialRequiresTwoCompleteAesBlocks() {
        assertNull(AiDexPairingMaterial.fromDecodedBytes(ByteArray(15), ByteArray(16)))
        assertNull(AiDexPairingMaterial.fromDecodedBytes(ByteArray(16), ByteArray(17)))
    }

    @Test
    fun provisionedMaterialReplacesBothSerialDerivedValuesDefensively() {
        val secret = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (0x70 + it).toByte() }
        val expectedSecret = secret.copyOf()
        val expectedIv = iv.copyOf()
        val material = AiDexPairingMaterial.fromDecodedBytes(secret, iv)!!

        secret.fill(0)
        iv.fill(0)
        val exchange = AiDexKeyExchange("F-22222FZXKT", material)

        assertTrue(exchange.usesProvisionedPairingMaterial)
        assertArrayEquals(expectedSecret, exchange.getChallenge())
        assertArrayEquals(expectedIv, exchange.snIv)
    }

    @Test
    fun provisionedIvCompletesNormalBondDecryption() {
        val secret = ByteArray(16) { (0x20 + it).toByte() }
        val iv = ByteArray(16) { (0x40 + it).toByte() }
        val pairKey = ByteArray(16) { (0x60 + it).toByte() }
        val sessionKey = ByteArray(16) { (0x10 + it).toByte() }
        val bondPlaintext = sessionKey + Crc8Maxim.checksum(sessionKey).toByte()
        val bondCiphertext = AesCfb128.encrypt(bondPlaintext, pairKey, iv)!!
        val material = AiDexPairingMaterial.fromDecodedBytes(secret, iv)!!
        val exchange = AiDexKeyExchange("22222FZXKT", material)

        exchange.onPairKeyReceived(pairKey)

        assertTrue(exchange.decryptBond(bondCiphertext))
        assertArrayEquals(sessionKey, exchange.sessionKey)
    }

    @Test
    fun registryNormalizesGenerationPrefixesAndRejectsPartialMaterial() {
        val secret = ByteArray(16) { (it + 3).toByte() }
        val iv = ByteArray(16) { (it + 7).toByte() }

        assertFalse(AiDexPairingMaterialRegistry.install("", secret, iv))
        assertFalse(AiDexPairingMaterialRegistry.install("F-22222FZXKT", ByteArray(15), iv))
        assertTrue(AiDexPairingMaterialRegistry.install("AiDEX F-22222FZXKT", secret, iv))

        val exchange = AiDexKeyExchange(
            "X-22222FZXKT",
            AiDexPairingMaterialRegistry.find("22222FZXKT"),
        )
        assertTrue(exchange.usesProvisionedPairingMaterial)
        assertArrayEquals(secret, exchange.getChallenge())
        assertArrayEquals(iv, exchange.snIv)
    }
}
