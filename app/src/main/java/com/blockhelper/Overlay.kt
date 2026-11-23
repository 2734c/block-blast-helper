package com.blockhelper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Colours for the 1st, 2nd and 3rd move. */
val STEP_COLORS = intArrayOf(0xFFFF7A2F.toInt(), 0xFF17B39C.toInt(), 0xFF8E6CF0.toInt())

fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
}

/** Draws the recommended placements over the game. Drawn only on cell edges and corners, so it does not disturb recognition. */
class HintView(ctx: Context) : View(ctx) {
    private var rec: Recognition? = null
    private var plan: Plan? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val loc = IntArray(2)
    private val rect = RectF()

    fun show(r: Recognition?, p: Plan?) {
        rec = r; plan = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = rec ?: return
        val p = plan ?: return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val g = r.geom
        val cw = g.cellW; val ch = g.cellH
        stroke.strokeWidth = 0.07f * cw
        for ((k, mv) in p.moves.withIndex()) {
            val tp = r.pieces[mv.slot] ?: continue
            val color = STEP_COLORS[k % STEP_COLORS.size]
            stroke.color = color; fill.color = color
            val ins = 0.09f * cw
            var first: Pair<Int, Int>? = null
            for ((rr, cc) in tp.piece.cells()) {
                if (first == null) first = rr to cc
                val x0 = g.left + (mv.x + cc) * cw; val y0 = g.top + (mv.y + rr) * ch
                rect.set(x0 + ins, y0 + ins, x0 + cw - ins, y0 + ch - ins)
                canvas.drawRoundRect(rect, 0.12f * cw, 0.12f * cw, stroke)
            }
            first?.let { (rr, cc) ->
                val bx = g.left + (mv.x + cc) * cw + 0.2f * cw; val by = g.top + (mv.y + rr) * ch + 0.2f * ch
                badge(canvas, bx, by, 0.13f * cw, k + 1)
            }
            // Matching badge in the gap between the board and the tray.
            badge(canvas, tp.cx, g.bottom + 0.045f * g.size, 0.018f * g.size, k + 1)
        }
        canvas.restore()
    }

    private fun badge(canvas: Canvas, x: Float, y: Float, radius: Float, n: Int) {
        canvas.drawCircle(x, y, radius, fill)
        text.textSize = radius * 1.25f
        canvas.drawText(n.toString(), x, y - (text.descent() + text.ascent()) / 2, text)
    }
}

/** The floating control panel. Drag it by the title row. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class PanelView(ctx: Context, private val wm: WindowManager, private val prefs: Prefs) : LinearLayout(ctx) {
    val status: TextView
    val hintBtn: Button
    val autoBtn: Button
    val diagBtn: Button
    private val body: LinearLayout
    private val title: TextView
    private var collapsed = false
    lateinit var params: WindowManager.LayoutParams

    private val dp = ctx.resources.displayMetrics.density

    init {
        orientation = VERTICAL
        val pad = (10 * dp).toInt()
        setPadding(pad, (6 * dp).toInt(), pad, pad)
        background = GradientDrawable().apply {
            setColor(0xEE1E1A18.toInt()); cornerRadius = 14 * dp
            setStroke((1 * dp).toInt(), 0x33FFFFFF)
        }
        title = TextView(ctx).apply {
            text = "⠿ 블록 도우미"; setTextColor(0xFFEDE6DA.toInt()); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        status = TextView(ctx).apply {
            text = "추천이나 자동을 누르세요"; setTextColor(0xFFC9BFB0.toInt()); textSize = 12f
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            maxWidth = (260 * dp).toInt()
        }
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
        hintBtn = button(ctx, "추천")
        autoBtn = button(ctx, "자동")
        diagBtn = button(ctx, "진단")
        val foldBtn = button(ctx, "접기")
        foldBtn.setOnClickListener { setCollapsed(true) }
        row.addView(hintBtn); row.addView(autoBtn); row.addView(diagBtn); row.addView(foldBtn)
        body = LinearLayout(ctx).apply { orientation = VERTICAL; addView(status); addView(row) }
        addView(title); addView(body)
        title.setOnClickListener { if (collapsed) setCollapsed(false) }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        title.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt()
                    wm.updateViewLayout(this, params)
                }
                MotionEvent.ACTION_UP -> {
                    prefs.panelX = params.x; prefs.panelY = params.y
                    if (!moved) title.performClick()
                }
            }
            true
        }
    }

    private fun button(ctx: Context, label: String) = Button(ctx).apply {
        text = label; textSize = 13f; isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        background = GradientDrawable().apply { setColor(0xFF3A332E.toInt()); cornerRadius = 10 * dp }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = (6 * dp).toInt() }
    }

    fun setActive(btn: Button, active: Boolean) {
        (btn.background as GradientDrawable).setColor(if (active) STEP_COLORS[0] else 0xFF3A332E.toInt())
    }

    fun setCollapsed(c: Boolean) {
        collapsed = c
        body.visibility = if (c) GONE else VISIBLE
        title.text = if (c) "⠿ 블록" else "⠿ 블록 도우미"
    }
}
