package com.f0x1d.logfox.feature.preferences.impl.data.terminal

import com.fredporciuncula.flow.preferences.Preference

internal interface TerminalSettingsLocalDataSource {
    fun selectedTerminalTypeKey(): Preference<String>
    fun fallbackToDefaultTerminal(): Preference<Boolean>

    /** Blocks until pending writes hit the disk (used right before a process restart). */
    fun flushToDisk(): Boolean
}
