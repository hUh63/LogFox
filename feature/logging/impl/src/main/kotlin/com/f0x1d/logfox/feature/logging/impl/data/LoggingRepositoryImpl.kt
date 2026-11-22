package com.f0x1d.logfox.feature.logging.impl.data

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.logging.api.data.LogLineParser
import com.f0x1d.logfox.feature.logging.api.data.LoggingRepository
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.exception.TerminalNotSupportedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.BufferedReader
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class LoggingRepositoryImpl @Inject constructor(
    private val logLineParser: LogLineParser,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : LoggingRepository {

    override fun startLogging(
        terminal: Terminal,
        startingId: Long,
        startLogsTime: Long?,
    ): Flow<LogLine> = flow {
        val formattedTimestamp = startLogsTime?.let { formatTimestampForLogcat(it) }

        val command = LoggingRepository.COMMAND + if (formattedTimestamp == null) {
            emptyArray()
        } else {
            arrayOf("-T", formattedTimestamp)
        }
        Timber.d("Starting logging with command: ${command.joinToString(" ")}")

        emitLines(
            terminal = terminal,
            command = command,
            startingId = startingId,
            // 60 s of silence is common on an idle device; a shorter window only tore the
            // stream down and rebuilt logcat over and over
            readLineTimeout = 60.seconds,
        )
    }.flowOn(ioDispatcher)

    override fun dumpLogs(terminal: Terminal): Flow<LogLine> = flow {
        val command = LoggingRepository.COMMAND + LoggingRepository.DUMP_FLAG

        emitLines(
            terminal = terminal,
            command = command,
            startingId = 0,
            readLineTimeout = 1.seconds,
        )
    }.flowOn(ioDispatcher)

    private suspend fun FlowCollector<LogLine>.emitLines(
        terminal: Terminal,
        command: Array<String>,
        startingId: Long,
        readLineTimeout: Duration,
    ) {
        if (terminal.isSupported().not()) {
            Timber.d("Terminal $terminal is not supported")
            throw TerminalNotSupportedException()
        }

        val process = terminal.execute(*command) ?: throw TerminalNotSupportedException()
        Timber.d("Started process")

        var idsCounter = startingId
        Timber.d("Starting with id $idsCounter")

        try {
            Timber.d("Started scope")

            process.output.bufferedReader().use { reader ->
                Timber.d("Got reader")

                // A record is held back until the next header line shows up, so continuation
                // lines (stack traces, messages with newlines) can be appended to it instead
                // of being dropped
                var pending: LogLine? = null

                while (true) {
                    val line: String?

                    if (pending == null) {
                        line = withTimeoutOrNull(readLineTimeout) {
                            reader.readLineCancellable()
                        }

                        if (line == null) {
                            // Idle or EOF. End the flow *normally* instead of throwing: the old
                            // `withTimeout` raised a CancellationException, which the collection
                            // code now (correctly) treats as a real cancel - so a live logcat
                            // that was simply idle stopped for good.
                            break
                        }
                    } else {
                        // Only hold a record for a moment: waiting the full idle timeout for a
                        // continuation would make the last line of a quiet logcat appear 60 s late
                        line = withTimeoutOrNull(CONTINUATION_WINDOW) {
                            reader.readLineCancellable()
                        }

                        if (line == null) {
                            pending?.let { emit(it) }
                            pending = null

                            continue
                        }
                    }

                    val logLine = logLineParser.parse(
                        id = idsCounter++,
                        line = line,
                    )

                    if (logLine == null) {
                        // `--------- beginning of main` and friends are separators, not content
                        if (pending != null && !line.startsWith("---------")) {
                            pending = pending.copy(
                                content = pending.content + "\n" + line,
                                originalContent = pending.originalContent + "\n" + line,
                            )
                        }

                        continue
                    }

                    pending?.let { emit(it) }
                    pending = logLine
                }

                // Do not lose the last record when the stream simply ends
                pending?.let { emit(it) }
            }
        } finally {
            Timber.d("Destroying process")
            runCatching {
                process.destroy()
            }
        }
    }

    private suspend fun BufferedReader.readLineCancellable(): String? = withContext(ioDispatcher) {
        while (true) {
            if (ready()) {
                return@withContext readLine()
            }
            delay(100L)
        }

        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun formatTimestampForLogcat(timeMillis: Long): String {
        val seconds = timeMillis / 1000

        // The fraction is interpreted by position: ".5" means 500 ms, not 5 ms, so a
        // non-padded value silently started collection up to a second too late
        val millis = (timeMillis % 1000).toString().padStart(3, '0')

        return "$seconds.$millis"
    }

    private companion object {
        // How long a parsed record is held back waiting for possible continuation lines
        val CONTINUATION_WINDOW = 150.milliseconds
    }
}
