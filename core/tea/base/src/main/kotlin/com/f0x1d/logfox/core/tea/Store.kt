package com.f0x1d.logfox.core.tea

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Store<State, Command, SideEffect>(
    initialState: State,
    private val reducer: Reducer<State, Command, SideEffect>,
    private val effectHandlers: List<EffectHandler<SideEffect, Command>>,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = { it.printStackTrace() },
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _sideEffects = Channel<SideEffect>(capacity = Channel.UNLIMITED)
    val sideEffects: Flow<SideEffect> = _sideEffects.receiveAsFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    fun send(command: Command) {
        val result = try {
            reducer.reduce(_state.value, command)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable

            // A single bad command must not take the whole screen down. The reducer is pure,
            // so we can keep the previous state and just report the failure.
            onError(throwable)
            return
        }

        _state.value = result.state

        result.sideEffects.forEach { sideEffect ->
            _sideEffects.trySend(sideEffect)

            effectHandlers.forEach { handler ->
                val jobId = UUID.randomUUID().toString()
                val job = scope.launch {
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
            }
        }
    }

    fun cancel() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()

        _sideEffects.close()
        effectHandlers.forEach { it.close() }
    }
}
