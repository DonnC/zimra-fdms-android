package org.donnc.fzimra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.donnc.fzimra.core.DeviceCsr
import org.donnc.fzimra.core.DeviceKeys
import org.donnc.fzimra.core.FiscalSignature
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringReader
import java.security.KeyStore

/**
 * Runs on a device or emulator: `./gradlew connectedAndroidTest`.
 *
 * Emulators only offer software keys, so these tests pass
 * `requireHardware = false`; the hardware requirement is checked separately.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSignerTest {
    private val alias = "fzimra-test-" + System.nanoTime()

    @After
    fun deleteKey() {
        KeystoreSigner.delete(alias)
    }

    @Test
    fun createsAnAcceptedP256KeyAndSigns() {
        val signer = KeystoreSigner.create(alias, requireHardware = false)
        assertTrue(DeviceKeys.isAccepted(signer.publicKey))
        val canonical = "322FISCALINVOICEUSD12020-09-19T09:00:00230015.003002300"
        val signature = FiscalSignature.sign(signer, canonical) // verified against the public key inside
        assertTrue(FiscalSignature.verify(signer.publicKey, canonical, signature.signature))
    }

    @Test
    fun thePrivateKeyCannotBeExported() {
        KeystoreSigner.create(alias, requireHardware = false)
        val entry = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        assertNull("the key material must not be readable", entry.privateKey.encoded)
    }

    @Test
    fun loadsTheSameKeyAgain() {
        val created = KeystoreSigner.create(alias, requireHardware = false)
        val loaded = KeystoreSigner.load(alias)
        assertNotNull(loaded)
        loaded!!
        assertArrayEquals(created.publicKey.encoded, loaded.publicKey.encoded)
        assertArrayEquals(created.publicKey.encoded, KeystoreSigner.getOrCreate(alias, requireHardware = false).publicKey.encoded)
    }

    @Test
    fun refusesToOverwriteAnExistingKey() {
        KeystoreSigner.create(alias, requireHardware = false)
        assertThrows(KeystoreUnavailableException::class.java) { KeystoreSigner.create(alias, requireHardware = false) }
    }

    @Test
    fun deleteRemovesTheKey() {
        KeystoreSigner.create(alias, requireHardware = false)
        assertTrue(KeystoreSigner.exists(alias))
        KeystoreSigner.delete(alias)
        assertFalse(KeystoreSigner.exists(alias))
        assertNull(KeystoreSigner.load(alias))
    }

    @Test
    fun attestationChainCarriesTheKey() {
        val signer = KeystoreSigner.create(alias, attestationChallenge = "server-nonce".toByteArray(), requireHardware = false)
        val chain = signer.certificateChain
        assertTrue("an attestation chain is expected", chain.isNotEmpty())
        assertArrayEquals(signer.publicKey.encoded, chain.first().publicKey.encoded)
    }

    @Test
    fun certificateRequestFromTheKeystoreKeyIsValid() {
        val signer = KeystoreSigner.create(alias, requireHardware = false)
        val csr = PEMParser(StringReader(DeviceCsr.create(signer, "SN: 001", 187))).use { it.readObject() as PKCS10CertificationRequest }
        assertEquals("ZIMRA-SN: 001-0000000187", csr.subject.rdNs.single().first.value.toString())
        assertTrue(csr.isSignatureValid(JcaContentVerifierProviderBuilder().build(csr.subjectPublicKeyInfo)))
    }

    @Test
    fun hardwareRequirementMatchesTheSecurityLevel() {
        val signer = KeystoreSigner.create(alias, requireHardware = false)
        val level = signer.securityLevel
        KeystoreSigner.delete(alias)
        if (level == KeySecurityLevel.SOFTWARE) {
            assertThrows(KeystoreUnavailableException::class.java) { KeystoreSigner.create(alias) }
            assertFalse("a refused key must not be left behind", KeystoreSigner.exists(alias))
        } else {
            assertEquals(level, KeystoreSigner.create(alias).securityLevel)
        }
    }
}
