package com.kutup.navigasyon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.InputStream
import java.util.Locale
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

        private data class PolarStation(
            val name: String,
            val latitude: Double,
            val longitude: Double
        )

        // Kutup bolgesindeki bilinen iletisim noktalarinin yaklasik koordinatlari.
        private val POLAR_STATIONS = listOf(
            PolarStation("Longyearbyen (Svalbard)", 78.2232, 15.6469),
            PolarStation("Ny-Alesund (Svalbard)", 78.9236, 11.9233),
            PolarStation("Utqiagvik (Alaska)", 71.2906, -156.7886),
            PolarStation("Tiksi (Saha)", 71.6366, 128.8718),
            PolarStation("Alert (Nunavut)", 82.5018, -62.3481)
        )
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var galleryButton: Button
    private lateinit var compassStatusTextView: TextView
    private lateinit var azimutuResultTextView: TextView
    private lateinit var latitudeResultTextView: TextView

    private lateinit var compass: CompassSensor
    private lateinit var starDetector: StarDetector
    private lateinit var polarisFinder: PolarisFinder
    private lateinit var latitudeSolver: LatitudeSolver

    private lateinit var locationManager: LocationManager
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val verticalFov = 60f

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "Gorsel secilmedi", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        setProcessingState(true)
        cameraExecutor.execute {
            try {
                val bitmap = decodeBitmapFromUri(uri)
                if (bitmap == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Gorsel okunamadi", Toast.LENGTH_SHORT).show()
                        setProcessingState(false)
                    }
                    return@execute
                }
                processBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Galeri isleme hatasi", e)
                runOnUiThread {
                    Toast.makeText(this, "Galeri fotografi islenemedi", Toast.LENGTH_SHORT).show()
                    setProcessingState(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (allPermissionsGranted()) {
            startCamera()
            updateLocationAndStationInfo()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        initializeModules()
        compass.startListening()
    }

    private fun initializeUI() {
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        compassStatusTextView = findViewById(R.id.compassStatus)
        azimutuResultTextView = findViewById(R.id.azimutuResult)
        latitudeResultTextView = findViewById(R.id.latitudeResult)

        captureButton.isEnabled = false
        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener { openGallery() }

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

    private fun openGallery() {
        pickImageLauncher.launch("image/*")
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
                updateLocationAndStationInfo()
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

        setProcessingState(true)

        localImageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processImage(image)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Fotograf cekme hatasi", exc)
                    setProcessingState(false)
                    Toast.makeText(this@MainActivity, "Fotograf cekilemedi", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImage(image: ImageProxy) {
        cameraExecutor.execute {
            try {
                val bitmap = imageToBitmap(image)
                processBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Isleme hatasi", e)
                runOnUiThread {
                    setProcessingState(false)
                    Toast.makeText(this, "Fotograf islenemedi", Toast.LENGTH_SHORT).show()
                }
            } finally {
                image.close()
            }
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        try {
            val stars = starDetector.detectStars(bitmap)

            if (stars.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Yildiz tespit edilemedi", Toast.LENGTH_SHORT).show()
                    setProcessingState(false)
                }
                return
            }

            val (polaris, _) = polarisFinder.findPolaris(stars, bitmap.height, bitmap.width)
            val latitudeResult = latitudeSolver.calculateLatitude(
                polaris.y,
                bitmap.height,
                verticalFov
            )

            runOnUiThread {
                displayResults(polaris, latitudeResult)
                setProcessingState(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap isleme hatasi", e)
            runOnUiThread {
                setProcessingState(false)
                Toast.makeText(this, "Fotograf islenemedi", Toast.LENGTH_SHORT).show()
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

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, opts)
        }

        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val maxDim = 1600
        var sampleSize = 1
        var w = opts.outWidth
        var h = opts.outHeight
        while (w > maxDim || h > maxDim) {
            w /= 2
            h /= 2
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        contentResolver.openInputStream(uri).use { input: InputStream? ->
            if (input == null) return null
            return BitmapFactory.decodeStream(input, null, decodeOpts)
        }
    }

    private fun setProcessingState(isProcessing: Boolean) {
        captureButton.isEnabled = !isProcessing && imageCapture != null
        galleryButton.isEnabled = !isProcessing
    }

    private fun updateLocationAndStationInfo() {
        if (!allPermissionsGranted()) {
            azimutuResultTextView.text = "Konum izni yok"
            return
        }

        val userLocation = findBestLastKnownLocation()
        if (userLocation == null) {
            azimutuResultTextView.text = "Konum alinamadi (GPS acik olmali)"
            return
        }

        var nearest: PolarStation? = null
        var nearestDistanceMeters = Float.MAX_VALUE
        var nearestBearing = 0f

        for (station in POLAR_STATIONS) {
            val results = FloatArray(3)
            Location.distanceBetween(
                userLocation.latitude,
                userLocation.longitude,
                station.latitude,
                station.longitude,
                results
            )

            if (results[0] < nearestDistanceMeters) {
                nearest = station
                nearestDistanceMeters = results[0]
                nearestBearing = results[1]
            }
        }

        if (nearest == null) {
            azimutuResultTextView.text = "Istasyon verisi bulunamadi"
            return
        }

        val bearing360 = normalizeBearing(nearestBearing)
        val directionText = bearingToDirection(bearing360)
        val distanceKm = nearestDistanceMeters / 1000f

        azimutuResultTextView.text = """
            Konumunuz:
            ${formatCoord(userLocation.latitude, userLocation.longitude)}

            En yakin kutup istasyonu:
            ${nearest.name}
            ${formatCoord(nearest.latitude, nearest.longitude)}

            Uzaklik: ${String.format(Locale.US, "%.1f", distanceKm)} km
            Yon: ${String.format(Locale.US, "%.0f", bearing360)}° ($directionText)
        """.trimIndent()
    }

    private fun findBestLastKnownLocation(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best!!.accuracy) {
                    best = loc
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        return best
    }

    private fun normalizeBearing(bearing: Float): Float {
        var b = bearing % 360f
        if (b < 0f) b += 360f
        return b
    }

    private fun bearingToDirection(bearing: Float): String {
        val dirs = listOf("Kuzey", "Kuzeydogu", "Dogu", "Guneydogu", "Guney", "Guneybati", "Bati", "Kuzeybati")
        val idx = (((bearing + 22.5f) / 45f).toInt()) % 8
        return dirs[idx]
    }

    private fun formatCoord(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon)
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
