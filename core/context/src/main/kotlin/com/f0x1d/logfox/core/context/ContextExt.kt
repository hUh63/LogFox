package com.f0x1d.logfox.core.context

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.f0x1d.logfox.core.compat.shouldRequestNotificationsPermission
import com.f0x1d.logfox.feature.strings.Strings
import java.io.File
import kotlin.system.exitProcess

val Context.hasPermissionToReadLogs: Boolean get() = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.READ_LOGS,
) == PackageManager.PERMISSION_GRANTED

val Context.notificationManagerCompat get() = NotificationManagerCompat.from(this)
val Context.notificationManager get() = getSystemService<NotificationManager>()!!
val Context.activityManager get() = getSystemService<ActivityManager>()!!

fun Context.hardRestartApp() {
    // Never crash while restarting: this runs right after the first Shizuku
    // authorization, where any failure looks like "the app closed itself".
    val launchIntent = runCatching {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }.getOrNull()

    val restarted = launchIntent != null && runCatching {
        startActivity(launchIntent)
        true
    }.getOrDefault(false)

    // Only kill the process when a replacement activity was really started. Killing it
    // unconditionally is what made the app vanish after the first authorization whenever
    // the launch intent was missing or blocked by background-start restrictions.
    if (!restarted) return

    runCatching {
        for (task in activityManager.appTasks) task.finishAndRemoveTask()
    }

    exitProcess(0)
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
fun Context.toast(text: Int) = toast(getString(text))

fun Context.shareIntent(text: String) = baseShareIntent {
    it.putExtra(Intent.EXTRA_TEXT, text)
    it.type = "text/plain"
}

/**
 * Shares an already readable content Uri (a file opened from another app).
 *
 * Putting the file text in EXTRA_TEXT overflows the binder transaction for any real log
 * and the failure was swallowed, so the share button looked simply dead.
 */
fun Context.shareUriIntent(uri: android.net.Uri, mimeType: String = "text/plain") = baseShareIntent {
    it.putExtra(Intent.EXTRA_STREAM, uri)
    it.type = mimeType
    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

fun Context.shareFileIntent(file: File) = baseShareIntent {
    val uri = file.asUri(this)

    it.putExtra(Intent.EXTRA_STREAM, uri)
    it.type = "text/plain"
    // FileProvider grants access through this flag; without it the receiving app gets a
    // SecurityException even though the URI itself was created successfully
    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun Context.baseShareIntent(block: (Intent) -> Unit) {
    try {
        val intent = Intent(Intent.ACTION_SEND)
        block(intent)

        startActivity(Intent.createChooser(intent, getString(Strings.share)))
    } catch (e: Exception) {
        e.printStackTrace()
        toast(Strings.too_big_log)
    }
}

fun Context.catchingNotNumber(block: () -> Unit) = try {
    block()
} catch (e: NumberFormatException) {
    toast(Strings.this_is_not_a_number)
}

inline fun <reified T> Context.sendService(action: String) = startService(Intent(this, T::class.java).setAction(action))

@SuppressLint("InlinedApi")
fun Context.hasNotificationsPermission() = if (shouldRequestNotificationsPermission) {
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
} else {
    true
}

fun Context.doIfNotificationsAllowed(block: NotificationManagerCompat.() -> Unit) = if (hasNotificationsPermission()) {
    block(notificationManagerCompat)
} else {
    Unit
}

val Context.isHorizontalOrientation get() = resources.configuration.orientation ==
    Configuration.ORIENTATION_LANDSCAPE
