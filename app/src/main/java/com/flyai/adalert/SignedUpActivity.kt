package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 보호자 가입 완료 (피그마 v2 · 1496:2) — 네 걸음(P01~P04)을 끝낸 자리.
 *
 * 시안에서 이 프레임은 P04와 「보호자 시작 홈(가족 미연결)」 사이에 놓여 있고,
 * 흐름 설명(1036:4)도 「보호 설정 4단계 → 가입 완료 → 시작 홈(미연결) →
 * 연결 코드 받기 → P05」로 적혀 있다. 그래서 [ShareActivity]는 이제 P05로 바로
 * 가지 않고 여기를 거쳐 홈으로 간다.
 *
 * 왜 한 장을 더 두는가 — P01~P04는 **설정을 고르는** 걸음이라 저장됐다는 말을 아직
 * 듣지 못했다. 여기서 한 번 매듭을 짓고, 연결은 홈에서 새 걸음으로 시작한다.
 * 어르신 폰이 아직 없을 때가 대부분이라 연결을 미룰 수 있어야 하기 때문이다.
 *
 * 상태 알약은 없다 (시안에도 없다) — 아직 지킬 가족이 없어 「보호 상태」라고 할 것이 없다.
 */
class SignedUpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        Done.bind(
            this,
            title = "가입이 완료되었어요",
            sub = "보호 설정이 준비됐어요\n이제 가족을 연결해요",
            button = "보호자 홈으로 가기",
        ) {
            startActivity(
                Intent(this, GuardianHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
    }
}
