package com.f0x1d.logfox.feature.crashes.impl.data

import android.content.Context
import android.net.Uri
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.feature.crashes.api.data.CrashesRetraceRepository
import com.f0x1d.logfox.feature.crashes.impl.retrace.ProguardRetrace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class CrashesRetraceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : CrashesRetraceRepository {

    private companion object {
        const val MAPPING_FILE_NAME = "proguard-mapping.txt"

        // mapping.txt of a big app is a few MB, anything way above that is not a mapping file
        const val MAX_MAPPING_BYTES = 64 * 1024 * 1024
    }

    private val mappingFile = File(context.filesDir, MAPPING_FILE_NAME)

    @Volatile
    private var cached: ProguardRetrace? = null

    override fun isMappingAvailable(): Boolean = mappingFile.exists()

    override suspend fun importMapping(uri: Uri): Boolean = withContext(ioDispatcher) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes(MAX_MAPPING_BYTES).decodeToString()
            } ?: return@runCatching false

            val retrace = ProguardRetrace.parse(text)

            mappingFile.writeText(text)
            cached = retrace

            true
        }.getOrElse { throwable ->
            Timber.e(throwable, "Failed to import ProGuard mapping")
            false
        }
    }

    override suspend fun clearMapping() = withContext(ioDispatcher) {
        runCatching { mappingFile.delete() }
        cached = null
    }

    override fun retrace(text: String): String {
        val retrace = retrace() ?: return text

        return runCatching { retrace.retrace(text) }
            .getOrElse { throwable ->
                Timber.e(throwable, "Failed to retrace stack trace")
                text
            }
    }

    private fun retrace(): ProguardRetrace? {
        cached?.let { return it }

        if (!mappingFile.exists()) return null

        val parsed = runCatching {
            ProguardRetrace.parse(mappingFile.readText())
        }.getOrElse { throwable ->
            Timber.e(throwable, "Failed to parse stored ProGuard mapping")
            null
        }

        cached = parsed

        return parsed
    }
}
