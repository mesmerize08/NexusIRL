package io.github.mesmerize08.nexusirl.core.secrets

import com.google.crypto.tink.Aead
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.Properties

/**
 * [SecretStore] persisted to [file], with every value encrypted by [aead]. The secret's id is
 * bound as associated data, so a ciphertext cannot be moved to another entry.
 *
 * On device the [aead] comes from [KeystoreSecretStore.create]: a Tink AES-256-GCM keyset that is
 * itself wrapped by a non-exportable Android Keystore key. Without the device's Keystore the file
 * is unreadable, which is also why it is excluded from backups.
 */
class AeadSecretStore(private val file: File, private val aead: Aead) : SecretStore {
    private val cache = LinkedHashMap<String, String>()

    init {
        load()
    }

    @Synchronized
    override fun put(value: String, ref: SecretRef): SecretRef {
        cache[ref.id] = value
        save()
        return ref
    }

    @Synchronized
    override fun get(ref: SecretRef): String? = cache[ref.id]

    @Synchronized
    override fun remove(ref: SecretRef) {
        if (cache.remove(ref.id) != null) {
            save()
        }
    }

    @Synchronized
    override fun values(): Collection<String> = cache.values.toList()

    @Synchronized
    override fun refs(): Collection<SecretRef> = cache.keys.map { SecretRef(it) }

    private fun load() {
        if (!file.exists()) {
            return
        }
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        for (id in properties.stringPropertyNames()) {
            val ciphertext = Base64.getDecoder().decode(properties.getProperty(id))
            try {
                cache[id] = aead.decrypt(ciphertext, id.toByteArray()).toString(Charsets.UTF_8)
            } catch (e: GeneralSecurityException) {
                // Undecryptable (e.g. Keystore key lost after restore). Drop it; the user re-enters it.
            }
        }
    }

    private fun save() {
        val properties = Properties()
        for ((id, value) in cache) {
            val ciphertext = aead.encrypt(value.toByteArray(Charsets.UTF_8), id.toByteArray())
            properties.setProperty(id, Base64.getEncoder().encodeToString(ciphertext))
        }
        val parent = file.absoluteFile.parentFile
        parent?.mkdirs()
        val temporary = File(parent, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, null) }
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                throw IOException("Failed to write ${file.name}")
            }
        }
    }
}
