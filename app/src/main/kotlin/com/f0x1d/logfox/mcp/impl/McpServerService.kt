package com.f0x1d.logfox.mcp.impl

import android.app.Notification
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.f0x1d.logfox.core.context.OPEN_APP_INTENT_ID
import com.f0x1d.logfox.core.context.makeServicePendingIntent
import com.f0x1d.logfox.core.ui.icons.Icons
import com.f0x1d.logfox.feature.logging.presentation.service.MainActivityPendingIntentProvider
import com.f0x1d.logfox.feature.notifications.api.MCP_SERVER_CHANNEL_ID
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.mcp.api.McpServerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service hosting the MCP server, so that it survives while
 * LogFox is in background.
 */
@AndroidEntryPoint
class McpServerService : LifecycleService() {

    companion object {
        const val ACTION_STOP_SERVER = "mcp.STOP_SERVER"
        const val ACTION_RESTART_SERVER = "mcp.RESTART_SERVER"

        const val DEFAULT_PORT = McpServerManager.DEFAULT_PORT

        const val PREF_MCP_SERVER_ENABLED = "pref_mcp_server_enabled"
        const val PREF_MCP_SERVER_PORT = "pref_mcp_server_port"

        private const val NOTIFICATION_ID = 8765

        private const val RESTART_DELAY_MS = 250L
    }

    @Inject
    lateinit var mcpServerManager: McpServerManager

    @Inject
    lateinit var mainActivityPendingIntentProvider: MainActivityPendingIntentProvider

    override fun onCreate() {
        super.onCreate()

        Timber.d("[MCP] Service onCreate() called")

        startForeground(NOTIFICATION_ID, notification())

        lifecycleScope.launch {
            Timber.d("[MCP] Launching coroutine to start server...")

            startServer()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                Timber.i("[MCP] Received stop server action, calling stopSelf()")

                lifecycleScope.launch {
                    stopServer()

                    stopSelf()
                }
            }

            ACTION_RESTART_SERVER -> {
                Timber.i("[MCP] Received restart server action")

                lifecycleScope.launch {
                    stopServer()

                    delay(RESTART_DELAY_MS)

                    startServer()
                }
            }

            else -> Timber.d("[MCP] Service started with action: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("[MCP] Service onDestroy() called")

        // lifecycleScope is cancelled right after this method, so the server has to be
        // shut down synchronously here - otherwise the socket would stay bound.
        runCatching { mcpServerManager.stopNow() }
            .onFailure { Timber.e(it, "[MCP] Failed to stop server") }

        super.onDestroy()
    }

    private suspend fun startServer() {
        runCatching { mcpServerManager.start(configuredPort()) }
            .onFailure { Timber.e(it, "[MCP] Failed to start server") }
    }

    private suspend fun stopServer() {
        runCatching { mcpServerManager.stop() }
            .onFailure { Timber.e(it, "[MCP] Failed to stop server") }
    }

    private fun preferences(): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

    private fun configuredPort(): Int {
        val port = preferences().getInt(PREF_MCP_SERVER_PORT, DEFAULT_PORT)

        return if (port < 1024 || port > 65535) DEFAULT_PORT else port
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, MCP_SERVER_CHANNEL_ID)
            .setContentTitle(getString(Strings.mcp_server_notification_title))
            .setContentText(getString(Strings.mcp_server_notification_text, configuredPort()))
            .setSmallIcon(Icons.ic_logfox)
            .setOngoing(true)
            .setContentIntent(mainActivityPendingIntentProvider.provide(OPEN_APP_INTENT_ID))
            .addAction(
                Icons.ic_clear,
                getString(Strings.mcp_server_stop),
                makeServicePendingIntent<McpServerService>(NOTIFICATION_ID + 1) {
                    action = ACTION_STOP_SERVER
                },
            )
            .build()
}
