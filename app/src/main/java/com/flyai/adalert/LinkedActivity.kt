package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/**
 * 연결 완료 (피그마 v2 · P06 · 1349:114) — 보호자 쪽 준비의 마지막 화면.
 *
 * 어르신 폰이 코드를 넣고 들어온 순간에만 열린다([InviteActivity.check]).
 * 뼈대와 좌표는 [Done]이 갖고 있다 — 「보호자 가입 완료」와 같은 판이다.
 *
 * 알약의 「안전」은 지어낸 값이 아니라 지금 상태다 — 가족이 연결됐고 준비한 설정이
 * 저장됐으면 안전, 하나라도 비면 「확인 필요」.
 */
class LinkedActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: "가족"
        val safe = Family.familyId != null

        val body = Done.bind(
            this,
            title = "${name}님과 연결되었어요",
            sub = "준비한 보호 설정이 적용되었어요",
            button = "보호자 홈으로 가기",
            chip = if (safe) Done.Chip("현재 보호 상태 · 안전", Look.MINT, Look.MINT_TINT)
                   else Done.Chip("현재 보호 상태 · 확인 필요", Look.WARN_INK, Look.WARN_TINT),
        ) {
            startActivity(
                Intent(this, GuardianHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }

        // 이름을 넘겨받지 못한 채 열렸을 때(재진입 등)를 위한 보정
        if (intent.getStringExtra(EXTRA_NAME).isNullOrBlank()) Family.profiles { all ->
            val senior = all.firstOrNull { it.isSenior }?.name
            if (senior != null) runOnUiThread {
                (body.getChildAt(1) as TextView).text = "${senior}님과 연결되었어요"
            }
        }
    }
}
