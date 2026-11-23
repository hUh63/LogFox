package com.f0x1d.logfox.feature.logging.presentation.service

import android.content.Context
import com.f0x1d.logfox.core.di.DefaultDispatcher
import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.crashes.api.domain.FlushPendingCrashesUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.ProcessLogLineCrashesUseCase
import com.f0x1d.logfox.feature.logging.api.domain.AddLogLineUseCase
import com.f0x1d.logfox.feature.logging.api.domain.BroadcastLogEventUseCase
import com.f0x1d.logfox.feature.logging.api.domain.ClearLogsUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLastLogUseCase
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsSnapshotUseCase
import com.f0x1d.logfox.feature.logging.api.domain.StartLoggingUseCase
import com.f0x1d.logfox.feature.logging.api.domain.UpdateLogsUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetLogsUpdateIntervalUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.terminal.ShouldFallbackToDefaultTerminalUseCase
import com.f0x1d.logfox.feature.recordings.api.domain.NotifyLoggingStoppedUseCase
import com.f0x1d.logfox.feature.recordings.api.domain.ProcessLogLineRecordingUseCase
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.terminals.api.domain.ExitTerminalUseCase
import com.f0x1d.logfox.feature.terminals.api.domain.GetDefaultTerminalUseCase
import com.f0x1d.logfox.feature.terminals.api.domain.GetSelectedTerminalUseCase
import com.f0x1d.logfox.feature.terminals.api.exception.TerminalNotSupportedException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

internal class LoggingServiceEffectHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val startLoggingUseCase: StartLoggingUseCase,
    private val addLogLineUseCase: AddLogLineUseCase,
    private val clearLogsUseCase: ClearLogsUseCase,
    private val getLogsSnapshotUseCase: GetLogsSnapshotUseCase,
    private val getLastLogUseCase: GetLastLogUseCase,
    private val updateLogsUseCase: UpdateLogsUseCase,
    private val processLogLineCrashesUseCase: ProcessLogLineCrashesUseCase,
    private val flushPendingCrashesUseCase: FlushPendingCrashesUseCase,
    private val processLogLineRecordingUseCase: ProcessLogLineRecordingUseCase,
    private val notifyLoggingStoppedUseCase: NotifyLoggingStoppedUseCase,
    private val getSelectedTerminalUseCase: GetSelectedTerminalUseCase,
    private val getDefaultTerminalUseCase: GetDefaultTerminalUseCase,
    private val exitTerminalUseCase: ExitTerminalUseCase,
    private val getLogsUpdateIntervalUseCase: GetLogsUpdateIntervalUseCase,
    private val shouldFallbackToDefaultTerminalUseCase: ShouldFallbackToDefaultTerminalUseCase,
    private val broadcastLogEventUseCase: BroadcastLogEventUseCase,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : EffectHandler<LoggingServiceSideEffect, LoggingServiceCommand> {

    private val effectScope = CoroutineScope(defaultDispatcher + SupervisorJob())

    private var logCollectionJob: Job? = null
    private var logUpdatesJob: Job? = null

    override suspend fun handle(
        effect: LoggingServiceSideEffect,
        onCommand: suspend (LoggingServiceCommand) -> Unit,
    ) {
        when (effect) {
            is LoggingServiceSideEffect.SelectTerminal -> {
                val terminal = getSelectedTerminalUseCase()
                Timber.d("Selected terminal: $terminal")
                onCommand(LoggingServiceCommand.TerminalSelected(terminal))
            }

            is LoggingServiceSideEffect.StartLogCollection -> {
                Timber.d("Starting log collection with terminal: ${effect.terminal}")
                val lastLog = getLastLogUseCase()
                val startingId = lastLog?.id?.plus(1) ?: 0L

                logCollectionJob?.cancel()
                logCollectionJob = effectScope.launch {
                    var failed = false
                    var cancelled = false

                    try {
                        startLoggingUseCase(
                            terminal = effect.terminal,
                            startingId = startingId,
                            lastLogTime = lastLog?.dateAndTime,
                        ).catch { throwable ->
                            // Cancellation must stay cancellation, otherwise a normal stop
                            // was reported as an error and triggered an extra restart
                            if (throwable is CancellationException) throw throwable

                            failed = true
                            Timber.e(throwable, "Logging flow error")
                            onCommand(
                                LoggingServiceCommand.LoggingError(
                                    error = throwable,
                                    terminal = effect.terminal,
                                )
                            )
                        }.collect { logLine ->
                            withContext(defaultDispatcher) {
                                // One failing step (full disk, broken recording file, dead
                                // receiver) used to abort the whole collection flow, which
                                // stopped logging for good
                                step("store") { addLogLineUseCase(logLine) }
                                step("crashes") { processLogLineCrashesUseCase(logLine) }
                                step("recording") { processLogLineRecordingUseCase(logLine) }
                                step("broadcast") { broadcastLogEventUseCase(logLine) }
                            }
                        }
                    } catch (cancellationException: CancellationException) {
                        cancelled = true
                        throw cancellationException
                    } catch (throwable: Throwable) {
                        // Failure inside collect (not covered by Flow.catch, which only
                        // protects the upstream) used to kill logging silently and forever
                        failed = true
                        Timber.e(throwable, "Logging collection failed")
                        onCommand(
                            LoggingServiceCommand.LoggingError(
                                error = throwable,
                                terminal = effect.terminal,
                            )
                        )
                    } finally {
                        // Only a clean finish restarts collection; an error path already
                        // schedules its own retry and a cancellation must not restart at all
                        if (!failed && !cancelled) {
                            onCommand(LoggingServiceCommand.LoggingFlowCompleted)
                        }
                    }
                }
            }

            is LoggingServiceSideEffect.StopLogCollection -> {
                Timber.d("Stopping log collection")
                logCollectionJob?.cancel()
                logCollectionJob = null
            }

            is LoggingServiceSideEffect.ClearLogs -> {
                Timber.d("Clearing logs")
                clearLogsUseCase()
            }

            is LoggingServiceSideEffect.ScheduleLogUpdates -> {
                Timber.d("Scheduling log updates")
                logUpdatesJob?.cancel()
                logUpdatesJob = effectScope.launch {
                    while (true) {
                        // Reading the interval used to sit outside any protection: one bad
                        // preference value killed the only loop that ever pushes logs to the
                        // UI, so the list froze until the app was restarted
                        val interval = try {
                            getLogsUpdateIntervalUseCase().coerceAtLeast(MIN_UPDATE_INTERVAL_MS)
                        } catch (cancellationException: CancellationException) {
                            throw cancellationException
                        } catch (throwable: Throwable) {
                            Timber.w(throwable, "Failed to read logs update interval")
                            MIN_UPDATE_INTERVAL_MS
                        }

                        delay(interval)

                        step("snapshot") {
                            val logs = getLogsSnapshotUseCase()
                            Timber.d("Sending ${logs.size} logs to store")
                            updateLogsUseCase(logs)
                        }

                        // A crash whose process died and nothing else logged stayed
                        // "collecting" forever: it was neither stored nor notified until some
                        // unrelated line arrived. This tick completes stale collections.
                        step("crash-flush") {
                            flushPendingCrashesUseCase()
                        }
                    }
                }
            }

            is LoggingServiceSideEffect.CancelLogUpdates -> {
                Timber.d("Cancelling log updates")
                logUpdatesJob?.cancel()
                logUpdatesJob = null
            }

            is LoggingServiceSideEffect.NotifyLoggingStopped -> {
                Timber.d("Notifying logging stopped")
                notifyLoggingStoppedUseCase()
            }

            is LoggingServiceSideEffect.ExitTerminal -> {
                Timber.d("Exiting terminal: ${effect.terminal}")
                exitTerminalUseCase(effect.terminal)
            }

            is LoggingServiceSideEffect.HandleLoggingError -> {
                val error = effect.error
                val terminal = effect.terminal

                when {
                    error is TerminalNotSupportedException -> {
                        if (shouldFallbackToDefaultTerminalUseCase()) {
                            val message = context.getString(Strings.terminal_unavailable_falling_back)
                            onCommand(LoggingServiceCommand.ShowToast(message))
                            onCommand(
                                LoggingServiceCommand.TerminalFallback(
                                    newTerminal = getDefaultTerminalUseCase(),
                                )
                            )
                        } else {
                            // Wait and retry with same terminal
                            delay(RETRY_DELAY_MS)
                            onCommand(
                                LoggingServiceCommand.TerminalSelected(terminal)
                            )
                        }
                    }
                    error is CancellationException -> {
                        // Ignore cancellation
                    }
                    else -> {
                        val message = context.getString(
                            Strings.error,
                            error.localizedMessage ?: error.message ?: "Unknown error",
                        )
                        onCommand(LoggingServiceCommand.ShowToast(message))
                        error.printStackTrace()
                        // Wait and retry
                        delay(RETRY_DELAY_MS)
                        onCommand(
                            LoggingServiceCommand.TerminalSelected(terminal)
                        )
                    }
                }
            }

            // UI side effects - handled by Service, ignored here
            is LoggingServiceSideEffect.ShowToast -> Unit
            is LoggingServiceSideEffect.PerformKillService -> Unit
        }
    }

    override fun close() {
        logCollectionJob?.cancel()
        logCollectionJob = null
        logUpdatesJob?.cancel()
        logUpdatesJob = null
    }

    companion object {
        private const val RETRY_DELAY_MS = 10_000L
        private const val MIN_UPDATE_INTERVAL_MS = 50L
    }

    // Keeps the log pipeline alive when a single step fails: rethrows cancellation only
    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Log pipeline step '%s' failed", name)
        }
    }
}
