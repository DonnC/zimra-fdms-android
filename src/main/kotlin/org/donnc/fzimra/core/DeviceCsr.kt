package org.donnc.fzimra.core

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Certificate signing requests for a fiscal device.
 *
 * ZIMRA issues the device certificate from a PKCS #10 request whose subject
 * common name is `ZIMRA-<serial number>-<device ID as 10 digits>`. Other
 * subject fields are optional and must match ZIMRA's values exactly if
 * present, so fzimra leaves them out.
 */
public object DeviceCsr {
    private const val MAX_10_DIGITS = 9_999_999_999L

    /** The subject common name ZIMRA expects: `ZIMRA-<serialNo>-<deviceId padded to 10 digits>`. */
    public fun commonName(serialNo: String, deviceId: Int): String {
        if (serialNo.isBlank()) throw FiscalValueException("the device serial number is blank")
        if (deviceId <= 0 || deviceId > MAX_10_DIGITS) throw FiscalValueException("deviceID must be 1 to 10 digits")
        return "ZIMRA-$serialNo-${deviceId.toString().padStart(10, '0')}"
    }

    /**
     * A PEM-encoded certificate signing request for the device key held by
     * [signer], signed by that key.
     *
     * @throws FiscalSigningException if the key is not one ZIMRA accepts or
     *   the request signature does not verify.
     */
    public fun create(signer: Signer, serialNo: String, deviceId: Int): String {
        val name = commonName(serialNo, deviceId)
        DeviceKeys.requireAccepted(signer.publicKey, signer.algorithm)

        val subject = Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8String(name))))
        val requestInfo = Der.sequence(
            Der.integer(0),
            subject,
            signer.publicKey.encoded, // SubjectPublicKeyInfo, already DER
            Der.contextConstructed(0), // no attributes
        )
        val signature = try {
            signer.sign(requestInfo)
        } catch (e: java.security.GeneralSecurityException) {
            throw FiscalSigningException("the signer failed: ${e.message}", e)
        }
        if (!FiscalSignature.verifyBytes(signer.publicKey, signer.algorithm, requestInfo, signature)) {
            throw FiscalSigningException("the signer returned a signature that does not verify with its public key")
        }
        val algorithm = when (signer.algorithm) {
            SignatureAlgorithm.ECDSA_P256_SHA256 -> Der.sequence(Der.oid(OID_ECDSA_SHA256))
            SignatureAlgorithm.RSA_SHA256 -> Der.sequence(Der.oid(OID_RSA_SHA256), Der.NULL)
        }
        val request = Der.sequence(requestInfo, algorithm, Der.bitString(signature))
        return pem("CERTIFICATE REQUEST", request)
    }

    private const val OID_COMMON_NAME = "2.5.4.3"
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_RSA_SHA256 = "1.2.840.113549.1.1.11"

    private fun pem(label: String, der: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }
}

/** The small subset of ASN.1 DER needed to write a certificate signing request. */
internal object Der {
    val NULL: ByteArray = byteArrayOf(0x05, 0x00)

    fun sequence(vararg parts: ByteArray): ByteArray = tagged(0x30, concat(parts))

    fun set(vararg parts: ByteArray): ByteArray = tagged(0x31, concat(parts))

    fun integer(value: Long): ByteArray = tagged(0x02, java.math.BigInteger.valueOf(value).toByteArray())

    fun utf8String(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))

    fun bitString(bytes: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0) + bytes)

    fun contextConstructed(tag: Int, vararg parts: ByteArray): ByteArray = tagged(0xA0 or tag, concat(parts))

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2 && arcs[0] <= 2) { "invalid OID $dotted" }
        val out = ByteArrayOutputStream()
        writeBase128(out, arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) writeBase128(out, arc)
        return tagged(0x06, out.toByteArray())
    }

    fun tagged(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
            return
        }
        val bytes = generateSequence(length) { it ushr 8 }.takeWhile { it > 0 }.map { it and 0xFF }.toList().reversed()
        out.write(0x80 or bytes.size)
        bytes.forEach { out.write(it) }
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Long) {
        val groups = mutableListOf((value and 0x7F).toInt())
        var rest = value ushr 7
        while (rest > 0) {
            groups += ((rest and 0x7F) or 0x80).toInt()
            rest = rest ushr 7
        }
        groups.reversed().forEach { out.write(it) }
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
