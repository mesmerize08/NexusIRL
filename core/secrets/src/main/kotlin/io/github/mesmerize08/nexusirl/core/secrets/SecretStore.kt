package io.github.mesmerize08.nexusirl.core.secrets

import java.util.UUID

/**
 * Opaque reference to a secret held in a [SecretStore]. Settings JSON stores this reference in
 * place of the secret itself (docs/PORT_PLAN.md §9.3), e.g. `rtmp://host/app/{secret:<uuid>}`.
 */
@JvmInline
value class SecretRef(val id: String) {
    /** The placeholder text written into settings in place of the secret. */
    val placeholder: String
        get() = "{secret:$id}"

    companion object {
        private val placeholderPattern = Regex("""\{secret:([0-9a-fA-F-]{36})\}""")

        fun random(): SecretRef = SecretRef(UUID.randomUUID().toString())

        /** Finds every secret placeholder in [text]. */
        fun findAll(text: String): List<SecretRef> = placeholderPattern.findAll(text).map { SecretRef(it.groupValues[1]) }.toList()

        /** Replaces every placeholder in [text] with its value from [store] (in memory only). */
        fun resolve(text: String, store: SecretStore): String = placeholderPattern.replace(text) { match ->
            store.get(SecretRef(match.groupValues[1]))
                ?: throw MissingSecretException(SecretRef(match.groupValues[1]))
        }
    }
}

class MissingSecretException(val ref: SecretRef) : IllegalStateException("Secret ${ref.id} is missing")

/**
 * Stores secrets (stream keys, passwords, tokens; inventory in docs/PORT_PLAN.md §9.2) outside the
 * settings document. Implementations must never log or export values.
 */
interface SecretStore {
    /** Stores [value], under [ref] when given (replacing any previous value), and returns its ref. */
    fun put(value: String, ref: SecretRef = SecretRef.random()): SecretRef

    fun get(ref: SecretRef): String?

    fun remove(ref: SecretRef)

    /** All stored values, for exact-match masking in [Redactor]. Never display or export these. */
    fun values(): Collection<String>

    /** All stored refs, so unreferenced secrets can be removed. */
    fun refs(): Collection<SecretRef>
}

/** Non-persistent store for tests and previews. */
class InMemorySecretStore : SecretStore {
    private val secrets = LinkedHashMap<String, String>()

    @Synchronized
    override fun put(value: String, ref: SecretRef): SecretRef {
        secrets[ref.id] = value
        return ref
    }

    @Synchronized
    override fun get(ref: SecretRef): String? = secrets[ref.id]

    @Synchronized
    override fun remove(ref: SecretRef) {
        secrets.remove(ref.id)
    }

    @Synchronized
    override fun values(): Collection<String> = secrets.values.toList()

    @Synchronized
    override fun refs(): Collection<SecretRef> = secrets.keys.map { SecretRef(it) }
}
