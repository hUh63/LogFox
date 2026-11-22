package com.f0x1d.logfox.feature.crashes.presentation.list

import com.f0x1d.logfox.core.di.DefaultDispatcher
import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.crashes.api.domain.GetAllCrashesFlowUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.GetCrashesSearchQueryFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.GetCrashesSortReversedOrderFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.GetCrashesSortTypeFlowUseCase
import com.f0x1d.logfox.feature.crashes.api.model.AppCrashesCount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class CrashesSearchEffectHandler @Inject constructor(
    private val getAllCrashesFlowUseCase: GetAllCrashesFlowUseCase,
    private val getCrashesSearchQueryFlowUseCase: GetCrashesSearchQueryFlowUseCase,
    private val getCrashesSortTypeFlowUseCase: GetCrashesSortTypeFlowUseCase,
    private val getCrashesSortReversedOrderFlowUseCase: GetCrashesSortReversedOrderFlowUseCase,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : EffectHandler<CrashesSideEffect, CrashesCommand> {

    override suspend fun handle(
        effect: CrashesSideEffect,
        onCommand: suspend (CrashesCommand) -> Unit,
    ) {
        when (effect) {
            is CrashesSideEffect.LoadCrashes -> {
                // Results used to ignore the sort setting entirely: the main list was
                // ordered by the chosen key while search results stayed ordered by time
                combine(
                    getAllCrashesFlowUseCase().distinctUntilChanged(),
                    getCrashesSearchQueryFlowUseCase(),
                    getCrashesSortTypeFlowUseCase(),
                    getCrashesSortReversedOrderFlowUseCase(),
                ) { crashes, query, sortType, sortInReversedOrder ->
                    crashes.filter { crash ->
                        crash.packageName.contains(query, ignoreCase = true) ||
                            crash.appName?.contains(query, ignoreCase = true) == true
                    }.map { AppCrashesCount(it) }
                        .let(sortType.sorter)
                        .let { result -> if (sortInReversedOrder) result.asReversed() else result }
                }
                    .distinctUntilChanged()
                    .flowOn(defaultDispatcher)
                    .collect { searchedCrashes ->
                        onCommand(CrashesCommand.SearchedCrashesLoaded(searchedCrashes))
                    }
            }

            else -> {
                // Handled by other effect handlers
            }
        }
    }
}
