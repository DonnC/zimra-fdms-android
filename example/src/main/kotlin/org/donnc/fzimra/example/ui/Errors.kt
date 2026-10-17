package org.donnc.fzimra.example.ui

import org.donnc.fzimra.android.KeystoreUnavailableException
import org.donnc.fzimra.core.DeviceCertificateException
import org.donnc.fzimra.core.FdmsErrors
import org.donnc.fzimra.core.FdmsException
import org.donnc.fzimra.core.FdmsNetworkException
import org.donnc.fzimra.core.FdmsProtocolException
import org.donnc.fzimra.core.FiscalIntegrityException

/** A failure, said so a cashier or supervisor can act on it. */
fun Throwable.friendly(): String = when (this) {
    is FdmsNetworkException -> "ZIMRA could not be reached. Check the connection and try again. Nothing was lost."
    is FdmsException -> when {
        isAuthenticationFailure -> "ZIMRA refused the device certificate. It may have expired or been revoked: renew it or register the device again."
        errorCode != null -> (FdmsErrors.message(errorCode!!) ?: message.orEmpty()) + " ($errorCode" + (operationId?.let { ", operation $it" } ?: "") + ")"
        else -> message ?: "ZIMRA refused the request."
    }
    is FdmsProtocolException -> "ZIMRA's answer could not be understood: ${message.orEmpty()}"
    is DeviceCertificateException -> "The certificate is not this device's: ${message.orEmpty()}"
    is KeystoreUnavailableException -> "The phone's secure key store is unavailable: ${message.orEmpty()}"
    is FiscalIntegrityException -> "The fiscal data on this phone failed its integrity checks: ${problems.joinToString("; ")}"
    else -> message ?: this::class.java.simpleName
}
