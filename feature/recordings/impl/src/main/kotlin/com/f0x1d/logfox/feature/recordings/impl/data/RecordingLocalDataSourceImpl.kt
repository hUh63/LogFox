package com.f0x1d.logfox.feature.recordings.impl.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.f0x1d.logfox.core.context.toast
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

    // Serializes "snapshot -> write -> trim". Without it two dumps (size trigger + end())
    // took the same snapshot and appended the identical block twice
    private val dumpMutex = Mutex()

    // The notification STOP action and the in-app STOP can fire at the same time. Both used
    // to read the same recordingFile and insert a row for it, so the list ended up with two
    // entries pointing at one file (and deleting either deleted the shared file)
    private val endMutex = Mutex()

    // A chunk that could not be written used to be dropped with nothing but a Log.w, so a
    // recording that was reported as saved could be silently missing its middle. Failures are
    // retried (a full disk is often transient), and whatever still cannot be written is
    // reported to the user when the recording ends.
    private var consecutiveWriteFailures = 0
    private var droppedLinesCount = 0

    private companion object {
        const val TAG = "RecordingLocalDataSource"
        const val MAX_WRITE_RETRIES = 3
    }

    override suspend fun record() = withContext(ioDispatcher) {
        // Hold the dump lock while swapping the buffer/file: an in-flight dump used to read
        // the *new* file after this switch and append the previous recording's lines to it
        dumpMutex.withLock {
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

    override suspend fun end(): LogRecording? = endMutex.withLock {
        endLocked()
    }

    private suspend fun endLocked(): LogRecording? = withContext(ioDispatcher) {
        // Nothing to end: the session was already finished (or never started)
        if (recordingFile == null && state.value != RecordingState.RECORDING &&
            state.value != RecordingState.PAUSED
        ) {
            return@withContext null
        }

        state.update { RecordingState.SAVING }

        try {
            stopFiltersCollection()
            dumpLines()
            notificationsLocalDataSource.cancelRecordingNotification()

            val file = recordingFile ?: return@withContext null

            // Tell the user instead of handing over a silently incomplete recording
            val dropped = droppedLinesCount
            droppedLinesCount = 0

            if (dropped > 0) {
                Handler(Looper.getMainLooper()).post {
                    context.toast(Strings.recording_partially_saved)
                }
            }

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
            consecutiveWriteFailures = 0
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

    private suspend fun dumpLines() = dumpMutex.withLock {
        val batch = linesMutex.withLock {
            if (recordedLines.isEmpty()) return@withLock emptyList()

            recordedLines.toList()
        }

        if (batch.isEmpty()) return@withLock

        val content = batch.joinToString("\n") {
            logLineFormatterRepository.formatForExport(
                logLine = it,
            )
        }

        val targetFile = fileMutex.withLock { recordingFile }

        if (targetFile == null) return@withLock

        // A write failure must not escape: it used to break the whole logcat collection flow
        val written = runCatching {
            targetFile.appendText(content + "\n")
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to write ${batch.size} recorded lines", throwable)
        }.isSuccess

        // Only touch what belongs to this recording: record() clears the buffer and switches
        // the file itself, and must not be undone here
        val stillSameRecording = fileMutex.withLock { recordingFile === targetFile }

        if (!stillSameRecording) return@withLock

        if (!written) {
            consecutiveWriteFailures++

            // Keep the lines in the buffer for the next dump, but do not retry a lost cause
            // forever (the buffer would never drain and every later line would be lost too)
            if (consecutiveWriteFailures < MAX_WRITE_RETRIES) return@withLock

            Log.e(
                TAG,
                "Giving up on ${batch.size} recorded lines after $MAX_WRITE_RETRIES attempts",
            )

            droppedLinesCount += batch.size
            consecutiveWriteFailures = 0
        } else {
            consecutiveWriteFailures = 0
        }

        linesMutex.withLock {
            if (recordedLines.size >= batch.size) {
                recordedLines.subList(0, batch.size).clear()
            } else {
                recordedLines.clear()
            }
        }
    }

    /**
     * Claims a file name atomically. A plain `exists()` check let the recorder and "save
     * logs" choose the same name within one formatted tick and overwrite each other - one
     * recording's content ended up in the other's file.
     */
    private fun uniqueRecordingFile(baseName: String): File {
        var index = 0

        while (true) {
            val candidate = File(
                recordingsDir,
                if (index == 0) "$baseName.log" else "$baseName-$index.log",
            )

            // createNewFile is the atomic claim: it returns false when the name is taken
            if (runCatching { candidate.createNewFile() }.getOrDefault(false)) return candidate

            index++
        }
    }
}
