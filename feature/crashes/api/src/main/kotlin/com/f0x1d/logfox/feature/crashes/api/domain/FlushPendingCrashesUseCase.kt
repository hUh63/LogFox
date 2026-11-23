package com.f0x1d.logfox.feature.crashes.api.domain

/**
 * Completes a crash that is still being collected but received no further log lines.
 *
 * Crash collection used to be flushed only when the *next* line arrived, so the most common
 * case — the crashing process dies and nothing else logs afterwards — left the crash neither
 * stored nor notified until unrelated output happened to show up (or never).
 */
interface FlushPendingCrashesUseCase {
    suspend operator fun invoke()
}
