package org.donnc.fzimra.core

/**
 * The gateway refused a request (HTTP 4xx or 5xx).
 *
 * @property status the HTTP status.
 * @property errorCode ZIMRA's error code, such as `DEV01`, when it gave one.
 * @property operationId ZIMRA's ID for the request; quote it to ZIMRA support.
 */
public class FdmsException(
    public val status: Int,
    public val errorCode: String?,
    public val title: String?,
    public val detail: String?,
    public val operationId: String?,
) : Exception(describe(status, errorCode, title, detail)) {
    /** ZIMRA asks for the request to be repeated later: a server or gateway problem. */
    public val isRetryable: Boolean get() = status >= 500 || status == 408 || status == 429

    /**
     * The device certificate was refused: not issued by ZIMRA, revoked,
     * expired, or issued to another device.
     */
    public val isAuthenticationFailure: Boolean get() = status == 401

    private companion object {
        fun describe(status: Int, code: String?, title: String?, detail: String?): String {
            val text = detail ?: title ?: code?.let(FdmsErrors::message)
            return "ZIMRA answered HTTP $status" + (code?.let { " $it" } ?: "") + (text?.let { ": $it" } ?: "")
        }
    }
}

/** ZIMRA's answer could not be understood. */
public class FdmsProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** ZIMRA's own descriptions of its request error codes. */
public object FdmsErrors {
    private val MESSAGES = mapOf(
        "DEV01" to "Device not found or not active",
        "DEV02" to "Activation key is incorrect",
        "DEV03" to "Certificate request is invalid",
        "DEV04" to "Device model is blacklisted",
        "DEV05" to "Taxpayer is not active",
        "DEV06" to "Device model and version is not registered in FDMS",
        "DEV07" to "Username is already used",
        "DEV08" to "Provided user information is not correct",
        "DEV09" to "Security code is not valid",
        "DEV10" to "Password is not valid",
        "DEV11" to "User credentials are incorrect",
        "DEV12" to "Token is not valid",
        "DEV13" to "User is not confirmed",
        "DEV14" to "Email or phone number is not valid or doesn't exist",
        "DEV15" to "Email or phone number already confirmed",
        "RCPT01" to "Submitting receipt is not allowed. Fiscal day is closed or fiscal day close initiated",
        "RCPT02" to "Submit receipt failed. The receipt structure invalid or field requirements not satisfied",
        "FISC01" to "Open day is not allowed",
        "FISC03" to "Closing day is not allowed. Close day is in progress",
        "FISC04" to "Closing day is not allowed. Fiscal day not opened",
        "FILE01" to "File is too big. Allowed file size: 3 MB",
        "FILE02" to "File structure invalid or field requirements not satisfied",
        "FILE03" to "Device operating mode is not Offline",
        "FILE04" to "File sent for already closed day or closing in progress",
        "FILE05" to "Device id in input parameters and file header are different",
    )

    /** ZIMRA's description of [code], or `null` for a code it does not document. */
    public fun message(code: String): String? = MESSAGES[code.uppercase()]
}
