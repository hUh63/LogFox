package com.f0x1d.logfox.core.ui.dialog

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.f0x1d.logfox.core.ui.icons.Icons
import com.f0x1d.logfox.feature.strings.Strings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun FragmentActivity.showAreYouSureDialog(
    title: Int,
    message: Int = Strings.are_you_sure,
    okClicked: () -> Unit,
) = supportFragmentManager.showAreYouSureDialog(title, message, okClicked)

fun FragmentActivity.showAreYouSureDeleteDialog(okClicked: () -> Unit) = showAreYouSureDialog(
    title = Strings.delete,
    message = Strings.are_you_sure_delete,
    okClicked = okClicked,
)

fun FragmentActivity.showAreYouSureClearDialog(okClicked: () -> Unit) = showAreYouSureDialog(
    title = Strings.clear,
    message = Strings.are_you_sure_clear,
    okClicked = okClicked,
)

fun Fragment.showAreYouSureDialog(
    title: Int,
    message: Int = Strings.are_you_sure,
    okClicked: () -> Unit,
) = parentFragmentManager.showAreYouSureDialog(title, message, okClicked)

fun Fragment.showAreYouSureDeleteDialog(okClicked: () -> Unit) = showAreYouSureDialog(
    title = Strings.delete,
    message = Strings.are_you_sure_delete,
    okClicked = okClicked,
)

fun Fragment.showAreYouSureClearDialog(okClicked: () -> Unit) = showAreYouSureDialog(
    title = Strings.clear,
    message = Strings.are_you_sure_clear,
    okClicked = okClicked,
)

private fun FragmentManager.showAreYouSureDialog(
    title: Int,
    message: Int,
    okClicked: () -> Unit,
) = showCallbackDialog(
    tag = "are_you_sure_${title}_${message}",
    configure = { _, _, builder ->
        builder
            .setIcon(Icons.ic_dialog_warning)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(Strings.yes) { _, _ -> okClicked() }
            .setNegativeButton(Strings.no, null)
    },
)
