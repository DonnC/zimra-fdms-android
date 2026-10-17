package org.donnc.fzimra.core

import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.security.auth.x500.X500Principal

/** A certificate could not be used as this device's certificate. */
public class DeviceCertificateException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Reads and checks the device certificate ZIMRA issues. */
public object DeviceCertificate {
    /**
     * The certificates in [pem], in order.
     *
     * @throws DeviceCertificateException if [pem] holds no readable certificate.
     */
    public fun parse(pem: String): List<X509Certificate> {
        val certificates = try {
            CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(pem.trim().toByteArray(Charsets.US_ASCII)))
                .map { it as X509Certificate }
        } catch (e: CertificateException) {
            throw DeviceCertificateException("the certificate cannot be read: ${e.message}", e)
        }
        if (certificates.isEmpty()) throw DeviceCertificateException("no certificate found")
        return certificates
    }

    /**
     * Checks that [certificate] is this device's: issued for [publicKey], to
     * the device ID in [identity], and valid at [now].
     *
     * @throws DeviceCertificateException naming the first problem found.
     */
    public fun check(certificate: X509Certificate, publicKey: PublicKey, identity: DeviceIdentity, now: Instant = Instant.now()) {
        if (!certificate.publicKey.encoded.contentEquals(publicKey.encoded)) {
            throw DeviceCertificateException("the certificate is not for this device's key")
        }
        // The subject names the device as ZIMRA-<serial>-<10-digit device ID>. The
        // device ID is what identifies it; the serial's spelling is not relied on.
        val deviceSuffix = "-" + identity.deviceId.toString().padStart(10, '0')
        val commonName = commonName(certificate.subjectX500Principal)
        if (commonName == null || !commonName.endsWith(deviceSuffix)) {
            throw DeviceCertificateException("the certificate is issued to '$commonName', not to device ${identity.deviceId}")
        }
        val at = java.util.Date.from(now)
        if (at.before(certificate.notBefore)) throw DeviceCertificateException("the certificate is not valid yet")
        if (at.after(certificate.notAfter)) throw DeviceCertificateException("the certificate expired on ${certificate.notAfter.toInstant()}")
    }

    private fun commonName(principal: X500Principal): String? =
        principal.getName(X500Principal.RFC2253)
            .split(Regex("(?<!\\\\),"))
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substring(3)
            ?.replace("\\", "")
}
