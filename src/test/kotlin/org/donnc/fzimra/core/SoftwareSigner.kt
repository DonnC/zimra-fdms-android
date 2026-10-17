package org.donnc.fzimra.core

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A signer with the key in memory. **Tests only**: a fiscal device key must
 * live in secure hardware, which is why the library ships no such signer.
 */
internal class SoftwareSigner(
    val privateKey: PrivateKey,
    override val publicKey: PublicKey,
    override val algorithm: SignatureAlgorithm,
) : Signer {
    constructor(pair: KeyPair, algorithm: SignatureAlgorithm) : this(pair.private, pair.public, algorithm)

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance(algorithm.jcaName).run {
        initSign(privateKey)
        update(data)
        sign()
    }

    companion object {
        fun ecP256(): SoftwareSigner = SoftwareSigner(ecKeyPair("secp256r1"), SignatureAlgorithm.ECDSA_P256_SHA256)

        fun rsa(bits: Int = 2048): SoftwareSigner = SoftwareSigner(
            KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair(),
            SignatureAlgorithm.RSA_SHA256,
        )

        fun ecKeyPair(curve: String): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

        fun fromPem(privateKeyPem: String, publicKeyPem: String): SoftwareSigner {
            val factory = KeyFactory.getInstance("EC")
            return SoftwareSigner(
                factory.generatePrivate(PKCS8EncodedKeySpec(pemBody(privateKeyPem))),
                factory.generatePublic(X509EncodedKeySpec(pemBody(publicKeyPem))),
                SignatureAlgorithm.ECDSA_P256_SHA256,
            )
        }

        fun publicKeyFromPem(pem: String): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pemBody(pem)))

        private fun pemBody(pem: String): ByteArray =
            Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
    }
}
