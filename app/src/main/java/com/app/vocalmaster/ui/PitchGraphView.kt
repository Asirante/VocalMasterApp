package com.app.vocalmaster.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

class PitchGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val historySize = 80 // 화면에 표시할 프레임 수 (~3.7초 @ 46ms)

    // Pair<detected, target> 단일 큐 — 두 값의 크기가 항상 동일하게 유지됨
    private val frames = ArrayDeque<Pair<Float, Float>>()

    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ec9b0") // 청록 — 사용자 음정
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ec4899") // 핑크 — 정답 선
        strokeWidth = 2f
        style = Paint.Style.STROKE
        alpha = 140
    }

    private val userPath = Path()
    private val targetPath = Path()

    /** ScoringEngine 콜백에서 호출 — 메인 스레드에서만 */
    fun pushFrame(detectedHz: Float, targetHz: Float) {
        if (frames.size >= historySize) frames.pollFirst()
        frames.addLast(Pair(detectedHz, targetHz))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frames.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val step = w / historySize

        // Hz → Y 좌표 변환 (C2=65Hz ~ C7=2093Hz 범위를 뷰 높이에 매핑)
        val minHz = 65f
        val maxHz = 2093f
        fun hzToY(hz: Float): Float =
            if (hz <= 0f) h // 무음은 화면 밖으로
            else h - ((hz - minHz) / (maxHz - minHz)).coerceIn(0f, 1f) * h

        userPath.reset()
        targetPath.reset()
        frames.forEachIndexed { i, (detected, target) ->
            val x = i * step
            if (i == 0) {
                userPath.moveTo(x, hzToY(detected))
                targetPath.moveTo(x, hzToY(target))
            } else {
                userPath.lineTo(x, hzToY(detected))
                targetPath.lineTo(x, hzToY(target))
            }
        }
        canvas.drawPath(targetPath, targetPaint)
        canvas.drawPath(userPath, userPaint)
    }

    fun clear() {
        frames.clear()
        invalidate()
    }
}
