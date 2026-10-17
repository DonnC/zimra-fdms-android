package org.donnc.fzimra.core

import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.time.Duration

/** The client over real TLS against a fake gateway that checks device certificates. */
class FdmsTlsTest {
    private val pki = TestPki()
    private val identity = DeviceIdentity(4321, "SN-TLS", "fzimra-test", "1.0")
    private val signer = SoftwareSigner.ecP256()

    private val server = FakeZimraServer(pki) { request ->
        val device = request.path.startsWith("/Device/")
        if (device && !issuedToThisDevice(request)) return@FakeZimraServer Answer(401, """{"title":"Unauthorized","status":401}""")
        when (request.path.substringAfterLast('/')) {
            "VerifyTaxpayerInformation" -> Answer(200, """{"operationID":"op-v","taxPayerName":"Acme","taxPayerTIN":"2000000000","deviceBranchName":"Harare"}""")
            "RegisterDevice" -> Answer(200, """{"operationID":"op-r","certificate":${json(issue(request.body))}}""")
            "GetConfig" -> Answer(
                200,
                """{"taxPayerName":"Acme","taxPayerTIN":"2000000000","deviceBranchName":"Harare","deviceOperatingMode":"Online",
                "taxPayerDayMaxHrs":24,"taxpayerDayEndNotificationHrs":2,"qrUrl":"https://fdmstest.zimra.co.zw","applicableTaxes":[]}""",
            )
            "Ping" -> Answer(200, """{"reportingFrequency":5}""", mapOf("operationID" to "op-p"))
            "Slow" -> {
                Thread.sleep(3_000)
                Answer(200, "{}")
            }
            else -> Answer(404, """{"title":"Not found","status":404}""")
        }
    }

    @After
    fun stop() = server.close()

    private fun issuedToThisDevice(request: ReceivedRequest): Boolean {
        val certificate = request.clientCertificate ?: return false
        return try {
            certificate.verify(pki.ca.publicKey)
            certificate.subjectX500Principal.name.contains("-0000004321")
        } catch (e: Exception) {
            false
        }
    }

    /** Issues a certificate for the CSR's key with the CSR's subject, as ZIMRA does. */
    private fun issue(body: String): String {
        val csrPem = Json.parse(body).let { (it as JsonValue.Obj).fields["certificateRequest"] as JsonValue.Str }.value
        val csr = PEMParser(StringReader(csrPem)).use { it.readObject() as PKCS10CertificationRequest }
        val commonName = IETFUtils.valueToString(csr.subject.getRDNs(BCStyle.CN).first().first.value)
        val key = JcaPKCS10CertificationRequest(csr).publicKey
        return TestPki.pem(pki.deviceCertificate(key, commonName))
    }

    private fun json(text: String) = Json.write(JsonValue.Str(text))

    private fun client(transport: FdmsTransport) = FdmsClient(identity, transport, server.baseUrl, retries = 0)

    @Test
    fun registersThenTalksAsTheDevice() {
        val public = client(HttpsTransport(trust = pki.trust()))
        assertEquals("Acme", public.verifyTaxpayerInformation("ABCD1234").taxpayerName)
        assertNull("public requests send no client certificate", server.requests.last().clientCertificate)

        val issued = public.registerDevice("ABCD1234", DeviceCsr.create(signer, identity.serialNo, identity.deviceId))
        val chain = DeviceCertificate.parse(issued.certificatePem)
        DeviceCertificate.check(chain.first(), signer.publicKey, identity)

        val device = client(HttpsTransport(deviceKeys = pki.keyManager(signer.privateKey, chain), trust = pki.trust()))
        assertEquals(24, device.getConfig().dayMaxHours)
        assertEquals(5, device.ping())
        val ping = server.requests.last()
        assertNotNull("device requests present the certificate", ping.clientCertificate)
        assertEquals(chain.first(), ping.clientCertificate)
        assertEquals("fzimra-test", ping.headers["devicemodelname"])
        assertEquals("0", ping.headers["content-length"])
    }

    @Test
    fun aCertificateNotIssuedToTheDeviceIsRefused() {
        val stranger = DeviceIdentity(9999, "SN-X", "fzimra-test", "1.0")
        val wrongDevice = pki.deviceCertificate(signer.publicKey, DeviceCsr.commonName(stranger.serialNo, stranger.deviceId))
        val device = client(HttpsTransport(deviceKeys = pki.keyManager(signer.privateKey, listOf(wrongDevice)), trust = pki.trust()))
        val error = assertThrows(FdmsException::class.java) { device.getConfig() }
        assertTrue(error.isAuthenticationFailure)
    }

    @Test
    fun deviceRequestsNeedTheDeviceKeys() {
        val public = client(HttpsTransport(trust = pki.trust()))
        assertThrows(IllegalStateException::class.java) { public.getStatus() }
        assertTrue("nothing was sent", server.requests.isEmpty())
    }

    @Test
    fun anUntrustedServerIsNotContacted() {
        val platformTrust = client(HttpsTransport())
        assertThrows(FdmsNetworkException::class.java) { platformTrust.verifyTaxpayerInformation("ABCD1234") }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aServerThatDoesNotAnswerTimesOut() {
        val transport = HttpsTransport(trust = pki.trust(), timeout = Duration.ofMillis(500))
        val request = FdmsRequest("GET", server.baseUrl + "/Public/v1/Slow", emptyMap(), null, deviceAuthenticated = false)
        assertThrows(FdmsNetworkException::class.java) { transport.execute(request) }
    }

    @Test
    fun onlyHttpsIsUsed() {
        val transport = HttpsTransport(trust = pki.trust())
        assertThrows(IllegalArgumentException::class.java) {
            transport.execute(FdmsRequest("GET", "http://localhost:1/", emptyMap(), null, deviceAuthenticated = false))
        }
    }

    @Test
    fun errorsComeBackWithTheirBody() {
        val transport = HttpsTransport(trust = pki.trust())
        val response = transport.execute(FdmsRequest("GET", server.baseUrl + "/Public/v1/Missing", emptyMap(), null, deviceAuthenticated = false))
        assertEquals(404, response.status)
        assertEquals("""{"title":"Not found","status":404}""", response.body)
        assertEquals("application/json", response.headers["content-type"])
    }

    @Test
    fun theCertificateIsChecked() {
        val chain = DeviceCertificate.parse(TestPki.pem(pki.deviceCertificate(signer.publicKey, DeviceCsr.commonName("SN-TLS", 4321))))
        DeviceCertificate.check(chain.first(), signer.publicKey, identity)

        val otherKey = SoftwareSigner.ecP256().publicKey
        assertThrows(DeviceCertificateException::class.java) { DeviceCertificate.check(chain.first(), otherKey, identity) }
        assertThrows(DeviceCertificateException::class.java) {
            DeviceCertificate.check(chain.first(), signer.publicKey, identity.copy(deviceId = 4322))
        }
        val expired = pki.deviceCertificate(
            signer.publicKey, DeviceCsr.commonName("SN-TLS", 4321),
            notBefore = java.time.Instant.now().minusSeconds(7200), notAfter = java.time.Instant.now().minusSeconds(3600),
        )
        assertThrows(DeviceCertificateException::class.java) { DeviceCertificate.check(expired, signer.publicKey, identity) }
        val future = pki.deviceCertificate(
            signer.publicKey, DeviceCsr.commonName("SN-TLS", 4321),
            notBefore = java.time.Instant.now().plusSeconds(3600), notAfter = java.time.Instant.now().plusSeconds(7200),
        )
        assertThrows(DeviceCertificateException::class.java) { DeviceCertificate.check(future, signer.publicKey, identity) }
        assertThrows(DeviceCertificateException::class.java) { DeviceCertificate.parse("not a certificate") }
        assertEquals(2, DeviceCertificate.parse(TestPki.pem(chain.first()) + TestPki.pem(pki.ca)).size)
    }
}
