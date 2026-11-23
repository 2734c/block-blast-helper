package com.blockhelper

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DragProbeTest {
    private val green = 0xFF5A8D4B.toInt()
    private val outline = 0xFF102713.toInt()

    /** Draws a piece the way the game draws blocks: filled squares with a dark outline. */
    private fun drawPiece(px: IntArray, w: Int, piece: Piece, cx: Float, cy: Float, cell: Float) {
        val tlx = cx - piece.w * cell / 2; val tly = cy - piece.h * cell / 2
        for ((r, c) in piece.cells()) {
            val x0 = (tlx + c * cell).toInt(); val y0 = (tly + r * cell).toInt()
            val x1 = (tlx + (c + 1) * cell).toInt(); val y1 = (tly + (r + 1) * cell).toInt()
            for (y in y0 until y1) for (x in x0 until x1) {
                if (x < 0 || y < 0 || x >= w || y >= px.size / w) continue
                val edge = x - x0 < 3 || x1 - x <= 3 || y - y0 < 3 || y1 - y <= 3
                px[y * w + x] = if (edge) outline else green
            }
        }
    }

    private fun setup(): Triple<Frame, Recognition, Piece> {
        val before = TestImages.load("screen_1440x2304.png")
        val rec = Recognizer.recognize(before).rec!!
        return Triple(before, rec, rec.pieces[0]!!.piece)
    }

    private fun vacateSlot(px: IntArray, w: Int, rec: Recognition) {
        val tp = rec.pieces[0]!!
        val bg = (0xFF shl 24) or (rec.bg[0] shl 16) or (rec.bg[1] shl 8) or rec.bg[2]
        for (y in tp.top - 2..tp.bottom + 14) for (x in tp.left - 2..tp.right + 14) px[y * w + x] = bg
    }

    @Test
    fun findsDraggedPieceOverBoard() {
        val (before, rec, piece) = setup()
        val px = before.px.copyOf()
        vacateSlot(px, before.w, rec)
        val trueX = 655.3f; val trueY = 820.6f
        drawPiece(px, before.w, piece, trueX, trueY, rec.geom.cellW)
        val found = DragProbe.locate(before, Frame(before.w, before.h, px), rec, piece, trueX + 70f, trueY - 110f, 5f)
        assertNotNull(found)
        assertTrue("found ${found!!.cx},${found.cy}", !found.aligned && abs(found.cx - trueX) < 10f && abs(found.cy - trueY) < 10f)
    }

    @Test
    fun usesSnappedPreviewWhenBothVisible() {
        val (before, rec, piece) = setup()
        val px = before.px.copyOf()
        vacateSlot(px, before.w, rec)
        val g = rec.geom
        // Preview snapped to columns 5-6, rows 3-5 (empty on this board).
        val ghostX = g.left + 6 * g.cellW; val ghostY = g.top + 4.5f * g.cellH
        drawPiece(px, before.w, piece, ghostX, ghostY, g.cellW)
        val trueX = ghostX - 1.45f * g.cellW; val trueY = ghostY - 1.6f * g.cellH
        drawPiece(px, before.w, piece, trueX, trueY, g.cellW)
        val found = DragProbe.locate(before, Frame(before.w, before.h, px), rec, piece, trueX, trueY + 60f, 5f)
        assertNotNull(found)
        // The snapped preview gives the landing cell exactly.
        assertTrue("found ${found!!.cx},${found.cy}", found.aligned && found.landX == 5 && found.landY == 3)
    }

    @Test
    fun overlappingPreviewStillFindsDraggedPiece() {
        val (before, rec, piece) = setup()
        val px = before.px.copyOf()
        vacateSlot(px, before.w, rec)
        val g = rec.geom
        val ghostX = g.left + 6 * g.cellW; val ghostY = g.top + 4.5f * g.cellH
        drawPiece(px, before.w, piece, ghostX, ghostY, g.cellW)
        val trueX = ghostX - 0.3f * g.cellW; val trueY = ghostY - 0.35f * g.cellH
        drawPiece(px, before.w, piece, trueX, trueY, g.cellW)
        val found = DragProbe.locate(before, Frame(before.w, before.h, px), rec, piece, trueX + 40f, trueY + 90f, 5f)
        assertNotNull(found)
        // Either answer leads to the right drop: the preview by whole cells, the dragged block continuously.
        val onPreview = found!!.aligned && found.landX == 5 && found.landY == 3
        // Blocks land on the nearest cell, so an error under half a cell still lands right; require a margin.
        val cell = rec.geom.cellW
        val onDragged = !found.aligned && abs(found.cx - trueX) < 0.25f * cell && abs(found.cy - trueY) < 0.25f * cell
        assertTrue("found ${found.cx},${found.cy} aligned=${found.aligned}", onPreview || onDragged)
    }

    @Test
    fun findsPieceAboveTheBoard() {
        val (before, rec, piece) = setup()
        val px = before.px.copyOf()
        vacateSlot(px, before.w, rec)
        val trueX = 720f; val trueY = 1700f // between the board and the tray
        drawPiece(px, before.w, piece, trueX, trueY, rec.geom.cellW * 0.9f)
        val found = DragProbe.locate(before, Frame(before.w, before.h, px), rec, piece, 700f, 1650f, 5f)
        assertNotNull(found)
        assertTrue("found ${found!!.cx},${found.cy}", abs(found.cx - trueX) < 8f && abs(found.cy - trueY) < 8f)
    }
}
