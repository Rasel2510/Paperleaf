package com.rasel.paperleaf.ui.reader

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Draws one whole sheet (page with its marks) over the area. */
typealias SheetDrawer = (Canvas) -> Unit

/**
 * Paper page turn. The sheet is bound at the left edge. The part beyond the
 * fold line is folded back over the page (its back side shows), and the page
 * underneath appears where it was.
 *
 * Forward: the current page ([shot]) curls away to the left over [neighbor].
 * Backward: the previous page ([neighbor]) curls back in from the left, the
 * same motion run in reverse.
 *
 * [pivotY] (0 top .. 1 bottom) is the height the fold goes through, [tilt]
 * how far it leans (sideways shift per unit of height).
 */
fun drawCurl(
    c: Canvas,
    w: Float,
    h: Float,
    t: Float,
    forward: Boolean,
    pivotY: Float,
    tilt: Float,
    shot: SheetDrawer,
    neighbor: SheetDrawer,
    paper: Int,
) {
    if (w <= 0f || h <= 0f) return
    val area = RectF(0f, 0f, w, h)
    val q = if (forward) t else 1 - t
    val top = if (forward) shot else neighbor
    val bottom = if (forward) neighbor else shot

    c.save()
    c.clipRect(area)
    bottom(c)
    if (q <= 0.001f) {
        top(c)
        c.restore()
        return
    }
    if (q >= 0.999f) {
        c.restore()
        return
    }

    // Fold line through (foldX, pivot), leaning by `lean`. It never crosses
    // the bound edge, so the lean dies out near the end.
    val foldX = w * (1 - q)
    val pivot = pivotY.coerceIn(0f, 1f) * h
    val reach = max(pivot, h - pivot)
    val maxLean = foldX / reach
    val lean = (tilt * min(1f, q * 4)).coerceIn(-maxLean, maxLean)
    val xTop = foldX - pivot * lean
    val xBottom = foldX + (h - pivot) * lean

    val far = Path().apply {
        moveTo(xTop, 0f); lineTo(w + h, 0f); lineTo(w + h, h); lineTo(xBottom, h); close()
    }
    val keep = Path().apply {
        moveTo(-1f, 0f); lineTo(xTop, 0f); lineTo(xBottom, h); lineTo(-1f, h); close()
    }

    val len = sqrt(lean * lean + 1)
    val ux = lean / len // along the fold
    val uy = 1 / len
    val nx = uy // towards the folded-away side
    val ny = -ux

    // Mirror across the fold line.
    val a = 2 * ux * ux - 1
    val b = 2 * ux * uy
    val d = 2 * uy * uy - 1
    val mirror = Matrix().apply {
        setValues(
            floatArrayOf(
                a, b, foldX - a * foldX - b * pivot,
                b, d, pivot - b * foldX - d * pivot,
                0f, 0f, 1f,
            ),
        )
    }

    val lifted = w - foldX
    val strength = min(1f, (1 - q) * 3) * min(1f, q * 6)

    // Shadow of the lifted page on the page underneath.
    c.save()
    c.clipPath(far)
    val reachOut = max(12f, min(lifted, w * 0.3f))
    c.drawRect(
        area,
        Paint().apply {
            shader = LinearGradient(
                foldX, pivot, foldX + nx * reachOut, pivot + ny * reachOut,
                argb(0.38f * strength, 0), 0, Shader.TileMode.CLAMP,
            )
        },
    )
    c.restore()

    // The part of the top sheet still lying flat.
    c.save()
    c.clipPath(keep)
    top(c)
    c.restore()

    val flap = Path().apply {
        op(far, Path().apply { addRect(area, Path.Direction.CW) }, Path.Op.INTERSECT)
        transform(mirror)
    }

    // Shadow of the folded-back part on the flat part.
    c.save()
    c.clipPath(keep)
    c.drawPath(
        flap,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(0.32f * strength, 0)
            maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        },
    )
    c.restore()

    // Back of the page: paper with the print faintly showing through.
    c.save()
    c.concat(mirror)
    c.clipRect(area)
    c.clipPath(far)
    c.drawRect(area, Paint().apply { color = paper })
    c.saveLayerAlpha(area, 26)
    top(c)
    c.restore()
    c.restore()

    // Roundness of the bend: dark crease, a soft highlight, a darker free edge.
    c.save()
    c.clipPath(flap)
    val bounds = RectF().also { flap.computeBounds(it, true) }
    val span = max(1f, lifted)
    c.drawRect(
        bounds,
        Paint().apply {
            shader = LinearGradient(
                foldX, pivot, foldX - nx * span, pivot - ny * span,
                intArrayOf(0x47000000, 0x00000000, 0x1AFFFFFF, 0x00000000, 0x24000000),
                floatArrayOf(0f, 0.16f, 0.42f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
        },
    )
    c.restore()

    c.restore()
}

/** Plain slide: the current page moves out, the next one follows it in. */
fun drawSlide(c: Canvas, w: Float, h: Float, t: Float, forward: Boolean, shot: SheetDrawer, neighbor: SheetDrawer, paper: Int) {
    val sign = if (forward) -1f else 1f
    val moved = t * w
    c.save()
    c.clipRect(0f, 0f, w, h)
    c.drawColor(paper)
    c.save()
    c.translate(sign * moved, 0f)
    shot(c)
    c.restore()
    c.save()
    c.translate(sign * (moved - w), 0f)
    neighbor(c)
    c.restore()
    c.restore()
}

private fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

/**
 * Page turn of an open book (two pages side by side, [leftRect] and
 * [rightRect] meeting at the spine). Going forward, the right page lifts at
 * the spine and is laid over onto the left; its back shows the next left
 * page, the right page under it is the next right page. Going back runs the
 * same motion in reverse.
 *
 * [first] is the left page of the earlier of the two spreads (1-based).
 * [page] draws one page (with its marks) into a rectangle; pages outside the
 * book are left empty.
 */
fun drawBookCurl(
    c: Canvas,
    leftRect: RectF,
    rightRect: RectF,
    t: Float,
    forward: Boolean,
    pivotY: Float,
    tilt: Float,
    first: Int,
    page: (Canvas, Int, RectF) -> Unit,
    paper: Int,
) {
    val w = rightRect.width()
    val h = rightRect.height()
    if (w <= 0f || h <= 0f) return
    val q = if (forward) t else 1 - t
    // In the frame used below, the spine is at x = 0.
    val left = RectF(-w, 0f, 0f, h)
    val right = RectF(0f, 0f, w, h)
    val base = first // left page while the sheet is on the right
    val top = first + 1 // the sheet being turned, front side
    val back = first + 2 // the same sheet, back side
    val under = first + 3 // right page under the sheet

    c.save()
    c.translate(rightRect.left, rightRect.top)
    c.clipRect(RectF(-w, 0f, w, h))

    if (q >= 0.999f) {
        page(c, back, left)
        page(c, under, right)
        c.restore()
        return
    }
    page(c, base, left)
    page(c, under, right)
    if (q <= 0.001f) {
        page(c, top, right)
        c.restore()
        return
    }

    val foldX = w * (1 - q)
    val pivot = pivotY.coerceIn(0f, 1f) * h
    val reach = max(pivot, h - pivot)
    val maxLean = foldX / reach
    val lean = (tilt * min(1f, q * 4)).coerceIn(-maxLean, maxLean)
    val xTop = foldX - pivot * lean
    val xBottom = foldX + (h - pivot) * lean

    val far = Path().apply {
        moveTo(xTop, 0f); lineTo(w + h, 0f); lineTo(w + h, h); lineTo(xBottom, h); close()
        op(Path().apply { addRect(right, Path.Direction.CW) }, Path.Op.INTERSECT)
    }
    val keep = Path().apply {
        moveTo(0f, 0f); lineTo(xTop, 0f); lineTo(xBottom, h); lineTo(0f, h); close()
    }

    val len = sqrt(lean * lean + 1)
    val ux = lean / len
    val uy = 1 / len
    val nx = uy
    val ny = -ux
    val a = 2 * ux * ux - 1
    val b = 2 * ux * uy
    val d = 2 * uy * uy - 1
    val mirror = Matrix().apply {
        setValues(floatArrayOf(a, b, foldX - a * foldX - b * pivot, b, d, pivot - b * foldX - d * pivot, 0f, 0f, 1f))
    }
    val lifted = w - foldX
    val strength = min(1f, (1 - q) * 3) * min(1f, q * 6)

    // Shadow of the lifted sheet on the right page under it.
    c.save()
    c.clipPath(far)
    val reachOut = max(12f, min(lifted, w * 0.3f))
    c.drawRect(
        right,
        Paint().apply {
            shader = LinearGradient(
                foldX, pivot, foldX + nx * reachOut, pivot + ny * reachOut,
                argb(0.38f * strength, 0), 0, Shader.TileMode.CLAMP,
            )
        },
    )
    c.restore()

    // The part of the sheet still lying on the right.
    c.save()
    c.clipPath(keep)
    page(c, top, right)
    c.restore()

    val flap = Path(far).apply { transform(mirror) }

    // Shadow of the folded part.
    c.save()
    c.clipPath(keep)
    c.drawPath(
        flap,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(0.32f * strength, 0)
            maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        },
    )
    c.restore()

    // The back of the sheet: the next left page, readable, landing where it
    // will lie. (Mirrored at the spine, then folded: two mirrors, no mirror.)
    c.save()
    c.clipPath(flap)
    c.drawRect(flap.computeBoundsRect(), Paint().apply { color = paper })
    c.concat(mirror)
    c.scale(-1f, 1f)
    page(c, back, left)
    c.restore()

    // Roundness of the bend.
    c.save()
    c.clipPath(flap)
    val span = max(1f, lifted)
    c.drawRect(
        flap.computeBoundsRect(),
        Paint().apply {
            shader = LinearGradient(
                foldX, pivot, foldX - nx * span, pivot - ny * span,
                intArrayOf(0x47000000, 0x00000000, 0x1AFFFFFF, 0x00000000, 0x24000000),
                floatArrayOf(0f, 0.16f, 0.42f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
        },
    )
    c.restore()

    c.restore()
}

private fun Path.computeBoundsRect(): RectF = RectF().also { computeBounds(it, true) }
