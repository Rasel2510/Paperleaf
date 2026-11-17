package com.rasel.paperleaf.ui.reader

import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.rasel.paperleaf.core.PageTint
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

/** Mixes [color] with white (day) or black (night) by [amount]. */
private fun soften(color: Int, amount: Float, toward: Int): Int {
    fun ch(shift: Int): Int {
        val c = (color shr shift) and 0xFF
        val t = (toward shr shift) and 0xFF
        return (c + (t - c) * amount).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * The page: drawn here, from the page bitmaps, with the marks on top, and the
 * page turn drawn the same way. Every touch on the page is handled here too:
 * side taps and swipes turn, a long press selects a word, two fingers zoom.
 */
@Composable
fun PageView(
    state: ReaderState,
    tint: PageTint,
    curl: Boolean,
    onTapMiddle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleRadius = with(density) { 9f * this.density }
    val handleTouch = with(density) { 30f * this.density }
    val currentCurl by rememberUpdatedState(curl)
    val tapMiddle by rememberUpdatedState(onTapMiddle)

    val pagePaint = remember(tint) {
        Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(tint.matrix()) }
    }

    // Converts between the screen and page points of the page on screen.
    fun geometry(w: Float, h: Float): Pair<RectF, Float> {
        val fit = state.images.fit(state.page - 1, RectF(0f, 0f, w, h))
        val scale = fit.width() / state.doc.pageSize(state.page - 1).width
        return fit to scale
    }

    fun toPage(v: Offset, w: Float, h: Float): Offset {
        val (fit, scale) = geometry(w, h)
        val cx = w / 2
        val cy = h / 2
        val x = (v.x - state.pan.x - cx) / state.zoom + cx
        val y = (v.y - state.pan.y - cy) / state.zoom + cy
        return Offset((x - fit.left) / scale, (y - fit.top) / scale)
    }

    fun toView(p: Offset, w: Float, h: Float): Offset {
        val (fit, scale) = geometry(w, h)
        val cx = w / 2
        val cy = h / 2
        val x = fit.left + p.x * scale
        val y = fit.top + p.y * scale
        return Offset((x - cx) * state.zoom + cx + state.pan.x, (y - cy) * state.zoom + cy + state.pan.y)
    }

    fun clampPan(pan: Offset, zoom: Float, w: Float, h: Float): Offset {
        val maxX = (zoom - 1) * w / 2
        val maxY = (zoom - 1) * h / 2
        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
    }

    /** The selection handles in screen points: start (left, bottom of the first line), end. */
    fun handles(w: Float, h: Float): Pair<Offset, Offset>? {
        val s = state.selection ?: return null
        if (s.page != state.page) return null
        val text = state.textOf(s.page) ?: return null
        val a = text.box(s.range.first) ?: return null
        val b = text.box(s.range.last) ?: return null
        return toView(Offset(a.left, a.bottom), w, h) to toView(Offset(b.right, b.bottom), w, h)
    }

    // Draws page [page] (1-based) with its marks into [area].
    fun drawPage(c: Canvas, page: Int, area: RectF, live: Boolean) {
        val fill = Paint().apply { color = tint.blank }
        c.drawRect(area, fill)
        if (page !in 1..state.pageCount) return
        val fit = state.images.fit(page - 1, area)
        val bitmap = (if (live && state.zoom > 1.05f) state.images.sharp(page - 1) else null) ?: state.images[page - 1]
        if (bitmap != null) c.drawBitmap(bitmap, null, fit, pagePaint)
        val scale = fit.width() / state.doc.pageSize(page - 1).width
        fun map(r: RectF) = RectF(fit.left + r.left * scale, fit.top + r.top * scale, fit.left + r.right * scale, fit.top + r.bottom * scale)

        // Highlights as a marker pen would: color on the paper, the print
        // stays dark (multiply). On the Night page the colors are lightened
        // onto the dark paper instead (screen).
        val mark = Paint()
        for (h in state.highlights) {
            if (h.page != page) continue
            val color = h.color ?: 0xFFFF9100.toInt()
            if (tint.isDark) {
                mark.color = soften(color, 0.45f, 0xFF000000.toInt())
                mark.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            } else {
                mark.color = soften(color, 0.45f, 0xFFFFFFFF.toInt())
                mark.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            for (r in h.rects) c.drawRect(map(r), mark)
        }

        if (!live) return
        // Search matches; the current one stronger.
        val current = state.matches.getOrNull(state.matchIndex)
        val found = Paint().apply { color = 0x55FFC400 }
        val currentPaint = Paint().apply { color = 0x88FF9100.toInt() }
        for (m in state.matches) {
            if (m.page != page) continue
            for (r in m.rects) c.drawRect(map(r), if (m === current) currentPaint else found)
        }
        // The selection.
        state.selection?.takeIf { it.page == page }?.let {
            val sel = Paint().apply { color = 0x552E6F6A }
            for (r in state.selectedRects()) c.drawRect(map(r), sel)
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.images.setViewport(it.width, it.height) }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val start = down.position
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, start)

                    // A selection handle: move that end.
                    handles(w, h)?.let { (a, b) ->
                        val nearA = hypot(start.x - a.x, start.y - (a.y + handleRadius)) < handleTouch
                        val nearB = hypot(start.x - b.x, start.y - (b.y + handleRadius)) < handleTouch
                        if (nearA || nearB) {
                            val s = state.selection!!
                            // Moving the end that is drawn at the touched handle.
                            val movingFocus = if (nearB) s.focus >= s.anchor else s.focus < s.anchor
                            down.consume()
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val p = toPage(ch.position - Offset(0f, handleRadius * 2), w, h)
                                state.dragSelection(p.x, p.y, movingFocus)
                                ch.consume()
                            }
                            state.lookUp()
                            return@awaitEachGesture
                        }
                    }

                    // Wait: a tap, a move, a second finger, or a long press.
                    var kind = "long"
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val e: PointerEvent = awaitPointerEvent()
                            if (e.changes.count { it.pressed } > 1) { kind = "pinch"; break }
                            val ch = e.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) { kind = "tap"; break }
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            if ((ch.position - start).getDistance() > slop) { kind = "move"; break }
                        }
                    }

                    when (kind) {
                        "tap" -> {
                            if (state.selection != null) {
                                state.clearSelection()
                            } else if (currentCurl && state.zoom <= 1.05f && start.x < w * 0.28f) {
                                state.turnPage(false)
                            } else if (currentCurl && state.zoom <= 1.05f && start.x > w * 0.72f) {
                                state.turnPage(true)
                            } else {
                                tapMiddle()
                            }
                        }

                        "long" -> {
                            val p = toPage(start, w, h)
                            state.selectWordAt(p.x, p.y)
                            // Keep the finger down and move: the selection grows.
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if ((ch.position - start).getDistance() > slop && state.selection != null) {
                                    val q = toPage(ch.position, w, h)
                                    state.dragSelection(q.x, q.y, movingFocus = true)
                                }
                                ch.consume()
                            }
                            state.lookUp()
                        }

                        "pinch" -> {
                            state.clearSelection()
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.none { it.pressed }) break
                                val zoomChange = e.calculateZoom()
                                val panChange = e.calculatePan()
                                val centroid = e.calculateCentroid(useCurrent = true)
                                val old = state.zoom
                                val next = (old * zoomChange).coerceIn(1f, 3f)
                                val factor = next / old
                                val center = Offset(w / 2, h / 2)
                                // Keep the point under the fingers in place.
                                val pan = (state.pan - (centroid - center)) * factor + (centroid - center) + panChange
                                state.zoom = next
                                state.pan = clampPan(pan, next, w, h)
                                e.changes.forEach(PointerInputChange::consume)
                            }
                            if (state.zoom < 1.05f) {
                                state.zoom = 1f
                                state.pan = Offset.Zero
                            }
                            state.images.sharpen(state.page - 1, state.zoom)
                        }

                        "move" -> {
                            if (state.turn != null) {
                                // A turn is still settling: remember this swipe.
                                var last = start
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                    last = ch.position
                                    if (!ch.pressed) break
                                }
                                val dx = last.x - start.x
                                val vx = tracker.calculateVelocity().x
                                if (abs(dx) > 40 || abs(vx) > 500) state.turnPage(if (abs(vx) > 500) vx < 0 else dx < 0)
                                return@awaitEachGesture
                            }
                            if (state.zoom > 1.05f) {
                                // Zoomed: the page pans. A quick swipe that starts at the
                                // page's edge turns the page.
                                val maxX = (state.zoom - 1) * w / 2
                                val startPan = state.pan
                                var previous = start
                                var end = start
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (e.changes.count { it.pressed } > 1) break
                                    tracker.addPosition(ch.uptimeMillis, ch.position)
                                    if (!ch.pressed) { end = ch.position; break }
                                    state.pan = clampPan(state.pan + (ch.position - previous), state.zoom, w, h)
                                    previous = ch.position
                                    end = ch.position
                                    ch.consume()
                                }
                                val v = tracker.calculateVelocity()
                                val atLeft = startPan.x >= maxX - 1
                                val atRight = startPan.x <= -maxX + 1
                                if (abs(v.x) > 600 && abs(v.y) < 800) {
                                    if (v.x < 0 && atRight) state.turnPage(true)
                                    if (v.x > 0 && atLeft) state.turnPage(false)
                                }
                                state.images.sharpen(state.page - 1, state.zoom)
                                return@awaitEachGesture
                            }
                            // At normal size: sideways turns, up and down does nothing.
                            var last = start
                            val firstMove = currentEvent.changes.firstOrNull { it.id == down.id }?.position ?: start
                            val horizontal = abs(firstMove.x - start.x) >= abs(firstMove.y - start.y)
                            if (!horizontal) return@awaitEachGesture
                            if (state.selection != null) state.clearSelection()
                            val forward = firstMove.x < start.x
                            val turning = state.startDrag(forward, start.y / h)
                            val span = if (currentCurl) 0.85f else 1f
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPosition(ch.uptimeMillis, ch.position)
                                last = ch.position
                                if (!ch.pressed) break
                                val dx = ch.position.x - start.x
                                if (turning) {
                                    val moved = if (forward) -dx else dx
                                    state.drag(moved / (w * span), ch.position.y - start.y, w, h, start.y / h)
                                } else {
                                    state.pushEdge(dx * 0.35f)
                                }
                                ch.consume()
                            }
                            if (turning) state.release(tracker.calculateVelocity().x) else state.releaseEdge()
                        }
                    }
                }
            },
    ) {
        // Read so the page draws again when these change.
        state.images.version
        val w = size.width
        val h = size.height
        val area = RectF(0f, 0f, w, h)
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            val turn = state.turn
            if (turn != null) {
                val shot: SheetDrawer = { cv -> drawPage(cv, turn.from, area, false) }
                val neighbor: SheetDrawer = { cv -> drawPage(cv, turn.to, area, false) }
                if (curl) {
                    drawCurl(c, w, h, state.progress, turn.forward, state.pivotY, state.tilt, shot, neighbor, tint.blank)
                } else {
                    drawSlide(c, w, h, state.progress, turn.forward, shot, neighbor, tint.blank)
                }
            } else {
                c.drawColor(tint.blank)
                c.save()
                c.translate(state.edgePush, 0f)
                c.translate(state.pan.x + w / 2, state.pan.y + h / 2)
                c.scale(state.zoom, state.zoom)
                c.translate(-w / 2, -h / 2)
                drawPage(c, state.page, area, true)
                c.restore()
                handles(w, h)?.let { (a, b) ->
                    val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E6F6A.toInt() }
                    c.drawCircle(a.x, a.y + handleRadius, handleRadius, knob)
                    c.drawCircle(b.x, b.y + handleRadius, handleRadius, knob)
                }
            }
        }
    }
}
