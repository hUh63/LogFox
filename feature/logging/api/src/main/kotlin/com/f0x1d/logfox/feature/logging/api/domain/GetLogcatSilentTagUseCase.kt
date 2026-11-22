package com.f0x1d.logfox.feature.logging.api.domain

/**
 * Some OEM builds (Nubia/ZTE and friends) ship with `persist.log.tag` set to `S` (silent),
 * which makes logcat return nothing at all - the app looks broken even though it works
 * (F0x1d/LogFox#258).
 *
 * Returns the offending system property (e.g. `persist.log.tag=S`) or null when logcat
 * is not muted.
 */
interface GetLogcatSilentTagUseCase {
    suspend operator fun invoke(): String?
}
