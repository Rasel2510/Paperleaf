package com.rasel.paperleaf.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Every read and write of the library database. All functions run off the main thread. */
class Repository(private val helper: Db) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val _changes = MutableStateFlow(0)

    /** Goes up after every write: screens showing data reload when it does. */
    val changes: StateFlow<Int> = _changes

    private fun changed() {
        _changes.value++
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun now() = System.currentTimeMillis()

    // Books

    suspend fun books(): List<Book> = io {
        db.rawQuery("SELECT * FROM books ORDER BY last_opened DESC", null).use { c ->
            c.all { book() }
        }
    }

    suspend fun book(id: Long): Book? = io {
        db.rawQuery("SELECT * FROM books WHERE id = ?", arrayOf("$id")).use { c ->
            if (c.moveToFirst()) c.book() else null
        }
    }

    suspend fun addBook(title: String, path: String, pageCount: Int): Long = io {
        db.insertOrThrow(
            "books",
            null,
            ContentValues().apply {
                put("title", title)
                put("file_path", path)
                put("page_count", pageCount)
                put("last_opened", now())
                // A PDF added here carries no marks of ours; one from elsewhere
                // is still looked through once, for highlights made in other apps.
                put("marks_imported", 0)
            },
        )
    }.also { changed() }

    suspend fun renameBook(id: Long, title: String) = update("books", id, ContentValues().apply { put("title", title) })

    suspend fun saveProgress(id: Long, page: Int) =
        update("books", id, ContentValues().apply { put("last_page", page); put("last_opened", now()) })

    suspend fun savePageCount(id: Long, count: Int) =
        update("books", id, ContentValues().apply { put("page_count", count) })

    suspend fun marksImported(id: Long): Boolean = io {
        db.rawQuery("SELECT marks_imported FROM books WHERE id = ?", arrayOf("$id")).use { c ->
            c.moveToFirst() && c.getInt(0) != 0
        }
    }

    suspend fun setMarksImported(id: Long) =
        update("books", id, ContentValues().apply { put("marks_imported", 1) })

    suspend fun deleteBook(book: Book) {
        io { db.delete("books", "id = ?", arrayOf("${book.id}")) }
        // The book is off the shelf either way; a file left behind only takes space.
        for (path in listOf(book.filePath, book.coverPath)) runCatching { File(path).delete() }
        changed()
    }

    private suspend fun update(table: String, id: Long, values: ContentValues) {
        io { db.update(table, values, "id = ?", arrayOf("$id")) }
        changed()
    }

    // Highlights

    suspend fun highlights(bookId: Long): List<Highlight> = io {
        db.rawQuery(
            "SELECT * FROM highlights WHERE book_id = ? ORDER BY page, id",
            arrayOf("$bookId"),
        ).use { c -> c.all { highlight() } }
    }

    suspend fun addHighlight(
        bookId: Long,
        page: Int,
        text: String,
        color: Int?,
        marker: String?,
        rects: List<RectF>,
    ): Long = io {
        db.insertOrThrow(
            "highlights",
            null,
            ContentValues().apply {
                put("book_id", bookId)
                put("page", page)
                put("text", text)
                if (color != null) put("color", color.toLong() and 0xFFFFFFFFL) else putNull("color")
                put("annotation_marker", marker)
                put("created_at", now())
                put("rects", rects.encodeRects())
            },
        )
    }.also { changed() }

    suspend fun setHighlightRects(id: Long, rects: List<RectF>) =
        update("highlights", id, ContentValues().apply { put("rects", rects.encodeRects()) })

    suspend fun setHighlightColor(id: Long, color: Int) =
        update("highlights", id, ContentValues().apply { put("color", color.toLong() and 0xFFFFFFFFL) })

    suspend fun deleteHighlight(id: Long) {
        io { db.delete("highlights", "id = ?", arrayOf("$id")) }
        changed()
    }

    // Bookmarks

    suspend fun bookmarkPages(bookId: Long): Set<Int> = io {
        db.rawQuery("SELECT page FROM bookmarks WHERE book_id = ?", arrayOf("$bookId")).use { c ->
            c.all { getInt(0) }.toSet()
        }
    }

    suspend fun setBookmark(bookId: Long, page: Int, on: Boolean) {
        io {
            if (on) {
                db.insertWithOnConflict(
                    "bookmarks",
                    null,
                    ContentValues().apply { put("book_id", bookId); put("page", page) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            } else {
                db.delete("bookmarks", "book_id = ? AND page = ?", arrayOf("$bookId", "$page"))
            }
        }
        changed()
    }

    // Saved words

    suspend fun words(bookId: Long): List<SavedWord> = io {
        db.rawQuery("SELECT * FROM words WHERE book_id = ? ORDER BY id DESC", arrayOf("$bookId")).use { c ->
            c.all { SavedWord(long("id"), int("page"), string("word")!!, string("meaning")) }
        }
    }

    suspend fun addWord(bookId: Long, page: Int, word: String, meaning: String?): Long = io {
        db.insertOrThrow(
            "words",
            null,
            ContentValues().apply {
                put("book_id", bookId)
                put("page", page)
                put("word", word)
                put("meaning", meaning)
                put("created_at", now())
            },
        )
    }.also { changed() }

    suspend fun setWordMeaning(id: Long, meaning: String?) =
        update("words", id, ContentValues().apply { put("meaning", meaning) })

    suspend fun deleteWord(id: Long) {
        io { db.delete("words", "id = ?", arrayOf("$id")) }
        changed()
    }

    // Word review

    /** Words (of every book) that have a meaning and are due at [now], longest waiting first. */
    suspend fun wordsToReview(now: Long, limit: Int = 30): List<ReviewWord> = io {
        db.rawQuery(
            "SELECT w.id, w.word, w.meaning, w.page, w.box, b.title " +
                "FROM words w JOIN books b ON b.id = w.book_id " +
                "WHERE w.meaning IS NOT NULL AND w.meaning <> '' AND w.due <= ? " +
                "ORDER BY w.due, w.id LIMIT ?",
            arrayOf("$now", "$limit"),
        ).use { c ->
            c.all {
                ReviewWord(long("id"), string("word")!!, string("meaning")!!, int("page"), int("box"), string("title")!!)
            }
        }
    }

    suspend fun reviewCounts(now: Long): ReviewCounts = io {
        val explained = "meaning IS NOT NULL AND meaning <> ''"
        db.rawQuery(
            "SELECT " +
                "SUM(CASE WHEN $explained AND due <= ? THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN $explained AND due > ? THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN $explained THEN 0 ELSE 1 END), " +
                "MIN(CASE WHEN $explained AND due > ? THEN due END) FROM words",
            arrayOf("$now", "$now", "$now"),
        ).use { c ->
            c.moveToFirst()
            ReviewCounts(
                due = c.getInt(0),
                resting = c.getInt(1),
                withoutMeaning = c.getInt(2),
                nextDue = if (c.isNull(3)) null else c.getLong(3),
            )
        }
    }

    suspend fun setWordReview(id: Long, box: Int, due: Long) =
        update("words", id, ContentValues().apply { put("box", box); put("due", due) })

    // Reading log

    /** Adds to today's totals of [bookId]. */
    suspend fun logReading(bookId: Long, pages: Int, seconds: Int) {
        if (pages == 0 && seconds == 0) return
        io {
            val day = LocalDate.now().toString()
            db.beginTransaction()
            try {
                db.execSQL(
                    "UPDATE reading_log SET pages = pages + ?, seconds = seconds + ? WHERE day = ? AND book_id = ?",
                    arrayOf<Any>(pages, seconds, day, bookId),
                )
                val changed = db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }
                if (changed == 0) {
                    db.insert(
                        "reading_log",
                        null,
                        ContentValues().apply {
                            put("day", day); put("book_id", bookId); put("pages", pages); put("seconds", seconds)
                        },
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun readingDays(): List<ReadingDay> = io {
        db.rawQuery("SELECT day, SUM(pages), SUM(seconds) FROM reading_log GROUP BY day", null).use { c ->
            c.all { ReadingDay(LocalDate.parse(getString(0)), getInt(1), getInt(2)) }
        }
    }

    // Backup

    /** Every book with all that belongs to it, as JSON for a backup file. */
    suspend fun exportLibrary(): List<Pair<String, JSONObject>> = io {
        val out = mutableListOf<Pair<String, JSONObject>>()
        db.rawQuery("SELECT * FROM books ORDER BY id", null).use { books ->
            while (books.moveToNext()) {
                val id = books.long("id")
                val path = books.string("file_path")!!
                fun rows(sql: String, map: Cursor.() -> Any): JSONArray =
                    db.rawQuery(sql, arrayOf("$id")).use { c -> JSONArray(c.all(map)) }

                out += path to JSONObject().apply {
                    put("title", books.string("title"))
                    put("file", File(path).name)
                    put("last_page", books.int("last_page"))
                    put("page_count", books.int("page_count"))
                    put("last_opened", books.long("last_opened"))
                    put(
                        "highlights",
                        rows("SELECT * FROM highlights WHERE book_id = ?") {
                            JSONObject().apply {
                                put("page", int("page"))
                                put("text", string("text"))
                                put("color", if (isNull(getColumnIndexOrThrow("color"))) JSONObject.NULL else long("color"))
                                put("annotation_marker", string("annotation_marker") ?: JSONObject.NULL)
                                put("created_at", long("created_at"))
                                put("rects", string("rects") ?: JSONObject.NULL)
                            }
                        },
                    )
                    put("bookmarks", rows("SELECT page FROM bookmarks WHERE book_id = ?") { getInt(0) })
                    put(
                        "words",
                        rows("SELECT * FROM words WHERE book_id = ?") {
                            JSONObject().apply {
                                put("page", int("page"))
                                put("word", string("word"))
                                put("meaning", string("meaning") ?: JSONObject.NULL)
                                put("created_at", long("created_at"))
                                put("box", int("box"))
                                put("due", long("due"))
                            }
                        },
                    )
                    put(
                        "reading",
                        rows("SELECT day, pages, seconds FROM reading_log WHERE book_id = ?") {
                            JSONObject().apply {
                                put("day", getString(0)); put("pages", getInt(1)); put("seconds", getInt(2))
                            }
                        },
                    )
                }
            }
        }
        out
    }

    /** File names (not paths) of the PDFs of all books on the shelf. */
    suspend fun bookFileNames(): MutableSet<String> = io {
        db.rawQuery("SELECT file_path FROM books", null).use { c -> c.all { File(getString(0)).name }.toMutableSet() }
    }

    /**
     * Puts one book from a backup (made here or by the Flutter app) on the
     * shelf with all that belongs to it, or nothing at all if a part fails.
     * Every value is checked: it was read from a file.
     */
    suspend fun importBook(book: JSONObject, filePath: String) {
        io {
            fun num(o: JSONObject, key: String, fallback: Long): Long =
                (o.opt(key) as? Number)?.toLong() ?: fallback
            fun text(o: JSONObject, key: String): String? =
                (o.opt(key) as? String)?.takeIf { it.isNotEmpty() }
            fun objects(key: String): List<JSONObject> {
                val list = book.optJSONArray(key) ?: return emptyList()
                return (0 until list.length()).mapNotNull { list.opt(it) as? JSONObject }
            }

            db.beginTransaction()
            try {
                val id = db.insertOrThrow(
                    "books",
                    null,
                    ContentValues().apply {
                        put("title", text(book, "title") ?: "Untitled")
                        put("file_path", filePath)
                        put("last_page", num(book, "last_page", 1))
                        put("page_count", num(book, "page_count", 0))
                        put("last_opened", num(book, "last_opened", now()))
                        put("marks_imported", 0)
                    },
                )
                for (row in objects("highlights")) {
                    val words = text(row, "text") ?: continue
                    db.insertOrThrow(
                        "highlights",
                        null,
                        ContentValues().apply {
                            put("book_id", id)
                            put("page", num(row, "page", 1))
                            put("text", words)
                            if (row.opt("color") is Number) put("color", num(row, "color", 0)) else putNull("color")
                            put("annotation_marker", text(row, "annotation_marker"))
                            put("created_at", num(row, "created_at", now()))
                            put("rects", text(row, "rects"))
                        },
                    )
                }
                book.optJSONArray("bookmarks")?.let { pages ->
                    for (i in 0 until pages.length()) {
                        val page = pages.opt(i) as? Number ?: continue
                        db.insertWithOnConflict(
                            "bookmarks",
                            null,
                            ContentValues().apply { put("book_id", id); put("page", page.toInt()) },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                }
                for (row in objects("words")) {
                    val word = text(row, "word") ?: continue
                    db.insertOrThrow(
                        "words",
                        null,
                        ContentValues().apply {
                            put("book_id", id)
                            put("page", num(row, "page", 1))
                            put("word", word)
                            put("meaning", text(row, "meaning"))
                            put("created_at", num(row, "created_at", now()))
                            put("box", num(row, "box", 0))
                            put("due", num(row, "due", 0))
                        },
                    )
                }
                for (row in objects("reading")) {
                    val day = text(row, "day") ?: continue
                    if (runCatching { LocalDate.parse(day) }.isFailure) continue
                    db.insertWithOnConflict(
                        "reading_log",
                        null,
                        ContentValues().apply {
                            put("day", day); put("book_id", id)
                            put("pages", num(row, "pages", 0)); put("seconds", num(row, "seconds", 0))
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        changed()
    }
}

private inline fun <T> Cursor.all(map: Cursor.() -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += map()
    return out
}

private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
private fun Cursor.string(column: String): String? {
    val i = getColumnIndexOrThrow(column)
    return if (isNull(i)) null else getString(i)
}

private fun Cursor.book() = Book(long("id"), string("title")!!, string("file_path")!!, int("last_page"), int("page_count"))

private fun Cursor.highlight(): Highlight {
    val colorIndex = getColumnIndexOrThrow("color")
    return Highlight(
        id = long("id"),
        page = int("page"),
        text = string("text")!!,
        color = if (isNull(colorIndex)) null else getLong(colorIndex).toInt(),
        marker = string("annotation_marker"),
        rects = decodeRects(string("rects")),
    )
}
