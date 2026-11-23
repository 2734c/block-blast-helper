package com.blockhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

class BotService : AccessibilityService() {
    companion object {
        @Volatile var instance: BotService? = null
    }

    private enum class Mode { IDLE, HINT, AUTO }

    private val main = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var panel: PanelView? = null
    private var hint: HintView? = null

    @Volatile private var mode = Mode.IDLE
    @Volatile private var runId = 0
    @Volatile private var lastShotAt = 0L
    @Volatile private var lastShotError = 0
    @Volatile var lastLog: String = ""
        private set

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        showOverlays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        runId++
        mode = Mode.IDLE
        removeOverlays()
        instance = null
        shotExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlays

    private fun showOverlays() {
        val h = HintView(this)
        wm.addView(h, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false))
        h.visibility = android.view.View.GONE
        hint = h
        val p = PanelView(this, wm, prefs)
        p.params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            x = prefs.panelX; y = prefs.panelY
        }
        wm.addView(p, p.params)
        p.hintBtn.setOnClickListener { toggle(Mode.HINT) }
        p.autoBtn.setOnClickListener { toggle(Mode.AUTO) }
        p.diagBtn.setOnClickListener { diagnose() }
        panel = p
    }

    private fun removeOverlays() {
        hint?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        hint = null; panel = null
    }

    private fun status(msg: String) {
        lastLog = msg
        main.post { panel?.status?.text = msg }
    }

    private fun showHint(rec: Recognition?, plan: Plan?) {
        main.post {
            hint?.let {
                it.show(rec, plan)
                it.visibility = if (rec != null && plan != null && plan.moves.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun updateButtons() {
        main.post {
            panel?.let {
                it.setActive(it.hintBtn, mode == Mode.HINT)
                it.setActive(it.autoBtn, mode == Mode.AUTO)
                it.autoBtn.text = if (mode == Mode.AUTO) "정지" else "자동"
            }
        }
    }

    private fun toggle(m: Mode) {
        val id = ++runId
        showHint(null, null)
        if (mode == m) {
            mode = Mode.IDLE
            status("멈췄어요")
            updateButtons()
            return
        }
        mode = m
        updateButtons()
        when (m) {
            Mode.HINT -> { status("화면을 읽는 중…"); thread(name = "hint") { safeLoop(id) { hintLoop(id) } } }
            Mode.AUTO -> { status("자동 플레이를 시작해요"); thread(name = "auto") { safeLoop(id) { autoLoop(id) } } }
            Mode.IDLE -> {}
        }
    }

    private fun stopWith(id: Int, msg: String) {
        if (runId != id) return
        runId++
        mode = Mode.IDLE
        showHint(null, null)
        status(msg)
        updateButtons()
    }

    private inline fun safeLoop(id: Int, body: () -> Unit) {
        try { body() } catch (t: Throwable) { stopWith(id, "오류로 멈췄어요: ${t.javaClass.simpleName} ${t.message ?: ""}") }
    }

    // ---------------------------------------------------------------- screen

    private fun displaySize(): Point {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION") d.getRealSize(p)
        return p
    }

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

    /** Takes a screenshot (blocking). Respects the system limit of about three screenshots per second. */
    fun capture(): Frame? {
        val wait = 360 - (SystemClock.uptimeMillis() - lastShotAt)
        if (wait > 0) Thread.sleep(wait)
        val latch = CountDownLatch(1)
        var out: Frame? = null
        lastShotAt = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hb = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    var bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); hb.close()
                    if (bmp != null) {
                        val size = displaySize()
                        if ((bmp.width > bmp.height) != (size.x > size.y)) {
                            val angle = if (displayRotation() == Surface.ROTATION_270) 90f else -90f
                            val m = Matrix().apply { postRotate(angle) }
                            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
                            bmp.recycle(); bmp = rotated
                        }
                        val px = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        out = Frame(bmp.width, bmp.height, px)
                        bmp.recycle()
                    }
                } finally { latch.countDown() }
            }

            override fun onFailure(errorCode: Int) { lastShotError = errorCode; latch.countDown() }
        })
        latch.await(4, TimeUnit.SECONDS)
        return out
    }

    private fun captureError(): String = when (lastShotError) {
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "이 화면은 캡처가 막혀 있어요"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "캡처 간격이 너무 짧아요"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "화면 캡처 권한이 없어요. 접근성 서비스를 다시 켜주세요"
        else -> "화면 캡처에 실패했어요 (코드 $lastShotError)"
    }

    /** Waits until two screenshots in a row read the same board and tray. */
    private fun captureStable(id: Int, tries: Int = 16): Pair<Frame, Recognition>? {
        var prevSig: String? = null
        repeat(tries) {
            if (runId != id) return null
            val f = capture() ?: run { status(captureError()); return@repeat }
            val rr = Recognizer.recognize(f)
            val rec = rr.rec
            if (rec == null) { status(rr.error ?: "화면을 읽지 못했어요"); prevSig = null; return@repeat }
            val sig = rec.signature()
            if (sig == prevSig) return f to rec
            prevSig = sig
        }
        return null
    }

    // ---------------------------------------------------------------- gestures

    private fun gesture(stroke: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(stroke).build()
        main.post {
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { ok = false; latch.countDown() }
            }, main)
            if (!sent) latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float): Path = Path().apply {
        moveTo(x0, y0)
        // A zero-length path is not a valid stroke, so always move at least a pixel.
        if (abs(x1 - x0) < 1f && abs(y1 - y0) < 1f) lineTo(x0 + 1f, y0 + 1f) else lineTo(x1, y1)
    }

    private fun dragMs() = when (prefs.speed) { 0 -> 220L; 2 -> 520L; else -> 360L }
    private fun pauseMs() = when (prefs.speed) { 0 -> 150L; 2 -> 1100L; else -> 500L }

    // ---------------------------------------------------------------- hint mode

    private fun hintLoop(id: Int) {
        var lastSig: String? = null
        var shownSig: String? = null
        while (runId == id) {
            val f = capture()
            if (f == null) { status(captureError()); Thread.sleep(800); continue }
            val rr = Recognizer.recognize(f)
            val rec = rr.rec
            if (rec == null) {
                status(rr.error ?: "화면을 읽지 못했어요"); showHint(null, null)
                lastSig = null; shownSig = null
                Thread.sleep(500); continue
            }
            val sig = rec.signature()
            if (sig == lastSig && sig != shownSig) {
                val pieces = rec.pieceArray()
                if (pieces.all { it == null }) {
                    showHint(null, null); status("새 블록을 기다리는 중…")
                } else {
                    val plan = Solver.solve(rec.board, pieces, prefs.strategy)
                    if (runId != id) break
                    showHint(rec, plan)
                    status(planText(rec, plan))
                }
                shownSig = sig
            }
            lastSig = sig
            Thread.sleep(200)
        }
    }

    private fun planText(rec: Recognition, plan: Plan): String {
        if (plan.placed == 0) return "놓을 수 있는 블록이 없어요"
        var b = rec.board
        var lines = 0
        for (m in plan.moves) {
            val res = Solver.applyMove(b, rec.pieces[m.slot]!!.piece, m.x, m.y)
            lines += res.lines; b = res.board
        }
        val order = plan.moves.indices.joinToString(" → ") { (it + 1).toString() }
        val warn = if (plan.placed < plan.total) " · ${plan.total - plan.placed}개는 놓을 자리가 없어요" else ""
        return "$order 순서로 놓으세요 · 줄 ${lines}개 클리어$warn"
    }

    // ---------------------------------------------------------------- auto mode

    private fun autoLoop(id: Int) {
        var failures = 0
        var moves = 0
        while (runId == id) {
            val (frame, rec) = captureStable(id) ?: run {
                stopWith(id, "게임 화면을 읽지 못해서 멈췄어요. ${lastLog}")
                return
            }
            val pieces = rec.pieceArray()
            if (pieces.all { it == null }) { Thread.sleep(250); continue }
            val plan = Solver.solve(rec.board, pieces, prefs.strategy)
            if (runId != id) return
            if (plan.placed == 0) { stopWith(id, "놓을 수 있는 블록이 없어요. 게임이 끝났어요. (${moves}수 둠)"); return }
            val mv = plan.moves[0]
            val tp = rec.pieces[mv.slot]!!
            status("${moves + 1}번째 수: ${mv.slot + 1}번 블록을 ${mv.y + 1}행 ${mv.x + 1}열에")
            val dropped = drop(id, frame, rec, tp, mv)
            if (runId != id) return
            if (!dropped) {
                failures++
                if (failures >= 2) { stopWith(id, "블록을 옮기지 못해서 멈췄어요. ${lastLog}"); return }
                continue
            }
            // Verify where it landed and learn from any miss.
            val after = captureStable(id) ?: continue
            val key = "${tp.piece.w}x${tp.piece.h}"
            val expected = Solver.applyMove(rec.board, tp.piece, mv.x, mv.y).board
            when {
                after.second.board == expected -> { failures = 0; moves++; prefs.setConfirmed(key, true) }
                after.second.board == rec.board -> {
                    failures++; prefs.setConfirmed(key, false)
                    if (failures >= 2) { stopWith(id, "블록이 놓이지 않았어요. 드래그 보정을 초기화하고 다시 해보세요."); return }
                }
                else -> {
                    moves++; failures = 0
                    prefs.setConfirmed(key, false)
                    landedShift(rec.board, tp.piece, mv, after.second.board)?.let { (dx, dy) ->
                        prefs.offset(key)?.let { (ox, oy) -> prefs.setOffset(key, ox + dx, oy + dy) }
                        status("${dx}, ${dy}칸 어긋나서 보정했어요")
                    }
                }
            }
            Thread.sleep(pauseMs())
        }
    }

    /** If the block landed somewhere other than planned, returns how many cells it was off by. */
    private fun landedShift(before: Long, p: Piece, mv: Move, actual: Long): Pair<Int, Int>? {
        for (dy in -3..3) for (dx in -3..3) {
            if (dx == 0 && dy == 0) continue
            if (!Solver.canPlace(before, p, mv.x + dx, mv.y + dy)) continue
            if (Solver.applyMove(before, p, mv.x + dx, mv.y + dy).board == actual) return dx to dy
        }
        return null
    }

    /**
     * Drags a tray block onto the board. Unless the drop has been verified before (and fast drag is on),
     * it holds the block, takes a screenshot to see where the block (or the game's landing preview) is,
     * corrects the finger position, and only then releases.
     */
    private fun drop(id: Int, before: Frame, rec: Recognition, tp: TrayPiece, mv: Move): Boolean {
        val p = tp.piece; val g = rec.geom
        val cw = g.cellW; val ch = g.cellH
        val size = displaySize()
        fun clampX(x: Float) = x.coerceIn(2f, size.x - 3f)
        fun clampY(y: Float) = y.coerceIn(2f, size.y - 3f)
        val tx = g.left + (mv.x + p.w / 2f) * cw
        val ty = g.top + (mv.y + p.h / 2f) * ch
        val key = "${p.w}x${p.h}"
        val known = prefs.offset(key)
        val guess = known ?: prefs.defaultOffset ?: (0f to -(p.h / 2f + 1f))
        var curX = clampX(tx - guess.first * cw)
        var curY = clampY(ty - guess.second * ch)

        if (prefs.fastDrag && known != null && prefs.confirmed(key)) {
            val s = GestureDescription.StrokeDescription(line(tp.cx, tp.cy, curX, curY), 0, dragMs(), false)
            return gesture(s).also { if (!it) status("드래그 입력이 취소됐어요") }
        }

        if (abs(curX - tp.cx) < 1f && abs(curY - tp.cy) < 1f) curX += 1f
        var stroke = GestureDescription.StrokeDescription(line(tp.cx, tp.cy, curX, curY), 0, dragMs(), true)
        if (!gesture(stroke)) { status("드래그 입력이 취소됐어요"); return false }
        Thread.sleep(120)

        fun moveTo(x: Float, y: Float, release: Boolean): Boolean {
            // Strokes must continue exactly where the last one ended, and must not have zero length.
            val nx = if (abs(x - curX) < 1f && abs(y - curY) < 1f) curX + 1f else x
            val ny = if (abs(x - curX) < 1f && abs(y - curY) < 1f) curY + 1f else y
            stroke = stroke.continueStroke(line(curX, curY, nx, ny), 0, if (release) 90L else 200L, !release)
            curX = nx; curY = ny
            return gesture(stroke)
        }
        fun cancel(msg: String): Boolean {
            // Put the block back on its tray slot.
            stroke = stroke.continueStroke(line(curX, curY, tp.cx, tp.cy), 0, dragMs(), false)
            gesture(stroke)
            status(msg)
            return false
        }

        for (iter in 0 until 3) {
            if (runId != id) return cancel("멈췄어요")
            val mid = capture() ?: return cancel(captureError())
            val expX = if (iter == 0) curX + guess.first * cw else tx
            val expY = if (iter == 0) curY + guess.second * ch else ty
            val radius = if (iter == 0 && known == null) 5f else 2.5f
            val found = DragProbe.locate(before, mid, rec, p, expX, expY, radius)
                ?: return cancel("끌고 있는 블록을 화면에서 찾지 못했어요")
            if (found.aligned) {
                // The landing cell is known exactly: move by whole cells.
                prefs.setOffset(key, (found.cx - curX) / cw, (found.cy - curY) / ch)
                if (found.landX == mv.x && found.landY == mv.y) return moveTo(curX, curY, true)
                if (!moveTo(clampX(curX + (mv.x - found.landX) * cw), clampY(curY + (mv.y - found.landY) * ch), false)) return cancel("드래그 입력이 취소됐어요")
            } else {
                val ox = (found.cx - curX) / cw; val oy = (found.cy - curY) / ch
                prefs.setOffset(key, ox, oy)
                if (abs(found.cx - tx) < 0.12f * cw && abs(found.cy - ty) < 0.12f * ch) return moveTo(curX, curY, true)
                if (!moveTo(clampX(tx - ox * cw), clampY(ty - oy * ch), false)) return cancel("드래그 입력이 취소됐어요")
            }
            Thread.sleep(100)
        }
        return moveTo(curX, curY, true)
    }

    // ---------------------------------------------------------------- diagnostics

    private fun diagnose() {
        if (mode == Mode.AUTO) { status("자동 플레이 중에는 진단할 수 없어요"); return }
        status("진단 이미지를 만드는 중…")
        thread(name = "diag") {
            try {
                val f = capture() ?: run { status(captureError()); return@thread }
                val rr = Recognizer.recognize(f)
                val plan = rr.rec?.let { rec -> rec.pieceArray().takeIf { ps -> ps.any { it != null } }?.let { Solver.solve(rec.board, it, prefs.strategy) } }
                val info = listOf(
                    "화면 ${f.w}x${f.h}, 회전 ${displayRotation()}",
                    "드래그 보정: ${prefs.calibrationSummary()}",
                    "최근 상태: $lastLog",
                )
                val names = Diagnostics.save(this, f, rr, plan, info)
                status("갤러리 Pictures/BlockHelper에 저장했어요: $names")
            } catch (t: Throwable) {
                status("진단 저장 실패: ${t.message}")
            }
        }
    }
}
