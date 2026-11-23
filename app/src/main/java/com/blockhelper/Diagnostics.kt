package com.blockhelper

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.provider.MediaStore

/** Saves the raw screenshot and an annotated copy to Pictures/BlockHelper, for troubleshooting recognition. */
object Diagnostics {
    fun save(ctx: Context, f: Frame, rr: RecognizeResult, plan: Plan?, info: List<String>): String {
        val stamp = System.currentTimeMillis()
        val raw = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888)
        val rawName = "blockhelper_${stamp}_raw.png"
        write(ctx, raw, rawName)

        val bmp = raw.copy(Bitmap.Config.ARGB_8888, true)
        raw.recycle()
        val c = Canvas(bmp)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.CYAN }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.MAGENTA }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = f.w / 36f }
        val shade = Paint().apply { color = 0xCC000000.toInt() }

        val lines = ArrayList<String>()
        val rec = rr.rec
        if (rec == null) {
            lines.add("인식 실패: ${rr.error}")
        } else {
            val g = rec.geom
            for (i in 0..8) {
                c.drawLine(g.left + i * g.cellW, g.top, g.left + i * g.cellW, g.bottom, line)
                c.drawLine(g.left, g.top + i * g.cellH, g.right, g.top + i * g.cellH, line)
            }
            for (r in 0 until 8) for (col in 0 until 8) if (Solver.isFilled(rec.board, r, col)) {
                c.drawCircle(g.left + (col + 0.5f) * g.cellW, g.top + (r + 0.5f) * g.cellH, g.cellW * 0.12f, mark)
            }
            line.color = Color.YELLOW
            c.drawLine(0f, rec.trayTop.toFloat(), f.w.toFloat(), rec.trayTop.toFloat(), line)
            c.drawLine(0f, rec.trayBottom.toFloat(), f.w.toFloat(), rec.trayBottom.toFloat(), line)
            for (tp in rec.pieces) {
                if (tp == null) continue
                line.color = Color.YELLOW
                c.drawRect(RectF(tp.left.toFloat(), tp.top.toFloat(), tp.right.toFloat(), tp.bottom.toFloat()), line)
                for ((r, col) in tp.piece.cells()) {
                    c.drawCircle(tp.left + (col + 0.5f) * tp.cellPx, tp.top + (r + 0.5f) * tp.cellPx, tp.cellPx * 0.15f, mark)
                }
            }
            if (plan != null) {
                for ((k, mv) in plan.moves.withIndex()) {
                    val p = rec.pieces[mv.slot]?.piece ?: continue
                    line.color = STEP_COLORS[k % 3]
                    for ((r, col) in p.cells()) {
                        val x0 = g.left + (mv.x + col) * g.cellW; val y0 = g.top + (mv.y + r) * g.cellH
                        c.drawRect(RectF(x0 + 10, y0 + 10, x0 + g.cellW - 10, y0 + g.cellH - 10), line)
                    }
                }
            }
            lines.add("보드 (%.0f, %.0f) 칸 %.1f x %.1f".format(g.left, g.top, g.cellW, g.cellH))
            lines.add("트레이: " + rec.pieces.joinToString("  ") { it?.piece?.key() ?: "-" })
            if (plan != null) lines.add("추천: " + plan.moves.joinToString(" ") { "${it.slot + 1}번→(${it.y + 1}행,${it.x + 1}열)" })
        }
        lines.addAll(info)
        val lh = text.textSize * 1.3f
        c.drawRect(0f, 0f, f.w.toFloat(), lh * (lines.size + 0.6f), shade)
        lines.forEachIndexed { i, s -> c.drawText(s, 16f, lh * (i + 1), text) }
        val annName = "blockhelper_${stamp}_check.png"
        write(ctx, bmp, annName)
        bmp.recycle()
        return "$rawName, $annName"
    }

    private fun write(ctx: Context, bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BlockHelper")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("갤러리에 파일을 만들지 못했어요")
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
