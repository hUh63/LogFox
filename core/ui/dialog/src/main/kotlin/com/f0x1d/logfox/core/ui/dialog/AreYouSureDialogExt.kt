package com.f0x1d.logfox.core.ui.dialog

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.f0x1d.logfox.core.ui.icons.Icons
import com.f0x1d.logfox.feature.strings.Strings

fun FragmentActivity.showAreYouSureDialog(
    title: Int,
    message: Int = Strings.are_you_sure,
    onConfirm: (Context) -> Unit,
) = this.showAreYouSureDialogInternal(title, message, onConfirm)

fun FragmentActivity.showAreYouSureDeleteDialog(onConfirm: (Context) -> Unit) = showAreYouSureDialog(
    title = Strings.delete,
    onConfirm = onConfirm,
)

fun FragmentActivity.showAreYouSureClearDialog(onConfirm: (Context) -> Unit) = showAreYouSureDialog(
    title = Strings.clear,
    onConfirm = onConfirm,
)

fun Fragment.showAreYouSureDialog(
    title: Int,
    message: Int = Strings.are_you_sure,
    onConfirm: (Context) -> Unit,
) = requireContext().showAreYouSureDialogInternal(title, message, onConfirm)

fun Fragment.showAreYouSureDeleteDialog(onConfirm: (Context) -> Unit) = showAreYouSureDialog(
    title = Strings.delete,
    onConfirm = onConfirm,
)

fun Fragment.showAreYouSureClearDialog(onConfirm: (Context) -> Unit) = showAreYouSureDialog(
    title = Strings.clear,
    onConfirm = onConfirm,
)

private fun Context.showAreYouSureDialogInternal(
    title: Int,
    message: Int,
    onConfirm: (Context) -> Unit,
) = showCallbackDialog(
    tag = "are_you_sure_${title}_${message}",
    configure = { context, _, builder ->
        builder
            .setIcon(Icons.ic_dialog_warning)
            .setTitle(title)
            .setMessage(message)
            // The callback receives the current context so it can resolve the CURRENT
            // fragment instance: the captured one is detached after a rotation
            .setPositiveButton(Strings.yes) { _, _ -> onConfirm(context) }
            .setNegativeButton(Strings.no, null)
    },
)
