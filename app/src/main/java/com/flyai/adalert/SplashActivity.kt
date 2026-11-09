package com.flyai.adalert

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

/**
 * 스플래시 · GuADian 첫 화면.
 *
 * 앱 실행 시 가장 먼저 뜨는 한 장. 역할은 **이름 제시** 하나뿐 —
 * 어르신이 처음 켤 때도, 매일 켤 때도, 무슨 앱인지 큰 로고로 먼저 표시.
 * 피그마 v2에 스플래시 프레임 없음 → 00 소개의 로고 줄(방패 + "GuADian") 그대로 확대.
 *
 * ## 여기서 결정 없음
 * 이동 화면 결정은 [IntroActivity] 담당(가족 연결 폰은 스스로 홈으로 이동).
 * 갈림길이 두 곳이면 한쪽만 고치고 왜 안 바뀌나 하는 문제.
 *
 * ## 짧은 체류
 * [STAY_MS]는 로고 인식에 필요한 만큼만. 스플래시는 앱이 켜지는 **모든 순간**에 끼어드는
 * 화면 → 길면 매일 쓰는 사람에게는 그냥 기다림.
 */
class SplashActivity : Activity() {

    private companion object {
        const val STAY_MS = 1_400L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 이동은 한 번만. 뒤로가기로 되돌아왔을 때 이중 열림 방지 */
    private var moved = false

    private val go = Runnable {
        if (moved) return@Runnable
        moved = true
        // 개발용 통로(--ez preview / --es screen) 그대로 전달.
        // 스플래시가 런처여도 `am start -n .../.SplashActivity --es screen s02` 동작.
        startActivity(
            Intent(this, IntroActivity::class.java)
                .putExtras(intent.extras ?: Bundle())
        )
        // 스택에 미잔류 — 소개에서 뒤로가기 시 앱 종료가 정상,
        // 로고 화면 재등장은 갇힌 것처럼 보이는 문제.
        fade()
        finish()
    }

    /** 로고 → 소개 페이드 전환. 툭 잘리면 화면 오류처럼 보임 */
    private fun fade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        // 로고 한 덩이가 화면 정가운데 → Insets 없이도 시스템 바와 겹침 없음.
        paintWordmark(findViewById(R.id.text_wordmark))
    }

    /** 워드마크 "GuADian" — Gu·ian은 먹색, 가운데 AD만 로고 꺾쇠와 같은 초록(DS v2) */
    private fun paintWordmark(v: TextView) {
        val word = "GuADian"
        v.text = SpannableString(word).apply {
            setSpan(
                ForegroundColorSpan(getColor(R.color.brand_green)),
                word.indexOf("AD"), word.indexOf("AD") + 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(go)
        handler.postDelayed(go, STAY_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(go)
    }

    /** 로고 표시 중 뒤로가기 → 바로 종료 */
    override fun onBackPressed() {
        moved = true
        finish()
    }
}
