package com.f0x1d.logfox.core.tea

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseStoreViewModel<ViewState, State, Command, SideEffect>(
    initialState: State,
    reducer: Reducer<State, Command, SideEffect>,
    effectHandlers: List<EffectHandler<SideEffect, Command>>,
    viewStateMapper: ViewStateMapper<State, ViewState>,
    initialSideEffects: List<SideEffect> = emptyList(),
    viewStateMappingDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    sideEffectPersistenceNamespace: String? = null,
) : ViewModel() {

    // Passed in the constructor (not an overridable property) because the Store is built
    // while the subclass is still being constructed: an override would read unset fields
    private val store = Store(
        initialState = initialState,
        reducer = reducer,
        effectHandlers = effectHandlers,
        scope = viewModelScope,
        persistence = SideEffectPersistenceProvider.forNamespace(
            sideEffectPersistenceNamespace ?: javaClass.name,
        ),
        restorePersisted = { persisted -> restorePersistedSideEffect(persisted) },
    )

    /**
     * Rebuilds a [PersistableSideEffect] that was queued before the process was killed.
     * Return null to drop it. Called once, after the screen subscribes for the first time,
     * so the ViewModel's dependencies are fully initialised.
     */
    protected open fun restorePersistedSideEffect(persisted: PersistedSideEffect): SideEffect? = null

    val state: StateFlow<ViewState> = store.state
        .map { viewStateMapper.map(it) }
        .flowOn(viewStateMappingDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = viewStateMapper.map(initialState),
        )
    val sideEffects: Flow<SideEffect> = store.sideEffects

    init {
        initialSideEffects.forEach { effect ->
            effectHandlers.forEach { handler ->
                viewModelScope.launch {
                    try {
                        handler.handle(effect) { cmd ->
                            withContext(Dispatchers.Main.immediate) {
                                send(cmd)
                            }
                        }
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable

                        // These run outside Store.send, so a failure here (e.g.
                        // ForegroundServiceStartNotAllowedException when the app is in
                        // background) escaped viewModelScope and crashed the process
                        throwable.printStackTrace()
                    }
                }
            }
        }
    }

    fun send(command: Command) {
        store.send(command)
    }

    override fun onCleared() {
        super.onCleared()
        store.cancel()
    }
}
