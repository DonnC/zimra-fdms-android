package org.donnc.fzimra.android

import org.donnc.fzimra.core.DeviceKey
import org.donnc.fzimra.core.DeviceKeyStore
import org.donnc.fzimra.core.FdmsEnvironment
import org.donnc.fzimra.core.FdmsTransport
import org.donnc.fzimra.core.Gateway
import org.donnc.fzimra.core.HttpsTransport
import javax.net.ssl.X509TrustManager

/** ZIMRA's Fiscal Device Gateway, reached over mutual TLS with the device's Keystore key. */
public class ZimraGateway(
    override val name: String,
    override val baseUrl: String,
    override val allowsSoftwareKeys: Boolean,
    private val trust: X509TrustManager? = null,
) : Gateway {
    @Volatile private var cached: Pair<DeviceKey?, FdmsTransport>? = null

    override fun transport(key: DeviceKey?): FdmsTransport {
        cached?.let { (k, t) -> if (k === key) return t }
        require(key == null || key is KeystoreSigner) { "ZIMRA's gateway needs a KeystoreSigner device key" }
        // The key manager reads the certificate at each connection, so one transport serves the key for good.
        return HttpsTransport(deviceKeys = (key as KeystoreSigner?)?.keyManager(), trust = trust).also { cached = key to it }
    }

    override fun toString(): String = "ZimraGateway($name)"

    public companion object {
        /** ZIMRA's test environment. Software keys are accepted, for emulators. */
        @JvmField
        public val TEST: ZimraGateway = ZimraGateway("ZIMRA test", FdmsEnvironment.TEST.baseUrl, allowsSoftwareKeys = true)

        /** ZIMRA's production environment: real fiscal records. The device key must be in secure hardware. */
        @JvmField
        public val PRODUCTION: ZimraGateway = ZimraGateway("ZIMRA production", FdmsEnvironment.PRODUCTION.baseUrl, allowsSoftwareKeys = false)
    }
}

/**
 * The device key in Android Keystore under [alias]: in secure hardware, or in
 * software only when [allowSoftware] (emulators).
 */
public class KeystoreKeyStore(private val alias: String, private val allowSoftware: Boolean) : DeviceKeyStore {
    override fun load(): DeviceKey? = KeystoreSigner.load(alias)

    override fun create(): DeviceKey = try {
        KeystoreSigner.getOrCreate(alias, requireHardware = true)
    } catch (e: KeystoreUnavailableException) {
        if (!allowSoftware) throw e
        KeystoreSigner.getOrCreate(alias, requireHardware = false)
    }

    override fun delete() {
        KeystoreSigner.delete(alias)
    }
}
