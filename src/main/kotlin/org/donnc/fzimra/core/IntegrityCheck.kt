package org.donnc.fzimra.core

import java.security.PublicKey

/**
 * Checks that a snapshot's unsent receipts are genuine and consistent: each
 * one's hash and signature, the links between consecutive receipts, and their
 * agreement with the device state.
 */
internal object IntegrityCheck {
    fun problems(snapshot: FiscalSnapshot, deviceId: Int, publicKey: PublicKey): List<String> {
        val problems = mutableListOf<String>()
        val state = snapshot.state
        if (state.deviceId != deviceId) problems += "the state belongs to device ${state.deviceId}, not $deviceId"

        snapshot.outbox.forEach { r ->
            val name = "receipt ${r.fiscalDayNo}/${r.globalNo}"
            if (r.deviceId != deviceId) problems += "$name belongs to device ${r.deviceId}"
            val canonical = SignatureStrings.receipt(
                r.deviceId, r.receipt.type, r.receipt.currency, r.globalNo, r.date, r.receipt.total, r.receipt.taxes,
                r.previousReceiptHash,
            )
            if (Sha256.base64(canonical) != r.signature.hash) {
                problems += "$name does not match its hash"
            } else if (!verifies(publicKey, canonical, r.signature.signature)) {
                problems += "$name is not signed by this device's key"
            }
        }

        snapshot.outbox.zipWithNext().forEach { (a, b) ->
            val name = "receipt ${b.fiscalDayNo}/${b.globalNo}"
            if (b.fiscalDayNo == a.fiscalDayNo) {
                if (b.receiptCounter != a.receiptCounter + 1) problems += "$name breaks the day's receipt counter"
                if (b.previousReceiptHash != a.signature.hash) problems += "$name is not chained to the previous receipt"
                if (b.globalNo != a.globalNo + 1) problems += "$name breaks the global numbering"
            } else {
                if (b.fiscalDayNo < a.fiscalDayNo) problems += "$name is out of fiscal day order"
                if (b.receiptCounter != 1 || b.previousReceiptHash != null) problems += "$name does not start its fiscal day"
                if (b.globalNo != a.globalNo + 1 && b.globalNo != 1) problems += "$name breaks the global numbering"
            }
            if (!b.date.isAfter(a.date)) problems += "$name is not dated after the previous receipt"
        }

        val last = snapshot.outbox.lastOrNull()
        if (last != null) {
            if (state.lastReceiptDate != last.date) problems += "the device's last receipt date does not match its last receipt"
            val days = listOfNotNull(state.currentDay) + state.closingDays
            snapshot.outbox.groupBy { it.fiscalDayNo }.forEach { (dayNo, receipts) ->
                val day = days.firstOrNull { it.fiscalDayNo == dayNo } ?: return@forEach // closed and dropped
                val end = receipts.last()
                if (day.receiptCounter != end.receiptCounter || day.lastReceiptHash != end.signature.hash) {
                    problems += "fiscal day $dayNo does not end with its last receipt"
                }
            }
            if (state.currentDay?.fiscalDayNo == last.fiscalDayNo && state.lastGlobalNo != last.globalNo) {
                problems += "the device's last global number does not match its last receipt"
            }
        }
        return problems
    }

    private fun verifies(publicKey: PublicKey, canonical: String, signature: String): Boolean = try {
        FiscalSignature.verify(publicKey, canonical, signature)
    } catch (e: RuntimeException) {
        false
    }
}
