package com.f0x1d.logfox.feature.crashes.presentation.details

import com.f0x1d.logfox.feature.crashes.api.model.AppCrash

internal data class CrashDetailsState(
    val crash: AppCrash?,
    val crashLog: String?,
    val originalCrashLog: String?,
    val blacklisted: Boolean?,
    val wrapCrashLogLines: Boolean,
    val useSeparateNotificationsChannelsForCrashes: Boolean,
    val textSize: Int,
    val searchQuery: String,
    val searchMatchRanges: List<IntRange>,
)
