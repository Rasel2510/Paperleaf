package com.rasel.paperleaf.data

import android.graphics.RectF
import java.io.File

data class Book(
    val id: Long,
    val title: String,
    val filePath: String,
    val lastPage: Int,
    val pageCount: Int,
) {
    /** Cover image made at import, next to the PDF copy. */
    val coverPath: String get() = File(filePath).path.removeSuffix(".pdf") + ".jpg"

    val progress: Float
        get() = if (pageCount == 0) 0f else (lastPage.toFloat() / pageCount).coerceIn(0f, 1f)
}

/**
 * A highlight. [rects] are the marked lines in page points, from the page's
 * top-left corner. Highlights that came from the Flutter app (in a backup)
 * have none until their book is opened: the marks are then read from the
 * PDF itself.
 */
data class Highlight(
    val id: Long,
    val page: Int,
    val text: String,
    val color: Int?,
    val marker: String?,
    val rects: List<RectF>,
)

data class SavedWord(val id: Long, val page: Int, val word: String, val meaning: String?)

/** A saved word as a flashcard: the word of any book, with its meaning. */
data class ReviewWord(
    val id: Long,
    val word: String,
    val meaning: String,
    val page: Int,
    /** How well the word is known, 0 (new or forgotten) upwards. */
    val box: Int,
    val bookTitle: String,
)

/**
 * Where the saved words stand: [due] can be reviewed now, [resting] come back
 * later (the first at [nextDue], ms since the epoch), [withoutMeaning] cannot
 * be reviewed until they are looked up.
 */
data class ReviewCounts(val due: Int, val resting: Int, val withoutMeaning: Int, val nextDue: Long?)

/** Reading totals of one calendar day (all books together). */
data class ReadingDay(val day: java.time.LocalDate, val pages: Int, val seconds: Int) {
    val active: Boolean get() = pages > 0 || seconds > 0
}

/** "l,t,r,b;l,t,r,b" — how highlight lines are stored. */
fun List<RectF>.encodeRects(): String =
    joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

fun decodeRects(text: String?): List<RectF> {
    if (text.isNullOrBlank()) return emptyList()
    return text.split(';').mapNotNull { part ->
        val n = part.split(',').mapNotNull { it.toFloatOrNull() }
        if (n.size == 4) RectF(n[0], n[1], n[2], n[3]) else null
    }
}
