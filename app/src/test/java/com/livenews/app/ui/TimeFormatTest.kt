package com.livenews.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TimeFormatTest {
    private val now = Instant.parse("2026-11-05T18:00:00Z").toEpochMilli()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val utc = ZoneOffset.UTC

    @Test
    fun shortAges() {
        assertEquals("now", TimeFormat.short(now, now - 20_000, utc))
        assertEquals("now", TimeFormat.short(now, now + hour, utc))
        assertEquals("5m", TimeFormat.short(now, now - 5 * minute, utc))
        assertEquals("59m", TimeFormat.short(now, now - 59 * minute - 59_000, utc))
        assertEquals("3h", TimeFormat.short(now, now - 3 * hour - 10 * minute, utc))
        assertEquals("2d", TimeFormat.short(now, now - 2 * day - hour, utc))
        assertEquals("Oct 20", TimeFormat.short(now, now - 16 * day, utc))
    }

    @Test
    fun spokenAges() {
        assertEquals("just now", TimeFormat.ago(now, now - 20_000, utc))
        assertEquals("5 min ago", TimeFormat.ago(now, now - 5 * minute, utc))
        assertEquals("3 hr ago", TimeFormat.ago(now, now - 3 * hour, utc))
        assertEquals("yesterday", TimeFormat.ago(now, now - 30 * hour, utc))
        assertEquals("3 days ago", TimeFormat.ago(now, now - 3 * day, utc))
        assertEquals("on Oct 20", TimeFormat.ago(now, now - 16 * day, utc))
    }
}
