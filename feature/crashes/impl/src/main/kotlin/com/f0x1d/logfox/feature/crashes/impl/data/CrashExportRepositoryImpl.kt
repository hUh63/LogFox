package com.f0x1d.logfox.feature.crashes.impl.data

import android.net.Uri
import com.f0x1d.logfox.core.context.deviceData
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.core.io.putZipEntry
import com.f0x1d.logfox.feature.crashes.api.data.CrashExportRepository
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRepository
import com.f0x1d.logfox.feature.datetime.api.DateTimeFormatter
import com.f0x1d.logfox.feature.export.api.data.ExportRepository
import com.f0x1d.logfox.feature.logging.api.data.LogLineFormatterRepository
import com.f0x1d.logfox.feature.logging.api.data.LogLineParser
import com.f0x1d.logfox.feature.preferences.api.data.ServiceSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal class CrashExportRepositoryImpl @Inject constructor(
    private val crashesRepository: CrashesRepository,
    private val exportRepository: ExportRepository,
    private val serviceSettingsRepository: ServiceSettingsRepository,
    private val appInfoDataSource: AppInfoDataSource,
    private val dateTimeFormatter: DateTimeFormatter,
    private val logLineParser: LogLineParser,
    private val logLineFormatterRepository: LogLineFormatterRepository,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : CrashExportRepository {

    override suspend fun exportToFile(crashId: Long, uri: Uri) = withContext(ioDispatcher) {
        // Everything below (PackageManager lookups, whole-file read + reformat) used to run
        // on the main thread, which froze the UI / triggered ANRs on large crash logs
        val appCrash = crashesRepository.getById(crashId)
            ?: error("Crash $crashId no longer exists")

        val dumpFile = appCrash.logDumpFile?.takeIf { it.exists() }

        val content = buildString {
            if (serviceSettingsRepository.includeDeviceInfoInArchives().value) {
                appendLine(deviceData)
                appendLine()
            }
            if (serviceSettingsRepository.includeAppInfoInExports().value) {
                appendLine(appInfoDataSource.getAppInfo(appCrash.packageName).format())
                appendLine()
            }
            appCrash.logFile?.let { append(formatLogFileContent(it)) }

            // The crash log holds the exception lines only; the dump holds the whole logcat
            // buffer around the crash. Exporting "the crash to a file" without it gave the
            // user a file that could not explain why the app died.
            if (dumpFile != null) {
                appendLine()
                appendLine()
                appendLine("========== FULL LOG DUMP (logcat buffer around the crash) ==========")
                appendLine()
            }
        }

        if (content.isBlank() && dumpFile == null) error("Nothing to export for crash $crashId")

        if (dumpFile == null) {
            exportRepository.writeContentToUri(uri, content)
        } else {
            // Streamed in: the dump can be up to 16M characters, and formatting it into one
            // String (then encoding it) held several copies of it at once - an OOM on low end
            // devices, right when the user needed the file
            exportRepository.writeContentAndFileToUri(uri, content, dumpFile)
        }
    }

    override suspend fun exportToZip(crashId: Long, uri: Uri) = withContext(ioDispatcher) {
        val appCrash = crashesRepository.getById(crashId) ?: return@withContext

        val deviceInfo = if (serviceSettingsRepository.includeDeviceInfoInArchives().value) {
            deviceData
        } else {
            null
        }

        val appInfo = if (serviceSettingsRepository.includeAppInfoInExports().value) {
            appInfoDataSource.getAppInfo(appCrash.packageName).format()
        } else {
            null
        }

        val logExtension = if (serviceSettingsRepository.exportLogsAsTxt().value) "txt" else "log"
        val suffix = dateTimeFormatter.formatForExport(appCrash.dateAndTime)

        exportRepository.writeZipToUri(uri) {
            if (deviceInfo != null) {
                putZipEntry(
                    name = "device_${suffix}.txt",
                    content = deviceInfo.encodeToByteArray(),
                )
            }

            if (appInfo != null) {
                putZipEntry(
                    name = "app_${suffix}.txt",
                    content = appInfo.encodeToByteArray(),
                )
            }

            appCrash.logFile?.let { file ->
                putZipEntry(
                    name = "crash_${suffix}.$logExtension",
                    content = formatLogFileContent(file).encodeToByteArray(),
                )
            }

            appCrash.logDumpFile?.let { file ->
                // Copied as-is: formatting a 50k line dump allocated far more than the file
                // itself. The dump is raw logcat, it already carries level/time/pid
                putZipEntry(
                    name = "dump_${suffix}.log",
                    file = file,
                )
            }
        }
    }

    private fun formatLogFileContent(file: File): String {
        // Never let a missing log file break the whole export (F0x1d/LogFox#254)
        if (!file.exists()) return ""

        val lines = runCatching { file.readLines() }.getOrElse { return "" }
        return lines.mapIndexed { index, line ->
            logLineParser.parse(index.toLong(), line)?.let { logLine ->
                logLineFormatterRepository.formatForExport(logLine)
            } ?: line
        }.joinToString("\n")
    }
}
