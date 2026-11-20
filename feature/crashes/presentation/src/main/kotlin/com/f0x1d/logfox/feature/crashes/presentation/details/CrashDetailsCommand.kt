package com.f0x1d.logfox.feature.crashes.presentation.details

import android.net.Uri
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash

internal sealed interface CrashDetailsCommand {
    data class CrashLoaded(val crash: AppCrash, val crashLog: String?) : CrashDetailsCommand

    data class BlacklistStatusLoaded(val blacklisted: Boolean?) : CrashDetailsCommand

    data class PreferencesUpdated(
        val wrapCrashLogLines: Boolean,
        val useSeparateNotificationsChannelsForCrashes: Boolean,
        val textSize: Int,
    ) : CrashDetailsCommand

    data object WrapLinesClicked : CrashDetailsCommand

    data object IncreaseTextSize : CrashDetailsCommand

    data object DecreaseTextSize : CrashDetailsCommand

    data object OpenAppInfoClicked : CrashDetailsCommand

    data object OpenNotificationSettingsClicked : CrashDetailsCommand

    data object BlacklistClicked : CrashDetailsCommand

    data object ConfirmBlacklist : CrashDetailsCommand

    data object DeleteClicked : CrashDetailsCommand

    data object ConfirmDelete : CrashDetailsCommand

    data object ExportCrashToFileClicked : CrashDetailsCommand

    data object ExportCrashToZipClicked : CrashDetailsCommand

    data class FileExportPickerReady(val filename: String) : CrashDetailsCommand

    data class ZipExportPickerReady(val filename: String) : CrashDetailsCommand

    data class ExportCrashToFile(val uri: Uri) : CrashDetailsCommand

    data class ExportCrashToZip(val uri: Uri) : CrashDetailsCommand

    data object CopyCrashLog : CrashDetailsCommand

    data object ShareCrashLog : CrashDetailsCommand

    data class SearchInLog(val query: String) : CrashDetailsCommand

    data object ShowReplaceDialog : CrashDetailsCommand

    data class ApplyReplace(val find: String, val replaceWith: String) : CrashDetailsCommand

    data object ClearReplace : CrashDetailsCommand

    data object ImportMappingClicked : CrashDetailsCommand

    data class ImportMapping(val uri: Uri) : CrashDetailsCommand

    data class MappingImported(val success: Boolean) : CrashDetailsCommand

    data object ClearMappingClicked : CrashDetailsCommand

    data object MappingCleared : CrashDetailsCommand

    /** Export finished with an error; [message] is the localized failure reason. */
    data class ExportFailed(val message: String?) : CrashDetailsCommand
}
