package com.livenews.app.data

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Reads the date formats feeds actually use: RFC 822 (RSS), ISO 8601 (Atom), and common near-misses. */
object DateParser {
    private val spaces = Regex("""\s+""")
    private val weekday = Regex("""^[A-Za-z]{3,9}\.?,?\s+(?=\d)""")
    private val shortYear = Regex("""^(\d{1,2} [A-Za-z]{3,9} )(\d{2})(?= )""")
    private val earliest = Instant.parse("1995-01-01T00:00:00Z").toEpochMilli()

    private fun format(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern(pattern).toFormatter(Locale.US)

    private val zoned = listOf(
        "d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss XXX",
        "d MMM yyyy HH:mm:ss z",
        "d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm z",
        "d MMMM yyyy HH:mm:ss Z",
        "d MMMM yyyy HH:mm:ss z",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ss z",
    ).map(::format)

    private val local = listOf(
        "d MMM yyyy HH:mm:ss",
        "d MMM yyyy HH:mm",
        "d MMMM yyyy HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
    ).map(::format)

    /** Milliseconds since 1970, or null when [raw] is not a date this app understands. */
    fun parse(raw: String?): Long? {
        var text = spaces.replace(raw?.trim().orEmpty(), " ")
        if (text.isEmpty()) return null
        val millis = iso(text) ?: run {
            text = weekday.replace(text, "")
            text = shortYear.replace(text) { m -> m.groupValues[1] + "20" + m.groupValues[2] }
            text = text.replace("Sept ", "Sep ")
            if (text.endsWith(" UT") || text.endsWith(" Z")) text = text.substringBeforeLast(' ') + " GMT"
            rfc(text)
        }
        return millis?.takeIf { it >= earliest }
    }

    private fun iso(text: String): Long? {
        if (text.length < 10 || !text[0].isDigit() || text[4] != '-') return null
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: DateTimeException) {
        }
        if (text.length == 10) {
            try {
                return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeException) {
            }
        }
        return null
    }

    private fun rfc(text: String): Long? {
        for (formatter in zoned) {
            try {
                return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (e: DateTimeException) {
            }
        }
        for (formatter in local) {
            try {
                return LocalDateTime.parse(text, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (e: DateTimeException) {
            }
        }
        return null
    }
}
