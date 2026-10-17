package org.donnc.fzimra.core

import java.time.Instant
import java.time.LocalDateTime

/** In-memory [TextStorage]; [failWrites] simulates a full disk or a crash during a write. */
internal class InMemoryTextStorage : TextStorage {
    private val data = LinkedHashMap<String, String>()
    var failWrites = false
    var writes = 0

    override fun read(key: String): String? = data[key]

    override fun keys(): Set<String> = data.keys.toSet()

    override fun write(changes: Map<String, String?>) {
        if (failWrites) throw FiscalStoreException("simulated write failure")
        writes++
        for ((key, value) in changes) if (value == null) data.remove(key) else data[key] = value
    }
}

internal fun sampleReceipt(globalNo: Int, deviceId: Int = 7001, type: ReceiptType = ReceiptType.FISCAL_INVOICE): IssuedReceipt {
    val request = if (type == ReceiptType.CREDIT_NOTE) {
        invoice(
            listOf(bread(1)), type = type, notes = "Returned \"stale\"\nloaf",
            original = OriginalReceipt.ByDevice(deviceId, 3, 1),
        )
    } else {
        invoice(
            lines = listOf(bread(2), ReceiptLine.sale("Tuition", 1, d("20.00"), EXEMPT, "49019900")),
            payments = listOf(Payment.card(10), Payment.cash(50)),
            buyer = Buyer(
                "Mbare Hardware (Pvt) Ltd", "2000456781", "220045678", "Mbare Branch",
                BuyerContacts("+263 242 700 123", "accounts@mbare.co.zw"),
                BuyerAddress("Harare", "Harare", "Kwame Nkrumah Ave", "48", "CBD"),
            ),
        )
    }
    return IssuedReceipt(
        deviceId = deviceId,
        fiscalDayNo = 4,
        receiptCounter = globalNo,
        globalNo = globalNo,
        date = LocalDateTime.of(2026, 10, 17, 9, 0).plusMinutes(globalNo.toLong()),
        receipt = prepared(request),
        previousReceiptHash = if (globalNo == 1) null else "prev+hash/$globalNo=",
        signature = DeviceSignature("hash$globalNo=", "sig/$globalNo+=="),
        qrCode = "https://fdmstest.zimra.co.zw/00000070011710202600000000${globalNo}4C8BE27663330417",
        verificationCode = "4C8B-E276-6333-0417",
    )
}

internal fun sampleSnapshot(version: Long = 3, deviceId: Int = 7001) = FiscalSnapshot(
    version = version,
    state = DeviceState(
        deviceId = deviceId,
        lastGlobalNo = 2,
        lastReceiptDate = LocalDateTime.of(2026, 10, 17, 9, 2),
        lastFiscalDayNo = 4,
        currentDay = FiscalDayState(
            fiscalDayNo = 4,
            openedAt = LocalDateTime.of(2026, 10, 17, 8, 0),
            status = FiscalDayStatus.OPENED,
            receiptCounter = 2,
            lastReceiptHash = "abc+/=",
            counters = listOf(
                FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("2.30"), STANDARD, d("15")),
                FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", d("20.00"), EXEMPT, null),
                FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", d("22.30"), moneyType = MoneyType.CASH),
            ),
        ),
        closingDays = listOf(
            FiscalDayState(
                fiscalDayNo = 3,
                openedAt = LocalDateTime.of(2026, 10, 16, 8, 0),
                status = FiscalDayStatus.CLOSE_REQUESTED,
                receiptCounter = 9,
                lastReceiptHash = "day3+last=",
                counters = listOf(FiscalCounter(FiscalCounterType.SALE_BY_TAX, "ZWG", d("120.50"), STANDARD, d("15"))),
                closeReason = DayCloseReason.MAX_DAY_LENGTH,
            ),
        ),
    ),
    outbox = listOf(sampleReceipt(1, deviceId), sampleReceipt(2, deviceId, ReceiptType.CREDIT_NOTE)),
    seal = "c2VhbC1vZi1zbmFwc2hvdA==",
)

internal fun sampleRecord(
    globalNo: Int,
    status: ReceiptSyncStatus = ReceiptSyncStatus.SUBMITTED,
    updatedAt: Instant = Instant.parse("2026-10-17T09:00:00Z"),
    deviceId: Int = 7001,
) = ReceiptRecord(
    receipt = sampleReceipt(globalNo, deviceId),
    status = status,
    zimraReceiptId = if (status == ReceiptSyncStatus.SUBMITTED) 9_000_000_000L + globalNo else null,
    serverDate = if (status == ReceiptSyncStatus.SUBMITTED) LocalDateTime.of(2026, 10, 17, 9, 0, 30) else null,
    validations = if (status == ReceiptSyncStatus.SUBMITTED) listOf(ZimraValidation("RCPT031", "Yellow", null)) else emptyList(),
    updatedAt = updatedAt,
    seal = "c2VhbC1vZi1yZWNvcmQ=",
)
