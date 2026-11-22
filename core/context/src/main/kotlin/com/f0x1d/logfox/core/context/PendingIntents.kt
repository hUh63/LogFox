package com.f0x1d.logfox.core.context

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build

const val CRASH_DETAILS_INTENT_ID = 0
const val COPY_CRASH_INTENT_ID = 1
const val STOP_LOGGING_SERVICE_INTENT_ID = 2 // Unused
const val EXIT_APP_INTENT_ID = 3
const val PAUSE_RECORDING_INTENT_ID = 4
const val RESUME_RECORDING_INTENT_ID = 5
const val STOP_RECORDING_INTENT_ID = 6
const val OPEN_APP_INTENT_ID = 7

// IMMUTABLE: nothing in the app relies on the system filling in extras, while a mutable
// PendingIntent lets any app with notification access inject a clipboard payload
val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
} else {
    PendingIntent.FLAG_UPDATE_CURRENT
}

inline fun <reified T : BroadcastReceiver> Context.makeBroadcastPendingIntent(
    id: Int,
    setup: Intent.() -> Unit,
) = PendingIntent.getBroadcast(
    this,
    id,
    Intent(this, T::class.java).also { setup(it) },
    pendingIntentFlags,
)

inline fun <reified T : BroadcastReceiver> Context.makeBroadcastPendingIntent(
    id: Int,
    extras: Bundle = Bundle.EMPTY,
) = makeBroadcastPendingIntent<T>(
    id = id,
    setup = { putExtras(extras) },
)

inline fun <reified T : Service> Context.makeServicePendingIntent(
    id: Int,
    setup: Intent.() -> Unit,
) = PendingIntent.getService(
    this,
    id,
    Intent(this, T::class.java).also { setup(it) },
    pendingIntentFlags,
)

inline fun <reified T : Service> Context.makeServicePendingIntent(
    id: Int,
    extras: Bundle = Bundle.EMPTY,
) = makeServicePendingIntent<T>(
    id = id,
    setup = { putExtras(extras) },
)

inline fun <reified T : Activity> Context.makeActivityPendingIntent(
    id: Int,
    setup: Intent.() -> Unit,
) = PendingIntent.getActivity(
    this,
    id,
    Intent(this, T::class.java).also { setup(it) },
    pendingIntentFlags,
)

inline fun <reified T : Activity> Context.makeActivityPendingIntent(
    id: Int,
    extras: Bundle = Bundle.EMPTY,
) = makeActivityPendingIntent<T>(
    id = id,
    setup = { putExtras(extras) },
)
