package com.f0x1d.logfox.feature.crashes.api.data

import android.net.Uri

/**
 * Stores an imported ProGuard/R8 mapping.txt and retraces obfuscated stack traces with it
 * (F0x1d/LogFox#264).
 */
interface CrashesRetraceRepository {

    fun isMappingAvailable(): Boolean

    /** @return true when the file was parsed and stored successfully */
    suspend fun importMapping(uri: Uri): Boolean

    suspend fun clearMapping()

    fun retrace(text: String): String
}
