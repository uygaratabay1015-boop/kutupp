package com.kutup.navigasyon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/**
 * Gercek pusula + cihaz egimi (pitch/roll) verisi.
 * pitch: + deger = kamera ufkun ustune kalkik kabul edilir.
 */
class CompassSensor(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var azimuth: Float = 0f
    private var pitch: Float = 0f
    private var roll: Float = 0f

    private var magnetometerData = FloatArray(3)
    private var accelerometerData = FloatArray(3)
    private var rotationVectorData: FloatArray? = null
    private val orientationValues = FloatArray(3)

    var onAzimuthChanged: ((Float) -> Unit)? = null

    var isListening = false
        private set

    fun startListening() {
        if (isListening) return
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
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
            Sensor.TYPE_ROTATION_VECTOR -> rotationVectorData = event.values.copyOf()
        }

        val rotationMatrix = FloatArray(9)
        val success = if (rotationVectorData != null) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorData)
            true
        } else {
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerData, magnetometerData)
        }
        if (!success) return

        val remapped = FloatArray(9)
        val (axisX, axisY) = remapAxesForDisplay()
        val remapOk = SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
        if (!remapOk) return

        SensorManager.getOrientation(remapped, orientationValues)

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

    private fun remapAxesForDisplay(): Pair<Int, Int> {
        val rotation = context.display?.rotation ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
    }
}
