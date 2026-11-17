package com.f0x1d.logfox.feature.preferences.presentation.service.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
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
        findPreference<Preference>(PREF_MCP_SERVER_START)?.apply {
            isVisible = false

            setOnPreferenceClickListener {
                startMcpServer()

                true
            }
        }

        findPreference<Preference>(PREF_MCP_SERVER_STOP)?.apply {
            isVisible = false

            setOnPreferenceClickListener {
                stopMcpServer()

                true
            }
        }

        findPreference<Preference>(PREF_MCP_SERVER_PORT)?.apply {
            val port = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt(PREF_MCP_SERVER_PORT, MCP_DEFAULT_PORT)

            summary = "${getString(Strings.mcp_server_port)}: $port"

            setOnPreferenceClickListener {
                showMcpPortDialog()

                true
            }
        }

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
    }

    private fun startMcpServer() {
        toast(Strings.mcp_server_start)

        runCatching {
            ContextCompat.startForegroundService(requireContext(), mcpServerIntent())
        }
    }

    private fun stopMcpServer() {
        toast(Strings.mcp_server_stop_desc)

        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                mcpServerIntent().apply { action = MCP_SERVICE_ACTION_STOP },
            )
        }
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
            .setTitle(Strings.mcp_server_port)
            .setView(editText)
            .setPositiveButton(Strings.save) { _, _ ->
                val port = editText.text.toString().toIntOrNull() ?: MCP_DEFAULT_PORT

                preferences.edit().putInt(PREF_MCP_SERVER_PORT, port).apply()

                findPreference<Preference>(PREF_MCP_SERVER_PORT)?.summary =
                    "${getString(Strings.mcp_server_port)}: $port"

                toast("${getString(Strings.mcp_server_port)}: $port")
            }
            .setNegativeButton(Strings.close, null)
            .show()
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

        private const val PREF_MCP_SERVER_ENABLED = "pref_mcp_server_enabled"
        private const val PREF_MCP_SERVER_ENABLED_DEFAULT_V2 = "pref_mcp_server_enabled_default_v2"
        private const val PREF_MCP_SERVER_PORT = "pref_mcp_server_port"
        private const val PREF_MCP_SERVER_START = "pref_mcp_server_start"
        private const val PREF_MCP_SERVER_STOP = "pref_mcp_server_stop"

        private const val MCP_DEFAULT_PORT = 8765
    }
}
