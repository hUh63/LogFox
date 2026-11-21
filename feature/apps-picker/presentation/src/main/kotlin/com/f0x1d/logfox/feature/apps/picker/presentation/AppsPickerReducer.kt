package com.f0x1d.logfox.feature.apps.picker.presentation

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import javax.inject.Inject

internal class AppsPickerReducer @Inject constructor() : Reducer<AppsPickerState, AppsPickerCommand, AppsPickerSideEffect> {

    override fun reduce(
        state: AppsPickerState,
        command: AppsPickerCommand,
    ): ReduceResult<AppsPickerState, AppsPickerSideEffect> = when (command) {
        is AppsPickerCommand.BackPressed -> {
            if (state.searchActive) {
                state.copy(searchActive = false).noSideEffects()
            } else {
                state.withSideEffects(AppsPickerSideEffect.PopBackStack)
            }
        }

        is AppsPickerCommand.SearchActiveChanged -> {
            state.copy(searchActive = command.active).noSideEffects()
        }

        is AppsPickerCommand.QueryChanged -> {
            state.copy(query = command.query)
                .withSideEffects(
                    AppsPickerSideEffect.FilterApps(
                        query = command.query,
                        apps = state.apps,
                    ),
                )
        }

        is AppsPickerCommand.AppsLoaded -> {
            val loaded = state.copy(
                apps = command.apps,
                searchedApps = command.apps,
                isLoading = false,
            )

            // A query typed while the package list was still loading must survive: blindly
            // overwriting searchedApps with the full list showed unrelated apps while the
            // search box kept the text, so the user could pick the wrong package
            if (loaded.query.isNullOrBlank()) {
                loaded.noSideEffects()
            } else {
                loaded.withSideEffects(
                    AppsPickerSideEffect.FilterApps(
                        query = loaded.query.orEmpty(),
                        apps = command.apps,
                    ),
                )
            }
        }

        is AppsPickerCommand.SearchedAppsUpdated -> {
            // Results are computed off the main dispatcher and can complete out of order.
            // Without the query echoed back, a slow result for an older query replaced the
            // list while the search box already showed the newer text - the user could then
            // select a package that does not match what was typed.
            if (command.query != state.query) {
                state.noSideEffects()
            } else {
                state.copy(searchedApps = command.apps).noSideEffects()
            }
        }

        is AppsPickerCommand.AppClicked -> {
            state.withSideEffects(AppsPickerSideEffect.HandleAppSelection(command.app))
        }
    }
}
