package org.donnc.fzimra.android

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.donnc.fzimra.core.Sealer
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Seals fzimra's stored data with an HMAC-SHA256 key held in Android
 * Keystore.
 *
 * The key is generated inside the Keystore and cannot be exported, so the
 * app cannot forge a seal over edited data. StrongBox is used when available.
 *
 * Keep the key for as long as the device key: if it is lost, stored state can
 * no longer be checked and fzimra refuses it. Both keys are removed when the
 * app is uninstalled or its data cleared, together with fzimra's own stores.
 */
public class KeystoreSealer private constructor(
    /** The Keystore alias of the key. */
    public val alias: String,
    private val key: SecretKey,
) : Sealer {
    override fun seal(data: ByteArray): ByteArray = Mac.getInstance(ALGORITHM).run {
        init(key)
        doFinal(data)
    }

    /** Where the key is held. */
    public val securityLevel: KeySecurityLevel
        get() = Keystore.securityLevel(
            SecretKeyFactory.getInstance(key.algorithm, Keystore.PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo,
        )

    override fun toString(): String = "KeystoreSealer(alias=$alias)"

    public companion object {
        private const val ALGORITHM = "HmacSHA256"

        /**
         * The sealing key under [alias], creating it if it does not exist.
         *
         * @param requireHardware refuse a key that is held in software only.
         * @throws KeystoreUnavailableException if the key cannot be created as required.
         */
        public fun getOrCreate(alias: String, requireHardware: Boolean = true): KeystoreSealer {
            if (alias.isBlank()) throw KeystoreUnavailableException("the key alias is blank")
            load(alias)?.let { return it }
            try {
                generate(alias)
            } catch (e: GeneralSecurityException) {
                throw KeystoreUnavailableException("the Keystore could not create the sealing key: ${e.message}", e)
            }
            val sealer = checkNotNull(load(alias)) { "the new key under '$alias' cannot be loaded" }
            if (requireHardware && sealer.securityLevel == KeySecurityLevel.SOFTWARE) {
                delete(alias)
                throw KeystoreUnavailableException("this device can only hold the sealing key in software")
            }
            return sealer
        }

        /** The existing sealing key under [alias], or `null`. */
        public fun load(alias: String): KeystoreSealer? {
            val key = Keystore.keyStore().getKey(alias, null) as? SecretKey ?: return null
            return KeystoreSealer(alias, key)
        }

        /**
         * Deletes the key under [alias]. Data it sealed can no longer be
         * checked, so fzimra refuses it afterwards.
         */
        public fun delete(alias: String) {
            Keystore.keyStore().deleteEntry(alias)
        }

        private fun generate(alias: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generateKey(alias, strongBox = true)
                    return
                } catch (e: StrongBoxUnavailableException) {
                    // fall back to the trusted execution environment
                } catch (e: ProviderException) {
                    if (Keystore.keyStore().containsAlias(alias)) delete(alias)
                }
            }
            generateKey(alias, strongBox = false)
        }

        private fun generateKey(alias: String, strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setUserAuthenticationRequired(false)
                .apply { if (strongBox) Keystore.strongBoxBacked(this) }
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, Keystore.PROVIDER).run {
                init(spec)
                generateKey()
            }
        }
    }
}
