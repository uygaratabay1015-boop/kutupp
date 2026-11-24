package com.kutup.navigasyon

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Kamera ekseninde (yukseklik, roll) tahmini; roll + = saat yonu (sag taraf asagi). */
data class CameraTilt(
    val pitchDeg: Float,
    val rollDeg: Float,
    val spreadDeg: Float,
    val quality: Float,
    val usedGyro: Boolean
)

/**
 * Ivmeolcer + jiroskop (internetsiz, yalnizca cihaz sensorleri) ile cekim eğimi.
 * Hesap [TiltEstimator] icindedir; bu sinif yalnizca sensor olaylarini ona iletir.
 *
 * Sensorler kamera onizlemesi acikken surekli calistirilir: jiroskop sapmasi (bias) ve
 * zenit yonu cekimden once oturur. 100 Hz (10 ms) HIGH_SAMPLING_RATE_SENSORS izni gerektirmez.
 */
class TiltSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val estimator = TiltEstimator()
    private val lock = Any()

    @Volatile
    var isListening = false
        private set

    val hasGyroscope: Boolean get() = gyroscope != null
    val isAvailable: Boolean get() = accelerometer != null

    fun startListening() {
        if (isListening || accelerometer == null) return
        synchronized(lock) { estimator.reset() }
        sensorManager.registerListener(this, accelerometer, SAMPLING_PERIOD_US)
        gyroscope?.let { sensorManager.registerListener(this, it, SAMPLING_PERIOD_US) }
        isListening = true
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val v = event.values
        if (v.size < 3) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> synchronized(lock) {
                estimator.addAccelerometer(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
            Sensor.TYPE_GYROSCOPE -> synchronized(lock) {
                estimator.addGyroscope(event.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Son [windowSeconds] saniyenin agirlikli ortalama eğimi (pozlama penceresi).
     * @param surfaceRotation goruntunun dik gorunmesi icin kullanilan Surface.ROTATION_* degeri
     *        (ImageCapture.targetRotation). Yeterli sensor verisi yoksa null.
     */
    fun estimateForCapture(surfaceRotation: Int, windowSeconds: Double = 1.0): CameraTilt? {
        val est = synchronized(lock) { estimator.estimateLatest(windowSeconds) } ?: return null
        val upCam = TiltEstimator.upInCamera(est.upDevice, TiltEstimator.surfaceRotationToDegrees(surfaceRotation))
        val (pitch, roll) = TiltEstimator.pitchRollFromCameraUp(upCam)
        return CameraTilt(
            pitchDeg = pitch.toFloat(),
            rollDeg = roll.toFloat(),
            spreadDeg = est.spreadDeg.toFloat(),
            quality = est.quality.toFloat(),
            usedGyro = est.usedGyro
        )
    }

    private companion object {
        const val SAMPLING_PERIOD_US = 10_000
    }
}
