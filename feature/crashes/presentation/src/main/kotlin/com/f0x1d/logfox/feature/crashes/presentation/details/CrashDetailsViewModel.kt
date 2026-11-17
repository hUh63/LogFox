package com.f0x1d.logfox.feature.crashes.presentation.details

import com.f0x1d.logfox.core.tea.BaseStoreViewModel
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.GetUseSeparateNotificationsChannelsForCrashesUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.GetWrapCrashLogLinesUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetLogsTextSizeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class CrashDetailsViewModel @Inject constructor(
    reducer: CrashDetailsReducer,
    effectHandler: CrashDetailsEffectHandler,
    blacklistEffectHandler: CrashDetailsBlacklistEffectHandler,
    viewStateMapper: CrashDetailsViewStateMapper,
    getWrapCrashLogLinesUseCase: GetWrapCrashLogLinesUseCase,
    getUseSeparateNotificationsChannelsForCrashesUseCase: GetUseSeparateNotificationsChannelsForCrashesUseCase,
    getLogsTextSizeUseCase: GetLogsTextSizeUseCase,
) : BaseStoreViewModel<CrashDetailsViewState, CrashDetailsState, CrashDetailsCommand, CrashDetailsSideEffect>(
    initialState = CrashDetailsState(
        crash = null,
        crashLog = null,
        originalCrashLog = null,
        blacklisted = null,
        wrapCrashLogLines = getWrapCrashLogLinesUseCase(),
        textSize = getLogsTextSizeUseCase(),
        useSeparateNotificationsChannelsForCrashes = getUseSeparateNotificationsChannelsForCrashesUseCase(),
        searchQuery = "",
        searchMatchRanges = emptyList(),
    ),
    reducer = reducer,
    effectHandlers = listOf(effectHandler, blacklistEffectHandler),
    viewStateMapper = viewStateMapper,
    initialSideEffects = listOf(
        CrashDetailsSideEffect.LoadCrash,
        CrashDetailsSideEffect.ObservePreferences,
    ),
)
