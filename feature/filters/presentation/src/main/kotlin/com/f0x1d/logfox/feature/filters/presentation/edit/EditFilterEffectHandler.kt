package com.f0x1d.logfox.feature.filters.presentation.edit

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.filters.api.domain.CreateFilterUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ExportFiltersToUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.GetFilterByIdFlowUseCase
import com.f0x1d.logfox.feature.filters.api.domain.UpdateFilterUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

internal class EditFilterEffectHandler @Inject constructor(
    private val getFilterByIdFlowUseCase: GetFilterByIdFlowUseCase,
    private val createFilterUseCase: CreateFilterUseCase,
    private val updateFilterUseCase: UpdateFilterUseCase,
    private val exportFiltersToUriUseCase: ExportFiltersToUriUseCase,
) : EffectHandler<EditFilterSideEffect, EditFilterCommand> {

    // Auto-save fires on every keystroke and every effect runs in its own coroutine, so two
    // saves can overlap and an older snapshot can win the race and overwrite newer text.
    // Only the newest snapshot is allowed to be written, and writes are serialized.
    private val saveMutex = Mutex()
    private val saveVersion = AtomicLong(0L)

    // Auto-save used to write on every keystroke: typing 30 characters meant 30 Room
    // UPDATEs and 30 state emissions. Snapshots are coalesced now and only the latest one
    // is written, with an immediate flush when the screen is closed.
    private val pendingSave = AtomicReference<EditFilterSideEffect.SaveFilter?>(null)
    private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val debounceMutex = Mutex()
    private var debounceJob: Job? = null

    // Two fast "back" taps used to run SaveAndClose twice: the filter was created twice and
    // Close was emitted twice, popping both the editor and the filter list
    private val closeRequested = AtomicBoolean(false)

    override suspend fun handle(
        effect: EditFilterSideEffect,
        onCommand: suspend (EditFilterCommand) -> Unit,
    ) {
        when (effect) {
            is EditFilterSideEffect.LoadFilter -> {
                getFilterByIdFlowUseCase(effect.filterId ?: -1L)
                    .distinctUntilChanged()
                    .take(1)
                    .collect { filter ->
                        if (filter != null) {
                            onCommand(EditFilterCommand.FilterLoaded(filter))
                        }
                    }
            }

            is EditFilterSideEffect.SaveAndClose -> {
                if (!closeRequested.compareAndSet(false, true)) return

                // Write the final snapshot right away, skipping the debounce window
                pendingSave.set(null)
                save(effect.save.copy(), isFinal = true)

                onCommand(EditFilterCommand.SavedAndClose)
            }

            is EditFilterSideEffect.SaveFilter -> {
                if (closeRequested.get()) return

                pendingSave.set(effect)
                scheduleDebouncedSave()
            }

            is EditFilterSideEffect.ExportFilter -> {
                exportFiltersToUriUseCase(effect.uri, listOfNotNull(effect.filter))
            }

            // UI side effects - handled by Fragment
            is EditFilterSideEffect.NavigateToAppPicker -> Unit
            is EditFilterSideEffect.Close -> Unit
        }
    }

    private fun scheduleDebouncedSave() {
        debounceScope.launch {
            debounceMutex.withLock {
                debounceJob?.cancel()

                val job = launch {
                    delay(SAVE_DEBOUNCE_MS)
                    flushPendingSave()
                }

                debounceJob = job
            }
        }
    }

    private suspend fun flushPendingSave() {
        val effect = pendingSave.getAndSet(null) ?: return

        save(effect)
    }

    private suspend fun save(effect: EditFilterSideEffect.SaveFilter, isFinal: Boolean = false) {
        val version = saveVersion.incrementAndGet()

        saveMutex.withLock {
            // A newer snapshot is already queued up, writing this one would only
            // overwrite fresher text with older text
            if (version != saveVersion.get()) return@withLock

            // A debounced flush that grabbed the old snapshot just before "save and close"
            // could otherwise win the version race and overwrite the final text
            if (!isFinal && closeRequested.get()) return@withLock

            if (effect.filter == null) {
                createFilterUseCase(
                    name = effect.name,
                    including = effect.including,
                    enabled = effect.enabled,
                    enabledLogLevels = effect.enabledLogLevels,
                    uid = effect.uid,
                    pid = effect.pid,
                    tid = effect.tid,
                    packageName = effect.packageName,
                    tag = effect.tag,
                    content = effect.content,
                    tagRegex = effect.tagRegex,
                    contentRegex = effect.contentRegex,
                )
            } else {
                updateFilterUseCase(
                    userFilter = effect.filter,
                    name = effect.name,
                    including = effect.including,
                    enabled = effect.enabled,
                    enabledLogLevels = effect.enabledLogLevels,
                    uid = effect.uid,
                    pid = effect.pid,
                    tid = effect.tid,
                    packageName = effect.packageName,
                    tag = effect.tag,
                    content = effect.content,
                    tagRegex = effect.tagRegex,
                    contentRegex = effect.contentRegex,
                )
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }
}
