package org.donnc.fzimra.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** A throwaway certificate authority standing in for ZIMRA's. Tests only. */
internal class TestPki {
    private val serials = AtomicLong(1)
    private val caKeys = ecKeys()
    val ca: X509Certificate = certificate(
        "CN=Test ZIMRA CA", caKeys.public, "CN=Test ZIMRA CA", caKeys.private, isCa = true,
    )

    /** A device certificate for [publicKey], as ZIMRA would issue it. */
    fun deviceCertificate(
        publicKey: PublicKey,
        commonName: String,
        notBefore: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        notAfter: Instant = Instant.now().plus(365, ChronoUnit.DAYS),
    ): X509Certificate = certificate("CN=$commonName,O=Zimbabwe Revenue Authority,C=ZW", publicKey, "CN=Test ZIMRA CA", caKeys.private, notBefore = notBefore, notAfter = notAfter)

    /** A key and certificate for a server at localhost. */
    fun server(): Pair<PrivateKey, X509Certificate> {
        val keys = ecKeys()
        return keys.private to certificate("CN=localhost", keys.public, "CN=Test ZIMRA CA", caKeys.private, localhost = true)
    }

    /** Trusts only this authority. */
    fun trust(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ca", ca)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun keyManager(key: PrivateKey, chain: List<X509Certificate>): X509KeyManager {
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("device", key, password, chain.toTypedArray())
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            .keyManagers.filterIsInstance<X509KeyManager>().first()
    }

    private fun certificate(
        subject: String,
        publicKey: PublicKey,
        issuer: String,
        issuerKey: PrivateKey,
        isCa: Boolean = false,
        localhost: Boolean = false,
        notBefore: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        notAfter: Instant = Instant.now().plus(365, ChronoUnit.DAYS),
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer), BigInteger.valueOf(serials.getAndIncrement()), Date.from(notBefore), Date.from(notAfter),
            X500Name(subject), publicKey,
        )
        if (isCa) builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        if (localhost) {
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, "localhost"), GeneralName(GeneralName.iPAddress, "127.0.0.1"))),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    companion object {
        fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        fun pem(certificate: X509Certificate): String =
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
                "\n-----END CERTIFICATE-----\n"
    }
}
