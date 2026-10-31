package tk.glucodata.share.ican

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The build-time values the iCan Health cloud channel sends with every request.
 *
 * The vendor rejects a request whose headers do not match the official app's, so a typo in any
 * of these is not a cosmetic bug: it turns every call into a rejected one. These tests pin the
 * values against the contract recovered from the RU app v01.16.01.02.
 */
class ICanHealthCloudConstantsTests {

    @Test
    fun baseUrlAndPathMatchVendor() {
        assertEquals("https://rus.icancgm.com", ICanHealthCloudConstants.BASE_URL)
        assertEquals("/ican/api/", ICanHealthCloudConstants.APP_HOST_FIRST_PATH)
    }

    @Test
    fun rsaPublicKeyIsPem() {
        assertTrue(ICanHealthCloudConstants.RSA_PUBLIC_KEY.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertTrue(ICanHealthCloudConstants.RSA_PUBLIC_KEY.endsWith("-----END PUBLIC KEY-----"))
    }

    @Test
    fun rsaPublicKeyParsesAsA2048BitKey() {
        val body = ICanHealthCloudConstants.RSA_PUBLIC_KEY
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filter { !it.isWhitespace() }
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
                as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
    }

    @Test
    fun requestHeadersMatchTheOfficialApp() {
        assertEquals("30", ICanHealthCloudConstants.VERSION_CODE)
        assertEquals("01.16.01.02", ICanHealthCloudConstants.VERSION_NAME)
        assertEquals("ican-health-android", ICanHealthCloudConstants.CLIENT_ID)
        assertEquals("1", ICanHealthCloudConstants.PLATFORM_TYPE)
        assertEquals("RUS", ICanHealthCloudConstants.COUNTRY)
        assertEquals("ru", ICanHealthCloudConstants.LANG_CODE)
        assertEquals("3", ICanHealthCloudConstants.TIME_ZONE)
    }

    @Test
    fun headerNamesAreSpelledTheWayTheVendorSpellsThem() {
        assertEquals("Sino-Auth", ICanHealthCloudConstants.HEADER_SINO_AUTH)
        assertEquals("platformType", ICanHealthCloudConstants.HEADER_PLATFORM_TYPE)
        assertEquals("langCode", ICanHealthCloudConstants.HEADER_LANG_CODE)
        assertEquals("country", ICanHealthCloudConstants.HEADER_COUNTRY)
        assertEquals("versionCode", ICanHealthCloudConstants.HEADER_VERSION_CODE)
        assertEquals("versionName", ICanHealthCloudConstants.HEADER_VERSION_NAME)
        assertEquals("clientId", ICanHealthCloudConstants.HEADER_CLIENT_ID)
        assertEquals("authorization", ICanHealthCloudConstants.HEADER_AUTHORIZATION)
        assertEquals("timeZone", ICanHealthCloudConstants.HEADER_TIME_ZONE)
    }

    @Test
    fun authorizationCarriesTheVendorBasicCredentials() {
        val authorization = ICanHealthCloudConstants.AUTHORIZATION_BASIC
        assertTrue(authorization.startsWith("Basic "))
        val decoded = String(Base64.getDecoder().decode(authorization.removePrefix("Basic ")))
        assertEquals("ican-health-android:e91a8c9c5b4f40faa11263e7bb7ca0a7", decoded)
    }

    @Test
    fun randomStringAlphabetIsTheVendorAlphabet() {
        val alphabet = ICanHealthCloudConstants.GET_RANDOM_STRING_ALPHABET
        assertEquals("ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678", alphabet)
        assertEquals(alphabet.length, alphabet.toSet().size)
        assertTrue(alphabet.all { it.isLetterOrDigit() })
        assertTrue(alphabet.any { it.isUpperCase() })
        assertTrue(alphabet.any { it.isLowerCase() })
        assertTrue(alphabet.any { it.isDigit() })
    }
}
