package com.f0x1d.logfox.feature.logging.impl.domain

import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.logging.api.domain.GetLogcatSilentTagUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal class GetLogcatSilentTagUseCaseImpl @Inject constructor(
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : GetLogcatSilentTagUseCase {

    private companion object {
        val KEYS = listOf("persist.log.tag", "log.tag")

        // S = silent (nothing at all), W = warnings only (no debug/info/verbose)
        val SILENT_VALUES = setOf("S", "W")
    }

    // Runs a process in the fallback path, so it must never run on the main thread: this
    // used to fork `getprop` and block readText() on Main every time the log screen opened
    override suspend fun invoke(): String? = withContext(ioDispatcher) {
        for (key in KEYS) {
            val value = systemProperty(key)?.trim()?.uppercase() ?: continue

            if (value in SILENT_VALUES) return@withContext "$key=$value"
        }

        null
    }

    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)

        get.invoke(null, key) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))

            try {
                // getprop does not write to stderr, but the pipe was left open and the
                // child never destroyed: two descriptors leaked per call
                runCatching { process.errorStream.close() }

                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } finally {
                runCatching { process.destroy() }
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
