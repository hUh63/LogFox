package com.f0x1d.logfox.feature.logging.impl.domain

import android.net.Uri
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.export.api.data.ExportRepository
import com.f0x1d.logfox.feature.logging.api.data.LogLineFormatterRepository
import com.f0x1d.logfox.feature.logging.api.domain.ExportLogsToUriUseCase
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class ExportLogsToUriUseCaseImpl @Inject constructor(
    private val logLineFormatterRepository: LogLineFormatterRepository,
    private val exportRepository: ExportRepository,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportLogsToUriUseCase {

    // Formatting 10 000 lines used to run on the main thread (the store dispatches effects
    // on viewModelScope = Main.immediate) and froze the UI
    override suspend fun invoke(lines: List<LogLine>, uri: Uri) = withContext(ioDispatcher) {
        val content = lines.joinToString("\n") { line ->
            logLineFormatterRepository.formatForExport(
                logLine = line,
            )
        }
        exportRepository.writeContentToUri(uri, content)
    }
}
