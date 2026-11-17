package com.f0x1d.logfox.feature.terminals.impl

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.ParcelFileDescriptor.AutoCloseOutputStream
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.strings.Strings
import com.f0x1d.logfox.feature.terminals.api.base.Terminal
import com.f0x1d.logfox.feature.terminals.api.base.TerminalType
import com.f0x1d.logfox.feature.terminals.api.model.TerminalProcess
import com.f0x1d.logfox.feature.terminals.api.model.TerminalResult
import com.f0x1d.logfox.feature.terminals.presentation.IUserService
import com.f0x1d.logfox.feature.terminals.presentation.service.UserService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
internal class ShizukuTerminal @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : Terminal {

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_ID = 8

        // Shizuku callbacks are not guaranteed to arrive (dialog dismissed, Shizuku
        // killed, device profile where the user service can't be started). Waiting
        // forever left the setup screen doing literally nothing on click, see
        // F0x1d/LogFox#249 and F0x1d/LogFox#256
        private const val PERMISSION_TIMEOUT_MS = 30_000L
        private const val BIND_TIMEOUT_MS = 15_000L
    }

    override val type = TerminalType.Shizuku
    override val title = R.string.shizuku

    private var userService: IUserService? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, UserService::class.java))
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(
                context.run {
                    packageManager.getPackageInfo(packageName, 0).versionCode
                },
            )
            .tag(context.getString(Strings.app_name))
    }

    override suspend fun isSupported(): Boolean {
        val hasPermission = runCatching { ensurePermission() }.getOrDefault(false)

        if (hasPermission.not()) return false

        return runCatching { ensureUserServiceBound() }.getOrDefault(false)
    }

    private suspend fun ensurePermission(): Boolean = when {
        runCatching { Shizuku.pingBinder() }.getOrDefault(false).not() -> false

        Shizuku.isPreV11() -> false

        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true

        Shizuku.shouldShowRequestPermissionRationale() -> false

        else -> withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != SHIZUKU_PERMISSION_REQUEST_ID) return

                        runCatching { Shizuku.removeRequestPermissionResultListener(this) }

                        if (continuation.isActive) {
                            continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                }

                Shizuku.addRequestPermissionResultListener(listener)

                runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_ID) }
                    .onFailure {
                        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }

                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        } ?: false
    }

    private suspend fun ensureUserServiceBound(): Boolean {
        if (userService != null) return true

        return withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                        if (binder == null || !binder.pingBinder()) {
                            if (continuation.isActive) continuation.resume(false)
                            return
                        }

                        userService = IUserService.Stub.asInterface(binder)

                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onServiceDisconnected(componentName: ComponentName?) {
                        userService = null
                    }
                }

                continuation.invokeOnCancellation {
                    runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                }

                runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
                    .onFailure {
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        } ?: false
    }

    override suspend fun executeNow(vararg command: String) = withContext(ioDispatcher) {
        userService?.executeNow(command.joinToString(" ")) ?: TerminalResult(3)
    }

    override fun execute(vararg command: String) = userService?.run {
        val processId = execute(command.joinToString(" "))

        TerminalProcess(
            output = AutoCloseInputStream(processOutput(processId) ?: return@run null),
            error = AutoCloseInputStream(processError(processId) ?: return@run null),
            input = AutoCloseOutputStream(processInput(processId) ?: return@run null),
        ) {
            destroyProcess(processId)
        }
    }

    override suspend fun exit() {
        // Catching as binder might not even was received
        runCatching {
            // With true flag it does not remove connection from cache
            Shizuku.unbindUserService(userServiceArgs, null, false)
            Shizuku.unbindUserService(userServiceArgs, null, true)

            userService = null
        }
    }
}
