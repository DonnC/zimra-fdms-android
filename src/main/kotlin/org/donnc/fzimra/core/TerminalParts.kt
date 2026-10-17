package org.donnc.fzimra.core

import java.security.cert.X509Certificate

/**
 * A device key that can hold the certificate ZIMRA issued for it.
 * On Android, `KeystoreSigner` is one.
 */
public interface DeviceKey : Signer {
    /** The installed ZIMRA certificate, or `null` before registration. */
    public val certificate: X509Certificate?

    /**
     * Checks and keeps the certificate ZIMRA issued for this key.
     *
     * @throws DeviceCertificateException if it is not this key's, or not this device's.
     */
    public fun installCertificate(certificatePem: String, identity: DeviceIdentity)
}

/** Where a terminal's device key lives. */
public interface DeviceKeyStore {
    /** The key, or `null` if none was created yet. */
    public fun load(): DeviceKey?

    /** Creates the key, replacing nothing: there must not be one yet. */
    public fun create(): DeviceKey

    /** Deletes the key. Irreversible: the device must be registered again. */
    public fun delete()
}

/**
 * Where a terminal sends its fiscal data: ZIMRA's test or production
 * environment, or a simulator.
 */
public interface Gateway {
    /** A short name for people, such as "ZIMRA test". */
    public val name: String

    /** The gateway's address, used by `FdmsClient`. */
    public val baseUrl: String

    /**
     * Whether a device key held in software is acceptable, as on an emulator.
     * Never for production.
     */
    public val allowsSoftwareKeys: Boolean

    /** How requests reach the gateway, authenticating as [key] once it has a certificate. */
    public fun transport(key: DeviceKey?): FdmsTransport
}

/** Sees every exchange with the gateway, for example to show or log it. */
public fun interface FdmsObserver {
    /**
     * @param response the answer, or `null` if there was none.
     * @param error the network failure, if there was no answer.
     */
    public fun onExchange(request: FdmsRequest, response: FdmsResponse?, error: Exception?, millis: Long)
}

/** Keeps a terminal's own small state (configuration, invoice numbers) as text. */
public interface TerminalStore {
    public fun load(): String?
    public fun save(text: String)
    public fun delete()
}

/** The taxes ZIMRA configured for the taxpayer, with the usual ones by name. */
public class Taxes internal constructor(
    /** Every tax, as ZIMRA sent them. */
    public val all: List<TaxDefinition>,
) {
    /** The standard VAT rate: the highest positive rate. */
    public val standard: TaxDefinition get() = find("standard VAT") { list -> list.filter { (it.percent?.signum() ?: 0) > 0 }.maxByOrNull { it.percent!! } }

    /** The zero rate. */
    public val zeroRated: TaxDefinition get() = find("zero-rated") { list -> list.firstOrNull { it.percent?.signum() == 0 } }

    /** The exempt tax (no rate). */
    public val exempt: TaxDefinition get() = find("exempt") { list -> list.firstOrNull { it.percent == null } }

    /** The tax with ZIMRA's [taxId]. */
    public fun byId(taxId: Int): TaxDefinition = find("ID $taxId") { list -> list.firstOrNull { it.taxId == taxId } }

    /** The tax at [percent], or the exempt tax for `null`. */
    public fun at(percent: Number?): TaxDefinition = if (percent == null) exempt else {
        val rate = java.math.BigDecimal(percent.toString())
        find("$rate%") { list -> list.firstOrNull { it.percent != null && it.percent.compareTo(rate) == 0 } }
    }

    private fun find(what: String, pick: (List<TaxDefinition>) -> TaxDefinition?): TaxDefinition =
        pick(all) ?: throw FiscalValueException("ZIMRA configured no $what tax for this taxpayer; its taxes are ${all.map { it.name }}")
}
