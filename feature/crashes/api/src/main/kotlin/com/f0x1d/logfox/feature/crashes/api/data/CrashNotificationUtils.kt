package com.f0x1d.logfox.feature.crashes.api.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import com.f0x1d.logfox.feature.crashes.api.model.CrashType
import com.f0x1d.logfox.feature.notifications.api.CRASHES_CHANNEL_GROUP_ID

// A channel per package meant a permanent channel in the system settings for every app that
// ever crashed. The crash type is enough: the app name is in the notification title anyway.
val AppCrash.notificationChannelId get() = "${CRASHES_CHANNEL_GROUP_ID}_${crashType.readableName}"

private const val CRASHES_CHANNEL_ID = "crashes"

private val CURRENT_CRASH_CHANNEL_IDS: Set<String> = buildSet {
    add(CRASHES_CHANNEL_ID)
    CrashType.entries.forEach { add("${CRASHES_CHANNEL_GROUP_ID}_${it.readableName}") }
}

/**
 * Removes crash channels created by older builds (one channel per type *and* package), their
 * groups and their leftover notifications.
 *
 * Android does allow deleting a channel the app created; it only keeps a "deleted channels"
 * counter that the app cannot clear. Deleting here is therefore the closest thing to a clean
 * state: nothing from the old naming scheme stays visible, and no notification points at a
 * channel that will never be posted to again.
 */
fun Context.cleanupLegacyCrashChannels() {
    val manager = NotificationManagerCompat.from(this)

    // Cancel first: older builds posted with the bare package name as the tag, and removing
    // the channel while its notifications are still shown can leave them stranded
    runCatching {
        getSystemService<NotificationManager>()?.activeNotifications?.forEach { notification ->
            val channelId = NotificationCompat.getChannelId(notification.notification) ?: return@forEach

            if (isLegacyCrashChannel(channelId)) {
                manager.cancel(notification.tag, notification.id)
            }
        }
    }.onFailure { it.printStackTrace() }

    manager.notificationChannels.forEach { channel ->
        val id = channel.id

        if (isLegacyCrashChannel(id)) {
            manager.deleteNotificationChannel(id)
        }
    }

    manager.notificationChannelGroups.forEach { group ->
        val id = group.id

        if (isLegacyCrashChannel(id)) {
            manager.deleteNotificationChannelGroup(id)
        }
    }
}

private fun isLegacyCrashChannel(id: String): Boolean =
    id !in CURRENT_CRASH_CHANNEL_IDS && id.startsWith("${CRASHES_CHANNEL_GROUP_ID}_")
