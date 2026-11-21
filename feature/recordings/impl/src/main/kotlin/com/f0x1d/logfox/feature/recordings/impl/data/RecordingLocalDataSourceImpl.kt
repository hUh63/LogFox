package com.f0x1d.logfox.feature.recordings.impl.data

import android.content.Context
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.database.api.data.LogRecordingDataSource
import com.f0x1d.logfox.feature.datetime.api.DateTimeFormatter
import com.f0x1d.logfox.feature.filters.api.domain.GetAllEnabledFiltersFlowUseCase
import com.f0x1d.logfox.feature.filters.api.model.UserFilter
import com.f0x1d.logfox.feature.filters.api.model.suits
import com.f0x1d.logfox.feature.logging.api.data.LogLineFormatterRepository
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.preferences.api.data.LogsSettingsRepository
import com.f0x1d.logfox.feature.recordings.api.data.RecordingState
import com.f0x1d.logfox.feature.recordings.api.model.LogRecording
import com.f0x1d.logfox.feature.recordings.impl.mapper.toEntity
import com.f0x1d.logfox.feature.strings.Strings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RecordingLocalDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logRecordingDataSource: LogRecordingDataSource,
    private val dateTimeFormatter: DateTimeFormatter,
    private val logLineFormatterRepository: LogLineFormatterRepository,
    private val logsSettingsRepository: LogsSettingsRepository,
    private val notificationsLocalDataSource: RecordingNotificationsLocalDataSource,
    private val getAllEnabledFiltersFlowUseCase: GetAllEnabledFiltersFlowUseCase,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : RecordingLocalDataSource {

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    private val enabledFilters = MutableStateFlow<List<UserFilter>>(emptyList())
    private var filtersCollectionJob: Job? = null

    private val recordingsDir = File("${context.filesDir.absolutePath}/recordings").apply {
        if (!exists()) mkdirs()
    }

    private val state = MutableStateFlow(RecordingState.IDLE)
    override val recordingState: StateFlow<RecordingState> = state

    private var recordingTime = 0L
    private var recordingFile: File? = null
    private val fileMutex = Mutex()

    private val recordedLines = mutableListOf<LogLine>()
    private val linesMutex = Mutex()

    override suspend fun record() = withContext(ioDispatcher) {
        linesMutex.withLock {
            recordedLines.clear()
        }

        recordingTime = System.currentTimeMillis()

        fileMutex.withLock {
            // The formatted timestamp is user-configurable (HH:mm is a valid pattern), so
            // two recordings started within the same formatted tick used to share one file
            // and the second one appended to the first one's content
            recordingFile = uniqueRecordingFile(dateTimeFormatter.formatForExport(recordingTime))
        }

        startFiltersCollection()

        state.update { RecordingState.RECORDING }
        notificationsLocalDataSource.sendRecordingNotification()
    }

    private fun startFiltersCollection() {
        filtersCollectionJob?.cancel()
        filtersCollectionJob = scope.launch {
            getAllEnabledFiltersFlowUseCase().collect { filters ->
                enabledFilters.value = filters
            }
        }
    }

    private fun stopFiltersCollection() {
        filtersCollectionJob?.cancel()
        filtersCollectionJob = null
    }

    override suspend fun pause() = withContext(ioDispatcher) {
        state.update { RecordingState.PAUSED }
        notificationsLocalDataSource.sendRecordingPausedNotification()
    }

    override suspend fun resume() = withContext(ioDispatcher) {
        state.update { RecordingState.RECORDING }
        notificationsLocalDataSource.sendRecordingNotification()
    }

    override suspend fun end(): LogRecording? = withContext(ioDispatcher) {
        state.update { RecordingState.SAVING }

        try {
            stopFiltersCollection()
            dumpLines()
            notificationsLocalDataSource.cancelRecordingNotification()

            val file = recordingFile ?: return@withContext null

            LogRecording(
                title = "${context.getString(Strings.record_file)} ${logRecordingDataSource.count() + 1}",
                dateAndTime = recordingTime,
                file = file,
            ).let {
                it.copy(id = logRecordingDataSource.insert(it.toEntity()))
            }
        } finally {
            // Leaving the state at SAVING on any failure (missing file, disk full, DB error)
            // used to freeze recording for the rest of the process: every new line was
            // dropped and the button only ever tried to "end" an already broken session.
            recordingFile = null
            state.update { RecordingState.IDLE }
        }
    }

    override suspend fun loggingStopped(): Unit = withContext(ioDispatcher) {
        when (state.value) {
            RecordingState.RECORDING,
            RecordingState.PAUSED,
            -> end()

            else -> Unit
        }
    }

    override suspend fun processLogLine(logLine: LogLine) {
        if (state.value != RecordingState.RECORDING) return
        if (!logLine.suits(enabledFilters.value)) return

        val shouldDump = linesMutex.withLock {
            recordedLines.add(logLine)
            recordedLines.size >= logsSettingsRepository.logsDisplayLimit().value
        }

        if (shouldDump) {
            dumpLines()
        }
    }

    private suspend fun dumpLines() {
        val batch = linesMutex.withLock {
            if (recordedLines.isEmpty()) return@withLock emptyList()

            recordedLines.toList()
        }

        if (batch.isEmpty()) return

        val content = batch.joinToString("\n") {
            logLineFormatterRepository.formatForExport(
                logLine = it,
            )
        }

        // Write first, drop the buffer afterwards. Clearing before the write meant an
        // IOException (storage full, file removed) silently swallowed the whole batch and
        // the user got a recording with an invisible hole
        var written = false

        fileMutex.withLock {
            recordingFile?.let { file ->
                file.appendText(content + "\n")
                written = true
            }
        }

        if (written) {
            linesMutex.withLock {
                if (recordedLines.size >= batch.size) {
                    recordedLines.subList(0, batch.size).clear()
                } else {
                    recordedLines.clear()
                }
            }
        }
    }

    private fun uniqueRecordingFile(baseName: String): File {
        val base = File(recordingsDir, "$baseName.log")

        if (!base.exists()) return base

        var index = 1

        while (true) {
            val candidate = File(recordingsDir, "$baseName-$index.log")

            if (!candidate.exists()) return candidate

            index++
        }
    }
}
