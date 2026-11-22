package com.f0x1d.logfox.feature.export.impl.data

import android.content.Context
import android.net.Uri
import com.f0x1d.logfox.core.di.IODispatcher
import com.f0x1d.logfox.core.io.exportToZip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipOutputStream
import javax.inject.Inject

internal class ExportLocalDataSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ExportLocalDataSource {

    // openOutputStream() returns null for a deleted/invalid URI. Using ?.use meant nothing
    // was written and no error was raised: the export "succeeded" with an empty file
    private fun openOutputStreamOrThrow(uri: Uri) =
        context.contentResolver.openOutputStream(uri)
            ?: throw FileNotFoundException("Cannot open output stream for $uri")

    private fun openInputStreamOrThrow(uri: Uri) =
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open input stream for $uri")

    override suspend fun writeContentToUri(uri: Uri, content: String): Unit = withContext(ioDispatcher) {
        openOutputStreamOrThrow(uri).use {
            it.write(content.encodeToByteArray())
        }
    }

    override suspend fun writeContentAndFileToUri(uri: Uri, content: String, file: File): Unit = withContext(ioDispatcher) {
        // Fail before touching the destination, otherwise the content is already written
        // and the export leaves a truncated file behind
        if (!file.exists()) throw FileNotFoundException("Missing source file: $file")

        openOutputStreamOrThrow(uri).use { outputStream ->
            outputStream.write(content.encodeToByteArray())
            file.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    override suspend fun copyFileToUri(uri: Uri, file: File): Unit = withContext(ioDispatcher) {
        if (!file.exists()) throw FileNotFoundException("Missing source file: $file")

        openOutputStreamOrThrow(uri).use { outputStream ->
            file.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    override suspend fun writeZipToUri(
        uri: Uri,
        block: ZipOutputStream.() -> Unit,
    ): Unit = withContext(ioDispatcher) {
        openOutputStreamOrThrow(uri).use {
            it.exportToZip(block)
        }
    }

    override suspend fun readContentFromUri(uri: Uri): String? = withContext(ioDispatcher) {
        openInputStreamOrThrow(uri).use { input ->
            // Streamed with a cap: readBytes() + decodeToString() held up to 3x the file
            // size in memory at once
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0

            while (true) {
                val read = input.read(chunk)

                if (read < 0) break

                total += read

                if (total > MAX_IMPORT_BYTES) {
                    throw IOException("File is too large to import ($total bytes)")
                }

                output.write(chunk, 0, read)
            }

            output.toByteArray().decodeToString()
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    }
}
