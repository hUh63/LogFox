package com.f0x1d.logfox.core.context

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

fun File.asUri(context: Context): Uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.provider",
    this,
)

/**
 * Opens the given file with whatever app can display a text file (including LogFox itself,
 * see LogFileViewerActivity). Used to view recorded logs without leaving the app
 * (F0x1d/LogFox#235)
 */
fun Context.viewTextFile(file: File) {
    if (!file.exists()) {
        toast(com.f0x1d.logfox.feature.strings.Strings.cannot_open_file)
        return
    }

    try {
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.asUri(this@viewTextFile), "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    } catch (e: Exception) {
        e.printStackTrace()
        toast(com.f0x1d.logfox.feature.strings.Strings.cannot_open_file)
    }
}
