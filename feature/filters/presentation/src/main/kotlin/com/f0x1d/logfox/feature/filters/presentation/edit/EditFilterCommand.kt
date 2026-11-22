package com.f0x1d.logfox.feature.filters.presentation.edit

import android.net.Uri
import com.f0x1d.logfox.feature.filters.api.model.UserFilter

internal sealed interface EditFilterCommand {
    data object Load : EditFilterCommand
    data class FilterLoaded(val filter: UserFilter) : EditFilterCommand

    // Form field updates
    data class UpdateName(val name: String) : EditFilterCommand
    data class UpdateUid(val uid: String) : EditFilterCommand
    data class UpdatePid(val pid: String) : EditFilterCommand
    data class UpdateTid(val tid: String) : EditFilterCommand
    data class UpdatePackageName(val packageName: String) : EditFilterCommand
    data class UpdateTag(val tag: String) : EditFilterCommand
    data class UpdateContent(val content: String) : EditFilterCommand

    // Actions
    data object ToggleIncluding : EditFilterCommand
    data object ToggleEnabled : EditFilterCommand
    data object ToggleTagRegex : EditFilterCommand
    data object ToggleContentRegex : EditFilterCommand
    data class FilterLevel(val which: Int, val filtering: Boolean) : EditFilterCommand
    data object ClearLevels : EditFilterCommand
    data object Save : EditFilterCommand

    // Emitted after the auto-save finished while closing the screen
    data object SavedAndClose : EditFilterCommand
    data class Export(val uri: Uri) : EditFilterCommand

    // Close requested (back button/arrow/predictive back); reducer decides whether to confirm.
    data object AttemptClose : EditFilterCommand

    // User confirmed discarding unsaved changes in the dialog; close unconditionally.

    // Navigation
    data object SelectApp : EditFilterCommand
}
