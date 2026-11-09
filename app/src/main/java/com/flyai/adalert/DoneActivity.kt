package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/**
 * 보호 시작 완료 (피그마 v2 · S04 · 1037:107) — 준비의 마지막 화면.
 *
 * 처음으로 "다 됐다"를 말하는 자리. 앞의 세 화면이 확인·동의·권한이었다면 이 화면은
 * 켜진 것을 한 번에 보여 주고 끝낸다. 앱바도 진행 바도 없이 가운데 체크 76 하나 —
 * v1의 화면 끝까지 닿던 파란 띠는 v2에서 사라졌다.
 *
 * 짜임은 [Done]이 갖고 있다. 보호자 쪽 두 장과 같은 판이되 체크가 346에서 시작한다.
 *
 * ## 시안과 다른 곳
 * 시안의 부제는 「보호 설정이 준비됐어요 이제 가족을 연결해요」인데, 이는 보호자의
 * 「가입 완료」(1496:2)와 **한 글자도 다르지 않다** — 옮겨 붙이고 고치지 않은 것으로 본다.
 * 어르신은 이 화면에 닿기까지 이미 코드를 넣고 동의까지 마쳤으니 연결할 가족이 남아 있지
 * 않다. 그래서 부제만은 지금 상태를 말하게 두었다: 연결된 보호자의 이름, 모르면 지켜보고
 * 있다는 말.
 *
 * 상태 알약은 시안대로 없다. 접근성이 꺼져 있으면 [PermissionActivity]가 여기로 넘기지
 * 않으므로, 이 자리에 닿았다는 것 자체가 이미 「안전」이라 알약이 할 말이 없다.
 */
class DoneActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_step)
        Insets.apply(this)

        val body = Done.bind(
            this,
            title = "안심하고 사용하세요",
            sub = "가디안이 항상 지켜보고 있어요",
            button = "안심폰 시작하기",
            top = 298,          // 시안 체크 y=346
            senior = true,      // 어르신 규격 — 버튼 66
        ) {
            // 시안 캔버스의 어르신 줄은 S00~S04 다음이 E01(어르신 홈)이다. 「안심폰
            // 시작하기」를 눌렀는데 「내 정보」가 열리면 무엇이 시작됐는지 보이지 않는다
            startActivity(
                Intent(this, SeniorHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }

        Family.profiles { all ->
            val name = all.firstOrNull { !it.isSenior }?.name
            if (name != null) runOnUiThread {
                (body.getChildAt(2) as TextView).text = "${name}님과 연결되었어요"
            }
        }
    }
}
