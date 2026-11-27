package com.f0x1d.logfox.presentation
import com.f0x1d.logfox.core.tea.ReliableSideEffect

internal sealed interface MainSideEffect : ReliableSideEffect {
    data object StartLoggingServiceIfNeeded : MainSideEffect
    data object SaveNotificationsPermissionAsked : MainSideEffect
    data object HandleBackExit : MainSideEffect

    data object FinishActivity : MainSideEffect
    data object OpenSetup : MainSideEffect
}
