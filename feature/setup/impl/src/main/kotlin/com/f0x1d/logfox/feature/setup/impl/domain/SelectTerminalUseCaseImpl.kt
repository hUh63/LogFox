package com.f0x1d.logfox.feature.setup.impl.domain

import com.f0x1d.logfox.feature.preferences.api.data.TerminalSettingsRepository
import com.f0x1d.logfox.feature.setup.api.domain.SelectTerminalUseCase
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import javax.inject.Inject

internal class SelectTerminalUseCaseImpl @Inject constructor(
    private val terminalSettingsRepository: TerminalSettingsRepository,
) : SelectTerminalUseCase {

    override suspend fun invoke(type: TerminalType) {
        terminalSettingsRepository.selectedTerminalType().set(type)

        // The setup screen restarts the app right after granting access. `apply()` only
        // schedules the disk write, and exitProcess() skips that queue, so the freshly chosen
        // backend could be lost and the user had to authorize again on the next launch
        terminalSettingsRepository.flushToDisk()
    }
}
