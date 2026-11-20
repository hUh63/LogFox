package com.f0x1d.logfox.feature.datetime.impl

import android.content.Context
import android.icu.text.SimpleDateFormat
import com.f0x1d.logfox.feature.datetime.api.DateTimeFormatter
import com.f0x1d.logfox.feature.preferences.api.data.DateTimeSettingsRepository
import com.f0x1d.logfox.feature.strings.Strings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

internal class DateTimeFormatterImpl
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dateTimeSettingsRepository: DateTimeSettingsRepository,
) : DateTimeFormatter {

    // Formatters are cached against the pattern they were built for, so changing the
    // date/time format in settings takes effect immediately instead of after a process
    // restart. An invalid user-supplied pattern falls back to the default pattern instead
    // of throwing from a lazy initializer (which used to crash the whole screen).
    @Volatile
    private var cachedDatePattern: String? = null

    @Volatile
    private var cachedDateFormatter: SimpleDateFormat? = null

    @Volatile
    private var cachedTimePattern: String? = null

    @Volatile
    private var cachedTimeFormatter: SimpleDateFormat? = null

    override fun formatDate(time: Long): String = tryFormatBy(
        dateFormatterFor(dateTimeSettingsRepository.dateFormat().value),
        time,
    )

    override fun formatTime(time: Long): String = tryFormatBy(
        timeFormatterFor(dateTimeSettingsRepository.timeFormat().value),
        time,
    )

    override fun formatForExport(time: Long) = formatDate(time)
        .withReplacedBadSymbolsForFileName + "-" +
        formatTime(time)
            .withReplacedBadSymbolsForFileName

    private fun dateFormatterFor(pattern: String?): SimpleDateFormat {
        val key = pattern.orEmpty()

        cachedDateFormatter?.let { if (cachedDatePattern == key) return it }

        return createFormatter(key, DateTimeSettingsRepository.DATE_FORMAT_DEFAULT).also {
            cachedDatePattern = key
            cachedDateFormatter = it
        }
    }

    private fun timeFormatterFor(pattern: String?): SimpleDateFormat {
        val key = pattern.orEmpty()

        cachedTimeFormatter?.let { if (cachedTimePattern == key) return it }

        return createFormatter(key, DateTimeSettingsRepository.TIME_FORMAT_DEFAULT).also {
            cachedTimePattern = key
            cachedTimeFormatter = it
        }
    }

    private fun tryFormatBy(formatter: SimpleDateFormat, time: Long) = try {
        // android.icu.text.SimpleDateFormat is not thread safe
        synchronized(formatter) { formatter.format(time) }
    } catch (e: IllegalArgumentException) {
        context.getString(Strings.error, e.localizedMessage)
    }

    private fun createFormatter(format: String, fallback: String): SimpleDateFormat =
        runCatching { SimpleDateFormat(format, Locale.getDefault()) }
            .recoverCatching { SimpleDateFormat(fallback, Locale.getDefault()) }
            .getOrElse { SimpleDateFormat(fallback, Locale.getDefault()) }

    private val String.withReplacedBadSymbolsForFileName get() =
        replace(":", "-")
            .replace("[^a-zA-Z0-9\\-]".toRegex(), "_")
}
