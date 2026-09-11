package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductBarcodeTests {
    @Test
    fun validEan13Ean8AndUpcA() {
        assertEquals("4000417025005", ProductBarcode.normalize("4000417025005"))
        assertEquals("5449000000996", ProductBarcode.normalize(" 5449000000996 "))
        assertEquals("96385074", ProductBarcode.normalize("96385074"))
        // UPC-A becomes the 13-digit key Open Food Facts uses.
        assertEquals("0049000006346", ProductBarcode.normalize("049000006346"))
        assertEquals("0049000006346", ProductBarcode.normalize("0049000006346"))
    }

    @Test
    fun wrongCheckDigitOrShapeIsRejected() {
        assertNull(ProductBarcode.normalize("4000417025006"))
        assertNull(ProductBarcode.normalize("123"))
        assertNull(ProductBarcode.normalize("ABCDEFGH"))
        assertNull(ProductBarcode.normalize(null))
        // A sensor QR payload is not a product.
        assertNull(ProductBarcode.normalize("https://example.com/sensor/123"))
    }
}
