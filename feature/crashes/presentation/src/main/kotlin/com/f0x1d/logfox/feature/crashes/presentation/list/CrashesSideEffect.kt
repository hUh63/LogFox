package com.f0x1d.logfox.feature.crashes.presentation.list

import com.f0x1d.logfox.core.tea.PersistableSideEffect
import com.f0x1d.logfox.core.tea.ReliableSideEffect
import com.f0x1d.logfox.feature.preferences.api.CrashesSort

internal sealed interface CrashesSideEffect {
    // Business logic side effects
    data object LoadCrashes : CrashesSideEffect

    data class UpdateSearchQuery(val query: String) : CrashesSideEffect

    data class UpdateSortPreferences(val sortType: CrashesSort, val sortInReversedOrder: Boolean) : CrashesSideEffect

    data class DeleteCrashesByPackageName(val packageName: String) : CrashesSideEffect

    data class DeleteCrash(val crashId: Long) : CrashesSideEffect

    data object ClearAllCrashes : CrashesSideEffect

    data class CheckAppDisabled(val packageName: String, val disabled: Boolean?) : CrashesSideEffect

    // UI side effects - handled by Fragment
    data class NavigateToCrashDetails(val crashId: Long) : CrashesSideEffect, PersistableSideEffect {
        override val persistedKey get() = "navigate_to_crash_details"

        // Tapping a crash and immediately leaving must not lose the destination
        override val persistedArgs get() = mapOf("crashId" to crashId.toString())
    }
    data class NavigateToAppCrashes(val packageName: String, val appName: String?) : CrashesSideEffect, ReliableSideEffect
    data object NavigateToBlacklist : CrashesSideEffect, ReliableSideEffect
}
