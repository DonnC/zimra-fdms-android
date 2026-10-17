package org.donnc.fzimra.android

import android.content.Context
import org.donnc.fzimra.core.DeviceConfiguration
import org.donnc.fzimra.core.FiscalClock
import org.donnc.fzimra.core.FiscalDevice
import org.donnc.fzimra.core.FiscalStateStore
import org.donnc.fzimra.core.GlobalNumbering
import org.donnc.fzimra.core.ReceiptRecordStore
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
     */
    public fun device(
        context: Context,
        configuration: DeviceConfiguration,
        signer: Signer,
        stateStore: FiscalStateStore? = null,
        recordStore: ReceiptRecordStore? = null,
        clock: FiscalClock = ZimbabweClock(),
        numbering: GlobalNumbering = GlobalNumbering(),
    ): FiscalDevice {
        val app = context.applicationContext
        return FiscalDevice(
            configuration = configuration,
            signer = signer,
            stateStore = stateStore ?: DefaultStores.state(app),
            recordStore = recordStore ?: DefaultStores.records(app),
            clock = clock,
            numbering = numbering,
        )
    }
}
