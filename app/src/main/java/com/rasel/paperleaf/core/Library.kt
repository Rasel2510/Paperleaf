package com.rasel.paperleaf.core

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.rasel.paperleaf.data.Highlight
import com.rasel.paperleaf.pdf.PdfDoc
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The file is not a PDF (wrong or damaged file). */
class NotAPdfException : Exception()

/** The file is not a Paperleaf backup (or it is damaged). */
class BadBackupException : Exception()

/** Putting books on the shelf: from a picked file, another app, or a backup. */
class Library(private val context: Context, private val repo: Repository) {

    private val booksDir: File get() = File(context.filesDir, "books").apply { mkdirs() }

    /** Copies the PDF behind [uri] onto the shelf; returns the new book. */
    suspend fun import(uri: Uri): Book {
        val name = displayName(uri) ?: "Book.pdf"
        val book = importStream(name) {
            context.contentResolver.openInputStream(uri) ?: throw NotAPdfException()
        }
        val source = sourceOf(uri) ?: return book
        repo.setSourceUri(book.id, source)
        return book.copy(sourceUri = source)
    }

    /**
     * A PDF another app handed over ("Open with"). Opening the same file
     * again must not fill the shelf with copies: a book with the same title
     * is that book.
     */
    suspend fun importIncoming(uri: Uri): Book {
        val title = titleFor(displayName(uri) ?: "Book.pdf")
        repo.books().firstOrNull { it.title == title }?.let { book ->
            if (book.sourceUri != null) return book
            val source = sourceOf(uri) ?: return book
            repo.setSourceUri(book.id, source)
            return book.copy(sourceUri = source)
        }
        return import(uri)
    }

    /**
     * Where the book's own file is, for saving highlights into it: its place
     * on the phone ("file://…") when that can be found, else [uri] itself
     * (kept readable and writable after a restart when the file's app allows).
     */
    private fun sourceOf(uri: Uri): String? {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        realPath(uri)?.let { return Uri.fromFile(File(it)).toString() }
        return uri.toString()
    }

    /** The file on the phone's storage behind [uri], when there is one. */
    private fun realPath(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path
        var mediaUri: Uri? = null
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val volume = id.substringBefore(':')
                    val path = id.substringAfter(':')
                    if (volume.equals("primary", ignoreCase = true)) {
                        return@runCatching File(Environment.getExternalStorageDirectory(), path).path
                    }
                    return@runCatching "/storage/$volume/$path"
                }
                "com.android.providers.downloads.documents" -> {
                    if (id.startsWith("raw:")) return@runCatching id.removePrefix("raw:")
                    id.substringAfter("msf:", "").toLongOrNull()?.let {
                        mediaUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it)
                    }
                }
                "com.android.providers.media.documents" -> {
                    id.substringAfter(':').toLongOrNull()?.let {
                        mediaUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it)
                    }
                }
            }
        } else if (uri.authority == MediaStore.AUTHORITY) {
            mediaUri = uri
        }
        @Suppress("DEPRECATION")
        val column = MediaStore.MediaColumns.DATA
        context.contentResolver.query(mediaUri ?: uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() && File(it).exists() }

    private val fileMarks = context.getSharedPreferences("file_marks", Context.MODE_PRIVATE)

    /** What was last saved into book [id]'s file (null: nothing yet, the file is as it came). */
    fun savedMarks(id: Long): String? = fileMarks.getString("$id", null)

    fun setSavedMarks(id: Long, key: String) = fileMarks.edit().putString("$id", key).apply()

    /** May the app write to any file on the phone's storage ("All files access")? */
    fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    enum class SaveResult { Saved, NeedsFileAccess, Failed }

    /**
     * Saves [highlights] into the file the book came from, as real PDF
     * highlights (like other reader apps do), so they are still there when
     * the book is added again, after the app was removed. Highlights already
     * in that file are replaced; everything else in it is kept.
     * [SaveResult.NeedsFileAccess]: the file is on the phone, but another app
     * owns it and the app may not write it without "All files access".
     */
    suspend fun saveHighlightsToSource(book: Book, doc: PdfDoc, highlights: List<Highlight>): SaveResult =
        writing.withLock {
            withContext(Dispatchers.IO) {
                val source = Uri.parse(book.sourceUri ?: return@withContext SaveResult.Failed)
                val path = if (source.scheme == "file") source.path else realPath(source)
                val temp = File(context.cacheDir, "highlights-${book.id}.pdf")
                try {
                    if (path != null && File(path).exists()) {
                        if (!hasFileAccess() && !writable(source)) return@withContext SaveResult.NeedsFileAccess
                        val file = File(path)
                        val ok = runCatching {
                            // Made aside first: the book is only overwritten once the new file is complete.
                            file.inputStream().use { input -> temp.outputStream().use { doc.writeAnnotated(highlights, it, from = input) } }
                            if (hasFileAccess()) {
                                file.outputStream().use { out -> temp.inputStream().use { it.copyTo(out) } }
                            } else {
                                writeTo(source, temp)
                            }
                        }.onFailure { Log.w(TAG, "could not save highlights into $path", it) }.isSuccess
                        if (ok) MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("application/pdf"), null)
                        return@withContext if (ok) SaveResult.Saved else if (hasFileAccess()) SaveResult.Failed else SaveResult.NeedsFileAccess
                    }
                    // A file only another app can reach (no place on the phone found).
                    runCatching {
                        context.contentResolver.openInputStream(source)!!.use { input ->
                            temp.outputStream().use { doc.writeAnnotated(highlights, it, from = input) }
                        }
                        writeTo(source, temp)
                    }.onFailure { Log.w(TAG, "could not save highlights into $source", it) }
                        // Without "All files access" the file's place may be hidden: with it, it may be found.
                        .fold({ SaveResult.Saved }, { if (hasFileAccess()) SaveResult.Failed else SaveResult.NeedsFileAccess })
                } finally {
                    temp.delete()
                }
            }
        }

    /** Can [uri] be opened for writing (without "All files access")? */
    private fun writable(uri: Uri): Boolean =
        uri.scheme == "content" && runCatching { context.contentResolver.openFileDescriptor(uri, "rw")!!.close() }.isSuccess

    /** Replaces the file behind [uri] with [from]. */
    private fun writeTo(uri: Uri, from: File) {
        context.contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out ->
                out.channel.truncate(0)
                from.inputStream().use { it.copyTo(out) }
            }
        }
    }

    /** One save into a file at a time. */
    private val writing = kotlinx.coroutines.sync.Mutex()

    private suspend fun importStream(name: String, open: () -> InputStream): Book = withContext(Dispatchers.IO) {
        val target = File(booksDir, "${System.currentTimeMillis()}.pdf")
        val cover = File(target.path.removeSuffix(".pdf") + ".jpg")
        try {
            open().use { input -> target.outputStream().use { input.copyTo(it) } }
            if (!looksLikePdf(target)) throw NotAPdfException()
            // 0 pages: the cover could not be drawn (a locked PDF, say). The
            // reader fills the count in when it opens.
            val pages = makeCover(target, cover)
            val id = repo.addBook(titleFor(name), target.path, pages)
            repo.book(id) ?: throw IllegalStateException("book vanished")
        } catch (e: Exception) {
            // Leave nothing behind for a book that did not reach the shelf.
            target.delete()
            cover.delete()
            throw e
        }
    }

    private fun titleFor(fileName: String) = cleanBookTitle(fileName.removeSuffix(".pdf").removeSuffix(".PDF")).ifEmpty { "Untitled" }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Draws page 1 as the cover; returns the page count (0 if the PDF cannot be read). */
    private fun makeCover(pdf: File, cover: File): Int = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val width = 400
                    val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bitmap.recycle()
                }
                renderer.pageCount
            }
        }
    }.getOrDefault(0)

    // Backup: the same format as the Flutter app, so its backups restore here.
    // backup.json lists every book and its notes; books/ holds each PDF and cover.

    /** Writes the backup to [out]; false when there is no book to back up. */
    suspend fun backup(out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        val books = repo.exportLibrary()
        if (books.isEmpty()) return@withContext false
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject().apply {
                put("format", 1)
                put("app", "Paperleaf Reader")
                put("created", java.time.LocalDateTime.now().toString())
                put("books", org.json.JSONArray(books.map { it.second }))
            }
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            for ((path, _) in books) {
                val pdf = File(path)
                if (!pdf.exists()) continue
                for (file in listOf(pdf, File(path.removeSuffix(".pdf") + ".jpg"))) {
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry("books/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    }

    data class Restored(val added: Int, val skipped: Int)

    /**
     * Adds the books of a backup that are not on the shelf. Never replaces or
     * deletes anything. The zip is read twice (list first, files second), so
     * [open] is called twice.
     */
    suspend fun restore(open: () -> InputStream): Restored = withContext(Dispatchers.IO) {
        val books = readManifest(open) ?: throw BadBackupException()
        val onShelf = repo.bookFileNames()
        val wanted = mutableMapOf<String, JSONObject>()
        var skipped = 0
        for (book in books) {
            // Only the file name is used, so a backup cannot write elsewhere.
            val name = File(book.optString("file")).name
            if (!name.lowercase().endsWith(".pdf")) continue
            if (name in onShelf) skipped++ else wanted[name] = book
        }

        val extracted = mutableMapOf<String, File>()
        try {
            ZipInputStream(open().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.removePrefix("books/")
                    if (entry.isDirectory || !entry.name.startsWith("books/") || name.contains('/')) continue
                    val pdfName = if (name.endsWith(".jpg")) name.removeSuffix(".jpg") + ".pdf" else name
                    if (pdfName !in wanted) continue
                    val file = File(booksDir, name)
                    file.outputStream().use { zip.copyTo(it) }
                    extracted[name] = file
                }
            }
            var added = 0
            for ((name, book) in wanted) {
                val pdf = extracted[name] ?: continue
                repo.importBook(book, pdf.path)
                extracted.remove(name)
                extracted.remove(name.removeSuffix(".pdf") + ".jpg")
                added++
            }
            Restored(added, skipped)
        } finally {
            // Files of books that did not reach the shelf.
            extracted.values.forEach { it.delete() }
        }
    }

    private fun readManifest(open: () -> InputStream): List<JSONObject>? = runCatching {
        ZipInputStream(open().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "backup.json") continue
                val data = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                val list = data.getJSONArray("books")
                return@runCatching (0 until list.length()).mapNotNull { list.opt(it) as? JSONObject }
            }
            null
        }
    }.getOrNull()

    companion object {
        /** Every PDF has "%PDF" near its start. */
        fun looksLikePdf(file: File): Boolean {
            val head = ByteArray(1024)
            val read = file.inputStream().use { it.read(head) }
            if (read <= 0) return false
            return String(head, 0, read, Charsets.ISO_8859_1).contains("%PDF")
        }
    }
}
private const val TAG = "Paperleaf"
