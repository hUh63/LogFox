package com.f0x1d.logfox.core.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import com.f0x1d.logfox.core.ui.dialog.databinding.DialogTextBinding
import com.f0x1d.logfox.feature.strings.Strings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Context.showEditTextDialog(
    title: CharSequence?,
    initialText: String?,
    setupViews: (DialogTextBinding) -> Unit = {},
    setupDialog: MaterialAlertDialogBuilder.() -> Unit = {},
    onSave: (String?) -> Unit,
) {
    // The dialog must be FragmentManager-owned, otherwise rotation destroys it along with
    // the typed text and the save callback. Preferences live in a FragmentActivity.
    val fragmentManager = (this as? FragmentActivity)?.supportFragmentManager ?: return

    fragmentManager.showCallbackDialog(
        tag = "edit_text_$title",
        configure = { context, savedState, builder ->
            val dialogBinding = DialogTextBinding.inflate(LayoutInflater.from(context))
            setupViews(dialogBinding)
            dialogBinding.text.setText(savedState?.getCharSequence(CallbackDialogFragment.KEY_TEXT) ?: initialText)

            builder
                .setTitle(title)
                .setView(dialogBinding.root)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onSave(dialogBinding.text.text?.toString())
                }
                .setNegativeButton(Strings.close, null)
                .apply(setupDialog)

            // setOnShowListener lives on the dialog, not on the builder. Posting on the
            // inflated view runs once the dialog window has attached it.
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
}
