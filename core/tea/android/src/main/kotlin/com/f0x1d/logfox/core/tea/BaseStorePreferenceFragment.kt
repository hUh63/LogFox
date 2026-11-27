package com.f0x1d.logfox.core.tea

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Base PreferenceFragment for TEA architecture.
 *
 * Features:
 * - Automatic state collection via [render]
 * - Automatic side effect handling via [handleSideEffect]
 * - Convenience [send] method for dispatching commands
 *
 * @param ViewState presentation state type rendered by this fragment
 * @param State internal domain state type managed by the store
 * @param Command user actions or system events that can modify state
 * @param SideEffect side effects for UI actions (navigation, toasts) or business logic
 * @param VM ViewModel type extending [BaseStoreViewModel]
 */
abstract class BaseStorePreferenceFragment<
    ViewState,
    State,
    Command,
    SideEffect,
    VM : BaseStoreViewModel<ViewState, State, Command, SideEffect>,
    > : PreferenceFragmentCompat() {

    /**
     * The ViewModel for this fragment. Should be obtained via `by viewModels()` or similar.
     */
    protected abstract val viewModel: VM

    /**
     * Render state to UI. Called on every state change.
     * Must be idempotent - same state = same UI.
     */
    abstract fun render(state: ViewState)

    /**
     * Handle side effects (navigation, snackbars, etc.)
     * Called for ALL side effects - ignore those not relevant to UI.
     * Business logic side effects are handled by EffectHandlers.
     */
    abstract fun handleSideEffect(sideEffect: SideEffect)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // One throwing render/handler used to cancel these collectors, and the
                // screen silently stopped updating (or stopped reacting to every action)
                // for the rest of its lifetime
                launch { viewModel.state.collect { state -> guard { render(state) } } }
                launch { viewModel.sideEffects.collect { effect -> guard { handleSideEffect(effect) } } }
            }
        }
    }

    /**
     * Convenience method to send commands to ViewModel.
     */
    protected fun send(command: Command) {
        viewModel.send(command)
    }

    /**
     * Dialog callbacks outlive the fragment instance (a rotation re-creates it), so they
     * resolve the CURRENT instance through the context and submit through this method.
     * Calling send() on the captured old instance throws: it is already detached.
     */
    fun submit(command: Command) {
        viewModel.send(command)
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable

            throwable.printStackTrace()
        }
    }
}
