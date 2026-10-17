package org.donnc.fzimra.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The strings that a fiscal device and FDMS hash and sign.
 *
 * Fields are converted to text and concatenated with no separator.
 * A single wrong character makes FDMS flag the receipt with RCPT020 or refuse
 * the day close with `BadCertificateSignature`, so every function here is
 * tested against the specification's worked examples.
 *
 * All text is upper-cased with [Locale.ROOT], independent of the device locale.
 */
public object SignatureStrings {
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val CURRENCY = Regex("^[A-Za-z]{3}$")

    /** `YYYY-MM-DDTHH:mm:ss`: local wall-clock time, 24-hour, no time zone. */
    public fun formatDateTime(value: LocalDateTime): String = DATE_TIME.format(value)

    /** `YYYY-MM-DD`. */
    public fun formatDate(value: LocalDate): String = DATE.format(value)

    /**
     * A tax percent as it is signed: `15` → `"15.00"`,
     * `14.5` → `"14.50"`, `0` → `"0.00"`, exempt (`null`) → `""`.
     *
     * @throws FiscalValueException if [percent] has more than two decimal places.
     */
    public fun formatPercent(percent: BigDecimal?): String {
        if (percent == null) return ""
        return try {
            percent.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        } catch (e: ArithmeticException) {
            throw FiscalValueException("tax percent $percent has more than two decimal places")
        }
    }

    /**
     * The tax block of a receipt signature:
     * taxPercent ‖ taxAmount in cents ‖ salesAmountWithTax in cents, for each
     * tax ordered by taxID.
     */
    public fun receiptTaxes(taxes: List<ReceiptTax>): String =
        taxes.sortedBy { it.taxId }.joinToString("") { tax ->
            formatPercent(tax.taxPercent) +
                FiscalMoney.toCents(tax.taxAmount, "taxAmount") +
                FiscalMoney.toCents(tax.salesAmountWithTax, "salesAmountWithTax")
        }

    /**
     * The input of a receipt's device signature:
     * deviceID ‖ receiptType ‖ receiptCurrency ‖ receiptGlobalNo ‖ receiptDate ‖
     * receiptTotal in cents ‖ receiptTaxes ‖ previousReceiptHash.
     *
     * [previousReceiptHash] is `null` for the first receipt of a fiscal day.
     */
    public fun receipt(
        deviceId: Int,
        receiptType: ReceiptType,
        currency: String,
        receiptGlobalNo: Int,
        receiptDate: LocalDateTime,
        receiptTotal: BigDecimal,
        taxes: List<ReceiptTax>,
        previousReceiptHash: String?,
    ): String {
        requirePositive(deviceId, "deviceID")
        requirePositive(receiptGlobalNo, "receiptGlobalNo")
        if (previousReceiptHash != null && previousReceiptHash.isBlank()) {
            throw FiscalValueException("previousReceiptHash must be null or a hash, not blank")
        }
        return buildString {
            append(deviceId)
            append(receiptType.specName.uppercase(Locale.ROOT))
            append(currencyCode(currency))
            append(receiptGlobalNo)
            append(formatDateTime(receiptDate))
            append(FiscalMoney.toCents(receiptTotal, "receiptTotal"))
            append(receiptTaxes(taxes))
            append(previousReceiptHash ?: "")
        }
    }

    /**
     * The input of FDMS's signature on a receipt:
     * receiptDeviceSignature ‖ receiptID ‖ serverDate.
     */
    public fun receiptServer(deviceSignatureBase64: String, receiptId: Long, serverDate: LocalDateTime): String =
        deviceSignatureBase64 + receiptId + formatDateTime(serverDate)

    /**
     * Non-zero [counters] in signing order: by counter type, then
     * currency, then taxID, then money type in ZIMRA's order.
     */
    public fun sortedCounters(counters: List<FiscalCounter>): List<FiscalCounter> =
        counters.filter { it.value.signum() != 0 }.sortedWith(
            compareBy<FiscalCounter> { it.type.ordinal }
                .thenBy { it.currency.uppercase(Locale.ROOT) }
                .thenBy { it.taxId ?: -1 }
                .thenBy { it.moneyType?.ordinal ?: -1 },
        )

    /**
     * The counter block of a fiscal-day signature: for each non-zero
     * counter, counterType ‖ currency ‖ taxPercent or moneyType ‖ value in cents.
     */
    public fun counters(counters: List<FiscalCounter>): String =
        sortedCounters(counters).joinToString("") { counter ->
            val qualifier = counter.moneyType?.specName?.uppercase(Locale.ROOT)
                ?: formatPercent(counter.taxPercent)
            counter.type.specName.uppercase(Locale.ROOT) +
                currencyCode(counter.currency) +
                qualifier +
                FiscalMoney.toCents(counter.value, "fiscalCounterValue")
        }

    /**
     * The input of a fiscal day's device signature:
     * deviceID ‖ fiscalDayNo ‖ fiscalDayDate ‖ fiscalDayCounters.
     *
     * [fiscalDayDate] is the date the day was opened, not the date it closes.
     */
    public fun fiscalDay(
        deviceId: Int,
        fiscalDayNo: Int,
        fiscalDayDate: LocalDate,
        counters: List<FiscalCounter>,
    ): String {
        requirePositive(deviceId, "deviceID")
        requirePositive(fiscalDayNo, "fiscalDayNo")
        return "$deviceId$fiscalDayNo${formatDate(fiscalDayDate)}${counters(counters)}"
    }

    /**
     * The input of FDMS's signature on a closed fiscal day:
     * deviceID ‖ fiscalDayNo ‖ fiscalDayDate ‖ fiscalDayUpdated ‖
     * reconciliationMode ‖ fiscalDayCounters ‖ fiscalDayDeviceSignature.
     *
     * The device signature is left out when the day was reconciled manually.
     */
    public fun fiscalDayServer(
        deviceId: Int,
        fiscalDayNo: Int,
        fiscalDayDate: LocalDate,
        fiscalDayUpdated: LocalDateTime,
        reconciliationMode: String,
        counters: List<FiscalCounter>,
        deviceSignatureBase64: String?,
    ): String {
        val mode = reconciliationMode.uppercase(Locale.ROOT)
        return buildString {
            append(deviceId)
            append(fiscalDayNo)
            append(formatDate(fiscalDayDate))
            append(formatDateTime(fiscalDayUpdated))
            append(mode)
            append(counters(counters))
            if (mode != "MANUAL") append(deviceSignatureBase64 ?: "")
        }
    }

    internal fun currencyCode(currency: String): String {
        if (!CURRENCY.matches(currency)) throw FiscalValueException("currency must be an ISO 4217 code, got '$currency'")
        return currency.uppercase(Locale.ROOT)
    }

    private fun requirePositive(value: Int, field: String) {
        if (value <= 0) throw FiscalValueException("$field must be positive, got $value")
    }
}
