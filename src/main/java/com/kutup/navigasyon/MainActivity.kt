package com.kutup.navigasyon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KutupNav"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var compassStatusTextView: TextView
    private lateinit var azimutuResultTextView: TextView
    private lateinit var latitudeResultTextView: TextView

    private lateinit var compass: CompassSensor
    private lateinit var starDetector: StarDetector
    private lateinit var polarisFinder: PolarisFinder
    private lateinit var latitudeSolver: LatitudeSolver

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val verticalFov = 60f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        initializeModules()
        compass.startListening()
    }

    private fun initializeUI() {
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        compassStatusTextView = findViewById(R.id.compassStatus)
        azimutuResultTextView = findViewById(R.id.azimutuResult)
        latitudeResultTextView = findViewById(R.id.latitudeResult)

        captureButton.isEnabled = false
        captureButton.setOnClickListener { takePhoto() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initializeModules() {
        compass = CompassSensor(this)
        starDetector = StarDetector()
        polarisFinder = PolarisFinder()
        latitudeSolver = LatitudeSolver()

        compass.onAzimuthChanged = { azimuth ->
            updateCompassDisplay(azimuth)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Izinler reddedildi", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1280, 720))
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                captureButton.isEnabled = true
            } catch (exc: Exception) {
                Log.e(TAG, "Kamera baslatma hatasi", exc)
                captureButton.isEnabled = false
                Toast.makeText(this, "Kamera baslatilamadi", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val localImageCapture = imageCapture
        if (localImageCapture == null) {
            Toast.makeText(this, "Kamera henuz hazir degil", Toast.LENGTH_SHORT).show()
            return
        }

        captureButton.isEnabled = false

        localImageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processImage(image)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Fotograf cekme hatasi", exc)
                    captureButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Fotograf cekilemedi", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImage(image: ImageProxy) {
        cameraExecutor.execute {
            try {
                val bitmap = imageToBitmap(image)
                val stars = starDetector.detectStars(bitmap)

                if (stars.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "Yildiz tespit edilemedi", Toast.LENGTH_SHORT).show()
                        captureButton.isEnabled = true
                    }
                    return@execute
                }

                val (polaris, _) = polarisFinder.findPolaris(stars, bitmap.height, bitmap.width)
                val latitudeResult = latitudeSolver.calculateLatitude(
                    polaris.y,
                    bitmap.height,
                    verticalFov
                )

                runOnUiThread {
                    displayResults(polaris, latitudeResult)
                    captureButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Isleme hatasi", e)
                runOnUiThread {
                    captureButton.isEnabled = true
                    Toast.makeText(this, "Fotograf islenemedi", Toast.LENGTH_SHORT).show()
                }
            } finally {
                image.close()
            }
        }
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val srcWidth = image.width
        val srcHeight = image.height

        // Process at lower resolution to avoid heavy memory/CPU usage.
        val maxDim = 960
        val scale = maxOf(1, maxOf(srcWidth, srcHeight) / maxDim)
        val outWidth = srcWidth / scale
        val outHeight = srcHeight / scale

        val pixels = IntArray(outWidth * outHeight)

        for (y in 0 until outHeight) {
            val srcY = y * scale
            for (x in 0 until outWidth) {
                val srcX = x * scale
                val yIndex = srcY * yRowStride + srcX * yPixelStride
                val luma = yBuffer.get(yIndex).toInt() and 0xFF
                pixels[y * outWidth + x] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }

        return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    private fun updateCompassDisplay(azimuth: Float) {
        runOnUiThread {
            val direction = compass.getCardinalDirection()
            val facingNorth = compass.isFacingNorth()
            val status = if (facingNorth) "Kuzeye bakiyor" else "Yanlis yon"

            compassStatusTextView.text = "Pusula: $direction\nAzimuth: $azimuth\n$status"
        }
    }

    private fun displayResults(polaris: Star, result: LatitudeResult) {
        val resultsText = """
            SONUCLAR

            ENLEM: ${result.latitude}
            HATA PAYI: +/-${result.errorMargin}
            ARALIK: ${result.lowerBound} -> ${result.upperBound}

            Polaris yuksekligi: ${result.altitude}
            X: ${polaris.x.toInt()} px
            Y: ${polaris.y.toInt()} px
            Parlaklik: ${polaris.brightness.toInt()}
        """.trimIndent()

        latitudeResultTextView.text = resultsText
    }

    override fun onDestroy() {
        super.onDestroy()
        compass.stopListening()
        cameraExecutor.shutdown()
    }
}
