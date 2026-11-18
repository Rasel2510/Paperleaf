package com.rasel.paperleaf.pdf

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.SizeF
import com.rasel.paperleaf.data.Highlight
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** One line of the table of contents; [page] is 1-based, null when unknown. */
data class TocEntry(val title: String, val depth: Int, val page: Int?)

/** A highlight found inside the PDF file (made by another app, or by the Flutter version). */
data class PdfMark(val page: Int, val rects: List<RectF>, val color: Int, val subject: String?)

/**
 * The text of one page: one entry per character, with its box in page points
 * (from the top-left corner). Spaces and line breaks the text extraction
 * inserts have no box.
 */
class PageText(val text: String, private val boxes: List<RectF?>, private val lines: IntArray) {
    val length: Int get() = text.length

    fun box(i: Int): RectF? = boxes.getOrNull(i)

    /** The character at (or nearest to) [x],[y] on the same line; -1 when the page has no text there. */
    fun charAt(x: Float, y: Float, slop: Float = 6f): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in boxes.indices) {
            val b = boxes[i] ?: continue
            val dy = when {
                y < b.top -> b.top - y
                y > b.bottom -> y - b.bottom
                else -> 0f
            }
            if (dy > slop) continue
            val dx = when {
                x < b.left -> b.left - x
                x > b.right -> x - b.right
                else -> 0f
            }
            val d = dx + dy * 4
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return if (bestDistance <= slop * 4) best else -1
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    /** The word around character [i] as a range of indices. */
    fun wordAt(i: Int): IntRange {
        if (i !in text.indices) return IntRange.EMPTY
        if (!isWordChar(text[i])) return i..i
        var start = i
        var end = i
        while (start > 0 && isWordChar(text[start - 1]) && boxes[start - 1] != null) start--
        while (end < text.length - 1 && isWordChar(text[end + 1]) && boxes[end + 1] != null) end++
        return start..end
    }

    fun textOf(range: IntRange): String =
        text.substring(range.first, range.last + 1).replace(Regex("""\s+"""), " ").trim()

    /** The boxes of [range], joined line by line. */
    fun rectsOf(range: IntRange): List<RectF> {
        val byLine = linkedMapOf<Int, RectF>()
        for (i in range) {
            val b = boxes.getOrNull(i) ?: continue
            val line = lines[i]
            val r = byLine[line]
            if (r == null) byLine[line] = RectF(b) else r.union(b)
        }
        return byLine.values.toList()
    }

    /** Every place [query] appears (case ignored), as character ranges. */
    fun find(query: String): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val hay = text.lowercase()
        val needle = query.lowercase()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            out += at until at + needle.length
            from = at + needle.length
        }
        return out
    }
}

/**
 * An open PDF. Pages are drawn by Android's PdfRenderer (fast, the same
 * engine as the system); text, contents and marks come from PdfBox, which is
 * loaded only when first needed. Safe to use from background threads.
 */
class PdfDoc private constructor(
    private val file: File,
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int = renderer.pageCount
    private val sizes = arrayOfNulls<SizeF>(pageCount)
    private var closed = false

    private val boxLock = Any()
    private var box: PDDocument? = null
    private val texts = HashMap<Int, PageText>()

    /** Size of page [index] (0-based) in points. */
    fun pageSize(index: Int): SizeF = synchronized(renderer) {
        sizes[index] ?: if (closed) SizeF(612f, 792f) else {
            renderer.openPage(index).use { SizeF(it.width.toFloat(), it.height.toFloat()) }.also { sizes[index] = it }
        }
    }

    /** Page [index] drawn into a [width]×[height] bitmap on white; null once closed. */
    fun render(index: Int, width: Int, height: Int): Bitmap? = synchronized(renderer) {
        if (closed || width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        bitmap
    }

    private fun box(): PDDocument = box ?: PDDocument.load(file).also { box = it }

    /** The text of page [index] (0-based); cached. */
    fun text(index: Int): PageText = synchronized(boxLock) {
        texts[index]?.let { return it }
        val result = runCatching { extract(index) }.getOrElse { PageText("", emptyList(), IntArray(0)) }
        texts[index] = result
        result
    }

    private fun extract(index: Int): PageText {
        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
        val lines = ArrayList<Int>()
        var line = 0
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (p in textPositions) {
                    val h = p.heightDir.coerceAtLeast(1f)
                    val rect = RectF(
                        p.xDirAdj,
                        p.yDirAdj - h * 1.25f,
                        p.xDirAdj + p.widthDirAdj,
                        p.yDirAdj + h * 0.3f,
                    )
                    val unicode = p.unicode ?: continue
                    // A ligature ("fi") is one glyph for several characters.
                    for ((k, c) in unicode.withIndex()) {
                        val part = rect.width() / unicode.length
                        sb.append(c)
                        boxes += RectF(rect.left + part * k, rect.top, rect.left + part * (k + 1), rect.bottom)
                        lines += line
                    }
                }
            }

            override fun writeWordSeparator() {
                sb.append(' '); boxes += null; lines += line
            }

            override fun writeLineSeparator() {
                sb.append('\n'); boxes += null; lines += line
                line++
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(box())
        return PageText(sb.toString(), boxes, lines.toIntArray())
    }

    /** The PDF's bookmark tree, flattened. Empty for PDFs without one. */
    fun outline(): List<TocEntry> = synchronized(boxLock) {
        runCatching {
            val doc = box()
            val out = mutableListOf<TocEntry>()
            fun walk(node: PDOutlineNode, depth: Int) {
                for (item in node.children()) {
                    if (out.size >= 3000) return
                    val title = item.title?.trim().orEmpty()
                    if (title.isNotEmpty()) {
                        val page = runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) } }
                            .getOrNull()?.takeIf { it >= 0 }?.plus(1)
                        out += TocEntry(title, depth, page)
                    }
                    walk(item, depth + 1)
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0) }
            out
        }.getOrDefault(emptyList())
    }

    /** Highlights stored inside the file. */
    fun marks(): List<PdfMark> = synchronized(boxLock) {
        runCatching {
            val doc = box()
            val out = mutableListOf<PdfMark>()
            for ((i, page) in doc.pages.withIndex()) {
                val crop = page.cropBox
                for (annotation in page.annotations) {
                    if (annotation !is PDAnnotationTextMarkup || annotation.subtype != "Highlight") continue
                    val q = annotation.quadPoints ?: continue
                    val rects = mutableListOf<RectF>()
                    var k = 0
                    while (k + 7 < q.size) {
                        val xs = floatArrayOf(q[k], q[k + 2], q[k + 4], q[k + 6])
                        val ys = floatArrayOf(q[k + 1], q[k + 3], q[k + 5], q[k + 7])
                        rects += RectF(
                            xs.min() - crop.lowerLeftX,
                            crop.upperRightY - ys.max(),
                            xs.max() - crop.lowerLeftX,
                            crop.upperRightY - ys.min(),
                        )
                        k += 8
                    }
                    if (rects.isEmpty()) continue
                    val rgb = annotation.color?.components
                    val color = if (rgb != null && rgb.size >= 3) {
                        com.rasel.paperleaf.core.highlightColorFromFile(
                            Color.argb(255, (rgb[0] * 255).roundToInt(), (rgb[1] * 255).roundToInt(), (rgb[2] * 255).roundToInt()),
                        )
                    } else {
                        0xFFFF9100.toInt()
                    }
                    out += PdfMark(i + 1, rects, color, annotation.subject)
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Writes a copy of the PDF with [highlights] as real PDF highlights, for
     * other reader apps. Highlights already in the file are replaced. [from]:
     * the PDF to mark instead of the shelf copy (the file the book came from);
     * it must have the same pages, or nothing is written.
     */
    fun writeAnnotated(highlights: List<Highlight>, out: OutputStream, from: java.io.InputStream? = null) {
        (if (from != null) PDDocument.load(from) else PDDocument.load(file)).use { doc ->
            check(doc.numberOfPages == pageCount) { "not the same book" }
            for ((i, page) in doc.pages.withIndex()) {
                val kept = page.annotations.filterNot { it is PDAnnotationTextMarkup && it.subtype == "Highlight" }
                val crop = page.cropBox
                val added = highlights.filter { it.page == i + 1 && it.rects.isNotEmpty() }.map { h ->
                    val quads = FloatArray(h.rects.size * 8)
                    for ((k, r) in h.rects.withIndex()) {
                        val left = r.left + crop.lowerLeftX
                        val right = r.right + crop.lowerLeftX
                        val top = crop.upperRightY - r.top
                        val bottom = crop.upperRightY - r.bottom
                        floatArrayOf(left, top, right, top, left, bottom, right, bottom).copyInto(quads, k * 8)
                    }
                    val all = RectF(h.rects.first()).apply { h.rects.forEach { union(it) } }
                    // Soft, as the page shows it: other apps draw the file's color as it is.
                    val color = com.rasel.paperleaf.core.highlightColorForFile(h.color ?: 0xFFFF9100.toInt())
                    PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                        rectangle = PDRectangle(
                            all.left + crop.lowerLeftX,
                            crop.upperRightY - all.bottom,
                            all.width(),
                            all.height(),
                        )
                        quadPoints = quads
                        this.color = PDColor(
                            floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f),
                            PDDeviceRGB.INSTANCE,
                        )
                        subject = h.marker
                        contents = h.text
                        runCatching { constructAppearances() }
                    }
                }
                page.annotations = kept + added
            }
            doc.save(out)
        }
    }

    override fun close() {
        synchronized(renderer) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
        synchronized(boxLock) {
            runCatching { box?.close() }
            box = null
        }
    }

    companion object {
        fun open(file: File): PdfDoc {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfDoc(file, fd, PdfRenderer(fd))
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
