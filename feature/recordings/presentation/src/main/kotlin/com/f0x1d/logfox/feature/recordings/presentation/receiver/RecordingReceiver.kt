package com.f0x1d.logfox.feature.recordings.presentation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.f0x1d.logfox.core.di.MainDispatcher
import com.f0x1d.logfox.feature.recordings.api.domain.EndRecordingUseCase
import com.f0x1d.logfox.feature.recordings.api.domain.PauseRecordingUseCase
import com.f0x1d.logfox.feature.recordings.api.domain.ResumeRecordingUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RecordingReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE_LOGGING = "logfox.PAUSE_LOGGING"
        const val ACTION_RESUME_LOGGING = "logfox.RESUME_LOGGING"
        const val ACTION_STOP_LOGGING = "logfox.STOP_LOGGING"
    }

    @Inject
    lateinit var pauseRecordingUseCase: PauseRecordingUseCase

    @Inject
    lateinit var resumeRecordingUseCase: ResumeRecordingUseCase

    @Inject
    lateinit var endRecordingUseCase: EndRecordingUseCase

    @Inject
    @MainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        // finish() takes a recording to disk and inserts the Room row. Without goAsync the
        // process could be frozen the moment onReceive returned, losing the file and the
        // database entry (the in-memory state then stayed "recording")
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

        scope.launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE_LOGGING -> pauseRecordingUseCase()
                    ACTION_RESUME_LOGGING -> resumeRecordingUseCase()
                    ACTION_STOP_LOGGING -> endRecordingUseCase()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
