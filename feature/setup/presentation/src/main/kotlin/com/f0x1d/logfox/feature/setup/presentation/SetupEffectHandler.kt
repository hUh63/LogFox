package com.f0x1d.logfox.feature.setup.presentation

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.setup.api.domain.CheckReadLogsPermissionUseCase
import com.f0x1d.logfox.feature.setup.api.domain.CopyToClipboardUseCase
import com.f0x1d.logfox.feature.setup.api.domain.ExecuteGrantViaRootUseCase
import com.f0x1d.logfox.feature.setup.api.domain.ExecuteGrantViaShizukuUseCase
import com.f0x1d.logfox.feature.setup.api.domain.GetAdbCommandUseCase
import com.f0x1d.logfox.feature.setup.api.domain.SelectTerminalUseCase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private companion object {
        const val GRANT_TIMEOUT_MS = 90_000L
    }

    /**
     * `withTimeoutOrNull` alone could not rescue these grants: the libsu / Shizuku calls block
     * a thread and ignore cancellation, so the effect never returned and the setup buttons
     * stayed disabled forever. The watchdog reports the failure from a child coroutine, which
     * reaches the reducer even while the blocking call is still running.
     */
    private suspend fun runWithWatchdog(
        onCommand: suspend (SetupCommand) -> Unit,
        failureCommand: SetupCommand,
        block: suspend () -> Boolean,
    ): Boolean = coroutineScope {
        val watchdog = launch {
            delay(GRANT_TIMEOUT_MS)

            onCommand(failureCommand)
        }

        val result = withTimeoutOrNull(GRANT_TIMEOUT_MS) {
            runCatching { block() }.getOrDefault(false)
        } ?: false

        watchdog.cancel()

        result
    }

    override suspend fun handle(
        effect: SetupSideEffect,
        onCommand: suspend (SetupCommand) -> Unit,
    ) {
        when (effect) {
            is SetupSideEffect.ExecuteRootCommand -> {
                // A su prompt that is never answered used to suspend this effect forever:
                // no result, no error, buttons stuck
                val granted = runWithWatchdog(
                    onCommand = onCommand,
                    failureCommand = SetupCommand.RootExecutionFailed,
                ) { executeGrantViaRootUseCase() }

                onCommand(
                    if (granted) SetupCommand.RootExecutionSucceeded else SetupCommand.RootExecutionFailed,
                )
            }

            is SetupSideEffect.ExecuteShizukuCommand -> {
                val granted = runWithWatchdog(
                    onCommand = onCommand,
                    failureCommand = SetupCommand.ShizukuExecutionFailed,
                ) { executeGrantViaShizukuUseCase() }

                onCommand(
                    if (granted) SetupCommand.ShizukuExecutionSucceeded else SetupCommand.ShizukuExecutionFailed,
                )
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
