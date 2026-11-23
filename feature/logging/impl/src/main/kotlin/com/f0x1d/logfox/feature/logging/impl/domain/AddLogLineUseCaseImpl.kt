package com.f0x1d.logfox.feature.logging.impl.domain

import com.f0x1d.logfox.feature.logging.api.domain.AddLogLineUseCase
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.logging.impl.data.LogsBufferDataSource
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetLogsDisplayLimitUseCase
import javax.inject.Inject

internal class AddLogLineUseCaseImpl @Inject constructor(
    private val logsBufferDataSource: LogsBufferDataSource,
    private val getLogsDisplayLimitUseCase: GetLogsDisplayLimitUseCase,
) : AddLogLineUseCase {
    // Reading the setting for every single line allocated a new preference wrapper and hit
    // SharedPreferences each time, on the hot logcat path. A short TTL keeps user changes
    // visible without paying that cost per line.
    @Volatile
    private var cachedLimit: Int? = null

    @Volatile
    private var cachedLimitAt = 0L

    override suspend fun invoke(logLine: LogLine) {
        val now = System.nanoTime()

        val limit = cachedLimit?.takeIf { now - cachedLimitAt < LIMIT_CACHE_NANOS }
            ?: getLogsDisplayLimitUseCase().also {
                cachedLimit = it
                cachedLimitAt = now
            }

        logsBufferDataSource.add(logLine, limit)
    }

    private companion object {
        const val LIMIT_CACHE_NANOS = 5_000_000_000L
    }
}
