package org.donnc.fzimra.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** SHA-256 as used by ZIMRA: Base64 of the digest of the UTF-8 text (spec §13.1). */
public object Sha256 {
    /** The Base64-encoded SHA-256 digest of [text] encoded as UTF-8. */
    public fun base64(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

/**
 * The receipt QR code and verification code (spec §11, §10.3 fields 45–48).
 *
 * The QR code encodes `qrUrl/` ‖ deviceID (10 digits) ‖ receiptDate
 * (`ddMMyyyy`) ‖ receiptGlobalNo (10 digits) ‖ receiptQrData. The same
 * receiptQrData, in four groups of four characters, is printed as the
 * receipt's verification code.
 */
public object ReceiptQr {
    private val QR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT)
    private val QR_DATA = Regex("^[0-9A-Fa-f]{16}$")
    private const val MAX_10_DIGITS = 9_999_999_999L

    /**
     * receiptQrData: the first 16 hexadecimal characters of the MD5 digest of
     * the raw receipt device signature, in upper case as in the §11 examples.
     *
     * @param deviceSignatureBase64 the receipt's device signature, Base64-encoded.
     * @throws FiscalValueException if the signature is not valid Base64.
     */
    public fun qrData(deviceSignatureBase64: String): String {
        val signature = try {
            Base64.getDecoder().decode(deviceSignatureBase64)
        } catch (e: IllegalArgumentException) {
            throw FiscalValueException("device signature is not valid Base64")
        }
        if (signature.isEmpty()) throw FiscalValueException("device signature is empty")
        val md5 = MessageDigest.getInstance("MD5").digest(signature)
        return md5.joinToString("") { "%02X".format(Locale.ROOT, it) }.substring(0, 16)
    }

    /**
     * The content of the receipt QR code.
     *
     * @param qrUrl the verification URL from the device configuration (getConfig).
     * @param receiptDate the receipt date; only the day is encoded.
     */
    public fun url(qrUrl: String, deviceId: Int, receiptDate: LocalDate, receiptGlobalNo: Int, qrData: String): String {
        if (qrUrl.isBlank()) throw FiscalValueException("qrUrl is blank")
        requireTenDigits(deviceId.toLong(), "deviceID")
        requireTenDigits(receiptGlobalNo.toLong(), "receiptGlobalNo")
        requireQrData(qrData)
        val base = if (qrUrl.endsWith("/")) qrUrl else "$qrUrl/"
        return base +
            deviceId.toString().padStart(10, '0') +
            QR_DATE.format(receiptDate) +
            receiptGlobalNo.toString().padStart(10, '0') +
            qrData
    }

    /** The verification code printed on the receipt: `4C8BE27663330417` → `4C8B-E276-6333-0417`. */
    public fun verificationCode(qrData: String): String {
        requireQrData(qrData)
        return qrData.uppercase(Locale.ROOT).chunked(4).joinToString("-")
    }

    private fun requireQrData(qrData: String) {
        if (!QR_DATA.matches(qrData)) throw FiscalValueException("receiptQrData must be 16 hexadecimal characters")
    }

    private fun requireTenDigits(value: Long, field: String) {
        if (value <= 0 || value > MAX_10_DIGITS) throw FiscalValueException("$field must be 1 to 10 digits, got $value")
    }
}
