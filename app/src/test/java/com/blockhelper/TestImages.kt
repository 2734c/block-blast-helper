package com.blockhelper

import java.io.DataInputStream
import java.util.zip.GZIPInputStream

object TestImages {
    /** Loads a test screenshot stored as gzip: two big-endian ints (width, height), then RGB bytes. */
    fun load(name: String): Frame {
        val file = name.removeSuffix(".png") + ".rgb.gz"
        val stream = TestImages::class.java.classLoader!!.getResourceAsStream(file) ?: error("missing $file")
        DataInputStream(GZIPInputStream(stream).buffered()).use { inp ->
            val w = inp.readInt(); val h = inp.readInt()
            val bytes = ByteArray(w * h * 3)
            inp.readFully(bytes)
            val px = IntArray(w * h) { i ->
                val r = bytes[i * 3].toInt() and 0xFF; val g = bytes[i * 3 + 1].toInt() and 0xFF; val b = bytes[i * 3 + 2].toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Frame(w, h, px)
        }
    }

    fun boardOf(vararg rows: String): Long {
        var b = 0L
        rows.forEachIndexed { r, s -> s.forEachIndexed { c, ch -> if (ch == '#') b = b or (1L shl (r * 8 + c)) } }
        return b
    }

    fun rowsOf(board: Long): List<String> =
        (0 until 8).map { r -> (0 until 8).joinToString("") { c -> if (Solver.isFilled(board, r, c)) "#" else "." } }
}
