package com.f0x1d.logfox.feature.crashes.presentation.details

import com.f0x1d.logfox.core.tea.PersistableSideEffect
import com.f0x1d.logfox.core.tea.ReliableSideEffect
import android.net.Uri
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import java.io.File

internal sealed interface CrashDetailsSideEffect {
    // Business logic side effects
    data object LoadCrash : CrashDetailsSideEffect

    data object ShowReplace : CrashDetailsSideEffect, ReliableSideEffect

    data object LaunchMappingPicker : CrashDetailsSideEffect, ReliableSideEffect

    data class ImportMapping(val uri: Uri) : CrashDetailsSideEffect

    data object ClearMappingStored : CrashDetailsSideEffect

    data object ReloadCrash : CrashDetailsSideEffect

    data class FindInLog(val query: String, val text: String) : CrashDetailsSideEffect

    data class MappingImported(val success: Boolean) : CrashDetailsSideEffect

    data object ObservePreferences : CrashDetailsSideEffect

    data class SetWrapCrashLogLines(val wrap: Boolean) : CrashDetailsSideEffect

    data class SetTextSize(val size: Int) : CrashDetailsSideEffect

    data class ExportCrashToZip(val uri: Uri) : CrashDetailsSideEffect

    data class ExportCrashToFile(val uri: Uri) : CrashDetailsSideEffect

    data class PrepareFileExport(val packageName: String, val dateAndTime: Long) : CrashDetailsSideEffect

    data class PrepareZipExport(val packageName: String, val dateAndTime: Long) : CrashDetailsSideEffect

    data class ChangeBlacklist(val appCrash: AppCrash) : CrashDetailsSideEffect

    data class DeleteCrash(val appCrash: AppCrash) : CrashDetailsSideEffect

    // UI side effects
    data class OpenAppInfo(val packageName: String) : CrashDetailsSideEffect, ReliableSideEffect
    data class OpenNotificationSettings(val channelId: String) : CrashDetailsSideEffect, ReliableSideEffect
    data object ConfirmBlacklist : CrashDetailsSideEffect, ReliableSideEffect
    data object ConfirmDelete : CrashDetailsSideEffect, ReliableSideEffect
    data class CopyText(val text: String) : CrashDetailsSideEffect, ReliableSideEffect
    data class ShareCrashLog(val text: String) : CrashDetailsSideEffect, ReliableSideEffect
    data object Close : CrashDetailsSideEffect, ReliableSideEffect
    data class LaunchFileExportPicker(val filename: String) : CrashDetailsSideEffect, ReliableSideEffect
    data class LaunchZipExportPicker(val filename: String) : CrashDetailsSideEffect, ReliableSideEffect
    data object ExportSucceeded : CrashDetailsSideEffect, ReliableSideEffect

    data class ExportFailed(val message: String?) : CrashDetailsSideEffect, PersistableSideEffect {
        override val persistedKey get() = "export_failed"

        // The screen may be gone for hours: without this the user never learns why the
        // export they started before leaving failed
        override val persistedArgs get() = buildMap { message?.let { put("message", it) } }
    }
    data class ViewLogDump(val file: File) : CrashDetailsSideEffect, ReliableSideEffect
    data object LogDumpUnavailable : CrashDetailsSideEffect, PersistableSideEffect {
        override val persistedKey get() = "log_dump_unavailable"
    }
}
