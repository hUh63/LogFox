package com.f0x1d.logfox.feature.crashes.api.data

import com.f0x1d.logfox.feature.crashes.api.model.AppCrash
import com.f0x1d.logfox.feature.notifications.api.CRASHES_CHANNEL_GROUP_ID

// A channel per package meant a permanent, unremovable channel in the system settings for
// every app that ever crashed (and Android does not allow deleting channels). The type is
// enough: the app name is in the notification title anyway. Older per-package channels stay
// behind, they simply receive nothing new.
val AppCrash.notificationChannelId get() = "${CRASHES_CHANNEL_GROUP_ID}_${crashType.readableName}"
