package com.f0x1d.logfox.feature.crashes.api.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
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
 * Removes everything older builds left in the system notification settings.
 *
 * Older builds created one channel *per package* and nested them into a channel group per
 * crash type. Both are gone now (channels are flat, one per crash type), and this cleans up
 * whatever is still installed:
 *
 * 1. leftover notifications are cancelled (older builds tagged them with the bare package name,
 *    and deleting a channel that still shows notifications can strand them),
 * 2. the live channels are re-created **outside** any group, so the group deletion below
 *    cannot take them down,
 * 3. legacy per-package channels are deleted,
 * 4. every crash channel group is deleted.
 *
 * Android keeps a "deleted channels" counter (and the deleted channel records themselves, in
 * notification_policy.xml) that only the system can clear:
 *  - NotificationManager.deleteNotificationChannel() only marks the channel deleted
 *    (PreferencesHelper.deleteNotificationChannelLocked -> setDeleted(true)); the record, its
 *    name and its settings stay in the policy file, so every delete adds one more entry;
 *  - removing them for real needs PreferencesHelper.permanentlyDeleteNotificationChannel()
 *    (@VisibleForTesting, NotificationManagerInternal - system/root only) or
 *    INotificationManager.clearData() (checkCallerIsSystem, called when the user clears the
 *    app's storage).
 * There is therefore no app-facing way to shrink that list: not creating the groups in the
 * first place and never renaming/rebuilding channels is the only way to keep it from growing.
 * The settings screen shows the channels plus this explanation, and points at the only
 * user-reachable reset (clear app storage, which also resets the app's settings).
 */
fun Context.cleanupLegacyCrashChannels() {
    val manager = NotificationManagerCompat.from(this)

    // Cancel first: older builds posted with the bare package name as the tag, and removing
    // the channel while its notifications are still shown can leave them stranded
    runCatching {
        getSystemService<NotificationManager>()?.activeNotifications?.forEach { notification ->
            val channelId = NotificationCompat.getChannelId(notification.notification) ?: return@forEach

            // Only legacy ones: a notification on a live channel is a crash the user has
            // not seen yet and must not be cancelled just because the app was opened
            if (isLegacyCrashChannel(channelId)) {
                manager.cancel(notification.tag, notification.id)
            }
        }
    }.onFailure { it.printStackTrace() }

    // Deleting a group deletes the channels inside it, so move the current channels out first
    manager.notificationChannels
        .filter { it.id in CURRENT_CRASH_CHANNEL_IDS && it.group != null }
        .forEach { channel ->
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannelCompat
                        .Builder(channel.id, channel.importance)
                        .setName(channel.name)
                        .setDescription(channel.description?.toString() ?: "")
                        .setShowBadge(channel.canShowBadge())
                        .setLightsEnabled(channel.shouldShowLights())
                        .setVibrationEnabled(channel.shouldVibrate())
                        .build(),
                )
            }.onFailure { it.printStackTrace() }
        }

    manager.notificationChannels.forEach { channel ->
        val id = channel.id

        if (isLegacyCrashChannel(id)) {
            manager.deleteNotificationChannel(id)
        }
    }

    manager.notificationChannelGroups.forEach { group ->
        val id = group.id

        if (id == CRASHES_CHANNEL_GROUP_ID || id.startsWith("${CRASHES_CHANNEL_GROUP_ID}_")) {
            manager.deleteNotificationChannelGroup(id)
        }
    }
}

private fun isLegacyCrashChannel(id: String): Boolean =
    id !in CURRENT_CRASH_CHANNEL_IDS && id.startsWith("${CRASHES_CHANNEL_GROUP_ID}_")
