package com.app.vocalmaster

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 가속도계로 '흔들기'를 감지한다.
 * 중력(9.8)을 뺀 선형 가속도 크기가 임계값을 넘으면 onShake(intensity) 호출.
 * intensity: 0~1로 정규화한 흔드는 세기.
 * 연속 흔들기를 위해 짧은 디바운스만 두고, 흔드는 동안 반복 트리거된다.
 */
class ShakeDetector(
    context: Context,
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val onShake: (intensity: Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastTriggerMs = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        // 중력 포함 전체 가속도 크기에서 중력(약 9.81) 제거 → 움직임 성분
        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
        val linear = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)

        if (linear > threshold) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs >= DEBOUNCE_MS) {
                lastTriggerMs = now
                // intensity: 임계값을 넘은 정도를 고정 span(INTENSITY_SPAN)으로 정규화.
                // 임계값이 레벨별로 크게 달라도 세기 체감이 일정하도록 한다.
                val intensity = ((linear - threshold) / INTENSITY_SPAN).coerceIn(0f, 1f)
                onShake(intensity)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        // m/s^2 (중력 제거 후 움직임 성분). 설정에서 조정 가능(SettingsManager.shakeThreshold).
        const val DEFAULT_THRESHOLD = 4f
        private const val INTENSITY_SPAN = 10f  // 임계값보다 이만큼 더 세게 흔들면 최대 세기
        private const val DEBOUNCE_MS = 100L    // 연타 방지(타악기 반복은 허용)
    }
}
