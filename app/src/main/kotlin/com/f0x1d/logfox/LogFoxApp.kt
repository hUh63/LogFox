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
import com.f0x1d.logfox.core.tea.SideEffectPersistenceProvider
import com.f0x1d.logfox.data.SharedPreferencesSideEffectPersistence
import com.f0x1d.logfox.feature.crashes.api.data.cleanupLegacyCrashChannels
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

        // Reliable side effects (a failed export, "go to this crash") must outlive a process
        // death: the TEA module cannot touch a Context, so the application installs the store
        SideEffectPersistenceProvider.install { namespace ->
            SharedPreferencesSideEffectPersistence(this, namespace)
        }

        // LOGGING_ENABLED is a compile-time false, so nothing was ever planted: every
        // Timber call in the terminal/Shizuku/MCP code was a no-op and runtime failures were
        // invisible (the file tree stays opt-in because it writes to disk on every line)
        if (BuildConfig.DEBUG) {
            Timber.plant(if (LOGGING_ENABLED) timberFileTree else Timber.DebugTree())
        }
        Timber.d("onCreate")

        // "MCP server is running" lives in SharedPreferences and is only cleared on a clean
        // stop. After a force-stop or update it stayed true, so Settings kept showing a
        // running server with a link that could not work.
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(PREF_MCP_SERVER_RUNNING, false)
                .apply()
        }

        // Older builds created one crash notification channel per (type, package). Those
        // channels, their groups and their notifications are removed once on startup.
        runCatching { cleanupLegacyCrashChannels() }
            .onFailure { Timber.w(it, "Failed to clean up legacy crash channels") }

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
            preferences.edit().apply {
                // Only fill in a default for users who never made a choice. Writing `true`
                // unconditionally reopened the port (and its notification) for users who had
                // explicitly turned the server off
                if (!preferences.contains(McpServerService.PREF_MCP_SERVER_ENABLED)) {
                    putBoolean(McpServerService.PREF_MCP_SERVER_ENABLED, true)
                }

                putBoolean(PREF_MCP_SERVER_ENABLED_DEFAULT_V2, true)
            }.apply()
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
        private const val PREF_MCP_SERVER_RUNNING = "pref_mcp_server_running"
    }
}
