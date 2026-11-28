package com.f0x1d.logfox.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * An AlertDialog owned by the FragmentManager.
 *
 * Plain `MaterialAlertDialogBuilder(...).show()` dialogs are Activity-owned: a rotation
 * destroys them together with the typed text and the button listeners. A DialogFragment
 * survives in the FragmentManager, but its fields do NOT (the fragment instance is
 * recreated), so the dialog spec is kept in [DialogHostViewModel] and looked up by tag.
 * The widget state (typed text, switches) is stored in the saved-instance Bundle.
 */
class CallbackDialogFragment : DialogFragment() {

    private var savedWidgetState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedWidgetState = savedInstanceState
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = MaterialAlertDialogBuilder(requireContext())
        spec()?.configure(requireContext(), savedInstanceState, builder)
        return builder.create()
    }

    override fun onStart() {
        super.onStart()

        // Process death: the spec is gone. An empty dialog would only block the UI.
        if (spec() == null) {
            dismissAllowingStateLoss()
            return
        }

        savedWidgetState?.let { restoreWidgetState(dialog?.window?.decorView, it) }
        savedWidgetState = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveWidgetState(dialog?.window?.decorView, outState)
    }

    private fun spec(): DialogSpec? {
        val key = arguments?.getString(KEY_TAG) ?: return null
        val activity = activity ?: return null
        return ViewModelProvider(activity).get(DIALOG_VM_KEY, DialogHostViewModel::class.java).get(key)
    }

    companion object {
        const val KEY_TAG = "dialog_tag"

        private const val DIALOG_VM_KEY = "logfox_dialog_host"
    }
}

/**
 * Shows a retained dialog. The tag is also the key of its specification, so repeated taps
 * on the same action never stack two identical dialogs.
 */
fun Context.showCallbackDialog(
    tag: String,
    configure: (Context, Bundle?, MaterialAlertDialogBuilder) -> Unit,
) {
    val activity = this as? FragmentActivity ?: return

    ViewModelProvider(activity)
        .get(DIALOG_VM_KEY, DialogHostViewModel::class.java)
        .put(tag, DialogSpec(configure))

    if (activity.supportFragmentManager.findFragmentByTag(tag) != null) return

    CallbackDialogFragment()
        .apply { arguments = bundleOf(CallbackDialogFragment.KEY_TAG to tag) }
        .show(activity.supportFragmentManager, tag)
}

/**
 * Finds the current (post-rotation) instance of a fragment for dialog callbacks.
 *
 * The whole fragment tree is searched, not only the activity's direct children: every screen
 * is hosted inside a NavHostFragment, whose fragments live in its childFragmentManager. Looking
 * only at `supportFragmentManager.fragments` therefore found just the NavHost (and the dialog
 * itself), so all dialog callbacks - pick a backend, confirm a deletion, rename a filter -
 * resolved to null and the tap silently did nothing.
 */
inline fun <reified T : androidx.fragment.app.Fragment> Context.currentFragment(): T? =
    (this as? FragmentActivity)?.supportFragmentManager?.findAddedFragment(T::class.java)

fun <T : androidx.fragment.app.Fragment> androidx.fragment.app.FragmentManager.findAddedFragment(
    type: Class<T>,
): T? {
    // toList(): the manager must not be iterated while callbacks could modify it
    fragments.toList().forEach { fragment ->
        if (type.isInstance(fragment) && fragment.isAdded) return type.cast(fragment)

        val child = fragment.childFragmentManager

        if (child.fragments.isNotEmpty()) {
            child.findAddedFragment(type)?.let { return it }
        }
    }

    return null
}

private const val DIALOG_VM_KEY = "logfox_dialog_host"
private const val KEY_TEXT_PREFIX = "widget_text_"
private const val KEY_CHECKED_PREFIX = "widget_checked_"

private fun View.forEachWidget(block: (View) -> Unit) {
    if (id != View.NO_ID) block(this)
    if (this is ViewGroup) {
        for (index in 0 until childCount) getChildAt(index).forEachWidget(block)
    }
}

private fun saveWidgetState(root: View?, outState: Bundle) {
    root ?: return
    root.forEachWidget { view ->
        when (view) {
            is EditText -> outState.putCharSequence(KEY_TEXT_PREFIX + view.id, view.text)
            is CompoundButton -> outState.putBoolean(KEY_CHECKED_PREFIX + view.id, view.isChecked)
        }
    }
}

private fun restoreWidgetState(root: View?, state: Bundle) {
    root ?: return
    root.forEachWidget { view ->
        val textKey = KEY_TEXT_PREFIX + view.id
        val checkedKey = KEY_CHECKED_PREFIX + view.id
        when {
            view is EditText && state.containsKey(textKey) -> {
                view.setText(state.getCharSequence(textKey))
                view.setSelection(view.text?.length ?: 0)
            }
            view is CompoundButton && state.containsKey(checkedKey) -> {
                view.isChecked = state.getBoolean(checkedKey)
            }
        }
    }
}
