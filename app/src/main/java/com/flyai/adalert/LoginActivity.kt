package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 가족 연결 코드 입력 (피그마 v2 · S00 · 1376:2) — 어르신 폰이 가족에 들어오는 유일한 문.
 *
 * 보호자 폰이 P05에서 만든 **여섯 자리 코드**와 **네 자리 비밀번호**를 넣는다.
 * 이메일 주소를 받지 않는 이유는 [Family] 참고 — 어르신 폰에서 주소를 오타 없이
 * 치는 것부터가 벽이다.
 *
 * ## 시안과 다른 곳
 * 시안에는 코드 칸 여섯만 있다. 우리는 그 아래 **비밀번호 칸 넷**이 하나 더 필요하다
 * ([Family.joinFamily]가 둘을 함께 확인한다). 시안이 코드 칸과 버튼 사이를 크게 비워 둬서
 * 그 자리에 같은 규격의 칸으로 넣었다 — 새 모양을 만들지 않았다.
 *
 * 「새 가족 만들기」 버튼은 이 화면에서 뺐다. v2에서 가족을 만드는 곳은 보호자 흐름의
 * 마지막(P05)뿐이고, 여기 남겨 두면 어르신 폰이 자기 가족을 새로 만들어 버릴 수 있다.
 */
class LoginActivity : Activity() {

    private lateinit var code: CodeBoxes
    private lateinit var pin: CodeBoxes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        Insets.apply(this)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        Step.progress(this, "어르신 1/4")

        val msg = findViewById<TextView>(R.id.text_message)
        val w = CodeBoxes.boxWidth(this)

        code = CodeBoxes(this, 6, w) { if (it.length == 6) pin.focus() }
        findViewById<LinearLayout>(R.id.box_code).addView(code.view())

        pin = CodeBoxes(this, 4, w)
        findViewById<LinearLayout>(R.id.box_pin).addView(pin.view())
        code.focus()

        findViewById<Button>(R.id.btn_join).setOnClickListener {
            if (code.text.length < 6 || pin.text.length < 4) {
                msg.text = "코드 6자리와 비밀번호 4자리를 모두 넣어 주세요."
                return@setOnClickListener
            }
            msg.text = "잠시만요…"
            Family.joinFamily(code.text, pin.text) { error ->
                if (error == null) {
                    // 역할을 골라서 온 어르신 폰 → 준비 화면(S01).
                    // 그냥 연결만 하러 온 경우 → 프로필 고르기.
                    val senior = intent.getStringExtra("role") == Family.ROLE_SENIOR
                    // 이 폰이 가족에 들어온 지금이 01-2의 이름을 세울 수 있는 첫 순간이다.
                    // 프로필을 고르러 가는 경우는 그 화면이 알아서 하므로 건드리지 않는다
                    val go = {
                        startActivity(
                            Intent(
                                this,
                                if (senior) SeniorSetupActivity::class.java
                                else ProfileActivity::class.java
                            )
                        )
                        finish()
                    }
                    if (senior) Family.claimProfile(this, Family.ROLE_SENIOR) { go() } else go()
                } else msg.text = error
            }
        }
    }
}
