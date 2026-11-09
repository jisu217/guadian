package com.flyai.adalert

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 연결 코드 QR (피그마 v2 · P05 「QR 보기」 1361:20이 여는 화면).
 *
 * 코드를 손으로 부르지 않고 **화면째 보여 주는** 자리. 어르신 폰 카메라(또는 옆 사람 폰)로
 * 읽으면 여섯 자리와 비밀번호가 글자로 나온다 — 앱에 스캐너를 넣지 않았기 때문에
 * 담는 값도 링크가 아니라 **읽을 수 있는 문장**이다. 링크를 담으면 카메라가 웹을 열려 하고,
 * 그 순간 이 앱이 막으려는 「모르는 링크를 누르는 일」을 우리가 시키는 꼴이 된다.
 *
 * 코드가 화면에 그대로 떠 있으므로 이 화면은 잠금 화면 위로 띄우지 않는다(기본 그대로).
 */
class QrActivity : Activity() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_PIN = "pin"

        /** QR 한 변(px). 어두운 곳에서도 읽히도록 화면 폭 절반보다 크게 */
        private const val SIZE = 720
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        val pin = intent.getStringExtra(EXTRA_PIN)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Look.color(Look.BG))
            setPadding(dp(24), dp(24), dp(24), dp(24))

            addView(TextView(this@QrActivity).apply {
                text = "가족 연결 코드"
                textSize = 14f
                letterSpacing = -0.015f
                includeFontPadding = false
                typeface = Look.bold(this@QrActivity)
                setTextColor(Look.color(Look.INK_SOFT))
            })

            addView(ImageView(this@QrActivity).apply {
                setImageBitmap(qr(payload(code, pin)))
                layoutParams = LinearLayout.LayoutParams(dp(260), dp(260))
                    .apply { topMargin = dp(20) }
            })

            addView(TextView(this@QrActivity).apply {
                text = code
                textSize = 34f
                letterSpacing = 0.06f
                includeFontPadding = false
                typeface = Look.bold(this@QrActivity)
                setTextColor(Look.color(Look.MINT))
                setPadding(0, dp(24), 0, 0)
            })

            addView(TextView(this@QrActivity).apply {
                text = pin?.let { "비밀번호 $it" } ?: "비밀번호는 이 가족을 만든 휴대폰에 있어요"
                textSize = 14f
                letterSpacing = -0.015f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Look.color(Look.INK_SOFT))
                setPadding(0, dp(10), 0, 0)
            })

            isClickable = true
            setOnClickListener { finish() }
        })
        Insets.apply(this)
    }

    /** 카메라가 읽었을 때 사람이 그대로 읽을 수 있는 문장 (링크 아님 — 위 설명 참고) */
    private fun payload(code: String, pin: String?) = buildString {
        append("안심폰 연결 코드 $code")
        pin?.let { append(" / 비밀번호 $it") }
    }

    /** 흑백 QR 한 장. 여백(margin)은 1 — 화면 자체가 흰 바탕이라 더 둘 필요가 없다 */
    private fun qr(text: String): Bitmap {
        val bits = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, SIZE, SIZE,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
        )
        val out = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(bits.width)
        for (y in 0 until bits.height) {
            for (x in 0 until bits.width) row[x] = if (bits[x, y]) Color.BLACK else Color.WHITE
            out.setPixels(row, 0, bits.width, 0, y, bits.width, 1)
        }
        return out
    }

    private fun dp(v: Int) = Look.dp(this, v)
}
