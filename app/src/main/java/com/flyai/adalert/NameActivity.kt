package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 01-2 · 이름 입력 (피그마 v2 · 1501:2). [AccountActivity]와 [RoleActivity] 사이.
 *
 * 시안 흐름이 `00 → 01 → 01-2 → 02`라 여기서 이름을 먼저 받는다. 계정에 표시될
 * 이름이고, 역할(어르신/보호자)은 다음 화면에서 고른다 — 프로필은 그때 만들어진다.
 *
 * 그래서 이 화면은 **저장만 하고 넘긴다.** 이름 하나 때문에 프로필을 미리 만들면
 * 역할을 안 고르고 나간 사람의 빈 프로필이 가족에 남는다.
 */
class NameActivity : Activity() {

    companion object {
        /** 다음 화면들이 읽어 갈 자리 — 프로필 생성 시점까지 이 폰에만 둔다 */
        const val PREFS = "setup"
        const val KEY_NAME = "name"

        fun savedName(a: Activity): String =
            a.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, "").orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_name)
        Insets.apply(this)

        // 시안 1501:17 Field/Phone — #F5F7FA 채움 r14, 테두리 없음
        findViewById<LinearLayout>(R.id.box_name).background = Look.pill(this, Look.CARD, 14)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        val input = findViewById<EditText>(R.id.input_name)
        input.setText(savedName(this))

        val note = findViewById<TextView>(R.id.text_note)
        val noteDefault = note.text
        findViewById<Button>(R.id.btn_next).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                // 안내 자리를 그대로 쓴다 — 새 줄을 만들면 그만큼 CTA가 밀린다
                note.text = "이름을 적어 주세요."
                return@setOnClickListener
            }
            note.text = noteDefault
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_NAME, name).apply()
            startActivity(Intent(this, RoleActivity::class.java))
        }
    }
}
