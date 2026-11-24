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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Store<State, Command, SideEffect>(
    initialState: State,
    private val reducer: Reducer<State, Command, SideEffect>,
    private val effectHandlers: List<EffectHandler<SideEffect, Command>>,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = { it.printStackTrace() },
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    // UI effects are one-shot events, so they must only be delivered while somebody is
    // collecting. A bare Channel buffers effects while the view is STOPPED and replays them
    // to the recreated view (duplicate navigation/toasts); without a subscriber the effect
    // is dropped instead. On a fresh subscription we also drain anything buffered by an
    // exiting subscriber so nothing from the previous view leaks into the new one.
    private val _sideEffects = Channel<SideEffect>(capacity = SIDE_EFFECTS_BUFFER)
    private val activeCollectors = AtomicInteger(0)
    val sideEffects: Flow<SideEffect> = flow {
        if (activeCollectors.incrementAndGet() == 1) {
            while (true) {
                val buffered = _sideEffects.tryReceive()
                if (buffered.isFailure || buffered.getOrNull() == null) break
            }
        }

        try {
            emitAll(_sideEffects.receiveAsFlow())
        } finally {
            activeCollectors.decrementAndGet()
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
            _sideEffects.trySend(sideEffect)

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

    fun cancel() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()

        _sideEffects.close()
        effectHandlers.forEach { it.close() }
    }

    private companion object {
        const val SIDE_EFFECTS_BUFFER = 64
    }
}
