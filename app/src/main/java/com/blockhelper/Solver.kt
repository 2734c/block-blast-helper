package com.blockhelper

import java.lang.Long.bitCount

/**
 * Board: a Long with bit (row * 8 + col) set for a filled cell.
 * Piece: row masks where bit c of rows[r] is cell (r, c).
 */
class Piece(val h: Int, val w: Int, val rows: IntArray) {
    val n: Int = rows.sumOf { Integer.bitCount(it) }

    /** Board mask for every position the piece fits inside the 8x8 grid, indexed by y * 8 + x (0 if out of bounds). */
    val masks: LongArray = LongArray(64).also { arr ->
        for (y in 0..8 - h) for (x in 0..8 - w) {
            var m = 0L
            for (r in 0 until h) m = m or (rows[r].toLong() shl (x + 8 * (y + r)))
            arr[y * 8 + x] = m
        }
    }

    fun cells(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (r in 0 until h) for (c in 0 until w) if ((rows[r] shr c) and 1 == 1) out.add(r to c)
        return out
    }

    fun key(): String = rows.joinToString("/") { r -> (0 until w).joinToString("") { c -> if ((r shr c) and 1 == 1) "#" else "." } }

    override fun equals(other: Any?): Boolean = other is Piece && other.h == h && other.w == w && other.rows.contentEquals(rows)
    override fun hashCode(): Int = rows.contentHashCode() * 31 + w

    companion object {
        fun fromCells(cells: Collection<Pair<Int, Int>>): Piece? {
            if (cells.isEmpty()) return null
            val minR = cells.minOf { it.first }; val minC = cells.minOf { it.second }
            val h = cells.maxOf { it.first } - minR + 1
            val w = cells.maxOf { it.second } - minC + 1
            if (h > 8 || w > 8) return null
            val rows = IntArray(h)
            for ((r, c) in cells) rows[r - minR] = rows[r - minR] or (1 shl (c - minC))
            return Piece(h, w, rows)
        }

        fun of(vararg lines: String): Piece {
            val cells = ArrayList<Pair<Int, Int>>()
            lines.forEachIndexed { r, line -> line.forEachIndexed { c, ch -> if (ch == '#') cells.add(r to c) } }
            return fromCells(cells)!!
        }
    }
}

data class Move(val slot: Int, val x: Int, val y: Int)

class Plan(val moves: List<Move>, val placed: Int, val total: Int, val score: Double, val finals: Int)

class MoveResult(val board: Long, val rowMask: Int, val colMask: Int) {
    val lines: Int get() = Integer.bitCount(rowMask) + Integer.bitCount(colMask)
}

enum class Strategy { SAFE, COMBO }

object Solver {
    private class Weights(
        val filled: Double, val rough: Double, val hole1: Double, val hole2: Double, val hole3: Double,
        val fit: Double, val clear: Double, val multi: Double, val clearMove: Double,
    )

    // Tuned by self-play simulation (see README).
    private val SAFE = Weights(0.3, 1.5, 10.0, 5.0, 2.0, 2.0, 4.0, 3.0, 0.0)
    private val COMBO = Weights(0.3, 1.5, 10.0, 5.0, 2.0, 2.0, 6.0, 5.0, 14.0)

    /** Shapes whose last free spot we try not to lose, with the penalty for losing it. */
    private val FIT: List<Pair<Piece, Double>> = listOf(
        Piece.of("###", "###", "###") to 22.0,
        Piece.of("#####") to 9.0, Piece.of("#", "#", "#", "#", "#") to 9.0,
        Piece.of("###", "###") to 6.0, Piece.of("##", "##", "##") to 6.0,
        Piece.of("##", "##") to 8.0,
        Piece.of("####") to 3.0, Piece.of("#", "#", "#", "#") to 3.0,
        Piece.of("###", "#..", "#..") to 3.0, Piece.of("###", "..#", "..#") to 3.0,
        Piece.of("#..", "#..", "###") to 3.0, Piece.of("..#", "..#", "###") to 3.0,
        Piece.of("###") to 3.0, Piece.of("#", "#", "#") to 3.0,
        Piece.of("#.", "#.", "##") to 1.0, Piece.of(".#", ".#", "##") to 1.0,
        Piece.of("##", "#.", "#.") to 1.0, Piece.of("##", ".#", ".#") to 1.0,
        Piece.of("###", "#..") to 1.0, Piece.of("###", "..#") to 1.0,
        Piece.of("#..", "###") to 1.0, Piece.of("..#", "###") to 1.0,
        Piece.of("###", ".#.") to 1.0, Piece.of(".#.", "###") to 1.0,
        Piece.of("#.", "##", "#.") to 1.0, Piece.of(".#", "##", ".#") to 1.0,
        Piece.of(".##", "##.") to 1.0, Piece.of("##.", ".##") to 1.0,
        Piece.of("#.", "##", ".#") to 1.0, Piece.of(".#", "##", "#.") to 1.0,
    )

    private const val NOT_COL0 = -0x0101010101010102L // 0xFEFEFEFEFEFEFEFE
    private const val NOT_COL7 = 0x7F7F7F7F7F7F7F7FL
    private const val NOT_ROW7 = 0x00FFFFFFFFFFFFFFL

    fun isFilled(board: Long, r: Int, c: Int): Boolean = (board ushr (r * 8 + c)) and 1L == 1L

    fun canPlace(board: Long, p: Piece, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x + p.w > 8 || y + p.h > 8) return false
        return board and p.masks[y * 8 + x] == 0L
    }

    fun applyMove(board: Long, p: Piece, x: Int, y: Int): MoveResult {
        val nb = board or p.masks[y * 8 + x]
        var rowMask = 0
        var colMask = 0xFF
        for (r in 0 until 8) {
            val row = ((nb ushr (8 * r)) and 0xFF).toInt()
            if (row == 0xFF) rowMask = rowMask or (1 shl r)
            colMask = colMask and row
        }
        if (rowMask == 0 && colMask == 0) return MoveResult(nb, 0, 0)
        var clear = 0L
        for (r in 0 until 8) {
            var rowBits = colMask.toLong()
            if ((rowMask shr r) and 1 == 1) rowBits = 0xFFL
            clear = clear or (rowBits shl (8 * r))
        }
        return MoveResult(nb and clear.inv(), rowMask, colMask)
    }

    private fun fits(board: Long, p: Piece): Boolean {
        for (y in 0..8 - p.h) for (x in 0..8 - p.w) if (board and p.masks[y * 8 + x] == 0L) return true
        return false
    }

    private fun evaluate(board: Long, wt: Weights): Double {
        val filled = bitCount(board)
        val rough = bitCount((board xor (board ushr 1)) and NOT_COL7) + bitCount((board xor (board ushr 8)) and NOT_ROW7)
        var small = 0.0
        var empty = board.inv()
        while (empty != 0L) {
            var region = empty and -empty
            while (true) {
                val grow = (region or ((region shl 1) and NOT_COL0) or ((region ushr 1) and NOT_COL7) or
                    (region shl 8) or (region ushr 8)) and board.inv()
                if (grow == region) break
                region = grow
            }
            when (bitCount(region)) {
                1 -> small += wt.hole1
                2 -> small += wt.hole2
                3 -> small += wt.hole3
            }
            empty = empty and region.inv()
        }
        var unfit = 0.0
        for ((p, w) in FIT) if (!fits(board, p)) unfit += w
        return -filled * wt.filled - rough * wt.rough - small - unfit * wt.fit
    }

    private class State(val board: Long, val rem: Int, val moves: List<Move>, val gain: Double)

    /** Tries every order and position of the pieces. Null entries are empty tray slots. */
    fun solve(board: Long, pieces: Array<Piece?>, strategy: Strategy = Strategy.SAFE): Plan {
        val wt = if (strategy == Strategy.COMBO) COMBO else SAFE
        val idx = pieces.indices.filter { pieces[it] != null }
        var fullRem = 0
        for (i in idx) fullRem = fullRem or (1 shl i)

        // One map per remaining-pieces mask, keyed by board.
        var layer: Array<HashMap<Long, State>> = Array(8) { HashMap() }
        layer[fullRem][board] = State(board, fullRem, emptyList(), 0.0)
        var depth = 0
        for (d in idx.indices) {
            val next: Array<HashMap<Long, State>> = Array(8) { HashMap() }
            var any = false
            for (map in layer) for (st in map.values) {
                for (i in idx) {
                    if ((st.rem shr i) and 1 == 0) continue
                    val p = pieces[i]!!
                    val rem = st.rem and (1 shl i).inv()
                    val target = next[rem]
                    for (y in 0..8 - p.h) for (x in 0..8 - p.w) {
                        val m = p.masks[y * 8 + x]
                        if (st.board and m != 0L) continue
                        val res = applyMove(st.board, p, x, y)
                        val l = res.lines
                        val gain = st.gain + if (l > 0) l * wt.clear + (l - 1) * l * wt.multi + wt.clearMove else 0.0
                        val prev = target[res.board]
                        if (prev == null || prev.gain < gain) {
                            target[res.board] = State(res.board, rem, st.moves + Move(i, x, y), gain)
                            any = true
                        }
                    }
                }
            }
            if (!any) break
            layer = next
            depth = d + 1
        }

        val cache = HashMap<Long, Double>()
        var best: State? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var finals = 0
        for (map in layer) for (st in map.values) {
            finals++
            val e = cache.getOrPut(st.board) { evaluate(st.board, wt) }
            val s = e + st.gain
            if (s > bestScore) { bestScore = s; best = st }
        }
        return Plan(best?.moves ?: emptyList(), depth, idx.size, bestScore, finals)
    }
}
