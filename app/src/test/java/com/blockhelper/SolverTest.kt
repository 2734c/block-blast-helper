package com.blockhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SolverTest {
    @Test
    fun clearsRowsAndColumns() {
        val b = TestImages.boardOf(
            "#######.",
            ".......#",
            ".......#",
            ".......#",
            ".......#",
            ".......#",
            ".......#",
            ".......#",
        )
        val res = Solver.applyMove(b, Piece.of("#"), 7, 0)
        assertEquals(2, res.lines)
        assertEquals(0L, res.board)
    }

    @Test
    fun matchesReferenceEngine() {
        val text = javaClass.classLoader!!.getResource("solver_cases.txt")!!.readText()
        var n = 0
        for (line in text.lines().filter { it.isNotBlank() }) {
            val (rowsS, piecesS, modeS, placedS, scoreS) = line.split(";")
            val rows = rowsS.split(",").map { it.toInt() }
            var board = 0L
            rows.forEachIndexed { r, v -> board = board or (v.toLong() shl (8 * r)) }
            val pieces: Array<Piece?> = piecesS.split(" ").map { if (it == "-") null else Piece.of(*it.split("/").toTypedArray()) }.toTypedArray()
            val plan = Solver.solve(board, pieces, if (modeS == "combo") Strategy.COMBO else Strategy.SAFE)
            assertEquals(line, placedS.toInt(), plan.placed)
            if (scoreS != "none") assertEquals(line, scoreS.toDouble(), plan.score, 1e-6)
            n++
        }
        assertEquals(40, n)
    }

    @Test
    fun screenshotPlan() {
        val board = TestImages.boardOf(".....###", ".....##.", "###..##.", "..##....", "..##....", "..##....", "####..##", "###...##")
        val p = Piece.of("##", "##", "##")
        val plan = Solver.solve(board, arrayOf(p, p, null))
        assertEquals(2, plan.placed)
        assertEquals(-44.3, plan.score, 1e-6)
    }

    @Test
    fun selfPlaySurvivesAndIsFast() {
        val shapes = listOf(
            arrayOf("#"), arrayOf("##"), arrayOf("#", "#"), arrayOf("###"), arrayOf("#", "#", "#"),
            arrayOf("####"), arrayOf("#", "#", "#", "#"), arrayOf("#####"), arrayOf("#", "#", "#", "#", "#"),
            arrayOf("##", "##"), arrayOf("###", "###", "###"), arrayOf("###", "###"), arrayOf("##", "##", "##"),
            arrayOf("##", "#."), arrayOf("##", ".#"), arrayOf("#.", "##"), arrayOf(".#", "##"),
            arrayOf("###", ".#."), arrayOf(".##", "##."), arrayOf("#.", "#.", "##"), arrayOf("###", "#..", "#.."),
        ).map { Piece.of(*it) }
        val rnd = Random(7)
        var board = 0L
        var turns = 0
        var worstMs = 0L
        while (turns < 60) {
            val ps: Array<Piece?> = Array(3) { shapes[rnd.nextInt(shapes.size)] }
            val t0 = System.currentTimeMillis()
            val plan = Solver.solve(board, ps)
            worstMs = maxOf(worstMs, System.currentTimeMillis() - t0)
            if (plan.placed < 3) break
            for (m in plan.moves) board = Solver.applyMove(board, ps[m.slot]!!, m.x, m.y).board
            turns++
        }
        assertTrue("survived only $turns turns", turns >= 30)
        assertTrue("slowest turn $worstMs ms", worstMs < 3000)
    }

    @Test
    fun worstCaseEmptyBoardSingles() {
        val one = Piece.of("#")
        val t0 = System.currentTimeMillis()
        val plan = Solver.solve(0L, arrayOf(one, Piece.of("##"), Piece.of("#", "#")))
        assertEquals(3, plan.placed)
        assertTrue(System.currentTimeMillis() - t0 < 5000)
    }
}
