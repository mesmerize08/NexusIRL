package io.github.mesmerize08.nexusirl.core.secrets

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

/**
 * Creates the device [SecretStore]: an [AeadSecretStore] whose Tink AES-256-GCM keyset is wrapped
 * by a non-exportable Android Keystore master key (docs/PORT_PLAN.md §9.3). The deprecated
 * `EncryptedSharedPreferences` is intentionally not used.
 */
object KeystoreSecretStore {
    private const val KEYSET_NAME = "secrets_keyset"
    private const val KEYSET_PREFERENCES = "io.github.mesmerize08.nexusirl.secrets.keyset"
    private const val MASTER_KEY_URI = "android-keystore://io.github.mesmerize08.nexusirl.secrets.master"
    private const val FILE_NAME = "secrets.properties"

    fun create(context: Context): SecretStore {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        return AeadSecretStore(file, createAead(context))
    }

    private fun createAead(context: Context): Aead {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFERENCES)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
