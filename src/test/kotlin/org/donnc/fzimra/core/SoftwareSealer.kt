package org.donnc.fzimra.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A sealer with the key in memory. **Tests only**: a sealing key must live
 * where the app cannot read it, which is why the library ships no such sealer.
 */
internal class SoftwareSealer(private val key: ByteArray = TEST_KEY) : Sealer {
    override fun seal(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    companion object {
        val TEST_KEY = ByteArray(32) { it.toByte() }
    }
}

/** [snapshot] sealed as a device with the test key would seal it. */
internal fun sealed(snapshot: FiscalSnapshot): FiscalSnapshot = Seals(SoftwareSealer()).seal(snapshot)
