package com.f0x1d.logfox.core.copy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.f0x1d.logfox.core.context.toast
import com.f0x1d.logfox.feature.strings.Strings

// Clipboard content travels through a binder transaction (~1MB). A crash log can be several
// megabytes, and setPrimaryClip() then throws: the user tapped "copy" and got an error toast
// with nothing copied. Copying the beginning is useful, the error is not.
private const val MAX_CLIPBOARD_CHARS = 100_000

fun Context.copyText(text: String) {
    val payload = if (text.length <= MAX_CLIPBOARD_CHARS) text else text.take(MAX_CLIPBOARD_CHARS)

    val copied = runCatching {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("LogFox", payload))
    }.onFailure { th ->
        toast(getString(Strings.error, th.localizedMessage))
    }.isSuccess

    if (copied && payload.length != text.length) {
        toast(Strings.copied_log_trimmed)
    }
}
