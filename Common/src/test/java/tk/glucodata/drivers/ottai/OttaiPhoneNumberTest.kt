package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OttaiPhoneNumberTest {
    @Test
    fun acceptsMainlandMobileSubscriberDigits() {
        assertEquals("13800138000", normalizeOttaiPhone("13800138000", OttaiSmsCountry.MAINLAND_CHINA))
        assertEquals("19912345678", normalizeOttaiPhone("19912345678", OttaiSmsCountry.MAINLAND_CHINA))
    }

    @Test
    fun rejectsInvalidMainlandSubscriberDigits() {
        assertNull(normalizeOttaiPhone("12800138000", OttaiSmsCountry.MAINLAND_CHINA))
        assertNull(normalizeOttaiPhone("1380013800", OttaiSmsCountry.MAINLAND_CHINA))
        assertNull(normalizeOttaiPhone("13800138000x", OttaiSmsCountry.MAINLAND_CHINA))
        assertNull(normalizeOttaiPhone("+8613800138000", OttaiSmsCountry.MAINLAND_CHINA))
    }

    @Test
    fun acceptsHongKongMobileSubscriberDigits() {
        assertEquals("51234567", normalizeOttaiPhone("51234567", OttaiSmsCountry.HONG_KONG))
        assertEquals("91234567", normalizeOttaiPhone("91234567", OttaiSmsCountry.HONG_KONG))
    }

    @Test
    fun rejectsInvalidHongKongSubscriberDigits() {
        assertNull(normalizeOttaiPhone("31234567", OttaiSmsCountry.HONG_KONG))
        assertNull(normalizeOttaiPhone("5123456", OttaiSmsCountry.HONG_KONG))
        assertNull(normalizeOttaiPhone("+85251234567", OttaiSmsCountry.HONG_KONG))
    }
}
