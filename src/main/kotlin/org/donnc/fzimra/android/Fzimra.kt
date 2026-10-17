package org.donnc.fzimra.android

import android.content.Context
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.FdmsClient
import org.donnc.fzimra.core.FdmsEnvironment
import org.donnc.fzimra.core.FiscalClock
import org.donnc.fzimra.core.FiscalDevice
import org.donnc.fzimra.core.FiscalStateStore
import org.donnc.fzimra.core.HttpsTransport
import org.donnc.fzimra.core.GlobalNumbering
import org.donnc.fzimra.core.ReceiptRecordStore
import org.donnc.fzimra.core.Sealer
import org.donnc.fzimra.core.Signer
import org.donnc.fzimra.core.ZimbabweClock
import javax.net.ssl.X509TrustManager

/** Entry point for using fzimra on Android. */
public object Fzimra {
    /**
     * A fiscal device for [configuration], signing with [signer].
     *
     * The stores default to fzimra's own private storage; pass your own to keep
     * device state or receipt records elsewhere. [numbering] chooses when the
     * global receipt number starts again at 1.
     *
     * Stored data is sealed against tampering with a Keystore key under
     * `fzimra.seal.<deviceId>`, unless you pass your own [sealer]. That key is
     * held at least as securely as [signer]'s: in hardware, unless the device
     * key itself is a software key (as on an emulator).
     *
     * With [backgroundSync], each sale or close request asks `FzimraBackground`
     * for a sync run, which takes effect once `FzimraBackground.install` has
     * been called.
     */
    public fun device(
        context: Context,
        configuration: DeviceConfiguration,
        signer: Signer,
        stateStore: FiscalStateStore? = null,
        recordStore: ReceiptRecordStore? = null,
        sealer: Sealer? = null,
        clock: FiscalClock = ZimbabweClock(),
        numbering: GlobalNumbering = GlobalNumbering(),
        backgroundSync: Boolean = true,
    ): FiscalDevice {
        val app = context.applicationContext
        val device = FiscalDevice(
            configuration = configuration,
            signer = signer,
            stateStore = stateStore ?: DefaultStores.state(app),
            recordStore = recordStore ?: DefaultStores.records(app),
            sealer = sealer ?: KeystoreSealer.getOrCreate(
                sealAlias(configuration.deviceId),
                requireHardware = (signer as? KeystoreSigner)?.securityLevel != KeySecurityLevel.SOFTWARE,
            ),
            clock = clock,
            numbering = numbering,
        )
        if (backgroundSync) {
            device.addWorkListener { FzimraBackground.requestSync(app, configuration.deviceId) }
        }
        return device
    }

    /**
     * A client for ZIMRA's Fiscal Device Gateway that authenticates as the
     * device with [signer]'s key and installed certificate.
     *
     * Before registration the client can verify the taxpayer and register;
     * once the certificate is installed (`KeystoreSigner.installCertificate`)
     * the same client can use every other request.
     *
     * @param clock kept in step with ZIMRA's clock; pass the clock your
     *   `FiscalDevice` uses so receipts are never dated ahead of ZIMRA.
     * @param trust which servers to trust; the platform's authorities by default.
     */
    public fun client(
        identity: DeviceIdentity,
        signer: KeystoreSigner,
        environment: FdmsEnvironment = FdmsEnvironment.TEST,
        clock: ZimbabweClock? = null,
        trust: X509TrustManager? = null,
    ): FdmsClient = FdmsClient(
        identity = identity,
        transport = HttpsTransport(deviceKeys = signer.keyManager(), trust = trust),
        baseUrl = environment.baseUrl,
        clock = clock,
    )

    /** The Keystore alias of the default sealing key for [deviceId]. */
    public fun sealAlias(deviceId: Int): String = "fzimra.seal.$deviceId"
}
