package com.f0x1d.logfox.feature.setup.impl.domain

import android.Manifest
import android.content.Context
import com.f0x1d.logfox.feature.setup.api.domain.ExecuteGrantViaShizukuUseCase
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.di.ShizukuTerminal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

internal class ExecuteGrantViaShizukuUseCaseImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ShizukuTerminal private val shizukuTerminal: Terminal,
) : ExecuteGrantViaShizukuUseCase {

    private val grantCommand: Array<String>
        get() = arrayOf("pm", "grant", context.packageName, Manifest.permission.READ_LOGS)

    override suspend fun invoke(): Boolean = runCatching {
        shizukuTerminal.isSupported() &&
            shizukuTerminal.executeNow(*grantCommand).isSuccessful
    }.getOrElse { throwable ->
        // A timeout/cancellation must propagate: swallowing it made the caller report
        // "shizuku error" and, worse, kept the cancelled work running
        if (throwable is CancellationException) throw throwable

        Timber.e(throwable, "Failed to grant READ_LOGS via Shizuku")
        false
    }
}
