package org.donnc.fzimra.android

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyStore

/** Android Keystore helpers shared by fzimra's keys. */
internal object Keystore {
    const val PROVIDER = "AndroidKeyStore"

    fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun securityLevel(info: KeyInfo): KeySecurityLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        securityLevelOf(info)
    } else {
        @Suppress("DEPRECATION")
        if (info.isInsideSecureHardware) KeySecurityLevel.TRUSTED_ENVIRONMENT else KeySecurityLevel.SOFTWARE
    }

    /** Asks for StrongBox, where the platform has it. */
    fun strongBoxBacked(builder: KeyGenParameterSpec.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setStrongBox(builder)
    }

    @SuppressLint("UseRequiresApi") // private: every caller checks Build.VERSION first
    @TargetApi(Build.VERSION_CODES.P)
    private fun setStrongBox(builder: KeyGenParameterSpec.Builder) {
        builder.setIsStrongBoxBacked(true)
    }

    @SuppressLint("UseRequiresApi") // private: every caller checks Build.VERSION first
    @TargetApi(Build.VERSION_CODES.S)
    private fun securityLevelOf(info: KeyInfo): KeySecurityLevel = when (info.securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
        else -> KeySecurityLevel.UNKNOWN
    }
}
