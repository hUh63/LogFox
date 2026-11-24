package com.f0x1d.logfox.feature.crashes.impl.data

import com.f0x1d.logfox.feature.logging.api.model.LogLine

internal interface CrashDataSource {
    suspend fun process(line: LogLine)

    /**
     * Emits the crash that is currently being collected if its collection window has passed
     * without any further lines. Without it a crash followed by silence was never stored.
     */
    suspend fun flushPending(force: Boolean = false)
}
