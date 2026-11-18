package com.rasel.paperleaf.core

import com.rasel.paperleaf.data.ReadingDay
import com.rasel.paperleaf.data.SavedWord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Small rules with no Android in them (tested in src/test).

/**
 * A readable title from a PDF's file name:
 * "15-05-2021-084550The-Alchemist-Paulo-Coelho" becomes "The Alchemist Paulo Coelho".
 */
fun cleanBookTitle(fileName: String): String {
    val original = fileName.trim()
    var title = original
    // A date, time or download number on the front. A few digits stay: they
    // may be the title itself ("1984").
    val lead = Regex("""^[\d\s._()\[\]-]+""").find(title)?.value ?: ""
    if (lead.count { it.isDigit() } >= 6) title = title.substring(lead.length)
    // A copy number on the end: "Book (1)".
    title = title.replace(Regex("""\s*\(\d+\)\s*$"""), "")
    // Underscores stand for spaces; so do dashes when there is no space at all.
    title = title.replace('_', ' ')
    if (!title.trim().contains(' ')) title = title.replace('-', ' ')
    title = title.replace(Regex("""\s+"""), " ").trim()
    return title.ifEmpty { original }
}

// Word review: a word sits in a box, 0 to [LAST_BOX]. Knowing it moves it one
// box up, and each box rests longer. Not knowing it sends it back to box 0.

val REVIEW_INTERVALS = intArrayOf(0, 1, 3, 7, 16, 35)
val LAST_BOX = REVIEW_INTERVALS.size - 1

data class NextReview(val box: Int, val due: Long)

fun nextReview(box: Int, knew: Boolean, now: Long, zone: ZoneId = ZoneId.systemDefault()): NextReview {
    if (!knew) return NextReview(0, now)
    val next = (box + 1).coerceIn(1, LAST_BOX)
    // From the start of that day: a word known late at night is not held
    // back until late at night.
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(REVIEW_INTERVALS[next].toLong())
    return NextReview(next, day.atStartOfDay(zone).toInstant().toEpochMilli())
}

/** "today", "tomorrow", "in 3 days". */
fun dueLabel(due: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val days = ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(due).atZone(zone).toLocalDate(),
    )
    return when {
        days <= 0 -> "today"
        days == 1L -> "tomorrow"
        else -> "in $days days"
    }
}

// Reading stats

data class ReadingStats(
    val today: ReadingDay,
    val streak: Int,
    /** Oldest first, ending with today. */
    val lastSeven: List<ReadingDay>,
    val totalPages: Int,
    val totalSeconds: Int,
    val activeDays: Int,
)

fun readingStats(days: List<ReadingDay>, today: LocalDate): ReadingStats {
    val byDay = days.associateBy { it.day }
    fun on(d: LocalDate) = byDay[d] ?: ReadingDay(d, 0, 0)
    // Consecutive days ending today, or yesterday (today is not over yet).
    var cursor = if (on(today).active) today else today.minusDays(1)
    var streak = 0
    while (on(cursor).active) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return ReadingStats(
        today = on(today),
        streak = streak,
        lastSeven = (6 downTo 0).map { on(today.minusDays(it.toLong())) },
        totalPages = days.sumOf { it.pages },
        totalSeconds = days.sumOf { it.seconds },
        activeDays = days.count { it.active },
    )
}

/** "42 min", "1 h 05 min". */
fun formatDuration(seconds: Int): String {
    val minutes = Math.round(seconds / 60.0).toInt()
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
}

// Copying saved words

fun csvField(value: String): String =
    if (value.contains(Regex("""[",\n\r]"""))) "\"" + value.replace("\"", "\"\"") + "\"" else value

fun wordsToCsv(words: List<SavedWord>): String =
    (listOf("word,meaning,page") + words.map { "${csvField(it.word)},${csvField(it.meaning ?: "")},${it.page}" })
        .joinToString("\n")

fun wordsWithMeanings(words: List<SavedWord>): String =
    words.joinToString("\n") { if (it.meaning == null) it.word else "${it.word} — ${it.meaning}" }

// Highlight colors of earlier versions, and what replaces them (ARGB). Pale
// yellow sank into the paper and blues turned grey on tinted pages.

private val retiredHighlightColors = mapOf(
    0xFFFFC400.toInt() to 0xFFFF9100.toInt(), // yellow -> amber
    0xFF2979FF.toInt() to 0xFF8B2CFF.toInt(), // blue -> violet
    0xFF00AAFF.toInt() to 0xFF8B2CFF.toInt(), // light blue -> violet
)

/** The highlight colors on offer (ARGB): amber, green, pink, violet. */
val highlightPalette = listOf(0xFFFF9100.toInt(), 0xFF00C853.toInt(), 0xFFF50057.toInt(), 0xFF8B2CFF.toInt())

/** How far a highlight's color is mixed with white on the page: a marker pen, not paint. */
const val HIGHLIGHT_SOFTEN = 0.45f

/** [argb] mixed with white by [HIGHLIGHT_SOFTEN]: as the page shows it. */
fun softHighlight(argb: Int): Int {
    fun ch(shift: Int): Int {
        val c = (argb shr shift) and 0xFF
        return (c + (255 - c) * HIGHLIGHT_SOFTEN).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * The color a highlight gets inside the PDF: the soft color the page shows, so
 * other reader apps (which draw the file's color as it is) look the same.
 */
fun highlightColorForFile(argb: Int): Int = softHighlight(argb)

/**
 * A color read from a PDF: one of ours (saved soft) is the palette color again;
 * one from another app stays as it is.
 */
fun highlightColorFromFile(argb: Int): Int {
    fun near(a: Int, b: Int) = intArrayOf(16, 8, 0).all { s -> kotlin.math.abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)) <= 4 }
    return highlightPalette.firstOrNull { near(softHighlight(it), argb) } ?: argb
}

/** The color that replaces [argb], or null. A color read back from a PDF can be a step off per channel. */
fun replacementForHighlightColor(argb: Int): Int? {
    fun near(other: Int) = intArrayOf(16, 8, 0).all { s -> kotlin.math.abs(((argb shr s) and 0xFF) - ((other shr s) and 0xFF)) <= 4 }
    return retiredHighlightColors.entries.firstOrNull { near(it.key) }?.value
}
