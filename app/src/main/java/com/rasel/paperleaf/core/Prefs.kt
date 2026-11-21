package com.rasel.paperleaf.core

import android.content.Context
import android.graphics.ColorMatrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Page background of the reader (old-book looks). */
enum class PageTint(val label: String, val swatch: Int) {
    White("White", 0xFFFFFFFF.toInt()),
    Cream("Cream", 0xFFF8F1DC.toInt()),
    Sepia("Sepia", 0xFFF1E2C2.toInt()),
    OldBook("Old book", 0xFFE6CF9C.toInt()),
    Sage("Sage", 0xFFDCE8D5.toInt()),
    Night("Night", 0xFF1E1B18.toInt());

    val isDark: Boolean get() = this == Night

    /** Text color on [swatch] (previews, the page number line). */
    val textColor: Int get() = if (isDark) 0xFFE1DACD.toInt() else 0xFF1A1A1A.toInt()

    /** An empty page after the tint: white run through [matrix]. */
    val blank: Int get() = if (isDark) 0xFF010000.toInt() else swatch

    /**
     * Light tints multiply the page: white paper takes the tint, black text
     * stays black. Night inverts it to light text on dark.
     */
    fun matrix(): ColorMatrix = if (isDark) {
        ColorMatrix(
            floatArrayOf(
                -0.88f, 0f, 0f, 0f, 225f,
                0f, -0.88f, 0f, 0f, 218f,
                0f, 0f, -0.88f, 0f, 205f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    } else {
        ColorMatrix().apply {
            setScale(
                ((swatch shr 16) and 0xFF) / 255f,
                ((swatch shr 8) and 0xFF) / 255f,
                (swatch and 0xFF) / 255f,
                1f,
            )
        }
    }
}

enum class PageTurn(val label: String) { Flip("Book flip"), Slide("Slide") }

/** How the reader is turned: as the phone's auto-rotate says, or held one way (the rest of the app follows the phone). */
enum class ReaderOrientation { Phone, Upright, Sideways }

enum class LibrarySort(val label: String) { Recent("Recently read"), Title("Title A–Z"), Progress("Most read") }

/** The reader preferences, saved on the device. Apply to every book. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("paperleaf", Context.MODE_PRIVATE)

    private val _tint = MutableStateFlow(enumOr(prefs.getString("page_tint", null), PageTint.Sepia))
    val tint: StateFlow<PageTint> = _tint

    private val _turn = MutableStateFlow(enumOr(prefs.getString("page_turn", null), PageTurn.Flip))
    val turn: StateFlow<PageTurn> = _turn

    private val _keepAwake = MutableStateFlow(prefs.getBoolean("keep_awake", true))
    val keepAwake: StateFlow<Boolean> = _keepAwake

    private val _orientation = MutableStateFlow(enumOr(prefs.getString("reader_orientation", null), ReaderOrientation.Phone))

    /** How the reader is turned: as the phone's auto-rotate says, or held one way. */
    val orientation: StateFlow<ReaderOrientation> = _orientation

    fun setOrientation(value: ReaderOrientation) {
        _orientation.value = value
        prefs.edit().putString("reader_orientation", value.name).apply()
    }

    private val _edges = MutableStateFlow(prefs.getBoolean("book_edges", true))

    /** The stacked page edges beside the page, like the side of a real book. */
    val bookEdges: StateFlow<Boolean> = _edges

    fun setBookEdges(value: Boolean) {
        _edges.value = value
        prefs.edit().putBoolean("book_edges", value).apply()
    }

    private val _sort = MutableStateFlow(enumOr(prefs.getString("library_sort", null), LibrarySort.Recent))
    val sort: StateFlow<LibrarySort> = _sort

    private val _openLast = MutableStateFlow(prefs.getBoolean("open_last_book", true))

    /** Start in the book that was open when the app was left. */
    val openLastBook: StateFlow<Boolean> = _openLast

    fun setOpenLastBook(value: Boolean) {
        _openLast.value = value
        prefs.edit().putBoolean("open_last_book", value).apply()
    }

    private val _inPdf = MutableStateFlow(prefs.getBoolean("highlights_in_pdf", true))

    /**
     * Highlights are also saved into the book's own PDF file (they then outlive
     * the app, and other reader apps show them). Off: only inside the app.
     */
    val highlightsInPdf: StateFlow<Boolean> = _inPdf

    fun setHighlightsInPdf(value: Boolean) {
        _inPdf.value = value
        prefs.edit().putBoolean("highlights_in_pdf", value).apply()
    }

    /** The book that was open when the app was left; -1 when the reader went back to the shelf. */
    var lastBookId: Long
        get() = prefs.getLong("last_book", -1)
        set(value) = prefs.edit().putLong("last_book", value).apply()

    fun setTint(value: PageTint) {
        _tint.value = value
        prefs.edit().putString("page_tint", value.name).apply()
    }

    fun setTurn(value: PageTurn) {
        _turn.value = value
        prefs.edit().putString("page_turn", value.name).apply()
    }

    fun setKeepAwake(value: Boolean) {
        _keepAwake.value = value
        prefs.edit().putBoolean("keep_awake", value).apply()
    }

    fun setSort(value: LibrarySort) {
        _sort.value = value
        prefs.edit().putString("library_sort", value.name).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
