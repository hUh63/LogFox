package com.f0x1d.logfox.feature.filters.presentation.edit

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import com.f0x1d.logfox.feature.filters.api.model.UserFilter
import com.f0x1d.logfox.feature.filters.presentation.edit.di.EditFilterArgs
import com.f0x1d.logfox.feature.logging.api.model.LogLevel
import javax.inject.Inject

internal class EditFilterReducer @Inject constructor(
    private val args: EditFilterArgs,
) : Reducer<EditFilterState, EditFilterCommand, EditFilterSideEffect> {

    override fun reduce(
        state: EditFilterState,
        command: EditFilterCommand,
    ): ReduceResult<EditFilterState, EditFilterSideEffect> {
        val result = reduceInner(state, command)

        // Auto-save: editing an existing filter persists it right away, there is no save
        // button anymore (F0x1d/LogFox#261). New filters are created once, on close.
        return if (command.isFieldEdit && result.state.filter != null) {
            result.copy(sideEffects = result.sideEffects + result.state.toSaveFilterEffect())
        } else {
            result
        }
    }

    private fun reduceInner(
        state: EditFilterState,
        command: EditFilterCommand,
    ): ReduceResult<EditFilterState, EditFilterSideEffect> = when (command) {
        is EditFilterCommand.Load -> {
            state.withSideEffects(EditFilterSideEffect.LoadFilter(args.filterId))
        }

        is EditFilterCommand.FilterLoaded -> {
            val enabledLogLevels = MutableList(LogLevel.entries.size) { false }
            val allowedLevels = command.filter.allowedLevels.map { it.ordinal }
            for (i in enabledLogLevels.indices) {
                enabledLogLevels[i] = allowedLevels.contains(i)
            }

            state.copy(
                filter = command.filter,
                name = command.filter.name,
                including = command.filter.including,
                enabled = command.filter.enabled,
                enabledLogLevels = enabledLogLevels,
                uid = command.filter.uid,
                pid = command.filter.pid,
                tid = command.filter.tid,
                packageName = command.filter.packageName,
                tag = command.filter.tag,
                content = command.filter.content,
                tagRegex = command.filter.tagRegex,
                contentRegex = command.filter.contentRegex,
                isDirty = false,
            ).noSideEffects()
        }

        is EditFilterCommand.UpdateName -> {
            state.copy(name = command.name, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.UpdateUid -> {
            state.copy(uid = command.uid, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.UpdatePid -> {
            state.copy(pid = command.pid, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.UpdateTid -> {
            state.copy(tid = command.tid, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.UpdatePackageName -> {
            state.copy(packageName = command.packageName, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.UpdateTag -> {
            state.copy(tag = command.tag, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.UpdateContent -> {
            state.copy(content = command.content, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.ToggleIncluding -> {
            state.copy(including = !state.including, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.ToggleEnabled -> {
            state.copy(enabled = !state.enabled, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.ToggleTagRegex -> {
            state.copy(tagRegex = !state.tagRegex, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.ToggleContentRegex -> {
            state.copy(contentRegex = !state.contentRegex, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.FilterLevel -> {
            val newEnabledLogLevels = state.enabledLogLevels.toMutableList().apply {
                this[command.which] = command.filtering
            }
            state.copy(enabledLogLevels = newEnabledLogLevels, isDirty = true).noSideEffects()
        }

        is EditFilterCommand.ClearLevels -> {
            state.copy(
                enabledLogLevels = MutableList(LogLevel.entries.size) { false },
                isDirty = true,
            ).noSideEffects()
        }

        is EditFilterCommand.Save -> {
            state.withSideEffects(
                state.toSaveFilterEffect(),
                EditFilterSideEffect.Close,
            )
        }

        is EditFilterCommand.SavedAndClose -> {
            state.withSideEffects(EditFilterSideEffect.Close)
        }

        is EditFilterCommand.Export -> {
            // Export what is on screen right now. `state.filter` is only the snapshot loaded
            // when the screen opened, so after auto-save edits it was stale (and for a brand
            // new filter it was even null, which exported nothing at all).
            state.withSideEffects(
                EditFilterSideEffect.ExportFilter(
                    uri = command.uri,
                    filter = state.toCurrentFilter(),
                ),
            )
        }

        is EditFilterCommand.SelectApp -> {
            state.withSideEffects(EditFilterSideEffect.NavigateToAppPicker)
        }

        is EditFilterCommand.AttemptClose -> {
            // No save button anymore: leaving the screen persists the filter (F0x1d/LogFox#261).
            // A brand new filter with nothing filled in must not be created though - an empty
            // "including" filter matches every line and used to quietly disable the others.
            when {
                state.filter != null -> {
                    state.withSideEffects(EditFilterSideEffect.SaveAndClose(state.toSaveFilterEffect()))
                }

                state.hasMeaningfulContent() -> {
                    state.withSideEffects(EditFilterSideEffect.SaveAndClose(state.toSaveFilterEffect()))
                }

                else -> state.withSideEffects(EditFilterSideEffect.Close)
            }
        }
    }

    private fun List<Boolean>.toEnabledLogLevels(): List<LogLevel> = mapIndexedNotNull {
            index,
            value,
        ->
        if (value) LogLevel.entries[index] else null
    }

    private fun EditFilterState.toSaveFilterEffect() = EditFilterSideEffect.SaveFilter(
        filter = filter,
        name = name,
        including = including,
        enabled = enabled,
        enabledLogLevels = enabledLogLevels.toEnabledLogLevels(),
        uid = uid,
        pid = pid,
        tid = tid,
        packageName = packageName,
        tag = tag,
        content = content,
        tagRegex = tagRegex,
        contentRegex = contentRegex,
    )

    private fun EditFilterState.toCurrentFilter() = UserFilter(
        id = filter?.id ?: 0L,
        name = name,
        including = including,
        allowedLevels = enabledLogLevels.toEnabledLogLevels(),
        uid = uid,
        pid = pid,
        tid = tid,
        packageName = packageName,
        tag = tag,
        content = content,
        tagRegex = tagRegex,
        contentRegex = contentRegex,
        enabled = enabled,
    )

    private fun EditFilterState.hasMeaningfulContent(): Boolean =
        !name.isNullOrBlank() ||
            !uid.isNullOrBlank() ||
            !pid.isNullOrBlank() ||
            !tid.isNullOrBlank() ||
            !packageName.isNullOrBlank() ||
            !tag.isNullOrBlank() ||
            !content.isNullOrBlank() ||
            enabledLogLevels.any { it }

    private val EditFilterCommand.isFieldEdit: Boolean
        get() = when (this) {
            is EditFilterCommand.UpdateName,
            is EditFilterCommand.UpdateUid,
            is EditFilterCommand.UpdatePid,
            is EditFilterCommand.UpdateTid,
            is EditFilterCommand.UpdatePackageName,
            is EditFilterCommand.UpdateTag,
            is EditFilterCommand.UpdateContent,
            is EditFilterCommand.ToggleIncluding,
            is EditFilterCommand.ToggleEnabled,
            is EditFilterCommand.ToggleTagRegex,
            is EditFilterCommand.ToggleContentRegex,
            is EditFilterCommand.FilterLevel,
            is EditFilterCommand.ClearLevels,
            -> true

            else -> false
        }
}
