package org.donnc.fzimra.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** ZIMRA's Fiscal Device Gateway environments. */
public enum class FdmsEnvironment(public val baseUrl: String) {
    TEST("https://fdmsapitest.zimra.co.zw"),
    PRODUCTION("https://fdmsapi.zimra.co.zw"),
}

/**
 * One HTTP request to the Fiscal Device Gateway.
 *
 * @property deviceAuthenticated whether the request must present the device
 *   certificate (mutual TLS). Only taxpayer verification, registration and
 *   the server certificate are public.
 */
public data class FdmsRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val deviceAuthenticated: Boolean,
)

/**
 * The gateway's answer.
 *
 * @property headers response headers, with lower-case names.
 */
public data class FdmsResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** The gateway could not be reached, or did not answer in time. Safe to retry later. */
public class FdmsNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sends requests to the Fiscal Device Gateway. fzimra ships [HttpsTransport];
 * provide your own to route requests another way (for example through a proxy
 * your app already configures).
 *
 * Calls block: make them off the main thread.
 */
public fun interface FdmsTransport {
    /**
     * Performs [request].
     *
     * @throws FdmsNetworkException if there is no answer.
     */
    public fun execute(request: FdmsRequest): FdmsResponse
}

/**
 * The default transport: HTTPS with the platform's TLS stack and no extra
 * dependencies.
 *
 * Device-authenticated requests present the device certificate through
 * [deviceKeys]; on Android, `KeystoreSigner.keyManager()` provides it from the
 * Keystore without the private key ever leaving it.
 *
 * @param deviceKeys the device's key and certificate, for mutual TLS; `null`
 *   before the device is registered (public requests only).
 * @param trust which servers to trust; the platform's trusted authorities by
 *   default. Pass your own only to pin or to reach a test server.
 * @param timeout connect and read timeout. ZIMRA answers within 30 seconds.
 */
public class HttpsTransport(
    deviceKeys: X509KeyManager? = null,
    trust: X509TrustManager? = null,
    private val timeout: Duration = Duration.ofSeconds(30),
) : FdmsTransport {
    private val trustManager: X509TrustManager = trust ?: platformTrust()
    private val publicSockets: SSLSocketFactory = sockets(null)
    private val deviceSockets: SSLSocketFactory? = deviceKeys?.let { sockets(arrayOf(it)) }

    override fun execute(request: FdmsRequest): FdmsResponse {
        val sockets = if (request.deviceAuthenticated) {
            deviceSockets ?: throw IllegalStateException("this request needs the device certificate; give HttpsTransport the device keys")
        } else {
            publicSockets
        }
        val connection = try {
            URL(request.url).openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("only HTTPS is allowed: ${request.url}")
        } catch (e: IOException) {
            throw FdmsNetworkException("cannot reach ${request.url}: ${e.message}", e)
        }
        return try {
            connection.sslSocketFactory = sockets
            connection.connectTimeout = timeout.toMillis().toInt()
            connection.readTimeout = timeout.toMillis().toInt()
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = request.method
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            } else if (request.method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val status = connection.responseCode
            val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.joinToString(",") }
            FdmsResponse(status, headers, body)
        } catch (e: IOException) {
            throw FdmsNetworkException("no answer from ${request.url}: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun sockets(keys: Array<KeyManager>?): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(keys, arrayOf(trustManager), null) }.socketFactory

    private companion object {
        fun platformTrust(): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
