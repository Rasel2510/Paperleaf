package com.rasel.paperleaf.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rasel.paperleaf.App
import com.rasel.paperleaf.LocalToast
import com.rasel.paperleaf.core.PageTurn
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.pdf.PdfDoc
import com.rasel.paperleaf.pdf.TocEntry
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.Poster
import com.rasel.paperleaf.ui.RetroBox
import com.rasel.paperleaf.ui.RetroButton
import com.rasel.paperleaf.ui.notes.NotesScreen
import com.rasel.paperleaf.ui.settings.ReadingSettingsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Highlight colors; the first is the default. Strong, so they show on tinted paper. */
val HighlightColors = listOf(0xFFFF9100.toInt(), 0xFF00C853.toInt(), 0xFFF50057.toInt(), 0xFF8B2CFF.toInt())

@Composable
fun ReaderScreen(book: Book, startPage: Int?, onClose: () -> Unit) {
    val toast = LocalToast.current
    val doc = remember(book.id) { runCatching { PdfDoc.open(File(book.filePath)) }.getOrNull() }
    if (doc == null || doc.pageCount == 0) {
        LaunchedEffect(Unit) {
            toast("Could not open this PDF.")
            onClose()
        }
        return
    }
    val scope = rememberCoroutineScope()
    val state = remember(book.id) { ReaderState(book, startPage, doc, scope) }
    // This book reopens by itself next time, unless the reader goes back to the shelf.
    LaunchedEffect(book.id) { App.prefs.lastBookId = book.id }
    Reader(state) {
        App.prefs.lastBookId = -1
        onClose()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Reader(state: ReaderState, onClose: () -> Unit) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tint by App.prefs.tint.collectAsState()
    val turnStyle by App.prefs.turn.collectAsState()
    val keepAwake by App.prefs.keepAwake.collectAsState()
    val changes by App.repo.changes.collectAsState()

    var chrome by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var contents by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(HighlightColors.first()) }

    LaunchedEffect(changes) { state.reloadNotes() }

    // Read aloud: the voice reads the page, then the page turns and it goes on.
    // With two pages on screen it reads the left one, then the right one, then turns.
    val aloudHolder = remember { arrayOfNulls<ReadAloud>(1) }
    val aloudPageState = remember { mutableIntStateOf(0) }
    var aloudPage by aloudPageState
    fun readAloud(page: Int = state.page) {
        aloudPageState.intValue = page
        scope.launch { aloudHolder[0]?.readPage(page, state.pageText(page)) }
    }
    val aloud = remember(state) {
        ReadAloud(context, scope) {
            val next = aloudPageState.intValue + 1
            when {
                next in state.visiblePages -> readAloud(next)
                state.visiblePages.last < state.pageCount -> state.turnPage(true)
                else -> aloudHolder[0]?.stop()
            }
        }.also { aloudHolder[0] = it }
    }
    DisposableEffect(aloud) { onDispose { aloud.shutdown() } }
    LaunchedEffect(state.page, aloud.status) {
        val going = aloud.status == ReadAloud.Status.Reading || aloud.status == ReadAloud.Status.Starting
        // New pages while reading (turned by the voice or by hand): read them from the top.
        if (going && aloudPage !in state.visiblePages) readAloud()
    }
    LaunchedEffect(aloud.problem) {
        aloud.problem?.let {
            toast(it)
            aloud.problem = null
        }
    }

    // Close the file and save the place on the way out.
    DisposableEffect(state) {
        onDispose {

            kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob()).launch {
                state.flush()
                withContext(Dispatchers.IO) { state.doc.close() }
            }
        }
    }

    // The OS may stop the app in the background: write what is pending.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> scope.launch { state.flush() }
                Lifecycle.Event.ON_RESUME -> state.resumed()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val stayAwake = keepAwake || aloud.reading
    DisposableEffect(stayAwake) {
        val window = (context as? Activity)?.window
        if (stayAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val saveAnnotated = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val rows = App.repo.highlights(state.book.id)
                        context.contentResolver.openOutputStream(uri)?.use { state.doc.writeAnnotated(rows, it) }
                    }.isSuccess
                }
                toast(if (ok) "Annotated PDF saved. Open it in any PDF reader." else "Could not save the annotated PDF.")
            }
        }
    }

    BackHandler {
        when {
            notes -> notes = false
            state.searching -> state.closeSearch()
            state.selection != null -> state.clearSelection()
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(tint.blank))) {
        PageView(
            state = state,
            tint = tint,
            curl = turnStyle == PageTurn.Flip,
            onTapMiddle = { chrome = !chrome },
            spoken = aloud.current,
            spreadInsets = with(LocalDensity.current) {
                (WindowInsets.statusBars.getTop(this) + 6.dp.toPx()) to
                    (WindowInsets.navigationBars.getBottom(this) + 26.dp.toPx())
            },
        )

        // Top: the bar, or the search field.
        AnimatedVisibility(
            visible = chrome || state.searching,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().background(p.paper).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.searching) {
                        SearchBar(state, query, { query = it })
                    } else {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = p.ink) }
                        Spacer(Modifier.weight(1f))
                        val marked = state.page in state.bookmarks
                        IconButton(onClick = { state.toggleBookmark() }) {
                            Icon(
                                if (marked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                "Bookmark page",
                                tint = if (marked) Poster.terracotta else p.ink,
                            )
                        }
                        IconButton(onClick = { if (aloud.reading) aloud.stop() else readAloud() }) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                "Read aloud",
                                tint = if (aloud.reading) Poster.teal else p.ink,
                            )
                        }
                        IconButton(onClick = { state.searching = true; chrome = true }) { Icon(Icons.Default.Search, "Search in book", tint = p.ink) }
                        IconButton(onClick = {
                            if (state.zoom > 1.05f) {
                                state.zoom = 1f
                                state.pan = Offset.Zero
                            } else {
                                state.zoom = 2f * state.zoomBoost
                                state.images.sharpen((state.visiblePages.first - 1)..(state.visiblePages.last - 1), state.zoom)
                            }
                        }) {
                            Icon(if (state.zoom > 1.05f) Icons.Default.ZoomOut else Icons.Default.ZoomIn, "Zoom", tint = p.ink)
                        }
                        IconButton(onClick = { notes = true }) { Icon(Icons.AutoMirrored.Filled.ListAlt, "Highlights, bookmarks, words", tint = p.ink) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = p.ink) }
                            DropdownMenu(menu, { menu = false }, containerColor = p.card) {
                                @Composable
                                fun item(label: String, action: () -> Unit) = DropdownMenuItem(
                                    text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { menu = false; action() },
                                )
                                item("Contents") {
                                    if (state.toc.isEmpty()) toast("This PDF has no table of contents.") else contents = true
                                }
                                item("Go to page") { if (state.pageCount > 1) goTo = true }
                                item("Save annotated PDF") {
                                    val name = state.book.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
                                    saveAnnotated.launch("$name.pdf")
                                }
                                item("Reading settings") { settings = true }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
            }
        }

        // Bottom: the selection bar, or the page number line.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (state.selection != null) {
                SelectionBar(
                    state = state,
                    color = color,
                    onColor = { color = it },
                    onHighlight = {
                        scope.launch {
                            if (runCatching { state.highlightSelection(color) }.getOrDefault(false)) toast("Highlight saved")
                        }
                    },
                    onSave = {
                        toast("Word saved")
                        scope.launch { runCatching { state.saveSelectedWord() }.onFailure { toast("Could not save the word.") } }
                    },
                    onSpeak = { state.selectedText()?.let { aloud.say(it) } },
                    onCopy = {
                        state.selectedText()?.let { text ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Paperleaf", text))
                            toast("Copied")
                        }
                        state.clearSelection()
                    },
                )
            } else {
                val chapter = chapterFor(state.toc, state.page)
                val textColor = Color(tint.textColor).copy(alpha = 0.72f)
                val glow = Color(tint.swatch)
                val style = TextStyle(
                    fontFamily = Fonts.body,
                    fontSize = 12.sp,
                    color = textColor,
                    shadow = Shadow(glow, Offset.Zero, 6f),
                )
                if (aloud.reading) {
                    AloudBar(
                        aloud = aloud,
                        onPlay = { if (aloudPage !in state.visiblePages) readAloud() else aloud.resume() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 30.dp),
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                ) {
                    val pages = state.visiblePages
                    Text(
                        if (pages.first == pages.last) "PAGE ${state.page} / ${state.pageCount}"
                        else "PAGES ${pages.first}–${pages.last} / ${state.pageCount}",
                        style = style,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        chapter ?: "Chapter unavailable",
                        Modifier.weight(1f),
                        style = style.copy(textAlign = TextAlign.End),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (notes) {
            NotesScreen(
                book = state.book,
                onBack = { notes = false },
                onOpenPage = { page ->
                    notes = false
                    state.goTo(page)
                },
            )
        }
    }

    if (settings) ReadingSettingsSheet(onDismiss = { settings = false })
    if (contents) ContentsSheet(state.toc, onDismiss = { contents = false }) { entry ->
        contents = false
        val page = entry.page
        if (page == null) toast("Could not open that section.") else state.goTo(page)
    }
    if (goTo) GoToPageSheet(state.page, state.pageCount, onDismiss = { goTo = false }) { page ->
        goTo = false
        state.goTo(page)
    }
}

/** While reading aloud: pause or go on, the speed, stop. */
@Composable
private fun AloudBar(aloud: ReadAloud, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    RetroBox(modifier, color = p.card, radius = 28.dp, shadow = 3.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val paused = aloud.status == ReadAloud.Status.Paused
            IconButton(onClick = { if (paused) onPlay() else aloud.pause() }) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Go on reading" else "Pause", tint = p.ink)
            }
            Text(
                "${aloud.rate.toString().removeSuffix(".0")}×",
                Modifier
                    .clickable { aloud.nextRate() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = { aloud.stop() }) { Icon(Icons.Default.Stop, "Stop reading", tint = p.ink) }
        }
    }
}

/** The chapter the page is in: the last contents entry at or before it (the deepest on a tie). */
fun chapterFor(toc: List<TocEntry>, page: Int): String? {
    var best: TocEntry? = null
    for (e in toc) {
        val at = e.page ?: continue
        if (at > page) continue
        val bestAt = best?.page ?: 0
        if (at > bestAt || (at == bestAt && e.depth > (best?.depth ?: -1))) best = e
    }
    return best?.title
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SearchBar(state: ReaderState, query: String, onQuery: (String) -> Unit) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    IconButton(onClick = { state.closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search", tint = p.ink) }
    Box(Modifier.weight(1f)) {
        if (query.isEmpty()) Text("Search in this book", style = MaterialTheme.typography.bodyLarge.copy(color = p.ink.copy(alpha = 0.5f)))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            cursorBrush = SolidColor(p.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { state.search(query) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    val count = when {
        !state.searchDone && state.matches.isEmpty() -> "…"
        state.matches.isEmpty() && query.isNotBlank() && state.searchDone && state.matchIndex < 0 -> ""
        state.matches.isEmpty() -> ""
        else -> "${state.matchIndex + 1}/${state.matches.size}${if (state.searchDone) "" else "…"}"
    }
    Text(count, style = MaterialTheme.typography.bodyMedium)
    IconButton(onClick = { state.nextMatch(-1) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous match", tint = p.ink) }
    IconButton(onClick = { state.nextMatch(1) }) { Icon(Icons.Default.KeyboardArrowDown, "Next match", tint = p.ink) }
}

/** Shown while text is selected: the word's meaning, colors, highlight, save, copy. */
@Composable
private fun SelectionBar(
    state: ReaderState,
    color: Int,
    onColor: (Int) -> Unit,
    onHighlight: () -> Unit,
    onSave: () -> Unit,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.paper)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 14.dp)) {
            state.lookup?.let { lookup ->
                Column(Modifier.padding(top = 6.dp, end = 12.dp, bottom = 6.dp)) {
                    Text(lookup.word, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            lookup.loading -> "Looking up…"
                            lookup.meaning != null -> lookup.meaning
                            else -> "No meaning found. Check your internet connection."
                        },
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (lookup.meaning == null) p.ink.copy(alpha = 0.6f) else p.ink,
                        ),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (c in HighlightColors) {
                    Box(
                        Modifier
                            .padding(end = 12.dp)
                            .size(30.dp)
                            .background(Color(c), CircleShape)
                            .border(if (c == color) 3.5.dp else 1.5.dp, p.ink, CircleShape)
                            .clickable { onColor(c) },
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Read aloud the marked text", tint = p.ink) }
                IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(22.dp), tint = p.ink) }
                IconButton(onClick = { state.clearSelection() }) { Icon(Icons.Default.Close, "Close", tint = p.ink) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(end = 12.dp)) {
                RetroButton("Highlight", onHighlight, Modifier.weight(1f), Icons.Default.Highlight, Color(color), Poster.onLight)
                Spacer(Modifier.width(12.dp))
                RetroButton("Save", onSave, Modifier.weight(1f), Icons.Default.Star, Poster.teal, Poster.onDark)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(entries: List<TocEntry>, onDismiss: () -> Unit, onPick: (TocEntry) -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        // Opens all the way: nothing is hidden below the bottom edge.
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text("Contents", Modifier.padding(start = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(entries) { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(e) }
                        .padding(start = (24 + 18 * e.depth.coerceAtMost(5)).dp, top = 10.dp, end = 24.dp, bottom = 10.dp),
                ) {
                    Text(
                        e.title,
                        Modifier.weight(1f),
                        style = if (e.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    )
                    e.page?.let { Text("$it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoToPageSheet(current: Int, count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val p = LocalPalette.current
    var value by remember { mutableFloatStateOf(current.toFloat()) }
    val page = Math.round(value)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        // Opens all the way: nothing is hidden below the bottom edge.
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Go to page", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Page $page of $count", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value,
                onValueChange = { value = it },
                valueRange = 1f..count.toFloat(),
                steps = 0,
                colors = SliderDefaults.colors(thumbColor = Poster.terracotta, activeTrackColor = Poster.terracotta),
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                RetroButton("Go", { onGo(page) }, icon = Icons.AutoMirrored.Filled.ArrowForward)
            }
        }
    }
}
