package com.f0x1d.logfox.feature.filters.presentation.edit

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.filters.api.domain.CreateFilterUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ExportFiltersToUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.GetFilterByIdFlowUseCase
import com.f0x1d.logfox.feature.filters.api.domain.UpdateFilterUseCase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
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
                handle(EditFilterSideEffect.SaveFilter(
                    filter = effect.save.filter,
                    name = effect.save.name,
                    including = effect.save.including,
                    enabled = effect.save.enabled,
                    enabledLogLevels = effect.save.enabledLogLevels,
                    uid = effect.save.uid,
                    pid = effect.save.pid,
                    tid = effect.save.tid,
                    packageName = effect.save.packageName,
                    tag = effect.save.tag,
                    content = effect.save.content,
                    tagRegex = effect.save.tagRegex,
                    contentRegex = effect.save.contentRegex,
                )) { cmd -> onCommand(cmd) }

                onCommand(EditFilterCommand.SavedAndClose)
            }

            is EditFilterSideEffect.SaveFilter -> save(effect)

            is EditFilterSideEffect.ExportFilter -> {
                exportFiltersToUriUseCase(effect.uri, listOfNotNull(effect.filter))
            }

            // UI side effects - handled by Fragment
            is EditFilterSideEffect.NavigateToAppPicker -> Unit
            is EditFilterSideEffect.Close -> Unit
            is EditFilterSideEffect.ConfirmDiscard -> Unit
        }
    }

    private suspend fun save(effect: EditFilterSideEffect.SaveFilter) {
        val version = saveVersion.incrementAndGet()

        saveMutex.withLock {
            // A newer snapshot is already queued up, writing this one would only
            // overwrite fresher text with older text
            if (version != saveVersion.get()) return@withLock

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
}
