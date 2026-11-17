package com.rasel.paperleaf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.rasel.paperleaf.data.Book
import com.rasel.paperleaf.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
        return importStream(name) {
            context.contentResolver.openInputStream(uri) ?: throw NotAPdfException()
        }
    }

    /**
     * A PDF another app handed over ("Open with"). Opening the same file
     * again must not fill the shelf with copies: a book with the same title
     * is that book.
     */
    suspend fun importIncoming(uri: Uri): Book {
        val title = titleFor(displayName(uri) ?: "Book.pdf")
        repo.books().firstOrNull { it.title == title }?.let { return it }
        return import(uri)
    }

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
