package com.blockhelper

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a block that is being dragged, by comparing a screenshot taken mid-drag with one taken before.
 * Pixels that became block-coloured are evidence; pixels that were already block-coloured (placed blocks)
 * are unknown, because a dragged block of the same colour over them is invisible to a diff.
 *
 * Many games also draw a snapped preview where the block would land. If two copies of the block are found,
 * the one that is not aligned to the grid is the dragged block.
 */
object DragProbe {
    /**
     * aligned = true means the match sits exactly on the grid (a landing preview, or the block itself snapped):
     * landX/landY is then the cell where the block would land, and corrections should move by whole cells.
     */
    class Found(
        val cx: Float, val cy: Float, val scale: Float, val score: Float, val coverage: Float,
        val aligned: Boolean, val landX: Int, val landY: Int,
    )

    private const val G = 4 // pixels per sampling block
    var debug = false

    private class Cand(val cx: Float, val cy: Float, val s: Float, val score: Float, val coverage: Float, val onD: Int, val onArea: Int)

    private class Grid(val x0: Int, val y0: Int, val gw: Int, val gh: Int) {
        val d = IntArray(gw * gh) // samples that became block-coloured
        val u = IntArray(gw * gh) // samples that were block-coloured already (unknown)
        val dI = IntArray((gw + 1) * (gh + 1))
        val uI = IntArray((gw + 1) * (gh + 1))
        val perBlock = (G / 2) * (G / 2)

        fun integrate() {
            val iw = gw + 1
            for (by in 0 until gh) {
                var rd = 0; var ru = 0
                for (bx in 0 until gw) {
                    rd += d[by * gw + bx]; ru += u[by * gw + bx]
                    dI[(by + 1) * iw + bx + 1] = dI[by * iw + bx + 1] + rd
                    uI[(by + 1) * iw + bx + 1] = uI[by * iw + bx + 1] + ru
                }
            }
        }

        fun bx(px: Float) = ((px - x0) / G).roundToInt().coerceIn(0, gw)
        fun by(py: Float) = ((py - y0) / G).roundToInt().coerceIn(0, gh)

        fun sum(I: IntArray, x0p: Float, y0p: Float, x1p: Float, y1p: Float): Int {
            val a = bx(x0p); val b = bx(x1p); val c = by(y0p); val e = by(y1p)
            if (b <= a || e <= c) return 0
            val iw = gw + 1
            return I[e * iw + b] - I[c * iw + b] - I[e * iw + a] + I[c * iw + a]
        }

        fun area(x0p: Float, y0p: Float, x1p: Float, y1p: Float): Int {
            val a = bx(x0p); val b = bx(x1p); val c = by(y0p); val e = by(y1p)
            if (b <= a || e <= c) return 0
            return (b - a) * (e - c) * perBlock
        }
    }

    fun locate(before: Frame, after: Frame, rec: Recognition, piece: Piece, expX: Float, expY: Float, radiusCells: Float): Found? {
        if (before.w != after.w || before.h != after.h) return null
        val cw = rec.geom.cellW
        val reach = (radiusCells + max(piece.w, piece.h) * 0.6f + 1.5f) * cw
        val rx0 = max(0, (expX - reach).toInt()); val rx1 = min(after.w, (expX + reach).toInt())
        val ry0 = max(0, (expY - reach).toInt()); val ry1 = min(after.h, (expY + reach).toInt())
        val grid = Grid(rx0, ry0, (rx1 - rx0) / G, (ry1 - ry0) / G)
        if (grid.gw < 4 || grid.gh < 4) return null
        for (by in 0 until grid.gh) for (bx in 0 until grid.gw) {
            var d = 0; var u = 0
            var y = ry0 + by * G
            while (y < ry0 + by * G + G) {
                var x = rx0 + bx * G
                while (x < rx0 + bx * G + G) {
                    if (Recognizer.blockish(before.at(x, y), rec)) u++
                    else if (Recognizer.blockish(after.at(x, y), rec)) d++
                    x += 2
                }
                y += 2
            }
            grid.d[by * grid.gw + bx] = d; grid.u[by * grid.gw + bx] = u
        }
        grid.integrate()

        val first = search(grid, piece, cw, expX, expY, radiusCells * cw) ?: return null
        if (debug) println("first ${first.cx},${first.cy} s=${first.s} score=${first.score} cov=${first.coverage} err=${gridError(first.cx, first.cy, piece, rec)}")

        // Explain away the first match (its cells become unknown) and look for a second copy.
        val cs = first.s * cw
        val tlx = first.cx - piece.w * cs / 2; val tly = first.cy - piece.h * cs / 2
        for ((r, c) in piece.cells()) {
            val a = grid.bx(tlx + c * cs - G); val b = grid.bx(tlx + (c + 1) * cs + G)
            val c0 = grid.by(tly + r * cs - G); val c1 = grid.by(tly + (r + 1) * cs + G)
            for (yy in c0 until c1) for (xx in a until b) {
                val i = yy * grid.gw + xx
                grid.u[i] += grid.d[i]; grid.d[i] = 0
            }
        }
        grid.integrate()
        val second = search(grid, piece, cw, expX, expY, radiusCells * cw)
            ?.takeIf { it.onD >= 0.2f * it.onArea && hypot(it.cx - first.cx, it.cy - first.cy) > 0.15f * cw }
        if (debug && second != null) println("second ${second.cx},${second.cy} s=${second.s} score=${second.score} cov=${second.coverage} err=${gridError(second.cx, second.cy, piece, rec)}")

        // A grid-aligned copy tells us the landing cell exactly, so prefer it.
        var chosen = first
        if (second != null && gridError(second.cx, second.cy, piece, rec) < gridError(first.cx, first.cy, piece, rec)) chosen = second
        val g = rec.geom
        val aligned = gridError(chosen.cx, chosen.cy, piece, rec) < 0.12f && abs(chosen.s - 1f) <= 0.06f
        val lx = ((chosen.cx - piece.w * g.cellW / 2 - g.left) / g.cellW).roundToInt()
        val ly = ((chosen.cy - piece.h * g.cellH / 2 - g.top) / g.cellH).roundToInt()
        return Found(chosen.cx, chosen.cy, chosen.s, chosen.score, chosen.coverage, aligned, lx, ly)
    }

    private fun search(grid: Grid, piece: Piece, cw: Float, expX: Float, expY: Float, radius: Float): Cand? {
        val on = Array(piece.h) { r -> BooleanArray(piece.w) { c -> (piece.rows[r] shr c) and 1 == 1 } }
        fun evalAt(cx: Float, cy: Float, s: Float): Cand? {
            val cs = s * cw
            val tlx = cx - piece.w * cs / 2; val tly = cy - piece.h * cs / 2
            val ins = 0.05f * cs
            var score = 0f; var onKnown = 0; var onD = 0; var onArea = 0; var offD = 0
            for (r in -1..piece.h) for (c in -1..piece.w) {
                val x0 = tlx + c * cs + ins; val y0 = tly + r * cs + ins
                val x1 = tlx + (c + 1) * cs - ins; val y1 = tly + (r + 1) * cs - ins
                val isOn = r in 0 until piece.h && c in 0 until piece.w && on[r][c]
                val d = grid.sum(grid.dI, x0, y0, x1, y1)
                if (isOn) {
                    val a = grid.area(x0, y0, x1, y1); val u = grid.sum(grid.uI, x0, y0, x1, y1)
                    val known = max(0, a - u)
                    score += d - 0.8f * (known - d)
                    onKnown += known; onD += d; onArea += a
                } else offD += d
            }
            score -= 0.8f * offD
            if (onArea == 0 || onKnown < 0.25f * onArea || onD < 0.6f * onKnown) return null
            // Mild preference for the board's own cell size.
            score *= 1f - 0.5f * abs(s - 1f)
            return Cand(cx, cy, s, score, onD.toFloat() / onKnown, onD, onArea)
        }

        val scales = floatArrayOf(0.9f, 0.95f, 1.0f, 1.05f, 1.1f)
        val coarse = 2f * G
        var best: Cand? = null
        var y = expY - radius
        while (y <= expY + radius) {
            var x = expX - radius
            while (x <= expX + radius) {
                if (hypot(x - expX, y - expY) <= radius) {
                    for (s in scales) evalAt(x, y, s)?.let { if (best == null || it.score > best!!.score) best = it }
                }
                x += coarse
            }
            y += coarse
        }
        var b = best ?: return null
        val c0 = b
        for (s in floatArrayOf(c0.s - 0.025f, c0.s, c0.s + 0.025f)) {
            var dy = -coarse
            while (dy <= coarse) {
                var dx = -coarse
                while (dx <= coarse) {
                    evalAt(c0.cx + dx, c0.cy + dy, s)?.let { if (it.score > b.score) b = it }
                    dx += G / 2f
                }
                dy += G / 2f
            }
        }
        return if (b.score > 0f) b else null
    }

    /** Distance (in cells) of a block's top-left corner from the nearest grid point. */
    fun gridError(cx: Float, cy: Float, piece: Piece, rec: Recognition): Float {
        val g = rec.geom
        val tlx = (cx - piece.w * g.cellW / 2 - g.left) / g.cellW
        val tly = (cy - piece.h * g.cellH / 2 - g.top) / g.cellH
        return abs(tlx - tlx.roundToInt()) + abs(tly - tly.roundToInt())
    }
}
