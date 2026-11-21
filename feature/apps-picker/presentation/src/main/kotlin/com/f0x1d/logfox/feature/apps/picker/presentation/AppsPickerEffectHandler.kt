package com.f0x1d.logfox.feature.apps.picker.presentation

import com.f0x1d.logfox.core.di.DefaultDispatcher
import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.apps.picker.api.domain.FilterAppsUseCase
import com.f0x1d.logfox.feature.apps.picker.api.domain.GetInstalledAppsUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class AppsPickerEffectHandler @Inject constructor(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val filterAppsUseCase: FilterAppsUseCase,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : EffectHandler<AppsPickerSideEffect, AppsPickerCommand> {

    override suspend fun handle(
        effect: AppsPickerSideEffect,
        onCommand: suspend (AppsPickerCommand) -> Unit,
    ) {
        when (effect) {
            is AppsPickerSideEffect.LoadApps -> {
                // A failed PackageManager call used to escape, so AppsLoaded was never sent
                // and isLoading stayed true: the screen showed a spinner forever with no
                // way out. Always resolve, an empty list at worst.
                val apps = runCatching {
                    withContext(defaultDispatcher) {
                        getInstalledAppsUseCase()
                    }
                }.getOrElse { throwable ->
                    throwable.printStackTrace()
                    emptyList()
                }
                onCommand(AppsPickerCommand.AppsLoaded(apps))
            }

            is AppsPickerSideEffect.FilterApps -> {
                val filteredApps = runCatching {
                    withContext(defaultDispatcher) {
                        filterAppsUseCase(effect.query, effect.apps)
                    }
                }.getOrElse { throwable ->
                    throwable.printStackTrace()
                    emptyList()
                }
                onCommand(AppsPickerCommand.SearchedAppsUpdated(query = effect.query, apps = filteredApps))
            }

            // UI side effects are handled by Fragment
            is AppsPickerSideEffect.PopBackStack -> Unit
            is AppsPickerSideEffect.HandleAppSelection -> Unit
        }
    }
}
