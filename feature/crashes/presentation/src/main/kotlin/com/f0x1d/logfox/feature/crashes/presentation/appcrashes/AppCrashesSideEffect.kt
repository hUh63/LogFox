package com.f0x1d.logfox.feature.crashes.presentation.appcrashes
import com.f0x1d.logfox.core.tea.ReliableSideEffect

internal sealed interface AppCrashesSideEffect : ReliableSideEffect {
    // Business logic side effects
    data object LoadCrashes : AppCrashesSideEffect

    data class DeleteCrash(val crashId: Long) : AppCrashesSideEffect

    // UI side effects
    data class NavigateToCrashDetails(val crashId: Long) : AppCrashesSideEffect
}
