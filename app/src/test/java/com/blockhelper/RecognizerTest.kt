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
        // Grid line centres measured on the screenshot: x 148 … 1291.5, y 411 … 1554.
        val g = rec.geom
        assertTrue("left ${g.left}", abs(g.left - 148f) < 2.5f)
        assertTrue("right ${g.right}", abs(g.right - 1291.5f) < 2.5f)
        assertTrue("top ${g.top}", abs(g.top - 411f) < 2.5f)
        assertTrue("bottom ${g.bottom}", abs(g.bottom - 1554f) < 2.5f)
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

    private fun assertReads(name: String) {
        val res = Recognizer.recognize(TestImages.load(name))
        assertNotNull("$name: ${res.error}", res.rec)
        val rec = res.rec!!
        assertEquals(name, expectedBoard, TestImages.rowsOf(rec.board))
        println("$name: left=${rec.geom.left} top=${rec.geom.top} cell=${rec.geom.cellW}x${rec.geom.cellH}")
        assertEquals(name, "##/##/##", rec.pieces[0]?.piece?.key())
        assertEquals(name, "##/##/##", rec.pieces[1]?.piece?.key())
        assertNull(name, rec.pieces[2])
    }

    @Test
    fun readsDarkTheme() = assertReads("screen_dark")

    @Test
    fun readsLandscapeWithBlackBars() = assertReads("screen_letterbox")

    @Test
    fun readsGradientBackground() = assertReads("screen_gradient")

    @Test
    fun reportsBlankCapture() {
        val res = Recognizer.recognize(Frame(800, 1200, IntArray(800 * 1200) { 0xFF000000.toInt() }))
        assertNull(res.rec)
        assertTrue(res.error!!, res.error!!.contains("비어"))
    }

    @Test
    fun rejectsScreenWithoutBoard() {
        val px = IntArray(800 * 1200) { 0xFFB2A793.toInt() }
        assertNull(Recognizer.recognize(Frame(800, 1200, px)).rec)
    }
}
