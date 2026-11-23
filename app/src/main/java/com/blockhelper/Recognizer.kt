package com.blockhelper

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A screenshot as ARGB pixels. */
class Frame(val w: Int, val h: Int, val px: IntArray) {
    fun at(x: Int, y: Int): Int = px[y * w + x]
}

/** Where the 8x8 grid is on screen. left/top are the inner edge of the grid. */
class BoardGeom(
    val left: Float, val top: Float, val cellW: Float, val cellH: Float,
    val outerLeft: Int, val outerTop: Int, val outerRight: Int, val outerBottom: Int,
) {
    val right: Float get() = left + 8 * cellW
    val bottom: Float get() = top + 8 * cellH
    val size: Float get() = 8 * cellW
    fun cellX(c: Float) = left + c * cellW
    fun cellY(r: Float) = top + r * cellH
}

class TrayPiece(
    val slot: Int, val piece: Piece,
    val left: Int, val top: Int, val right: Int, val bottom: Int, val cellPx: Float,
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

class Recognition(
    val geom: BoardGeom,
    val board: Long,
    val pieces: Array<TrayPiece?>,
    val bg: IntArray,
    val emptyRef: IntArray,
    val trayTop: Int,
    val trayBottom: Int,
) {
    fun pieceArray(): Array<Piece?> = Array(3) { pieces[it]?.piece }

    /** Changes when the board or the tray changes; used to wait for animations to settle. */
    fun signature(): String = buildString {
        append(java.lang.Long.toHexString(board))
        for (p in pieces) {
            append('|')
            if (p != null) append(p.piece.key()).append('@').append((p.cx / 12).roundToInt()).append(',').append((p.cy / 12).roundToInt())
        }
    }
}

class RecognizeResult(val rec: Recognition?, val error: String?)

object Recognizer {
    /** Tray block size relative to a board cell, measured from the game (64px vs 143.75px). */
    var trayRatio = 0.447f

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun lum(c: Int) = (r(c) + g(c) + b(c)) / 3

    private fun dist(c: Int, ref: IntArray) = max(abs(r(c) - ref[0]), max(abs(g(c) - ref[1]), abs(b(c) - ref[2])))

    /** True for the page background and its darker shadows (same hue, lower brightness). */
    fun bgLike(c: Int, bg: IntArray): Boolean {
        val bgSum = bg[0] + bg[1] + bg[2]
        if (bgSum == 0) return false
        val k = (r(c) + g(c) + b(c)).toFloat() / bgSum
        if (k <= 0.62f || k >= 1.06f) return false
        val dev = max(abs(r(c) - k * bg[0]), max(abs(g(c) - k * bg[1]), abs(b(c) - k * bg[2])))
        return dev < 12f
    }

    /** A pixel that belongs to a block (not background, not an empty board cell, not a grid line). */
    fun blockish(c: Int, rec: Recognition): Boolean = !bgLike(c, rec.bg) && dist(c, rec.emptyRef) > 45

    private fun median(v: IntArray): Int { v.sort(); return v[v.size / 2] }

    fun recognize(f: Frame): RecognizeResult {
        val W = f.w; val H = f.h
        if (W < 200 || H < 200) return RecognizeResult(null, "화면이 너무 작아요")

        // 1. Background colour: median of a strip near the left edge.
        val y0s = (H * 0.15).toInt(); val y1s = (H * 0.85).toInt(); val xs = max(1, (W * 0.02).toInt())
        val n = y1s - y0s
        val cr = IntArray(n); val cg = IntArray(n); val cb = IntArray(n)
        for (i in 0 until n) { val c = f.at(xs, y0s + i); cr[i] = r(c); cg[i] = g(c); cb[i] = b(c) }
        val bg = intArrayOf(median(cr), median(cg), median(cb))

        // 2. Longest run of non-background pixels on each row.
        val runLen = IntArray(H); val runStart = IntArray(H); val runEnd = IntArray(H); val nonBgCount = IntArray(H)
        for (y in 0 until H) {
            var best = 0; var bs = 0; var s = -1; var cnt = 0
            val base = y * W
            for (x in 0..W) {
                val v = x < W && dist(f.px[base + x], bg) > 28
                if (v) { cnt++; if (s < 0) s = x } else if (s >= 0) {
                    if (x - s > best) { best = x - s; bs = s }
                    s = -1
                }
            }
            runLen[y] = best; runStart[y] = bs; runEnd[y] = bs + best - 1; nonBgCount[y] = cnt
        }

        // 3. The board is the most square block of rows with a long run.
        val blocks = ArrayList<IntArray>() // [first, last]
        var cur: IntArray? = null
        for (y in 0 until H) {
            if (runLen[y] < 0.5 * W) continue
            val c = cur
            if (c != null && y - c[1] <= 4) c[1] = y else { cur = intArrayOf(y, y); blocks.add(cur) }
        }
        var bestBlk: IntArray? = null; var bestSq = Float.MAX_VALUE
        for (blk in blocks) {
            val rows = blk[1] - blk[0] + 1
            if (rows < 0.3 * W) continue
            val lens = IntArray(rows) { runLen[blk[0] + it] }
            val wMed = median(lens).toFloat()
            val sq = abs(rows / wMed - 1f)
            if (sq < bestSq) { bestSq = sq; bestBlk = blk }
        }
        val blk = bestBlk ?: return RecognizeResult(null, "보드를 찾지 못했어요")
        if (bestSq > 0.2f) return RecognizeResult(null, "보드 모양이 정사각형이 아니에요")
        val top = blk[0]; val bot = blk[1]
        val left = median(IntArray(bot - top + 1) { runStart[top + it] })
        val right = median(IntArray(bot - top + 1) { runEnd[top + it] })

        // 4. Inner edge of the grid: first dark pixel scanning inward from the frame.
        val maxScan = ((right - left) * 0.08).toInt()
        fun scan(lines: List<Int>, start: Int, step: Int, horizontal: Boolean): Int? {
            val found = ArrayList<Int>()
            for (t in lines) {
                for (k in 0 until maxScan) {
                    val q = start + k * step
                    if (q < 0 || q >= (if (horizontal) W else H)) break
                    val c = if (horizontal) f.at(q, t) else f.at(t, q)
                    if (lum(c) < 85) { found.add(q); break }
                }
            }
            if (found.size < 5) return null
            return median(found.toIntArray())
        }
        val fr = (0..8).map { 0.2f + it * 0.075f }
        val ys = fr.map { (top + it * (bot - top)).toInt() }
        val xs2 = fr.map { (left + it * (right - left)).toInt() }
        val iL = scan(ys, left, 1, true) ?: return RecognizeResult(null, "보드 왼쪽 경계를 찾지 못했어요")
        val iR = scan(ys, right, -1, true) ?: return RecognizeResult(null, "보드 오른쪽 경계를 찾지 못했어요")
        val iT = scan(xs2, top, 1, false) ?: return RecognizeResult(null, "보드 위쪽 경계를 찾지 못했어요")
        val iB = scan(xs2, bot, -1, false) ?: return RecognizeResult(null, "보드 아래쪽 경계를 찾지 못했어요")
        val cw = (iR - iL + 1) / 8f; val ch = (iB - iT + 1) / 8f
        if (abs(cw - ch) / cw > 0.05f) return RecognizeResult(null, "칸 크기가 맞지 않아요")
        if (cw * 8 < 0.4f * W) return RecognizeResult(null, "보드가 너무 작아요")
        val geom = BoardGeom(iL.toFloat(), iT.toFloat(), cw, ch, left, top, right, bot)

        // 5. Cell colours from a patch around each centre.
        val means = Array(64) { IntArray(3) }
        val half = max(2, (0.15f * cw).toInt())
        for (rr in 0 until 8) for (cc in 0 until 8) {
            val cx = (iL + (cc + 0.5f) * cw).toInt(); val cy = (iT + (rr + 0.5f) * ch).toInt()
            var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
            var y = cy - half
            while (y <= cy + half) {
                var x = cx - half
                while (x <= cx + half) {
                    val c = f.at(x, y); sr += r(c); sg += g(c); sb += b(c); cnt++
                    x += 2
                }
                y += 2
            }
            means[rr * 8 + cc] = intArrayOf((sr / cnt).toInt(), (sg / cnt).toInt(), (sb / cnt).toInt())
        }
        // Empty reference: darkest low-saturation cell, else darkest cell.
        fun sat(m: IntArray) = maxOf(m[0], m[1], m[2]) - minOf(m[0], m[1], m[2])
        fun lumOf(m: IntArray) = m[0] + m[1] + m[2]
        val neutral = means.filter { sat(it) < 35 }
        val emptyRef = (if (neutral.isNotEmpty()) neutral else means.toList()).minBy { lumOf(it) }
        var board = 0L
        for (i in 0 until 64) {
            val m = means[i]
            val d = maxOf(abs(m[0] - emptyRef[0]), abs(m[1] - emptyRef[1]), abs(m[2] - emptyRef[2]))
            if (d > 40) board = board or (1L shl i)
        }

        // 6. Tray: three equal slots under the board, stopping before any full-width banner.
        val B = cw * 8
        val ty0 = (iB + 0.08f * B).toInt()
        var ty1 = min(H, (iB + 0.55f * B).toInt())
        for (y in ty0 until ty1) if (nonBgCount[y] >= 0.6 * W) { ty1 = y - 3; break }
        val ts = trayRatio * cw
        val pieces = arrayOfNulls<TrayPiece>(3)
        if (ty1 - ty0 > ts) {
            for (k in 0 until 3) pieces[k] = traySlot(f, bg, k, (iL + k * B / 3).toInt(), (iL + (k + 1) * B / 3).toInt(), ty0, ty1, ts)
        }
        return RecognizeResult(Recognition(geom, board, pieces, bg, emptyRef, ty0, ty1), null)
    }

    private fun longestGroup(idx: List<Int>): List<Int> {
        if (idx.isEmpty()) return idx
        var best = ArrayList<Int>(); var cur = ArrayList<Int>()
        for (v in idx) {
            if (cur.isNotEmpty() && v - cur.last() > 3) { if (cur.size > best.size) best = cur; cur = ArrayList() }
            cur.add(v)
        }
        if (cur.size > best.size) best = cur
        return best
    }

    private fun traySlot(f: Frame, bg: IntArray, slot: Int, x0: Int, x1: Int, y0: Int, y1: Int, ts: Float): TrayPiece? {
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return null
        val mask = BooleanArray(w * h)
        val rowCnt = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!bgLike(f.at(x0 + x, y0 + y), bg)) { mask[y * w + x] = true; rowCnt[y]++ }
        }
        val rows = longestGroup((0 until h).filter { rowCnt[it] >= 3 })
        if (rows.size < 0.5f * ts) return null
        val ry0 = rows.first(); val ry1 = rows.last()
        val colCnt = IntArray(w)
        for (y in ry0..ry1) for (x in 0 until w) if (mask[y * w + x]) colCnt[x]++
        val cols = longestGroup((0 until w).filter { colCnt[it] >= 3 })
        if (cols.size < 0.5f * ts) return null
        val bx0 = cols.first(); val bx1 = cols.last()
        val pw = bx1 - bx0 + 1; val ph = ry1 - ry0 + 1
        val nw = (pw / ts).roundToInt().coerceIn(1, 5)
        val nh = (ph / ts).roundToInt().coerceIn(1, 5)
        val sw = pw.toFloat() / nw; val sh = ph.toFloat() / nh
        val cells = ArrayList<Pair<Int, Int>>()
        for (rr in 0 until nh) for (cc in 0 until nw) {
            val cy = ry0 + (rr + 0.5f) * sh; val cx = bx0 + (cc + 0.5f) * sw
            val hh = 0.25f * min(sw, sh)
            var on = 0; var tot = 0
            var y = (cy - hh).toInt()
            while (y <= (cy + hh).toInt()) {
                var x = (cx - hh).toInt()
                while (x <= (cx + hh).toInt()) {
                    if (x in 0 until w && y in 0 until h) { tot++; if (mask[y * w + x]) on++ }
                    x++
                }
                y++
            }
            if (tot > 0 && on * 2 > tot) cells.add(rr to cc)
        }
        val piece = Piece.fromCells(cells) ?: return null
        return TrayPiece(slot, piece, x0 + bx0, y0 + ry0, x0 + bx1, y0 + ry1, (sw + sh) / 2)
    }
}
