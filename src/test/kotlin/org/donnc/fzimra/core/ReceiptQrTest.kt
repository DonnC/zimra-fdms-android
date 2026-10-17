package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class ReceiptQrTest {
    @Test
    fun specExample1() {
        // §11 example No 1.
        assertEquals(
            "https://invoice.zimra.co.zw/0000000321030420231112223331" + "4C8BE27663330417",
            ReceiptQr.url("https://invoice.zimra.co.zw", 321, LocalDate.of(2023, 4, 3), 1_112_223_331, "4C8BE27663330417"),
        )
    }

    @Test
    fun specExample2WithTrailingSlash() {
        // §11 example No 2.
        assertEquals(
            "https://invoice.zimra.co.zw/0000000322040420230000001332" + "C10B0476B3B14678",
            ReceiptQr.url("https://invoice.zimra.co.zw/", 322, LocalDate.of(2023, 4, 4), 1332, "C10B0476B3B14678"),
        )
    }

    @Test
    fun verificationCodeGroupsQrData() {
        // §10.3 field 46.
        assertEquals("4C8B-E276-6333-0417", ReceiptQr.verificationCode("4C8BE27663330417"))
        assertEquals("4C8B-E276-6333-0417", ReceiptQr.verificationCode("4c8be27663330417"))
    }

    @Test
    fun qrDataIsTheUpperCaseMd5PrefixOfTheRawSignature() {
        // MD5 of the bytes "abc" is 900150983cd24fb0d6963f7d28e17f72; "abc" in Base64 is "YWJj".
        assertEquals("900150983CD24FB0", ReceiptQr.qrData("YWJj"))
    }

    @Test
    fun rejectsInvalidInputs() {
        assertThrows(FiscalValueException::class.java) { ReceiptQr.qrData("not base64!") }
        assertThrows(FiscalValueException::class.java) { ReceiptQr.qrData("") }
        assertThrows(FiscalValueException::class.java) { ReceiptQr.verificationCode("4C8BE2766333041") }
        assertThrows(FiscalValueException::class.java) { ReceiptQr.verificationCode("4C8BE2766333041Z") }
        assertThrows(FiscalValueException::class.java) {
            ReceiptQr.url("", 1, LocalDate.of(2026, 1, 1), 1, "4C8BE27663330417")
        }
        assertThrows(FiscalValueException::class.java) {
            ReceiptQr.url("https://x", 0, LocalDate.of(2026, 1, 1), 1, "4C8BE27663330417")
        }
        assertThrows(FiscalValueException::class.java) {
            ReceiptQr.url("https://x", 1, LocalDate.of(2026, 1, 1), -1, "4C8BE27663330417")
        }
    }

    @Test
    fun sha256OfKnownInputs() {
        assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", Sha256.base64(""))
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", Sha256.base64("abc"))
    }
}
