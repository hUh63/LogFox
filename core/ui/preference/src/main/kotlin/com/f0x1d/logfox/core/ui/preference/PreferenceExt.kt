package com.f0x1d.logfox.core.ui.preference

import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import com.f0x1d.logfox.core.ui.dialog.databinding.DialogTextBinding
import com.f0x1d.logfox.core.ui.dialog.showCallbackDialog
import com.f0x1d.logfox.core.ui.dialog.showEditTextDialog
import com.f0x1d.logfox.feature.strings.Strings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Preference.setupAsEditTextPreference(
    setupViews: (DialogTextBinding) -> Unit,
    setupDialog: MaterialAlertDialogBuilder.() -> Unit,
    get: () -> String?,
    save: (String?) -> Unit,
) = setOnPreferenceClickListener {
    context.showEditTextDialog(
        title = title,
        initialText = get(),
        setupViews = setupViews,
        setupDialog = setupDialog,
        onSave = save,
    )
    return@setOnPreferenceClickListener true
}

fun Preference.setupAsListPreference(
    setupDialog: MaterialAlertDialogBuilder.() -> Unit,
    items: Array<String>,
    selected: () -> Int,
    onSelected: (Int) -> Unit,
) = setOnPreferenceClickListener {
    // A plain Activity-owned dialog lost its selection callback on rotation; keeping it in
    // the FragmentManager means the choice is still delivered after a configuration change
    (context as? FragmentActivity)?.supportFragmentManager?.showCallbackDialog(
        tag = "list_preference_$title",
        configure = { _, _, builder ->
            builder
                .setTitle(title)
                .setSingleChoiceItems(items, selected()) { dialog, which ->
                    dialog.cancel()
                    onSelected(which)
                }
                .setPositiveButton(Strings.close, null)
                .apply(setupDialog)
        },
    )
    return@setOnPreferenceClickListener true
}
