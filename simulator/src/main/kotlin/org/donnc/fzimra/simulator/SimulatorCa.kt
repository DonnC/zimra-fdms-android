package org.donnc.fzimra.simulator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * The simulator's certificate authority: issues device certificates from
 * certificate requests, authenticates devices by them, and signs what the
 * gateway accepts.
 */
internal class SimulatorCa(val keys: KeyPair, val certificate: X509Certificate) {
    private val serials = AtomicLong(System.currentTimeMillis())

    val pem: String get() = pem(certificate)

    /** SHA-1 of the certificate, upper-case hex, as ZIMRA names its certificates. */
    val thumbprint: String =
        MessageDigest.getInstance("SHA-1").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

    /** A device certificate for the key in [csrPem], with its subject; and that subject's common name. */
    fun issueFromCsr(csrPem: String, validity: Duration): Pair<X509Certificate, String> {
        val csr = try {
            PEMParser(StringReader(csrPem)).use { it.readObject() as PKCS10CertificationRequest }
        } catch (e: Exception) {
            throw IllegalArgumentException("the certificate request cannot be read")
        }
        val publicKey = JcaPKCS10CertificationRequest(csr).publicKey
        require(csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(publicKey))) {
            "the certificate request's signature does not verify"
        }
        val cn = csr.subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.let(IETFUtils::valueToString)
            ?: throw IllegalArgumentException("the certificate request has no common name")
        val now = Instant.now()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(certificate.subjectX500Principal.name), BigInteger.valueOf(serials.incrementAndGet()),
            Date.from(now.minusSeconds(60)), Date.from(now.plus(validity)), csr.subject, publicKey,
        ).addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))) to cn
    }

    /** What mutual TLS establishes: a valid certificate this authority issued. */
    fun authenticate(certificate: X509Certificate?): X509Certificate {
        certificate ?: throw GatewayError(401, "Unauthorized", "no client certificate")
        try {
            certificate.verify(keys.public)
        } catch (e: Exception) {
            throw GatewayError(401, "Unauthorized", "the client certificate was not issued by FDMS")
        }
        if (certificate.notAfter.before(Date())) throw GatewayError(401, "Unauthorized", "the client certificate expired")
        return certificate
    }

    /** ZIMRA's signature on [text]. */
    fun sign(text: String): JsonObject {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(text.toByteArray(Charsets.UTF_8))
            sign()
        }
        return JsonObject(
            mapOf(
                "hash" to JsonPrimitive(sha256(text)),
                "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature)),
                "certificateThumbprint" to JsonPrimitive(thumbprint),
            ),
        )
    }

    companion object {
        fun create(): SimulatorCa {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val name = X500Name("CN=fzimra FDMS simulator CA")
            val now = Instant.now()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(3650))), name, keys.public,
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)))
            return SimulatorCa(keys, certificate)
        }

        fun restore(privateKey: String, publicKey: String, certificatePem: String): SimulatorCa {
            val factory = KeyFactory.getInstance("EC")
            val keys = KeyPair(
                factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))),
            )
            return SimulatorCa(keys, certificate(certificatePem))
        }

        fun certificate(pem: String): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate

        fun pem(certificate: X509Certificate): String =
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
                "\n-----END CERTIFICATE-----\n"

        fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

        fun sha256(text: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }
}

/** A refusal, answered as ZIMRA's problem details. */
internal class GatewayError(val status: Int, val code: String?, message: String) : Exception(message)
