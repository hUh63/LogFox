package com.f0x1d.logfox.feature.setup.impl.domain

import android.Manifest
import android.content.Context
import com.f0x1d.logfox.feature.setup.api.domain.ExecuteGrantViaRootUseCase
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.di.RootTerminal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

internal class ExecuteGrantViaRootUseCaseImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @RootTerminal private val rootTerminal: Terminal,
) : ExecuteGrantViaRootUseCase {

    private val grantCommand: Array<String>
        get() = arrayOf("pm", "grant", context.packageName, Manifest.permission.READ_LOGS)

    override suspend fun invoke(): Boolean = runCatching {
        // Check the exit code instead of assuming success: a denied su prompt or an SELinux
        // denial used to be reported as "granted", which then showed up as an empty log list.
        rootTerminal.isSupported() && rootTerminal.executeNow(*grantCommand).isSuccessful
    }.getOrElse { throwable ->
        if (throwable is CancellationException) throw throwable

        false
    }
}
