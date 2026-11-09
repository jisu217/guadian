package com.flyai.adalert

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * 화면 접근성 트리를 extras까지 통째로 파일에 덤프. 개발용.
 *
 * `uiautomator dump`로는 extras 확인 불가 → targetUrl·chromeRole 실제 채움 여부 확인 수단 없음.
 * 감지 규칙 신규 작성 시 화면에 오는 값을 직접 보는 것이 가장 빠름 — 도구로 유지.
 *
 * ```
 * adb shell am broadcast -a com.flyai.adalert.DUMP
 * adb pull /sdcard/Android/data/com.flyai.adalert/files/tree-dump.txt
 * ```
 *
 * 이 방송의 리시버는 **디버그 빌드에서만** 등록
 * (이유: [AdDetectService.onServiceConnected]). 배포 빌드에서 위 명령은 무동작이 정상.
 */
object TreeDumper {

    const val ACTION = "com.flyai.adalert.DUMP"
    private const val TAG = "AdDetectService"
    private const val MAX_NODES = 6000

    fun dump(ctx: Context, root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder(64 * 1024)
        if (root == null) {
            sb.append("rootInActiveWindow == null\n")
        } else {
            val screen = Rect().also { root.getBoundsInScreen(it) }
            sb.append("package=").append(root.packageName).append('\n')
            sb.append("screen=").append(screen.toShortString()).append('\n')
            sb.append("---\n")
            write(sb, root, 0, intArrayOf(0))
        }
        val f = File(ctx.getExternalFilesDir(null), "tree-dump.txt")
        return try {
            f.writeText(sb.toString())
            Log.i(TAG, "tree dumped: ${f.absolutePath} (${sb.length} chars)")
            f.absolutePath
        } catch (e: Throwable) {
            Log.e(TAG, "tree dump failed", e)
            "FAILED: ${e.message}"
        }
    }

    private fun write(sb: StringBuilder, node: AccessibilityNodeInfo, depth: Int, count: IntArray) {
        if (count[0] >= MAX_NODES || depth > 100) return
        count[0]++

        val b = Rect().also { node.getBoundsInScreen(it) }
        sb.append("  ".repeat(depth.coerceAtMost(40)))
            .append('#').append(count[0])
            .append(" cls=").append(node.className)
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" vis=").append(if (node.isVisibleToUser) 1 else 0)
            .append(" clk=").append(if (node.isClickable) 1 else 0)
            .append(" scr=").append(if (node.isScrollable) 1 else 0)
            .append(' ').append(b.toShortString())

        node.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" text=").append(clip(it.toString())) }
        node.contentDescription?.takeIf { it.isNotEmpty() }
            ?.let { sb.append(" desc=").append(clip(it.toString())) }

        // 이 도구의 존재 이유 — 브라우저가 실어 보내는 웹 정보
        try {
            val ex = node.extras
            if (ex != null && !ex.isEmpty) {
                for (k in ex.keySet()) {
                    val v = ex.get(k) ?: continue
                    val s = v.toString()
                    if (s.isEmpty()) continue
                    sb.append(' ').append(k.removePrefix("AccessibilityNodeInfo."))
                        .append('=').append(clip(s))
                }
            }
        } catch (e: Throwable) {
            sb.append(" extras=<error:").append(e.javaClass.simpleName).append('>')
        }
        sb.append('\n')

        for (i in 0 until node.childCount) {
            write(sb, node.getChild(i) ?: continue, depth + 1, count)
            if (count[0] >= MAX_NODES) return
        }
    }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').replace('\r', ' ')
        return if (one.length <= 120) "[$one]" else "[" + one.take(120) + "…]"
    }
}
