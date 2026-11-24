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
        if (isBlank(f)) return RecognizeResult(null, "캡처된 화면이 비어 있어요(${W}×${H}). 게임 화면에서 다시 눌러주세요")

        // 1. Rough board square: a square block of rows that stand out from the background.
        //    Several background guesses are tried (edge strip, then the most common colours).
        var approx: IntArray? = null
        for (bg in backgroundCandidates(f)) {
            approx = boardByBackground(f, bg)
            if (approx != null) break
        }

        // 2. Exact grid from the 9 evenly spaced grid lines in each direction.
        val geom = (if (approx != null) gridNear(f, approx) else null)
            ?: gridGlobal(f)
            ?: approx?.let { innerByScan(f, it) }
            ?: return RecognizeResult(null, "보드를 찾지 못했어요(캡처 ${W}×${H}). 게임 화면에서 눌렀는지 확인하고, 계속 안 되면 진단 이미지를 보내주세요")
        val iL = geom.left; val iT = geom.top; val cw = geom.cellW; val ch = geom.cellH
        val iR = geom.right; val iB = geom.bottom

        // 3. Cell colours from a patch around each centre.
        val means = Array(64) { IntArray(3) }
        val half = max(2, (0.15f * cw).toInt())
        for (rr in 0 until 8) for (cc in 0 until 8) {
            val cx = (iL + (cc + 0.5f) * cw).toInt(); val cy = (iT + (rr + 0.5f) * ch).toInt()
            var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0
            var y = cy - half
            while (y <= cy + half) {
                var x = cx - half
                while (x <= cx + half) {
                    if (x in 0 until W && y in 0 until H) { val c = f.at(x, y); sr += r(c); sg += g(c); sb += b(c); cnt++ }
                    x += 2
                }
                y += 2
            }
            if (cnt == 0) return RecognizeResult(null, "보드가 화면 밖으로 나가 있어요")
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

        // 4. Tray: three equal slots under the board. The background is read just below the board,
        //    and the tray stops before any banner as wide as the board.
        val B = cw * 8
        val bg = stripMedian(f, iL.toInt(), iR.toInt(), (iB + 0.025f * B).toInt(), (iB + 0.07f * B).toInt())
            ?: return RecognizeResult(null, "보드 아래 공간을 찾지 못했어요")
        val ty0 = (iB + 0.08f * B).toInt()
        var ty1 = min(H, (iB + 0.55f * B).toInt())
        val bx0 = max(0, (iL - 0.05f * B).toInt()); val bx1 = min(W, (iR + 0.05f * B).toInt())
        for (y in ty0 until ty1) {
            var cnt = 0
            for (x in bx0 until bx1 step 2) if (dist(f.at(x, y), bg) > 28) cnt++
            if (cnt >= 0.9f * (bx1 - bx0) / 2) { ty1 = y - 3; break }
        }
        val ts = trayRatio * cw
        val pieces = arrayOfNulls<TrayPiece>(3)
        if (ty1 - ty0 > ts) {
            for (k in 0 until 3) {
                val sx0 = max(0, (iL + k * B / 3).toInt()); val sx1 = min(W, (iL + (k + 1) * B / 3).toInt())
                pieces[k] = traySlot(f, bg, k, sx0, sx1, ty0, ty1, ts)
            }
        }
        return RecognizeResult(Recognition(geom, board, pieces, bg, emptyRef, ty0, ty1), null)
    }

    /** A screenshot that is (almost) one colour, e.g. black when capture is blocked. */
    private fun isBlank(f: Frame): Boolean {
        val counts = HashMap<Int, Int>()
        var n = 0
        var i = 0
        while (i < f.px.size) { val c = f.px[i]; val q = ((r(c) shr 4) shl 8) or ((g(c) shr 4) shl 4) or (b(c) shr 4); counts[q] = (counts[q] ?: 0) + 1; n++; i += 97 }
        return (counts.values.maxOrNull() ?: 0) > 0.97f * n
    }

    private fun stripMedian(f: Frame, x0: Int, x1: Int, y0: Int, y1: Int): IntArray? {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in max(0, y0) until min(f.h, y1) step 2) for (x in max(0, x0) until min(f.w, x1) step 3) {
            val c = f.at(x, y); rs.add(r(c)); gs.add(g(c)); bs.add(b(c))
        }
        if (rs.size < 20) return null
        return intArrayOf(median(rs.toIntArray()), median(gs.toIntArray()), median(bs.toIntArray()))
    }

    /** Background guesses: the left edge strip, then the three most common colours on screen. */
    private fun backgroundCandidates(f: Frame): List<IntArray> {
        val out = ArrayList<IntArray>()
        val W = f.w; val H = f.h
        stripMedian(f, max(1, (W * 0.02).toInt()), max(2, (W * 0.02).toInt() + 1), (H * 0.15).toInt(), (H * 0.85).toInt())?.let { out.add(it) }
        val counts = HashMap<Int, IntArray>() // quantized colour -> [count, sumR, sumG, sumB]
        var i = 0
        while (i < f.px.size) {
            val c = f.px[i]
            val q = ((r(c) shr 3) shl 10) or ((g(c) shr 3) shl 5) or (b(c) shr 3)
            val e = counts.getOrPut(q) { IntArray(4) }
            e[0]++; e[1] += r(c); e[2] += g(c); e[3] += b(c)
            i += 37
        }
        for (e in counts.values.sortedByDescending { it[0] }.take(3)) {
            val col = intArrayOf(e[1] / e[0], e[2] / e[0], e[3] / e[0])
            if (out.none { dist((0xFF shl 24) or (it[0] shl 16) or (it[1] shl 8) or it[2], col) < 12 }) out.add(col)
        }
        return out
    }

    /** Returns [left, top, right, bottom] of a square block of rows that stand out from bg, or null. */
    private fun boardByBackground(f: Frame, bg: IntArray): IntArray? {
        val W = f.w; val H = f.h
        val minDim = min(W, H)
        val runLen = IntArray(H); val runStart = IntArray(H); val runEnd = IntArray(H)
        for (y in 0 until H) {
            var best = 0; var bs = 0; var s = -1
            val base = y * W
            for (x in 0..W) {
                val v = x < W && dist(f.px[base + x], bg) > 28
                if (v) { if (s < 0) s = x } else if (s >= 0) {
                    // Runs touching the screen edge are bars or panels, not the board.
                    if (x - s > best && s > 0 && x < W) { best = x - s; bs = s }
                    s = -1
                }
            }
            runLen[y] = best; runStart[y] = bs; runEnd[y] = bs + best - 1
        }
        val blocks = ArrayList<IntArray>()
        var cur: IntArray? = null
        for (y in 0 until H) {
            if (runLen[y] < 0.45f * minDim) continue
            val c = cur
            if (c != null && y - c[1] <= 4) c[1] = y else { cur = intArrayOf(y, y); blocks.add(cur) }
        }
        var bestBlk: IntArray? = null; var bestSq = Float.MAX_VALUE
        for (blk in blocks) {
            val rows = blk[1] - blk[0] + 1
            if (rows < 0.3f * minDim) continue
            val wMed = median(IntArray(rows) { runLen[blk[0] + it] }).toFloat()
            val sq = abs(rows / wMed - 1f)
            if (sq < bestSq) { bestSq = sq; bestBlk = blk }
        }
        val blk = bestBlk ?: return null
        if (bestSq > 0.2f) return null
        val top = blk[0]; val bot = blk[1]
        val left = median(IntArray(bot - top + 1) { runStart[top + it] })
        val right = median(IntArray(bot - top + 1) { runEnd[top + it] })
        return intArrayOf(left, top, right, bot)
    }

    private class Comb(val period: Float, val offset: Float, val score: Int, val weakest: Int)

    /** line: thin-line crossings; step: any strong brightness step. Both counted per column or row. */
    private class Profiles(val line: IntArray, val step: IntArray)

    /**
     * Best set of 9 evenly spaced grid lines (8 cells). The 7 inner lines must look like thin lines;
     * the 2 outer lines may be plain steps (in dark themes the frame and the border line can be the same colour).
     * A strong thin line one cell outside the set means the set is shifted by a cell, so it is penalised.
     */
    private fun bestComb(pr: Profiles, pMin: Float, pMax: Float, oMin: Float, oMax: Float, pStep: Float): Comb? {
        val n = pr.line.size
        fun win(a: IntArray, r: Int) = IntArray(n) { i -> var m = 0; for (d in -r..r) { val j = i + d; if (j in 0 until n && a[j] > m) m = a[j] }; m }
        // Outer lines may show only one of the two edges of the border line, so they get a wider window.
        val line = win(pr.line, 2); val step = win(pr.step, 6)
        var best: Comb? = null; var bestScore = Int.MIN_VALUE
        val v = IntArray(9)
        var p = pMin
        while (p <= pMax) {
            val o0 = max(0, oMin.toInt()); val o1 = min(oMax.toInt(), (n - 1 - 8 * p).toInt())
            for (o in o0..o1) {
                var inner = 0
                for (k in 1..7) { v[k] = line[(o + k * p).roundToInt()]; if (v[k] > inner) inner = v[k] }
                v[0] = min(inner, max(line[o], step[o]))
                val e = (o + 8 * p).roundToInt(); v[8] = min(inner, max(line[e], step[e]))
                var sum = 0; var weakest = Int.MAX_VALUE
                for (k in 0..8) { sum += v[k]; if (v[k] < weakest) weakest = v[k] }
                val before = (o - p).roundToInt(); val after = (o + 9 * p).roundToInt()
                val outside = (if (before >= 0) line[before] else 0) + (if (after < n) line[after] else 0)
                val score = sum + 3 * weakest - 2 * min(outside, 2 * inner)
                if (score > bestScore) { bestScore = score; best = Comb(p, o.toFloat(), sum, weakest) }
            }
            p += pStep
        }
        return best
    }

    /**
     * Per column (columns = true) or per row inside a box: how often a thin line crosses it (a brightness step
     * followed within a few pixels by a step the other way), and how often any strong step does.
     * A single step, like the edge of a black bar, counts only as a step.
     */
    private fun edgeProfile(f: Frame, x0: Int, y0: Int, x1: Int, y1: Int, columns: Boolean): Profiles {
        val W = f.w; val H = f.h
        val xa = max(0, x0); val xb = min(W - 1, x1); val ya = max(0, y0); val yb = min(H - 1, y1)
        val line = IntArray(if (columns) W else H); val step = IntArray(if (columns) W else H)
        val outerA = if (columns) ya else xa; val outerB = if (columns) yb else xb
        val innerA = if (columns) xa else ya; val innerB = if (columns) xb else yb
        val len = innerB - innerA
        if (len <= 2) return Profiles(line, step)
        val sign = IntArray(len)
        var o = outerA
        while (o < outerB) {
            for (i in 0 until len) {
                val a = if (columns) f.at(innerA + i, o) else f.at(o, innerA + i)
                val b = if (columns) f.at(innerA + i + 1, o) else f.at(o, innerA + i + 1)
                val d = lum(b) - lum(a)
                sign[i] = if (d >= 10) 1 else if (d <= -10) -1 else 0
            }
            for (i in 0 until len) {
                val sgn = sign[i]
                if (sgn == 0) continue
                step[innerA + i]++
                var j = i + 1
                while (j < len && j <= i + 12) { if (sign[j] == -sgn) { line[innerA + i]++; line[innerA + j]++; break }; j++ }
            }
            o += 2
        }
        return Profiles(line, step)
    }

    /** Fits the grid by alternating column and row comb fits, then checks every line is visible. */
    private fun fitGrid(f: Frame, firstCols: Comb, yHint0: Int, yHint1: Int): BoardGeom? {
        var cols = firstCols
        val rowsProf = edgeProfile(f, cols.offset.toInt(), yHint0, (cols.offset + 8 * cols.period).toInt(), yHint1, false)
        var rows = bestComb(rowsProf, cols.period * 0.93f, cols.period * 1.07f, yHint0.toFloat(), yHint1.toFloat(), 0.5f) ?: return null
        repeat(2) {
            val cp = edgeProfile(f, (cols.offset - rows.period).toInt(), rows.offset.toInt(), (cols.offset + 9 * rows.period).toInt(), (rows.offset + 8 * rows.period).toInt(), true)
            cols = bestComb(cp, rows.period * 0.97f, rows.period * 1.03f, cols.offset - rows.period / 2, cols.offset + rows.period / 2, 0.1f) ?: return null
            val rp = edgeProfile(f, cols.offset.toInt(), (rows.offset - cols.period).toInt(), (cols.offset + 8 * cols.period).toInt(), (rows.offset + 9 * cols.period).toInt(), false)
            rows = bestComb(rp, cols.period * 0.98f, cols.period * 1.02f, rows.offset - cols.period / 2, rows.offset + cols.period / 2, 0.1f) ?: return null
        }
        // Each grid line must show along most of its length (profiles sample every 2nd pixel).
        if (cols.weakest < 0.3f * 4 * rows.period || rows.weakest < 0.3f * 4 * cols.period) return null
        if (abs(cols.period - rows.period) / cols.period > 0.04f) return null
        // The fit may sit on either edge of the grid lines; move it to the lines' centres.
        val cpF = edgeProfile(f, cols.offset.toInt() - 10, rows.offset.toInt(), (cols.offset + 8 * cols.period).toInt() + 10, (rows.offset + 8 * rows.period).toInt(), true)
        val rpF = edgeProfile(f, cols.offset.toInt(), rows.offset.toInt() - 10, (cols.offset + 8 * cols.period).toInt(), (rows.offset + 8 * rows.period).toInt() + 10, false)
        val (l, cw) = lineCentreFit(cpF.line, cols)
        val (t, ch) = lineCentreFit(rpF.line, rows)
        val pad = (0.12f * cw).toInt()
        return BoardGeom(l, t, cw, ch, (l - pad).toInt(), (t - pad).toInt(), (l + 8 * cw + pad).toInt(), (t + 8 * ch + pad).toInt())
    }

    /**
     * Refines a comb to the centres of the inner grid lines: the mass centre of each line's two edges,
     * then a straight-line fit of centre against line number. Returns (offset, period).
     */
    private fun lineCentreFit(line: IntArray, c: Comb): Pair<Float, Float> {
        val r = max(3, (0.06f * c.period).roundToInt())
        val ks = ArrayList<Float>(); val xs = ArrayList<Float>()
        for (k in 1..7) {
            val pos = (c.offset + k * c.period).roundToInt()
            var m = 0L; var w = 0L
            for (x in pos - r..pos + r) if (x in line.indices) { m += x.toLong() * line[x]; w += line[x] }
            // Profile index i is the step between pixels i and i+1, hence the half pixel.
            if (w > 0) { ks.add(k.toFloat()); xs.add(m.toFloat() / w + 0.5f) }
        }
        if (ks.size < 4) return c.offset to c.period
        val mk = ks.average().toFloat(); val mx = xs.average().toFloat()
        var num = 0f; var den = 0f
        for (i in ks.indices) { num += (ks[i] - mk) * (xs[i] - mx); den += (ks[i] - mk) * (ks[i] - mk) }
        val period = num / den
        if (abs(period - c.period) > 0.05f * c.period) return c.offset to c.period
        return (mx - period * mk) to period
    }

    private fun gridNear(f: Frame, a: IntArray): BoardGeom? {
        val (left, top, right, bot) = a
        val outer = (right - left).toFloat()
        val m = (0.06f * outer).toInt()
        val cp = edgeProfile(f, left - m, top, right + m, bot, true)
        val cols = bestComb(cp, outer / 8 * 0.85f, outer / 8 * 1.01f, (left - m).toFloat(), (left + 0.1f * outer), 0.5f) ?: return null
        return fitGrid(f, cols, top - m, bot + m)
    }

    private fun gridGlobal(f: Frame): BoardGeom? {
        val minDim = min(f.w, f.h).toFloat()
        val cp = edgeProfile(f, 0, 0, f.w - 1, f.h - 1, true)
        val cols = bestComb(cp, 0.05f * minDim, 0.13f * minDim, 0f, f.w.toFloat(), 0.5f) ?: return null
        return fitGrid(f, cols, 0, f.h - 1)
    }

    /** Older fallback: first dark pixel scanning inward from the rough square's edges. */
    private fun innerByScan(f: Frame, a: IntArray): BoardGeom? {
        val W = f.w; val H = f.h
        val (left, top, right, bot) = a
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
        val xs = fr.map { (left + it * (right - left)).toInt() }
        val iL = scan(ys, left, 1, true) ?: return null
        val iR = scan(ys, right, -1, true) ?: return null
        val iT = scan(xs, top, 1, false) ?: return null
        val iB = scan(xs, bot, -1, false) ?: return null
        val cw = (iR - iL + 1) / 8f; val ch = (iB - iT + 1) / 8f
        if (abs(cw - ch) / cw > 0.05f) return null
        return BoardGeom(iL.toFloat(), iT.toFloat(), cw, ch, left, top, right, bot)
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
