package com.livenews.app.ui

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

object TimeFormat {
    private const val MINUTE = 60_000L
    private val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    private fun day(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "now", "5m", "3h", "2d", then the date. For lists that re-render every half minute. */
    fun short(now: Long, then: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val minutes = (now - then).coerceAtLeast(0) / MINUTE
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d"
            else -> day(then, zone)
        }
    }

    /** "just now", "5 min ago", "3 hr ago", "2 days ago", "on Nov 3". */
    fun ago(now: Long, then: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val minutes = (now - then).coerceAtLeast(0) / MINUTE
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} hr ago"
            minutes < 2 * 24 * 60 -> "yesterday"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
            else -> "on ${day(then, zone)}"
        }
    }

    /**
     * A clock time for today, the date otherwise. The widget uses this because it is only redrawn
     * every so often, and "5m ago" would be wrong by the time anyone looked.
     */
    fun clock(context: Context, now: Long, then: Long): String {
        val zone = ZoneId.systemDefault()
        val sameDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate() == Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
        return if (sameDay) DateFormat.getTimeFormat(context).format(Date(then)) else day(then, zone)
    }
}
