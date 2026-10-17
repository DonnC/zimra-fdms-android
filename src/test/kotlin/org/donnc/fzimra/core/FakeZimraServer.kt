package org.donnc.fzimra.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** A request as the fake server received it. */
internal data class ReceivedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val clientCertificate: X509Certificate?,
)

internal data class Answer(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/**
 * A minimal HTTPS server on localhost, with a certificate from [pki], that
 * asks for (but does not require) a client certificate. Tests only.
 */
internal class FakeZimraServer(pki: TestPki, private val handle: (ReceivedRequest) -> Answer) : Closeable {
    val requests = CopyOnWriteArrayList<ReceivedRequest>()
    private val pool = Executors.newCachedThreadPool()
    private val socket: SSLServerSocket

    init {
        val (key, certificate) = pki.server()
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("server", key, password, arrayOf(certificate, pki.ca))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        // Client certificates are checked by the handler, so any is accepted here.
        val acceptAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pki.ca)
        }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(acceptAll), null) }
        socket = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
        socket.wantClientAuth = true
        pool.execute(::accept)
    }

    val baseUrl: String get() = "https://localhost:${socket.localPort}"

    private fun accept() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept() as SSLSocket
            } catch (e: SocketException) {
                return
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: SSLSocket) {
        client.use { exchange(it) }
    }

    private fun exchange(client: SSLSocket) {
        try {
            val input = BufferedInputStream(client.inputStream)
            val requestLine = readLine(input) ?: return
            val (method, path) = requestLine.split(" ").let { it[0] to it[1] }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
            val certificate = try {
                client.session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (e: SSLPeerUnverifiedException) {
                null
            }
            val request = ReceivedRequest(method, path, headers, body.toString(Charsets.UTF_8), certificate)
            requests += request
            val answer = handle(request)
            val bytes = answer.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${answer.status} X\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                answer.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            client.outputStream.apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        } catch (e: java.io.IOException) {
            // the client went away, as in the timeout test
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.size() == 0) null else line.toString("ISO-8859-1")
            if (b == '\n'.code) return line.toString("ISO-8859-1").trimEnd('\r')
            line.write(b)
        }
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }
}
