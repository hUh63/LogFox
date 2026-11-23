package com.f0x1d.logfox.feature.logging.impl.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.collection.LruCache
import com.f0x1d.logfox.feature.logging.api.data.LogLineParser
import com.f0x1d.logfox.feature.logging.api.model.LogLevel
import com.f0x1d.logfox.feature.logging.api.model.LogLine
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

internal class LogLineParserImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : LogLineParser {

    private val logRegex = LOG_REGEX.toRegex()

    // Fallback for lines the fixed-width pattern cannot express: uid names shorter than five
    // characters ("adb", "sdcard"), pids above five digits, a missing/ragged fraction, or an
    // empty message. Group order is identical to LOG_REGEX, so the same indices apply.
    private val relaxedLogRegex = RELAXED_LOG_REGEX.toRegex()
    private val uidRegex = UID_REGEX.toRegex()
    private val uidsCache = LruCache<String, String>(CACHE_SIZE)

    override fun parse(id: Long, line: String): LogLine? = runCatching {
        val trimmed = line.trim()

        val matchResult = logRegex.find(trimmed) ?: relaxedLogRegex.find(trimmed)

        if (matchResult == null) {
            Timber.d("Match result is null for: $line")
            return@runCatching null
        }

        val groups = matchResult.groupValues

        val uid = groups[UID_GROUP].trim()
        val packageName = resolvePackageName(uid)
        val time = parseTime(groups[TIME_GROUP])
        val level = parseLevel(groups[LEVEL_GROUP])

        LogLine(
            id = id,
            dateAndTime = time,
            uid = uid,
            pid = groups[PID_GROUP].trim(),
            tid = groups[TID_GROUP].trim(),
            packageName = packageName,
            level = level,
            tag = groups[TAG_GROUP].trim(),
            content = groups[CONTENT_GROUP],
            originalContent = groups[ORIGINAL_CONTENT_GROUP],
        )
    }.onFailure { throwable ->
        Timber.e(throwable, "Error while parsing log line")
    }.getOrNull()

    private fun resolvePackageName(uid: String): String? {
        uidsCache[uid]?.let { return it }

        val integerUid = resolveIntegerUid(uid) ?: return null

        return runCatching {
            val packageManager = context.packageManager

            // Multiple packages can share a single Linux uid (android:sharedUserId),
            // e.g. dozens of system apps under "android.uid.system". Picking the first
            // one made logs look like they come from a random sibling package, so
            // prefer the shared user id which is the only meaningful name for a group
            // (F0x1d/LogFox#239)
            val packageName = packageManager
                .getPackagesForUid(integerUid)
                ?.firstOrNull()
                ?: return@runCatching null

            (sharedUserId(packageManager, packageName) ?: packageName)
                .also { resolved -> uidsCache.put(uid, resolved) }
        }.getOrNull()
    }

    /**
     * sharedUserId is deprecated and gone from the SDK starting with API 36, so it has
     * to be read reflectively to keep working on devices that still have it
     */
    private fun sharedUserId(packageManager: PackageManager, packageName: String): String? =
        runCatching {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)

            ApplicationInfo::class.java
                .getField("sharedUserId")
                .get(applicationInfo) as? String
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun resolveIntegerUid(uid: String): Int? {
        uid.toIntOrNull()?.let { return it }

        WELL_KNOWN_UIDS[uid]?.let { return it }

        return uidRegex.find(uid)?.let { matchResult ->
            val userId = matchResult.groupValues[1].toInt()
            val appId = matchResult.groupValues[2].toInt()

            USER_UID_MULTIPLIER * userId + APP_UID_OFFSET + appId
        }
    }

    private fun parseTime(timeString: String): Long {
        val cleanTime = timeString.trim()
        val dotIndex = cleanTime.indexOf('.')

        // No fractional part at all used to make take(-1) return "" and toLong() throw,
        // dropping the whole line
        if (dotIndex < 0) {
            return cleanTime.toLong() * 1000
        }

        val seconds = cleanTime.take(dotIndex).toLong()

        // ".05" is 50 ms, not 5 ms: pad the fraction to milliseconds before parsing
        val milliseconds = cleanTime.substring(dotIndex + 1)
            .padEnd(3, '0')
            .take(3)
            .toLong()

        return seconds * 1000 + milliseconds
    }

    private fun parseLevel(level: String): LogLevel =
        LogLevel.entries.find { it.letter == level }
            // `A` (ASSERT, Log.wtf / native __android_log_assert) has no enum entry.
            // Throwing here made the whole line null and discarded it together with its
            // crash context; treat it as the highest severity instead
            ?: LogLevel.FATAL

    private companion object {
        // Format: time, uid, pid, tid, level, tag, message
        const val LOG_REGEX = "(.{14}) (.{5,}?) (.{1,5}) (.{1,5}) (.) (.+?): (.+)"

        // Anchored, whitespace-tolerant variant used only when LOG_REGEX does not match
        const val RELAXED_LOG_REGEX =
            "^(\\d{10}(?:\\.\\d{1,9})?) +(\\S{1,32}) +(\\d{1,10}) +(\\d{1,10}) +([VDIWEFAS]) +(.+?): ?(.*)$"
        const val UID_REGEX = "u(.+?).*a(.+)"

        const val CACHE_SIZE = 200
        const val USER_UID_MULTIPLIER = 100_000
        const val APP_UID_OFFSET = 10_000

        // Group indices
        const val ORIGINAL_CONTENT_GROUP = 0
        const val TIME_GROUP = 1
        const val UID_GROUP = 2
        const val PID_GROUP = 3
        const val TID_GROUP = 4
        const val LEVEL_GROUP = 5
        const val TAG_GROUP = 6
        const val CONTENT_GROUP = 7
    }
}
