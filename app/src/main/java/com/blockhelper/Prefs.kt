package com.blockhelper

import android.content.Context

/** Settings and learned drag offsets. Offsets are in board cells: dragged block centre minus finger. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("blockhelper", Context.MODE_PRIVATE)

    var strategy: Strategy
        get() = if (sp.getString("strategy", "safe") == "combo") Strategy.COMBO else Strategy.SAFE
        set(v) = sp.edit().putString("strategy", if (v == Strategy.COMBO) "combo" else "safe").apply()

    /** 0 = fast, 1 = normal, 2 = slow */
    var speed: Int
        get() = sp.getInt("speed", 1)
        set(v) = sp.edit().putInt("speed", v).apply()

    /** Skip the mid-drag check for block sizes whose drop has already been verified. */
    var fastDrag: Boolean
        get() = sp.getBoolean("fastDrag", false)
        set(v) = sp.edit().putBoolean("fastDrag", v).apply()

    var panelX: Int
        get() = sp.getInt("panelX", 24)
        set(v) = sp.edit().putInt("panelX", v).apply()
    var panelY: Int
        get() = sp.getInt("panelY", 120)
        set(v) = sp.edit().putInt("panelY", v).apply()

    // Keys carry a version so values learned by older builds are ignored.
    fun offset(key: String): Pair<Float, Float>? {
        val s = sp.getString("off2_$key", null) ?: return null
        val parts = s.split(",")
        return parts[0].toFloat() to parts[1].toFloat()
    }

    fun setOffset(key: String, dx: Float, dy: Float) {
        sp.edit().putString("off2_$key", "$dx,$dy").putString("off2_default", "$dx,$dy").apply()
    }

    val defaultOffset: Pair<Float, Float>? get() = offset("default")

    /** True when the stored offset came from the dragged block itself, not from a snapped landing preview. */
    fun exact(key: String): Boolean = sp.getBoolean("exact2_$key", false)
    fun setExact(key: String, v: Boolean) = sp.edit().putBoolean("exact2_$key", v).apply()

    fun confirmed(key: String): Boolean = sp.getBoolean("ok2_$key", false)
    fun setConfirmed(key: String, v: Boolean) = sp.edit().putBoolean("ok2_$key", v).apply()

    /** How far the dragged block moves per pixel of finger movement (1 = follows the finger exactly). */
    var gainX: Float
        get() = sp.getFloat("gainX2", 1f)
        set(v) = sp.edit().putFloat("gainX2", v).apply()
    var gainY: Float
        get() = sp.getFloat("gainY2", 1f)
        set(v) = sp.edit().putFloat("gainY2", v).apply()

    fun resetCalibration() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("off") || k.startsWith("ok") || k.startsWith("exact") || k.startsWith("gain")) e.remove(k)
        e.apply()
    }

    fun calibrationSummary(): String {
        val keys = sp.all.keys.filter { it.startsWith("off2_") && it != "off2_default" }.map { it.removePrefix("off2_") }.sorted()
        if (keys.isEmpty()) return "아직 없음"
        return keys.joinToString(", ") { k ->
            val (x, y) = offset(k)!!
            "$k(%.2f, %.2f)%s".format(x, y, if (confirmed(k)) "✓" else "")
        } + " · 이동 배율 %.2f, %.2f".format(gainX, gainY)
    }
}
