package com.kutup.navigasyon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Gercek pusula + cihaz egimi (pitch/roll) verisi.
 * pitch: + deger = kamera ufkun ustune kalkik kabul edilir.
 */
class CompassSensor(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var azimuth: Float = 0f
    private var pitch: Float = 0f
    private var roll: Float = 0f

    private var magnetometerData = FloatArray(3)
    private var accelerometerData = FloatArray(3)
    private val orientationValues = FloatArray(3)

    var onAzimuthChanged: ((Float) -> Unit)? = null

    var isListening = false
        private set

    fun startListening() {
        if (isListening) return
        magneticFieldSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        accelerometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        isListening = true
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerData = event.values.copyOf()
            Sensor.TYPE_ACCELEROMETER -> accelerometerData = event.values.copyOf()
        }

        val rotationMatrix = FloatArray(9)
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerData, magnetometerData)
        if (!success) return

        SensorManager.getOrientation(rotationMatrix, orientationValues)

        val rawAzimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat().normalize360()
        val rawPitchUpPositive = (-Math.toDegrees(orientationValues[1].toDouble())).toFloat()
        val rawRoll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()

        azimuth = smoothAngle(azimuth, rawAzimuth, 0.18f)
        pitch = smoothLinear(pitch, rawPitchUpPositive, 0.18f)
        roll = smoothLinear(roll, rawRoll, 0.18f)

        onAzimuthChanged?.invoke(azimuth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun getAzimuth(): Float = azimuth

    fun getPitchDegrees(): Float = pitch.coerceIn(-85f, 85f)

    fun getRollDegrees(): Float = roll.coerceIn(-90f, 90f)

    fun getCardinalDirection(): String {
        val directions = arrayOf("Kuzey", "KuzeyDogu", "Dogu", "DoguGuney", "Guney", "GuneyBati", "Bati", "BatiKuzey")
        val index = ((azimuth + 22.5f) / 45f).toInt() % 8
        return directions[index]
    }

    private fun Float.normalize360(): Float {
        var v = this
        while (v < 0f) v += 360f
        while (v >= 360f) v -= 360f
        return v
    }

    private fun smoothLinear(prev: Float, next: Float, alpha: Float): Float {
        return prev + alpha * (next - prev)
    }

    private fun smoothAngle(prev: Float, next: Float, alpha: Float): Float {
        var delta = next - prev
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return (prev + alpha * delta).normalize360()
    }
}
