package org.donnc.fzimra.core

import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** Certificate requests, checked with an independent parser (BouncyCastle). */
class DeviceCsrTest {
    private fun parse(pem: String): PKCS10CertificationRequest =
        PEMParser(StringReader(pem)).use { it.readObject() as PKCS10CertificationRequest }

    private fun verifier(csr: PKCS10CertificationRequest) =
        JcaContentVerifierProviderBuilder().setProvider(BouncyCastleProvider()).build(csr.subjectPublicKeyInfo)

    private fun commonNameOf(csr: PKCS10CertificationRequest): String =
        csr.subject.getRDNs(BCStyle.CN).single().first.value.toString()

    @Test
    fun commonNameFollowsZimrasFormat() {
        // The example in ZIMRA's documentation: serial "SN: 001", device 187.
        assertEquals("ZIMRA-SN: 001-0000000187", DeviceCsr.commonName("SN: 001", 187))
        assertEquals("ZIMRA-FP9320-2026-XYZ01-0000674473", DeviceCsr.commonName("FP9320-2026-XYZ01", 674473))
    }

    @Test
    fun commonNameRejectsInvalidInput() {
        assertThrows(FiscalValueException::class.java) { DeviceCsr.commonName(" ", 1) }
        assertThrows(FiscalValueException::class.java) { DeviceCsr.commonName("SN1", 0) }
    }

    @Test
    fun ecRequestIsValidPkcs10() {
        val signer = SoftwareSigner.ecP256()
        val pem = DeviceCsr.create(signer, "SN: 001", 187)
        val csr = parse(pem)
        assertEquals("ZIMRA-SN: 001-0000000187", commonNameOf(csr))
        assertEquals(1, csr.subject.rdNs.size) // no other subject fields
        assertEquals("1.2.840.10045.4.3.2", csr.signatureAlgorithm.algorithm.id) // ecdsa-with-SHA256
        assertArrayEquals(signer.publicKey.encoded, csr.subjectPublicKeyInfo.encoded)
        assertEquals(0, csr.attributes.size)
        assertTrue(csr.isSignatureValid(verifier(csr)))
    }

    @Test
    fun rsaRequestIsValidPkcs10() {
        val signer = SoftwareSigner.rsa(2048)
        val csr = parse(DeviceCsr.create(signer, "SN-2", 42))
        assertEquals("1.2.840.113549.1.1.11", csr.signatureAlgorithm.algorithm.id) // sha256WithRSAEncryption
        assertTrue(csr.isSignatureValid(verifier(csr)))
    }

    @Test
    fun longSerialNumbersUseLongFormLengths() {
        val serial = "S".repeat(300) + " é" // non-ASCII is kept as UTF-8
        val csr = parse(DeviceCsr.create(SoftwareSigner.ecP256(), serial, 1))
        assertEquals("ZIMRA-$serial-0000000001", commonNameOf(csr))
        assertTrue(csr.isSignatureValid(verifier(csr)))
    }

    @Test
    fun pemIsWrappedAt64Characters() {
        val lines = DeviceCsr.create(SoftwareSigner.rsa(2048), "SN1", 1).trimEnd().lines()
        assertEquals("-----BEGIN CERTIFICATE REQUEST-----", lines.first())
        assertEquals("-----END CERTIFICATE REQUEST-----", lines.last())
        assertTrue(lines.drop(1).dropLast(1).all { it.length <= 64 })
        assertTrue(lines.drop(1).dropLast(2).all { it.length == 64 })
    }

    @Test
    fun refusesKeysZimraDoesNotAccept() {
        assertThrows(FiscalSigningException::class.java) { DeviceCsr.create(SoftwareSigner.rsa(1024), "SN1", 1) }
    }

    @Test
    fun derEncodesKnownValues() {
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3"))
        assertArrayEquals(
            byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02),
            Der.oid("1.2.840.10045.4.3.2"),
        )
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00), Der.integer(0))
        assertEquals(0x7F, Der.tagged(0x04, ByteArray(127))[1].toInt())
        assertArrayEquals(byteArrayOf(0x04, 0x81.toByte(), 0x80.toByte()), Der.tagged(0x04, ByteArray(128)).copyOf(3))
        assertArrayEquals(byteArrayOf(0x04, 0x82.toByte(), 0x01, 0x00), Der.tagged(0x04, ByteArray(256)).copyOf(4))
    }
}
