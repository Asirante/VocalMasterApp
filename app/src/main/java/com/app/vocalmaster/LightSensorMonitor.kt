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
    private val darkLuxCeil: Float = DEFAULT_DARK_LUX_CEIL,
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
        // 0 lux=완전 어두움(darkness 1), darkLuxCeil 이상=충분히 밝음(darkness 0).
        // darkLuxCeil은 설정에서 조정 가능(SettingsManager.darkLuxCeil).
        val ceil = if (darkLuxCeil > 0f) darkLuxCeil else DEFAULT_DARK_LUX_CEIL
        val darkness = (1f - (lux / ceil)).coerceIn(0f, 1f)
        onLight(lux, darkness)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val DEFAULT_DARK_LUX_CEIL = 200f
    }
}
