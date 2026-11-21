package com.f0x1d.logfox.feature.logging.presentation.search

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.logging.api.domain.GetCaseSensitiveFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetQueryFlowUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateCaseSensitiveUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateQueryUseCase
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

internal class SearchLogsEffectHandler @Inject constructor(
    private val getQueryFlowUseCase: GetQueryFlowUseCase,
    private val updateQueryUseCase: UpdateQueryUseCase,
    private val getCaseSensitiveFlowUseCase: GetCaseSensitiveFlowUseCase,
    private val updateCaseSensitiveUseCase: UpdateCaseSensitiveUseCase,
) : EffectHandler<SearchLogsSideEffect, SearchLogsCommand> {

    override suspend fun handle(
        effect: SearchLogsSideEffect,
        onCommand: suspend (SearchLogsCommand) -> Unit,
    ) {
        when (effect) {
            is SearchLogsSideEffect.LoadQuery -> {
                getQueryFlowUseCase().collect { query ->
                    // Same guard as LogsEffectHandler: one failing emission must not kill
                    // the collection for the rest of the screen's life
                    runCatching { onCommand(SearchLogsCommand.QueryLoaded(query)) }
                        .onFailure { throwable ->
                            if (throwable is CancellationException) throw throwable
                            throwable.printStackTrace()
                        }
                }
            }

            is SearchLogsSideEffect.SaveQuery -> {
                updateQueryUseCase(effect.query)
            }

            is SearchLogsSideEffect.LoadCaseSensitive -> {
                getCaseSensitiveFlowUseCase().collect { caseSensitive ->
                    runCatching { onCommand(SearchLogsCommand.CaseSensitiveLoaded(caseSensitive)) }
                        .onFailure { throwable ->
                            if (throwable is CancellationException) throw throwable
                            throwable.printStackTrace()
                        }
                }
            }

            is SearchLogsSideEffect.SaveCaseSensitive -> {
                updateCaseSensitiveUseCase(effect.caseSensitive)
            }

            // UI side effects - handled by Fragment
            is SearchLogsSideEffect.Dismiss -> Unit
        }
    }
}
