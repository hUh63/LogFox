package com.f0x1d.logfox

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.f0x1d.logfox.core.context.notificationManagerCompat
import com.f0x1d.logfox.core.logging.TimberFileTree
import com.f0x1d.logfox.feature.notifications.api.LOGGING_STATUS_CHANNEL_ID
import com.f0x1d.logfox.feature.notifications.api.MCP_SERVER_CHANNEL_ID
import com.f0x1d.logfox.feature.notifications.api.RECORDING_STATUS_CHANNEL_ID
import com.f0x1d.logfox.feature.preferences.api.data.UISettingsRepository
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.mcp.impl.McpServerService
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class LogFoxApp : Application(), ImageLoaderFactory {
    @Inject
    lateinit var uiSettingsRepository: UISettingsRepository

    @Inject
    lateinit var imageLoaderProvider: Provider<ImageLoader>

    @Inject
    lateinit var timberFileTree: TimberFileTree

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG && LOGGING_ENABLED) {
            Timber.plant(timberFileTree)
        }
        Timber.d("onCreate")

        AppCompatDelegate.setDefaultNightMode(uiSettingsRepository.nightTheme().value)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions
                .Builder()
                .setPrecondition { _, _ -> uiSettingsRepository.monetEnabled().value }
                .build(),
        )

        notificationManagerCompat.apply {
            val loggingStatusChannel =
                NotificationChannelCompat
                    .Builder(
                        LOGGING_STATUS_CHANNEL_ID,
                        NotificationManagerCompat.IMPORTANCE_MIN,
                    ).setName(getString(Strings.logging_status))
                    .setShowBadge(false)
                    .build()

            val recordingStatusChannel =
                NotificationChannelCompat
                    .Builder(
                        RECORDING_STATUS_CHANNEL_ID,
                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    ).setName(getString(Strings.recording_status))
                    .setLightsEnabled(false)
                    .setVibrationEnabled(false)
                    .setSound(null, null)
                    .build()

            val mcpServerChannel =
                NotificationChannelCompat
                    .Builder(
                        MCP_SERVER_CHANNEL_ID,
                        NotificationManagerCompat.IMPORTANCE_LOW,
                    ).setName(getString(Strings.mcp_server_notification_channel))
                    .setDescription(getString(Strings.mcp_server_notification_desc))
                    .setShowBadge(false)
                    .build()

            createNotificationChannelsCompat(
                listOf(
                    loggingStatusChannel,
                    recordingStatusChannel,
                    mcpServerChannel,
                ),
            )
        }

        setupMcpServer()
    }

    override fun newImageLoader(): ImageLoader = imageLoaderProvider.get()

    /**
     * Starts MCP server on app launch when it is enabled in settings.
     */
    private fun setupMcpServer() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)

        if (!preferences.contains(PREF_MCP_SERVER_ENABLED_DEFAULT_V2)) {
            preferences.edit()
                .putBoolean(McpServerService.PREF_MCP_SERVER_ENABLED, true)
                .putBoolean(PREF_MCP_SERVER_ENABLED_DEFAULT_V2, true)
                .apply()
        }

        val enabled = preferences.getBoolean(McpServerService.PREF_MCP_SERVER_ENABLED, true)

        if (!enabled) {
            return
        }

        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, McpServerService::class.java))
        }.onFailure {
            Timber.e(it, "failed to start MCP server")
        }
    }

    companion object {
        private const val LOGGING_ENABLED = false

        private const val PREF_MCP_SERVER_ENABLED_DEFAULT_V2 = "pref_mcp_server_enabled_default_v2"
    }
}
