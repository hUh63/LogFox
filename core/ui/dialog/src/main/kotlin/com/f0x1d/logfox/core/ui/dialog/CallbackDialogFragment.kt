package com.f0x1d.logfox.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * An AlertDialog owned by the FragmentManager.
 *
 * A plain `MaterialAlertDialogBuilder(...).show()` dialog is owned by the Activity: on
 * rotation the Activity destroys it together with its button listeners, so a confirmation
 * or an in-progress edit was silently lost. A DialogFragment is retained by the
 * FragmentManager and re-creates its dialog on the new Activity, keeping its callbacks.
 */
class CallbackDialogFragment : DialogFragment() {

    /** Null after process death: lambdas cannot be stored in a Bundle. */
    var configure: ((Context, Bundle?, MaterialAlertDialogBuilder) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = MaterialAlertDialogBuilder(requireContext())
        .also { configure?.invoke(requireContext(), savedInstanceState, it) }
        .create()

    override fun onStart() {
        super.onStart()

        // After process death the caller's callback is gone. An empty dialog would only
        // block the UI, and firing an unknown action is worse than closing it.
        if (configure == null) dismissAllowingStateLoss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        // Keep what the user typed when the activity is re-created
        dialog?.findViewById<EditText>(R.id.text)?.let { outState.putCharSequence(KEY_TEXT, it.text) }
    }

    companion object {
        const val KEY_TEXT = "dialog_text"
    }
}

fun FragmentManager.showCallbackDialog(
    tag: String,
    configure: (Context, Bundle?, MaterialAlertDialogBuilder) -> Unit,
) {
    // Two quick taps used to stack two identical dialogs, and confirming the top one
    // still ran the first one's callback
    if (findFragmentByTag(tag) != null) return

    CallbackDialogFragment()
        .apply { this.configure = configure }
        .show(this, tag)
}
