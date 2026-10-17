package org.donnc.fzimra.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Conformance against `vectors/zimra-fdms-vectors.json`, whose receipts were
 * verified on ZIMRA's test environment (see `vectors/NOTICE.md`). Canonical
 * strings and hashes must match byte for byte.
 *
 * The vectors' receipts carry `taxCode` in the payload but not in the signed
 * string; fzimra does not model `taxCode` (see [ReceiptTax]), so their
 * canonical strings are reproduced directly.
 *
 * Numbers are read from their JSON text, so amounts never pass through
 * floating point.
 */
class VectorConformanceTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("vectors/zimra-fdms-vectors.json")) { "vectors missing" }
            .readText(),
    ).jsonObject

    private fun JsonObject.isNull(key: String): Boolean = this[key] == null || this[key] is JsonNull

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = text(key).toInt()

    private fun JsonObject.decimal(key: String): BigDecimal = BigDecimal(text(key))

    private fun JsonObject.optDecimal(key: String): BigDecimal? = if (isNull(key)) null else decimal(key)

    private fun JsonObject.optInt(key: String): Int? = if (isNull(key)) null else int(key)

    private fun JsonObject.optText(key: String): String? = if (isNull(key)) null else text(key)

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    private inline fun JsonArray.objects(action: (JsonObject) -> Unit) {
        for (element in this) action(element.jsonObject)
    }

    @Test
    fun receiptCanonicalStringsAndHashes() {
        var checked = 0
        vectors.array("receipts").objects { vector ->
            val receipt = vector.getValue("receipt").jsonObject
            val taxes = mutableListOf<ReceiptTax>()
            receipt.array("receiptTaxes").objects { tax ->
                taxes += ReceiptTax(
                    taxId = tax.int("taxID"),
                    taxPercent = tax.optDecimal("taxPercent"),
                    taxAmount = tax.decimal("taxAmount"),
                    salesAmountWithTax = tax.decimal("salesAmountWithTax"),
                )
            }
            val canonical = SignatureStrings.receipt(
                deviceId = vector.int("deviceId"),
                receiptType = ReceiptType.entries.single { it.specName == receipt.text("receiptType") },
                currency = receipt.text("receiptCurrency"),
                receiptGlobalNo = receipt.int("receiptGlobalNo"),
                receiptDate = LocalDateTime.parse(receipt.text("receiptDate")),
                receiptTotal = receipt.decimal("receiptTotal"),
                taxes = taxes,
                previousReceiptHash = vector.optText("previousReceiptHash"),
            )
            val name = vector.text("name")
            assertEquals(name, vector.text("canonical"), canonical)
            assertEquals(name, vector.text("hash"), Sha256.base64(canonical))
            checked++
        }
        assertEquals(5, checked)
    }

    @Test
    fun fiscalDayCanonicalStringsAndHashes() {
        var checked = 0
        vectors.array("fiscalDays").objects { vector ->
            val counters = mutableListOf<FiscalCounter>()
            vector.array("counters").objects { counter ->
                counters += FiscalCounter(
                    type = FiscalCounterType.entries.single { it.specName == counter.text("fiscalCounterType") },
                    currency = counter.text("fiscalCounterCurrency"),
                    value = counter.decimal("fiscalCounterValue"),
                    taxId = counter.optInt("fiscalCounterTaxID"),
                    taxPercent = counter.optDecimal("fiscalCounterTaxPercent"),
                    moneyType = counter.optText("fiscalCounterMoneyType")?.let { name ->
                        MoneyType.entries.single { it.specName == name }
                    },
                )
            }
            val canonical = SignatureStrings.fiscalDay(
                deviceId = vector.int("deviceId"),
                fiscalDayNo = vector.int("fiscalDayNo"),
                fiscalDayDate = LocalDate.parse(vector.text("fiscalDayDate")),
                counters = counters,
            )
            val name = vector.text("name")
            assertEquals(name, vector.text("canonical"), canonical)
            assertEquals(name, vector.text("hash"), Sha256.base64(canonical))
            checked++
        }
        assertEquals(3, checked)
    }

    @Test
    fun qrCodes() {
        var checked = 0
        vectors.array("qr").objects { vector ->
            val qrData = ReceiptQr.qrData(vector.text("deviceSignatureBase64"))
            val url = ReceiptQr.url(
                qrUrl = vector.text("qrUrl"),
                deviceId = vector.int("deviceId"),
                receiptDate = LocalDateTime.parse(vector.text("receiptDate")).toLocalDate(),
                receiptGlobalNo = vector.int("receiptGlobalNo"),
                qrData = qrData,
            )
            // The vectors use lower-case hex; fzimra follows the upper-case §11 examples.
            assertEquals(vector.text("name"), vector.text("expected").lowercase(), url.lowercase())
            assertTrue(qrData == qrData.uppercase())
            checked++
        }
        assertEquals(2, checked)
    }

    @Test
    fun sha256() {
        var checked = 0
        vectors.array("sha256").objects { vector ->
            assertEquals(vector.text("sha256Base64"), Sha256.base64(vector.text("input")))
            checked++
        }
        assertEquals(4, checked)
    }

    private fun lineFrom(json: JsonObject, index: Int) = PreparedLine(
        lineNo = index + 1,
        type = ReceiptLineType.entries.single { it.specName == json.text("receiptLineType") },
        name = json.text("receiptLineName"),
        quantity = json.decimal("receiptLineQuantity"),
        unitPrice = null,
        total = json.decimal("receiptLineTotal"),
        taxId = json.int("taxID"),
        taxPercent = json.optDecimal("taxPercent"),
        hsCode = null,
    )

    private fun JsonObject.taxRows(key: String): List<ReceiptTax> {
        val rows = mutableListOf<ReceiptTax>()
        array(key).objects { rows += ReceiptTax(it.int("taxID"), it.optDecimal("taxPercent"), it.decimal("taxAmount"), it.decimal("salesAmountWithTax")) }
        return rows
    }

    /** Amounts compared by value: the vectors write 3 where fzimra keeps 3.00. */
    private fun normalised(taxes: List<ReceiptTax>) = taxes.map {
        listOf(it.taxId, it.taxPercent?.stripTrailingZeros(), it.taxAmount.stripTrailingZeros(), it.salesAmountWithTax.stripTrailingZeros())
    }

    @Test
    fun taxTables() {
        var checked = 0
        vectors.array("taxes").objects { vector ->
            val lines = vector.array("lines").mapIndexed { i, line -> lineFrom(line.jsonObject, i) }
            val computed = ReceiptBuilder.taxTable(lines, pricesIncludeTax = vector.text("taxInclusive").toBoolean())
            assertEquals(vector.text("name"), normalised(vector.taxRows("expected")), normalised(computed))
            checked++
        }
        assertEquals(4, checked)
    }

    @Test
    fun receiptTaxTablesFromTheirLines() {
        var checked = 0
        vectors.array("receipts").objects { vector ->
            val receipt = vector.getValue("receipt").jsonObject
            val lines = receipt.array("receiptLines").mapIndexed { i, line -> lineFrom(line.jsonObject, i) }
            val computed = ReceiptBuilder.taxTable(lines, receipt.text("receiptLinesTaxInclusive").toBoolean())
            assertEquals(vector.text("name"), normalised(receipt.taxRows("receiptTaxes")), normalised(computed))
            checked++
        }
        assertEquals(5, checked)
    }

    private val vectorPublicKey by lazy { SoftwareSigner.publicKeyFromPem(vectors.text("publicKeyPem")) }

    @Test
    fun vectorSignaturesVerify() {
        var checked = 0
        for (key in listOf("receipts", "fiscalDays")) {
            vectors.array(key).objects { vector ->
                assertTrue(vector.text("name"), FiscalSignature.verify(vectorPublicKey, vector.text("canonical"), vector.text("signature")))
                checked++
            }
        }
        assertEquals(8, checked)
    }

    @Test
    fun ourSignaturesWithTheVectorKeyVerify() {
        // ECDSA signatures are randomised, so a port verifies its own signatures rather than comparing bytes.
        val signer = SoftwareSigner.fromPem(vectors.text("privateKeyPem"), vectors.text("publicKeyPem"))
        vectors.array("receipts").objects { vector ->
            val signature = FiscalSignature.sign(signer, vector.text("canonical"))
            assertEquals(vector.text("hash"), signature.hash)
            assertTrue(FiscalSignature.verify(vectorPublicKey, vector.text("canonical"), signature.signature))
        }
    }
}
