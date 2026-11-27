package com.f0x1d.logfox.core.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import com.f0x1d.logfox.core.ui.dialog.databinding.DialogTextBinding
import com.f0x1d.logfox.feature.strings.Strings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val DRAFTS_FILE_NAME = "dialog_drafts"
private const val DRAFT_KEY_PREFIX = "draft_"

/**
 * A text dialog that keeps the typed text across process death.
 *
 * A DialogFragment is restored by the FragmentManager after process death, but its
 * specification (a lambda) is not, so the dialog closes itself and everything the user typed
 * is gone. The typed text is therefore mirrored into a one-shot draft while the dialog is
 * open: any normal exit (save/close) deletes it, so a leftover draft can only be the result
 * of the process being killed — and it is consumed (read + deleted) the next time this
 * dialog is opened.
 */
fun Context.showEditTextDialog(
    title: CharSequence?,
    initialText: String?,
    setupViews: (DialogTextBinding) -> Unit = {},
    setupDialog: MaterialAlertDialogBuilder.() -> Unit = {},
    onSave: (Context, String?) -> Unit,
) {
    val tag = "edit_text_$title"
    val drafts = getSharedPreferences(DRAFTS_FILE_NAME, Context.MODE_PRIVATE)
    val draftKey = DRAFT_KEY_PREFIX + tag

    val draft = drafts.getString(draftKey, null)?.also {
        drafts.edit().remove(draftKey).apply()
    }

    showCallbackDialog(
        tag = tag,
        configure = { context, _, builder ->
            // Inflate with the CURRENT context: an Activity-owned dialog recreated after a
            // rotation must not hold on to the destroyed Activity's inflater/theme.
            val dialogBinding = DialogTextBinding.inflate(LayoutInflater.from(context))
            setupViews(dialogBinding)
            dialogBinding.text.setText(draft ?: initialText)

            dialogBinding.text.doAfterTextChanged { text ->
                if (text.isNullOrEmpty()) {
                    drafts.edit().remove(draftKey).apply()
                } else {
                    drafts.edit().putString(draftKey, text.toString()).apply()
                }
            }

            builder
                .setTitle(title)
                .setView(dialogBinding.root)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    drafts.edit().remove(draftKey).apply()
                    onSave(context, dialogBinding.text.text?.toString())
                }
                .setNegativeButton(Strings.close) { _, _ ->
                    drafts.edit().remove(draftKey).apply()
                }
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
}
