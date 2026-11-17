package com.f0x1d.logfox.feature.filters.presentation.edit

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.filters.api.domain.CreateFilterUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ExportFiltersToUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.GetFilterByIdFlowUseCase
import com.f0x1d.logfox.feature.filters.api.domain.UpdateFilterUseCase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import javax.inject.Inject

internal class EditFilterEffectHandler @Inject constructor(
    private val getFilterByIdFlowUseCase: GetFilterByIdFlowUseCase,
    private val createFilterUseCase: CreateFilterUseCase,
    private val updateFilterUseCase: UpdateFilterUseCase,
    private val exportFiltersToUriUseCase: ExportFiltersToUriUseCase,
) : EffectHandler<EditFilterSideEffect, EditFilterCommand> {

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

            is EditFilterSideEffect.SaveFilter -> {
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

            is EditFilterSideEffect.ExportFilter -> {
                exportFiltersToUriUseCase(effect.uri, listOfNotNull(effect.filter))
            }

            // UI side effects - handled by Fragment
            is EditFilterSideEffect.NavigateToAppPicker -> Unit
            is EditFilterSideEffect.Close -> Unit
            is EditFilterSideEffect.ConfirmDiscard -> Unit
        }
    }
}
