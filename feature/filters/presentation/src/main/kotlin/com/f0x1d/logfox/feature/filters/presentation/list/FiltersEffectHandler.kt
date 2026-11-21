package com.f0x1d.logfox.feature.filters.presentation.list

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.filters.api.domain.ClearAllFiltersUseCase
import com.f0x1d.logfox.feature.filters.api.domain.DeleteFilterUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ExportFiltersToUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.GetAllFiltersFlowUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ImportFiltersFromUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.SwitchFilterUseCase
import com.f0x1d.logfox.feature.strings.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import javax.inject.Inject

internal class FiltersEffectHandler @Inject constructor(
    private val getAllFiltersFlowUseCase: GetAllFiltersFlowUseCase,
    private val switchFilterUseCase: SwitchFilterUseCase,
    private val deleteFilterUseCase: DeleteFilterUseCase,
    private val clearAllFiltersUseCase: ClearAllFiltersUseCase,
    private val exportFiltersToUriUseCase: ExportFiltersToUriUseCase,
    private val importFiltersFromUriUseCase: ImportFiltersFromUriUseCase,
) : EffectHandler<FiltersSideEffect, FiltersCommand> {

    override suspend fun handle(
        effect: FiltersSideEffect,
        onCommand: suspend (FiltersCommand) -> Unit,
    ) {
        when (effect) {
            is FiltersSideEffect.LoadFilters -> {
                getAllFiltersFlowUseCase()
                    .distinctUntilChanged()
                    // A dirty row (e.g. an out-of-range level persisted by an older build)
                    // used to kill this collector for good, so the list never refreshed again
                    .retryWhen { throwable, attempt ->
                        if (throwable is CancellationException) throw throwable

                        throwable.printStackTrace()
                        delay(1_000L * (attempt + 1).coerceAtMost(10L))
                        true
                    }
                    .collect { filters ->
                        runCatching { onCommand(FiltersCommand.FiltersLoaded(filters)) }
                            .onFailure { throwable ->
                                if (throwable is CancellationException) throw throwable
                                throwable.printStackTrace()
                            }
                    }
            }

            is FiltersSideEffect.ImportFilters -> importFiltersFromUriUseCase(effect.uri)
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    throwable.printStackTrace()
                    onCommand(FiltersCommand.ShowMessage(Strings.filters_import_failed))
                }

            is FiltersSideEffect.ExportAllFilters -> exportFiltersToUriUseCase(effect.uri, effect.filters)
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    throwable.printStackTrace()
                    onCommand(FiltersCommand.ShowMessage(Strings.filters_export_failed))
                }

            is FiltersSideEffect.SwitchFilter -> switchFilterUseCase(effect.filter, effect.checked)

            is FiltersSideEffect.DeleteFilter -> deleteFilterUseCase(effect.filter)

            is FiltersSideEffect.ClearAllFilters -> clearAllFiltersUseCase()

            // UI side effects - handled by Fragment
            is FiltersSideEffect.NavigateToEditFilter -> Unit
            is FiltersSideEffect.NavigateToCreateFilter -> Unit
            is FiltersSideEffect.ShowMessage -> Unit
        }
    }
}
