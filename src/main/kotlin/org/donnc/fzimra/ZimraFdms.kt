package org.donnc.fzimra

/**
 * ZIMRA fiscalisation for Android: a thin SDK that turns an app into a
 * ZIMRA fiscal device (Fiscal Device Gateway API v7.2).
 *
 * One library, two packages:
 *
 * - [org.donnc.fzimra.core]: fiscal logic in plain Kotlin, with no Android
 *   imports, tested on the JVM: receipts, issuing, fiscal days, storage,
 *   signatures, certificate requests, and the receipt QR and verification
 *   codes.
 * - [org.donnc.fzimra.android]: the device key in Android Keystore, fzimra's
 *   own storage, and [org.donnc.fzimra.android.Fzimra] to set up a device.
 */
public object ZimraFdms {
    /** Version of the ZIMRA Fiscal Device Gateway API this SDK implements. */
    public const val SPEC_VERSION: String = "7.2"
}
