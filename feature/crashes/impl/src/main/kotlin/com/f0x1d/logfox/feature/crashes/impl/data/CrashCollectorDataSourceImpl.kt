package com.f0x1d.logfox.feature.crashes.impl.data

import android.content.Context
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRepository
import com.f0x1d.logfox.feature.crashes.api.data.DisabledAppsRepository
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import com.f0x1d.logfox.feature.logging.api.domain.GetLogsSnapshotUseCase
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import com.f0x1d.logfox.feature.preferences.api.data.CrashesSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import timber.log.Timber
import javax.inject.Inject

internal class CrashCollectorDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationsLocalDataSource: CrashesNotificationsLocalDataSource,
    private val crashesRepository: CrashesRepository,
    private val disabledAppsRepository: DisabledAppsRepository,
    private val crashesSettingsRepository: CrashesSettingsRepository,
    private val getLogsSnapshotUseCase: GetLogsSnapshotUseCase,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : CrashCollectorDataSource {

    private val logsDir = File(context.filesDir, "crashes").apply {
        if (!exists()) mkdirs()
    }

    override suspend fun collectCrash(appCrash: AppCrash, logLines: List<LogLine>) {
        // A lookup failure must not make the crash disappear: treat it as "not disabled"
        // and "not a duplicate" and let the insert/notification below decide
        if (runCatching { disabledAppsRepository.isDisabledFor(appCrash.packageName) }
                .onFailure { Timber.w(it, "Failed to read disabled apps") }
                .getOrDefault(false)) return

        // Avoid duplicates
        val existingCrashes = runCatching {
            crashesRepository.getAllByDateAndTime(
                dateAndTime = appCrash.dateAndTime,
                packageName = appCrash.packageName,
            )
        }.onFailure { Timber.w(it, "Failed to check for duplicate crash") }.getOrNull()
        if (!existingCrashes.isNullOrEmpty()) return

        val crashLog = logLines.joinToString(separator = "\n") { it.originalContent }

        val collecting = runCatching { crashesSettingsRepository.collectingFor(appCrash.crashType.name) }
            .onFailure { Timber.w(it, "Failed to read crash collection setting") }
            .getOrDefault(true)

        // Only persist the log file when the crash is actually collected. It used to be
        // written unconditionally, so with collection disabled nothing referenced the file
        // and every crash left an orphan in filesDir/crashes that no cleanup ever removed
        val logFile = if (collecting) {
            withContext(ioDispatcher) {
                // A failing disk must not swallow the crash itself: the row (and the
                // notification) is more important than its log attachment
                runCatching {
                    File(logsDir, "${appCrash.dateAndTime}-crash.log").apply {
                        writeText(crashLog)
                    }
                }.onFailure { Timber.w(it, "Failed to write crash log") }.getOrNull()
            }
        } else {
            null
        }

        // The crash log above only contains the crash's own lines. The dump keeps the whole
        // buffered logcat context around it (what happened right before the crash), which is
        // what the details screen and the zip export expose as a separate file.
        val logDumpFile = if (collecting) writeLogDump(appCrash.dateAndTime) else null

        val crashWithLog = appCrash.copy(
            logFile = logFile,
            logDumpFile = logDumpFile,
        )

        val finalCrash = if (collecting) {
            // Soft-deleted rows are only removed here; nothing else ever called it, so
            // deleted crashes (and their file paths) accumulated forever
            runCatching { crashesRepository.clearDeletedIfNeeded() }

            // The user asked to be told about every crash, so a failed insert still notifies
            // (with id == 0 the notification simply has no deep link)
            runCatching { crashWithLog.copy(id = crashesRepository.insert(crashWithLog)) }
                .onFailure {
                    Timber.e(it, "Failed to store crash ${appCrash.packageName}")

                    // Nothing references the files once the row is not stored: do not leave
                    // orphans behind in filesDir/crashes
                    logFile?.delete()
                    logDumpFile?.delete()
                }
                .getOrElse { crashWithLog }
        } else {
            crashWithLog
        }

        val showingNotifications = runCatching {
            crashesSettingsRepository.showingNotificationsFor(finalCrash.crashType.name)
        }.onFailure { Timber.w(it, "Failed to read crash notification setting") }.getOrDefault(true)

        if (showingNotifications) {
            // Notifications can fail (restricted channel, dead PendingIntent); that must
            // neither propagate into the log pipeline nor stop other pending crashes
            runCatching { notificationsLocalDataSource.sendErrorNotification(finalCrash, crashLog) }
                .onFailure { Timber.e(it, "Failed to notify crash ${appCrash.packageName}") }
        }
    }

    /**
     * Writes the whole buffered logcat to a file next to the crash log. Capped so a long
     * session cannot fill the disk: the dump is a context snapshot, not an archive.
     */
    private suspend fun writeLogDump(dateAndTime: Long): File? = withContext(ioDispatcher) {
        runCatching {
            val lines = getLogsSnapshotUseCase()

            if (lines.isEmpty()) return@runCatching null

            val dumpFile = File(logsDir, "$dateAndTime-dump.log")
            var writtenChars = 0L
            var writtenLines = 0

            dumpFile.bufferedWriter().use { writer ->
                for (line in lines) {
                    if (writtenChars >= MAX_DUMP_CHARS || writtenLines >= MAX_DUMP_LINES) break

                    writer.write(line.originalContent)
                    writer.newLine()

                    writtenChars += line.originalContent.length + 1
                    writtenLines++
                }
            }

            if (writtenLines == 0) {
                dumpFile.delete()

                null
            } else {
                dumpFile
            }
        }.onFailure { Timber.w(it, "Failed to write crash log dump") }.getOrNull()
    }

    private companion object {
        const val MAX_DUMP_LINES = 50_000
        const val MAX_DUMP_CHARS = 16L * 1024 * 1024
    }
}
