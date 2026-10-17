package org.donnc.fzimra

/**
 * ZIMRA fiscalisation for Android: a thin SDK that turns an app into a
 * ZIMRA fiscal device (Fiscal Device Gateway API v7.2).
 *
 * One library, two packages:
 *
 * - [org.donnc.fzimra.core]: fiscal logic in plain Kotlin, with no Android
 *   imports, tested on the JVM: exact money, the strings ZIMRA signs, hashes,
 *   and the receipt QR and verification codes.
 * - [org.donnc.fzimra.android]: Android platform integrations.
 */
public object ZimraFdms {
    /** Version of the ZIMRA Fiscal Device Gateway API this SDK implements. */
    public const val SPEC_VERSION: String = "7.2"
}
