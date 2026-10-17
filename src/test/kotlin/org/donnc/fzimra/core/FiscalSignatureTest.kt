package org.donnc.fzimra.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.util.Base64

class FiscalSignatureTest {
    private val canonical = "322CREDITNOTEUSD852020-09-19T09:23:07-40350-7000.000-100014.50-300-2300"

    @Test
    fun signsAndVerifiesWithEcP256() {
        val signer = SoftwareSigner.ecP256()
        val signature = FiscalSignature.sign(signer, canonical)
        assertEquals(Sha256.base64(canonical), signature.hash)
        assertTrue(FiscalSignature.verify(signer.publicKey, canonical, signature.signature))
    }

    @Test
    fun signsAndVerifiesWithRsa2048() {
        val signer = SoftwareSigner.rsa(2048)
        val signature = FiscalSignature.sign(signer, canonical)
        assertTrue(FiscalSignature.verify(signer.publicKey, canonical, signature.signature))
    }

    @Test
    fun ecdsaSignaturesAreDerEncoded() {
        val raw = Base64.getDecoder().decode(FiscalSignature.sign(SoftwareSigner.ecP256(), canonical).signature)
        assertEquals("a DER SEQUENCE", 0x30, raw[0].toInt() and 0xFF)
        assertEquals("its length covers the rest", raw.size - 2, raw[1].toInt() and 0xFF)
    }

    @Test
    fun verificationFailsForChangedTextOtherKeyOrBadInput() {
        val signer = SoftwareSigner.ecP256()
        val signature = FiscalSignature.sign(signer, canonical).signature
        assertFalse(FiscalSignature.verify(signer.publicKey, canonical + "0", signature))
        assertFalse(FiscalSignature.verify(SoftwareSigner.ecP256().publicKey, canonical, signature))
        assertFalse(FiscalSignature.verify(signer.publicKey, canonical, "not base64!"))
        assertFalse(FiscalSignature.verify(signer.publicKey, canonical, ""))
    }

    @Test
    fun refusesKeysZimraDoesNotAccept() {
        val p384 = SoftwareSigner(SoftwareSigner.ecKeyPair("secp384r1"), SignatureAlgorithm.ECDSA_P256_SHA256)
        assertThrows(FiscalSigningException::class.java) { FiscalSignature.sign(p384, canonical) }
        assertThrows(FiscalSigningException::class.java) { FiscalSignature.sign(SoftwareSigner.rsa(1024), canonical) }
        assertFalse(DeviceKeys.isAccepted(p384.publicKey))
        assertTrue(DeviceKeys.isAccepted(SoftwareSigner.ecP256().publicKey))
    }

    @Test
    fun refusesAKeyUsedWithTheWrongAlgorithm() {
        val ecKeyClaimingRsa = SoftwareSigner(SoftwareSigner.ecKeyPair("secp256r1"), SignatureAlgorithm.RSA_SHA256)
        assertThrows(FiscalSigningException::class.java) { FiscalSignature.sign(ecKeyClaimingRsa, canonical) }
    }

    /** A signer returning raw r‖s (P1363) instead of DER: ZIMRA rejects those, so fzimra must too. */
    private class RawP1363Signer(private val inner: SoftwareSigner) : Signer by inner {
        override fun sign(data: ByteArray): ByteArray {
            val der = inner.sign(data)
            val rLength = der[3].toInt()
            val r = BigInteger(1, der.copyOfRange(4, 4 + rLength))
            val s = BigInteger(1, der.copyOfRange(4 + rLength + 2, der.size))
            return fixed(r) + fixed(s)
        }

        private fun fixed(value: BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
    }

    @Test
    fun refusesSignaturesInTheWrongFormat() {
        val e = assertThrows(FiscalSigningException::class.java) {
            FiscalSignature.sign(RawP1363Signer(SoftwareSigner.ecP256()), canonical)
        }
        assertTrue(e.message!!.contains("does not verify"))
    }

    @Test
    fun refusesASignerWhoseKeyDoesNotMatch() {
        val other = SoftwareSigner.ecP256()
        val mismatched = object : Signer by SoftwareSigner.ecP256() {
            override val publicKey: PublicKey = other.publicKey
        }
        assertThrows(FiscalSigningException::class.java) { FiscalSignature.sign(mismatched, canonical) }
    }

    @Test
    fun wrapsSignerFailures() {
        val failing = object : Signer by SoftwareSigner.ecP256() {
            override fun sign(data: ByteArray): ByteArray = throw GeneralSecurityException("key invalidated")
        }
        val e = assertThrows(FiscalSigningException::class.java) { FiscalSignature.sign(failing, canonical) }
        assertTrue(e.cause is GeneralSecurityException)
    }
}
