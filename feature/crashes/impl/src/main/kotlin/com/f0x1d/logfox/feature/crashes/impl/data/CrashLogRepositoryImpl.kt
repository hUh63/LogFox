package com.f0x1d.logfox.feature.crashes.impl.data

import com.f0x1d.logfox.feature.crashes.api.data.CrashLogRepository
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import com.f0x1d.logfox.feature.logging.api.data.LogLineFormatterRepository
import com.f0x1d.logfox.feature.logging.api.data.LogLineParser
import javax.inject.Inject

internal class CrashLogRepositoryImpl @Inject constructor(
    private val logLineParser: LogLineParser,
    private val logLineFormatterRepository: LogLineFormatterRepository,
) : CrashLogRepository {
    override fun readCrashLog(appCrash: AppCrash): List<String> {
        // Crash log file may be gone (cleared storage, deleted crash, restored backup).
        // Reading it unguarded throws FileNotFoundException and crashes the app (F0x1d/LogFox#254)
        val logFile = appCrash.logFile?.takeIf { it.exists() } ?: return emptyList()

        val lines = runCatching { logFile.readLines() }.getOrElse { return emptyList() }

        return lines.mapIndexed { index, line ->
            logLineParser.parse(index.toLong(), line)?.let { logLine ->
                logLineFormatterRepository.formatOriginal(logLine)
            } ?: line
        }
    }
}
