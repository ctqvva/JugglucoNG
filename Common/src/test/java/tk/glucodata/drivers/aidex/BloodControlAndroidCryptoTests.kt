package tk.glucodata.drivers.aidex

import java.security.*
import java.security.spec.AlgorithmParameterSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.drivers.aidex.bloodcontrol.*

/** Android exposes digest-specific PSS but not the generic SunRsaSign algorithm name. */
class BloodControlAndroidCryptoTests {
    class AndroidOnlyProvider : Provider("AndroidOnlyPss", 1.0, "Synthetic Android algorithm availability") {
        init { put("Signature.SHA256withRSA/PSS", AndroidPssSpi::class.java.name) }
    }

    class AndroidPssSpi : SignatureSpi() {
        private val delegate = Signature.getInstance("RSASSA-PSS", desktopProvider)
        override fun engineInitVerify(key: PublicKey) = delegate.initVerify(key)
        override fun engineInitSign(key: PrivateKey) = delegate.initSign(key)
        override fun engineUpdate(value: Byte) = delegate.update(value)
        override fun engineUpdate(value: ByteArray, offset: Int, length: Int) = delegate.update(value, offset, length)
        override fun engineSign(): ByteArray = delegate.sign()
        override fun engineVerify(signature: ByteArray): Boolean = delegate.verify(signature)
        override fun engineSetParameter(parameters: AlgorithmParameterSpec) = delegate.setParameter(parameters)
        override fun engineGetParameters(): AlgorithmParameters = delegate.parameters
        @Deprecated("Legacy SPI") override fun engineSetParameter(name: String, value: Any) { error("Unused") }
        @Deprecated("Legacy SPI") override fun engineGetParameter(name: String): Any { error("Unused") }
    }

    @Test fun wireSigningAndVerificationWorkWithoutGenericRsassaPss() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val credentials = BloodControlCredentials("synthetic", ByteArray(32), pair.private, pair.public)
        desktopProvider = Signature.getInstance("RSASSA-PSS").provider
        val original = Security.getProviders().toList()
        val removed = original.withIndex().filter { it.value.getService("Signature", "RSASSA-PSS") != null }
        removed.forEach { Security.removeProvider(it.value.name) }
        Security.insertProviderAt(AndroidOnlyProvider(), 1)
        try {
            try { Signature.getInstance("RSASSA-PSS"); fail("Generic algorithm must be absent") }
            catch (_: NoSuchAlgorithmException) { }
            val plain = "{\"test\":true}".toByteArray()
            val wrapper = JSONObject(String(BloodControlProtocol.wrap(plain, "2026-11-07T00:00:00Z", credentials)))
            assertTrue(BloodControlProtocol.verify(plain, wrapper.getString("signatureBase64"), pair.public))
            assertFalse(BloodControlProtocol.verify(plain + 32.toByte(), wrapper.getString("signatureBase64"), pair.public))
        } finally {
            Security.removeProvider("AndroidOnlyPss")
            removed.forEach { Security.insertProviderAt(it.value, it.index + 1) }
        }
    }

    companion object { private lateinit var desktopProvider: Provider }
}
