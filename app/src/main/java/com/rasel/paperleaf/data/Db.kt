package com.rasel.paperleaf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The same tables as the Flutter app (so backups move between the two), plus
 * what drawing highlights natively needs: their line rectangles, and whether
 * a book's PDF has been searched for marks made elsewhere.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "reader.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              file_path TEXT NOT NULL,
              last_page INTEGER NOT NULL DEFAULT 1,
              page_count INTEGER NOT NULL DEFAULT 0,
              last_opened INTEGER NOT NULL,
              marks_imported INTEGER NOT NULL DEFAULT 0,
              source_uri TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE highlights(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
              page INTEGER NOT NULL,
              text TEXT NOT NULL,
              color INTEGER,
              annotation_marker TEXT,
              created_at INTEGER NOT NULL,
              rects TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE bookmarks(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
              page INTEGER NOT NULL,
              UNIQUE(book_id, page)
            )""",
        )
        db.execSQL(
            """CREATE TABLE words(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
              page INTEGER NOT NULL,
              word TEXT NOT NULL,
              meaning TEXT,
              created_at INTEGER NOT NULL,
              box INTEGER NOT NULL DEFAULT 0,
              due INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL(
            """CREATE TABLE reading_log(
              day TEXT NOT NULL,
              book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
              pages INTEGER NOT NULL DEFAULT 0,
              seconds INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY(day, book_id)
            )""",
        )
        db.execSQL("CREATE INDEX idx_highlights_book ON highlights(book_id, page)")
        db.execSQL("CREATE INDEX idx_words_book ON words(book_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: where the PDF came from, so highlights can be written back into that file.
        if (oldVersion < 2) db.execSQL("ALTER TABLE books ADD COLUMN source_uri TEXT")
    }
}
