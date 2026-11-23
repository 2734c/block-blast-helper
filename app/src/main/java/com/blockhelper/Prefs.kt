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

    fun offset(key: String): Pair<Float, Float>? {
        val s = sp.getString("off_$key", null) ?: return null
        val parts = s.split(",")
        return parts[0].toFloat() to parts[1].toFloat()
    }

    fun setOffset(key: String, dx: Float, dy: Float) {
        sp.edit().putString("off_$key", "$dx,$dy").putString("off_default", "$dx,$dy").apply()
    }

    val defaultOffset: Pair<Float, Float>? get() = offset("default")

    fun confirmed(key: String): Boolean = sp.getBoolean("ok_$key", false)
    fun setConfirmed(key: String, v: Boolean) = sp.edit().putBoolean("ok_$key", v).apply()

    fun resetCalibration() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("off_") || k.startsWith("ok_")) e.remove(k)
        e.apply()
    }

    fun calibrationSummary(): String {
        val keys = sp.all.keys.filter { it.startsWith("off_") && it != "off_default" }.map { it.removePrefix("off_") }.sorted()
        if (keys.isEmpty()) return "아직 없음"
        return keys.joinToString(", ") { k ->
            val (x, y) = offset(k)!!
            "$k(%.2f, %.2f)%s".format(x, y, if (confirmed(k)) "✓" else "")
        }
    }
}
