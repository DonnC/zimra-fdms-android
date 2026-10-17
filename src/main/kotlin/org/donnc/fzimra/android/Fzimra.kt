package org.donnc.fzimra.android

import android.content.Context
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.FdmsClient
import org.donnc.fzimra.core.FdmsEnvironment
import org.donnc.fzimra.core.FdmsObserver
import org.donnc.fzimra.core.FiscalTerminal
import org.donnc.fzimra.core.Gateway
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

    /**
     * Removes what fzimra keeps for [deviceId] in its own storage: the device
     * state, the receipt records and the sealing key. Use it when the device is
     * taken out of service. It cannot be undone, and the device key (the
     * `KeystoreSigner`) is left for you to delete.
     *
     * @param discardUnsentReceipts delete even if receipts have not reached
     *   ZIMRA yet. They are then lost: ZIMRA will never receive them.
     * @throws IllegalStateException if receipts are unsent and
     *   [discardUnsentReceipts] is `false`.
     */
    public fun deleteDeviceData(context: Context, deviceId: Int, discardUnsentReceipts: Boolean = false) {
        val app = context.applicationContext
        val unsent = DefaultStores.unsentReceipts(app, deviceId)
        check(unsent == 0 || discardUnsentReceipts) {
            "device $deviceId has $unsent receipt(s) ZIMRA has not received; sync them first"
        }
        FzimraBackground.cancel(app, deviceId)
        DefaultStores.delete(app, deviceId)
        KeystoreSealer.delete(sealAlias(deviceId))
    }

    private val terminals = java.util.concurrent.ConcurrentHashMap<String, FiscalTerminal>()

    /**
     * The fiscal terminal for [device] on [gateway]: everything a point of sale
     * needs, in one object. Call it in `Application.onCreate`, so background
     * sync can reach ZIMRA even when no screen is open; later calls return the
     * same terminal.
     *
     * ```kotlin
     * val terminal = Fzimra.terminal(context, DeviceIdentity(12345, "SN-001", "MyPOS", "1.0"), ZimraGateway.TEST)
     * terminal.register(activationKey)   // once
     * terminal.sell(lines)
     * ```
     *
     * The terminal keeps its key in Android Keystore, its data in the app's
     * private no-backup storage, separately for each gateway, and sends
     * receipts to ZIMRA in the background as soon as the network allows.
     * While the app runs it reports the device online and renews the
     * certificate before it expires.
     *
     * @param observer sees every exchange with the gateway, for example to log it.
     * @param numbering when the global receipt number starts again at 1.
     */
    public fun terminal(
        context: Context,
        device: DeviceIdentity,
        gateway: Gateway = ZimraGateway.TEST,
        observer: FdmsObserver? = null,
        numbering: GlobalNumbering = GlobalNumbering(),
    ): FiscalTerminal {
        val app = context.applicationContext
        val namespace = gateway.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val key = "$namespace/${device.deviceId}"
        terminals[key]?.let { return it }
        synchronized(terminals) {
            terminals[key]?.let { return it }
            val sealAlias = "fzimra.seal.$namespace.${device.deviceId}"
            val terminal = FiscalTerminal(
                identity = device,
                gateway = gateway,
                keys = KeystoreKeyStore("fzimra.key.$namespace.${device.deviceId}", allowSoftware = gateway.allowsSoftwareKeys),
                stateStore = DefaultStores.state(app, namespace),
                recordStore = DefaultStores.records(app, namespace),
                terminalStore = DefaultStores.terminal(app, namespace, device.deviceId),
                sealer = { signer ->
                    KeystoreSealer.getOrCreate(sealAlias, requireHardware = (signer as? KeystoreSigner)?.securityLevel != KeySecurityLevel.SOFTWARE)
                },
                observer = observer,
                numbering = numbering,
            )
            terminal.onUnregister = {
                FzimraBackground.cancel(app, device.deviceId)
                DefaultStores.delete(app, device.deviceId, namespace)
                KeystoreSealer.delete(sealAlias)
            }
            terminal.addWorkListener { FzimraBackground.requestSync(app, device.deviceId) }
            FzimraBackground.attach(device.deviceId, terminal)
            Upkeep.start(terminal)
            terminals[key] = terminal
            // Receipts left unsent, for example before the phone restarted, are sent now.
            if (terminal.isRegistered && runCatching { terminal.status().pendingReceipts }.getOrDefault(0) > 0) {
                FzimraBackground.requestSync(app, device.deviceId)
            }
            return terminal
        }
    }

    /** The Keystore alias of the default sealing key for [deviceId]. */
    public fun sealAlias(deviceId: Int): String = "fzimra.seal.$deviceId"
}
