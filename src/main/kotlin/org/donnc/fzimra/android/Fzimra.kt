package org.donnc.fzimra.android

import android.content.Context
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.FiscalClock
import org.donnc.fzimra.core.FiscalDevice
import org.donnc.fzimra.core.FiscalStateStore
import org.donnc.fzimra.core.GlobalNumbering
import org.donnc.fzimra.core.ReceiptRecordStore
import org.donnc.fzimra.core.Sealer
import org.donnc.fzimra.core.Signer
import org.donnc.fzimra.core.ZimbabweClock

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
    ): FiscalDevice {
        val app = context.applicationContext
        return FiscalDevice(
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
    }

    /** The Keystore alias of the default sealing key for [deviceId]. */
    public fun sealAlias(deviceId: Int): String = "fzimra.seal.$deviceId"
}
