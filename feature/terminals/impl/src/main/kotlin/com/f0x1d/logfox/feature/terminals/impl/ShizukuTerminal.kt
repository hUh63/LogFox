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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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

        private const val EXIT_CODE_FAILURE = 3
    }

    override val type = TerminalType.Shizuku
    override val title = R.string.shizuku

    private val userServiceMutex = Mutex()

    @Volatile
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

    private suspend fun ensureUserServiceBound(): Boolean = userServiceMutex.withLock {
        // Do NOT probe the cached proxy with `asBinder().isBinderAlive` here. The binder
        // handed out by Shizuku can report false while the user service is perfectly
        // alive, which made every isSupported() drop the proxy, rebind and time out -
        // the Shizuku backend was then shown as "unavailable" despite granted permission.
        // A genuinely dead proxy is already detected by execute()/executeNow(), which
        // call resetUserService() on RemoteException and rebind on the next call.
        if (userService != null) return@withLock true

        withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                        // pingBinder() throws when Shizuku dies between bind and connect, and
                        // an exception thrown from a binder callback is fatal to the process
                        val bound = runCatching {
                            if (binder == null || !binder.pingBinder()) return@runCatching false

                            userService = IUserService.Stub.asInterface(binder)

                            true
                        }.getOrElse { throwable ->
                            Timber.w(throwable, "Failed to bind the Shizuku user service")
                            false
                        }

                        if (continuation.isActive) continuation.resume(bound)
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
        val service = userService ?: return@withContext TerminalResult(EXIT_CODE_FAILURE)

        // The binder can die at any moment (Shizuku restarted, user service update, app
        // just granted READ_LOGS and the service is being recreated). An uncaught
        // RemoteException here used to crash the app right after the first
        // Shizuku authorization
        runCatching {
            service.executeNow(command.joinToString(" "))
        }.getOrElse { throwable ->
            Timber.w(throwable, "Shizuku user service failed, resetting")
            resetUserService()

            TerminalResult(EXIT_CODE_FAILURE)
        }
    }

    override fun execute(vararg command: String) = runCatching {
        userService?.run {
            val processId = execute(command.joinToString(" "))

            // Every binder call can return null (the :service process restarted, its
            // process table was cleared, ...). Returning on the first null leaked the
            // pipes already created AND left the logcat child running in the service
            // process forever - the 10 s retry kept adding more of them.
            val outputDescriptor = runCatching { processOutput(processId) }.getOrNull()
            val errorDescriptor = runCatching { processError(processId) }.getOrNull()
            val inputDescriptor = runCatching { processInput(processId) }.getOrNull()

            if (outputDescriptor == null || errorDescriptor == null || inputDescriptor == null) {
                listOfNotNull(outputDescriptor, errorDescriptor, inputDescriptor)
                    .forEach { descriptor -> runCatching { descriptor.close() } }
                runCatching { destroyProcess(processId) }

                return@run null
            }

            val output = AutoCloseInputStream(outputDescriptor)
            val error = AutoCloseInputStream(errorDescriptor)
            val input = AutoCloseOutputStream(inputDescriptor)

            TerminalProcess(
                output = output,
                error = error,
                input = input,
            ) {
                runCatching { input.close() }
                runCatching { error.close() }
                runCatching { output.close() }
                runCatching { destroyProcess(processId) }
            }
        }
    }.getOrElse { throwable ->
        Timber.w(throwable, "Shizuku user service failed to start a process, resetting")
        resetUserService()

        null
    }

    private fun resetUserService() {
        userService = null

        runCatching { Shizuku.unbindUserService(userServiceArgs, null, false) }
        runCatching { Shizuku.unbindUserService(userServiceArgs, null, true) }
    }

    override suspend fun exit() {
        try {
            // Catching as binder might not even was received
            runCatching {
                // With true flag it does not remove connection from cache
                Shizuku.unbindUserService(userServiceArgs, null, false)
                Shizuku.unbindUserService(userServiceArgs, null, true)
            }
        } finally {
            // Must be cleared even when unbinding throws: a stale proxy used to stay cached
            // forever and every later isSupported() kept returning true
            userService = null
        }
    }
}
