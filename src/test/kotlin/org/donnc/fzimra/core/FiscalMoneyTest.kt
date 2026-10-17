package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class FiscalMoneyTest {
    private fun d(value: String) = BigDecimal(value)

    @Test
    fun roundsHalfToEven() {
        assertEquals(d("0.12"), FiscalMoney.roundToCents(d("0.125")))
        assertEquals(d("0.14"), FiscalMoney.roundToCents(d("0.135")))
        assertEquals(d("-0.12"), FiscalMoney.roundToCents(d("-0.125")))
        assertEquals(d("2.68"), FiscalMoney.roundToCents(d("2.6750")))
        assertEquals(d("10.00"), FiscalMoney.roundToCents(d("10")))
    }

    @Test
    fun convertsWholeCents() {
        assertEquals(4035L, FiscalMoney.toCents(d("40.35")))
        assertEquals(-4035L, FiscalMoney.toCents(d("-40.35")))
        assertEquals(700L, FiscalMoney.toCents(d("7")))
        assertEquals(0L, FiscalMoney.toCents(d("0.00")))
        assertEquals(10L, FiscalMoney.toCents(d("0.1000")))
    }

    @Test
    fun handlesAmountsBeyondIntCents() {
        // 2^31 cents is 21 474 836.48: totals above it must not overflow.
        assertEquals(2_147_483_648L, FiscalMoney.toCents(d("21474836.48")))
        assertEquals(99_999_999_999L, FiscalMoney.toCents(d("999999999.99")))
    }

    @Test
    fun refusesFractionsOfACent() {
        val e = assertThrows(FiscalValueException::class.java) { FiscalMoney.toCents(d("1.005"), "receiptTotal") }
        assertEquals("receiptTotal: 1.005 has a fraction of a cent", e.message)
    }

    @Test
    fun refusesAmountsBeyondLong() {
        assertThrows(FiscalValueException::class.java) { FiscalMoney.toCents(d("1E+20")) }
    }

    @Test
    fun convertsFromCents() {
        assertEquals(d("40.35"), FiscalMoney.fromCents(4035))
        assertEquals(d("-0.07"), FiscalMoney.fromCents(-7))
    }

    @Test
    fun splitsProportionallyAndExactly() {
        val parts = FiscalMoney.splitProportionally(d("0.10"), listOf(d("1"), d("1"), d("1")))
        assertEquals(listOf(d("0.04"), d("0.03"), d("0.03")), parts) // tie goes to the earlier weight
        assertEquals(d("0.10"), parts.fold(BigDecimal.ZERO, BigDecimal::add))
    }

    @Test
    fun splitFollowsWeightsAndKeepsSign() {
        val parts = FiscalMoney.splitProportionally(d("-1.00"), listOf(d("3"), d("1")))
        assertEquals(listOf(d("-0.75"), d("-0.25")), parts)
    }

    @Test
    fun splitWithZeroWeightsGivesEverythingToFirst() {
        assertEquals(
            listOf(d("0.05"), d("0.00")),
            FiscalMoney.splitProportionally(d("0.05"), listOf(BigDecimal.ZERO, BigDecimal.ZERO)),
        )
        assertEquals(emptyList<BigDecimal>(), FiscalMoney.splitProportionally(d("1.00"), emptyList()))
    }

    @Test
    fun splitSumsExactlyForManyAwkwardShares() {
        val weights = listOf(d("33.33"), d("33.33"), d("33.34"), d("0.01"), d("7"))
        for (cents in listOf(1L, 2L, 7L, 99L, 10_001L, 123_457L)) {
            val total = FiscalMoney.fromCents(cents)
            val sum = FiscalMoney.splitProportionally(total, weights).fold(BigDecimal.ZERO, BigDecimal::add)
            assertEquals(total, sum)
        }
    }
}
