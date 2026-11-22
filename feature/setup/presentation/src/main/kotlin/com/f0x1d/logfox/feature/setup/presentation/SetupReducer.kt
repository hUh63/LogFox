package com.f0x1d.logfox.feature.setup.presentation

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import javax.inject.Inject

internal class SetupReducer @Inject constructor() : Reducer<SetupState, SetupCommand, SetupSideEffect> {

    override fun reduce(
        state: SetupState,
        command: SetupCommand,
    ): ReduceResult<SetupState, SetupSideEffect> = when (command) {
        // The backend must only be persisted after the grant actually succeeded. It used to
        // be written on click, so a failed su/Shizuku prompt left the app with an
        // unsupported backend saved: every later launch ended with an empty log list
        is SetupCommand.RootClicked -> {
            if (state.isRunning) {
                state.noSideEffects()
            } else {
                state.copy(isRunning = true).withSideEffects(
                    SetupSideEffect.ExecuteRootCommand,
                )
            }
        }

        // Does NOT reset isRunning: doing so re-enabled Root/Shizuku while a grant was
        // still in flight, which is exactly the parallel-request race the flag prevents
        is SetupCommand.AdbClicked -> state.withSideEffects(
            SetupSideEffect.SelectTerminal(TerminalType.Default),
            SetupSideEffect.CheckAdbPermission,
        )

        is SetupCommand.ShizukuClicked -> {
            if (state.isRunning) {
                state.noSideEffects()
            } else {
                state.copy(isRunning = true).withSideEffects(
                    SetupSideEffect.ExecuteShizukuCommand,
                )
            }
        }

        is SetupCommand.CheckPermissionClicked -> state.withSideEffects(
            SetupSideEffect.CheckPermission,
        )

        is SetupCommand.CopyCommandClicked -> state.withSideEffects(
            SetupSideEffect.CopyAdbCommand(state.adbCommand),
            SetupSideEffect.ShowSnackbar(Strings.text_copied),
        )

        is SetupCommand.CloseAdbDialogClicked -> state.copy(
            showAdbDialog = false,
        ).noSideEffects()

        is SetupCommand.RootExecutionSucceeded -> state.copy(
            isRunning = false,
        ).withSideEffects(
            SetupSideEffect.SelectTerminal(TerminalType.Root),
            SetupSideEffect.CheckPermission,
        )

        is SetupCommand.RootExecutionFailed -> state.copy(
            isRunning = false,
        ).withSideEffects(
            SetupSideEffect.ShowSnackbar(Strings.no_root),
        )

        is SetupCommand.ShizukuExecutionSucceeded -> state.copy(
            isRunning = false,
        ).withSideEffects(
            SetupSideEffect.SelectTerminal(TerminalType.Shizuku),
            SetupSideEffect.RestartApp,
        )

        is SetupCommand.ShizukuExecutionFailed -> state.copy(
            isRunning = false,
        ).withSideEffects(
            SetupSideEffect.ShowSnackbar(Strings.shizuku_error),
        )

        is SetupCommand.PermissionGranted -> state.copy(
            showAdbDialog = false,
        ).withSideEffects(
            SetupSideEffect.RestartApp,
        )

        is SetupCommand.PermissionNotGranted -> state.withSideEffects(
            SetupSideEffect.ShowSnackbar(Strings.no_permission_detected),
        )

        is SetupCommand.ShowAdbDialog -> state.copy(
            showAdbDialog = true,
            adbCommand = command.adbCommand,
        ).noSideEffects()
    }
}
