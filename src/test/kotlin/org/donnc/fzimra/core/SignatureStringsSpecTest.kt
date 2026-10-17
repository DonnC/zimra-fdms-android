package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Conformance against the worked examples in ZIMRA's Fiscal Device Gateway
 * API v7.2 (§13). Only examples that reproduce their own printed hashes are
 * used: CreditNote example No 2 (§13.2.1) and the fiscal-day example (§13.3.1).
 */
class SignatureStringsSpecTest {
    private fun d(value: String) = BigDecimal(value)

    private val previousHash = "hNVJXP/ACOiE8McD3pKsDlqBXpuaUqQOfPnMyfZWI9k="

    private fun creditNoteExample2() = SignatureStrings.receipt(
        deviceId = 322,
        receiptType = ReceiptType.CREDIT_NOTE,
        currency = "USD",
        receiptGlobalNo = 85,
        receiptDate = LocalDateTime.of(2020, 9, 19, 9, 23, 7),
        receiptTotal = d("-40.35"),
        taxes = listOf(
            ReceiptTax(3, d("14.5"), d("-3.00"), d("-23.00")),
            ReceiptTax(1, null, d("0.00"), d("-7.00")),
            ReceiptTax(2, d("0"), d("0.00"), d("-10.00")),
        ),
        previousReceiptHash = previousHash,
    )

    @Test
    fun creditNoteExample2SignatureInputAndHash() {
        val canonical = creditNoteExample2()
        assertEquals("322CREDITNOTEUSD852020-09-19T09:23:07-40350-7000.000-100014.50-300-2300$previousHash", canonical)
        assertEquals("F9/QB0vhxQlEF2nk+oebwP8V+qBcNlOFvoTeE/1QxPc=", Sha256.base64(canonical))
    }

    @Test
    fun firstReceiptOfTheDayHasNoPreviousHash() {
        val canonical = SignatureStrings.receipt(
            322, ReceiptType.FISCAL_INVOICE, "usd", 1, LocalDateTime.of(2020, 9, 19, 9, 0),
            d("23.00"), listOf(ReceiptTax(1, d("15"), d("3.00"), d("23.00"))), null,
        )
        assertEquals("322FISCALINVOICEUSD12020-09-19T09:00:00230015.003002300", canonical)
    }

    @Test
    fun fiscalDayExampleHashAndCounterSegments() {
        // The printed concatenation, including its "USDLCASH" typo, reproduces the
        // printed hash, which pins the hashing step.
        val printed = "321842019-09-23SALEBYTAXZWL2300000SALEBYTAXZWL0.001200000SALEBYTAXUSD14.502500" +
            "SALEBYTAXZWL15.001200SALETAXBYTAXUSD15.00250SALETAXBYTAXZWL15.00230000" +
            "BALANCEBYMONEYTYPEUSDLCASH3700BALANCEBYMONEYTYPEZWLCASH2000000BALANCEBYMONEYTYPEZWLCARD1500000"
        assertEquals("OdT8lLI0JXhXl1XQgr64Zb1ltFDksFXThVxqM6O8xZE=", Sha256.base64(printed))

        val cases = listOf(
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "ZWL", d("23000.00"), taxId = 1) to "SALEBYTAXZWL2300000",
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "ZWL", d("12000.00"), 2, d("0")) to "SALEBYTAXZWL0.001200000",
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("25.00"), 3, d("14.5")) to "SALEBYTAXUSD14.502500",
            FiscalCounter(FiscalCounterType.SALE_TAX_BY_TAX, "ZWL", d("2300.00"), 4, d("15")) to
                "SALETAXBYTAXZWL15.00230000",
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "ZWL", d("15000"), moneyType = MoneyType.CARD) to
                "BALANCEBYMONEYTYPEZWLCARD1500000",
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("37.00"), moneyType = MoneyType.CASH) to
                "BALANCEBYMONEYTYPEUSDCASH3700",
        )
        for ((counter, expected) in cases) assertEquals(expected, SignatureStrings.counters(listOf(counter)))
    }

    @Test
    fun fiscalDayStringUsesTheOpeningDate() {
        assertEquals("321842019-09-23", SignatureStrings.fiscalDay(321, 84, LocalDate.of(2019, 9, 23), emptyList()))
    }

    @Test
    fun countersSortByTypeCurrencyTaxAndMoneyTypeEnumOrder() {
        val counters = listOf(
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "ZWL", d("150"), moneyType = MoneyType.CARD),
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "ZWL", d("10"), taxId = 2, taxPercent = d("15")),
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "ZWL", d("200"), moneyType = MoneyType.CASH),
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("5"), taxId = 2, taxPercent = d("15")),
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("1"), taxId = 1, taxPercent = d("0")),
        )
        assertEquals(
            "SALEBYTAXUSD0.00100SALEBYTAXUSD15.00500SALEBYTAXZWL15.001000" +
                "BALANCEBYMONEYTYPEZWLCASH20000BALANCEBYMONEYTYPEZWLCARD15000",
            SignatureStrings.counters(counters),
        )
    }

    @Test
    fun zeroCountersAreNeitherSignedNorSorted() {
        val counters = listOf(
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("0.00"), taxId = 1, taxPercent = d("15")),
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("1.00"), moneyType = MoneyType.CASH),
        )
        assertEquals("BALANCEBYMONEYTYPEUSDCASH100", SignatureStrings.counters(counters))
        assertEquals(1, SignatureStrings.sortedCounters(counters).size)
    }

    @Test
    fun negativeCreditNoteCountersAreSignedAsTheyAre() {
        val counter = FiscalCounter(FiscalCounterType.CREDIT_NOTE_BY_TAX, "USD", d("-23.00"), 3, d("14.5"))
        assertEquals("CREDITNOTEBYTAXUSD14.50-2300", SignatureStrings.counters(listOf(counter)))
    }

    @Test
    fun taxPercentFormatting() {
        assertEquals("15.00", SignatureStrings.formatPercent(d("15")))
        assertEquals("14.50", SignatureStrings.formatPercent(d("14.5")))
        assertEquals("0.00", SignatureStrings.formatPercent(d("0")))
        assertEquals("15.00", SignatureStrings.formatPercent(d("15.000")))
        assertEquals("", SignatureStrings.formatPercent(null))
        assertThrows(FiscalValueException::class.java) { SignatureStrings.formatPercent(d("14.125")) }
    }

    @Test
    fun dateTimeFormatDropsFractionsOfASecond() {
        assertEquals(
            "2026-01-05T07:08:09",
            SignatureStrings.formatDateTime(LocalDateTime.of(2026, 1, 5, 7, 8, 9, 999_000_000)),
        )
    }

    @Test
    fun receiptServerSignatureInputExample() {
        // §13.2.2 example.
        val deviceSignature = "YyXTSizBBrMjMk4VQL+sCNr+2AC6aQbDAn9JMV2rk3yJ6MDZwie0wqQW3oisNWrMkeZsuAyFSnFkU2A+pKm91sOH" +
            "VdjeRBebjQgAQQIMTCVIcYrx+BizQ7Ib9iCdsVI+Jel2nThqQiQzfRef6EgtgsaIAN+PV55xSrHvPkIe+Bc="
        assertEquals(
            "${deviceSignature}483772019-09-19T15:43:12",
            SignatureStrings.receiptServer(deviceSignature, 48377, LocalDateTime.of(2019, 9, 19, 15, 43, 12)),
        )
    }

    @Test
    fun fiscalDayServerStringIncludesDeviceSignatureUnlessManual() {
        val counters = listOf(
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("1.00"), moneyType = MoneyType.CASH),
        )
        val opened = LocalDate.of(2026, 10, 1)
        val updated = LocalDateTime.of(2026, 10, 1, 18, 0, 5)
        assertEquals(
            "7202026-10-012026-10-01T18:00:05AUTOBALANCEBYMONEYTYPEUSDCASH100SIG==",
            SignatureStrings.fiscalDayServer(7, 20, opened, updated, "Auto", counters, "SIG=="),
        )
        assertEquals(
            "7202026-10-012026-10-01T18:00:05MANUALBALANCEBYMONEYTYPEUSDCASH100",
            SignatureStrings.fiscalDayServer(7, 20, opened, updated, "Manual", counters, "SIG=="),
        )
    }

    @Test
    fun outputDoesNotDependOnTheDeviceLocale() {
        val expected = creditNoteExample2()
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.forLanguageTag("tr-TR"), Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
                Locale.setDefault(locale)
                assertEquals("locale $locale", expected, creditNoteExample2())
                assertEquals("14.50", SignatureStrings.formatPercent(d("14.5")))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun rejectsInvalidInputs() {
        val taxes = listOf(ReceiptTax(1, d("15"), d("3.00"), d("23.00")))
        val date = LocalDateTime.of(2026, 1, 1, 8, 0)
        assertThrows(FiscalValueException::class.java) {
            SignatureStrings.receipt(0, ReceiptType.FISCAL_INVOICE, "USD", 1, date, d("23.00"), taxes, null)
        }
        assertThrows(FiscalValueException::class.java) {
            SignatureStrings.receipt(1, ReceiptType.FISCAL_INVOICE, "USD", 0, date, d("23.00"), taxes, null)
        }
        assertThrows(FiscalValueException::class.java) {
            SignatureStrings.receipt(1, ReceiptType.FISCAL_INVOICE, "US", 1, date, d("23.00"), taxes, null)
        }
        assertThrows(FiscalValueException::class.java) {
            SignatureStrings.receipt(1, ReceiptType.FISCAL_INVOICE, "USD", 1, date, d("23.001"), taxes, null)
        }
        assertThrows(FiscalValueException::class.java) {
            SignatureStrings.receipt(1, ReceiptType.FISCAL_INVOICE, "USD", 1, date, d("23.00"), taxes, " ")
        }
        assertThrows(FiscalValueException::class.java) {
            SignatureStrings.fiscalDay(1, 0, LocalDate.of(2026, 1, 1), emptyList())
        }
    }
}
