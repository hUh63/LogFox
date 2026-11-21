package com.f0x1d.logfox.feature.crashes.presentation.details

import com.f0x1d.logfox.core.di.DefaultDispatcher
import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.crashes.api.domain.CheckAppDisabledUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.ClearMappingUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.DeleteCrashUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.ImportMappingUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.ExportCrashToFileUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.ExportCrashToZipUseCase
import com.f0x1d.logfox.feature.crashes.api.domain.GetCrashAndLogByIdFlowUseCase
import com.f0x1d.logfox.feature.crashes.presentation.details.di.CrashId
import com.f0x1d.logfox.feature.datetime.api.DateTimeFormatter
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.GetUseSeparateNotificationsChannelsForCrashesFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.GetWrapCrashLogLinesFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.crashes.SetWrapCrashLogLinesUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.logs.GetLogsTextSizeFlowUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.logs.SetLogsTextSizeUseCase
import com.f0x1d.logfox.feature.preferences.api.domain.service.GetLogFileExtensionUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

internal class CrashDetailsEffectHandler @Inject constructor(
    @CrashId private val crashId: Long,
    private val getCrashAndLogByIdFlowUseCase: GetCrashAndLogByIdFlowUseCase,
    private val deleteCrashUseCase: DeleteCrashUseCase,
    private val checkAppDisabledUseCase: CheckAppDisabledUseCase,
    private val exportCrashToFileUseCase: ExportCrashToFileUseCase,
    private val exportCrashToZipUseCase: ExportCrashToZipUseCase,
    private val getWrapCrashLogLinesFlowUseCase: GetWrapCrashLogLinesFlowUseCase,
    private val setWrapCrashLogLinesUseCase: SetWrapCrashLogLinesUseCase,
    private val getUseSeparateNotificationsChannelsForCrashesFlowUseCase: GetUseSeparateNotificationsChannelsForCrashesFlowUseCase,
    private val importMappingUseCase: ImportMappingUseCase,
    private val clearMappingUseCase: ClearMappingUseCase,
    private val getLogsTextSizeFlowUseCase: GetLogsTextSizeFlowUseCase,
    private val setLogsTextSizeUseCase: SetLogsTextSizeUseCase,
    private val getLogFileExtensionUseCase: GetLogFileExtensionUseCase,
    private val dateTimeFormatter: DateTimeFormatter,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : EffectHandler<CrashDetailsSideEffect, CrashDetailsCommand> {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun handle(
        effect: CrashDetailsSideEffect,
        onCommand: suspend (CrashDetailsCommand) -> Unit,
    ) {
        when (effect) {
            is CrashDetailsSideEffect.LoadCrash -> {
                getCrashAndLogByIdFlowUseCase(crashId).collect { value ->
                    value?.let { (crash, crashLog) ->
                        onCommand(CrashDetailsCommand.CrashLoaded(crash, crashLog))
                    }
                }
            }

            is CrashDetailsSideEffect.ReloadCrash -> {
                // One-shot. LoadCrash keeps collecting for the whole screen, so re-sending
                // it on every mapping import/clear leaked one more permanent collector, and
                // every later DB write re-read + retraced the log once per leaked collector
                val value = getCrashAndLogByIdFlowUseCase(crashId).firstOrNull()

                value?.let { (crash, crashLog) ->
                    onCommand(CrashDetailsCommand.CrashLoaded(crash, crashLog))
                }
            }

            is CrashDetailsSideEffect.FindInLog -> {
                // Scanning a multi-megabyte log must not run on the main thread
                val ranges = withContext(defaultDispatcher) {
                    findMatches(effect.text, effect.query)
                }
                onCommand(CrashDetailsCommand.SearchMatchesFound(effect.query, ranges))
            }

            is CrashDetailsSideEffect.SetWrapCrashLogLines -> {
                setWrapCrashLogLinesUseCase(effect.wrap)
            }

            is CrashDetailsSideEffect.SetTextSize -> {
                setLogsTextSizeUseCase(effect.size)
            }

            is CrashDetailsSideEffect.ObservePreferences -> {
                combine(
                    getWrapCrashLogLinesFlowUseCase(),
                    getUseSeparateNotificationsChannelsForCrashesFlowUseCase(),
                    getLogsTextSizeFlowUseCase(),
                ) { wrapCrashLogLines, useSeparateNotificationsChannelsForCrashes, textSize ->
                    CrashDetailsCommand.PreferencesUpdated(
                        wrapCrashLogLines = wrapCrashLogLines,
                        useSeparateNotificationsChannelsForCrashes = useSeparateNotificationsChannelsForCrashes,
                        textSize = textSize,
                    )
                }.collect { command ->
                    onCommand(command)
                }
            }

            is CrashDetailsSideEffect.PrepareFileExport -> {
                val filename = exportFilename(effect.packageName, effect.dateAndTime, getLogFileExtensionUseCase())
                onCommand(CrashDetailsCommand.FileExportPickerReady(filename))
            }

            is CrashDetailsSideEffect.PrepareZipExport -> {
                val filename = exportFilename(effect.packageName, effect.dateAndTime, "zip")
                onCommand(CrashDetailsCommand.ZipExportPickerReady(filename))
            }

            is CrashDetailsSideEffect.ImportMapping -> {
                val success = importMappingUseCase(effect.uri)

                onCommand(CrashDetailsCommand.MappingImported(success))
            }

            is CrashDetailsSideEffect.ClearMappingStored -> {
                clearMappingUseCase()

                onCommand(CrashDetailsCommand.MappingCleared)
            }

            is CrashDetailsSideEffect.ExportCrashToZip -> {
                val result = exportCrashToZipUseCase(crashId, effect.uri)

                // The Result used to be dropped entirely: a failed export looked like a
                // success and left a truncated/empty file behind
                if (result.isFailure) {
                    onCommand(
                        CrashDetailsCommand.ExportFailed(result.exceptionOrNull()?.localizedMessage)
                    )
                }
            }

            is CrashDetailsSideEffect.ExportCrashToFile -> {
                val result = exportCrashToFileUseCase(crashId, effect.uri)

                if (result.isFailure) {
                    onCommand(
                        CrashDetailsCommand.ExportFailed(result.exceptionOrNull()?.localizedMessage)
                    )
                }
            }

            is CrashDetailsSideEffect.ChangeBlacklist -> {
                checkAppDisabledUseCase(effect.appCrash.packageName)
            }

            is CrashDetailsSideEffect.DeleteCrash -> {
                deleteCrashUseCase(effect.appCrash.id)
            }

            // UI side effects - handled by Fragment
            is CrashDetailsSideEffect.OpenAppInfo -> Unit
            is CrashDetailsSideEffect.OpenNotificationSettings -> Unit
            is CrashDetailsSideEffect.ConfirmBlacklist -> Unit
            is CrashDetailsSideEffect.ConfirmDelete -> Unit
            is CrashDetailsSideEffect.CopyText -> Unit
            is CrashDetailsSideEffect.ShareCrashLog -> Unit
            is CrashDetailsSideEffect.Close -> Unit
            is CrashDetailsSideEffect.ShowReplace -> Unit
            is CrashDetailsSideEffect.MappingImported -> Unit
            is CrashDetailsSideEffect.LaunchMappingPicker -> Unit
            is CrashDetailsSideEffect.LaunchFileExportPicker -> Unit
            is CrashDetailsSideEffect.LaunchZipExportPicker -> Unit
            is CrashDetailsSideEffect.ExportFailed -> Unit
        }
    }

    private fun findMatches(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()

        val lowerText = text.lowercase(Locale.ENGLISH)
        val lowerQuery = query.lowercase(Locale.ENGLISH)

        if (lowerQuery.isEmpty()) return emptyList()

        val ranges = ArrayList<IntRange>()
        var index = 0

        while (ranges.size < MAX_SEARCH_MATCHES) {
            index = lowerText.indexOf(lowerQuery, index)

            if (index == -1) break

            ranges += index until index + lowerQuery.length
            index += lowerQuery.length
        }

        return ranges
    }

    private companion object {
        // Highlighting every occurrence of "at" in a big log meant tens of thousands of
        // spans (dozens of MB of objects). 1000 is far beyond what anyone can scroll to
        const val MAX_SEARCH_MATCHES = 1_000
    }

    private fun exportFilename(packageName: String, dateAndTime: Long, extension: String): String {
        val pkg = packageName.replace(".", "-")
        val formattedDate = dateTimeFormatter.formatForExport(dateAndTime)
        return "crash-$pkg-$formattedDate.$extension"
    }
}
