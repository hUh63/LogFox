package com.f0x1d.logfox.feature.logging.api.domain

import com.f0x1d.logfox.feature.logging.api.model.LogLine

/**
 * Re-broadcasts every collected log line as an Intent, so automation apps (Tasker,
 * MacroDroid, Automate) can react to log events without reading logcat themselves
 * (F0x1d/LogFox#255, F0x1d/LogFox#253).
 *
 * Action: [ACTION]
 */
interface BroadcastLogEventUseCase {

    operator fun invoke(logLine: LogLine)

    companion object {
        const val ACTION = "com.f0x1d.logfox.LOG_EVENT"

        const val EXTRA_LEVEL = "level"
        const val EXTRA_TAG = "tag"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_UID = "uid"
        const val EXTRA_PID = "pid"
        const val EXTRA_TID = "tid"
        const val EXTRA_DATE_AND_TIME = "dateAndTime"
    }
}
