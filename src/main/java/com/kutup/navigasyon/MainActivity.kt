package com.kutup.navigasyon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.json.JSONArray
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    enum class Hemisphere { NORTH, SOUTH }

    data class PolarStation(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val source: String
    )

    companion object {
        private const val TAG = "KutupNav"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var previewView: PreviewView
    private lateinit var mapView: MiniMapView
    private lateinit var captureButton: Button
    private lateinit var galleryButton: Button
    private lateinit var modeButton: Button
    private lateinit var compassStatusTextView: TextView
    private lateinit var infoTextView: TextView
    private lateinit var resultTextView: TextView

    private lateinit var compass: CompassSensor
    private lateinit var starDetector: StarDetector
    private lateinit var polarisFinder: PolarisFinder
    private lateinit var southernCrossFinder: SouthernCrossFinder
    private lateinit var latitudeSolver: LatitudeSolver

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var hemisphereMode = Hemisphere.NORTH
    private val verticalFov = 60f

    private var stations: List<PolarStation> = emptyList()

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

        stations = loadStationsFromAssets()

        initializeUI()
        initializeModules()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        compass.startListening()
        updateModeText()
        drawMapForEstimatedLatitude(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        compass.stopListening()
        cameraExecutor.shutdown()
    }

    private fun initializeUI() {
        previewView = findViewById(R.id.previewView)
        mapView = findViewById(R.id.mapView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        modeButton = findViewById(R.id.modeButton)
        compassStatusTextView = findViewById(R.id.compassStatus)
        infoTextView = findViewById(R.id.azimutuResult)
        resultTextView = findViewById(R.id.latitudeResult)

        captureButton.isEnabled = false
        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        modeButton.setOnClickListener {
            hemisphereMode = if (hemisphereMode == Hemisphere.NORTH) Hemisphere.SOUTH else Hemisphere.NORTH
            updateModeText()
        }
        mapView.setOnClickListener {
            MiniMapView.showFullscreenMap(this, mapView.getMarkers(), mapView.getUserLabel())
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initializeModules() {
        compass = CompassSensor(this)
        starDetector = StarDetector()
        polarisFinder = PolarisFinder()
        southernCrossFinder = SouthernCrossFinder()
        latitudeSolver = LatitudeSolver()

        compass.onAzimuthChanged = { azimuth ->
            runOnUiThread {
                val direction = compass.getCardinalDirection()
                compassStatusTextView.text = String.format(Locale.US, "Pusula: %s %.0f°", direction, azimuth)
            }
        }
    }

    private fun updateModeText() {
        val modeText = if (hemisphereMode == Hemisphere.NORTH) "MOD: KUZEY" else "MOD: GUNEY"
        modeButton.text = modeText
        infoTextView.text = if (hemisphereMode == Hemisphere.NORTH) {
            "Mod: Kuzey (Polaris)"
        } else {
            "Mod: Guney (Guney Haci)"
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
                Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_SHORT).show()
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

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
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
            Toast.makeText(this, "Kamera hazir degil", Toast.LENGTH_SHORT).show()
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
                    resultTextView.text = "Yildiz bulunamadi"
                    setProcessingState(false)
                }
                return
            }

            val (refStar, confidence, modeName) = if (hemisphereMode == Hemisphere.NORTH) {
                val (polaris, c) = polarisFinder.findPolaris(stars, bitmap.height, bitmap.width)
                Triple(polaris, c, "Polaris")
            } else {
                val (scp, c) = southernCrossFinder.findSouthCelestialPole(stars, bitmap.height, bitmap.width)
                Triple(scp, c, "Guney Haci")
            }

            if (confidence < 0.20f) {
                runOnUiThread {
                    resultTextView.text = "Guven dusuk, tekrar cek"
                    setProcessingState(false)
                }
                return
            }

            var result = latitudeSolver.calculateLatitude(refStar.y, bitmap.height, verticalFov)
            if (hemisphereMode == Hemisphere.SOUTH) {
                val signed = -abs(result.latitude)
                val err = result.errorMargin
                result = result.copy(
                    latitude = signed,
                    lowerBound = signed - err,
                    upperBound = signed + err,
                    altitude = abs(result.altitude)
                )
            }

            runOnUiThread {
                resultTextView.text = String.format(
                    Locale.US,
                    "%s | Tahmini enlem %.4f | Hata +/-%.2f",
                    modeName,
                    result.latitude,
                    result.errorMargin
                )

                val nearest = findNearestStationByLatitude(result.latitude.toDouble())
                if (nearest != null) {
                    infoTextView.text = String.format(
                        Locale.US,
                        "Mod: %s | En yakin istasyon: %s",
                        if (hemisphereMode == Hemisphere.NORTH) "Kuzey" else "Guney",
                        nearest.name
                    )
                }

                drawMapForEstimatedLatitude(result.latitude.toDouble())
                setProcessingState(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap isleme hatasi", e)
            runOnUiThread {
                resultTextView.text = "Isleme hatasi"
                setProcessingState(false)
            }
        }
    }

    private fun findNearestStationByLatitude(estimatedLat: Double): PolarStation? {
        if (stations.isEmpty()) return null
        return stations.minByOrNull { abs(it.latitude - estimatedLat) }
    }

    private fun drawMapForEstimatedLatitude(estimatedLat: Double?) {
        val markers = mutableListOf<MapMarker>()
        for (s in stations) {
            markers.add(MapMarker(s.name, s.latitude, s.longitude, 0xFF4FC3F7.toInt(), 6f))
        }

        if (estimatedLat != null) {
            val nearest = findNearestStationByLatitude(estimatedLat)
            if (nearest != null) {
                markers.add(MapMarker("Tahmini", estimatedLat, nearest.longitude, 0xFFFF5252.toInt(), 9f))
                markers.add(MapMarker("En yakin", nearest.latitude, nearest.longitude, 0xFFFFC107.toInt(), 8f))
                mapView.setMapData(markers, "Kirmizi: Tahmini | Sari: En yakin")
                return
            }
        }

        mapView.setMapData(markers, "Mavi: Istasyonlar")
    }

    private fun setProcessingState(isProcessing: Boolean) {
        captureButton.isEnabled = !isProcessing && imageCapture != null
        galleryButton.isEnabled = !isProcessing
        modeButton.isEnabled = !isProcessing
    }

    private fun loadStationsFromAssets(): List<PolarStation> {
        return try {
            val json = assets.open("polar_stations.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PolarStation(
                            name = o.getString("name"),
                            latitude = o.getDouble("latitude"),
                            longitude = o.getDouble("longitude"),
                            source = o.optString("source", "trusted")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Istasyon verisi okunamadi", e)
            emptyList()
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w > 1600 || h > 1600) {
            w /= 2
            h /= 2
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        contentResolver.openInputStream(uri).use { input: InputStream? ->
            if (input == null) return null
            return BitmapFactory.decodeStream(input, null, opts)
        }
    }
}
