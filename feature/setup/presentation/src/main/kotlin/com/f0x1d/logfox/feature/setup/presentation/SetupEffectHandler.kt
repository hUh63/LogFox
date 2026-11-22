package com.f0x1d.logfox.feature.setup.presentation

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.setup.api.domain.CheckReadLogsPermissionUseCase
import com.f0x1d.logfox.feature.setup.api.domain.CopyToClipboardUseCase
import com.f0x1d.logfox.feature.setup.api.domain.ExecuteGrantViaRootUseCase
import com.f0x1d.logfox.feature.setup.api.domain.ExecuteGrantViaShizukuUseCase
import com.f0x1d.logfox.feature.setup.api.domain.GetAdbCommandUseCase
import com.f0x1d.logfox.feature.setup.api.domain.SelectTerminalUseCase
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal class SetupEffectHandler @Inject constructor(
    private val executeGrantViaRootUseCase: ExecuteGrantViaRootUseCase,
    private val executeGrantViaShizukuUseCase: ExecuteGrantViaShizukuUseCase,
    private val selectTerminalUseCase: SelectTerminalUseCase,
    private val checkReadLogsPermissionUseCase: CheckReadLogsPermissionUseCase,
    private val copyToClipboardUseCase: CopyToClipboardUseCase,
    private val getAdbCommandUseCase: GetAdbCommandUseCase,
) : EffectHandler<SetupSideEffect, SetupCommand> {

    override suspend fun handle(
        effect: SetupSideEffect,
        onCommand: suspend (SetupCommand) -> Unit,
    ) {
        when (effect) {
            is SetupSideEffect.ExecuteRootCommand -> {
                // A su prompt that is never answered used to suspend this effect forever:
                // no result, no error, buttons stuck
                if (withTimeoutOrNull(GRANT_TIMEOUT_MS) { executeGrantViaRootUseCase() } == true) {
                    onCommand(SetupCommand.RootExecutionSucceeded)
                } else {
                    onCommand(SetupCommand.RootExecutionFailed)
                }
            }

            is SetupSideEffect.ExecuteShizukuCommand -> {
                if (withTimeoutOrNull(GRANT_TIMEOUT_MS) { executeGrantViaShizukuUseCase() } == true) {
                    onCommand(SetupCommand.ShizukuExecutionSucceeded)
                } else {
                    onCommand(SetupCommand.ShizukuExecutionFailed)
                }
            }

            is SetupSideEffect.SelectTerminal -> {
                selectTerminalUseCase(effect.type)
            }

            is SetupSideEffect.CopyAdbCommand -> {
                copyToClipboardUseCase(effect.command)
            }

            is SetupSideEffect.CheckPermission -> {
                if (checkReadLogsPermissionUseCase()) {
                    onCommand(SetupCommand.PermissionGranted)
                } else {
                    onCommand(SetupCommand.PermissionNotGranted)
                }
            }

            is SetupSideEffect.CheckAdbPermission -> {
                if (checkReadLogsPermissionUseCase()) {
                    onCommand(SetupCommand.PermissionGranted)
                } else {
                    onCommand(SetupCommand.ShowAdbDialog(getAdbCommandUseCase()))
                }
            }

            // UI side effects - handled by Fragment
            is SetupSideEffect.ShowSnackbar,
            is SetupSideEffect.RestartApp,
            -> Unit
        }
    }
}
