package com.f0x1d.logfox.feature.logging.impl.domain

import com.f0x1d.logfox.feature.logging.api.domain.GetLogcatSilentTagUseCase
import javax.inject.Inject

internal class GetLogcatSilentTagUseCaseImpl @Inject constructor() : GetLogcatSilentTagUseCase {

    private companion object {
        val KEYS = listOf("persist.log.tag", "log.tag")

        // S = silent (nothing at all), W = warnings only (no debug/info/verbose)
        val SILENT_VALUES = setOf("S", "W")
    }

    override fun invoke(): String? {
        for (key in KEYS) {
            val value = systemProperty(key)?.trim()?.uppercase() ?: continue

            if (value in SILENT_VALUES) return "$key=$value"
        }

        return null
    }

    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)

        get.invoke(null, key) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))

            process.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
}
