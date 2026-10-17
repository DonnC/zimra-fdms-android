package org.donnc.fzimra

/**
 * ZIMRA fiscalisation for Android: a thin SDK that turns an app into a
 * ZIMRA fiscal device (Fiscal Device Gateway API v7.2).
 *
 * One library, two packages:
 *
 * - [org.donnc.fzimra.core]: the fiscal kernel in plain Kotlin, with
 *   no Android imports. It holds the protocol, the canonical strings, receipt
 *   rules, counters, the fiscal-day state machine and the contracts the host
 *   app implements. It is tested on the JVM.
 * - [org.donnc.fzimra.android]: what only Android can provide, such
 *   as the device key in Android Keystore.
 *
 * The SDK assumes nothing about the host app: storage, networking and
 * scheduling are supplied by the app through the contracts in `core`.
 */
public object ZimraFdms {
    /** Version of the ZIMRA Fiscal Device Gateway API this SDK implements. */
    public const val SPEC_VERSION: String = "7.2"
}
