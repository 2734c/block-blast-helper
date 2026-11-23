package com.blockhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RecognizerTest {
    private val expectedBoard = listOf(
        ".....###",
        ".....##.",
        "###..##.",
        "..##....",
        "..##....",
        "..##....",
        "####..##",
        "###...##",
    )

    @Test
    fun readsBoardAndTray() {
        val res = Recognizer.recognize(TestImages.load("screen_1440x2304.png"))
        val rec = res.rec
        assertNotNull(res.error, rec)
        rec!!
        assertEquals(expectedBoard, TestImages.rowsOf(rec.board))
        assertTrue("cell ${rec.geom.cellW}", abs(rec.geom.cellW - 143.7f) < 2f)
        assertTrue("left ${rec.geom.left}", abs(rec.geom.left - 145f) < 3f)
        assertTrue("top ${rec.geom.top}", abs(rec.geom.top - 409f) < 3f)
        assertEquals("##/##/##", rec.pieces[0]?.piece?.key())
        assertEquals("##/##/##", rec.pieces[1]?.piece?.key())
        assertNull(rec.pieces[2])
        val p0 = rec.pieces[0]!!
        assertTrue("tray piece centre ${p0.cx},${p0.cy}", abs(p0.cx - 342f) < 6f && abs(p0.cy - 1894f) < 6f)
    }

    @Test
    fun readsIrregularTrayPiece() {
        val rec = Recognizer.recognize(TestImages.load("screen_L.png")).rec!!
        assertEquals("#./#./##", rec.pieces[0]?.piece?.key())
    }

    @Test
    fun readsDownscaledScreen() {
        val f = TestImages.load("screen_1440x2304.png")
        // Nearest-neighbour downscale to 1080 wide, like a phone screen.
        val w = 1080; val h = 1728
        val px = IntArray(w * h) { i -> f.at((i % w) * f.w / w, (i / w) * f.h / h) }
        val rec = Recognizer.recognize(Frame(w, h, px)).rec!!
        assertEquals(expectedBoard, TestImages.rowsOf(rec.board))
        assertEquals("##/##/##", rec.pieces[1]?.piece?.key())
    }

    @Test
    fun rejectsScreenWithoutBoard() {
        val px = IntArray(800 * 1200) { 0xFFB2A793.toInt() }
        assertNull(Recognizer.recognize(Frame(800, 1200, px)).rec)
    }
}
