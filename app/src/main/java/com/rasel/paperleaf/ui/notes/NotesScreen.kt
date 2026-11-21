package com.rasel.paperleaf.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.paperleaf.App
import com.rasel.paperleaf.LocalToast
import com.rasel.paperleaf.core.Dictionary
import com.rasel.paperleaf.core.wordsToCsv
import com.rasel.paperleaf.core.wordsWithMeanings
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.data.Highlight
import com.rasel.paperleaf.rememberLoaded
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.Poster
import com.rasel.paperleaf.ui.RetroBox
import com.rasel.paperleaf.ui.RetroButton
import com.rasel.paperleaf.ui.ScreenBar
import kotlinx.coroutines.launch

private data class Entry(
    /** Stays the same for the same item (the list keeps each row's place when others change). */
    val key: Any,
    val page: Int,
    val title: String,
    val subtitle: String? = null,
    val accent: Color? = null,
    val onDelete: (() -> Unit)? = null,
    val onLookup: (() -> Unit)? = null,
)

/** Highlights, bookmarks and saved words of one book. Tapping one opens its page. */
@Composable
fun NotesScreen(book: Book, onBack: () -> Unit, onOpenPage: (Int) -> Unit) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val highlights = rememberLoaded(book.id) { App.repo.highlights(book.id) }
    val bookmarks = rememberLoaded(book.id) { App.repo.bookmarkPages(book.id) }
    val words = rememberLoaded(book.id) { App.repo.words(book.id) }
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Highlight?>(null) }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        ScreenBar("Notes", onBack)
        SecondaryTabRow(
            selectedTabIndex = tab,
            containerColor = p.paper,
            contentColor = p.ink,
            indicator = {
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = false), height = 4.dp, color = Poster.terracotta)
            },
            divider = { Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink)) },
        ) {
            listOf("HIGHLIGHTS", "BOOKMARKS", "WORDS").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Text(
                            label,
                            fontFamily = Fonts.body,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp,
                            color = if (tab == i) p.ink else p.ink.copy(alpha = 0.55f),
                        )
                    },
                )
            }
        }
        when (tab) {
            0 -> EntryList(
                empty = "No highlights yet",
                accent = Poster.mustard,
                unit = "highlight",
                separator = "\n\n",
                // Newest first.
                entries = remember(highlights) {
                    highlights?.sortedByDescending { it.id }?.map { h ->
                        Entry("h${h.id}", h.page, h.text, accent = h.color?.let { Color(it) }, onDelete = { confirm = h })
                    }
                },
                onOpenPage = onOpenPage,
            )
            1 -> EntryList(
                empty = "No bookmarks yet",
                accent = Poster.terracotta,
                unit = null,
                entries = bookmarks?.sorted()?.map { page ->
                    Entry("b$page", page, "Page $page", onDelete = { scope.launch { App.repo.setBookmark(book.id, page, false) } })
                },
                onOpenPage = onOpenPage,
            )
            else -> EntryList(
                empty = "No saved words yet",
                accent = Poster.teal,
                unit = "word",
                extraCopies = words?.let {
                    listOf(
                        Triple("Copy words + meanings", wordsWithMeanings(it), "Copied words with meanings"),
                        Triple("Copy as CSV (word, meaning, page)", wordsToCsv(it), "Copied CSV"),
                    )
                } ?: emptyList(),
                entries = words?.map { w ->
                    Entry(
                        key = "w${w.id}",
                        page = w.page,
                        title = w.word,
                        subtitle = w.meaning,
                        onDelete = { scope.launch { runCatching { App.repo.deleteWord(w.id) }.onFailure { toast("Could not delete the word.") } } },
                        onLookup = if (w.meaning == null && Dictionary.lookupWord(w.word) != null) {
                            {
                                toast("Looking up “${w.word}”…")
                                scope.launch {
                                    when (val result = Dictionary.define(w.word)) {
                                        is Dictionary.Result.Found -> App.repo.setWordMeaning(w.id, result.meaning)
                                        else -> toast(Dictionary.missingMessage(w.word, result))
                                    }
                                }
                            }
                        } else null,
                    )
                },
                onOpenPage = onOpenPage,
            )
        }
    }

    confirm?.let { h ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = p.card,
            title = { Text("Delete highlight?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("It is removed from the list and from the page.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { runCatching { App.repo.deleteHighlight(h.id) }.onFailure { toast("Could not delete the highlight.") } }
                }) { Text("DELETE", color = p.ink) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL", color = p.ink) } },
        )
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Paperleaf", text))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    empty: String,
    accent: Color,
    unit: String?,
    entries: List<Entry>?,
    onOpenPage: (Int) -> Unit,
    separator: String = "\n",
    extraCopies: List<Triple<String, String, String>> = emptyList(),
) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val items = entries ?: return
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(empty, style = MaterialTheme.typography.bodyMedium) }
        return
    }
    fun copyWith(text: String, message: String) {
        copy(context, text)
        toast(message)
    }

    Column(Modifier.fillMaxSize()) {
        if (unit != null) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 23.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                val count = items.size
                Text(
                    "$count ${unit.uppercase()}${if (count == 1) "" else "S"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.5.sp),
                )
                if (extraCopies.isNotEmpty()) {
                    Box {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreHoriz, "More ways to copy", tint = p.ink) }
                        DropdownMenu(open, { open = false }, containerColor = p.card) {
                            for ((label, text, message) in extraCopies) {
                                DropdownMenuItem(
                                    text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { open = false; copyWith(text, message) },
                                )
                            }
                        }
                    }
                }
                RetroButton(
                    "Copy all",
                    icon = Icons.Default.CopyAll,
                    onClick = {
                        copyWith(items.joinToString(separator) { it.title }, "Copied $count $unit${if (count == 1) "" else "s"}")
                    },
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(items, key = { it.key }) { e ->
                RetroBox(Modifier.fillMaxWidth(), shadow = 3.dp, clipContent = false) {
                    val strip = e.accent ?: accent
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // The color strip and its ink line are drawn, not laid out: the
                            // row is then measured once (a strip as tall as the row needed two).
                            // Its outer corners are rounded like the card's (the card does not clip).
                            .drawBehind {
                                val w = 10.dp.toPx()
                                val r = CornerRadius(6.dp.toPx())
                                drawPath(
                                    Path().apply {
                                        addRoundRect(RoundRect(0f, 0f, w, size.height, topLeftCornerRadius = r, bottomLeftCornerRadius = r))
                                    },
                                    strip,
                                )
                                drawRect(p.ink, topLeft = Offset(w, 0f), size = Size(2.dp.toPx(), size.height))
                            }
                            .combinedClickable(
                                onClick = { onOpenPage(e.page) },
                                onLongClick = if (unit != null) {
                                    { copyWith(e.title, "Copied") }
                                } else null,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(start = 26.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
                            Text(e.title, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (e.subtitle != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(e.subtitle, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("PAGE ${e.page}", fontFamily = Fonts.display, fontSize = 12.sp, color = Poster.terracotta)
                        }
                        if (e.onLookup != null) {
                            IconButton(onClick = e.onLookup) { Icon(Icons.AutoMirrored.Filled.ManageSearch, "Look up meaning", tint = p.ink) }
                        }
                        if (unit != null) {
                            IconButton(onClick = { copyWith(e.title, "Copied") }) {
                                Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(20.dp), tint = p.ink)
                            }
                        }
                        if (e.onDelete != null) {
                            IconButton(onClick = e.onDelete) { Icon(Icons.Default.DeleteOutline, "Delete", tint = p.ink) }
                        }
                    }
                }
            }
        }
    }
}

