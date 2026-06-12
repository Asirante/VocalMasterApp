package com.app.vocalmaster

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 환경 센서(주변 온도 TYPE_AMBIENT_TEMPERATURE / 상대 습도 TYPE_RELATIVE_HUMIDITY)를 읽는다.
 * 성대는 건조한 환경에 민감하므로 연습 환경 점검(보컬 컨디션 안내)에 쓴다.
 * 두 센서가 모두 없는 기기가 많으므로 isAvailable로 UI 노출 여부를 결정한다.
 * onUpdate(temperatureC, humidityPercent): 해당 센서가 없거나 아직 값이 없으면 null.
 */
class EnvironmentMonitor(
    context: Context,
    private val onUpdate: (temperatureC: Float?, humidityPercent: Float?) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val tempSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humiditySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)

    val hasTemperature: Boolean get() = tempSensor != null
    val hasHumidity: Boolean get() = humiditySensor != null
    val isAvailable: Boolean get() = hasTemperature || hasHumidity

    private var temperatureC: Float? = null
    private var humidityPercent: Float? = null

    fun start() {
        tempSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        humiditySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> temperatureC = event.values[0]
            Sensor.TYPE_RELATIVE_HUMIDITY -> humidityPercent = event.values[0]
            else -> return
        }
        onUpdate(temperatureC, humidityPercent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
