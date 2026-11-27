package com.f0x1d.logfox.core.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import com.f0x1d.logfox.core.ui.dialog.databinding.DialogTextBinding
import com.f0x1d.logfox.feature.strings.Strings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Context.showEditTextDialog(
    title: CharSequence?,
    initialText: String?,
    setupViews: (DialogTextBinding) -> Unit = {},
    setupDialog: MaterialAlertDialogBuilder.() -> Unit = {},
    onSave: (Context, String?) -> Unit,
) = showCallbackDialog(
    tag = "edit_text_$title",
    configure = { context, _, builder ->
        // Inflate with the CURRENT context: an Activity-owned dialog recreated after a
        // rotation must not hold on to the destroyed Activity's inflater/theme.
        val dialogBinding = DialogTextBinding.inflate(LayoutInflater.from(context))
        setupViews(dialogBinding)
        dialogBinding.text.setText(initialText)

        builder
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(context, dialogBinding.text.text?.toString())
            }
            .setNegativeButton(Strings.close, null)
            .apply(setupDialog)

        dialogBinding.root.post {
            dialogBinding.text.apply {
                requestFocus()
                setSelection(text?.length ?: 0)

                postDelayed({
                    context.getSystemService<InputMethodManager>()?.showSoftInput(
                        this,
                        InputMethodManager.SHOW_IMPLICIT,
                    )
                }, 100)
            }
        }
    },
)
