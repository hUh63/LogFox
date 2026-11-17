package com.f0x1d.logfox.feature.recordings.impl.domain

import android.net.Uri
import com.f0x1d.logfox.core.context.deviceData
import com.f0x1d.logfox.feature.export.api.data.ExportRepository
import com.f0x1d.logfox.feature.preferences.api.domain.service.GetIncludeDeviceInfoInArchivesUseCase
import com.f0x1d.logfox.feature.recordings.api.data.RecordingsRepository
import com.f0x1d.logfox.feature.recordings.api.domain.ExportRecordingFileUseCase
import java.io.FileNotFoundException
import javax.inject.Inject

internal class ExportRecordingFileUseCaseImpl @Inject constructor(
    private val recordingsRepository: RecordingsRepository,
    private val exportRepository: ExportRepository,
    private val getIncludeDeviceInfoInArchivesUseCase: GetIncludeDeviceInfoInArchivesUseCase,
) : ExportRecordingFileUseCase {

    override suspend fun invoke(recordingId: Long, uri: Uri): Result<Unit> = runCatching {
        val recording = recordingsRepository.getById(recordingId) ?: return@runCatching

        // Recording file might be gone (cleared storage, deleted while recording is
        // still referenced by the database, restored backup, etc.) - reading it
        // unconditionally used to throw FileNotFoundException and crash the app
        // (F0x1d/LogFox#254)
        if (!recording.file.exists()) {
            throw FileNotFoundException(recording.file.absolutePath)
        }

        if (getIncludeDeviceInfoInArchivesUseCase()) {
            val prefix = deviceData + "\n\n"
            exportRepository.writeContentAndFileToUri(uri, prefix, recording.file)
        } else {
            exportRepository.copyFileToUri(uri, recording.file)
        }
    }
}
