package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 계정 시작 (피그마 v2 · 01 안심폰 시작하기 / 계정 만들기).
 *
 * ## 번호 입력만, 전송 없음
 * 인증 문자 발송 서비스 아직 없음. 이 화면은 **모양만** 진짜 — 번호를 적으면 체크 표시 +
 * 다음으로 진행, 문자 전송 없음·번호 저장 없음. 화면 아래에 그대로 명시. 없는 기능을
 * 있는 것처럼 두면 시연장에서 "문자가 안 오는데요" — 우리가 만든 오해.
 *
 * 실제 가족 연결: 다음 단계(역할 선택 → 가족 코드).
 */
class AccountActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        // 피그마 01 입력칸: 흰 바탕 · 테두리 1.8 #DFE3EA · r12 (DS 토큰에 없는 값 — 여기에 직접 기재)
        // 시안 1358:5 Field/Phone — #F5F7FA 채움 r14, 테두리 없음
        findViewById<LinearLayout>(R.id.box_phone).background =
            Look.pill(this, Look.CARD, 14)

        val phone = findViewById<EditText>(R.id.input_phone)
        val ok = findViewById<TextView>(R.id.text_ok)
        val agree = findViewById<TextView>(R.id.check_agree)
        var agreed = true
        fun paint() {
            // 피그마 01의 체크는 Icon/Success(파란 원 24 + 흰 체크). 꺼진 모양은 피그마에
            // 없음 — 같은 원에 입력칸 테두리를 두른 것으로 제작 (ic_p0_check_off).
            agree.setBackgroundResource(
                if (agreed) R.drawable.ic_p0_check else R.drawable.ic_p0_check_off
            )
        }
        paint()
        findViewById<LinearLayout>(R.id.row_agree).setOnClickListener { agreed = !agreed; paint() }

        // 번호 열 자리 이상 → 오른쪽 체크 표시 (시안)
        phone.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.filter { it.isDigit() }?.length ?: 0
                ok.visibility = if (digits >= 10) android.view.View.VISIBLE
                else android.view.View.INVISIBLE
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // '이미 계정이 있어요'(로그인) 줄은 피그마대로 00 소개 화면으로 이동 — [IntroActivity].
        val next = findViewById<Button>(R.id.btn_next)
        next.setOnClickListener {
            val digits = phone.text.filter { it.isDigit() }.length
            val note = findViewById<TextView>(R.id.text_note)
            when {
                digits < 10 -> note.text = "휴대폰 번호를 끝까지 적어 주세요."
                !agreed -> note.text = "필수 약관에 동의해야 시작할 수 있어요."
                else -> startActivity(Intent(this, NameActivity::class.java))
            }
        }
    }
}
