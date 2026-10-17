package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class FiscalTypesTest {
    @Test
    fun enumOrdersFollowTheSpecification() {
        assertEquals(
            listOf("Cash", "Card", "MobileWallet", "Coupon", "Credit", "BankTransfer", "Other"),
            MoneyType.entries.map { it.specName },
        )
        assertEquals(
            listOf(
                "SaleByTax", "SaleTaxByTax", "CreditNoteByTax", "CreditNoteTaxByTax",
                "DebitNoteByTax", "DebitNoteTaxByTax", "BalanceByMoneyType",
            ),
            FiscalCounterType.entries.map { it.specName },
        )
        assertEquals(listOf("FiscalInvoice", "CreditNote", "DebitNote"), ReceiptType.entries.map { it.specName })
    }

    @Test
    fun byTaxCountersNeedATaxAndNoMoneyType() {
        assertThrows(FiscalValueException::class.java) {
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", BigDecimal.ONE)
        }
        assertThrows(FiscalValueException::class.java) {
            FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", BigDecimal.ONE, taxId = 1, moneyType = MoneyType.CASH)
        }
    }

    @Test
    fun balanceCountersNeedAMoneyTypeAndNoTax() {
        assertThrows(FiscalValueException::class.java) {
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", BigDecimal.ONE)
        }
        assertThrows(FiscalValueException::class.java) {
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", BigDecimal.ONE, taxId = 1, moneyType = MoneyType.CASH)
        }
    }

    @Test
    fun countersAndTaxesValidateIdentifiers() {
        assertThrows(FiscalValueException::class.java) {
            FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "DOLLARS", BigDecimal.ONE, moneyType = MoneyType.CASH)
        }
        assertThrows(FiscalValueException::class.java) { ReceiptTax(0, null, BigDecimal.ZERO, BigDecimal.ONE) }
    }
}
