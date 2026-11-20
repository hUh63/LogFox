package com.f0x1d.logfox.feature.crashes.presentation.details

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import com.f0x1d.logfox.feature.crashes.api.data.notificationChannelId
import javax.inject.Inject

internal class CrashDetailsReducer @Inject constructor() : Reducer<CrashDetailsState, CrashDetailsCommand, CrashDetailsSideEffect> {

    override fun reduce(
        state: CrashDetailsState,
        command: CrashDetailsCommand,
    ): ReduceResult<CrashDetailsState, CrashDetailsSideEffect> = when (command) {
        is CrashDetailsCommand.CrashLoaded -> state.copy(
            crash = command.crash,
            crashLog = command.crashLog,
            originalCrashLog = command.crashLog,
            searchMatchRanges = emptyList(),
        ).noSideEffects()

        is CrashDetailsCommand.ImportMappingClicked -> state.withSideEffects(
            CrashDetailsSideEffect.LaunchMappingPicker,
        )

        is CrashDetailsCommand.ImportMapping -> state.withSideEffects(
            CrashDetailsSideEffect.ImportMapping(command.uri),
        )

        is CrashDetailsCommand.MappingImported -> state.copy(
            mappingAvailable = command.success || state.mappingAvailable,
        ).withSideEffects(
            CrashDetailsSideEffect.MappingImported(command.success),
            CrashDetailsSideEffect.LoadCrash,
        )

        is CrashDetailsCommand.ClearMappingClicked -> state.withSideEffects(
            CrashDetailsSideEffect.ClearMappingStored,
        )

        is CrashDetailsCommand.MappingCleared -> state.copy(
            mappingAvailable = false,
        ).withSideEffects(
            CrashDetailsSideEffect.LoadCrash,
        )

        is CrashDetailsCommand.ExportFailed -> state.withSideEffects(
            CrashDetailsSideEffect.ExportFailed(command.message),
        )

        is CrashDetailsCommand.ShowReplaceDialog -> state.withSideEffects(
            CrashDetailsSideEffect.ShowReplace,
        )

        is CrashDetailsCommand.ApplyReplace -> state.copy(
            // Applied to the original log, so replacements do not stack on each other.
            // Lets the user strip noisy R8 tokens out of a stacktrace (F0x1d/LogFox#264)
            crashLog = state.originalCrashLog?.replace(command.find, command.replaceWith)
                ?: state.crashLog,
            searchQuery = "",
            searchMatchRanges = emptyList(),
        ).noSideEffects()

        is CrashDetailsCommand.ClearReplace -> state.copy(
            crashLog = state.originalCrashLog,
            searchQuery = "",
            searchMatchRanges = emptyList(),
        ).noSideEffects()

        is CrashDetailsCommand.BlacklistStatusLoaded -> state.copy(
            blacklisted = command.blacklisted,
        ).noSideEffects()

        is CrashDetailsCommand.WrapLinesClicked -> state.withSideEffects(
            CrashDetailsSideEffect.SetWrapCrashLogLines(wrap = !state.wrapCrashLogLines),
        )

        is CrashDetailsCommand.PreferencesUpdated -> state.copy(
            wrapCrashLogLines = command.wrapCrashLogLines,
            useSeparateNotificationsChannelsForCrashes = command.useSeparateNotificationsChannelsForCrashes,
            textSize = command.textSize,
        ).noSideEffects()

        is CrashDetailsCommand.IncreaseTextSize -> state.withSideEffects(
            CrashDetailsSideEffect.SetTextSize(
                size = (state.textSize + TEXT_SIZE_STEP).coerceAtMost(TEXT_SIZE_MAX),
            ),
        )

        is CrashDetailsCommand.DecreaseTextSize -> state.withSideEffects(
            CrashDetailsSideEffect.SetTextSize(
                size = (state.textSize - TEXT_SIZE_STEP).coerceAtLeast(TEXT_SIZE_MIN),
            ),
        )

        is CrashDetailsCommand.OpenAppInfoClicked -> {
            val appCrash = state.crash
            if (appCrash != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.OpenAppInfo(appCrash.packageName),
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.OpenNotificationSettingsClicked -> {
            val appCrash = state.crash
            if (appCrash != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.OpenNotificationSettings(appCrash.notificationChannelId),
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.BlacklistClicked -> {
            val appCrash = state.crash
            if (appCrash != null) {
                if (state.blacklisted == false) {
                    state.withSideEffects(CrashDetailsSideEffect.ConfirmBlacklist)
                } else {
                    state.withSideEffects(CrashDetailsSideEffect.ChangeBlacklist(appCrash))
                }
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.ConfirmBlacklist -> {
            val appCrash = state.crash
            if (appCrash != null) {
                state.withSideEffects(CrashDetailsSideEffect.ChangeBlacklist(appCrash))
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.DeleteClicked -> state.withSideEffects(
            CrashDetailsSideEffect.ConfirmDelete,
        )

        is CrashDetailsCommand.ConfirmDelete -> {
            val appCrash = state.crash
            if (appCrash != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.DeleteCrash(appCrash),
                    CrashDetailsSideEffect.Close,
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.ExportCrashToFileClicked -> {
            val appCrash = state.crash
            if (appCrash != null && state.crashLog != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.PrepareFileExport(
                        packageName = appCrash.packageName,
                        dateAndTime = appCrash.dateAndTime,
                    ),
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.ExportCrashToZipClicked -> {
            val appCrash = state.crash
            if (appCrash != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.PrepareZipExport(
                        packageName = appCrash.packageName,
                        dateAndTime = appCrash.dateAndTime,
                    ),
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.FileExportPickerReady -> {
            state.withSideEffects(
                CrashDetailsSideEffect.LaunchFileExportPicker(filename = command.filename),
            )
        }

        is CrashDetailsCommand.ZipExportPickerReady -> {
            state.withSideEffects(
                CrashDetailsSideEffect.LaunchZipExportPicker(filename = command.filename),
            )
        }

        is CrashDetailsCommand.ExportCrashToFile -> {
            state.withSideEffects(
                CrashDetailsSideEffect.ExportCrashToFile(
                    uri = command.uri,
                ),
            )
        }

        is CrashDetailsCommand.ExportCrashToZip -> {
            state.withSideEffects(
                CrashDetailsSideEffect.ExportCrashToZip(
                    uri = command.uri,
                ),
            )
        }

        is CrashDetailsCommand.CopyCrashLog -> {
            val crashLog = state.crashLog
            if (crashLog != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.CopyText(crashLog),
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.ShareCrashLog -> {
            val crashLog = state.crashLog
            if (crashLog != null) {
                state.withSideEffects(
                    CrashDetailsSideEffect.ShareCrashLog(crashLog),
                )
            } else {
                state.noSideEffects()
            }
        }

        is CrashDetailsCommand.SearchInLog -> {
            val query = command.query
            val crashLog = state.crashLog.orEmpty()
            val ranges = if (query.isNotEmpty()) {
                val lowerLog = crashLog.lowercase(java.util.Locale.ENGLISH)
                val lowerQuery = query.lowercase(java.util.Locale.ENGLISH)
                buildList {
                    var index = 0
                    while (true) {
                        index = lowerLog.indexOf(lowerQuery, index)
                        if (index == -1) break
                        add(index until index + lowerQuery.length)
                        index += lowerQuery.length
                    }
                }
            } else {
                emptyList()
            }
            state.copy(searchQuery = query, searchMatchRanges = ranges).noSideEffects()
        }
    }

    private companion object {
        const val TEXT_SIZE_STEP = 2
        const val TEXT_SIZE_MIN = 8
        const val TEXT_SIZE_MAX = 30
    }
}
