package com.app.vocalmaster

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 가속도계로 '화면을 아래로 엎어 놓기'를 감지한다.
 * - 엎어 놓으면 onFaceDown(), 다시 집어 들면 onFaceUp() 호출.
 * - 흔들기(ShakeDetector)와 구분되도록 저역 필터로 중력 성분(z축)만 추적하고,
 *   자세가 HOLD_MS 이상 유지될 때만 트리거한다(스쳐 지나가는 동작 무시).
 * 연습 모드에서 폰을 엎어두면 일시정지하는 데 쓴다.
 */
class FlipPauseDetector(
    context: Context,
    private val onFaceDown: () -> Unit,
    private val onFaceUp: () -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var gravityZ = 0f
    private var faceDown = false
    private var candidateSince = 0L

    fun start() {
        gravityZ = 0f
        faceDown = false
        candidateSince = 0L
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // 저역 필터로 흔들림 성분을 걸러내고 중력 방향(z축)만 추적.
        // 화면이 위를 향하면 +9.8, 바닥을 향하면 -9.8에 수렴.
        gravityZ += LOW_PASS_ALPHA * (event.values[2] - gravityZ)

        // 히스테리시스: 엎기 진입은 거의 수평으로 엎었을 때만,
        // 해제는 살짝만 들어 올려도 되도록 문턱을 다르게 둔다.
        val wantsFaceDown =
            if (faceDown) gravityZ < FACE_DOWN_EXIT_Z else gravityZ < FACE_DOWN_ENTER_Z

        if (wantsFaceDown == faceDown) {
            candidateSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (candidateSince == 0L) {
            candidateSince = now
            return
        }
        if (now - candidateSince >= HOLD_MS) {
            faceDown = !faceDown
            candidateSince = 0L
            if (faceDown) onFaceDown() else onFaceUp()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val LOW_PASS_ALPHA = 0.15f
        private const val FACE_DOWN_ENTER_Z = -8.5f // 거의 완전히 엎어둔 상태 (중력 ≈ -9.8)
        private const val FACE_DOWN_EXIT_Z = -3f    // 집어 들거나 세우면 해제
        private const val HOLD_MS = 500L            // 자세 유지 시간 (오작동 방지)
    }
}
