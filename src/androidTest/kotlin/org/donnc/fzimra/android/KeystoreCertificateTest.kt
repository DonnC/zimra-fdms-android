package org.donnc.fzimra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.donnc.fzimra.core.DeviceCertificateException
import org.donnc.fzimra.core.DeviceCsr
import org.donnc.fzimra.core.DeviceIdentity
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/** Runs on a device or emulator: `./gradlew connectedAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class KeystoreCertificateTest {
    private val alias = "fzimra-test-cert-${System.nanoTime()}"
    private val identity = DeviceIdentity(4321, "SN-ANDROID", "fzimra-test", "1.0")
    private val ca = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @After
    fun cleanUp() = KeystoreSigner.delete(alias)

    private fun issue(key: PublicKey, commonName: String): String {
        val now = System.currentTimeMillis()
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                X500Name("CN=Test ZIMRA CA"), BigInteger.ONE, Date(now - 86_400_000), Date(now + 86_400_000),
                X500Name("CN=$commonName"), key,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(ca.private)),
        )
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(certificate.encoded) + "\n-----END CERTIFICATE-----\n"
    }

    @Test
    fun theZimraCertificateIsInstalledAndPresentedForTls() {
        val signer = KeystoreSigner.getOrCreate(alias, requireHardware = false)
        val keys = signer.keyManager()
        assertFalse(signer.hasZimraCertificate)
        assertNull(signer.zimraCertificateExpiry)
        assertNull("no client certificate before registration", keys.chooseClientAlias(arrayOf("EC"), null, null))

        val pem = issue(signer.publicKey, DeviceCsr.commonName(identity.serialNo, identity.deviceId))
        signer.installCertificate(pem, identity)

        assertTrue(signer.hasZimraCertificate)
        assertTrue(KeystoreSigner.load(alias)!!.hasZimraCertificate)
        assertTrue(signer.zimraCertificateExpiry!!.isAfter(java.time.Instant.now()))
        assertEquals("the key manager made earlier sees it", alias, keys.chooseClientAlias(arrayOf("EC"), null, null))
        assertArrayEquals(signer.publicKey.encoded, keys.getCertificateChain(alias)!!.first().publicKey.encoded)
        assertEquals(signer.publicKey.algorithm, keys.getPrivateKey(alias)!!.algorithm)
        assertNull(keys.getPrivateKey("another"))
    }

    @Test
    fun aCertificateForAnotherKeyOrDeviceIsRefused() {
        val signer = KeystoreSigner.getOrCreate(alias, requireHardware = false)
        val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
        assertThrows(DeviceCertificateException::class.java) {
            signer.installCertificate(issue(otherKey, DeviceCsr.commonName(identity.serialNo, identity.deviceId)), identity)
        }
        assertThrows(DeviceCertificateException::class.java) {
            signer.installCertificate(issue(signer.publicKey, DeviceCsr.commonName("SN-X", 9999)), identity)
        }
        assertFalse(signer.hasZimraCertificate)
    }
}
