package com.f0x1d.logfox.feature.crashes.presentation.details

import com.f0x1d.logfox.core.tea.ViewStateMapper
import javax.inject.Inject

internal class CrashDetailsViewStateMapper @Inject constructor() : ViewStateMapper<CrashDetailsState, CrashDetailsViewState> {
    override fun map(state: CrashDetailsState) = CrashDetailsViewState(
        crash = state.crash,
        crashLog = state.crashLog,
        blacklisted = state.blacklisted,
        wrapCrashLogLines = state.wrapCrashLogLines,
        useSeparateNotificationsChannelsForCrashes = state.useSeparateNotificationsChannelsForCrashes,
        textSize = state.textSize,
        mappingAvailable = state.mappingAvailable,
        searchQuery = state.searchQuery,
        searchMatchRanges = state.searchMatchRanges,
        loaded = state.loaded,
    )
}
