package org.donnc.fzimra.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * How the device identifies itself to ZIMRA.
 *
 * @property serialNo the serial number the device was registered with.
 * @property modelName the device model name, exactly as registered with ZIMRA.
 * @property modelVersion the device model version, exactly as registered with ZIMRA.
 */
public data class DeviceIdentity(
    val deviceId: Int,
    val serialNo: String,
    val modelName: String,
    val modelVersion: String,
) {
    init {
        if (deviceId <= 0) throw FiscalValueException("deviceID must be positive, got $deviceId")
        if (serialNo.isBlank() || serialNo.length > 20) throw FiscalValueException("the serial number must be 1 to 20 characters")
        if (modelName.isBlank() || modelVersion.isBlank()) throw FiscalValueException("the model name and version are required")
    }
}

/** The taxpayer's branch the device belongs to. */
public data class BranchDetails(
    val name: String,
    val address: Address?,
    val contacts: Contacts?,
)

/**
 * Who ZIMRA says the device belongs to. Show it to the user to confirm before
 * registering.
 *
 * @property vatNumber `null` when the taxpayer is not registered for VAT.
 */
public data class TaxpayerInformation(
    val operationId: String?,
    val taxpayerName: String,
    val taxpayerTin: String,
    val vatNumber: String?,
    val branch: BranchDetails,
)

/** A device certificate issued by ZIMRA. */
public data class IssuedCertificate(
    val operationId: String?,
    /** The certificate, in PEM form. */
    val certificatePem: String,
)

/** How the device may submit receipts. */
public enum class OperatingMode(public val zimraName: String) {
    ONLINE("Online"),
    OFFLINE("Offline"),
}

/**
 * The device's configuration held by ZIMRA. Fetch it before each new fiscal
 * day: taxes, VAT registration and limits can change.
 *
 * @property dayMaxHours the longest a fiscal day may stay open.
 * @property dayEndNotificationHours how long before [dayMaxHours] the user
 *   must be warned that the day is ending.
 * @property taxes the taxes the device may apply, now or in future.
 * @property certificateValidTill the last day the device certificate is valid.
 * @property qrUrl the URL receipt QR codes start with.
 */
public data class ZimraConfiguration(
    val operationId: String?,
    val taxpayerName: String,
    val taxpayerTin: String,
    val vatNumber: String?,
    val serialNo: String?,
    val branch: BranchDetails,
    val operatingMode: OperatingMode,
    val dayMaxHours: Int,
    val dayEndNotificationHours: Int,
    val taxes: List<TaxDefinition>,
    val certificateValidTill: LocalDate?,
    val qrUrl: String,
) {
    /** Whether the taxpayer is registered for VAT. */
    val isVatPayer: Boolean get() = !vatNumber.isNullOrBlank()

    /** The configuration a `FiscalDevice` issues with, for [deviceId]. */
    public fun toDeviceConfiguration(deviceId: Int): DeviceConfiguration =
        DeviceConfiguration(deviceId, qrUrl, taxes, isVatPayer, dayMaxHours)
}

/** A fiscal day's status at ZIMRA. */
public enum class ZimraDayStatus(public val zimraName: String) {
    CLOSED("FiscalDayClosed"),
    OPENED("FiscalDayOpened"),
    CLOSE_INITIATED("FiscalDayCloseInitiated"),
    CLOSE_FAILED("FiscalDayCloseFailed"),
}

/**
 * What ZIMRA knows of the device's fiscal days and receipts.
 *
 * @property lastFiscalDayNo the open day, or the last closed one; `null`
 *   before the device's first day.
 * @property lastReceiptGlobalNo the global number of the last receipt ZIMRA
 *   received; `null` before the first. ZIMRA reports the latest-dated
 *   receipt, which is not always the highest number.
 * @property closingErrorCode why the last close failed, when [dayStatus] is
 *   [ZimraDayStatus.CLOSE_FAILED].
 * @property dayClosed when the last day was closed, when [dayStatus] is
 *   [ZimraDayStatus.CLOSED].
 */
public data class ZimraStatus(
    val operationId: String?,
    val dayStatus: ZimraDayStatus,
    val lastFiscalDayNo: Int?,
    val lastReceiptGlobalNo: Int?,
    val closingErrorCode: String?,
    val dayClosed: LocalDateTime?,
)

/**
 * ZIMRA's signing certificate, for checking ZIMRA's signatures on receipts
 * and fiscal days.
 *
 * @property chainPem the certificate first, then the rest of its chain, in PEM form.
 * @property validTill when the signing certificate expires.
 */
public data class ServerCertificate(
    val chainPem: List<String>,
    val validTill: Instant?,
)
