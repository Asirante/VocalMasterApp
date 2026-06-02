package com.app.vocalmaster.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 현재 가사 한 줄을, 진행도(0~1)에 따라 왼쪽→오른쪽으로 색이 채워지는 그라디언트로 표시.
 * 채워진 부분은 강조색, 아직 안 부른 부분은 흐린 색.
 */
class LyricGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var text: String = ""
    private var progress: Float = 0f // 0~1

    private val filledColor = Color.parseColor("#EC4899")   // 핑크 (부른 부분)
    private val unfilledColor = Color.parseColor("#9CA3AF") // 회색 (안 부른 부분)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(20f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setLyric(line: String) {
        if (line != text) {
            text = line
            requestLayout() // 줄 바뀌면 높이 재계산
        }
        invalidate()
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val lineHeight = (paint.fontMetrics.bottom - paint.fontMetrics.top)
        val lines = if (text.isEmpty()) 1 else 1
        val h = (lineHeight * lines + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return

        val cx = width / 2f
        val baseline = paddingTop - paint.fontMetrics.top
        val textWidth = paint.measureText(text)
        val left = cx - textWidth / 2f
        val right = cx + textWidth / 2f

        // 진행 경계 x좌표
        val splitX = left + (right - left) * progress

        // 채워진 부분과 안 채워진 부분을 가르는 그라디언트
        // (경계에서 살짝 부드럽게 섞이도록 좁은 구간을 둠)
        val blend = max(1f, textWidth * 0.02f)
        val gx0 = min(splitX - blend, right)
        val gx1 = min(splitX + blend, right)
        paint.shader = LinearGradient(
            max(left, gx0), 0f, max(left + 1f, gx1), 0f,
            filledColor, unfilledColor, Shader.TileMode.CLAMP
        )
        canvas.drawText(text, cx, baseline, paint)
        paint.shader = null
    }

    private fun spToPx(sp: Float): Float =
        sp * resources.displayMetrics.scaledDensity
}
