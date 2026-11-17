package com.f0x1d.logfox.feature.filters.presentation.edit.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.navigation.fragment.findNavController
import com.f0x1d.logfox.core.tea.BaseStoreFragment
import com.f0x1d.logfox.core.ui.base.ext.doAfterTextChanged
import com.f0x1d.logfox.core.ui.dialog.showAreYouSureDialog
import com.f0x1d.logfox.core.ui.dialog.showEditTextDialog
import com.f0x1d.logfox.core.ui.icons.Icons
import com.f0x1d.logfox.core.ui.view.setClickListenerOn
import com.f0x1d.logfox.core.ui.view.setupBackButton
import com.f0x1d.logfox.core.ui.view.setupClickableTitle
import com.f0x1d.logfox.feature.filters.presentation.R
import com.f0x1d.logfox.feature.filters.presentation.databinding.FragmentEditFilterBinding
import com.f0x1d.logfox.feature.filters.presentation.edit.EditFilterCommand
import com.f0x1d.logfox.feature.filters.presentation.edit.EditFilterSideEffect
import com.f0x1d.logfox.feature.filters.presentation.edit.EditFilterState
import com.f0x1d.logfox.feature.filters.presentation.edit.EditFilterViewModel
import com.f0x1d.logfox.feature.filters.presentation.edit.EditFilterViewState
import com.f0x1d.logfox.feature.navigation.api.Directions
import com.f0x1d.logfox.feature.strings.Strings
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.insetter.applyInsetter

@AndroidEntryPoint
internal class EditFilterFragment :
    BaseStoreFragment<
        FragmentEditFilterBinding,
        EditFilterViewState,
        EditFilterState,
        EditFilterCommand,
        EditFilterSideEffect,
        EditFilterViewModel,
        >() {

    override val viewModel by hiltNavGraphViewModels<EditFilterViewModel>(
        Directions.editFilterFragment,
    )

    private val exportFilterLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let { send(EditFilterCommand.Export(it)) }
    }

    // Routes system/predictive back through TEA so the filter is saved before leaving
    private val closeOnBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            send(EditFilterCommand.AttemptClose)
        }
    }

    private val levelButtons by lazy {
        listOf(
            binding.levelVerboseButton to 0,
            binding.levelDebugButton to 1,
            binding.levelInfoButton to 2,
            binding.levelWarningButton to 3,
            binding.levelErrorButton to 4,
            binding.levelFatalButton to 5,
            binding.levelSilentButton to 6,
        )
    }

    // Setters that update each field without firing its text-changed callback, so rendering state
    // back into the form is not mistaken for a user edit (which would mark the form dirty).
    private lateinit var setUidText: (CharSequence?) -> Unit
    private lateinit var setPidText: (CharSequence?) -> Unit
    private lateinit var setTidText: (CharSequence?) -> Unit
    private lateinit var setPackageNameText: (CharSequence?) -> Unit
    private lateinit var setTagText: (CharSequence?) -> Unit
    private lateinit var setContentText: (CharSequence?) -> Unit

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) = FragmentEditFilterBinding.inflate(inflater, container, false)

    override fun FragmentEditFilterBinding.onViewCreated(view: View, savedInstanceState: Bundle?) {
        scrollView.applyInsetter {
            type(
                navigationBars = true,
                ime = true,
            ) {
                padding(vertical = true)
            }
        }

        toolbar.setupBackButton { send(EditFilterCommand.AttemptClose) }
        toolbar.menu.apply {
            setClickListenerOn(R.id.export_item) {
                exportFilterLauncher.launch("filter.json")
            }
            setClickListenerOn(R.id.tag_regex_item) {
                send(EditFilterCommand.ToggleTagRegex)
            }
            setClickListenerOn(R.id.content_regex_item) {
                send(EditFilterCommand.ToggleContentRegex)
            }
        }
        toolbar.setupClickableTitle(
            background = com.f0x1d.logfox.core.ui.view.R.drawable.bg_toolbar_title_clickable,
        ) { showRenameDialog() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            closeOnBackPressedCallback,
        )

        includingButton.setOnClickListener {
            send(EditFilterCommand.ToggleIncluding)
        }
        enabledButton.setOnClickListener {
            send(EditFilterCommand.ToggleEnabled)
        }
        levelButtons.forEach { (button, which) ->
            button.setOnClickListener {
                // MaterialButton flips isChecked in performClick() before this listener,
                // so reading it here gives the state the user just chose
                send(EditFilterCommand.FilterLevel(which, button.isChecked))
            }
        }
        clearLevelsButton.setOnClickListener {
            levelButtons.forEach { (button, _) -> button.isChecked = false }

            send(EditFilterCommand.ClearLevels)
        }

        selectAppButton.setOnClickListener {
            send(EditFilterCommand.SelectApp)
        }

        setUidText = uidText.doAfterTextChanged(this@EditFilterFragment) { send(EditFilterCommand.UpdateUid(it?.toString().orEmpty())) }
        setPidText = pidText.doAfterTextChanged(this@EditFilterFragment) { send(EditFilterCommand.UpdatePid(it?.toString().orEmpty())) }
        setTidText = tidText.doAfterTextChanged(this@EditFilterFragment) { send(EditFilterCommand.UpdateTid(it?.toString().orEmpty())) }
        setPackageNameText = packageNameText.doAfterTextChanged(this@EditFilterFragment) {
            send(EditFilterCommand.UpdatePackageName(it?.toString().orEmpty()))
        }
        setTagText = tagText.doAfterTextChanged(this@EditFilterFragment) { send(EditFilterCommand.UpdateTag(it?.toString().orEmpty())) }
        setContentText = contentText.doAfterTextChanged(this@EditFilterFragment) {
            send(EditFilterCommand.UpdateContent(it?.toString().orEmpty()))
        }
    }

    override fun render(state: EditFilterViewState) {
        binding.apply {
            updateIncludingButton(state.including)
            updateEnabledButton(state.enabled)
            updateTitle(state.name)

            setTextIfDifferent(uidText, state.uid.orEmpty(), setUidText)
            setTextIfDifferent(pidText, state.pid.orEmpty(), setPidText)
            setTextIfDifferent(tidText, state.tid.orEmpty(), setTidText)
            setTextIfDifferent(packageNameText, state.packageName.orEmpty(), setPackageNameText)
            setTextIfDifferent(tagText, state.tag.orEmpty(), setTagText)
            setTextIfDifferent(contentText, state.content.orEmpty(), setContentText)

            levelButtons.forEach { (button, which) ->
                val checked = state.enabledLogLevels.getOrElse(which) { false }

                if (button.isChecked != checked) button.isChecked = checked
            }

            toolbar.menu.findItem(R.id.export_item).isVisible = state.filter != null
            toolbar.menu.findItem(R.id.tag_regex_item).isChecked = state.tagRegex
            toolbar.menu.findItem(R.id.content_regex_item).isChecked = state.contentRegex
        }
    }

    override fun handleSideEffect(sideEffect: EditFilterSideEffect) {
        when (sideEffect) {
            is EditFilterSideEffect.NavigateToAppPicker -> {
                findNavController().navigate(Directions.action_editFilterFragment_to_appsPickerFragment)
            }

            is EditFilterSideEffect.Close -> {
                findNavController().popBackStack()
            }

            is EditFilterSideEffect.ConfirmDiscard -> {
                showAreYouSureDialog(
                    title = Strings.discard_changes,
                    message = Strings.discard_changes_message,
                ) {
                    send(EditFilterCommand.AttemptCloseConfirmed)
                }
            }

            // Business logic side effects are handled by EffectHandler
            else -> Unit
        }
    }

    private fun FragmentEditFilterBinding.updateTitle(name: String?) = toolbar.run {
        title = name ?: getString(Strings.filter_name_hint)
        setTitleTextColor(
            MaterialColors.getColor(
                this,
                if (name == null) {
                    android.R.attr.textColorHint
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            ),
        )
    }

    private fun FragmentEditFilterBinding.updateIncludingButton(including: Boolean) = includingButton.run {
        setIconResource(if (including) Icons.ic_add else Icons.ic_clear)

        ColorStateList.valueOf(
            MaterialColors.getColor(
                this,
                if (including) {
                    android.R.attr.colorPrimary
                } else {
                    androidx.appcompat.R.attr.colorError
                },
            ),
        ).also {
            iconTint = it
            strokeColor = it
            setTextColor(it)
        }

        setText(if (including) Strings.including else Strings.excluding)
    }

    private fun FragmentEditFilterBinding.updateEnabledButton(enabled: Boolean) = enabledButton.run {
        setIconResource(if (enabled) Icons.ic_eye else Icons.ic_block)

        ColorStateList.valueOf(
            MaterialColors.getColor(
                this,
                if (enabled) {
                    android.R.attr.colorPrimary
                } else {
                    androidx.appcompat.R.attr.colorError
                },
            ),
        ).also {
            iconTint = it
            strokeColor = it
            setTextColor(it)
        }

        setText(if (enabled) Strings.enabled else Strings.disabled)
    }

    private fun showRenameDialog() = requireContext().showEditTextDialog(
        title = getString(Strings.rename_filter),
        initialText = viewModel.state.value.name,
        setupViews = { it.textLayout.setHint(Strings.filter_name) },
        setupDialog = { setIcon(Icons.ic_dialog_text_fields) },
    ) { newName ->
        send(EditFilterCommand.UpdateName(newName.orEmpty()))
    }

    // Updates the field only when the value actually changed, via the watcher-suppressing setter so a
    // render doesn't register as a user edit. The guard also avoids needlessly moving the cursor.
    private fun setTextIfDifferent(
        textView: android.widget.EditText,
        text: String,
        setText: (CharSequence?) -> Unit,
    ) {
        if (textView.text.toString() != text) {
            setText(text)
        }
    }
}
