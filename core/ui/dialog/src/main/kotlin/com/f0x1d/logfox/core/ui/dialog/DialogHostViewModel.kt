package com.f0x1d.logfox.core.ui.dialog

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A DialogFragment is re-created from its arguments after a configuration change, so any
 * lambda stored in a field is lost. The dialog specification therefore lives in an
 * Activity-scoped ViewModel, which survives rotations. After process death the spec is
 * gone and the restored (empty) dialog closes itself instead of doing nothing on click.
 *
 * Specifications only capture stable data (strings, resource ids, ViewModels, commands),
 * never a Context or a View: the current Context is passed into [DialogSpec.configure].
 */
class DialogHostViewModel : ViewModel() {

    private val specs = HashMap<String, DialogSpec>()

    fun put(key: String, spec: DialogSpec) {
        specs[key] = spec
    }

    fun get(key: String): DialogSpec? = specs[key]

    override fun onCleared() {
        specs.clear()
    }
}

data class DialogSpec(
    val configure: (Context, Bundle?, MaterialAlertDialogBuilder) -> Unit,
)
