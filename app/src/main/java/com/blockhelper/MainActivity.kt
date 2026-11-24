package com.blockhelper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var calibText: TextView
    private val dp by lazy { resources.displayMetrics.density }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p, p, p)
        }
        fun heading(s: String) = TextView(this).apply {
            text = s; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
        }
        fun body(s: String) = TextView(this).apply { text = s; textSize = 14f; setLineSpacing(0f, 1.25f) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; isAllCaps = false
            setOnClickListener { onClick() }
        }

        col.addView(TextView(this).apply { text = "블록 도우미"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD })
        col.addView(body("8×8 블록 퍼즐에서 블록 3개를 놓을 최선의 순서와 자리를 찾아 게임 화면 위에 보여주거나, 대신 놓아줘요."))

        col.addView(heading("1. 접근성 서비스 켜기"))
        statusText = TextView(this).apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 10 * dp }
        }
        col.addView(statusText)
        col.addView(button("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(body(
            "설치된 앱 목록(또는 '설치된 앱')에서 '블록 도우미'를 켜세요. 화면 캡처와 터치 입력에 이 권한이 필요해요.\n\n" +
                "회색으로 막혀서 켤 수 없다면: 아래 '앱 정보 열기' → 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜세요. " +
                "직접 설치한 앱은 처음에 이 단계가 필요해요."
        ))
        col.addView(button("앱 정보 열기") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(heading("2. 게임에서 쓰기"))
        col.addView(body(
            "게임을 켜면 화면 위에 작은 패널이 떠요. 제목 줄을 끌어서 옮길 수 있어요.\n" +
                "• 추천: 놓을 자리를 색 테두리와 번호(1→2→3)로 보여줘요. 아래 블록 위에도 같은 번호가 떠요.\n" +
                "• 자동: 블록을 대신 끌어다 놓아요. 다시 누르면 멈춰요. 블록을 잡은 채 잠깐 멈춰서 위치를 맞춘 뒤 놓아요. " +
                "자동을 켤 때마다 처음 두 번의 드래그 기록(_drag.png)이 갤러리에 저장돼요.\n" +
                "• 진단: 지금 화면과 인식 결과를 갤러리 Pictures/BlockHelper에 저장해요. 인식이 이상하면 이 이미지를 보내주세요.\n" +
                "• 접기: 패널을 작게 줄여요. '⠿ 블록'을 누르면 다시 펼쳐져요."
        ))

        col.addView(heading("3. 설정"))
        col.addView(body("전략"))
        val strat = RadioGroup(this)
        val safe = RadioButton(this).apply { id = View.generateViewId(); text = "안정형: 오래 버티기 (기본)" }
        val combo = RadioButton(this).apply { id = View.generateViewId(); text = "콤보형: 매번 줄 지우기 우선 (점수↑, 위험↑)" }
        strat.addView(safe); strat.addView(combo)
        strat.check(if (prefs.strategy == Strategy.COMBO) combo.id else safe.id)
        strat.setOnCheckedChangeListener { _, id -> prefs.strategy = if (id == combo.id) Strategy.COMBO else Strategy.SAFE }
        col.addView(strat)

        col.addView(body("자동 플레이 속도"))
        val speed = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val speedIds = listOf("빠르게", "보통", "천천히").mapIndexed { i, s ->
            RadioButton(this).apply { id = View.generateViewId(); text = s }.also { speed.addView(it) }.id to i
        }
        speed.check(speedIds.first { it.second == prefs.speed }.first)
        speed.setOnCheckedChangeListener { _, id -> prefs.speed = speedIds.first { it.first == id }.second }
        col.addView(speed)

        col.addView(CheckBox(this).apply {
            text = "빠른 드래그: 한 번 맞게 놓인 블록 크기는 중간 확인 없이 바로 놓기"
            isChecked = prefs.fastDrag
            setOnCheckedChangeListener { _, v -> prefs.fastDrag = v }
        })

        calibText = body("")
        col.addView(calibText)
        col.addView(button("드래그 보정 초기화") { prefs.resetCalibration(); refresh() })

        col.addView(heading("알아두세요"))
        col.addView(body(
            "자동 플레이는 게임 이용약관에 어긋날 수 있고, 계정 제재를 받을 수 있어요. 본인 판단으로 사용하세요.\n" +
                "광고나 팝업이 뜨면 화면을 읽지 못해서 자동 플레이가 멈춰요."
        ))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.startsWith("$packageName/") } == true
        statusText.text = if (enabled) "✓ 켜져 있어요. 게임을 실행하세요." else "꺼져 있어요"
        (statusText.background as GradientDrawable).setColor(if (enabled) 0x3317B39C else 0x33FF7A2F)
        calibText.text = "드래그 보정값(블록 크기별): ${prefs.calibrationSummary()}"
    }
}
