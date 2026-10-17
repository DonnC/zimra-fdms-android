package org.donnc.fzimra.android

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.donnc.fzimra.core.DeviceCertificate
import org.donnc.fzimra.core.DeviceCertificateException
import org.donnc.fzimra.core.DeviceIdentity
import org.donnc.fzimra.core.SignatureAlgorithm
import org.donnc.fzimra.core.Signer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** Where a device key is held. */
public enum class KeySecurityLevel {
    /** A dedicated secure chip (StrongBox). */
    STRONGBOX,

    /** The processor's trusted execution environment. */
    TRUSTED_ENVIRONMENT,

    /** Software only: the key is not protected by hardware. */
    SOFTWARE,

    /** The platform did not say. */
    UNKNOWN,
}

/** The Android Keystore cannot hold the device key as required. */
public class KeystoreUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * A fiscal device key held in Android Keystore.
 *
 * The key is an EC P-256 key generated inside the Keystore. It cannot be
 * exported: the app, and fzimra, only ever see its public key and the
 * signatures it makes. StrongBox is used when the device supports the key's
 * requirements; otherwise the trusted execution environment.
 *
 * The key is authorised for SHA-256 signatures (receipts, fiscal days,
 * certificate requests) and for unhashed signatures, which the TLS handshake
 * with ZIMRA needs. Authorisations are fixed when a key is created.
 *
 * One alias holds one device key. Losing or deleting it means registering the
 * device with ZIMRA again.
 */
public class KeystoreSigner private constructor(
    /** The Keystore alias of the key. */
    public val alias: String,
    private val privateKey: PrivateKey,
    override val publicKey: PublicKey,
) : Signer {
    override val algorithm: SignatureAlgorithm = SignatureAlgorithm.ECDSA_P256_SHA256

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance(algorithm.jcaName).run {
        initSign(privateKey)
        update(data)
        sign()
    }

    /**
     * The key's certificate chain from the Keystore. When the key was created
     * with an attestation challenge, this is the key attestation chain, which
     * lets a server confirm that the key was generated in secure hardware;
     * read it before [installCertificate], which replaces it.
     */
    public val certificateChain: List<X509Certificate>
        get() = Keystore.keyStore().getCertificateChain(alias)?.map { it as X509Certificate }.orEmpty()

    /** Where the key is held. */
    public val securityLevel: KeySecurityLevel
        get() = Keystore.securityLevel(
            KeyFactory.getInstance(privateKey.algorithm, Keystore.PROVIDER).getKeySpec(privateKey, KeyInfo::class.java),
        )

    /**
     * When the installed ZIMRA certificate expires, or `null` before one is
     * installed. Renew it before then (ZIMRA suggests a month ahead): make a new
     * certificate request with `DeviceCsr`, send it with
     * `FdmsClient.issueCertificate` and install the answer.
     */
    public val zimraCertificateExpiry: java.time.Instant?
        get() = zimraChain()?.first()?.notAfter?.toInstant()

    /** Whether a ZIMRA device certificate has been installed for the key. */
    public val hasZimraCertificate: Boolean
        get() = zimraChain() != null

    /**
     * Stores the device certificate ZIMRA issued (from `registerDevice` or
     * `issueCertificate`) with the key, for mutual TLS with ZIMRA. It replaces
     * the key's current certificate chain.
     *
     * @throws DeviceCertificateException if [certificatePem] is not a valid
     *   certificate for this key and the device in [identity].
     */
    public fun installCertificate(certificatePem: String, identity: DeviceIdentity) {
        val chain = DeviceCertificate.parse(certificatePem)
        DeviceCertificate.check(chain.first(), publicKey, identity)
        Keystore.keyStore().setKeyEntry(alias, privateKey, null, chain.toTypedArray())
    }

    /**
     * The key and its ZIMRA certificate for TLS, for `HttpsTransport`. The
     * private key stays in the Keystore; the TLS stack signs with it there.
     * The certificate is looked up at each connection, so a certificate
     * installed later is used without a new key manager.
     */
    public fun keyManager(): X509KeyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            if (hasZimraCertificate) arrayOf(alias) else null

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            if (hasZimraCertificate) alias else null

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            if (hasZimraCertificate) alias else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == this@KeystoreSigner.alias) zimraChain()?.toTypedArray() else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this@KeystoreSigner.alias) privateKey else null

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    /** The installed ZIMRA certificate chain, or `null` while the key has only its own. */
    private fun zimraChain(): List<X509Certificate>? {
        val chain = certificateChain
        val leaf = chain.firstOrNull() ?: return null
        val issuedByZimra = leaf.subjectX500Principal.name.contains("CN=ZIMRA-") && leaf.subjectX500Principal != leaf.issuerX500Principal
        return chain.takeIf { issuedByZimra && leaf.publicKey.encoded.contentEquals(publicKey.encoded) }
    }

    override fun toString(): String = "KeystoreSigner(alias=$alias)"

    public companion object {
        /** Whether a key exists under [alias]. */
        public fun exists(alias: String): Boolean = Keystore.keyStore().containsAlias(alias)

        /**
         * The device key under [alias], creating it if it does not exist.
         *
         * @param attestationChallenge a challenge from your server, bound into
         *   the key attestation chain. Only used when the key is created.
         * @param requireHardware refuse a key that is held in software only.
         *   Leave this `true` on real devices; emulators only offer software keys.
         * @throws KeystoreUnavailableException if the key cannot be created as required.
         */
        public fun getOrCreate(
            alias: String,
            attestationChallenge: ByteArray? = null,
            requireHardware: Boolean = true,
        ): KeystoreSigner = load(alias) ?: create(alias, attestationChallenge, requireHardware)

        /** The existing device key under [alias], or `null`. */
        public fun load(alias: String): KeystoreSigner? {
            val entry = Keystore.keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
            return KeystoreSigner(alias, entry.privateKey, entry.certificate.publicKey)
        }

        /**
         * Creates the device key under [alias].
         *
         * @throws KeystoreUnavailableException if a key already exists under
         *   [alias], or the key cannot be created as required.
         */
        public fun create(alias: String, attestationChallenge: ByteArray? = null, requireHardware: Boolean = true): KeystoreSigner {
            if (alias.isBlank()) throw KeystoreUnavailableException("the key alias is blank")
            if (exists(alias)) throw KeystoreUnavailableException("a key already exists under '$alias'")
            try {
                generate(alias, attestationChallenge)
            } catch (e: java.security.GeneralSecurityException) {
                throw KeystoreUnavailableException("the Keystore could not create the device key: ${e.message}", e)
            }
            val signer = checkNotNull(load(alias)) { "the new key under '$alias' cannot be loaded" }
            if (requireHardware && signer.securityLevel == KeySecurityLevel.SOFTWARE) {
                delete(alias)
                throw KeystoreUnavailableException("this device can only hold the key in software")
            }
            return signer
        }

        /**
         * Deletes the key under [alias].
         *
         * This is irreversible: the device can no longer sign, and must be
         * registered with ZIMRA again with a new key.
         */
        public fun delete(alias: String) {
            Keystore.keyStore().deleteEntry(alias)
        }

        private fun generate(alias: String, challenge: ByteArray?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && generateInStrongBox(alias, challenge)) return
            generateKey(spec(alias, challenge, strongBox = false))
        }

        /** Tries StrongBox; `false` when it is missing or cannot meet the key's requirements. */
        @SuppressLint("UseRequiresApi") // private: every caller checks Build.VERSION first
        @TargetApi(Build.VERSION_CODES.P)
        private fun generateInStrongBox(alias: String, challenge: ByteArray?): Boolean = try {
            generateKey(spec(alias, challenge, strongBox = true))
            true
        } catch (e: StrongBoxUnavailableException) {
            false
        } catch (e: java.security.ProviderException) {
            if (exists(alias)) delete(alias)
            false
        }

        private fun generateKey(spec: KeyGenParameterSpec) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, Keystore.PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }

        private fun spec(alias: String, challenge: ByteArray?, strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // SHA-256 for fiscal signatures; NONE for the TLS handshake, which signs a precomputed hash.
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setUserAuthenticationRequired(false)
                .apply { if (challenge != null) setAttestationChallenge(challenge) }
                .apply { if (strongBox) Keystore.strongBoxBacked(this) }
                .build()
    }
}
