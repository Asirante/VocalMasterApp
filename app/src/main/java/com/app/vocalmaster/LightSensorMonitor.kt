package com.app.vocalmaster

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 조도 센서(TYPE_LIGHT)로 주변 밝기를 읽는다.
 * onLight(lux, darkness): lux는 원시 조도, darkness는 0(밝음)~1(어두움) 정규화 값.
 * 어두운 환경일수록 무대 효과를 강하게 적용하는 데 쓴다.
 */
class LightSensorMonitor(
    context: Context,
    private val onLight: (lux: Float, darkness: Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    val isAvailable: Boolean get() = lightSensor != null

    fun start() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        // 약 0~200 lux 구간을 어두움 정도로 매핑 (실내 조명 기준).
        // 0 lux=완전 어두움(darkness 1), 200 lux 이상=충분히 밝음(darkness 0).
        val darkness = (1f - (lux / DARK_LUX_CEIL)).coerceIn(0f, 1f)
        onLight(lux, darkness)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val DARK_LUX_CEIL = 200f
    }
}
