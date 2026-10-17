package org.donnc.fzimra.core

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Signature algorithms ZIMRA accepts, in its order of preference. */
public enum class SignatureAlgorithm(public val jcaName: String) {
    /** ECDSA on the NIST P-256 (secp256r1) curve with SHA-256. Preferred. */
    ECDSA_P256_SHA256("SHA256withECDSA"),

    /** RSA (2048 bits or more) with SHA-256, PKCS #1 v1.5 padding. */
    RSA_SHA256("SHA256withRSA"),
}

/**
 * Signs with the fiscal device's private key.
 *
 * The private key never leaves the signer: fzimra only asks for signatures and
 * the public key. On Android, use [org.donnc.fzimra.android.KeystoreSigner];
 * implement this interface yourself only for other secure key stores.
 */
public interface Signer {
    /** The algorithm [sign] uses. */
    public val algorithm: SignatureAlgorithm

    /** The public half of the device key. */
    public val publicKey: PublicKey

    /**
     * Signs [data]: hashes it with SHA-256 and signs the hash.
     *
     * @return the signature; ASN.1 DER-encoded for ECDSA.
     */
    public fun sign(data: ByteArray): ByteArray
}

/** Signing failed, or produced a signature that does not verify. */
public class FiscalSigningException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * A device signature: the Base64 SHA-256 [hash] of the signed text and the
 * Base64 [signature] over it.
 */
public data class DeviceSignature(
    val hash: String,
    val signature: String,
)

/** Signs and verifies the strings built by [SignatureStrings]. */
public object FiscalSignature {
    /**
     * Signs [canonical] with [signer].
     *
     * The signature is verified against the signer's public key before it is
     * returned, so a faulty signer, or one that returns a signature format
     * ZIMRA rejects, fails here rather than on ZIMRA's side.
     *
     * @throws FiscalSigningException if the key is not one ZIMRA accepts, or
     *   the signature does not verify.
     */
    public fun sign(signer: Signer, canonical: String): DeviceSignature {
        DeviceKeys.requireAccepted(signer.publicKey, signer.algorithm)
        val data = canonical.toByteArray(Charsets.UTF_8)
        val signature = try {
            signer.sign(data)
        } catch (e: GeneralSecurityException) {
            throw FiscalSigningException("the signer failed: ${e.message}", e)
        }
        if (!verifyBytes(signer.publicKey, signer.algorithm, data, signature)) {
            throw FiscalSigningException("the signer returned a signature that does not verify with its public key")
        }
        return DeviceSignature(Sha256.base64(canonical), Base64.getEncoder().encodeToString(signature))
    }

    /**
     * Whether [signatureBase64] is a valid signature of [canonical] by
     * [publicKey]. The algorithm follows the key type.
     */
    public fun verify(publicKey: PublicKey, canonical: String, signatureBase64: String): Boolean {
        val algorithm = when (publicKey) {
            is ECPublicKey -> SignatureAlgorithm.ECDSA_P256_SHA256
            is RSAPublicKey -> SignatureAlgorithm.RSA_SHA256
            else -> return false
        }
        val signature = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return verifyBytes(publicKey, algorithm, canonical.toByteArray(Charsets.UTF_8), signature)
    }

    internal fun verifyBytes(publicKey: PublicKey, algorithm: SignatureAlgorithm, data: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance(algorithm.jcaName).run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            }
        } catch (e: GeneralSecurityException) {
            false
        }
}

/** The key types ZIMRA accepts for a fiscal device. */
public object DeviceKeys {
    /** Order of the NIST P-256 group; identifies the curve. */
    private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private const val MIN_RSA_BITS = 2048

    /** Whether [publicKey] is an EC P-256 or RSA (2048 bits or more) key. */
    public fun isAccepted(publicKey: PublicKey): Boolean = when (publicKey) {
        is ECPublicKey -> publicKey.params.order == P256_ORDER
        is RSAPublicKey -> publicKey.modulus.bitLength() >= MIN_RSA_BITS
        else -> false
    }

    internal fun requireAccepted(publicKey: PublicKey, algorithm: SignatureAlgorithm) {
        val matches = when (algorithm) {
            SignatureAlgorithm.ECDSA_P256_SHA256 -> publicKey is ECPublicKey
            SignatureAlgorithm.RSA_SHA256 -> publicKey is RSAPublicKey
        }
        if (!matches || !isAccepted(publicKey)) {
            throw FiscalSigningException(
                "ZIMRA accepts EC P-256 or RSA keys of at least $MIN_RSA_BITS bits, used with $algorithm; " +
                    "got a ${publicKey.algorithm} key",
            )
        }
    }
}
