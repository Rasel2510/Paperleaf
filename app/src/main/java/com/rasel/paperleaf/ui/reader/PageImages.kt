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
import kotlin.math.sqrt

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

    /** Sharper copies of the pages on screen (both of two), for zoom [sharpScale]. */
    private val sharp = HashMap<Int, Bitmap>()
    private var sharpScale = 1f
    private var sharpJob: Job? = null
    /** Goes up when the viewport changes, so a bitmap drawn for the old size is not kept. */
    private var generation = 0

    /** The area pages are fitted into, in pixels. Bitmaps of another size are dropped. */
    fun setViewport(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        generation++
        cache.clear()
        pending.clear()
        sharpJob?.cancel()
        sharp.clear()
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
    fun sharp(index: Int): Bitmap? = sharp[index]

    /** Draw the pages around [index] first; forget far ones. */
    fun focus(index: Int) {
        center = index
        val far = cache.keys.filter { it - index > 3 || index - it > 2 }
        far.forEach { cache.remove(it) }
        // With two pages on screen, the pages of the spreads either side too.
        for (i in listOf(index, index + 1, index - 1, index + 2, index + 3, index - 2)) request(i)
        sharp.keys.retainAll(setOf(index, index + 1))
    }

    /** Make sharp copies of [pages] (0-based: every page on screen) for zoom [scale] (after a pinch settles). */
    fun sharpen(pages: IntRange, scale: Float) {
        if (scale <= 1.05f) {
            sharpJob?.cancel()
            sharp.clear()
            return
        }
        // Up to the largest zoom (higher for two pages), but no bitmap past ~16M pixels.
        val s = min(scale, 9f)
        val wanted = pages.filter { it in 0 until doc.pageCount }
        val sameScale = abs(sharpScale - s) < 0.25f
        if (sameScale && wanted.all { it in sharp }) return
        sharpJob?.cancel()
        if (sameScale) sharp.keys.retainAll(wanted.toSet()) else sharp.clear()
        val gen = generation
        sharpJob = scope.launch {
            for (index in wanted) {
                if (index in sharp) continue
                val fit = fit(index, RectF(0f, 0f, width.toFloat(), height.toFloat()))
                val k = min(s, sqrt(16_000_000f / (fit.width() * fit.height()).coerceAtLeast(1f)))
                val bitmap = runCatching {
                    withContext(renderer) {
                        doc.render(index, (fit.width() * k).roundToInt(), (fit.height() * k).roundToInt())
                    }
                }.getOrNull() ?: return@launch
                if (gen != generation) return@launch
                sharp[index] = bitmap
                sharpScale = s
                version++
            }
        }
    }

    private fun request(index: Int) {
        if (index !in 0 until doc.pageCount || width == 0 || index in cache || index in pending) return
        pending += index
        val gen = generation
        scope.launch {
            val fit = fit(index, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            val bitmap = runCatching {
                withContext(renderer) {
                    doc.render(index, fit.width().roundToInt().coerceAtLeast(1), fit.height().roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
            // Drawn for a viewport that has since changed (the phone turned): not kept.
            if (gen != generation) return@launch
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
