package com.rasel.paperleaf

import com.rasel.paperleaf.core.Dictionary
import com.rasel.paperleaf.core.cleanBookTitle
import com.rasel.paperleaf.core.csvField
import com.rasel.paperleaf.core.dueLabel
import com.rasel.paperleaf.core.formatDuration
import com.rasel.paperleaf.core.nextReview
import com.rasel.paperleaf.core.readingStats
import com.rasel.paperleaf.data.ReadingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class LogicTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).toInstant(zone).toEpochMilli()

    @Test
    fun titlesFromFileNames() {
        assertEquals("The Alchemist Paulo Coelho", cleanBookTitle("15-05-2021-084550The-Alchemist-Paulo-Coelho"))
        assertEquals("1984", cleanBookTitle("1984"))
        assertEquals("2001 A Space Odyssey", cleanBookTitle("2001 A Space Odyssey"))
        assertEquals("my book final", cleanBookTitle("my_book_final"))
        assertEquals("Deep Work", cleanBookTitle("Deep-Work"))
        assertEquals("Spider-Man 2", cleanBookTitle("Spider-Man 2"))
        assertEquals("Atomic Habits", cleanBookTitle("Atomic Habits (1)"))
        assertEquals("20240101_123456", cleanBookTitle("20240101_123456"))
    }

    @Test
    fun knownWordsRestLongerEachTime() {
        val now = at(2026, 11, 17, 22, 30)
        val days = listOf(1, 3, 7, 16, 35)
        for ((box, wait) in days.withIndex()) {
            val next = nextReview(box, true, now, zone)
            assertEquals(box + 1, next.box)
            assertEquals(at(2026, 11, 17 + 0, 0) + wait * 86_400_000L, next.due)
        }
        assertEquals(5, nextReview(99, true, now, zone).box)
    }

    @Test
    fun aForgottenWordStartsOver() {
        val now = at(2026, 11, 17, 9)
        val next = nextReview(4, false, now, zone)
        assertEquals(0, next.box)
        assertEquals(now, next.due)
    }

    @Test
    fun dueDaysInPlainWords() {
        val now = at(2026, 11, 17, 22)
        assertEquals("today", dueLabel(at(2026, 11, 17), now, zone))
        assertEquals("tomorrow", dueLabel(at(2026, 11, 18), now, zone))
        assertEquals("in 3 days", dueLabel(at(2026, 11, 20), now, zone))
        assertEquals("today", dueLabel(at(2026, 11, 15), now, zone))
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val today = LocalDate.of(2026, 11, 17)
        val days = listOf(
            ReadingDay(today.minusDays(1), 5, 60),
            ReadingDay(today.minusDays(2), 3, 60),
            ReadingDay(today.minusDays(4), 9, 60),
        )
        val stats = readingStats(days, today)
        assertEquals(2, stats.streak)
        assertEquals(17, stats.totalPages)
        assertEquals(7, stats.lastSeven.size)
        assertEquals(today, stats.lastSeven.last().day)
    }

    @Test
    fun durationsAndCsv() {
        assertEquals("42 min", formatDuration(42 * 60))
        assertEquals("1 h 05 min", formatDuration(65 * 60))
        assertEquals("\"a, b\"", csvField("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
    }

    @Test
    fun onlySingleWordsAreLookedUp() {
        assertEquals("horizon", Dictionary.lookupWord("“horizon,”"))
        assertNull(Dictionary.lookupWord("two words"))
        assertEquals("(noun) a sign", Dictionary.parseDefinition("""[{"meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"a sign"}]}]}]"""))
        assertNull(Dictionary.parseDefinition("not json"))
    }
}
