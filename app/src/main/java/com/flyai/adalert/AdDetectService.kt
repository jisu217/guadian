package com.flyai.adalert

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.flyai.adalert.serp.SerpFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.hypot

/**
 * 화면에 이미 표시된 공식 광고 표기를 읽어 사용자에게 광고임을 알려주는 접근성 서비스.
 *
 * 광고 차단·숨김·스킵 없음. 오버레이는 터치 통과(FLAG_NOT_TOUCHABLE) —
 * 광고 클릭이나 구매·설치 선택을 일절 방해 안 함. 구글 정책상 반드시 유지 필요.
 *
 * ## 어느 화면을 보는가 — 앱 목록 유지 안 함
 * 이전: 유튜브·인스타·당근·크롬·삼성인터넷 다섯 개만 감시 → 웨일·엣지·파이어폭스와
 * **카카오톡 인앱 브라우저**에서는 코드가 한 줄도 실행 안 됨. 어르신이 광고를 가장 많이 만나는
 * 경로가 "카톡으로 온 링크"인데 그 경로가 통째로 비어 있었음.
 *
 * 지금: [AdScanner]가 노드에서 웹 엔진의 흔적을 찾아 판단. 웹이면 어느 앱 안에 있든 감시.
 * 네이티브 UI만 [AdRules.nativeAdApps]로 제한 — 앱마다 구조가 제각각이라 임의로 훑으면
 * "광고 설정" 같은 메뉴에 테두리를 씌우는 사고 발생.
 *
 * ## 삼성 인터넷은 동작 안 함 (2026-08-11 실측)
 * 삼성 인터넷은 웹 콘텐츠를 접근성 트리에 아예 생성 안 함. 브라우저 UI 노드만 20~46개 오고
 * 페이지 본문은 한 글자도 안 옴. 서비스 설정을 스크린리더 유형으로 바꾸는 우회도 불통.
 * 우리가 고칠 수 있는 문제 아님.
 */
class AdDetectService : AccessibilityService() {

    private companion object {
        const val TAG = "AdDetectService"
        /** 스캔 최소 간격. 스크롤 중에는 내용 변경 이벤트가 초당 수십 번 도착 */
        const val SCAN_INTERVAL_MS = 200L
        /** 광고가 사라졌는지 직접 확인하는 주기 */
        const val RECHECK_MS = 1000L
        /**
         * 페이지가 뜬 뒤 광고를 나중에 끼워 넣는(지연 로드) 사이트를 위한 재스캔 시각.
         * 이게 없으면 광고 삽입 이벤트가 스로틀 구간에 떨어졌을 때 그 화면은
         * 사용자가 다시 스크롤할 때까지 영원히 재스캔 안 됨.
         */
        val LAZY_RESCAN_MS = longArrayOf(600, 1800, 3500)
        /** 스캔이 잘렸을 때 직전 영역을 몇 번까지 붙잡고 있을지 */
        const val MAX_TRUNCATED_HOLDS = 3
        /** 광고가 안 보인다고 판단해도 이만큼은 테두리 유지 (깜빡임 방지) */
        const val CLEAR_DELAY_MS = 700L
        /** 알림음·진동을 다시 울리기까지의 최소 간격 */
        const val ALERT_MIN_GAP_MS = 3000L
        /**
         * 스크롤 추적 간격. 전체 순회는 수백 ms가 걸려 스크롤 추종 불가 →
         * 이미 잡아둔 광고 노드의 좌표만 다시 읽는 빠른 경로를 따로 가동 (노드 5개 남짓의 IPC).
         */
        /**
         * 실측(S25, 크롬): 좌표 한 번 읽는 데 **0.3~1.5ms**. 노드 몇 개의 IPC라
         * 트리 순회와는 비용이 두 자릿수 차이 → 화면 주사율(120Hz = 8.3ms)에 맞춰 읽음.
         * 더 느슨하게 잡으면 그만큼이 그대로 테두리가 뒤처지는 거리.
         */
        const val TRACK_INTERVAL_MS = 8L
        /**
         * 이벤트가 끊긴 뒤에도 이 횟수만큼은 계속 추적 (손을 뗀 뒤 관성 스크롤 구간).
         * 8ms × 100 ≈ 0.8초. 관성이 멎기 전에 추적을 놓으면 그때부터 테두리 뒤처짐.
         */
        const val TRACK_TICKS = 100
        /**
         * 표본과 표본 사이를 속도로 메울 수 있는 최대 시간.
         *
         * 좌표 조회는 IPC라 대답이 오는 순간 이미 과거의 값 → 마지막 표본 이후
         * 흐른 시간만큼 광고가 더 갔을 위치를 예측해서 그림. 다만 스크롤이 갑자기 멎으면 예측이
         * 틀리므로, 오래 끌지 않고 여기서 중단 — 틀린 예측이 오래 남는 것보다 잠깐 뒤처지는 편이 나음.
         */
        const val MAX_LEAD_MS = 80L
        /**
         * 예측으로 밀어줄 수 있는 최대 거리(px). 시간뿐 아니라 거리로도 제한 —
         * 빠르게 흔들면 순간 속도가 3px/ms까지 올라가서 시간 상한만으로는 240px 앞지름.
         */
        const val MAX_LEAD_PX = 40f
        /**
         * 이보다 빠르게 움직이면 테두리를 흐려서 감춤.
         *
         * 다른 프로세스가 그리는 화면에 우리 창을 프레임 단위로 맞추는 방법은 안드로이드에 없음
         * → 빠른 구간에서는 **반드시** 얼마간 어긋남. 기사 한 줄이 60~70px이라
         * 어긋난 테두리는 곧바로 "기사를 광고로 표시한 것"으로 보임.
         * 이 앱은 미탐보다 오탐이 훨씬 치명적이므로, 못 맞출 바에는 그동안 숨김.
         * 손을 멈추면 곧바로 정확한 자리에 재표시.
         *
         * 기준값은 실측 기반 — 보통의 플링이 1.5px/ms. 표본이 8ms마다 신선하게 들어오므로
         * 그 정도 속도에서는 오차가 20px 안쪽이라 감출 이유 없음. 기사 한 줄(60~70px)을
         * 넘길 만큼 어긋나기 시작하는 구간부터 걷어냄.
         */
        const val FADE_START_PX_PER_MS = 3.0f
        const val FADE_FULL_PX_PER_MS = 6.0f
        /**
         * 좌표를 못 읽었을 때 테두리를 **감춘 채로** 되돌아오기를 기다리는 시간.
         * 곧바로 지워버리면 전체 스캔이 다시 찾을 때까지 반 초 넘게 비고, 붙잡고 있으면
         * 밑에서 올라온 기사에 테두리가 씌워짐. 감추고 기다리는 것이 둘 다 피하는 길.
         */
        const val TRACK_GRACE_MS = 400L
        /**
         * 좌표가 이만큼 그대로면 "트리가 아직 안 왔다"가 아니라 정말 멈춘 것으로 간주.
         * 실측: 스크롤 중 갱신이 끊기는 구간이 평균 75ms, 긴 쪽이 100ms대.
         * 너무 짧게 잡으면 갱신을 기다리는 사이에 예측이 꺼져 테두리가 얼어붙고,
         * 너무 길게 잡으면 손을 뗀 뒤에도 한동안 앞질러 그려 지나침.
         */
        const val STALE_STOP_MS = 160L
        /**
         * 흐림을 목표값까지 한 프레임에 얼마나 옮길지. 120Hz에서 0.18이면 시정수가 40ms 남짓.
         * 곧바로 목표값을 쓰면 속도가 경계를 넘나들 때마다 테두리가 껌뻑임 —
         * 사람 눈에는 그게 "잘못 잡았다 지웠다"로 보임.
         */
        const val FADE_SMOOTH = 0.18f
        /**
         * 이보다 빠른 한 표본은 스크롤이 아니라 화면 재배치로 간주.
         *
         * 실측(크롬, 스와이프 4회): 진짜 스크롤은 1105표본 중 99%가 6px/ms 아래고 p99가 8,
         * 최대가 15. 반면 재배치는 25~76px/ms로 한 표본에 500px씩 건너뛰어 사이가 뚜렷이
         * 벌어짐(15~25 구간에 표본이 3개뿐) → 15에서 절단.
         */
        const val JUMP_PX_PER_MS = 15f
        /**
         * 건너뛰기·닫기 컨트롤을 찾는 순회의 상한.
         *
         * 본 스캔([AdScanner])은 노드 수와 시간으로 막혀 있는데 이쪽만 상한이 없었음.
         * 컨트롤이 없는 화면에서는 매번 트리를 끝까지 훑고 아무것도 못 찾음 —
         * 그것도 본 스캔이 예산에 걸려 잘릴 만큼 무거운 화면일수록 그 뒤에 통째로 붙음.
         * 본 스캔보다 작게 설정. 컨트롤은 이미 잡아둔 광고 영역 안에 있어서
         * 트리 전체를 다 볼 일이 애초에 없음.
         */
        const val ACTION_NODE_BUDGET = 2000
        const val ACTION_TIME_BUDGET_MS = 120L

        /**
         * 사용자가 "광고 닫기"를 **직접 눌렀을 때**의 예산. 미리보기보다 훨씬 넉넉함.
         *
         * 위의 120ms는 매 스캔 도는 라벨 미리보기용이라 저렴해야 함. 그런데 누른 순간까지
         * 같은 값을 사용 중이었음. 접근성 노드 하나가 IPC 한 번이라 실측 1ms 안팎(같은
         * 로그의 `scan truncated: visited=349 403ms`) → 120ms에 100~200개.
         * 기사 맨 아래 배너는 트리에서 한참 뒤라 **닿기도 전에 예산 소진** —
         * 구글 광고(cbb 닫기 노드가 실제로 있는 광고)에서 `0 개 닫음`이 나온 원인.
         *
         * 누른 순간은 사용자가 결과를 기다리는 시점이라 0.5초 사용 가능. 게다가
         * 아래 가지치기가 들어가 실제로는 이 상한에 닿을 일이 거의 없음.
         */
        const val PRESS_NODE_BUDGET = 8000
        const val PRESS_TIME_BUDGET_MS = 500L

        /**
         * 닫기 컨트롤을 찾을 때 광고 영역에 주는 여유.
         *
         * 광고의 X는 모서리에 딱 붙어 있어, 감지 영역이 몇 px만 좁게 잡혀도 경계 밖으로
         * 밀려나 탈락. 브라우저 자신의 버튼을 잘못 집는 위험은 이 정도 여유로는
         * 거의 안 늘어남 — 툴바는 화면 위쪽이고 배너는 본문 안이라 16dp로는 안 닿음.
         */
        const val CLOSE_SLOP_DP = 16

        /**
         * 광고 클릭 뒤 리다이렉트가 멎기를 기다리는 시간. 이 시간 동안 주소가 안 바뀌면
         * 최종 목적지로 보고 서버 답과 대조([ClickJudge.settle]). 요청 자체는 클릭 즉시
         * ([WebNode.earlyJudgeTarget]이 허락하는 주소일 때). 실측 홉 간격: 204ms·784ms.
         */
        const val REDIRECT_SETTLE_MS = 900L

        /**
         * 광고를 누른 뒤 이 시간 안에 일어난 사이트 이동만 광고가 데려간 것으로 간주.
         * 리다이렉트를 몇 홉 타고 느린 서버를 거치는 경우까지 담아야 해서 넉넉히 설정.
         */
        const val AD_CLICK_WINDOW_MS = 6_000L

        /**
         * 광고 클릭(투터치 통과) 직후 표시를 걷어 두는 시간. 크롬이 다음 페이지를 그리는
         * 동안에도 옛 페이지 노드가 좌표를 계속 돌려줘(실측 쿠팡 0.9초) anchor lost까지
         * 테두리·가드 잔류. 클릭이 이동으로 안 이어진 드문 경우는 이 시간 뒤 스캔이 복원
         */
        const val CLICK_CLEAR_MS = 1_000L

        /** 테두리 두께·모서리는 [Look]이 보유 — 내 정보 화면([MeActivity])도 같은 값을 봐야 함 */
        const val BORDER_RADIUS = Look.AD_BORDER_RADIUS

        /**
         * 가드 알림(화면 아래 조각)이 떠 있는 시간. 주의 광고의 "한 번 더 누르면
         * 들어가기"도 **알림이 보이는 동안만** 유효 — 알림이 사라진 지 한참 뒤의
         * 터치가 소리 없이 들어가지면, 왜 이번엔 안 물어봤는지 알 길이 없음 (실측 지적).
         */
        const val NOTICE_MS = 4_000L

        /**
         * 쓸어넘김 재생 시간의 하한·상한. 사용자가 손가락을 댄 시간을 그대로 쓰되,
         * 너무 짧으면 플링으로 오인돼 화면이 튀고 너무 길면 손을 뗀 뒤에도 한참 움직인다.
         */
        const val REPLAY_MIN_MS = 80L
        const val REPLAY_MAX_MS = 400L

        /** 재생이 끝나고 가드를 다시 붙이기까지의 여유. 재생 도중에 붙으면 그것마저 먹는다 */
        const val REPLAY_GAP_MS = 120L

        /**
         * 가드를 걷고 나서 재생을 쏘기까지의 대기.
         *
         * [WindowManager.removeView]는 **요청만 걸고 바로 리턴**한다. 창이 실제로 사라지는 것은
         * 다음 트랜잭션 — 곧바로 쏘면 아직 살아 있는 그 가드가 재생을 그대로 다시 먹어
         * 화면이 1px도 움직이지 않는다(실기기 확인). 창이 걷힐 한 프레임을 기다린다.
         */
        const val REPLAY_WAIT_MS = 32L

        /**
         * 마크 메모(같은 좌표면 href·색 재사용)를 이 시간마다 강제 재계산.
         * 제자리 회전(이벤트·좌표 변화 없이 소재만 교체) 때문에 상한 필요.
         * 재계산은 영역당 노드 수십 개 IPC라 2초에 한 번이면 스캔 부담 없음.
         */
        const val MARK_RECHECK_MS = 2_000L

        /** 광고 영역의 주소를 전부 로그로 출력 — 새 광고망의 링크 구조를 잴 때 켬 */
        const val DBG_URLS = false

        /**
         * 앱에서 빠져나갈 때 뒤로 가기를 몇 번까지 누를지.
         *
         * "들어오기 전으로 돌아가라"는 API 없음 — 화면의 앱이 그대로면 다시 누르기를
         * 반복할 뿐. 처음 3으로 뒀을 때 광고가 앱 깊숙한 페이지로 데려간 경우
         * (상품 상세 → 홈 → 종료 확인 → 종료 = 4번) 탈출 실패.
         * 이미 나간 뒤에는 앱이 바뀌어 멈추므로, 상한을 올려도 더 누르는 일 없음.
         */
        const val LEAVE_APP_TRIES = 8

        /**
         * 찾은 컨트롤의 우선순위. **낮을수록 우선.**
         *
         * 트리에서 먼저 만난 것을 그대로 쓰면 한 광고에 컨트롤이 둘 이상 붙어 있을 때
         * 어느 것이 뽑히는지가 순회 순서에 좌우됨. 건너뛰기가 있으면 그게 언제나 우위 —
         * 광고를 실제로 끝내는 것은 그쪽뿐이고, 닫기는 자리만 비우는 경우 있음.
         */
        const val RANK_SKIP = 0
        const val RANK_CLOSE = 1

        /**
         * 버튼 막대의 규격. **두 버튼이 같은 값 사용** — 한쪽만 알약 모양이면
         * 같은 막대의 두 선택지로 안 읽힘 ([bigButton] 참고).
         * v2(2-9 · 2-11)의 버튼은 흰 알약 52 · 반경 14다.
         */
        const val BAR_BUTTON_RADIUS = 14
        const val BAR_BUTTON_HEIGHT_DP = 52
        const val BAR_BUTTON_TEXT_SP = 16f

        // 표시 색은 [Look] 보유. 테두리·배지·쉴드·가드 알림이 **같은 표**를 봐야
        // 한 앱으로 읽힘 — 예전에는 여기와 Shield에 색이 따로 적혀 있어서, 한쪽만 고치면
        // 같은 "주의"가 화면마다 다른 노랑이 되곤 했음.
        /**
         * 조회에서 "모름"이 나온 주소를 다시 물어보기까지의 시간.
         * 처음 10분으로 뒀을 때 판정이 생긴 **뒤에도** 최대 10분간 색 미부착.
         * 조회는 서버 DB 읽기라 저렴하므로 짧게 설정.
         */
        const val PEEK_RETRY_MS = 20_000L   // 서버가 클릭 전 판정을 미리 해 둠(5초 안팎) → 그 뒤에 재질의
        /** href 추출 실패를 직전 값으로 메워주는 시한. 슬롯이 광고를 돌리면 끊겨야 함 */
        const val HREF_STICKY_MS = 1_500L
        /** "광고" 배지의 높이(dp). 피그마 v2 2-1 Label/Ad는 15 Bold + 위아래 8 = 31 */
        const val BADGE_H = 31
        /** "광고" 배지와 투터치 안내 조각 사이 틈(dp) — 같은 줄에 나란히 */
        const val TWO_TAP_HINT_GAP = 8
        const val TWO_TAP_HINT_TEXT = "한 번 더 누르면 열려요"

        /** 시안 2-7 Toast/Returned — 「돌아가기」로 원래 화면에 다다른 뒤 */
        const val RETURN_TITLE = "원래 화면으로 돌아왔어요"
        const val RETURN_BODY = "위험한 이동을 막았어요"

        /**
         * 돌아가기를 시키고 안내를 띄울 때까지의 여유.
         * 뒤로 가기가 끝나기 전에 띄우면 안내가 떠나는 페이지 위에 잠깐 보인다
         */
        const val RETURN_NOTICE_DELAY_MS = 350L
        /** 배지와 테두리 바깥선 사이 여백(dp). 선 두께([BORDER_W] 4)가 포함되므로 선 안쪽 틈은 이보다 4 작음 — 8이면 선에 붙어 보여 12 */
        const val BADGE_INSET = 12

        /**
         * 알림 글자에서 설치 파일 이름을 뽑는 자리.
         *
         * 공백·따옴표를 뺀 덩어리가 `.apk`로 끝나면 파일 이름으로 간주. 보통 앱은 알림에
         * `.apk`를 적을 일이 없어서, 이 한 조건만으로도 오탐이 사실상 없음.
         */
        val APK_NAME = Regex("""[^\s"'/\\]+\.apk""", RegexOption.IGNORE_CASE)

        /** 같은 파일로 다시 경고하지 않는 시간. 크롬은 진행률마다 알림 갱신 */
        const val APK_REPEAT_MS = 30_000L


        /**
         * 알림에서 읽은 파일 이름을 설치 화면에서 쓸 수 있는 시간.
         * 받자마자 누르는 것이 보통이라 넉넉히 잡되, 옛 이름이 엉뚱한 설치에 붙지 않게 기한 설정.
         */
        const val APK_FROM_NOTICE_MS = 10 * 60_000L

        /**
         * 브라우저에서 본 주소를 설치 파일의 **출처**로 인정하는 시간.
         * 이보다 오래됐으면 "받은 곳을 알 수 없어요"로 안내 — 아침에 본 뉴스 사이트를
         * 저녁에 연 파일의 출처로 붙이면 그건 거짓말.
         */
        const val APK_SOURCE_MS = 3 * 60_000L

        /** 설치 화면이 글자를 그릴 때까지 기다리는 시간. 이벤트 시점에는 아직 비어 있음 */
        const val INSTALLER_READ_MS = 450L

        /** 위험 판정 광고를 클릭 전에 덮을 때 적는 한 줄 (spec.md 1 · 마스킹 + 설명 문구) */
        const val MASK_TEXT = "위험한 광고를 가렸어요 · GuADian이 미리 막았어요"

        /** 설치 화면의 버튼·머리말. 앱 이름을 고를 때 제외 */
        val INSTALLER_WORDS = setOf(
            "설치", "취소", "열기", "완료", "업데이트", "앱 설치", "설치 안 함",
            "이 앱을 설치하시겠습니까", "기존 앱을 이 앱으로 바꾸시겠습니까"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 트리 순회는 노드마다 대상 앱으로 IPC가 오가고, **그 앱이 바쁘면 호출이 통째로 정지.**
     * 실측: 노드 144개를 읽는 데 11.8초 걸린 적 있음(페이지 로딩 중인 크롬).
     * 메인 스레드에서 돌리면 그동안 오버레이도 이벤트 처리도 전부 멈추므로 별도 스레드로 분리.
     */
    private val scanThread = HandlerThread("ad-scan").apply { start() }
    private val scanHandler = Handler(scanThread.looper)

    /**
     * 스크롤 추적은 **스캔과 다른 스레드**에서 가동.
     *
     * 같은 스레드에 두었을 때 전체 순회가 도는 동안 추적이 통째로 정지. 실측 로그에서 8ms마다
     * 찍히던 표본이 92ms·227ms씩 끊겼고, 그 사이 화면은 계속 흐르니 테두리만 제자리에 얼어붙어
     * 기사 위로 밀려남. 추적 한 번은 0.3~1.5ms짜리라 순회를 기다릴 이유 없음.
     */
    private val trackThread = HandlerThread("ad-track").apply { start() }
    private val trackHandler = Handler(trackThread.looper)

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val vibrator by lazy { getSystemService(VIBRATOR_SERVICE) as Vibrator }
    private val scanner = AdScanner()
    private val nav = NavigationGuard()

    /**
     * 위험도 판정. [HttpJudge]가 서버(server/main.py) 호출.
     * 캐시를 앞에 둬서 같은 사이트를 다시 만나면 서버 질의 없음.
     * 서버 없이 화면만 볼 일이 있으면 [StubJudge]로 교체 가능.
     */
    private val http = HttpJudge()
    private val judge: Judge = CachingJudge(http)

    // ── 사전 표시 (테두리 색) — 스캔 스레드 전용 ──────────────────────
    /**
     * 화면 광고의 href → 위험도("LOW"/"MEDIUM"/"HIGH"). 조회로 확인된 것만 보관.
     * 쓰기는 스캔 스레드, 읽기는 클릭 이벤트(메인)에서도 하므로 동시성 맵 필요.
     */
    private val riskMarks = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** "모름"이 나온 href를 언제까지 다시 안 물어볼지 */
    private val noneUntil = HashMap<String, Long>()
    private var lastMarkRegions = emptyList<Rect>()
    private var lastMarks = emptyList<String?>()
    /** 영역별로 마지막에 읽어낸 href와 그 시각. 추출이 간헐적으로 실패할 때 잠깐 재사용 */
    private var lastHrefs = emptyList<String?>()
    private var lastHrefAt = emptyList<Long>()
    /** 조회 응답·새 판정이 오면 참 — 다음 스캔이 색을 재계산 */
    private var marksDirty = false

    /**
     * 광고가 데려간 페이지를 덮는 창. 버튼 두 개가 하는 일은 기존 "뒤로 가기" 안내와 동일 —
     * 둘 다 [NavigationGuard]의 안내를 꺼야 함. 안 그러면 남은 안내 시간(12초) 동안
     * 다음 스캔이 곧바로 쉴드를 재표시.
     */
    private val shield by lazy {
        Shield(
            this,
            onLeave = {
                pendingJudge?.let { handler.removeCallbacks(it) }
                clickJudge?.cancel()
                // 다른 앱에 끌려온 것이면 한 번으로는 탈출 불가 —
                // "한 번 더 누르면 종료"인 앱이 있어 화면이 그대로일 때만 재시도.
                val leaves = nav.leavesApp
                scanHandler.post { nav.dismiss() }
                if (leaves) leaveApp(LEAVE_APP_TRIES) else performGlobalAction(GLOBAL_ACTION_BACK)
                // 시안 2-7 「복귀 완료」 — 되돌리고 끝내면 화면만 바뀌어 보여 무슨 일이
                // 있었는지 모른다. 돌아온 것과 막은 것을 한 줄씩 말해 둔다
                handler.postDelayed({
                    showNotice("✓", RETURN_TITLE, RETURN_BODY, Look.INK_SOFT)
                }, RETURN_NOTICE_DELAY_MS)
            },
            // "그냥 두기"는 **안내를 끄지 않음.** 쉴드가 걷힌 자리를 기존 버튼 막대가
            // 이어받아야 하기 때문 — 페이지를 보겠다는 것이지 나갈 길을 버리겠다는 뜻 아님.
            // 판정만 취소. 사용자가 이미 결정했으니 서버를 부를 이유 없음.
            onStay = {
                pendingJudge?.let { handler.removeCallbacks(it) }
                clickJudge?.cancel()
                onShieldStay()
            },
        )
    }

    /** 쉴드가 지금 덮고 있는 곳. 「그래도 보기」를 눌렀을 때 어디였는지 알기 위함 */
    private var shieldHost: String? = null

    /**
     * ⑧⑨ 주의 안내를 **보고도** 「그래도 보기」로 들어갔다. 보호자에게 한 줄.
     *
     * 착지 판정 시점이 아니라 여기인 이유 — 안내를 보고 돌아간 사람과 그대로 간 사람은
     * 다른 사건이고, 보호자가 알아야 할 것은 뒤쪽이다. 판정 기록은 이미 [report]가
     * 남겨 두었으므로 여기서는 **알림용 한 줄**만 더한다 (spec.md Part 03 · 8·9).
     */
    private fun onShieldStay() {
        Family.logEvent(
            this, "MEDIUM", "주의 안내를 보고도 사이트로 들어갔습니다.",
            shieldHost, blocked = false,
            alert = Family.Alert.PROCEED
        )
    }

    // ── 이식 기능 ①⑦ 검색결과 위험도 ─────────────────────────────────────────
    //
    // 이 서비스가 이 기능에 대해 아는 것은 아래 몇 줄이 전부. 판정·배지·관문은
    // 전부 `com.flyai.adalert.serp` 안에서 완결 ([SerpFeature] 참고).

    /** serp와 레이어2가 함께 쓰는 스코프. 서비스가 죽으면 함께 취소됨 */
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * **서비스 컨텍스트** 전달 — applicationContext면 오버레이 창에 토큰이 없어
     * BadTokenException으로 사망. apiKey가 비면 규칙만으로 돌고, 처음 보는
     * 사이트는 '확인 안 됨'으로 남아 아무것도 안 그림.
     */
    private val serp by lazy {
        SerpFeature(this, serviceScope, onRiskTapped = { onSearchRisk() })
    }

    // ── 이식 기능 ② 고위험 광고 마스킹 ───────────────────────────────────────

    /** 닫기 X가 접근성 트리에 없는 광고를 덮음 ([AdCoverOverlay] 참고) */
    private val adCover by lazy { AdCoverOverlay(this).also { it.onAutoTap = { onMaskTap() } } }

    /** 같은 광고를 연타해도 기록은 한 번만 */
    private var lastMaskLogAt = 0L

    /**
     * 위험 판정으로 가려 둔 광고 클릭 (spec.md 1 · 차단). 가드와 같은 처리 —
     * 막았다고 안내하고, 보호자에게 "누를 뻔했다"를 기록. 덮개가 가드보다 위에 있어
     * 가드의 [onGuardTap]은 이 터치를 못 받음.
     */
    private fun onMaskTap() {
        showNotice("⛔", "위험한 광고라 막았어요", "눌러도 열리지 않아요 · 가족에게도 알렸어요", Look.DANGER)
        val now = SystemClock.uptimeMillis()
        if (now - lastMaskLogAt < 10_000L) return
        lastMaskLogAt = now
        val id = shownMarks.indexOf("HIGH").takeIf { it >= 0 }?.let { shownIds.getOrNull(it) }
        Log.i(TAG, "마스킹 막음 id=${id?.take(80)}")
        Family.logEvent(
            this, "HIGH", "위험하다고 확인된 광고를 눌렀습니다.",
            id?.let { WebNode.hostOf(it) }, blocked = true,
            alert = Family.Alert.DOMAIN
        )
    }



    private var overlay: FrameLayout? = null
    private var controls: LinearLayout? = null

    /** 미리보기로 띄운 막대인지 여부. 그렇다면 스캔이 걷지 않음 (개발 빌드 전용) */
    private var barPinned = false
    private var screenRect = Rect()
    /** 마지막 스캔이 광고를 잘라낸 고정 띠([AdScanner.Result.coverBands]). 추적 스레드가 읽음 */
    @Volatile private var coverBands: List<Rect> = emptyList()

    /** 메인에서 쓰고 스캔 스레드에서 읽음 */
    @Volatile private var shownRegions = emptyList<Rect>()
    /** 지금 그려진 테두리들의 사전 표시. 색만 바뀐 경우를 알아채는 용도 */
    private var shownMarks = emptyList<String?>()
    /** 영역별 href의 스냅샷. 클릭 노드에서 href를 못 읽을 때 이걸로 보완 */
    @Volatile private var shownHrefs = emptyList<String?>()

    /**
     * 마지막으로 광고가 보이던 순간의 href들. 클릭 **이벤트 자체가 안 오는** 탭이 있어서
     * (실측: 같은 라벨 광고도 올 때와 안 올 때가 있음), 이동이 일어났는데 클릭 주소가 없으면
     * 직전 화면의 광고가 하나뿐이었을 때 그것을 클릭된 광고로 간주.
     */
    private var lastVisibleHrefs = emptyList<String>()
    private var lastVisibleHrefsAt = 0L

    /** [lastVisibleHrefs]를 만든 영역들과 그때의 호스트. 그 광고가 **아직 화면에 있는지** 확인하는 기준 */
    private var lastVisibleRegions = emptyList<Rect>()
    private var lastVisibleHost: String? = null

    /** 광고가 마지막으로 **화면에 있던** 시각. 링크를 읽었는지는 무관 */
    @Volatile private var lastAdSeenAt = 0L

    /** 직전 스캔에서 읽은 주소창. 같은 사이트 안의 이동을 알아채는 용도 */
    private var lastBarUrl: String? = null

    /**
     * 그 주소를 본 시각. **설치 파일의 출처를 말할 때** 참조 —
     * 한참 전에 본 뉴스 사이트를 "여기서 받았다"고 말하면 그건 거짓말.
     */
    private var lastBarAt = 0L

    /** 두 주소가 같은 곳을 가리키는지 여부 — 호스트+경로만 비교 (쿼리는 노출마다 붙음) */
    private fun sameTarget(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun key(u: String) = u.substringAfter("://").substringBefore('?').trimEnd('/')
        return key(a).equals(key(b), ignoreCase = true)
    }

    /**
     * 영역별 **광고 식별자**. 대개 href와 같지만, 매체 트래커 광고(cyad류)는 href에
     * 광고주 정보가 없어 **소재 이미지 주소**가 식별자. 색 조회(riskMarks·peek),
     * 서버에 남기는 매핑(click_url), 가드의 통과 허락이 전부 이 축으로 동작.
     * href는 "어디로 가는가"(앱 점프 판정 대상), 식별자는 "어느 광고인가"로 역할이 다름.
     */
    @Volatile private var shownIds = emptyList<String?>()
    private var lastVisibleIds = emptyList<String>()
    private var lastVisibleIdsAt = 0L

    /** 클릭 순간 잡아둔, 누른 광고의 식별자 */
    @Volatile private var adClickId: String? = null

    /**
     * href → 소재 이미지. 이미지 추출도 href처럼 간헐적으로 실패(실측) → 실패한
     * 스캔마다 식별자가 이미지↔href로 널뛰면 색이 깜박임. href는 노출마다 바뀌는
     * 값이라 같은 href에는 같은 이미지를 재사용해도 안전. 스캔 스레드 전용.
     */
    private val imgByHref = HashMap<String, String>()
    /** [shownRegions]와 1:1로 대응하는, 그 좌표를 만들어낸 노드. 스크롤 추적용 */
    @Volatile private var trackedAnchors = emptyList<Anchor?>()
    /** 남은 추적 횟수. 0이 되면 추적 중단 */
    @Volatile private var trackTicks = 0
    /** 추적 루프 가동 여부 (중복 실행 방지) */
    @Volatile private var tracking = false

    /**
     * 영역별로 좌표가 **마지막으로 실제로 바뀐** 시각. 표본을 찍은 시각 아님.
     *
     * 접근성 트리의 좌표는 화면 주사율로 갱신 안 됨. 실측(크롬 + 연합뉴스TV, S25):
     * 스크롤이 한창일 때도 같은 값이 **평균 75ms** 동안 그대로 오다가 한 번에 도약.
     * 표본 시각으로 나누면 그 도약을 9ms 동안 일어난 일로 착각해 속도가 60px/ms까지 튐
     * (실제 스크롤은 3px/ms 언저리).
     */
    private var changedAt = LongArray(0)
    /** 영역별 세로 속도 (px/ms). 표본 사이를 메워 그릴 때 사용 */
    private var velocity = FloatArray(0)
    /** vsync 루프 부착 여부 */
    private var animating = false
    /** 좌표를 못 읽기 시작한 시각 (스캔 스레드 전용). 0이면 잘 읽히는 중 */
    private var lostAt = 0L
    /** 좌표를 놓쳐 잠깐 감춰 둔 상태인지 여부 */
    private var bordersHidden = false
    /** "그냥 두기"를 누른 광고에는 버튼 재표시 없음 */
    @Volatile private var ignored = false
    /** 이전 스캔이 아직 안 끝났으면 새로 시작 안 함 (느린 앱에서 요청이 쌓이는 것 방지) */
    @Volatile private var scanning = false

    private var lastScan = 0L
    private var scanQueued = false
    /** 스캔이 잘려서 직전 영역을 붙잡고 있은 횟수 (스캔 스레드 전용) */
    private var truncatedHolds = 0
    /** 광고가 안 보이기 시작한 시각. 0이면 지금 보이는 중 (스캔 스레드 전용) */
    private var emptySince = 0L

    /**
     * 이번 방문에서 쉴드를 이미 보여준 사이트. 같은 사이트에 재표시 없음.
     * 판단은 스캔 스레드에서, 읽기·쓰기는 메인 스레드에서도 발생.
     */
    @Volatile private var shieldDoneSite: String? = null

    /** 리다이렉트가 멎기를 기다리는 도착지 대조. 새 홉이 오면 취소하고 재예약 */
    private var pendingJudge: Runnable? = null

    /** 진행 중인 클릭의 판정. 메인 스레드 전용 */
    private var clickJudge: ClickJudge? = null

    /** 광고 영역 안에서 클릭이 일어난 시각 */
    @Volatile private var adClickedAt = 0L
    /** 이 시각까지 광고 표시를 걷어 둠([CLICK_CLEAR_MS]). 메인 스레드 전용 */
    private var clickClearUntil = 0L

    /** 그때 누른 광고가 가리키던 주소. 앱으로 튀었을 때 판정할 유일한 단서 */
    @Volatile private var adClickTarget: String? = null

    /** 주소창에서 광고망 도메인(doubleclick 등)을 마지막으로 본 시각 */
    private var adNetSeenAt = 0L

    /**
     * 클릭 이벤트를 한 번이라도 받아봤는지 여부.
     *
     * 브라우저가 웹 요소의 클릭을 접근성 이벤트로 안 보내는 환경 존재 가능.
     * 그때 "광고 클릭이어야 한다"를 걸면 쉴드가 **영영 안 뜸.** 한 번도 못 받았으면
     * 이 조건을 쓸 수 없는 환경으로 보고 예전처럼 동작.
     */
    @Volatile private var sawAnyClick = false

    /** 앱으로 넘어간 것에 쉴드를 이미 보여준 패키지 */
    @Volatile private var shieldDonePkg: String? = null

    /**
     * 지금 떠 있는 안내가 **광고 클릭에서 비롯된 것인지 여부.**
     *
     * 매 스캔마다 다시 재면 안 됨 — 클릭 창([AD_CLICK_WINDOW_MS])이 지나는 순간
     * 안내가 저절로 소멸. 안내가 시작될 때 한 번 정하고 그대로 유지.
     */
    private var promptFromAd = false
    private var wasShowingBack = false
    /** 마지막으로 알림음·진동을 울린 시각 (메인 스레드 전용) */
    private var lastAlertAt = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 시안에는 2.5처럼 반 픽셀짜리 값 존재 */
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    // ── 스캔 주기 관리 ─────────────────────────────────────────────────────────

    /** 스로틀 구간에 들어온 요청을 버리지 않고 뒤로 연기 (버리면 지연 로드 광고를 통째로 놓침) */
    private val trailingScan = Runnable {
        scanQueued = false
        postScan()
    }

    private val lazyRescan = Runnable { requestScan() }

    /** 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 재확인하고, 사라졌을 때만 경고를 해제 */
    private val recheck = Runnable { postScan() }

    private val dumpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scanHandler.post { TreeDumper.dump(this@AdDetectService, rootInActiveWindow) }
        }
    }

    /**
     * 시연·확인용 미리보기. 아직 감지 기능이 없는 화면(설치 파일 위험 등)을 눈으로 보려면
     * 실제 상황을 만들어야 하는데, 그건 발표장에서 재현 불가. 개발 빌드에서만 등록
     * ([dumpReceiver]와 같은 이유로 배포 빌드에는 없음).
     *
     *   adb shell am broadcast -a com.flyai.adalert.PREVIEW --es screen apk
     */
    private val previewReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("screen")) {
                "apk" -> shield.showApkRisk("clean_booster.apk", "unknown-download.site")
                "medium" -> {
                    shield.showAnalyzing("https://example.com/preview")
                    shield.show(
                        Verdict(
                            Risk.MEDIUM,
                            "광고와 콘텐츠 구분이 어려운 화면이에요. 낯선 주소로 이동하려고 해요.",
                            "낯선 도메인 · 과도한 할인 문구"
                        )
                    )
                }
                "high" -> shield.showKnown("https://unknown-download.site/x")
                // 시안 09O-U — 판정이 오기 전, 또는 끝내 확인하지 못한 화면
                "working" -> shield.showAnalyzing("https://example.com/preview")
                // 버튼 막대는 강제 이동이 있어야 뜨는데, 그걸 실기기에서 만들기가 번거로움.
                // 스캔이 200ms마다 돌며 막대를 걷으므로 미리보기 동안에는 그걸 정지 —
                // 안 그러면 뜨자마자 사라져서 눈으로 볼 수 없음.
                "bar" -> { barPinned = true; showControls("돌아가기", emptyList(), true) }
                "skip" -> { barPinned = true; showControls("건너뛰기", emptyList(), false) }
                "unpin" -> { barPinned = false; hideControls() }
                // 피그마 2-3 — 악성 광고 마스킹 (2-2 투터치 카드는 spec.md 1행 기준으로 제거됨)
                "mask" -> adCover.cover(listOf(Rect(60, 592, 1020, 892)), MASK_TEXT, dismissible = false)
                "unmask" -> adCover.hide()
                else -> Log.i(TAG, "미리보기: screen 값을 모르겠다")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 화면 덤프는 개발 빌드에서만 수신.
        //
        // `adb shell am broadcast`로 부르려면 리시버가 exported여야 하는데, exported라는 것은
        // **설치된 아무 앱이나** 방송 한 번으로 화면 전체를 파일로 떨구게 시킬 수 있다는 뜻.
        // 접근성 서비스가 보는 화면에는 주소창·입력 중인 글·메시지 내용이 그대로 들어 있고,
        // 덤프는 다른 앱도 읽을 수 있는 외부 저장소에 기록됨. 배포 빌드에서는 등록 안 함.
        //
        // BuildConfig.DEBUG를 쓰지 않는 이유: 이 프로젝트는 buildFeatures.buildConfig가 꺼져 있어
        // (AGP 8부터 기본값) BuildConfig 자체가 생성 안 됨. 매니페스트의 debuggable 플래그가
        // 같은 것을 가리키면서 빌드 설정은 무변경.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val filter = IntentFilter(TreeDumper.ACTION)
            val preview = IntentFilter("com.flyai.adalert.PREVIEW")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dumpReceiver, filter, Context.RECEIVER_EXPORTED)
                registerReceiver(previewReceiver, preview, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(dumpReceiver, filter)
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(previewReceiver, preview)
            }
        }
        // 보호자가 설정을 바꾼 순간 반영되어야 한다 — 앱을 다시 켤 때까지 기다리게 하면
        // 껐다고 생각한 것이 계속 동작한다. 값 자체는 이 폰의 저장소에 거울로 남으므로
        // ([Family.watchSettings]) 판정 시점에는 네트워크를 기다리지 않는다
        settingsWatch = Family.watchSettings(this)
        Log.i(TAG, "service connected")
    }

    /** 가족 보호 설정 구독. 서비스가 살아 있는 동안만 */
    private var settingsWatch: com.google.firebase.firestore.ListenerRegistration? = null


    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // 검색결과 위험도는 **다른 분기보다 앞에서** 수신. 구글 앱은 이 서비스의
        // targetApps에 없어서, 뒤에 두면 이벤트가 거기까지 도달 불가.
        // 관심 없는 앱이면 SerpFeature가 스스로 걸러 아무 일도 안 함.
        serp.onEvent(event, pkg)
        if (pkg == packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            onNotification(event, pkg)
            return
        }

        // 설치 확인 화면이 앞으로 나옴 — 여기가 **진짜 위험한 순간**.
        // 파일을 받아 둔 것만으로는 아무 일도 없지만, 이 화면에서 한 번 더
        // 누르면 앱 설치됨. 알림 경로(위)는 무음 모드에서 오지 않으므로 이쪽이 주 경로.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg.contains("packageinstaller")
        ) {
            onInstaller(event)
            return
        }

        // **광고를 눌러서 온 것인가**를 가리는 근거.
        //
        // 호스트가 바뀌었다는 것만으로 덮으면 탭을 갈아타거나 주소를 직접 쳐 넣은 것에까지
        // 쉴드 표시. 그건 광고와 아무 상관이 없어서 방해만 됨.
        // 클릭이 일어난 자리가 우리가 이미 잡아둔 광고 영역 안이면 그때만 광고 클릭으로 간주.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            sawAnyClick = true
            val src = event.source
            val b = Rect()
            src?.getBoundsInScreen(b)
            val onAd = b.width() > 0 && b.height() > 0 &&
                shownRegions.any { Rect(it).intersect(b) }
            // 누른 광고가 어디로 가는지를 노드가 들고 있을 수 있음. 있으면 **앱으로 튀어도**
            // 판정할 대상이 생김 — 그쪽은 주소창이라는 것이 없어 지금은 판정 불가.
            // extras 값은 get()으로 받아 toString. getString으로 읽으면 값이 String이
            // 아닐 때 Bundle이 노드마다 ClassCastException 스택을 찍어 로그를 뒤덮음.
            // 누른 노드가 이미지면 targetUrl은 **이미지 주소** (실측:
            // `adimg.nate.com/.../cp_a_0421_640x400.png`). 링크의 목적지는 그 위 어딘가에 있으므로
            // 조상을 훑어 올라감. 조상에도 없으면(클릭 노드가 큰 컨테이너였던 실측 사례:
            // target=null → click_url이 안 가서 식별키가 영영 저장 안 됨) 화면 표시용으로
            // 이미 뽑아둔 그 영역의 href 사용.
            val regionIdx = shownRegions.indexOfFirst { Rect(it).intersect(b) }
            val target = adTargetFrom(src) ?: shownHrefs.getOrNull(regionIdx)
            if (onAd) {
                adClickedAt = SystemClock.uptimeMillis()
                adClickTarget = target
                adClickId = shownIds.getOrNull(regionIdx) ?: target
                // 통과 허락이 살아 있던 클릭인지 — revokePass가 지우기 전에 확보
                val passLive = adClickedAt - passAt < NOTICE_MS
                // 클릭이 광고에 **실제로 닿았다** = 투터치의 두 번째 터치. 허락은 여기서 끝 —
                // 나왔다가 같은 광고를 또 누르면 4초 안이라도 다시 투터치.
                revokePass()
                // 떠나는 화면의 표시는 지금 걷음([CLICK_CLEAR_MS]) — anchor lost 대기 불요.
                // 조건은 실제로 광고로 들어가는 클릭뿐(허락 통과 · 투터치 꺼짐). 컨테이너
                // 노드의 클릭도 onAd로 잡히므로, 무조건 걷으면 일반 탭에 가드가 1초 빔
                if (passLive || !twoTouchOn()) {
                    clickClearUntil = adClickedAt + CLICK_CLEAR_MS
                    lastAlertAt = adClickedAt   // 복원 시 재알림(소리·표시 수) 억제
                    setAdRegions(emptyList())
                }
            }
            Log.i(TAG, "clicked ${b.toShortString()} onAd=$onAd target=$target id=$adClickId")
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 페이지가 새로 뜸. 광고를 나중에 끼워 넣는 사이트를 위해 재스캔 예약.
            handler.removeCallbacks(lazyRescan)
            for (d in LAZY_RESCAN_MS) handler.postDelayed(lazyRescan, d)
            // 마크 메모도 폐기. 메모는 좌표만 보는데, 새로고침·페이지 이동으로 **같은
            // 좌표의 슬롯에 다른 광고**가 실리면 이전 광고의 href·색이 그대로 잔류
            // (실측: 기사 상단 구좌는 매번 정확히 같은 좌표라 메모가 영영 적중).
            scanHandler.post { marksDirty = true }
        }
        // 전체 순회는 스로틀에 걸려 스크롤 추종 불가. 좌표만 갱신하는 경로를 따로 깨움.
        requestTracking()
        requestScan()
    }

    // ── 스크롤 추적 ───────────────────────────────────────────────────────────

    /**
     * 스크롤 중에는 광고가 **무엇인지는 그대로고 어디 있는지만 변동.**
     * → 트리를 다시 훑지 않고 이미 잡아둔 노드의 좌표만 재독.
     * 전체 순회가 수백 ms인 데 비해 이건 노드 몇 개의 IPC라 스크롤 추종 가능.
     */
    private fun requestTracking() {
        if (shownRegions.isEmpty() || trackedAnchors.isEmpty()) return
        trackTicks = TRACK_TICKS
        if (!tracking) {
            tracking = true
            trackHandler.post(track)
        }
    }

    private val track = object : Runnable {
        override fun run() {
            // postDelayed로 이어 붙이므로 앞 회차가 느려도 요청이 안 쌓임
            val started = SystemClock.uptimeMillis()
            val rects = try {
                measureAnchors()
            } catch (e: Throwable) {
                null
            }
            val done = SystemClock.uptimeMillis()
            if (rects != null) {
                lostAt = 0L
                // 좌표를 읽는 동안에도 화면은 이동 중. IPC 왕복의 한가운데를 표본 시각으로 간주.
                // 이렇게 해두면 왕복이 느린 기기에서도 예측이 저절로 그만큼 앞당겨짐.
                val at = started + (done - started) / 2
                handler.post { moveBorders(rects, at) }
            } else {
                // 한 번 못 읽었다고 곧바로 지우면, 전체 스캔이 다시 찾아올 때까지 반 초 넘게
                // 테두리 소실. 실측: 플링이 끝날 때마다 그렇게 깜빡임.
                // 그렇다고 붙잡고 있으면 기사 위에 잔류 → **감추고, 잠깐 대기.**
                if (lostAt == 0L) {
                    lostAt = done
                    Log.d(TAG, "anchor lost -> hide borders")
                    handler.post {
                        val now = SystemClock.uptimeMillis()
                        // 투터치 허락이 살아 있는 채로 앵커가 통째로 사라졌다 = 두 번째 터치로
                        // 광고에 들어가는 중. [CLICK_CLEAR_MS]를 여기서도 거는 이유 — 크롬은
                        // 구글 광고 iframe에 TYPE_VIEW_CLICKED를 주지 않아 클릭 쪽 걷어내기가
                        // 발동조차 하지 않는다(실기기 34회 전부 미발생 · 이슈 #24).
                        // 막지 않으면 바로 아래 requestScan()이 옛 페이지 좌표로 테두리를
                        // 되살려 빈 화면 위에 0.5초 남는다
                        if (passAt != 0L && now - passAt < NOTICE_MS) {
                            clickClearUntil = now + CLICK_CLEAR_MS
                            lastAlertAt = now   // 복원 시 재알림(소리·표시 수) 억제
                            setAdRegions(emptyList())
                        }
                        hideBorders()
                    }
                }
                handler.post { requestScan() }
                if (done - lostAt > TRACK_GRACE_MS) {
                    lostAt = 0L
                    handler.post {
                        trackedAnchors = emptyList()
                        setAdRegions(emptyList())
                    }
                }
            }

            if (trackTicks-- > 0 && shownRegions.isNotEmpty()) {
                trackHandler.postDelayed(this, TRACK_INTERVAL_MS)
            } else {
                tracking = false
                // 추적이 끝났는데 속도가 남아 있으면 그 값으로 계속 앞질러 그려 어긋남.
                // 흐려 둔 것도 원래대로 복원 필요 — 멈춘 화면에서는 실측 좌표가 곧 정답.
                handler.post { settleBorders() }
            }
        }
    }

    /**
     * 추적 중인 노드들의 현재 좌표 읽기. 스캔 스레드 전용.
     *
     * 움직이지 않았어도 그대로 반환 — 속도를 0으로 되돌리려면 "안 움직였다"는 사실도 필요.
     * @return 못 읽었으면 null
     */
    private fun measureAnchors(): List<Rect>? {
        val anchors = trackedAnchors
        val prev = shownRegions
        if (anchors.isEmpty() || anchors.size != prev.size) return null

        val out = ArrayList<Rect>(anchors.size)
        for (i in anchors.indices) {
            val anchor = anchors[i]
            if (anchor == null) { out.add(prev[i]); continue }
            // 하나라도 못 읽으면 이번 표본은 통째로 폐기. 어떻게 할지는 [track]이 결정.
            // **못 읽었다는 것 자체가 신호** — 광고가 화면에서 사라졌다는 뜻.
            val measured = anchor.bounds() ?: return null
            // 고정 헤더·푸터 밑으로 들어간 부분은 잘라냄 — 스캔([AdScanner.clipByCovers])과 같은 띠.
            // 다 가려지면 높이 0 — [layoutBorders]가 숨김. null(소실)로 다루면 나머지 테두리까지 깜빡임
            clipByBands(measured)
            // 합성 좌표(전체 화면 승격, 피드 항목을 늘린 것)는 노드를 따라 움직이면 안 됨.
            // 위에서 좌표를 읽은 것은 광고가 아직 살아 있는지 확인하기 위한 것.
            out.add(if (anchor.follow) measured else prev[i])
        }
        return out
    }

    /** [coverBands]와 겹치는 위·아래를 잘라냄. 위에서 오는 띠는 top을, 아래에서 오는 띠는 bottom을 민다 */
    private fun clipByBands(r: Rect) {
        for (b in coverBands) {
            if (!Rect.intersects(b, r)) continue
            if (b.top <= r.top) r.top = maxOf(r.top, b.bottom) else r.bottom = minOf(r.bottom, b.top)
        }
        if (r.bottom < r.top) r.bottom = r.top
    }

    /** 메인 스레드. 스로틀만 하고 실제 순회는 스캔 스레드에 위임. */
    private fun requestScan() {
        val now = SystemClock.uptimeMillis()
        val since = now - lastScan
        if (since >= SCAN_INTERVAL_MS) {
            handler.removeCallbacks(trailingScan)
            scanQueued = false
            postScan()
        } else if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(trailingScan, SCAN_INTERVAL_MS - since)
        }
    }

    private fun postScan() {
        lastScan = SystemClock.uptimeMillis()
        handler.removeCallbacks(recheck)
        if (scanning) return
        scanning = true
        scanHandler.post {
            try {
                scanWork()
            } catch (e: Throwable) {
                Log.w(TAG, "scan failed", e)
            } finally {
                scanning = false
            }
        }
    }

    /** 스캔 스레드. 트리를 읽기만 하고 화면은 무변경. */
    private fun scanWork() {
        val root = rootInActiveWindow
        if (root == null) {
            handler.post { clearAll() }
            return
        }
        val pkg = root.packageName?.toString()
        if (pkg == null || pkg == packageName || AdRules.isIgnoredPackage(pkg)) {
            Log.d(TAG, "skip pkg=$pkg")
            nav.reset()
            handler.post { clearAll() }
            return
        }
        // 재난문자·화면캡처처럼 잠깐 뜨는 시스템 창은 **없던 일로 처리** — 기록을 지우지도,
        // 앱 전환으로 세지도 않음. 창이 닫히면 보던 페이지가 그대로이기 때문.
        if (AdRules.isTransientOverlay(pkg)) {
            Log.d(TAG, "transient pkg=$pkg — 스캔 건너뜀")
            return
        }

        val screen = Rect().also { root.getBoundsInScreen(it) }
        val result = scanner.scan(root, screen, pkg)


        if (result.regions.isNotEmpty()) {
            Log.i(TAG, "ads=${result.reasons} rects=${result.regions.map { it.toShortString() }} " +
                "screen=${screen.toShortString()} host=${result.pageHost} pkg=$pkg")
        }
        // 브라우저가 자기 화면에 설치 파일 이름을 띄움 = 지금 받는 중.
        // 여기가 **가장 이른 자리**이고, 출처도 이때가 정확 — 보고 있는 페이지가 곧 출처.
        result.apkName?.let { name ->
            val now = SystemClock.uptimeMillis()
            if (name != lastApk || now - lastApkAt > APK_REPEAT_MS) {
                lastApk = name
                lastApkAt = now
                if (now - lastShieldedAt > APK_REPEAT_MS) {
                    lastShieldedAt = now
                    lastShieldedApk = name
                    val host = WebNode.hostOf(AddressBar.urlOf(root)) ?: result.pageHost
                    Log.i(TAG, "다운로드 감지 name=$name from=${host ?: "모름"}")
                    handler.post { shield.showApkRisk(name, host) }
                    // 억제는 파일 이름 단위 — 진행률마다 갱신되는 알림이 같은 사건이다
                    Family.logEvent(
                        this, "HIGH", "출처를 알 수 없는 설치 파일을 내려받았습니다: $name",
                        host, type = "apk", blocked = true,
                        alert = Family.Alert.APK_DOWNLOAD, alertKey = name
                    )
                }
            }
        }

        if (result.shoppingHost) {
            // 아무것도 안 뜨는 이유가 "못 찾아서"인지 "쇼핑몰이라 껐어서"인지 구분 필요
            Log.i(TAG, "shopping site, ads not shown: host=${result.pageHost}")
        }
        if (result.shadow.isNotEmpty()) {
            // 등급이 낮아 그리지 않은 근거. 실기기에서 오탐 0이 확인되면 L1으로 승격.
            Log.d(TAG, "shadow=${result.shadow} host=${result.pageHost}")
        }
        if (result.truncated) {
            Log.d(TAG, "scan truncated: visited=${result.visited} ${result.elapsedMs}ms")
        }

        // 예산이 모자라 도중에 끊긴 결과로는 새 영역을 추가하지 않고 직전 영역 유지.
        // 부분적으로만 훑은 화면에서 영역을 갱신하면 그 자체가 오탐이 되기 때문.
        //
        // 다만 **무한정 유지는 금지.** 무거운 페이지에서 스캔이 계속 잘리면 이미 사라진 광고의
        // 테두리가 영영 잔류. 실제로 인앱 브라우저에서 페이지를 옮긴 뒤에도 이전 페이지의 테두리가
        // 화면에 잔류. 몇 번까지만 붙잡고 그 뒤에는 부분 결과 수용.
        val holding =
            result.truncated && shownRegions.isNotEmpty() && truncatedHolds < MAX_TRUNCATED_HOLDS
        if (holding) truncatedHolds++ else truncatedHolds = 0
        val regions = if (holding) shownRegions else result.regions
        val regionAnchors = if (holding) trackedAnchors else result.anchors

        // 히스테리시스 — **나타날 때는 즉시, 사라질 때는 잠깐 대기.**
        // 스크롤 중에는 노드가 한 프레임 사라졌다 곧바로 돌아오는 일이 잦음. 그때마다 지웠다 그리면
        // 테두리가 깜빡이고 알림음까지 재발. 어르신에게는 그 깜빡임 자체가 "잘못 잡았다"로
        // 느껴져서, 맞게 잡은 표시까지 불신하게 만듦.
        var stableAnchors = regionAnchors
        val stable = when {
            regions.isNotEmpty() -> {
                emptySince = 0L
                regions
            }
            shownRegions.isEmpty() -> regions          // 원래 없었으면 그대로 없음
            else -> {
                val now = SystemClock.uptimeMillis()
                if (emptySince == 0L) emptySince = now
                if (now - emptySince < CLEAR_DELAY_MS) {
                    stableAnchors = trackedAnchors     // 유지하는 동안에도 스크롤 추적 필요
                    shownRegions                       // 아직 대기
                } else {
                    emptySince = 0L
                    regions
                }
            }
        }

        // 벽시계가 아니라 부팅 이후 경과 시간 전달. 자동 시각 보정이나 시간대 변경이 일어나면
        // 벽시계는 앞뒤로 점프해서, 12초짜리 안내가 즉시 걷히거나 영영 안 걷힘.
        // 주소창 우선. **크롬은 페이지를 넘길 때 웹 콘텐츠를 트리에서 먼저 걷어내지만,
        // 주소창에는 이미 새 주소가 올라와 있음** — 실측(2026-08-15) 274ms 먼저,
        // 공백이 길었던 회차에서는 593ms. 그 시간이 그대로 쉴드가 늦게 뜨는 시간.
        //
        // 링크 다수결(pageHost)은 웹이 돌아오고 순회가 끝나야 나오므로 여기서는 후순위.
        val barUrl = AddressBar.urlOf(root)
        val barHost = WebNode.hostOf(barUrl)
        // 광고망 도메인을 **경유 중** — 구글 광고는 doubleclick.net 같은 중간 페이지를
        // 거쳐 착지. 일반 링크가 여길 지나갈 일은 없으므로 이것만으로 광고 클릭 확정.
        // (실측: 퀴즈 미끼 광고가 클릭 이벤트도 안 주고 착지 주소에 표식도 없어서
        // 판별을 통째로 비껴감 — 경유지가 유일한 증거.)
        if (barHost != null && AdRules.isAdHost(barHost)) adNetSeenAt = SystemClock.uptimeMillis()
        // 주소창을 읽었다면 브라우저에서 페이지를 보고 있는 것. 이렇게 두면
        // [NavigationGuard]가 재는 "공백"의 뜻이 **웹 콘텐츠가 없던 시간**에서
        // **주소창이 없던 시간**으로 변경 — 탭 목록을 가르는 신호로는 그쪽이 더 직접적.
        // 페이지 이동은 주소창을 감추지 않으므로 공백이 0이고, 탭 목록은 주소창 자체가 소실.
        val showBack = nav.update(
            pkg,
            barHost ?: result.pageHost,
            result.sawWeb || barHost != null,
            SystemClock.uptimeMillis()
        )

        // 주소는 **스캔 스레드에서** 읽음. 노드 조회는 IPC라 메인 스레드에서 하면
        // 대상 앱이 바쁠 때 화면이 통째로 정지 (트리 순회를 여기로 뺀 것과 같은 이유).
        //
        // 쉴드는 **사이트 강제 이동일 때만** 표시. 광고가 쿠팡 앱 같은 다른 앱을 열어버린
        // 경우(`nav.leavesApp`)에는 주소라는 것이 없어 판정할 대상 없음 — 그쪽은 돌아가기 막대만.
        // 광고를 눌러서 왔는지 여부. 근거가 넷이고 **하나만 맞아도 인정.**
        //
        //  1) 도착한 주소에 광고망이 붙인 클릭 표식 존재 (gclid·utm_source 등).
        //     구글 광고를 포함해 대부분이 여기 해당.
        //  2) 광고 영역 안에서 접근성 클릭 이벤트가 방금 도착.
        //     구글 광고 iframe은 이 이벤트를 아예 안 주지만, 주는 광고도 있음.
        //  3) 광고망 도메인(doubleclick 등)을 주소창에서 방금 봄 — 경유 흔적.
        //  4) **방금까지 광고가 보이던 화면에서 옴.** 광고망별 규칙이 아니라 범용
        //     신호: 뉴스를 읽다가 다른 사이트로 넘어가는 경로는 거의 광고뿐이고
        //     (기사 이동은 같은 사이트 안이라 여기까지 안 옴), 1~3이 전부 비는
        //     광고가 실측으로 확인됨 — dable 네이티브(눈밑지방 콘텐츠팜): 착지에
        //     표식 없음, iframe이라 클릭 이벤트 없음, 주소창 경유 흔적 없음.
        //     오탐(기사 속 일반 외부 링크)의 비용은 저위험 판정이 조용히 걷혀 낮음.
        val fromAdClick =
            AdRules.looksLikeAdClick(barUrl) ||
            SystemClock.uptimeMillis() - adClickedAt < AD_CLICK_WINDOW_MS ||
            SystemClock.uptimeMillis() - adNetSeenAt < AD_CLICK_WINDOW_MS ||
            SystemClock.uptimeMillis() - lastAdSeenAt < AD_CLICK_WINDOW_MS
        // 안내가 시작되는 순간에 한 번 정하고, 그 안내가 살아 있는 동안 유지
        if (showBack && !wasShowingBack) promptFromAd = fromAdClick
        if (!showBack) promptFromAd = false
        wasShowingBack = showBack

        // 앱이 바뀌면 "이미 보여줬다" 해제
        if (pkg != shieldDonePkg) shieldDonePkg = null

        // 눌린 광고의 식별자·목적지를 **여기서** 확보. 이 아래의 marksFor가 지금
        // 화면(착지 페이지)의 광고로 "직전 화면 광고" 기억을 덮어쓰므로, 쉴드가 나중에
        // 물으면 착지 페이지에 광고가 있는 경우 방금 누른 광고가 아니라 그것이 나옴.
        val clickedIdNow = clickedId()
        val clickedHrefNow = clickedHref()

        // **확인된 안전 광고(LOW)만 쉴드 없이 통과.**
        //
        // 전에는 주의(MEDIUM)도 여기 포함 — "첫 터치 때 가드가 이미 물어봤으니 들어와서
        // 또 보여주면 같은 말 두 번"이라는 이유. 그 전제가 오류 — 가드의 말은 배지 옆
        // "한 번 더 누르면 열려요" 조각뿐, 무엇을 보고 위험하다 했는지도 나갈 길도 없음.
        // 투터치를 끈 폰에서는 그 조각조차 없음.
        //
        // 결과: 처음 눌렀을 때는 "위험합니다"가 뜨는데, 판정이 캐시된 뒤 다시 누르면
        // 아무것도 안 뜨고 그냥 진입 — 두 번째가 더 위험한데 경고가 사라지는 셈.
        //
        // 캐시가 있으면 판정이 즉시 도착(서버 호출 0회) — 대기 화면 없이 결론 카드가 바로 표시.
        // 같은 페이지에 머무는 동안의 재표시는 [shieldDoneSite]가 차단, 나갔다 다시 들어오면
        // 다시 표시 — 위험 판정이 난 곳이므로 그것이 맞음.
        val knownSafeClick = clickedIdNow?.let { riskMarks[it] } == "LOW"

        // 광고가 다른 앱을 열어버린 경우. 주소가 없어 판정은 불가하지만,
        // 낯선 앱에 끌려온 것이야말로 큰 글씨로 나갈 길을 보여줘야 하는 상황.
        // 앱으로 튄 경우는 클릭 표식을 볼 주소 없음. 다만 [NavigationGuard]가 이미
        // "브라우저에서 웹을 보다가 곧바로 넘어갔다"까지 확인한 상태고(홈·최근앱을 거쳐
        // 사용자가 직접 연 것은 reset으로 걸러짐), 그렇게 앱이 열리는 경로는 사실상
        // 광고나 딥링크뿐 → 여기서는 표식 불요구.
        //
        // 이미 보여준 앱은 여기서 null로 유지 — 웹의 shieldDoneSite와 같은 이유로,
        // 쉴드 분기에 들어갔다 조용히 빠져나오면 아래의 버튼 막대 분기까지 도달 불가.
        val shieldApp =
            if (showBack && nav.leavesApp && pkg != shieldDonePkg) {
                if (knownSafeClick) {
                    shieldDonePkg = pkg   // 창이 끝난 뒤 뒤늦게 뜨지 않도록
                    null
                } else pkg
            } else null

        // **광고가 같은 사이트 안에 내려놓는 경우** (매체 자사 광고 등).
        //
        // [NavigationGuard]는 사이트가 바뀔 때만 알림 — 같은 사이트 안의 이동은 기사를
        // 눌러 넘기는 것과 구분 불가하기 때문. → 여기서는 "광고가 보였다"가
        // 아니라 **도착한 주소가 그 광고의 링크 자체**임을 근거로 삼음. 기사 링크가
        // 광고 링크와 같을 수는 없으니 이 근거는 사이트가 같아도 혼동 없음.
        val sameSiteAd = !showBack && barUrl != null &&
            barUrl != lastBarUrl &&
            lastVisibleHrefs.any { sameTarget(it, barUrl) } &&
            SystemClock.uptimeMillis() - lastAdSeenAt < AD_CLICK_WINDOW_MS
        if (barUrl != null) { lastBarUrl = barUrl; lastBarAt = SystemClock.uptimeMillis() }

        val shieldUrl =
            if (sameSiteAd) {
                Log.i(TAG, "광고 링크와 같은 주소에 도착 — 같은 사이트 광고로 본다")
                barUrl
            } else if (showBack && !nav.leavesApp && promptFromAd) {
                // 주소창을 못 읽으면 스캐너가 역산한 호스트로 폴백. 경로가 없어 판정이
                // 무뎌지지만, 쉴드를 아예 안 띄우는 것보다는 나음.
                val u = barUrl ?: result.pageHost?.let { "https://$it" }
                // **이번 방문에서 이미 보여준 사이트면 여기서 null로 유지** — 같은 사이트에
                // 머무는 동안의 쉴드 재표시 방지 ([shieldDoneSite]).
                val site = u?.let { WebNode.hostOf(it) }?.let { WebNode.siteOf(it) }
                when {
                    site != null && site == shieldDoneSite -> null
                    knownSafeClick -> {
                        if (site != null) shieldDoneSite = site
                        null
                    }
                    else -> u
                }
            } else null

        val actionLabel =
            if (showBack || ignored) null else findAdAction(root, stable)?.label

        /**
         * 쉴드가 덮고 있는 페이지에서 **실제로 벗어났는지 여부.**
         *
         * 쉴드는 시간이 지났다고 걷히면 안 됨. 그런데 스캐너가 역산한 사이트가 비어 있을 때
         * (링크가 거의 없는 단일 페이지) 그것만 보고 걷으면, 하필 **피싱 랜딩페이지의 생김새가
         * 정확히 그 모양**이라 제일 위험한 화면에서 경고가 혼자 소멸.
         * → 사이트를 확실히 읽었고 그것이 다를 때만 벗어난 것으로 간주.
         */
        val nowSite = (barHost ?: result.pageHost)?.let { WebNode.siteOf(it) }
        // 앱 전환으로 뜬 쉴드는 사이트 없음([Shield.shownSite]가 null). 그때 이 조건을 걸면
        // 그 앱 안의 WebView 호스트가 "다른 사이트"로 읽혀 쉴드가 곧바로 걷힘.
        val leftShieldPage = shield.isShowing && shield.shownSite != null &&
            nowSite != null && nowSite != shield.shownSite ||
            // 앱 쉴드는 앱 기준으로 해제 — 사용자가 시스템 뒤로가기로 그 앱을 나가면 종료
            shield.shownAppPkg != null && pkg != shield.shownAppPkg

        // 사이트를 벗어나면 "이미 보여줬다" 해제. 같은 사이트에 머무는 동안에만 막는 표시.
        if (nowSite != null && nowSite != shieldDoneSite) shieldDoneSite = null

        // 테두리 색 사전 결정 — 광고의 href를 서버에 조회(판정 기록만, LLM 없음)해서
        // 판정된 곳으로 가는 광고면 클릭 전에 노랑(주의)·빨강(위험)으로 착색.
        val marks = marksFor(stable, stableAnchors, nowSite)
        handler.post {
            screenRect = Rect(screen)
            // 잘린 결과를 유지하는(holding) 동안에는 직전 띠 그대로 — 빈 결과로 덮어쓰면 추적이 잘라내기를 멈춤
            if (!holding) coverBands = result.coverBands
            trackedAnchors = stableAnchors
            setAdRegions(stable, marks)
            if (shownRegions.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)
            // spec.md 1 · (DB 재활용) 차단 → 마스킹. 위험하다고 이미 판정된 광고는 누르기 전에
            // 자리를 덮고 한 줄로 이유 표기. 가드가 터치를 삼키는 것과 별개로,
            // 눈에 보이는 소재 자체를 가림. 사라지면 [setAdRegions]가 해제.
            adCover.syncAuto(
                stable.filterIndexed { i, _ -> marks.getOrNull(i) == "HIGH" },
                MASK_TEXT
            )
            when {
                shieldUrl != null -> {
                    raiseShield(shieldUrl, clickedIdNow)
                    handler.postDelayed(recheck, 1000)
                }
                shieldApp != null -> {
                    raiseAppShield(shieldApp, clickedHrefNow, clickedIdNow)
                    handler.postDelayed(recheck, 1000)
                }
                // 페이지 실제 이동 (시스템 뒤로가기 등 우리 버튼을 거치지 않은 경로)
                leftShieldPage -> {
                    shield.hide()
                    hideControls()
                }
                // **떠 있으면 유지.** 안내(12초)가 끝났다고 걷지 않음 —
                // 쉴드는 잠깐 알려주고 사라지는 안내가 아니라 사용자가 결정을 내려야
                // 넘어가는 관문. 시간으로 걷히면 고위험 경고가 12초 뒤에 저절로
                // 사라지고 그 밑의 페이지가 그냥 열림.
                //
                // 화면이 멈춰 있으면 접근성 이벤트가 안 와 스캔이 영영 안 돌므로 자체 기동.
                shield.isShowing -> handler.postDelayed(recheck, 1000)
                // 막대는 **광고가 다른 앱을 연 경우에만** (이슈 #41). 웹 착지는 주의·차단이면
                // 쉴드에 돌아가기가 이미 있고, 일반 광고면 브라우저 뒤로가기로 충분한데
                // 막대까지 뜨면 광고를 볼 때마다 방해다. 앱에는 주소창도 뒤로가기도
                // 없어 여기만 돌아갈 길을 남긴다.
                showBack && nav.leavesApp -> {
                    showControls("돌아가기", stable, true)
                    // 안내는 시간이 지나면 스스로 걷혀야 하는데, 화면이 멈춰 있으면 이벤트가 오지 않아
                    // 다음 스캔이 영영 안 돎. 안내가 떠 있는 동안만 자체 기동.
                    handler.postDelayed(recheck, 1000)
                }
                // 닫을 수 있을 때만 막대 표시 (spec.md 4번). [findAdAction]이 광고 영역 안에서
                // X 또는 "건너뛰기" 컨트롤을 찾았을 때만 [actionLabel]이 차고, 못 찾으면 막대 없음.
                // [ignored]면 null — 처리한 뒤에도 광고 노드는 트리에 남아 있어서, 이 조건이 없으면
                // 200ms 뒤 다음 스캔이 같은 막대를 재표시. 광고가 사라지면 [setAdRegions]가 해제.
                actionLabel != null -> showControls(actionLabel, stable, false)
                // 쉴드는 여기서 걷지 않음 — 위의 [leftShieldPage]와 [clearAll]만이 해제
                else -> hideControls()
            }
        }
    }

    /**
     * 쉴드 표시 + 판정 예약.
     *
     * 스캔은 200ms마다 도는데 안내는 12초 동안 유지되므로, 막지 않으면 같은 페이지에
     * 판정을 수십 번 걸게 됨. 이미 그 주소로 덮고 있으면 아무것도 안 함.
     */
    /**
     * 방금 눌린 광고의 href(목적지). 클릭 이벤트가 알려준 것이 우선, 이벤트가 안 오는
     * 탭(구글 iframe·쿠팡 딥링크, 실측)은 직전 화면에 광고가 **하나뿐이었으면** 그것.
     * 여러 개였으면 어느 것인지 알 수 없으니 포기 — 틀린 답보다 없는 답이 나음.
     * 앱으로 튄 광고의 판정 대상이 이걸로 결정됨.
     */
    private fun clickedHref(): String? =
        (if (SystemClock.uptimeMillis() - adClickedAt < AD_CLICK_WINDOW_MS) adClickTarget
        else null)
            ?: lastVisibleHrefs.singleOrNull().takeIf {
                SystemClock.uptimeMillis() - lastVisibleHrefsAt < AD_CLICK_WINDOW_MS
            }

    /**
     * 방금 눌린 광고의 **식별자** ([shownIds]와 같은 축). 색 기억 조회(안전 통과 /
     * 아는 광고 재확인 생략)와 서버에 남길 매핑(click_url)이 전부 이것 사용.
     * 답을 구하는 곳이 여럿이면 구글 iframe처럼 한쪽에만 있는 구멍 발생 — 실제 사례 있음.
     */
    private fun clickedId(): String? =
        (if (SystemClock.uptimeMillis() - adClickedAt < AD_CLICK_WINDOW_MS) adClickId
        else null)
            ?: lastVisibleIds.singleOrNull().takeIf {
                SystemClock.uptimeMillis() - lastVisibleIdsAt < AD_CLICK_WINDOW_MS
            }

    /**
     * ⑦ 어르신이 위험한 검색 결과를 **눌렀다.** 보호자에게 한 줄 기록.
     *
     * 화면에 떠 있기만 한 위험은 기록하지 않는다. 검색 결과에 위험한 줄이 섞이는 것은
     * 흔한 일이고 어르신은 대개 누르지 않고 지나가는데, 그때마다 올리면 스크롤 한 번에
     * 보호자 폰이 울려 결국 알림 자체가 꺼진다. 올릴 만한 사실은 "눌렀다" 쪽이다.
     *
     * 관문이 나가는 길만 주므로 진행이 실제로 멈춘다 — blocked=true (이슈 #46 기준).
     * 호스트를 싣지 않는 것은 원칙 — 어르신이 무엇을 검색했고 무엇을 눌렀는지는
     * 보호자에게도 전송 안 함. 같은 결과의 반복 탭은 [SerpFeature]가 이미 걸러 낸 뒤라
     * 여기까지 오지 않는다.
     */
    private fun onSearchRisk() {
        Family.logEvent(
            this,
            risk = "HIGH",
            reason = "위험한 검색 결과를 눌렀지만 막았어요",
            host = null,
            type = "search",
            blocked = true,
            alert = Family.Alert.SEARCH,
        )
    }

    private fun raiseShield(url: String, clickId: String?) {
        // **한 번 보여준 사이트에는 이번 방문 동안 재표시 없음.**
        // 저위험이 스스로 걷힐 때 [NavigationGuard]의 안내를 끄긴 하는데, 그건 스캔 스레드로
        // 넘기는 비동기라 이미 돌고 있던 스캔은 아직 "안내 중"으로 인식. 실측: 자동 해제
        // 150ms 뒤에 쉴드가 한 번 더 뜸. 사이트로 막으면 스레드와 무관하게 확실.
        val site = WebNode.hostOf(url)?.let { WebNode.siteOf(it) }
        if (site != null && site == shieldDoneSite) return
        if (shield.isShowingFor(url)) return
        shieldDoneSite = site
        shieldHost = WebNode.hostOf(url)
        hideControls()   // 쉴드가 덮는 동안에는 아래 "뒤로 가기" 막대 자리 없음
        serp.hide()      // 화면을 덮은 경고 위에 배지까지 겹치면 어느 것도 읽히지 않음

        // "위험" 광고는 가드가 클릭 자체를 막으니 여기 올 일이 없어야 하지만, 가드가
        // 스크롤을 못 따라잡는 틈에 눌리면 여기서 결론 카드가 수신. 판정은 이미 있으므로
        // 서버 재질의 없음.
        if (clickId?.let { riskMarks[it] } == "HIGH") {
            shield.showKnown(url)
            return
        }

        val newClick = !shield.isShowing
        shield.showAnalyzing(url)

        // **요청은 클릭 즉시, 대조는 주소가 멎은 뒤.**
        //
        // 광고 클릭은 거의 언제나 추적 리다이렉트 경유. 실측(2026-08-15, 네이트 → 오늘의집):
        // link.ohou.se → ohouse.airbridge.io → ohou.se 를 1초 안에 세 번 경유.
        // 예전에는 주소가 [REDIRECT_SETTLE_MS] 동안 안 바뀌어야 요청 — 그 시간이 통째로 대기.
        // 서버 fetch_chain이 리다이렉트(HTTP·JS·meta)를 끝까지 따라가므로 첫 홉으로 바로 요청하고,
        // 주소가 멎으면 [ClickJudge.settle]에서 도착 사이트와 대조 — 다르면 그때 도착지로 재요청.
        // 단, 첫 홉이 트래커·광고망이면 보내지 않음([WebNode.earlyJudgeTarget]) — 서버가 열면 클릭 중복 집계.
        //
        // 덮는 것은 이미 첫 홉에서 완료(실측 231ms) → 사용자는 보호된 상태.
        // 리다이렉트가 계속되면 대조가 계속 밀리는데, 그때는 쉴드 자신의 12초 상한이 받아
        // "확인하지 못했습니다"로 전환.
        pendingJudge?.let { handler.removeCallbacks(it) }
        // **누른 광고의 href를 판정과 함께 전송.** 서버가 "이 광고 = 이 판정" 매핑을 남기고,
        // 이후 같은 광고를 클릭 없이 식별.
        val clickUrl = clickId
        // 같은 클릭의 다음 홉이면 진행 중인 것을 이어 씀. 새 클릭·이미 끝난 것이면 새로 시작
        val current = clickJudge?.takeIf { !newClick && !it.finished }
            ?: ClickJudge(url, clickUrl).also { clickJudge = it; it.start() }
        val settle = Runnable { current.settle(url) }
        pendingJudge = settle
        handler.postDelayed(settle, REDIRECT_SETTLE_MS)
    }

    /**
     * 클릭 한 번의 판정 진행. 메인 스레드 전용.
     *
     * 첫 홉 주소로 즉시 요청([start]) → 주소가 멎으면 도착지와 대조([settle]):
     *  - 도착지가 첫 홉 그대로, 또는 서버가 실제 도착한 사이트([Verdict.at])가 폰의 도착 사이트와 같음
     *    → 그 답 채택. 폰이 본 것과 서버가 따라간 곳이 같으므로 예전(도착지로 요청)과 판정 입력 동일
     *  - 다름(앱 딥링크·UA 분기로 서버가 다른 곳에 도착) 또는 미수신 → 도착지로 재요청 — 예전 경로
     *  - 첫 홉이 트래커라 즉시 요청을 못 했으면([WebNode.earlyJudgeTarget] null) 도착지로 요청 — 예전 경로
     * 답과 도착이 어느 순서로 와도 한 번만 결론([reconcile]).
     *
     * **저위험 선행 통보**([Judge.judge]의 onEarly): 서버가 등급을 확정하면 문장 전에 먼저 옴.
     * 같은 대조 기준을 통과하면 쉴드만 먼저 걷음([provisional]) — 기록·캐시·사전 표시는 결론이 맡음.
     */
    private inner class ClickJudge(private val firstUrl: String, private val clickUrl: String?) {
        private var early: Verdict? = null
        private var earlyDone = false
        private var provisional: Verdict? = null
        private var settledUrl: String? = null
        var finished = false
            private set

        fun start() {
            val target = WebNode.earlyJudgeTarget(firstUrl)
            if (target == null) {
                // 트래커·광고망 — 미리 열면 클릭 중복 집계. 주소가 멎은 뒤 도착지로 요청
                Log.i(TAG, "첫 홉이 트래커라 즉시 요청 생략: $firstUrl")
                earlyDone = true
                return
            }
            judge.judge(target, clickUrl, onEarly = { v -> onProvisional(v) }) { v ->
                earlyDone = true
                early = v
                settledUrl?.let { reconcile(it) }
            }
        }

        fun settle(url: String) {
            if (finished) return
            settledUrl = url
            provisional?.let { applyProvisional(it, url) }
            if (earlyDone) reconcile(url)
        }

        /** 사용자가 떠나거나 머물기로 결정 — 판정 결과 불필요 */
        fun cancel() { finished = true }

        private fun usable(v: Verdict?, url: String): Boolean {
            if (v == null || v.risk == Risk.UNKNOWN) return false
            val site = WebNode.hostOf(url)?.let { WebNode.siteOf(it) }
            // 대조는 at(실제 도착지) 우선 — site는 공용 호스팅이면 비어 있어 대조 불가
            val arrived = v.at ?: v.site
            return url == firstUrl || (arrived != null && arrived == site)
        }

        private fun onProvisional(v: Verdict) {
            if (finished) return
            provisional = v
            settledUrl?.let { applyProvisional(v, it) }
        }

        private fun applyProvisional(v: Verdict, url: String) {
            provisional = null
            if (finished || v.risk != Risk.LOW || !usable(v, url)) return
            // 저위험 = 쉴드 해제가 전부. 문장(reason·advice)은 저위험 화면에 쓰이지 않음
            Log.i(TAG, "저위험 선행 통보 → 쉴드 먼저 걷음 site=${v.site}")
            if (shield.isShowingFor(url)) shield.show(v)
        }

        private fun reconcile(url: String) {
            if (finished) return
            finished = true
            val v = early
            if (usable(v, url)) {
                Log.i(TAG, "첫 홉 판정 채택 site=${v?.site} 도착=${WebNode.hostOf(url)?.let { WebNode.siteOf(it) }}")
                deliver(v!!, url)
            } else {
                Log.i(TAG, "도착지로 요청 (첫 홉 답 site=${v?.site} risk=${v?.risk})")
                judge.judge(url, clickUrl, onEarly = { e ->
                    if (e.risk == Risk.LOW && shield.isShowingFor(url)) shield.show(e)
                }) { deliver(it, url) }
            }
        }

        private fun deliver(verdict: Verdict, url: String) {
            // 판정이 오는 사이에 사용자가 또 다른 페이지로 갔을 수 있음. 그때 도착한 답은
            // **다른 페이지의 판정**이므로 폐기 — 안 그러면 A의 "안전합니다"가 B 위에 뜸.
            // 선행 통보로 이미 걷혔으면 isShowingFor가 false — 두 번 표시 없음
            if (shield.isShowingFor(url)) shield.show(verdict)
            // 방금 붙은 판정을 사전 표시에도 반영 — 같은 광고주의 광고가 곧바로 색 획득
            if (verdict.risk != Risk.UNKNOWN) refreshMarks()
            report(verdict, url)
        }
    }

    /**
     * 광고가 다른 앱을 열어버렸을 때의 쉴드.
     *
     * 여기는 주소창이 없지만, **클릭할 때 광고 노드에서 읽어둔 목적지 주소**가 있으면
     * 그것으로 웹과 같은 "확인 중 → 판정" 흐름 진행. 없으면 판정 없이 결론 화면만 표시.
     */
    private fun raiseAppShield(pkg: String, clickHref: String?, clickId: String?) {
        if (pkg == shieldDonePkg) return
        val key = "app:$pkg"
        if (shield.isShowingFor(key)) return
        shieldDonePkg = pkg
        hideControls()

        // 클릭 이벤트가 없는 광고(쿠팡 딥링크 실측)도 직전 화면의 유일 광고 폴백으로 잡힌
        // 값 — 스캔 시점에 캡처해서 수신. 시간 창은 clickedHref 안에 있음.
        // 구글 광고면 `adurl=` 안의 목적지로 — 클릭 링크(aclk)를 서버가 다시 열면 클릭 중복 집계 +
        // 리다이렉트 추적 시간(실측 2026-08-23: 유튜브 앱으로 넘어간 구글 광고 17초). 트래커면 예전대로 href
        val target = clickHref?.let { WebNode.earlyJudgeTarget(it) ?: it }
        if (target == null) {
            // 판정할 주소가 없으면 화면을 덮지 않는다 — 「다른 앱이 열렸어요」 전면
            // 안내를 걷고 돌아갈 길만 막대로 남긴다 (이슈 #41). 덮개는 위험이 확인된
            // 곳의 물건이고, 위험 근거 없이 화면부터 가리면 멀쩡한 앱(쿠팡 등)일수록
            // 이 앱이 방해로 읽힌다. 이후 스캔은 아래 막대 분기가 이어받아 유지
            showControls("돌아가기", emptyList(), true)
            return
        }
        shield.showAnalyzing(target)
        shield.markApp(pkg)
        pendingJudge?.let { handler.removeCallbacks(it) }
        // 앱 전환에는 리다이렉트 홉 없음(주소는 클릭 순간에 이미 확정) — 디바운스 없이 즉시 요청.
        // 식별자를 딸려 보내야 서버가 "이 광고 = 이 판정" 매핑을 남김 (쿠팡 소재 이미지 등).
        // 저위험 선행 통보면 문장 전에 쉴드부터 걷음
        judge.judge(target, clickId, onEarly = { e ->
            if (e.risk == Risk.LOW && shield.isShowingFor(target)) shield.show(e)
        }) { verdict ->
            if (shield.isShowingFor(target)) shield.show(verdict)
            if (verdict.risk != Risk.UNKNOWN) refreshMarks()
            report(verdict, target)
        }
    }

    /**
     * 가족 계정에 연결돼 있으면 보호자가 볼 기록 저장.
     *
     * **주의·위험만** 기록 — 안전한 곳까지 남기면 보호자 화면이 방문 기록이 되어,
     * 보호가 아니라 감시가 됨. 계정에 연결돼 있지 않으면 [Family.logEvent]가
     * 아무 일도 안 하므로 여기서 별도 확인 없음.
     */
    private fun report(v: Verdict, url: String) {
        // spec.md 3 · 일반 광고는 기록도, 화면 표시도 없음 (이슈 #41 — 막대도 앱 이동 전용).
        if (v.risk == Risk.LOW) return
        if (v.risk != Risk.MEDIUM && v.risk != Risk.HIGH) return
        // HIGH는 쉴드가 「돌아가기」만 주고 덮으므로 **진행되지 않은 것이 사실** —
        // blocked=true로 남긴다. MEDIUM은 사용자가 「그래도 보기」를 고를 수 있어 false.
        // 안 그러면 리포트가 "차단 0건" 아래 "고위험 N건 자동 차단"을 보여준다 (이슈 #46)
        // 주의는 **기록만**. 안내를 본 것과 안내를 보고도 들어간 것은 다른 사건이라,
        // 알림은 「그래도 보기」를 고른 순간에 나간다 ([onShieldStay])
        Family.logEvent(
            this, v.risk.name, v.reason, WebNode.hostOf(url), v.type,
            blocked = v.risk == Risk.HIGH,
            alert = if (v.risk == Risk.HIGH) Family.Alert.DOMAIN else null
        )
    }

    /**
     * 영역별 사전 표시 계산. 스캔 스레드 전용.
     *
     * 영역이 그대로면 지난 결과 재사용 — href 추출은 노드 IPC 수십 번이라
     * 200ms마다 다시 하면 스캔이 무거워짐. 조회 응답이 오면 [marksDirty]로 재계산.
     */
    private var lastMarkTriedAt = 0L

    private fun marksFor(regions: List<Rect>, anchors: List<Anchor?>, host: String?): List<String?> {
        val nowT = SystemClock.uptimeMillis()
        // "광고가 보이던 화면에서 나갔다" 신호의 근거. **링크를 읽었는지와 무관하게**
        // 광고가 화면에 있었다는 사실만 확인 — 링크를 못 읽는 광고(dable 실측)가 바로
        // 이 신호를 가장 필요로 하는 광고인데, href 기준으로 재면 그때만 신호가 빔.
        if (regions.isNotEmpty()) lastAdSeenAt = nowT
        // href를 못 읽은 영역이 남아 있으면 메모를 쓰지 않고 1초마다 재시도.
        // 추출은 간헐적으로 실패하는데, 화면이 안 바뀌면 이 메모가 실패를 **얼려서**
        // 같은 광고가 어떨 땐 색이 붙고 어떨 땐 끝까지 파랑인 증상 발생.
        val frozenNull = lastHrefs.any { it == null } && nowT - lastMarkTriedAt > 1_000
        // 메모가 맞다고 믿을 수 있는 시간에 상한 설정. 네이트 배너는 **제자리 회전**
        // — 페이지 이동 이벤트도, 좌표 변화도 없이 소재만 교체. 상한이 없으면
        // 메모가 영영 적중해서 SKT 광고가 쿠팡의 초록 ✓를 상속 (실측 스크린샷).
        val expired = nowT - lastMarkTriedAt > MARK_RECHECK_MS
        if (regions == lastMarkRegions && !marksDirty && !frozenNull && !expired) {
            // 메모를 쓰더라도 "마지막으로 보였다"는 지금이어야 함. 스냅샷 시각을 그대로
            // 두면 화면이 오래 멎은 뒤 광고를 눌렀을 때 "최근에 보인 광고가 없다"가 되어
            // 클릭 폴백이 빔 — 배지가 주의라던 광고가 일반 판정 경로로 새던 실측 원인.
            if (lastHrefs.any { it != null }) lastVisibleHrefsAt = nowT
            if (lastVisibleIds.isNotEmpty() && shownIds.any { it != null }) lastVisibleIdsAt = nowT
            return lastMarks
        }
        lastMarkTriedAt = nowT
        marksDirty = false
        val now = SystemClock.uptimeMillis()
        val toAsk = LinkedHashSet<String>()
        val hrefAt = LongArray(regions.size)
        val hrefs = regions.indices.map { i ->
            val read = anchors.getOrNull(i)?.let { a ->
                runCatching { adHrefOf(a.raw()) }.getOrNull()
            }
            if (read != null) {
                hrefAt[i] = now
                read
            } else if (
            // href 추출은 노드 상태에 따라 **간헐적으로 실패** (실측: 같은 광고가
            // 한 스캔에서는 읽히고 다음 스캔에서는 null). 실패한 스캔이 하필 조회 응답
            // 직후면 받은 색을 영영 못 쓰므로 같은 자리면 직전 href로 보완 — 다만
            // **잠깐만**. 광고 슬롯은 같은 자리에서 광고를 돌려가며 보여줘서(네이트 상단
            // 배너), 기한 없이 물려주면 다음 광고가 이전 광고의 색을 그대로 상속.
            // 실제로 슬롯의 모든 광고에 ✓가 붙는 사고 발생.
                regions.getOrNull(i) == lastMarkRegions.getOrNull(i) &&
                now - (lastHrefAt.getOrNull(i) ?: 0L) < HREF_STICKY_MS
            ) {
                hrefAt[i] = lastHrefAt.getOrNull(i) ?: 0L
                lastHrefs.getOrNull(i)
            } else null
        }
        // 식별자: 매체 트래커 광고는 href에 광고주가 없으니 소재 이미지가 식별자.
        // 이미지를 못 읽으면 href 그대로 사용 — 서버가 못 알아봐 파랑이 되고,
        // 그것은 안전한 방향 (틀린 초록보다 파랑).
        val ids = regions.indices.map { i ->
            if (DBG_URLS) anchors.getOrNull(i)?.let { a -> runCatching { logAllUrls(a.raw()) } }
            val href = hrefs[i]
            // 링크가 쓸 만하면 그것이 정체성. 매체 트래커(광고주 정보 없음)와
            // **링크를 아예 못 읽는 광고**(dable 네이티브 실측: 안에 노출 집계 주소와
            // 이미지밖에 없음)는 소재 이미지가 유일하게 남는 정체성.
            if (href != null && !AdRules.isSlotTracker(href)) return@map href
            val img = href?.let { imgByHref[it] }
                ?: anchors.getOrNull(i)?.let { a ->
                    runCatching { imgUrlOf(a.raw()) }.getOrNull()
                }?.also {
                    if (imgByHref.size > 100) imgByHref.clear()   // 세션 내 무한히 크지 않게
                    if (href != null) imgByHref[href] = it
                }
            img ?: href
        }
        val marks = regions.indices.map { i ->
            val id = ids[i]
            val state = when {
                id == null -> null
                riskMarks.containsKey(id) -> riskMarks[id]
                (noneUntil[id] ?: 0L) > now -> null
                else -> { toAsk.add(id); null }
            }
            Log.i(TAG, "mark[$i] ${state ?: "-"} id=${id?.take(90)}")
            state
        }
        lastHrefs = hrefs
        lastHrefAt = hrefAt.toList()
        shownHrefs = hrefs
        shownIds = ids
        if (hrefs.any { it != null }) {
            lastVisibleHrefs = hrefs.filterNotNull().distinct()
            lastVisibleIds = ids.filterNotNull().distinct()
            lastVisibleHrefsAt = now
            lastVisibleIdsAt = now
            lastVisibleRegions = regions
            lastVisibleHost = host
        } else if (regions != lastVisibleRegions && host == lastVisibleHost) {
            // **같은 페이지에서** 기억해둔 광고가 화면에서 소실 — 스크롤로 지나간 것.
            // 이 기억이 남아 있으면 "직전 화면의 유일 광고" 폴백이 방금 누른 것이 아닌
            // 광고를 지목 (실측: 스크롤 6초 안에 다른 광고를 눌렀더니 위로 지나간
            // 상단 배너가 판정됨). 페이지가 넘어간 경우(호스트 다름)는 유지 —
            // 착지 화면에서 "방금 누른 광고"를 아는 것이 이 기억의 존재 이유.
            // 같은 자리 추출 깜박임(영역 그대로, 읽기만 실패)도 유지.
            lastVisibleHrefs = emptyList()
            lastVisibleIds = emptyList()
            lastVisibleHrefsAt = 0L
            lastVisibleIdsAt = 0L
        }
        lastMarkRegions = regions
        lastMarks = marks
        if (toAsk.isNotEmpty()) {
            val asked = toAsk.toList()
            // 응답이 오기 전에 같은 것을 또 묻지 않음
            asked.forEach { noneUntil[it] = now + PEEK_RETRY_MS }
            if (noneUntil.size > 300) noneUntil.clear()   // 세션 내 무한히 크지 않게
            http.peek(asked) { found ->
                scanHandler.post {
                    Log.i(TAG, "peek ${asked.size}개 물음 -> ${found.size}개 답 $found")
                    riskMarks.putAll(found)
                    if (found.isNotEmpty()) {
                        marksDirty = true
                        requestScan()
                    }
                }
            }
        }
        return marks
    }

    /**
     * 광고 영역 노드에서 광고의 href 탐색 (클릭 전).
     * 링크는 대개 영역 컨테이너의 **안쪽**에 있어 아래로 순회. 이미지 파일 주소는 건너뜀 —
     * 광고는 대개 이미지를 감싼 링크라 이미지의 src가 먼저 걸리기 때문.
     */
    private fun adHrefOf(node: AccessibilityNodeInfo): String? {
        var checked = 0
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(node to 0)
        // 한도는 깊이 8·120노드. 처음 4·40으로는 **구글 iframe 광고의 링크에
        // 아예 도달 불가** — iframe 문서를 거쳐 내려가야 해서 생각보다 깊음.
        // 영역이 바뀔 때만 도는 탐색이라 이 정도는 스캔 부담 없음.
        while (queue.isNotEmpty() && checked < 120) {
            val (cur, depth) = queue.removeFirst()
            checked++
            extrasUrl(cur)?.let { if (!isImageUrl(it)) return it }
            if (depth < 8) {
                for (i in 0 until cur.childCount) {
                    cur.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return null
    }

    /** 새 판정 부착 — "모름" 기억을 지워 화면 광고들의 색을 재조회하게 함 */
    private fun refreshMarks() {
        scanHandler.post {
            noneUntil.clear()
            marksDirty = true
            requestScan()
        }
    }

    /**
     * 누른 광고가 **어디로 가는지** 탐색.
     *
     * 누른 노드 자신이 이미지면 그 `targetUrl`은 이미지 파일 주소라 쓸모 없음.
     * 목적지는 그것을 감싼 링크에 있으므로 조상을 몇 단만 순회.
     * 이 값이 있으면 광고가 앱을 열어버려도 판정할 대상이 생김 —
     * 그쪽은 주소창이 없어 지금은 아무것도 판정 불가.
     */
    private fun adTargetFrom(node: AccessibilityNodeInfo?): String? {
        var cur = node
        var up = 0
        while (cur != null && up < 6) {
            val url = extrasUrl(cur)
            // 이미지 파일이 아닌 주소가 나오면 그것이 목적지
            if (url != null && !isImageUrl(url)) return url
            cur = cur.parent
            up++
        }
        return null
    }

    /**
     * 광고 영역 속 **소재 이미지** 주소 — 매체 트래커 광고의 식별자.
     *
     * 실측(2026-08-16, 네이트 기사 상단 구좌 10회): 같은 소재는 노출이 바뀌어도 이미지
     * 주소가 동일(`cp_c_0116_640x160.jpg` 4회 일치), 파일명에 광고주가 그대로 있음
     * (`cp`=쿠팡, `tmap`=티맵). 한 ads_no 아래 소재가 여럿이라(쿠팡 b·c) 이미지가 더 정밀.
     * adimg류(광고 서버의 이미지)만 신뢰 — 아무 이미지나 잡으면 추천 위젯의 기사
     * 썸네일 같은 것이 식별자가 될 수 있음.
     */
    /** 실측용 임시 — 광고 영역 안의 주소를 전부 출력. 측정이 끝나면 삭제 */
    private fun logAllUrls(node: AccessibilityNodeInfo) {
        var checked = 0
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(node to 0)
        while (queue.isNotEmpty() && checked < 120) {
            val (cur, depth) = queue.removeFirst()
            checked++
            extrasUrl(cur)?.let { Log.i(TAG, "dbgurl $it") }
            if (depth < 8) {
                for (i in 0 until cur.childCount) {
                    cur.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
    }

    private fun imgUrlOf(node: AccessibilityNodeInfo): String? {
        var checked = 0
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(node to 0)
        while (queue.isNotEmpty() && checked < 120) {
            val (cur, depth) = queue.removeFirst()
            checked++
            // 광고 영역 안에서 찾은 이미지면 그것이 그 광고의 소재. 호스트 구분
            // 없음 — 광고망마다 CDN이 달라서(adimg.nate.com·img.dable.io…) 목록을
            // 들고 있으면 새 광고망마다 또 증가. 쿼리는 노출마다 붙는 값이라 제거.
            extrasUrl(cur)?.let { if (isImageUrl(it)) return it.substringBefore('?') }
            if (depth < 8) {
                for (i in 0 until cur.childCount) {
                    cur.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return null
    }

    private fun isImageUrl(url: String): Boolean =
        url.substringBefore('?').lowercase().matches(Regex(".*\\.(png|jpe?g|gif|webp|svg)$"))

    /**
     * extras에서 주소 추출. **get()으로 받아 toString** —
     * getString으로 읽으면 값이 String이 아닐 때 Bundle이 노드마다 ClassCastException
     * 스택을 찍어 로그를 뒤덮음 (`offscreen`에서 실제 경험).
     */
    private fun extrasUrl(node: AccessibilityNodeInfo): String? {
        val ex = node.extras ?: return null
        if (!ex.containsKey(WebNode.TARGET_URL)) return null
        return ex.get(WebNode.TARGET_URL)?.toString()?.takeIf { it.isNotEmpty() }
    }

    /** 패키지 이름 대신 사용자가 아는 앱 이름 표시 ("com.coupang.mobile"이 아니라 "쿠팡") */
    private fun appLabelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /**
     * 플랫폼이 이미 제공하는 "건너뛰기" / 광고 닫기(X) 컨트롤을 광고 영역 안에서 탐색.
     * 이 앱이 광고를 스스로 없애는 것이 아니라, 너무 작아서 누르기 힘든 기존 버튼을
     * 사용자가 원할 때 대신 눌러주기 위한 것이므로 화면에 실제로 있을 때만 탐색.
     *
     * 광고 영역이 잡혔을 때만 도는 두 번째 순회. 광고가 없으면 비용도 없음.
     * 광고가 있을 때의 비용은 [ACTION_NODE_BUDGET]·[ACTION_TIME_BUDGET_MS]로 제한.
     */
    private fun findAdAction(root: AccessibilityNodeInfo, regions: List<Rect>): Found? =
        findAdActions(root, regions).minByOrNull { it.rank }

    /**
     * 광고 **영역마다** 가장 좋은 컨트롤을 하나씩 골라 반환.
     *
     * 원래는 화면 전체에서 가장 순위 높은 컨트롤 **하나**만 반환, [doAdAction]이
     * 그 하나를 클릭. → 광고가 둘 이상인 화면에서 "광고 닫기"를 눌러도 광고
     * 하나만 닫히고 나머지는 그대로 잔류 — 버튼 이름은 광고를 없애 준다고 말하는데
     * 실제로는 한 개만 없어지니, 사용자는 같은 버튼을 몇 번이나 다시 눌러야 했음.
     *
     * 순회는 그대로 한 번이고 예산도 그대로. 바뀐 것은 결과를 담는 그릇뿐 —
     * 영역 번호별로 최고 순위를 따로 기억.
     *
     * @return 영역 순서대로, 컨트롤을 찾은 것만. 하나도 없으면 빈 목록
     */
    private fun findAdActions(root: AccessibilityNodeInfo, regions: List<Rect>): List<Found> =
        findAdActionSlots(root, regions).filterNotNull()

    /**
     * [regions]와 **같은 길이**의 배열. i번 자리가 null이면 그 광고에는 누를
     * X가 없다는 뜻 — 그 자리는 [AdCoverOverlay]로 가림.
     *
     * 찾은 것만 추려서 돌려주면 "몇 번 광고에 X가 없었는지"를 잃어버려,
     * 누를 수 있는 광고만 닫히고 나머지는 그대로 잔류.
     */
    private fun findAdActionSlots(
        root: AccessibilityNodeInfo,
        regions: List<Rect>,
        budget: ActionBudget = ActionBudget()
    ): Array<Found?> {
        if (regions.isEmpty()) return emptyArray()
        // 모서리에 붙은 X를 놓치지 않도록 조금 확장 ([CLOSE_SLOP_DP])
        val slop = dp(CLOSE_SLOP_DP)
        val areas = regions.map { Rect(it).apply { inset(-slop, -slop) } }
        val best = arrayOfNulls<Found>(regions.size)
        collectAdActions(root, areas, 0, budget, best)
        return best
    }

    private class Found(
        val node: AccessibilityNodeInfo,
        val label: String,
        val rank: Int
    )

    /** 예산이 남아 있는 동안만 도는 실제 순회. 영역별 최고 순위를 [best]에 채움. */
    private fun collectAdActions(
        node: AccessibilityNodeInfo,
        regions: List<Rect>,
        depth: Int,
        budget: ActionBudget,
        best: Array<Found?>
    ) {
        if (depth > 60 || !budget.take()) return

        val b = Rect().also { node.getBoundsInScreen(it) }
        // **광고와 겹치지 않는 가지는 통째로 건너뜀.** 예전에는 트리 전체를 훑어
        // 기사 본문에 예산을 다 쓰고 정작 광고에는 도달 실패. 광고는 화면의 한
        // 조각이므로 이 한 줄이 순회 비용의 대부분을 절감.
        //
        // 단 **좌표가 빈 노드에서는 자르지 않음.** 크롬 웹 트리의 중간 컨테이너는
        // 좌표를 안 채우는 일이 흔한데, 여기서 잘라내면 그 안의 X를 영영 못 찾음.
        if (!b.isEmpty && regions.none { Rect(it).intersect(b) }) return

        if (node.isVisibleToUser) {
            // 광고 영역에 걸친 컨트롤만 인정 (화면의 다른 닫기 버튼을 잘못 누르지 않도록)
            if (b.width() > 0 && b.height() > 0) {
                val idx = regions.indexOfFirst { Rect(it).intersect(b) }
                if (idx >= 0) {
                    val s = "${node.text ?: ""} ${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""}"
                        .lowercase()
                    val hit = when {
                        AdRules.skipWords.any { it in s } -> "건너뛰기" to RANK_SKIP
                        AdRules.closeWords.any { it in s } -> "광고 닫기" to RANK_CLOSE
                        AdRules.isCloseId(node.viewIdResourceName) -> "광고 닫기" to RANK_CLOSE
                        else -> null
                    }
                    val cur = best[idx]
                    if (hit != null && (cur == null || hit.second < cur.rank)) {
                        clickableFrom(node)?.let { best[idx] = Found(it, hit.first, hit.second) }
                    }
                    // 이 영역은 건너뛰기 발견 — 더 나은 것이 있을 수 없으니 이 가지는
                    // 여기서 종료. **다른 영역은 계속 봐야 하므로 순회 전체는 계속**
                    // (예전에 하나만 닫히던 원인이 이 조기 종료).
                    if (best[idx]?.rank == RANK_SKIP) return
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectAdActions(node.getChild(i) ?: continue, regions, depth + 1, budget, best)
        }
    }

    /**
     * 노드 수와 시간으로 순회 중단. 예산이 끝나면 컨트롤을 못 찾은 것으로 간주,
     * 못 찾으면 버튼을 안 띄울 뿐이라 조용히 실패해도 위험 없음
     * (반대로 광고를 못 찾는 쪽은 오탐·미탐이 되므로 본 스캔은 예산을 훨씬 넉넉하게 설정).
     */
    private class ActionBudget(
        private val nodeLimit: Int = ACTION_NODE_BUDGET,
        timeLimitMs: Long = ACTION_TIME_BUDGET_MS
    ) {
        private val deadline = SystemClock.uptimeMillis() + timeLimitMs

        /** 지금까지 본 노드 수. 진단 로그가 읽음 */
        var visited = 0
            private set

        /** 예산이 바닥나서 순회를 끊었는지 여부. 0개를 찾았을 때 원인을 가르는 값 */
        var exhausted = false
            private set

        /** 노드 하나를 볼 수 있으면 true. false면 예산 소진 */
        fun take(): Boolean {
            if (visited >= nodeLimit || SystemClock.uptimeMillis() > deadline) {
                exhausted = true
                return false
            }
            visited++
            return true
        }
    }

    /** 라벨 자신이 클릭 대상이 아닐 수 있으므로 가까운 조상까지만 확인 */
    private fun clickableFrom(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            up++
        }
        return null
    }

    /** 누른 순간에 화면을 다시 읽어 컨트롤을 찾아 클릭 (미리 저장한 노드는 오래되면 실패) */
    private fun doAdAction() {
        val regions = shownRegions
        // 광고 안의 X를 대신 클릭 (spec.md 4). 막대는 여기서 걷지 않음 — 광고가
        // 정말 사라지면 다음 스캔의 [setAdRegions]가 해제. 그쪽이 사실에 더 가까움.
        // X가 없는 광고는 애초에 막대 미표시 ("닫을 수 있을 때만").
        scanHandler.post {
            val root = rootInActiveWindow ?: return@post
            // **화면에 잡힌 광고를 전부 처리.** 하나만 누르면 나머지가 그대로
            // 남아, 사용자는 같은 버튼을 광고 수만큼 다시 눌러야 함.
            //
            // 누를 X가 있는 광고만 누름. X 없는 광고는 그대로 두고 테두리 표시만 유지
            // (2026-08-24 결정 — 가리지 않음). 실측: 한 화면 3개 중 X 있는 1개만 닫힘.
            // 사용자가 기다리는 순간이라 예산을 넉넉히 배정 ([PRESS_TIME_BUDGET_MS])
            val budget = ActionBudget(PRESS_NODE_BUDGET, PRESS_TIME_BUDGET_MS)
            val slots = runCatching { findAdActionSlots(root, regions, budget) }
                .getOrDefault(arrayOfNulls(regions.size))

            var done = 0
            for (i in regions.indices) {
                val target = slots.getOrNull(i) ?: continue
                val bounds = Rect().also { target.node.getBoundsInScreen(it) }
                val ok = runCatching {
                    target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (ok) done++
                Log.i(TAG, "adAction '${target.label}' click=$ok at ${bounds.toShortString()}")
            }
            Log.i(
                TAG,
                "adAction: 영역 ${regions.size}개 중 $done 개 닫음 " +
                    "(노드 ${budget.visited}개 봄, 예산소진=${budget.exhausted})"
            )
            // spec.md 4 · 닫았으면 그렇게 안내. 아무 말 없이 광고만 사라지면 무슨 일이
            // 일어난 것인지 전달 안 됨.
            if (done > 0) handler.post {
                showNotice("✓", "광고를 닫았어요", "닫히지 않는 광고는 그대로 두었어요", Look.MINT)
            }
        }
    }

    /**
     * 앱에서 벗어날 때까지 뒤로 가기 반복. "한 번 더 누르면 종료"인 앱은 한 번으로는 탈출 불가.
     * 화면의 앱이 그대로일 때만 다시 누르므로, 이미 나간 뒤에 한 번 더 뒤로 가는 일은 없음.
     */
    private fun leaveApp(tries: Int) {
        scanHandler.post {
            val before = rootInActiveWindow?.packageName?.toString()
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (tries <= 1) return@post
            scanHandler.postDelayed({
                if (before != null && rootInActiveWindow?.packageName?.toString() == before) {
                    leaveApp(tries - 1)
                }
            }, 700)
        }
    }

    /**
     * 화면 아래 같은 자리에 큰 버튼 두 개 표시. 작은 원래 버튼(닫기 X는 약 18dp로
     * 권장 최소 크기 48dp의 절반도 안 됨)을 누르기 힘든 사용자를 위한 보조 장치.
     * 앱이 스스로 광고를 없애지 않으며, 사용자가 누른 그 순간에만 원래 버튼을 대신 클릭.
     */
    private fun showControls(actionLabel: String, regions: List<Rect>, isBack: Boolean) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,   // 버튼 크기만큼만 차지 — 나머지 화면은 그대로 터치 가능
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 기본 자리는 화면 아래. 거기에 광고가 있으면 광고를 가리지 않도록 위로 이동
            // (광고를 가리면 터치까지 막아 광고 클릭을 방해하게 됨)
            //
            // 시안 2-9·2-11은 막대를 **늘 위**에 둔다. 그대로 따르지 않은 이유는 위가
            // 크롬 주소창 자리라, 주소창을 눌러 다른 곳으로 가려는 손을 막을 수 있어서다.
            // 광고가 아래에 없을 때만 아래에 두는 지금 규칙은 어느 쪽도 가리지 않는다
            val bottomStrip = Rect(
                screenRect.left, screenRect.bottom - dp(170),
                screenRect.right, screenRect.bottom - dp(80)
            )
            val clear = regions.none { Rect(it).intersect(bottomStrip) }
            gravity = (if (clear) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            y = if (clear) dp(90) else dp(20)
        }
        if (controls == null) {
            val made = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            // 창 추가 실패 = 서비스 창 토큰이 잠깐 무효(재바인딩 중). 이번 회차만 건너뜀 — 다음 스캔이 재시도.
            // 실측 2026-08-24: BadTokenException으로 프로세스 사망 → 안드로이드가 접근성 서비스 자동 해제
            runCatching { windowManager.addView(made, params) }
                .onFailure { Log.w(TAG, "막대 창을 띄우지 못했다: $it"); return }
            controls = made
        } else {
            runCatching { windowManager.updateViewLayout(controls, params) }
        }
        val bar = controls ?: return
        val want = if (isBack) 1 else 2
        if (bar.childCount == want && (bar.getChildAt(want - 1) as TextView).text == actionLabel) return
        bar.removeAllViews()
        // spec.md 3의 "돌아가기" 막대는 버튼 하나. "그냥 두기"는 4(광고 닫기)에만 있음.
        if (!isBack) bar.addView(bigButton("그냥 두기", primary = false) {
            ignored = true
            hideControls()
        })
        bar.addView(bigButton(actionLabel, primary = true) {
            if (isBack) {
                val leaves = nav.leavesApp
                scanHandler.post { nav.dismiss() }
                if (leaves) leaveApp(LEAVE_APP_TRIES) else performGlobalAction(GLOBAL_ACTION_BACK)
                hideControls()
            } else doAdAction()
        })
    }

    /**
     * 화면 아래(또는 위) 버튼 막대의 버튼 하나 — 피그마 2-9 「광고 모두 닫기 / 그냥 두기」,
     * 2-11 「쿠팡 진입 → 복귀」의 막대. 둘 다 높이 60 · 반경 18 · 좌우 24 · Bold 16.
     *
     * v2에서 막대의 색이 먹색에서 **흰 알약 + 파란 테두리**로 바뀌었다. 광고 테두리와
     * 같은 파랑이라 "같은 앱이 하는 말"로 읽히고, 페이지 위에 얹혔을 때 덜 무겁다.
     *
     * 머무는 길은 시트가 없는 자리에 떠 있으므로 **흰 바탕 채움.**
     * 투명하게 두면 밑에 있는 앱 화면이 글자 뒤로 비쳐 안 읽힘.
     */
    // **두 버튼의 생김새는 동일. 다른 것은 색뿐.** 예전에는 주 버튼만 반경 30의
    // 민트 알약이고 보조 버튼은 반경 18의 각진 상자. 나란히 붙어 있는 두 버튼의
    // 모서리가 서로 다르니 같은 막대의 두 선택지로 읽히지 않고, 실기기에서 "버튼 모양이
    // 다르다"는 지적 발생. 색은 그대로 구분 — 무엇이 기본 선택인지는
    // 여전히 한눈에 보여야 함.
    private fun bigButton(label: String, primary: Boolean, onTap: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = BAR_BUTTON_TEXT_SP
            letterSpacing = -0.015f
            gravity = Gravity.CENTER
            includeFontPadding = false
            // 굵기까지 동일 — 한쪽만 Bold면 글자 폭이 달라져 두 버튼의 크기가
            // 미묘하게 어긋남. 강조는 색 담당.
            typeface = Look.bold(this@AdDetectService)
            // 피그마 v2 2-9/2-11: 둘 다 **흰 바탕**이고 테두리와 글자색만 다르다 —
            // 주("광고 닫기"·"돌아가기")는 파랑 테두리에 파란 글자,
            // 보조("그냥 두기")는 옅은 회색 테두리에 회색 글자.
            // v1의 먹색 채움은 페이지 위에서 너무 무겁고, 광고 테두리(파랑)와도 따로 놀았다
            setTextColor(Look.color(if (primary) Look.MINT else Look.INK_SOFT))
            setPadding(dp(22), 0, dp(22), 0)
            background = Look.box(
                this@AdDetectService,
                Look.BG,
                if (primary) Look.MINT else Look.LINE,
                BAR_BUTTON_RADIUS,      // 두 버튼 공통
                1.8f
            )
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(BAR_BUTTON_HEIGHT_DP)
            ).apply { setMargins(dp(5), 0, dp(5), 0) }
        }

    private fun hideControls() {
        if (barPinned) return
        controls?.let { runCatching { windowManager.removeView(it) } }
        controls = null
    }

    // ── 테두리 ───────────────────────────────────────────────────────────────

    private fun setAdRegions(newRegions: List<Rect>, marks: List<String?> = emptyList()) {
        // 클릭 직후에는 빈 것으로 간주([CLICK_CLEAR_MS]) — 진행 중이던 스캔이
        // 옛 페이지 좌표로 되살리는 것 차단. 창이 시간 안에 안 바뀌면 다음 스캔이 복원
        val regions =
            if (SystemClock.uptimeMillis() < clickClearUntil) emptyList() else newRegions
        if (regions.isNotEmpty()) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val now = SystemClock.uptimeMillis()
            // 광고가 잠깐 사라졌다 돌아오는 경우에까지 다시 울리면 소리가 계속 남.
            // 히스테리시스로 대부분 걸러지지만, 마지막 안전장치로 최소 간격 설정.
            if (shownRegions.isEmpty() && now - lastAlertAt > ALERT_MIN_GAP_MS) {
                lastAlertAt = now
                SeniorStats.countAd(this)   // 어르신 홈·안심 요약의 "광고 표시 N번"
                if (prefs.getBoolean("sound", true)) beep()
                if (prefs.getBoolean("vibe", true)) {
                    vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            // 좌표가 같더라도 감춰 둔 상태였으면 재그리기 필요. 스토리 광고 다음이 또 광고면
            // 영역이 둘 다 전체 화면이라 좌표만 비교해서는 "안 바뀌었다"가 되어 계속 감춰짐.
            // 좌표가 같아도 **색이 바뀌었으면** 재그리기 — 조회 응답이 늦게 와서
            // 파란 테두리가 노랑·빨강으로 승격되는 경우가 해당.
            if (prefs.getBoolean("visual", true) &&
                (regions != shownRegions || marks != shownMarks || bordersHidden)
            ) {
                showBorders(regions, marks)
            }
            // 매 스캔마다 동기화 — 표시가 노랑·빨강으로 승격되거나, 주의 광고의 통과
            // 허락이 만료되어 가드를 되살려야 하는 경우는 테두리가 안 바뀌어도 발생.
            syncGuards(regions, marks)
        } else if (shownRegions.isNotEmpty()) {
            overlay?.let { runCatching { windowManager.removeView(it) } }
            overlay = null
            ignored = false   // 광고가 사라졌으므로 다음 광고에는 버튼 재표시
            removeGuards()
            adCover.hide()    // 덮을 광고 없음
        }
        shownRegions = regions
    }

    private fun clearAll() {
        trackedAnchors = emptyList()   // 추적 대상 없음 → 루프 정지
        setAdRegions(emptyList())
        hideControls()
        adCover.hide()
        // 쉴드는 화면 전체를 덮고 터치를 막는 창이라, 서비스가 내려갈 때 남으면 폰이 잠김
        shield.hide()
        hideNotice.run()
    }

    // ── 터치 가드 ────────────────────────────────────────────────────────────

    /** 광고 위에 얹는 투명 창들. 첫 터치를 광고 대신 수신 (투터치). 영역 번호 → 창 */
    private val guards = HashMap<Int, View>()

    /**
     * 한 번 눌러 통과를 허락받은 광고. 링크(href)가 있으면 그것으로, 없으면 영역 번호로 식별.
     *
     * 허락은 아래 중 **먼저 오는 것**으로 끝남 ([revokePass]):
     *  - 첫 터치로부터 [NOTICE_MS](4초) 경과
     *  - 클릭이 광고에 실제로 닿음 (두 번째 터치)
     *  - 광고가 화면에서 사라짐 (광고 페이지로 넘어감 · 스크롤로 벗어남 · 다른 앱)
     * 즉 광고에 들어갔다 나와서 같은 광고를 또 누르면 언제나 다시 투터치.
     */
    private var passHref: String? = null
    private var passIdx = -1
    private var passAt = 0L

    /** 탭과 쓸어넘김을 가르는 거리. 기기 밀도마다 달라 [ViewConfiguration]에서 받는다 */
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    /** 가드 위에서 시작한 터치의 시작점(화면 좌표)과 쓸어넘김 여부 */
    private var guardDownX = 0f
    private var guardDownY = 0f
    private var guardDragged = false

    /** 이 시각까지는 가드를 붙이지 않는다 — 재생 중인 제스처가 다시 걸리지 않도록 */
    private var replayUntil = 0L

    /**
     * 이미 "주의"·"위험"으로 판정된 광고는 **들어가기 전에** 클릭 처리.
     *
     * 들어간 다음에 덮고 물어보면 배지로 이미 말해준 것을 또 확인하는 모양.
     * → 광고 위에 투명 창을 얹어 첫 터치를 대신 수신 — 주의는 "한 번 더 누르세요"
     * 알림을 주고 가드를 걷어 두 번째 터치가 들어가게 하고(투터치 입장), 위험은 계속 차단.
     * 파랑(미확인)·초록(안전)은 가드 없음 — 광고를 함부로 막으면 안 되고, 막을 근거가
     * 있는 광고에만 부착.
     */
    private fun syncGuards(regions: List<Rect>, marks: List<String?>) {
        val now = SystemClock.uptimeMillis()
        val ids = shownIds
        val twoTouch = twoTouchOn()
        for (i in regions.indices) {
            val mark = marks.getOrNull(i)
            val id = ids.getOrNull(i)
            val passed = mark != "HIGH" && now - passAt < NOTICE_MS &&
                (if (id != null) id == passHref else i == passIdx)
            // "한 번 더 누르면 열려요"는 **첫 터치를 가로챈 그 광고**의 배지 옆에만,
            // 허락이 살아 있는 동안([revokePass]까지)만. 평소에는 배지뿐.
            setTwoTapHint(i, passed)
            // spec.md 1 · 위험(빨강)은 언제나 차단. 그 밖의 광고(일반 파랑·주의 주황)는
            // **투터치 설정**(어르신 폰 내 정보 · 기본 켜짐)이 켜져 있을 때 첫 터치를
            // 가로채고, 해제하면 테두리로 알려주기만 함.
            val guarded = mark == "HIGH" || twoTouch
            // 쓸어넘김을 재생하는 동안에는 붙이지 않음 — 재생한 제스처가 이 가드에 걸린다
            // 덮개에 다 가려진(높이 0) 영역에는 가드도 없음 — 헤더 위 터치를 먹지 않게
            val want = guarded && !passed && now >= replayUntil && regions[i].height() >= dp(20)
            val old = guards[i]
            if (!want) {
                if (old != null) {
                    guards.remove(i)
                    runCatching { windowManager.removeView(old) }
                }
                continue
            }
            if (old == null) {
                val v = View(this)
                v.setOnTouchListener { view, e -> onGuardTouch(view, e) }
                runCatching { windowManager.addView(v, guardParams(regions[i])) }
                    .onSuccess { guards[i] = v }
            } else {
                runCatching { windowManager.updateViewLayout(old, guardParams(regions[i])) }
            }
            guards[i]?.tag = mark to id
        }
        guards.keys.filter { it >= regions.size }.forEach { i ->
            guards.remove(i)?.let { runCatching { windowManager.removeView(it) } }
        }
    }

    private fun guardParams(r: Rect) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // NOT_TOUCH_MODAL: 창 **밖**의 터치는 밑으로 통과 — 막는 것은 광고 자리뿐
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
    }

    // ── 설치 파일 내려받기 (시안 11O) ─────────────────────────────────────────

    /** 알림에서 읽어 둔 파일 이름. 크롬은 진행률마다 알림을 갱신해서 여러 번 수신 */
    private var lastApk: String? = null
    private var lastApkAt = 0L

    /** 방금 경고한 것. 설치 화면은 창이 바뀔 때마다 이벤트가 와서 그대로 두면 계속 덮음 */
    private var lastShieldedApk: String? = null
    private var lastShieldedAt = 0L

    /**
     * 설치 확인 화면 등장. 시안 11O를 그 위에 덮음.
     *
     * **막을 수 있는 마지막 자리.** 여기서 "설치"를 누르면 그때부터는 우리가 할 수 있는
     * 일이 없음 → 판정을 기다리지 않고 곧바로 덮음 — 출처 불명 설치 파일에
     * 안전한 경우 없음.
     *
     * 파일 이름은 알림에서 읽어 둔 것이 있으면 그것을 쓰고(무음이 아니었던 경우),
     * 없으면 설치 화면이 보여주는 앱 이름을 읽음. 둘 다 없으면 이름 없이 경고 —
     * 이름을 모른다고 지나치는 것이 더 나쁨.
     */
    private fun onInstaller(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastShieldedAt < APK_REPEAT_MS) return
        lastShieldedAt = now

        // 알림에서 읽어 둔 파일 이름이 있으면 그것이 가장 정확.
        // 없으면 설치 화면에 적힌 앱 이름을 읽는데, **창이 그려질 시간 필요** —
        // 이벤트가 오는 순간에는 아직 글자가 없어서 실측에서 이름이 비었음.
        val fresh = lastApk?.takeIf { now - lastApkAt < APK_FROM_NOTICE_MS }
        if (fresh != null) return warnApk(fresh)
        // 창 제목이 실리는 기기도 있음. 없으면 잠시 뒤 화면에서 읽음 —
        // 설치 화면의 첫 창(InstallLaunch)은 글자 없는 통로라 그 순간에는 읽을 것이 없음.
        val title = event.text.firstOrNull()?.toString()?.trim()?.takeIf {
            it.length in 2..40 && it !in INSTALLER_WORDS
        }
        if (title != null) return warnApk(title)
        handler.postDelayed({ warnApk(installingAppName()) }, INSTALLER_READ_MS)
    }

    /** 설치 파일 경고 표시 + 보호자에게 알림 */
    private fun warnApk(name: String?) {
        val shown = name ?: "출처를 알 수 없는 설치 파일"
        lastShieldedApk = shown

        val host = sourceHost()
        Log.i(TAG, "설치 경고 name=$shown from=${host ?: "모름"}")
        shield.showApkRisk(shown, host)
        Family.logEvent(
            this, "HIGH", "출처를 알 수 없는 앱을 설치하려 했습니다: $shown",
            host, type = "apk", blocked = true,
            alert = Family.Alert.APK_INSTALL, alertKey = shown
        )
    }

    /**
     * 이 설치 파일을 **어디서 받았는지**. 모르면 null.
     *
     * 마지막으로 본 주소를 그냥 쓰면 안 됨 — 아침에 뉴스를 보고 저녁에 파일을 열면
     * "뉴스 사이트에서 받았다"가 되어 버림. 브라우저를 방금까지 보고 있었을 때만
     * 그 주소를 출처로 인정. 모르면 모른다고 말하는 편이 나음.
     */
    private fun sourceHost(): String? {
        if (SystemClock.uptimeMillis() - lastBarAt > APK_SOURCE_MS) return null
        return WebNode.hostOf(lastBarUrl)
    }

    /**
     * 설치 화면에 적힌 앱 이름.
     *
     * **이벤트의 노드 사용 금지** — 늦게 읽으려고 미뤄 둔 사이에 회수됨
     * (실측: 이름이 계속 비었음). 그때의 활성 창을 새로 읽음.
     *
     * 화면 구조가 기기마다 달라서 글자를 다 모은 뒤 버튼·안내 문구를 걸러내고 첫 줄
     * 선택. 설치 화면은 앱 이름을 맨 위에 배치.
     */
    private fun installingAppName(): String? {
        val root = rootInActiveWindow ?: return null
        val texts = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 8) return
            n.text?.toString()?.trim()?.let { if (it.length in 2..40) texts.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        return texts.firstOrNull { t ->
            t !in INSTALLER_WORDS && !t.endsWith("?") && !t.endsWith(".") && !t.endsWith("요")
        }
    }

    /**
     * 브라우저가 띄운 알림에서 **설치 파일 내려받기** 감지.
     *
     * 파일 시스템을 보는 것 아님 — 우리에게는 저장소 권한이 없고, 앱이 받은 파일을
     * 볼 방법도 없음. 대신 크롬이 스스로 띄우는 다운로드 알림의 **글자**를 읽음
     * (`typeNotificationStateChanged`). 별도 권한 불필요, 파일 이름이 그대로 도착.
     *
     * 출처는 알림에 없으므로 **직전에 보던 주소**에서 가져옴. 시안 11O가
     * "clean_booster.apk · unknown-download.site에서 전달됨"으로 그려져 있는 것이
     * 정확히 이 조합.
     *
     * 광고를 눌러서 받은 것인지는 안 따짐. 어르신이 어디서 받았든 출처 불명
     * 설치 파일은 같은 위험이고, 광고에서 왔는지 가리는 사이에 설치 완료됨.
     */
    private fun onNotification(event: AccessibilityEvent, pkg: String) {
        val words = buildString {
            event.text.forEach { append(it).append(' ') }
            (event.parcelableData as? android.app.Notification)?.extras?.let { x ->
                append(x.getCharSequence(android.app.Notification.EXTRA_TITLE)).append(' ')
                append(x.getCharSequence(android.app.Notification.EXTRA_TEXT))
            }
        }
        val name = APK_NAME.find(words)?.value ?: return

        // 같은 파일은 한 번만. 진행률 갱신마다 덮으면 화면이 계속 튀어나옴
        val now = SystemClock.uptimeMillis()
        if (name == lastApk && now - lastApkAt < APK_REPEAT_MS) return
        lastApk = name
        lastApkAt = now

        val host = sourceHost()
        Log.i(TAG, "설치 파일 감지 name=$name from=${host ?: "모름"} pkg=$pkg")
        handler.post { shield.showApkRisk(name, host) }
        // 보호자에게도 알림. 설치 파일은 어르신 혼자 판단하기 가장 어려운 것이고,
        // 우리가 막아도 "받았다"는 사실 자체가 알아야 할 일.
        Family.logEvent(
            this, "HIGH", "출처를 알 수 없는 설치 파일을 내려받았습니다: $name",
            host, type = "apk", blocked = true,
            alert = Family.Alert.APK_DOWNLOAD, alertKey = name
        )
    }

    /** 가드가 첫 터치 수신. 광고에는 이 터치 미전달 */
    private fun onGuardTap(v: View) {
        val (mark, id) = (v.tag as? Pair<*, *>) ?: return
        Log.i(TAG, "가드 막음 mark=$mark id=${(id as? String)?.take(80)}")
        if (mark == "HIGH") {
            showNotice(
                "⛔", "위험한 광고라 막았어요",
                "눌러도 열리지 않아요 · 가족에게도 알렸어요", Look.DANGER
            )
            // 보호자에게는 **막힌 것도** 알림 — 위험한 광고를 누를 뻔했다는 사실 자체가
            // 알아야 할 일이고, 실제로 도착하지 않았으니 이 경로 말고는 남을 곳이 없음.
            Family.logEvent(
                this, mark as String, "위험하다고 확인된 광고를 눌렀습니다.",
                (id as? String)?.let { WebNode.hostOf(it) }, blocked = true,
                alert = Family.Alert.DOMAIN
            )
            // 유형은 판정에 붙어 있는 값이라 여기(가드)서는 알 수 없음. 기록에는
            // "막았다"는 사실만 남고, 유형은 그 광고가 판정될 때 남은 기록에 있음.
            return
        }
        passHref = id as? String
        passIdx = guards.entries.firstOrNull { it.value === v }?.key ?: -1
        passAt = SystemClock.uptimeMillis()
        // 첫 터치를 가로채면서 그 광고의 배지 옆에 "한 번 더 누르면 열려요"를 켬.
        // 통과 허락은 [NOTICE_MS] 동안만 — 그 안에 다시 누르면 광고로 진입. 허락이 끝나면
        // ([revokePass]) 안내도 같이 꺼짐.
        handler.removeCallbacks(expirePass)
        handler.postDelayed(expirePass, NOTICE_MS)
        setTwoTapHint(passIdx, true)
        // 가드를 곧바로 걷어 다음 터치가 광고로 들어가게 함
        dropGuard(v)
    }

    /** 가드 하나만 걷음. [removeGuards]와 달리 통과 허락([revokePass])은 건드리지 않는다 */
    private fun dropGuard(v: View) {
        guards.entries.firstOrNull { it.value === v }?.let {
            guards.remove(it.key)
            runCatching { windowManager.removeView(v) }
        }
    }

    /**
     * 가드 위의 터치 하나. **누르면 막고, 쓸어넘기면 흘려보낸다.**
     *
     * 창 하나가 ACTION_DOWN을 받으면 그 제스처는 끝까지 그 창의 것이다. 도중에 창을 없애도
     * 밑의 앱으로 넘어가지 않고 그냥 취소된다 — 그래서 "탭만 소비하고 스크롤은 통과"가
     * 안드로이드에서 그대로는 안 된다. 쓸어넘김으로 판명되면 손을 뗀 시점에 같은 궤적을
     * [replaySwipe]로 다시 그려 준다.
     *
     * 광고가 화면의 절반을 넘는 경우가 흔해(실측 56%), 이게 없으면 그 화면에서는
     * 스크롤 자체가 불가능하다.
     */
    @Suppress("ClickableViewAccessibility")
    private fun onGuardTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                guardDownX = e.rawX
                guardDownY = e.rawY
                guardDragged = false
            }
            MotionEvent.ACTION_MOVE ->
                if (!guardDragged &&
                    hypot(e.rawX - guardDownX, e.rawY - guardDownY) > touchSlop
                ) guardDragged = true

            MotionEvent.ACTION_UP -> if (guardDragged) replaySwipe(v, e) else onGuardTap(v)
        }
        return true
    }

    /**
     * 가드가 먹은 쓸어넘김을 손을 뗀 뒤 같은 궤적으로 다시 그린다.
     *
     * 관성(플링)은 재현되지 않는다 — 재생은 등속 드래그라서 손을 뗀 자리에서 멈춘다.
     * 스크롤이 아예 안 되던 것보다는 낫다는 판단.
     */
    private fun replaySwipe(v: View, e: MotionEvent) {
        val ms = (e.eventTime - e.downTime).coerceIn(REPLAY_MIN_MS, REPLAY_MAX_MS)
        // 가드부터 걷는다. 남겨 두면 재생한 제스처를 이 가드가 그대로 다시 먹는다
        dropGuard(v)
        // [MotionEvent]는 시스템이 회수해 재사용 — [REPLAY_WAIT_MS] 뒤에 읽으면 다른 터치의
        // 좌표가 들어 있다. 지금 꺼내 둔다.
        val fromX = guardDownX
        val fromY = guardDownY
        val toX = e.rawX
        val toY = e.rawY
        // 기다리는 동안에도 가드가 붙으면 안 된다 — 대기까지 포함해서 막는다
        replayUntil = SystemClock.uptimeMillis() + REPLAY_WAIT_MS + ms + REPLAY_GAP_MS
        handler.postDelayed({ replayNow(fromX, fromY, toX, toY, ms) }, REPLAY_WAIT_MS)
    }

    /** 가드 창이 실제로 걷힌 뒤 실행되는 재생 본체. [replaySwipe] 참조 */
    private fun replayNow(fromX: Float, fromY: Float, toX: Float, toY: Float, ms: Long) {
        val path = Path().apply {
            moveTo(clampX(fromX), clampY(fromY))
            lineTo(clampX(toX), clampY(toY))
        }
        val ok = runCatching {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, ms))
                    .build(),
                null, null
            )
        }.getOrDefault(false)
        Log.i(TAG, "쓸어넘김 재생 ${if (ok) "성공" else "실패"} ${ms}ms")
    }

    /**
     * 재생 좌표를 화면 안으로 민다. 점이 하나라도 화면 밖이면 [dispatchGesture]가 제스처를
     * 통째로 거부해("재생 실패") 스크롤이 날아간다.
     *
     * 손가락은 화면 밖 좌표를 만들지 못하니 평소에는 걸리지 않는다. 화면 경계에 붙은 광고와
     * 접근성 도구가 넣는 합성 제스처를 위한 방어 — 검증 중 합성 입력으로 실제로 터뜨렸다.
     */
    private fun clampX(x: Float) =
        x.coerceIn(0f, (if (screenRect.width() > 0) screenRect.right
        else resources.displayMetrics.widthPixels) - 1f)

    private fun clampY(y: Float) =
        y.coerceIn(0f, (if (screenRect.height() > 0) screenRect.bottom
        else resources.displayMetrics.heightPixels) - 1f)

    private fun removeGuards() {
        guards.values.forEach { runCatching { windowManager.removeView(it) } }
        guards.clear()
        // 광고가 화면에서 없어졌으면 허락도 끝. 광고 페이지로 넘어간 경우(구글 iframe처럼
        // 클릭 이벤트가 안 오는 광고는 위의 [revokePass]가 못 잡음)와 스크롤로 벗어난 경우 모두.
        revokePass()
    }

    /** 투터치 통과 허락 회수. 다음 스캔(200ms)이 가드 복원 */
    private fun revokePass() {
        handler.removeCallbacks(expirePass)
        passAt = 0L
        passHref = null
        passIdx = -1
        overlay?.let { g -> for (i in 0 until g.childCount) setTwoTapHint(i, false) }
    }

    // ── 투터치 안내 조각 ─────────────────────────────────────────────────────

    /**
     * "광고" 배지 **오른쪽에 나란히** 붙는 한 줄 안내 "한 번 더 누르면 열려요".
     * 검정 바탕 · 흰 글씨. 첫 터치를 가로챈 순간 켜지고, 허락이 끝나면([revokePass]) 꺼짐.
     *
     * 배지와 같은 행([buildBorderView]의 LinearLayout) 안 1번 자식이라 자리는 배지 옆으로
     * 고정이고, 스크롤·재배치를 테두리와 함께 따라가며, 테두리 창이 터치를 통과시키므로
     * 터치를 막지 않음. 켜고 끄는 것은 [syncGuards].
     */
    private fun setTwoTapHint(idx: Int, on: Boolean) {
        val v = overlay?.getChildAt(idx) as? FrameLayout ?: return
        labelRowOf(v)?.getChildAt(1)?.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** 투터치 [기본 / 해제] — 어르신 폰의 설정 화면([MeActivity])이 적는 값. 기본 켜짐. */
    private fun twoTouchOn() =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean("twotouch", true)

    /**
     * 테두리 두께(dp). 사용자가 [MeActivity]에서 정한 값 — 없으면 [Look.AD_BORDER_W].
     *
     * 매번 읽는다. 설정이 바뀌면 다음 스캔(200ms)에 곧바로 반영되어야 하고,
     * SharedPreferences는 메모리 캐시라 읽기가 싸다.
     */
    private fun borderW() = getSharedPreferences("settings", MODE_PRIVATE)
        .getInt(Look.AD_BORDER_KEY, Look.AD_BORDER_W)
        .coerceIn(Look.AD_BORDER_W, Look.AD_BORDER_MAX)

    /**
     * 두께에 맞춘 모서리 반경. 반경이 두께보다 작으면 모서리가 안쪽으로 뭉개져
     * 각진 것처럼 보인다 — 두꺼워질 때만 따라 키우고 기본 두께에서는 [BORDER_RADIUS] 그대로.
     */
    private fun borderRadius(w: Int) = maxOf(BORDER_RADIUS, w)

    /** 투터치 통과 허락의 만료. 다음 스캔(200ms)이 가드 복원 */
    private val expirePass = Runnable { revokePass() }

    /** 화면 아래의 알림 조각. 가드가 터치를 가로챈 이유 안내 */
    private var notice: LinearLayout? = null
    private val hideNotice = Runnable {
        notice?.let { runCatching { windowManager.removeView(it) } }
        notice = null
        // 알림이 사라지면 통과 허락도 같이 종료. 다음 스캔(200ms)이 가드 복원.
        passAt = 0L
    }

    /**
     * 시안의 하단 안내 바. 위험을 막을 때만이 아니라 **괜찮을 때도** 안내.
     *
     * 저위험이면 아무 말 없이 통과시키던 것을 변경 — 아무 일도 없었던 것처럼 보이면
     * 앱이 무엇을 했는지가 전해지지 않고, 어르신 입장에서는 "눌렀는데 그냥 열렸네"로 끝남.
     */
    /**
     * 화면 아래 안내 바.
     *
     * 시안에 두 벌 존재 — 막았거나 빠져나왔을 때는 진한 먹색 토스트(12O), 괜찮다고
     * 말할 때는 옅은 카드(09O-L). 둘 다 [동그라미 + 굵은 한 줄 + 설명 한 줄]이라 하나로
     * 만들고 색만 변경. 같은 자리에 같은 모양으로 뜨면서 색만 다르니 "무슨 일이
     * 있었는지"가 한눈에 구분됨.
     *
     * 저위험이면 아무 말 없이 통과시키던 것을 변경 — 아무 일도 없었던 것처럼 보이면
     * 앱이 무엇을 했는지가 전해지지 않고, 어르신 입장에서는 "눌렀는데 그냥 열렸네"로 끝남.
     */
    private fun showNotice(
        glyph: String,
        title: String,
        sub: String,
        dot: String,
        dark: Boolean = true,
    ) {
        handler.removeCallbacks(hideNotice)
        val v = notice ?: buildNotice().also { made ->
            runCatching {
                windowManager.addView(made, WindowManager.LayoutParams(
                    resources.displayMetrics.widthPixels - dp(40),   // 피그마 2-7: 좌우 20
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // 알림 자체는 만질 것이 아님 — 밑의 화면을 가리기만 하고 터치는 통과
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    // **화면 한가운데.** 아래에 두었을 때 손이나 버튼 막대에 가려 못 보고
                    // 지나가는 일 발생 — 이 배너는 "무슨 일이 있었는지"를 알리는
                    // 유일한 창이라 안 읽히면 존재 이유 없음. 가운데는 시선이 이미
                    // 가 있는 자리이고, 터치는 통과시키므로(FLAG_NOT_TOUCHABLE) 화면을
                    // 덮어도 조작 방해 없음.
                    gravity = Gravity.CENTER
                })
            }.onSuccess { notice = made }
        }
        // 피그마 2-7 Toast/Returned: 바탕 rgba(13,18,32,.98) · 반경 14 · 여백 왼18 위16 오20 아래16 ·
        // 간격 14 · 동그라미 40 · 18 Bold 흰 제목 + 14 Regular 흰 70% 설명 (줄 간격 3)
        // 옅은 변형(dark=false)은 같은 뼈대에 카드색만 적용.
        v.background = GradientDrawable().apply {
            setColor(if (dark) Color.argb(250, 13, 18, 32) else Look.color(Look.UNKNOWN_TINT))
            cornerRadius = dp(14).toFloat()
            if (!dark) setStroke(dp(1), Look.color(Look.BAR_INFO_LINE))
        }
        v.setPadding(dp(18), dp(16), dp(20), dp(16))

        // 동그라미는 부르는 쪽이 정한 상태색으로 채우고(막음=빨강 · 닫음=파랑) 기호는 흰색 —
        // 마스킹의 Picto/Blocked(빨간 원 + 흰 막대)와 같은 규칙.
        val circle = v.getChildAt(0) as TextView
        circle.text = glyph
        circle.background = Look.dot(dot)
        (circle.layoutParams as LinearLayout.LayoutParams).let {
            it.width = dp(40)
            it.height = it.width
            it.rightMargin = dp(14)
            circle.layoutParams = it
        }

        val texts = v.getChildAt(1) as LinearLayout
        (texts.getChildAt(0) as TextView).apply {
            text = title
            textSize = 18f
            letterSpacing = -0.015f
            setTextColor(if (dark) Color.WHITE else Look.color(Look.INK))
        }
        (texts.getChildAt(1) as TextView).apply {
            text = sub
            textSize = 14f
            setTextColor(if (dark) Color.argb(179, 255, 255, 255) else Look.color(Look.INK_SOFT))
        }
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    /** 안내 바의 뼈대. 색과 크기는 [showNotice]가 그때그때 적용 */
    private fun buildNotice() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@AdDetectService).apply {
            textSize = 21f          // 피그마 2-7 Picto/Done의 ✓
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Look.bold(this@AdDetectService)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(this@AdDetectService).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@AdDetectService).apply {
                includeFontPadding = false
                typeface = Look.bold(this@AdDetectService)
            })
            addView(TextView(this@AdDetectService).apply {
                includeFontPadding = false
                letterSpacing = -0.015f
                setPadding(0, dp(3), 0, 0)
            })
        })
    }

    /**
     * 테두리를 새로 만들지 않고 자리만 이동. 스크롤 추적 경로가 호출.
     * 개수가 달라졌다면 광고가 새로 뜨거나 사라진 것이므로 전체 스캔에 위임.
     */
    private fun moveBorders(regions: List<Rect>, sampledAt: Long) {
        if (regions.size != shownRegions.size || overlay == null) return
        val prev = shownRegions

        // 표본 두 개로 속도 측정. 미래를 아는 것이 아니라 **직전까지 실제로 움직이던 속도**가
        // 잠깐 더 이어진다고 보는 것(관성 항법). 스크롤은 물리적으로 연속이라 수십 ms 안에
        // 방향이 뒤집히지 않으므로 대체로 적중.
        //
        // 틀리는 경우는 둘뿐이고, 둘 다 **측정값이 예측과 어긋나는 순간 예측을 버리는** 것으로 방지.
        //  - 사용자가 스크롤 방향을 바꿈  → 부호가 뒤집히면 속도를 0으로 (아래 sign 검사)
        //  - 관성이 갑자기 멎음(페이지 끝·손으로 붙잡음) → 좌표가 안 움직이면 속도를 0으로
        // 예측이 틀린 채로 남는 시간은 최대 한 표본(32ms), 그 뒤에는 실측값이 덮어씀.
        if (velocity.size != regions.size) {
            velocity = FloatArray(regions.size)
            changedAt = LongArray(regions.size) { sampledAt }
        }
        for (i in regions.indices) {
            val moved = regions[i].top - prev[i].top
            if (moved == 0) {
                // 좌표 그대로. **화면이 멎은 것과 트리가 아직 갱신되지 않은 것은 별개.**
                // 여기서 속도를 0으로 되돌리면 갱신을 기다리는 수십 ms 동안 테두리가 제자리에
                // 얼어붙었다가, 다음 표본에서 한꺼번에 따라잡느라 튐. 갱신이 올 때까지는
                // 마지막 속도로 계속 밀어줌 — 그게 원래 예측을 넣은 이유.
                if (sampledAt - changedAt[i] > STALE_STOP_MS) velocity[i] = 0f
                continue
            }
            // 표본 간격이 아니라 **좌표가 바뀐 간격**으로 나눔. 이게 이 함수의 핵심.
            val dt = (sampledAt - changedAt[i]).toFloat()
            changedAt[i] = sampledAt
            if (dt < 1f || dt > STALE_STOP_MS) {
                // 한참 멎어 있다가 다시 움직이기 시작. 그 구간의 평균 속도는 무의미.
                velocity[i] = 0f
                continue
            }
            val v = moved / dt
            if (kotlin.math.abs(v) >= JUMP_PX_PER_MS) {
                // 스크롤이 아니라 **재배치**. 광고 슬롯이 늦게 로드되면서 위쪽 내용이 밀리면
                // 광고가 한 표본에 수백 px 건너뜀. 위치는 그대로 따라가되(아래 layoutBorders)
                // 속도로는 집계 안 함. 이걸 속도로 읽으면 테두리가 40px 앞질러 나가고 알파가
                // 0으로 떨어져 통째로 사라졌다가 돌아옴 — 스크롤 한 번에 그게 스물아홉 번.
                velocity[i] = 0f
                continue
            }
            velocity[i] = when {
                // 방향 반전 → 예측이 틀렸다는 뜻. 한 박자 쉬고 실측만 추종.
                v * velocity[i] < 0f -> 0f
                // 처음 잡는 속도는 그대로 사용. 0에서부터 필터를 먹이면 스크롤 시작이 굼떠짐.
                velocity[i] == 0f -> v
                // 그 밖에는 낮은 통과 필터. 표본 하나가 튀어도 테두리가 덩달아 튀지 않음.
                else -> velocity[i] * 0.35f + v * 0.65f
            }
        }

        shownRegions = regions
        // 마스크도 같은 표본으로 이동 — 테두리만 따라가고 마스크가 제자리면 위험 광고는
        // 드러나고 마스크는 밑에서 올라온 기사를 덮는다 (이슈 #43, 실기기 재현)
        adCover.moveAuto(regions.filterIndexed { i, _ -> shownMarks.getOrNull(i) == "HIGH" })
        // 안 움직였으면 배치 무변경 (매번 부르면 쓸데없는 레이아웃이 32ms마다 돎).
        // 감춰 둔 상태에서 좌표가 돌아왔으면 좌표가 같더라도 재표시 필요.
        if (regions != prev || bordersHidden) layoutBorders(regions)
        startLeading()
    }

    /**
     * 테두리를 지우지 않고 잠깐 감춤.
     * 좌표를 놓친 동안 제자리에 남아 있으면 밑에서 올라온 기사에 테두리가 씌워지기 때문.
     * 좌표가 다시 읽히면 [layoutBorders]가 도로 표시.
     */
    private fun hideBorders() {
        val group = overlay ?: return
        bordersHidden = true
        for (i in 0 until group.childCount) group.getChildAt(i)?.visibility = View.GONE
    }

    /**
     * 화면 정지. 예측을 걷고 실측 좌표 그대로 배치.
     * 흐림은 여기서 되돌리지 않음 — [leader]가 몇 프레임에 걸쳐 복원.
     * 여기서 alpha=1로 튕기면 흐려져 있던 테두리가 툭 나타나 그 자체가 깜박임이 됨.
     */
    private fun settleBorders() {
        velocity.fill(0f)
        val group = overlay ?: return
        for (i in 0 until group.childCount) group.getChildAt(i)?.translationY = 0f
        startLeading()
    }

    /**
     * 표본과 표본 사이를 매 화면 갱신마다 보간.
     *
     * 좌표는 32ms에 한 번밖에 못 읽는데 화면은 그보다 자주 갱신되므로, 그대로 두면 테두리가
     * 계단처럼 이동. 여기서는 마지막 표본 이후 흐른 시간에 속도를 곱해 **지금 광고가 있을
     * 자리**로 밀어줌. 배치를 다시 하지 않고 translation만 바꾸므로 프레임마다 해도 저렴.
     */
    private fun startLeading() {
        if (animating) return
        animating = true
        Choreographer.getInstance().postFrameCallback(leader)
    }

    private val leader = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val group = overlay
            if (group == null) {
                animating = false
                return
            }
            val now = SystemClock.uptimeMillis()
            var moving = false
            for (i in 0 until group.childCount) {
                val v = velocity.getOrElse(i) { 0f }
                if (v != 0f) moving = true
                val view = group.getChildAt(i) ?: continue
                // 앞질러 그릴 시간은 **그 영역의 좌표가 마지막으로 바뀐 뒤** 흐른 시간.
                // 표본을 찍은 시각으로 재면, 같은 값이 계속 오는 동안 0에 머물러 예측이 꺼짐.
                val lead = (now - changedAt.getOrElse(i) { now }).coerceIn(0L, MAX_LEAD_MS)
                view.translationY = (v * lead).coerceIn(-MAX_LEAD_PX, MAX_LEAD_PX)
                // 너무 빨라서 못 맞추는 구간에서는 흐려서 감춤. 어긋난 테두리를 보여주면
                // 그 순간 멀쩡한 기사에 "광고" 표시가 붙은 것처럼 보이기 때문.
                val speed = kotlin.math.abs(v)
                val a = when {
                    speed <= FADE_START_PX_PER_MS -> 1f
                    speed >= FADE_FULL_PX_PER_MS -> 0f
                    else -> 1f - (speed - FADE_START_PX_PER_MS) /
                        (FADE_FULL_PX_PER_MS - FADE_START_PX_PER_MS)
                }
                // 목표값으로 곧바로 튀지 않고 몇 프레임에 걸쳐 접근
                val next = view.alpha + (a - view.alpha) * FADE_SMOOTH
                if (kotlin.math.abs(a - next) < 0.01f) {
                    view.alpha = a
                } else {
                    view.alpha = next
                    moving = true   // 아직 수렴 중이면 루프 유지
                }
            }
            if (tracking || moving) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                animating = false
            }
        }
    }

    /** 각 광고 영역에 테두리 + 배지를 표시 (터치 통과). 색은 사전 조회 결과 기준 */
    private fun showBorders(regions: List<Rect>, marks: List<String?> = emptyList()) {
        val group = overlay ?: FrameLayout(this).also {
            // 테두리 뷰마다 배지가 자기 경계 위쪽으로 돌출. 여기서 잘라내면
            // 자식(테두리 뷰)의 그리기가 그 뷰 경계에서 잘려 배지가 통째로 소실.
            it.clipChildren = false
            // 창 추가 실패 = 서비스 창 토큰이 잠깐 무효(재바인딩 중). 이번 회차만 건너뜀 — 다음 스캔이 재시도.
            // [overlay]는 성공한 뒤에만 기록 — 붙지 않은 뷰를 기억하면 이후 갱신·제거가 또 실패.
            // 실측 2026-08-24: BadTokenException으로 프로세스 사망 → 안드로이드가 접근성 서비스 자동 해제
            runCatching {
                windowManager.addView(it, WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ))
            }.onFailure { Log.w(TAG, "테두리 창을 띄우지 못했다: $it"); return }
            overlay = it
        }
        // 개수가 같으면 뷰를 그대로 두고 자리만 변경.
        // 매번 removeAllViews로 다 지웠다 다시 만들면 그 자체가 깜빡임이 됨.
        while (group.childCount > regions.size) group.removeViewAt(group.childCount - 1)
        while (group.childCount < regions.size) {
            group.addView(buildBorderView(), FrameLayout.LayoutParams(0, 0))
        }
        for (i in 0 until group.childCount) {
            styleBorder(group.getChildAt(i) as FrameLayout, marks.getOrNull(i))
        }
        shownMarks = marks
        layoutBorders(regions)
    }

    /**
     * 테두리·배지의 색과 글자를 사전 조회 결과에 맞춤.
     *
     * 파랑 "광고"의 뜻은 "안전"이 아니라 **"광고인 건 확실한데 목적지는 아직 확인 전"**.
     * 빨강 "위험"은 그 광고가 가리키는 곳이 판정 DB에 위험하다고 남아 있다는 뜻 (마스킹과 함께).
     * 뷰는 재사용되므로 같은 스타일이면 그리기 생략.
     */
    private fun styleBorder(v: FrameLayout, mark: String?) {
        // spec.md 1 · 클릭 전 테두리는 **파랑 아니면 차단** 둘뿐. 판정 기록이 "주의"여도
        // 누르기 전에는 일반 광고와 같은 파랑이고(투터치만), 주황은 누른 뒤(3행)에만 등장.
        val tone = Look.tone(if (mark == "HIGH") mark else null)
        val color = tone.line
        val label = tone.label
        // 두께도 열쇠에 넣는다 — 라벨만 보면 설정을 바꿔도 이 조기 반환에 걸려 반영이 안 된다
        val w = borderW()
        val key = "$label/$w"
        if (v.tag == key) return
        v.tag = key
        (v.background as GradientDrawable).apply {
            setStroke(dp(w), Look.color(color))
            cornerRadius = dp(borderRadius(w)).toFloat()
        }
        val badge = labelRowOf(v)?.getChildAt(0) as? TextView ?: return
        badge.text = label
        // 피그마 v2의 배지는 **꽉 찬 상태색 알약에 흰 글씨** (2-1 Label/Ad #1F63E0 · 2-12 Chip
        // "위험" #E01F26). 파랑 "광고"만 테두리(#2E7DFF)보다 한 톤 진한 #1F63E0(=Look.MINT).
        (badge.background as GradientDrawable).setColor(
            Look.color(if (color == Look.UNKNOWN) Look.MINT else color)
        )
        badge.setTextColor(Color.WHITE)
    }

    /**
     * 화면 좌표를 창 좌표로 바꿔 테두리 배치.
     * 오버레이 창은 상태바 아래에서 시작할 수 있어 실제 창 위치만큼 보정 필요.
     * 창이 아직 배치되기 전일 때만 다음 프레임으로 연기 — 스크롤 중에는 곧바로 옮겨야 따라붙음.
     */
    private fun layoutBorders(regions: List<Rect>) {
        val group = overlay ?: return
        if (group.width == 0 || group.height == 0) {
            group.post { layoutBorders(regions) }
            return
        }
        bordersHidden = false
        val loc = IntArray(2).also { group.getLocationOnScreen(it) }
        val win = Rect(loc[0], loc[1], loc[0] + group.width, loc[1] + group.height)
        for (i in regions.indices) {
            val v = group.getChildAt(i) as? FrameLayout ?: continue
            val c = Rect(regions[i])
            // 창 밖 조각은 감춤. 높이 하한을 낮게 둬야 320x50 인라인 배너를 놓치지 않음.
            if (!c.intersect(win) || c.height() < dp(20)) {
                v.visibility = View.GONE
                continue
            }
            v.visibility = View.VISIBLE
            // 배지는 상자 **안쪽** 오른쪽 위에 배치(시안). 바깥에 얹으면 광고를 하나도
            // 가리지 않지만, 위쪽이 잘리는 화면(광고가 화면 맨 위)에서는 배지도 함께 잘림.
            // 연한 바탕이라 가리는 면적도 작음.
            // 배지 + 투터치 안내가 든 행. 행 안에서 둘은 LinearLayout이 나란히 놓음.
            val row = labelRowOf(v)
            if (row != null) {
                row.visibility = View.VISIBLE
                (row.layoutParams as FrameLayout.LayoutParams).let {
                    it.topMargin = dp(BADGE_INSET)
                    it.rightMargin = dp(10)
                    row.layoutParams = it
                }
            }
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = c.width()
            lp.height = c.height()
            lp.setMargins(c.left - win.left, c.top - win.top, 0, 0)
            v.layoutParams = lp
        }
        // 터치 가드도 광고 추종. 예측 보간까지는 안 함 — 시각 요소가 아니라
        // 터치 표적이라 수십 ms 늦는 것은 티가 안 나고, 창 이동은 표본마다면 충분.
        for ((i, g) in guards) {
            regions.getOrNull(i)?.let { r ->
                runCatching { windowManager.updateViewLayout(g, guardParams(r)) }
            }
        }
    }

    private fun buildBorderView(): FrameLayout {
        // 배지는 "광고" 한 낱말짜리 알약 하나.
        //
        // 처음에는 검은 알약 안에 파란 "AD" 칩과 "광고" 글자를 나란히 넣었는데, 폭이 두 배가 되어
        // 그만큼 광고를 더 가림. 어르신에게 "AD"는 읽어야 할 정보가 아니라 자리만 차지하는 것이라
        // (그 뜻을 아는 사람은 한글 표기도 불필요) 빼고 그 자리에 "광고" 삽입.
        // 배지와 투터치 안내는 **같은 규격** — 글씨 15 Medium 흰색 · 높이 [BADGE_H] · 좌우 16 ·
        // 모서리 [BORDER_RADIUS]. 다른 것은 바탕색뿐(배지 파랑/빨강, 안내 검정).
        val badge = labelChip("광고", Look.color(Look.MINT))
        val hint = labelChip(TWO_TAP_HINT_TEXT, Color.BLACK).apply { visibility = View.GONE }
        // 두 칩을 가로 한 줄에. 안내는 배지 오른쪽에 [TWO_TAP_HINT_GAP]만큼 띄워 나란히.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(badge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(BADGE_H)
            ))
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(BADGE_H)
            ).apply { leftMargin = dp(TWO_TAP_HINT_GAP) })
        }
        val w = borderW()
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(w), Look.color(Look.UNKNOWN))
                setColor(Color.TRANSPARENT)
                // 시안의 강조 상자와 같은 둥근 모서리. 광고 자체는 각진 사각형이라
                // 모서리가 둥글어야 "광고 위에 우리가 얹은 것"으로 읽힘.
                cornerRadius = dp(borderRadius(w)).toFloat()
            }
            // 행이 광고 폭보다 길어질 수 있으므로(좁은 배너) 잘라내지 않음
            clipChildren = false
            clipToPadding = false
            // 행은 항상 첫째 자식 (뷰를 재사용하려면 구조가 매번 같아야 함).
            // 행 안의 0번이 "광고" 배지, 1번이 투터치 안내(평소 숨김) — [labelRowOf]·[setTwoTapHint] 참조.
            // 자리는 [layoutBorders]가 결정. 피그마 2-1은 배지가 **왼쪽** 위 (테두리 왼쪽에서 8).
            addView(row, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(BADGE_H)
            ).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(BADGE_INSET) })
        }
    }

    /** 테두리 위쪽의 칩 하나. 배지("광고")와 투터치 안내가 같은 규격으로 이것을 사용 */
    private fun labelChip(label: String, fill: Int) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        // 피그마 v2 2-1 Label/Ad: Bold 15 흰 · 높이 31 · 좌우 여백 16. 굵기는 Bold(700)→Medium(500)으로 한 단계 얇게
        textSize = 15f
        typeface = Look.medium(this@AdDetectService)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(16), 0, dp(16), 0)
        // 모서리는 테두리([BORDER_RADIUS])와 같은 반경 — 시안은 알약(높이 절반)이었으나 테두리에 맞춰 통일
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(BORDER_RADIUS).toFloat()
        }
    }

    /** 테두리 뷰 안의 [배지 + 투터치 안내] 행 */
    private fun labelRowOf(border: FrameLayout) = border.getChildAt(0) as? LinearLayout

    private fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        handler.postDelayed({ tone.release() }, 400)
    }

    override fun onInterrupt() {
        clearAll()
        serp.stop()
    }

    override fun onDestroy() {
        settingsWatch?.remove()
        settingsWatch = null
        handler.removeCallbacksAndMessages(null)
        scanHandler.removeCallbacksAndMessages(null)
        trackHandler.removeCallbacksAndMessages(null)
        scanThread.quitSafely()
        trackThread.quitSafely()
        runCatching { unregisterReceiver(dumpReceiver) }
        runCatching { unregisterReceiver(previewReceiver) }
        clearAll()
        serp.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}
