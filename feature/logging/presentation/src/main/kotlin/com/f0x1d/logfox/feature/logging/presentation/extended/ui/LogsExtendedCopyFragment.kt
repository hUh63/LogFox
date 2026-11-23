package com.f0x1d.logfox.feature.logging.presentation.extended.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.f0x1d.logfox.core.tea.BaseStoreFragment
import com.f0x1d.logfox.core.ui.view.setupBackButtonForNavController
import com.f0x1d.logfox.feature.logging.presentation.databinding.FragmentLogsExtendedCopyBinding
import com.f0x1d.logfox.feature.logging.presentation.extended.LogsExtendedCopyCommand
import com.f0x1d.logfox.feature.logging.presentation.extended.LogsExtendedCopySideEffect
import com.f0x1d.logfox.feature.logging.presentation.extended.LogsExtendedCopyState
import com.f0x1d.logfox.feature.logging.presentation.extended.LogsExtendedCopyViewModel
import com.f0x1d.logfox.core.context.toast
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.logging.presentation.extended.LogsExtendedCopyViewState
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.insetter.applyInsetter

@AndroidEntryPoint
internal class LogsExtendedCopyFragment :
    BaseStoreFragment<
        FragmentLogsExtendedCopyBinding,
        LogsExtendedCopyViewState,
        LogsExtendedCopyState,
        LogsExtendedCopyCommand,
        LogsExtendedCopySideEffect,
        LogsExtendedCopyViewModel,
        >() {

    override val viewModel by viewModels<LogsExtendedCopyViewModel>()

    // The displayed text is capped, the clipboard gets the whole thing
    private var fullText: String? = null

    private companion object {
        // TextView layout is O(n) on the main thread, the full text stays available for copying
        const val MAX_PREVIEW_CHARS = 200_000
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) = FragmentLogsExtendedCopyBinding.inflate(inflater, container, false)

    override fun FragmentLogsExtendedCopyBinding.onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        scrollView.applyInsetter {
            type(navigationBars = true) {
                padding(vertical = true)
            }
        }
        toolbar.setupBackButtonForNavController()

        copyAllButton.setOnClickListener {
            val text = fullText ?: return@setOnClickListener

            val ctx = context ?: return@setOnClickListener

            ctx.getSystemService(android.content.ClipboardManager::class.java)?.let { manager ->
                manager.setPrimaryClip(
                    android.content.ClipData.newPlainText(getString(Strings.app_name), text),
                )

                ctx.toast(Strings.copied)
            }
        }
    }

    override fun render(state: LogsExtendedCopyViewState) {
        fullText = state.text

        // A megabyte-long string in one TextView is measured and laid out on the main thread;
        // the preview is capped and the complete text is available through "copy all"
        val text = state.text ?: return

        val preview = if (text.length > MAX_PREVIEW_CHARS) {
            text.take(MAX_PREVIEW_CHARS) + "\n" + getString(Strings.preview_truncated, MAX_PREVIEW_CHARS)
        } else {
            text
        }

        // render() is called on every state change and the text can be megabytes: re-setting
        // it rebuilt the Editable, re-measured the whole string and dropped the user's
        // selection each time
        if (binding.logText.text?.toString() != preview) {
            binding.logText.text = preview
        }
    }

    override fun handleSideEffect(sideEffect: LogsExtendedCopySideEffect) {
        // All side effects are business logic, handled by EffectHandler
        // No UI side effects for this screen
    }
}
