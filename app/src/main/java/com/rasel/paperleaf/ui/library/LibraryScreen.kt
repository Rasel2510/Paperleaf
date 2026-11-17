package com.rasel.paperleaf.ui.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rasel.paperleaf.App
import com.rasel.paperleaf.LocalToast
import com.rasel.paperleaf.Screen
import com.rasel.paperleaf.core.BadBackupException
import com.rasel.paperleaf.core.LibrarySort
import com.rasel.paperleaf.core.NotAPdfException
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.rememberLoaded
import com.rasel.paperleaf.ui.Fonts
import com.rasel.paperleaf.ui.LocalPalette
import com.rasel.paperleaf.ui.Poster
import com.rasel.paperleaf.ui.RetroBox
import com.rasel.paperleaf.ui.RetroButton
import com.rasel.paperleaf.ui.settings.ReadingSettingsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun LibraryScreen(open: (Screen) -> Unit) {
    val p = LocalPalette.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val books = rememberLoaded { App.repo.books() }
    val sort by App.prefs.sort.collectAsState()
    val due = rememberLoaded { App.repo.reviewCounts(System.currentTimeMillis()).due } ?: 0
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Book?>(null) }
    var deleting by remember { mutableStateOf<Book?>(null) }

    fun work(failure: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (_: NotAPdfException) {
                toast("That file is not a PDF, or it is damaged.")
            } catch (_: BadBackupException) {
                toast("That file is not a Paperleaf backup, or it is damaged.")
            } catch (_: Exception) {
                toast(failure)
            } finally {
                busy = false
            }
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work("Could not add the book.") { App.library.import(uri) }
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            work("Could not create the backup.") {
                val made = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { App.library.backup(it) } ?: false
                }
                toast(if (made) "Backup saved." else "There are no books to back up yet.")
            }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            work("Could not restore the backup.") {
                val result = App.library.restore { context.contentResolver.openInputStream(uri)!! }
                toast(
                    when {
                        result.added == 0 && result.skipped == 0 -> "That backup has no books in it."
                        result.added == 0 -> "Every book of that backup is on the shelf."
                        result.added == 1 -> "Restored 1 book."
                        else -> "Restored ${result.added} books."
                    },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(p.paper)) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("My Library", style = MaterialTheme.typography.headlineSmall)
                    val count = books?.size
                    Text(
                        if (count == null) "" else "$count ${if (count == 1) "BOOK" else "BOOKS"} ON THE SHELF",
                        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.5.sp),
                    )
                }
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Tune, "Reading settings", tint = p.ink) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = p.ink) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = p.card) {
                        MenuItem(if (due > 0) "Review words ($due)" else "Review words") { menu = false; open(Screen.Review) }
                        MenuItem("Reading stats") { menu = false; open(Screen.Stats) }
                        MenuItem("Back up library") {
                            menu = false
                            saveBackup.launch("paperleaf-backup-${LocalDate.now()}.zip")
                        }
                        MenuItem("Restore a backup") {
                            menu = false
                            pickBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                        }
                        HorizontalDivider(color = p.paperDark)
                        for (s in LibrarySort.entries) {
                            MenuItem(s.label, checked = s == sort) { menu = false; App.prefs.setSort(s) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
            if (busy) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(4.dp),
                    color = Poster.terracotta,
                    trackColor = p.paperDark,
                )
            }

            val list = books
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyShelf()
                else -> {
                    val sorted = when (sort) {
                        LibrarySort.Recent -> list
                        LibrarySort.Title -> list.sortedBy { it.title.lowercase() }
                        LibrarySort.Progress -> list.sortedByDescending { it.progress }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(sorted, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                onOpen = { open(Screen.Reader(book)) },
                                onNotes = { open(Screen.Notes(book)) },
                                onRename = { renaming = book },
                                onDelete = { deleting = book },
                            )
                        }
                    }
                }
            }
        }

        RetroButton(
            label = "Add PDF",
            icon = Icons.Default.Add,
            onClick = { if (!busy) pickPdf.launch(arrayOf("application/pdf")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
            padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        )
    }

    if (settings) ReadingSettingsSheet(onDismiss = { settings = false })

    renaming?.let { book ->
        var text by remember(book.id) { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = p.card,
            title = { Text("Rename book", style = MaterialTheme.typography.titleLarge) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val name = text.trim()
                    renaming = null
                    if (name.isNotEmpty() && name != book.title) scope.launch { App.repo.renameBook(book.id, name) }
                }) { Text("SAVE", color = p.ink) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("CANCEL", color = p.ink) } },
        )
    }

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = p.card,
            title = { Text("Delete book?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("\"${book.title}\" and its notes will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    work("Could not delete the book.") { App.repo.deleteBook(book) }
                }) { Text("DELETE", color = p.ink) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("CANCEL", color = p.ink) } },
        )
    }
}

@Composable
private fun MenuItem(label: String, checked: Boolean? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        leadingIcon = if (checked == null) null else {
            { if (checked) Icon(Icons.Default.Check, null, tint = p.ink) else Spacer(Modifier.size(24.dp)) }
        },
    )
}

@Composable
private fun EmptyShelf() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RetroBox(color = Poster.mustard, radius = 40.dp) {
            Icon(Icons.Default.AutoStories, null, Modifier.padding(20.dp).size(48.dp), tint = Poster.onLight)
        }
        Spacer(Modifier.height(28.dp))
        Text("Your shelf is empty", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Add a PDF to start reading.", style = MaterialTheme.typography.bodyMedium)
    }
}

/** One book on the shelf: cover, progress strip, status stamp, title. */
@Composable
private fun BookCard(
    book: Book,
    onOpen: () -> Unit,
    onNotes: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val cover by produceState<ImageBitmap?>(null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeFile(book.coverPath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(Modifier.clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.66f)) {
            RetroBox(Modifier.fillMaxSize().padding(end = 4.dp, bottom = 4.dp), radius = 4.dp) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val image = cover
                        if (image != null) {
                            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            FallbackCover(book)
                        }
                        // Book spine.
                        Box(Modifier.width(8.dp).fillMaxHeight().background(Color(0x33000000)))
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
                    Box(Modifier.fillMaxWidth().height(8.dp).background(p.card)) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(book.progress)
                                .background(Poster.terracotta),
                        )
                    }
                }
            }
            Stamp(book, Modifier.offset((-6).dp, (-8).dp))
            Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)) {
                RetroBox(Modifier.size(28.dp), shadow = 0.dp, onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, "Book menu", Modifier.align(Alignment.Center).size(16.dp), tint = p.ink)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = p.card) {
                    MenuItem("Highlights & notes") { menu = false; onNotes() }
                    MenuItem("Rename") { menu = false; onRename() }
                    MenuItem("Delete") { menu = false; onDelete() }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            if (book.pageCount == 0) "NOT OPENED" else "PAGE ${book.lastPage} / ${book.pageCount}",
            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp, color = p.ink.copy(alpha = 0.65f)),
        )
    }
}

@Composable
private fun FallbackCover(book: Book) {
    val color = Poster.covers[(book.id % Poster.covers.size).toInt()]
    val on = if (color == Poster.mustard) Poster.onLight else Poster.onDark
    Column(Modifier.fillMaxSize().background(color).padding(start = 18.dp, top = 14.dp, end = 10.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(on))
        Spacer(Modifier.weight(1f))
        Text(book.title.take(1).uppercase().ifEmpty { "?" }, color = on, fontFamily = Fonts.display, fontSize = 56.sp)
        Text(book.title.uppercase(), color = on, fontFamily = Fonts.body, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Stamp(book: Book, modifier: Modifier) {
    val done = book.pageCount > 0 && book.progress >= 1f
    val label = when {
        done -> "DONE"
        book.lastPage <= 1 -> "NEW"
        else -> "${Math.round(book.progress * 100)}%"
    }
    RetroBox(
        modifier.rotate(-7f),
        color = if (done) Poster.teal else Poster.mustard,
        radius = 20.dp,
        shadow = 2.dp,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            fontFamily = Fonts.display,
            fontSize = 12.sp,
            color = if (done) Poster.onDark else Poster.onLight,
        )
    }
}
