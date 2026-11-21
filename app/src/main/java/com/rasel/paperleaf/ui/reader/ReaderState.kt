package com.rasel.paperleaf.ui.reader

import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.rasel.paperleaf.App
import com.rasel.paperleaf.core.Dictionary
import com.rasel.paperleaf.core.replacementForHighlightColor
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.data.Highlight
import com.rasel.paperleaf.pdf.PageText
import com.rasel.paperleaf.pdf.PdfDoc
import com.rasel.paperleaf.pdf.TocEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A page turn under way: from page [from] to [to] (1-based). */
data class Turn(val from: Int, val to: Int, val forward: Boolean)

/** Selected text on one page: characters [anchor]..[focus] (either order). */
data class Selection(val page: Int, val anchor: Int, val focus: Int) {
    val range: IntRange get() = min(anchor, focus)..max(anchor, focus)
}

/** The meaning of the one word that is selected. */
data class Lookup(
    val word: String,
    val meaning: String?,
    val loading: Boolean,
    /** Why there is no meaning (not in the dictionary, or no connection). */
    val missing: String? = null,
)

data class Match(val page: Int, val rects: List<RectF>)

/** Everything the reader screen shows and does, apart from drawing and touch handling. */
class ReaderState(val book: Book, startPage: Int?, val doc: PdfDoc, private val scope: CoroutineScope) {
    val pageCount = doc.pageCount
    val images = PageImages(doc, scope)

    /** The page on screen, 1-based. */
    var page by mutableIntStateOf((startPage ?: book.lastPage).coerceIn(1, max(1, pageCount)))
        private set

    // Page turn
    var turn by mutableStateOf<Turn?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var pivotY by mutableFloatStateOf(0.8f)
    var tilt by mutableFloatStateOf(-0.25f)

    /** Sideways push of the page when there is no page to turn to (px). */
    var edgePush by mutableFloatStateOf(0f)
        private set
    private var settling = false
    private var queued = 0

    // Zoom (current page only)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /**
     * How much more two pages may zoom than one: each of two pages is drawn
     * smaller than a single page (phone upright), so the same text size at
     * full zoom needs a larger zoom. 1 for a single page.
     */
    var zoomBoost = 1f

    /** The largest zoom: the same text size for one page and for two. */
    val maxZoom: Float get() = 3f * zoomBoost

    // Notes of this book
    var highlights by mutableStateOf<List<Highlight>>(emptyList())
        private set
    var bookmarks by mutableStateOf<Set<Int>>(emptySet())
        private set
    var toc by mutableStateOf<List<TocEntry>>(emptyList())
        private set

    // Selection
    var selection by mutableStateOf<Selection?>(null)
    var lookup by mutableStateOf<Lookup?>(null)
        private set
    private val meanings = HashMap<String, String>()
    private var lookupJob: Job? = null
    private val texts = HashMap<Int, PageText>()

    // Search
    var searching by mutableStateOf(false)
    var matches by mutableStateOf<List<Match>>(emptyList())
        private set
    var matchIndex by mutableIntStateOf(-1)
        private set
    var searchDone by mutableStateOf(true)
        private set
    private var searchJob: Job? = null

    // Progress and reading log
    private var savedPage = book.lastPage
    private var saveJob: Job? = null
    private var lastActivity = System.currentTimeMillis()
    private var pendingPages = 0
    private var pendingSeconds = 0

    init {
        images.focus(page - 1)
        loadText(page)
        scope.launch {
            if (book.pageCount != pageCount) App.repo.savePageCount(book.id, pageCount)
            toc = withContext(Dispatchers.IO) { doc.outline() }
            importMarks()
            // From here on, changes to the highlights are saved into the book's file;
            // also those not saved last time (the app stopped first, say). A book
            // never saved into holds what its file had.
            val rows = App.repo.highlights(book.id)
            fileMarks = App.library.savedMarks(book.id) ?: marksKey(rows).also { App.library.setSavedMarks(book.id, it) }
            saveMarksToFile(rows)
        }
        if (startPage != null) scheduleSave()
    }

    fun reloadNotes() {
        scope.launch {
            val rows = App.repo.highlights(book.id)
            // Highlights in colors the app no longer offers take the new ones
            // (saving them changes the data, and this runs again).
            val retired = rows.mapNotNull { h -> h.color?.let { replacementForHighlightColor(it) }?.let { h.id to it } }
            if (retired.isNotEmpty()) {
                for ((id, color) in retired) App.repo.setHighlightColor(id, color)
                return@launch
            }
            highlights = rows
            bookmarks = App.repo.bookmarkPages(book.id)
            saveMarksToFile(rows)
        }
    }

    // Highlights saved into the file the book came from (so they outlive the app)

    /** The highlights the book's file holds; null until those in it were read in. */
    private var fileMarks: String? = null
    private var fileJob: Job? = null

    private fun marksKey(rows: List<Highlight>) = rows.joinToString("|") { "${it.id}:${it.color}:${it.rects}" }

    /**
     * The book's file belongs to another app (a download, say): saving into it
     * needs "All files access". The reader asks once per run of the app.
     */
    var askFileAccess by mutableStateOf(false)
        private set
    private var unsaved: List<Highlight>? = null

    fun dismissFileAccess() {
        askFileAccess = false
        App.fileAccessAsked = true
    }

    /** A moment after the highlights stop changing, they go into the file (not on every tap). */
    private fun saveMarksToFile(rows: List<Highlight>, now: Boolean = false) {
        // Off: the file is left as it is (turned on later, it catches up when the book opens).
        if (book.sourceUri == null || !App.prefs.highlightsInPdf.value) return
        val known = fileMarks ?: return
        val key = marksKey(rows)
        if (key == known) return
        fileJob?.cancel()
        // App-wide: still saved when the reader is closed right after a change.
        fileJob = App.background.launch {
            if (!now) delay(1500)
            when (App.library.saveHighlightsToSource(book, doc, rows)) {
                com.rasel.paperleaf.core.Library.SaveResult.Saved -> {
                    fileMarks = key
                    App.library.setSavedMarks(book.id, key)
                    unsaved = null
                }
                com.rasel.paperleaf.core.Library.SaveResult.NeedsFileAccess -> {
                    unsaved = rows
                    if (!App.fileAccessAsked) withContext(Dispatchers.Main) { askFileAccess = true }
                }
                com.rasel.paperleaf.core.Library.SaveResult.Failed -> Unit
            }
        }
    }

    /** The text of [page], or null until it is read (then the screen draws again). */
    fun textOf(page: Int): PageText? = texts[page]

    /** The text of [page], read from the file if needed (also kept for drawing). */
    suspend fun pageText(page: Int): PageText {
        texts[page]?.let { return it }
        val text = withContext(Dispatchers.IO) { doc.text(page - 1) }
        texts[page] = text
        return text
    }

    private fun loadText(page: Int, then: ((PageText) -> Unit)? = null) {
        if (page !in 1..pageCount) return
        texts[page]?.let { then?.invoke(it); return }
        scope.launch {
            val text = withContext(Dispatchers.IO) { doc.text(page - 1) }
            texts[page] = text
            if (texts.size > 8) texts.keys.filter { abs(it - this@ReaderState.page) > 3 }.forEach { texts.remove(it) }
            then?.invoke(text)
        }
    }

    // Two pages side by side (the phone held sideways). [page] is then the
    // left page: always an odd page, so the pairs stay the same (1-2, 3-4…).

    var spread by mutableStateOf(false)
        private set

    val step: Int get() = if (spread) 2 else 1

    private fun spreadStart(p: Int) = if (spread) p - (p - 1) % 2 else p

    /** The pages on screen. */
    val visiblePages: IntRange
        get() = if (spread) page..minOf(page + 1, pageCount) else page..page

    fun setSpreadMode(on: Boolean) {
        if (on == spread || turn != null) return
        spread = on
        zoom = 1f
        pan = Offset.Zero
        val start = spreadStart(page)
        if (start != page) {
            // The same place, only shown as a pair: not a page turn.
            page = start
            scheduleSave()
        }
        images.focus(page - 1)
        loadText(page)
        loadText(page + 1)
    }

    // Moving between pages

    private fun shown(newPage: Int) {
        if (newPage == page) return
        val from = page
        page = newPage
        zoom = 1f
        pan = Offset.Zero
        selection = null
        lookup = null
        trackTurn(from, newPage)
        images.focus(newPage - 1)
        loadText(newPage)
        loadText(newPage + 1)
        scheduleSave()
    }

    /** A jump that is not a turn (contents, go to page, notes, search). */
    fun goTo(target: Int) {
        if (pageCount == 0) return
        shown(spreadStart(target.coerceIn(1, pageCount)))
    }

    val busy: Boolean get() = turn != null && settling

    /** A drag sideways began. Returns false when nothing will turn (no page there: the page is pushed instead). */
    fun startDrag(forward: Boolean, grabY: Float): Boolean {
        if (turn != null) return false
        val to = page + if (forward) step else -step
        if (to !in 1..pageCount) {
            edgePush = 0f
            return false
        }
        pivotY = grabY.coerceIn(0f, 1f)
        tilt = 0f
        images[to - 1]
        images[to]
        turn = Turn(page, to, forward)
        progress = 0f
        return true
    }

    fun drag(value: Float, dy: Float, width: Float, height: Float, grabY: Float) {
        if (turn == null || settling) return
        progress = value.coerceIn(0f, 1f)
        if (height > 0) {
            pivotY = (grabY + dy / height / 2).coerceIn(0f, 1f)
            tilt = (dy / (width * 0.6f)).coerceIn(-0.45f, 0.45f)
        }
    }

    /** Zoomed in at the page's edge: how far the page is pulled aside to turn (px, minus = toward the next). */
    var zoomPull by mutableFloatStateOf(0f)

    /** Pulled far enough that letting go turns the page. */
    fun zoomArmed(width: Float): Boolean {
        if (abs(zoomPull) < width * 0.1f) return false
        val to = page + if (zoomPull < 0) step else -step
        return to in 1..pageCount
    }

    fun releaseZoomPull() {
        val from = zoomPull
        if (from == 0f) return
        scope.launch {
            animate(from, 0f, animationSpec = tween(200)) { v, _ -> zoomPull = v }
        }
    }

    /**
     * Turns while zoomed: the zoom stays, and the new page shows where reading
     * starts (top left) going forward, or where it ends (bottom right) going back.
     */
    fun turnZoomed(forward: Boolean, width: Float, height: Float) {
        if (turn != null) return
        val to = page + if (forward) step else -step
        if (to !in 1..pageCount) return
        val z = zoom
        shown(to)
        zoom = z
        val maxX = (z - 1) * width / 2
        val maxY = (z - 1) * height / 2
        pan = if (forward) Offset(maxX, maxY) else Offset(-maxX, -maxY)
        images.sharpen((visiblePages.first - 1)..(visiblePages.last - 1), z)
    }

    fun pushEdge(px: Float) {
        edgePush = px.coerceIn(-36f, 36f)
    }

    fun releaseEdge() {
        scope.launch {
            animate(edgePush, 0f, animationSpec = tween(220)) { v, _ -> edgePush = v }
        }
    }

    /** The finger left: finish the turn or put the page back. */
    fun release(velocity: Float) {
        val current = turn ?: return
        val inDirection = if (current.forward) -velocity else velocity
        val complete = inDirection > -500 && (progress > 0.3f || inDirection > 500)
        settle(complete)
    }

    private fun settle(complete: Boolean) {
        val current = turn ?: return
        settling = true
        scope.launch {
            val remaining = if (complete) 1 - progress else progress
            val ms = (320 * remaining).roundToInt().coerceIn(120, 320)
            animate(progress, if (complete) 1f else 0f, animationSpec = tween(ms, easing = FastOutSlowInEasing)) { v, _ ->
                progress = v
            }
            if (complete) shown(current.to)
            turn = null
            progress = 0f
            settling = false
            val next = queued
            queued = 0
            if (next != 0) turnPage(next > 0)
        }
    }

    /** A turn without a drag (side taps). */
    fun turnPage(forward: Boolean) {
        if (turn != null) {
            if (settling) queued = if (forward) 1 else -1
            return
        }
        val to = page + if (forward) step else -step
        if (to !in 1..pageCount) {
            scope.launch {
                animate(0f, if (forward) -16f else 16f, animationSpec = tween(100)) { v, _ -> edgePush = v }
                animate(edgePush, 0f, animationSpec = tween(220)) { v, _ -> edgePush = v }
            }
            return
        }
        zoom = 1f
        pan = Offset.Zero
        images[to - 1]
        turn = Turn(page, to, forward)
        pivotY = 0.8f
        tilt = -0.25f
        progress = 0f
        settle(true)
    }

    // Selection

    /** Selects the word at [x],[y] (page points) on [target], a page on screen. */
    fun selectWordAt(target: Int, x: Float, y: Float) {
        val shownFrom = page
        loadText(target) { text ->
            if (shownFrom != page) return@loadText
            val i = text.charAt(x, y)
            if (i < 0) return@loadText
            val word = text.wordAt(i)
            if (word.isEmpty()) return@loadText
            selection = Selection(target, word.first, word.last)
            lookUp()
        }
    }

    /** Moves the [moving] end of the selection to the character at [x],[y]. */
    fun dragSelection(x: Float, y: Float, movingFocus: Boolean) {
        val s = selection ?: return
        val text = texts[s.page] ?: return
        val i = text.charAt(x, y, slop = 30f)
        if (i < 0) return
        selection = if (movingFocus) s.copy(focus = i) else s.copy(anchor = i)
    }

    fun selectedText(): String? {
        val s = selection ?: return null
        return texts[s.page]?.textOf(s.range)?.takeIf { it.isNotBlank() }
    }

    fun selectedRects(): List<RectF> {
        val s = selection ?: return emptyList()
        return texts[s.page]?.rectsOf(s.range) ?: emptyList()
    }

    fun clearSelection() {
        selection = null
        lookup = null
        lookupJob?.cancel()
    }

    /** A single selected word gets its meaning shown by itself. */
    fun lookUp() {
        val word = selectedText()?.let { Dictionary.lookupWord(it) }
        if (word == null) {
            lookupJob?.cancel()
            lookup = null
            return
        }
        if (lookup?.word == word) return
        lookupJob?.cancel()
        val known = meanings[word.lowercase()]
        if (known != null) {
            lookup = Lookup(word, known, false)
            return
        }
        lookup = Lookup(word, null, true)
        // The selection changes while its handles are dragged: ask once it rests.
        lookupJob = scope.launch {
            delay(350)
            val result = Dictionary.define(word)
            val found = (result as? Dictionary.Result.Found)?.meaning
            if (found != null) meanings[word.lowercase()] = found
            if (lookup?.word == word) {
                lookup = Lookup(word, found, false, if (found == null) Dictionary.missingMessage(word, result) else null)
            }
        }
    }

    suspend fun highlightSelection(color: Int): Boolean {
        val s = selection ?: return false
        val text = selectedText() ?: return false
        val rects = selectedRects()
        if (rects.isEmpty()) return false
        App.repo.addHighlight(book.id, s.page, text, color, "Paperleaf Reader highlight ${book.id}-${System.nanoTime()}", rects)
        clearSelection()
        return true
    }

    suspend fun saveSelectedWord(): Boolean {
        val selected = selectedText() ?: return false
        val single = Dictionary.lookupWord(selected)
        val word = single ?: selected
        val meaning = single?.let { meanings[it.lowercase()] } ?: lookup?.takeIf { it.word == single }?.meaning
        val page = selection?.page ?: page
        clearSelection()
        val id = App.repo.addWord(book.id, page, word, meaning)
        if (meaning == null && single != null) {
            Dictionary.lookup(single)?.let { App.repo.setWordMeaning(id, it) }
        }
        return true
    }

    // Bookmarks

    fun toggleBookmark() {
        val on = page !in bookmarks
        bookmarks = if (on) bookmarks + page else bookmarks - page
        scope.launch { App.repo.setBookmark(book.id, page, on) }
    }

    // Search

    fun search(query: String) {
        searchJob?.cancel()
        matches = emptyList()
        matchIndex = -1
        val q = query.trim()
        if (q.isEmpty()) return
        searchDone = false
        searchJob = scope.launch {
            val found = mutableListOf<Match>()
            for (i in 0 until pageCount) {
                val text = withContext(Dispatchers.IO) { doc.text(i) }
                for (range in text.find(q)) found += Match(i + 1, text.rectsOf(range))
                if (i % 10 == 9 || i == pageCount - 1) {
                    matches = found.toList()
                    // Start at the first match from the page on screen on.
                    if (matchIndex < 0 && found.any { it.page >= page }) {
                        matchIndex = found.indexOfFirst { it.page >= page }
                        goTo(found[matchIndex].page)
                    }
                }
            }
            if (matchIndex < 0 && found.isNotEmpty()) {
                matchIndex = 0
                goTo(found[0].page)
            }
            searchDone = true
        }
    }

    fun nextMatch(step: Int) {
        if (matches.isEmpty()) return
        matchIndex = Math.floorMod(matchIndex + step, matches.size)
        goTo(matches[matchIndex].page)
    }

    fun closeSearch() {
        searchJob?.cancel()
        searching = false
        matches = emptyList()
        matchIndex = -1
        searchDone = true
    }

    // Progress and the reading log. A turn 1-3 pages ahead counts as pages
    // read; bigger jumps are navigation. Time counts up to two minutes
    // between turns, so a book left open does not add hours.

    private fun trackTurn(from: Int, to: Int) {
        val now = System.currentTimeMillis()
        pendingSeconds += ((now - lastActivity) / 1000).toInt().coerceIn(0, 120)
        lastActivity = now
        if (to > from && to - from <= 3) pendingPages += to - from
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(700)
            flush()
        }
    }

    /** Writes the page and the reading log now (leaving, or the app going to the background). */
    suspend fun flush() = withContext(NonCancellable) {
        val now = System.currentTimeMillis()
        pendingSeconds += ((now - lastActivity) / 1000).toInt().coerceIn(0, 120)
        lastActivity = now
        val pages = pendingPages
        val seconds = pendingSeconds
        pendingPages = 0
        pendingSeconds = 0
        runCatching { App.repo.logReading(book.id, pages, seconds) }
        if (page != savedPage) {
            savedPage = page
            runCatching { App.repo.saveProgress(book.id, page) }
        }
    }

    /** Coming back to the app: time away is not reading time. */
    fun resumed() {
        lastActivity = System.currentTimeMillis()
        // Back from the settings screen with "All files access" on: save what is waiting.
        unsaved?.let { rows ->
            if (App.library.hasFileAccess()) {
                askFileAccess = false
                saveMarksToFile(rows, now = true)
            }
        }
    }

    // Highlights made elsewhere

    /**
     * Once per book: highlights inside the PDF (from another app, or from a
     * Flutter-app backup) get their place on the page, and those Notes does
     * not know get an entry, with the words under them.
     */
    private suspend fun importMarks() {
        runCatching {
            if (App.repo.marksImported(book.id)) return
            val marks = withContext(Dispatchers.IO) { doc.marks() }
            val rows = App.repo.highlights(book.id)
            val placed = HashSet<Long>()
            for (mark in marks) {
                val row = rows.firstOrNull { it.id !in placed && it.rects.isEmpty() && it.marker != null && it.marker == mark.subject }
                    ?: run {
                        val words = withContext(Dispatchers.IO) { wordsUnder(doc.text(mark.page - 1), mark.rects) }
                        rows.firstOrNull { it.id !in placed && it.rects.isEmpty() && it.page == mark.page && same(it.text, words) }
                    }
                if (row != null) {
                    placed += row.id
                    App.repo.setHighlightRects(row.id, mark.rects)
                    continue
                }
                if (rows.any { it.page == mark.page && it.rects.isNotEmpty() && overlaps(it.rects, mark.rects) }) continue
                val words = withContext(Dispatchers.IO) { wordsUnder(doc.text(mark.page - 1), mark.rects) }
                App.repo.addHighlight(
                    book.id, mark.page, words.ifBlank { "Highlight on page ${mark.page}" }, mark.color,
                    mark.subject ?: "Paperleaf Reader highlight ${book.id}-${System.nanoTime()}", mark.rects,
                )
            }
            App.repo.setMarksImported(book.id)
        }
    }

    private fun same(a: String, b: String) = a.filterNot { it.isWhitespace() } == b.filterNot { it.isWhitespace() }

    private fun overlaps(a: List<RectF>, b: List<RectF>) = a.any { r -> b.any { RectF.intersects(r, it) } }

    private fun wordsUnder(text: PageText, rects: List<RectF>): String {
        val sb = StringBuilder()
        for (i in 0 until text.length) {
            val box = text.box(i)
            if (box == null) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                continue
            }
            if (rects.any { it.contains(box.centerX(), box.centerY()) }) sb.append(text.text[i])
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }
}
