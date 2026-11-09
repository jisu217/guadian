package com.flyai.adalert

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * 상태 표시줄·내비게이션 바에 화면이 가리지 않게 여백 추가.
 *
 * ## 필요한 이유
 * 안드로이드 15부터 앱 화면은 기본으로 **상태 표시줄 뒤까지** 그려짐. 예전에는 시스템이
 * 알아서 아래로 내려 주었지만 이제는 앱이 스스로 회피 필요. 시안의 상단 막대(뒤로 화살표)와
 * 시계의 겹침 원인.
 *
 * `android:fitsSystemWindows="true"` 한 줄로도 가능하나, 그 방법은 레이아웃에 적어 둔 여백을
 * **덮어써서** 좌우 20dp가 통째로 사라짐(실제로 목록이 화면 끝에 붙어 나옴).
 * 그래서 원래 여백을 기억해 두고 거기에 더하는 방식.
 */
object Insets {

    /**
     * 화면 맨 아래(주로 파란 CTA)와 화면 끝 사이 거리 — 피그마 v2 공통 **12dp**.
     *
     * 시안은 844 높이 안에서 CTA가 y=755·높이 56이라 아래가 33(≈12dp)이고, 그 안에
     * 홈 인디케이터가 들어 있다. 안드로이드에서 같은 뜻은 "시스템 바를 **포함해** 12dp".
     *
     * 그래서 바닥만은 레이아웃의 paddingBottom을 인셋에 더하지 않고 **덮어쓴다.**
     * 더하면 3버튼 폰에서 48+20=68dp가 되어 시안의 두 배로 벌어진다(실측).
     * 인셋이 12dp보다 크면(3버튼 48dp) 그쪽을 쓴다 — 버튼을 내비 바 밑에 그릴 수는 없다.
     */
    private const val BOTTOM_DP = 12

    fun apply(a: Activity) {
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val v: View = content.getChildAt(0) ?: return
        val l = v.paddingLeft
        val t = v.paddingTop
        val r = v.paddingRight
        v.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(
                    l + bars.left, t + bars.top, r + bars.right,
                    maxOf(bars.bottom, Look.dp(view.context, BOTTOM_DP))
                )
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    l + insets.systemWindowInsetLeft,
                    t + insets.systemWindowInsetTop,
                    r + insets.systemWindowInsetRight,
                    maxOf(
                        insets.systemWindowInsetBottom,
                        Look.dp(view.context, BOTTOM_DP)
                    )
                )
            }
            insets
        }
        v.requestApplyInsets()
    }
}
