package com.f0x1d.logfox.feature.preferences.presentation.service.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.f0x1d.logfox.core.context.isHorizontalOrientation
import com.f0x1d.logfox.core.context.toast
import com.f0x1d.logfox.core.tea.BaseStorePreferenceFragment
import com.f0x1d.logfox.core.ui.icons.Icons
import com.f0x1d.logfox.core.ui.preference.setupAsListPreference
import com.f0x1d.logfox.core.ui.view.setupBackButtonForNavController
import com.f0x1d.logfox.feature.preferences.presentation.R
import com.f0x1d.logfox.feature.preferences.presentation.service.PreferencesServiceCommand
import com.f0x1d.logfox.feature.preferences.presentation.service.PreferencesServiceSideEffect
import com.f0x1d.logfox.feature.preferences.presentation.service.PreferencesServiceState
import com.f0x1d.logfox.feature.preferences.presentation.service.PreferencesServiceViewModel
import com.f0x1d.logfox.feature.preferences.presentation.service.PreferencesServiceViewState
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.insetter.applyInsetter

@AndroidEntryPoint
internal class PreferencesServiceFragment :
    BaseStorePreferenceFragment<
        PreferencesServiceViewState,
        PreferencesServiceState,
        PreferencesServiceCommand,
        PreferencesServiceSideEffect,
        PreferencesServiceViewModel,
        >() {

    override val viewModel by viewModels<PreferencesServiceViewModel>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        if (!sharedPreferences.contains(PREF_MCP_SERVER_ENABLED_DEFAULT_V2)) {
            sharedPreferences.edit()
                .putBoolean(PREF_MCP_SERVER_ENABLED, true)
                .putBoolean(PREF_MCP_SERVER_ENABLED_DEFAULT_V2, true)
                .apply()

            startMcpServer()
        }

        addPreferencesFromResource(R.xml.settings_service)

        findPreference<SwitchPreferenceCompat>("pref_start_on_boot")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                send(PreferencesServiceCommand.StartOnBootChanged(newValue as Boolean))
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("pref_show_logs_from_app_launch")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                send(PreferencesServiceCommand.ShowLogsFromAppLaunchChanged(newValue as Boolean))
                true
            }
        }

        setupMcpPreferences()
    }

    private fun setupMcpPreferences() {
        findPreference<SwitchPreferenceCompat>(PREF_MCP_SERVER_ENABLED)?.apply {
            val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

            if (!preferences.contains(PREF_MCP_SERVER_ENABLED)) {
                preferences.edit().putBoolean(PREF_MCP_SERVER_ENABLED, true).apply()

                isChecked = true

                startMcpServer()
            }

            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    startMcpServer()
                } else {
                    stopMcpServer()
                }

                true
            }
        }

        findPreference<Preference>(PREF_MCP_SERVER_PORT)?.setOnPreferenceClickListener {
            showMcpPortDialog()

            true
        }

        findPreference<Preference>(PREF_MCP_SERVER_LOCAL_LINK)?.setOnPreferenceClickListener { preference ->
            copyMcpLink(preference.summary?.toString())

            true
        }

        findPreference<Preference>(PREF_MCP_SERVER_LAN_LINK)?.setOnPreferenceClickListener { preference ->
            copyMcpLink(preference.summary?.toString())

            true
        }

        refreshMcpPreferences()
    }

    private fun refreshMcpPreferences() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val enabled = preferences.getBoolean(PREF_MCP_SERVER_ENABLED, true)
        val configuredPort = preferences.getInt(PREF_MCP_SERVER_PORT, MCP_DEFAULT_PORT)

        val running = preferences.getBoolean(PREF_MCP_SERVER_RUNNING, false)
        val activePort = preferences.getInt(PREF_MCP_SERVER_ACTIVE_PORT, configuredPort)
        val error = preferences.getString(PREF_MCP_SERVER_ERROR, null)

        findPreference<SwitchPreferenceCompat>(PREF_MCP_SERVER_ENABLED)?.isChecked = enabled

        findPreference<Preference>(PREF_MCP_SERVER_STATUS)?.summary = when {
            running -> getString(Strings.server_running, activePort)
            !error.isNullOrBlank() -> getString(Strings.server_start_failed, error)
            else -> getString(Strings.server_stopped)
        }

        findPreference<Preference>(PREF_MCP_SERVER_PORT)?.summary = getString(Strings.default_port, configuredPort.toString())

        val localLink = if (running) "http://127.0.0.1:$activePort" else null
        val lanLink = if (running) lanAddress()?.let { "http://$it:$activePort" } else null

        findPreference<Preference>(PREF_MCP_SERVER_LOCAL_LINK)?.summary =
            localLink ?: getString(Strings.not_set)

        findPreference<Preference>(PREF_MCP_SERVER_LAN_LINK)?.summary =
            lanLink ?: getString(Strings.not_set)
    }

    private fun lanAddress(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedByDescending { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun copyMcpLink(link: String?) {
        val context = requireContext()

        if (link.isNullOrBlank() || link == getString(Strings.not_set)) {
            return
        }

        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(Strings.mcp_server), link))

            context.toast(context.getString(Strings.preference_copied, link))
        }
    }

    private fun startMcpServer() {
        runCatching {
            ContextCompat.startForegroundService(requireContext(), mcpServerIntent())
        }

        runCatching { requireView().postDelayed({ refreshMcpPreferences() }, MCP_REFRESH_DELAY_MS) }
    }

    private fun stopMcpServer() {
        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                mcpServerIntent().apply { action = MCP_SERVICE_ACTION_STOP },
            )
        }

        runCatching { requireView().postDelayed({ refreshMcpPreferences() }, MCP_REFRESH_DELAY_MS) }
    }

    private fun restartMcpServer() {
        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                mcpServerIntent().apply { action = MCP_SERVICE_ACTION_RESTART },
            )
        }

        runCatching { requireView().postDelayed({ refreshMcpPreferences() }, MCP_REFRESH_DELAY_MS * 2) }
    }

    private fun mcpServerIntent() = Intent().apply {
        component = ComponentName(requireContext().packageName, MCP_SERVICE_CLASS)
    }

    private fun showMcpPortDialog() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val editText = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(preferences.getInt(PREF_MCP_SERVER_PORT, MCP_DEFAULT_PORT).toString())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(Strings.server_port)
            .setView(editText)
            .setPositiveButton(Strings.save) { _, _ ->
                val port = editText.text.toString().toIntOrNull()

                if (port == null || port < MCP_MIN_PORT || port > MCP_MAX_PORT) {
                    requireContext().toast(Strings.invalid_port)

                    return@setPositiveButton
                }

                preferences.edit().putInt(PREF_MCP_SERVER_PORT, port).apply()

                findPreference<Preference>(PREF_MCP_SERVER_PORT)?.summary =
                    getString(Strings.default_port, port.toString())

                if (preferences.getBoolean(PREF_MCP_SERVER_ENABLED, true)) {
                    restartMcpServer()
                } else {
                    refreshMcpPreferences()
                }
            }
            .setNegativeButton(Strings.close, null)
            .show()
    }

    override fun onResume() {
        super.onResume()

        runCatching { refreshMcpPreferences() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(Strings.service)
            setupBackButtonForNavController()
        }

        listView.apply {
            clipToPadding = false
            applyInsetter {
                type(navigationBars = true) {
                    padding(vertical = requireContext().isHorizontalOrientation)
                }
            }
        }
    }

    override fun render(state: PreferencesServiceViewState) {
        if (state.terminalNames.isEmpty()) return

        findPreference<Preference>("pref_selected_terminal_index")?.apply {
            val terminalNamesArray = state.terminalNames.toTypedArray()
            val selectedIndex = TerminalType.entries.indexOf(state.selectedTerminalType)

            setupAsListPreference(
                setupDialog = { setIcon(Icons.ic_dialog_terminal) },
                items = terminalNamesArray,
                selected = { selectedIndex },
                onSelected = { index ->
                    val type = TerminalType.entries[index]
                    send(PreferencesServiceCommand.TerminalSelected(type))
                },
            )

            summary = terminalNamesArray.getOrNull(selectedIndex) ?: ""
        }
    }

    override fun handleSideEffect(sideEffect: PreferencesServiceSideEffect) {
        when (sideEffect) {
            is PreferencesServiceSideEffect.ShowTerminalRestartDialog -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setIcon(Icons.ic_dialog_terminal)
                    .setTitle(Strings.new_terminal_selected)
                    .setMessage(Strings.new_terminal_selected_question)
                    .setPositiveButton(Strings.yes) { _, _ ->
                        send(PreferencesServiceCommand.ConfirmRestartLogging)
                    }
                    .setNeutralButton(Strings.no, null)
                    .show()
            }

            is PreferencesServiceSideEffect.ShowTerminalUnavailableToast -> {
                requireContext().toast(Strings.terminal_unavailable)
            }

            is PreferencesServiceSideEffect.ShowAndroid13WarningDialog -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setIcon(Icons.ic_dialog_warning)
                    .setTitle(Strings.warning)
                    .setMessage(Strings.android13_start_on_boot_warning)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            // Business logic side effects - handled by EffectHandler
            else -> Unit
        }
    }

    companion object {
        private const val MCP_SERVICE_CLASS = "com.f0x1d.logfox.mcp.impl.McpServerService"
        private const val MCP_SERVICE_ACTION_STOP = "mcp.STOP_SERVER"
        private const val MCP_SERVICE_ACTION_RESTART = "mcp.RESTART_SERVER"

        private const val PREF_MCP_SERVER_ENABLED = "pref_mcp_server_enabled"
        private const val PREF_MCP_SERVER_ENABLED_DEFAULT_V2 = "pref_mcp_server_enabled_default_v2"
        private const val PREF_MCP_SERVER_PORT = "pref_mcp_server_port"
        private const val PREF_MCP_SERVER_STATUS = "pref_mcp_server_status"
        private const val PREF_MCP_SERVER_LOCAL_LINK = "pref_mcp_server_local_link"
        private const val PREF_MCP_SERVER_LAN_LINK = "pref_mcp_server_lan_link"

        private const val PREF_MCP_SERVER_RUNNING = "pref_mcp_server_running"
        private const val PREF_MCP_SERVER_ACTIVE_PORT = "pref_mcp_server_active_port"
        private const val PREF_MCP_SERVER_ERROR = "pref_mcp_server_error"

        private const val MCP_DEFAULT_PORT = 8765
        private const val MCP_MIN_PORT = 1024
        private const val MCP_MAX_PORT = 65535

        private const val MCP_REFRESH_DELAY_MS = 700L
    }
}
