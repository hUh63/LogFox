package com.f0x1d.logfox.feature.logging.impl.domain

import android.content.Context
import android.content.Intent
import com.f0x1d.logfox.feature.logging.api.domain.BroadcastLogEventUseCase
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.preferences.api.data.ServiceSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class BroadcastLogEventUseCaseImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serviceSettingsRepository: ServiceSettingsRepository,
) : BroadcastLogEventUseCase {

    private companion object {
        // Logcat can produce thousands of lines per second, sending all of them would
        // melt both LogFox and the receiving automation app
        const val MAX_EVENTS_PER_SECOND = 50
    }

    private var windowStart = 0L
    private var sentInWindow = 0

    override fun invoke(logLine: LogLine) {
        if (!serviceSettingsRepository.broadcastLogEvents().value) return

        val now = System.currentTimeMillis()

        if (now - windowStart >= 1_000L) {
            windowStart = now
            sentInWindow = 0
        }

        if (sentInWindow >= MAX_EVENTS_PER_SECOND) return

        sentInWindow++

        val intent = Intent(BroadcastLogEventUseCase.ACTION).apply {
            putExtra(BroadcastLogEventUseCase.EXTRA_LEVEL, logLine.level.name)
            putExtra(BroadcastLogEventUseCase.EXTRA_TAG, logLine.tag)
            putExtra(BroadcastLogEventUseCase.EXTRA_CONTENT, logLine.content)
            putExtra(BroadcastLogEventUseCase.EXTRA_PACKAGE, logLine.packageName)
            putExtra(BroadcastLogEventUseCase.EXTRA_UID, logLine.uid)
            putExtra(BroadcastLogEventUseCase.EXTRA_PID, logLine.pid)
            putExtra(BroadcastLogEventUseCase.EXTRA_TID, logLine.tid)
            putExtra(BroadcastLogEventUseCase.EXTRA_DATE_AND_TIME, logLine.dateAndTime)
        }

        runCatching { context.sendBroadcast(intent) }
    }
}
