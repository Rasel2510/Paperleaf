package com.rasel.paperleaf.ui.reader

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.rasel.paperleaf.pdf.PdfDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bitmaps of the pages around the reader, drawn ahead so a turn never waits
 * for one. Sized to fit the screen; the page on screen also gets a sharper
 * copy while zoomed in. Used from the main thread only; drawing happens on a
 * background thread, one page at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageImages(private val doc: PdfDoc, private val scope: CoroutineScope) {

    /** Goes up whenever a bitmap is ready (the page view draws again). */
    var version by mutableIntStateOf(0)
        private set

    private val cache = LinkedHashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    private val renderer = Dispatchers.IO.limitedParallelism(1)
    private var width = 0
    private var height = 0
    private var center = 0

    private var sharp: Pair<Int, Bitmap>? = null
    private var sharpScale = 1f
    private var sharpJob: Job? = null

    /** The area pages are fitted into, in pixels. Bitmaps of another size are dropped. */
    fun setViewport(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        cache.clear()
        pending.clear()
        sharp = null
        version++
        focus(center)
    }

    /** The bitmap of page [index] (0-based), or null until it is drawn (it is asked for). */
    operator fun get(index: Int): Bitmap? {
        cache[index]?.let { return it }
        request(index)
        return null
    }

    /** A sharper bitmap of page [index] for zoom [scale], if one is ready. */
    fun sharp(index: Int): Bitmap? = sharp?.takeIf { it.first == index }?.second

    /** Draw the pages around [index] first; forget far ones. */
    fun focus(index: Int) {
        center = index
        val far = cache.keys.filter { abs(it - index) > 2 }
        far.forEach { cache.remove(it) }
        for (i in listOf(index, index + 1, index - 1, index + 2)) request(i)
        if (sharp?.first != index) sharp = null
    }

    /** Make a sharp copy of [index] for zoom [scale] (after a pinch settles). */
    fun sharpen(index: Int, scale: Float) {
        if (scale <= 1.05f) {
            sharp = null
            return
        }
        if (sharp?.first == index && abs(sharpScale - scale) < 0.25f) return
        sharpJob?.cancel()
        sharpJob = scope.launch {
            val fit = fit(index, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            val s = min(scale, 3f)
            val bitmap = withContext(renderer) {
                doc.render(index, (fit.width() * s).roundToInt(), (fit.height() * s).roundToInt())
            } ?: return@launch
            sharp = index to bitmap
            sharpScale = s
            version++
        }
    }

    private fun request(index: Int) {
        if (index !in 0 until doc.pageCount || width == 0 || index in cache || index in pending) return
        pending += index
        scope.launch {
            val fit = fit(index, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            val bitmap = runCatching {
                withContext(renderer) {
                    doc.render(index, fit.width().roundToInt().coerceAtLeast(1), fit.height().roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
            pending -= index
            if (bitmap != null && fit.width() > 0) {
                cache[index] = bitmap
                version++
            }
        }
    }

    /** Where page [index] sits in [area]: the whole page inside it, centered. */
    fun fit(index: Int, area: RectF): RectF {
        val size = doc.pageSize(index)
        val scale = min(area.width() / size.width, area.height() / size.height)
        val w = size.width * scale
        val h = size.height * scale
        val left = area.left + (area.width() - w) / 2
        val top = area.top + (area.height() - h) / 2
        return RectF(left, top, left + w, top + h)
    }
}
