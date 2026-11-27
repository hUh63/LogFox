package com.f0x1d.logfox.core.tea

/**
 * A [ReliableSideEffect] that also survives process death.
 *
 * Reliable effects wait in the queue while no view is listening, which covers rotations and
 * "user pressed home". They used to live in memory only, so when Android killed the process in
 * the background every queued result (an export failure, "the file was saved") disappeared
 * without a trace.
 *
 * Implementations describe themselves with plain strings, and the screen restores them with
 * [SideEffectPersistence] + its own `restorePersistedSideEffect` mapping. Only put effects here
 * that still make sense after a cold start — business operations are re-run by loading state,
 * so persisting them would duplicate work.
 */
interface PersistableSideEffect : ReliableSideEffect {
    /** Stable identifier; one pending effect per key (a newer one replaces the older). */
    val persistedKey: String

    val persistedArgs: Map<String, String> get() = emptyMap()
}

data class PersistedSideEffect(
    val key: String,
    val args: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L,
)

interface SideEffectPersistence {
    fun loadAll(): List<PersistedSideEffect>

    fun replaceAll(items: List<PersistedSideEffect>)
}

/**
 * Installed by the application (which owns the Context); the TEA module must stay
 * Android-free, so it cannot create a SharedPreferences-backed instance itself.
 */
object SideEffectPersistenceProvider {

    @Volatile
    private var factory: ((String) -> SideEffectPersistence)? = null

    private val instances = HashMap<String, SideEffectPersistence>()

    fun install(factory: (namespace: String) -> SideEffectPersistence) {
        synchronized(instances) {
            this.factory = factory
            instances.clear()
        }
    }

    fun forNamespace(namespace: String): SideEffectPersistence? {
        val create = factory ?: return null

        return synchronized(instances) {
            instances.getOrPut(namespace) { create(namespace) }
        }
    }
}
