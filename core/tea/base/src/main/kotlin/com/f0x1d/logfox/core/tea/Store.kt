package com.f0x1d.logfox.core.tea

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Store<State, Command, SideEffect>(
    initialState: State,
    private val reducer: Reducer<State, Command, SideEffect>,
    private val effectHandlers: List<EffectHandler<SideEffect, Command>>,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = { it.printStackTrace() },
    private val persistence: SideEffectPersistence? = null,
    private val restorePersisted: ((PersistedSideEffect) -> SideEffect?)? = null,
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    private data class Envelope<T>(val effect: T, val reliable: Boolean)

    private val uiLock = Any()

    // Effects are delivered at most once: an effect leaves the queue before it is emitted,
    // so the recreated view never repeats a navigation/toast that the old view already ran.
    // Reliable effects (ReliableSideEffect) wait in the queue until a view consumes them;
    // volatile ones are dropped while nobody listens — that is what prevents the stale
    // batch of one-shot events that used to be replayed after a rotation.
    private val pendingEffects = ArrayDeque<Envelope<SideEffect>>()
    private val uiWakeUp = Channel<Unit>(Channel.UNLIMITED)
    private var activeCollectors = 0

    // Mirrors the reliable effects that implement PersistableSideEffect. Written on every
    // change so a process death while the view is stopped cannot lose the result the user is
    // waiting for. Cleared when nobody listens for too long (see MAX_PERSISTED_AGE_MS).
    private val persisted = ArrayList<PersistedSideEffect>()
    private var persistedRestored = false

    init {
        runCatching {
            val now = System.currentTimeMillis()

            persistence?.loadAll()
                ?.filter { now - it.savedAt <= MAX_PERSISTED_AGE_MS }
                ?.takeLast(MAX_PERSISTED_EFFECTS)
                ?.let { persisted.addAll(it) }
        }.onFailure(onError)

        persistLocked()
    }

    val sideEffects: Flow<SideEffect> = flow {
        synchronized(uiLock) {
            activeCollectors++

            // Anything left by a previous subscription is stale for the volatile kind
            pendingEffects.removeAll { !it.reliable }

            // Restored once per process, after the screen is actually on screen: the effect
            // handlers of this ViewModel are alive by then, and the navigation controller is
            // ready to act on a navigation effect.
            if (!persistedRestored) {
                persistedRestored = true

                val restored: List<Pair<PersistedSideEffect, Envelope<SideEffect>>> =
                    persisted.mapNotNull { item ->
                        // Items this screen cannot rebuild (older version, removed feature)
                        // are dropped instead of being kept around until the TTL expires
                        val effect: SideEffect = runCatching {
                            restorePersisted?.invoke(item)
                        }.getOrNull() ?: return@mapNotNull null

                        item to Envelope(effect, reliable = true)
                    }

                persisted.clear()
                restored.forEach { (item, envelope) ->
                    persisted.add(item)
                    pendingEffects.addLast(envelope)
                }

                persistLocked()
            }
        }

        wakeUpCollectors()

        try {
            while (true) {
                val envelope = synchronized(uiLock) { pendingEffects.pollFirst() }

                if (envelope == null) {
                    // receive() throws when cancel() closes the channel; the flow must end
                    // quietly instead of failing the collector
                    if (uiWakeUp.receiveCatching().isClosed) break

                    continue
                }

                forgetPersisted(envelope.effect)

                emit(envelope.effect)
            }
        } finally {
            synchronized(uiLock) {
                activeCollectors--

                if (activeCollectors == 0) {
                    pendingEffects.removeAll { !it.reliable }
                }
            }
        }
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    private val sendLock = Any()

    fun send(command: Command) {
        // send() runs from the UI and from effect handlers that may resume on another
        // dispatcher. Reading _state.value, reducing and writing it back is not atomic, so
        // two concurrent commands could both start from the same state and the later write
        // discarded the earlier command's state change together with its side effects
        val result = synchronized(sendLock) {
            val reduced = try {
                reducer.reduce(_state.value, command)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable

                // A single bad command must not take the whole screen down. The reducer is
                // pure, so we can keep the previous state and just report the failure.
                onError(throwable)
                return
            }

            _state.value = reduced.state

            reduced
        }

        result.sideEffects.forEach { sideEffect ->
            enqueueUiEffect(sideEffect)

            effectHandlers.forEach { handler ->
                val jobId = UUID.randomUUID().toString()
                // LAZY: with Main.immediate a non-suspending handler ran to completion
                // before the job was registered, so its finally removed nothing and the
                // finished job stayed in `jobs` forever
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        handler.handle(sideEffect) { cmd ->
                            withContext(Dispatchers.Main) {
                                send(cmd)
                            }
                        }
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable

                        // Without this a single failing effect silently killed the handler
                        // chain for the rest of the screen's lifetime.
                        onError(throwable)
                    } finally {
                        jobs.remove(jobId)
                    }
                }
                jobs[jobId] = job
                job.start()
            }
        }
    }

    private fun enqueueUiEffect(sideEffect: SideEffect) {
        val reliable = sideEffect is ReliableSideEffect

        synchronized(uiLock) {
            // A volatile effect with no listener is stale by definition
            if (!reliable && activeCollectors == 0) return

            if (pendingEffects.size >= MAX_PENDING_EFFECTS) {
                // Prefer dropping a volatile effect: reliable ones carry user intent.
                // java.util.ArrayDeque has no removeAt, so remove by identity
                val victim = pendingEffects.firstOrNull { !it.reliable }

                if (victim != null) {
                    pendingEffects.remove(victim)
                } else {
                    pendingEffects.removeFirst()
                }
            }

            pendingEffects.addLast(Envelope(sideEffect, reliable))

            if (reliable && sideEffect is PersistableSideEffect && persistence != null) {
                val item = PersistedSideEffect(
                    key = sideEffect.persistedKey,
                    args = sideEffect.persistedArgs,
                    savedAt = System.currentTimeMillis(),
                )

                if (item.key.isNotEmpty()) {
                    persisted.removeAll { it.key == item.key }
                    persisted.add(item)

                    while (persisted.size > MAX_PERSISTED_EFFECTS) {
                        persisted.removeAt(0)
                    }

                    persistLocked()
                }
            }
        }

        wakeUpCollectors()
    }

    private fun forgetPersisted(effect: SideEffect) {
        if (persistence == null) return
        if (effect !is PersistableSideEffect) return

        synchronized(uiLock) {
            if (persisted.removeAll { it.key == effect.persistedKey }) {
                persistLocked()
            }
        }
    }

    private fun persistLocked() {
        val storage = persistence ?: return

        runCatching { storage.replaceAll(persisted.toList()) }.onFailure(onError)
    }

    private fun wakeUpCollectors() {
        // One wake-up per listener: a single token would leave the others sleeping
        val collectors = synchronized(uiLock) { activeCollectors }

        repeat(collectors) { uiWakeUp.trySend(Unit) }
    }

    fun cancel() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()

        uiWakeUp.close()
        effectHandlers.forEach { it.close() }
    }

    private companion object {
        const val MAX_PENDING_EFFECTS = 128
        const val MAX_PERSISTED_EFFECTS = 8
        const val MAX_PERSISTED_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
