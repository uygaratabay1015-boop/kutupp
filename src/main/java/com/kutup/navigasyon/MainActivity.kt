package com.kutup.navigasyon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan

class MainActivity : AppCompatActivity() {

    enum class Hemisphere { NORTH, SOUTH }
    enum class CaptureSource { CAMERA, GALLERY }

    companion object {
        private const val TAG = "KutupNav"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var galleryButton: Button
    private lateinit var calculateButton: Button
    private lateinit var modeButton: Button
    private lateinit var compassStatusTextView: TextView
    private lateinit var infoTextView: TextView
    private lateinit var resultTextView: TextView
    private lateinit var dateModeToday: RadioButton
    private lateinit var dateModeManual: RadioButton
    private lateinit var manualDateInput: EditText
    private lateinit var manualTimeInput: EditText

    private lateinit var compass: CompassSensor
    private lateinit var starDetector: StarDetector
    private lateinit var polarisFinder: PolarisFinder
    private lateinit var southernCrossFinder: SouthernCrossFinder
    private lateinit var latitudeSolver: LatitudeSolver
    private lateinit var skyPatternMatcher: SkyPatternMatcher
    private lateinit var starCatalogMatcher: StarCatalogMatcher

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var hemisphereMode = Hemisphere.NORTH
    private var verticalFov = 60f
    private var selectedBitmap: Bitmap? = null
    private var selectedSource: CaptureSource? = null
    private var isProcessing = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "Gorsel secilmedi", Toast.LENGTH_SHORT).show()
            setProcessingState(false)
            return@registerForActivityResult
        }

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
                runOnUiThread {
                    setSelectedPhoto(bitmap, CaptureSource.GALLERY)
                    setProcessingState(false)
                    Toast.makeText(this, "Foto secildi. HESAPLA'ya bas.", Toast.LENGTH_SHORT).show()
                }
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

        skyPatternMatcher = loadSkyPatternMatcher()
        starCatalogMatcher = loadStarCatalogMatcher()

        initializeUI()
        initializeModules()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        compass.startListening()
        updateModeText()
    }

    override fun onDestroy() {
        super.onDestroy()
        selectedBitmap?.recycle()
        compass.stopListening()
        cameraExecutor.shutdown()
    }

    private fun initializeUI() {
        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        calculateButton = findViewById(R.id.calculateButton)
        modeButton = findViewById(R.id.modeButton)
        compassStatusTextView = findViewById(R.id.compassStatus)
        infoTextView = findViewById(R.id.azimutuResult)
        resultTextView = findViewById(R.id.latitudeResult)
        dateModeToday = findViewById(R.id.dateModeToday)
        dateModeManual = findViewById(R.id.dateModeManual)
        manualDateInput = findViewById(R.id.manualDateInput)
        manualTimeInput = findViewById(R.id.manualTimeInput)

        val now = LocalDateTime.now()
        manualDateInput.setText(now.toLocalDate().toString())
        manualTimeInput.setText(String.format(Locale.US, "%02d:%02d", now.hour, now.minute))
        manualDateInput.isEnabled = false
        manualTimeInput.isEnabled = false

        dateModeToday.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                manualDateInput.isEnabled = false
                manualTimeInput.isEnabled = false
            }
        }
        dateModeManual.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                manualDateInput.isEnabled = true
                manualTimeInput.isEnabled = true
            }
        }

        captureButton.isEnabled = false
        calculateButton.isEnabled = false

        captureButton.setOnClickListener { takePhotoForSelection() }
        galleryButton.setOnClickListener {
            setProcessingState(true)
            pickImageLauncher.launch("image/*")
        }
        calculateButton.setOnClickListener { onCalculatePressed() }
        modeButton.setOnClickListener {
            hemisphereMode = if (hemisphereMode == Hemisphere.NORTH) Hemisphere.SOUTH else Hemisphere.NORTH
            updateModeText()
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
                compassStatusTextView.text = String.format(
                    Locale.US,
                    "Pusula: %s %.0f° | Pitch %.1f°",
                    direction,
                    azimuth,
                    compass.getPitchDegrees()
                )
            }
        }
    }

    private fun updateModeText() {
        modeButton.text = if (hemisphereMode == Hemisphere.NORTH) "MOD: KUZEY" else "MOD: GUNEY"
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
                updateVerticalFovFromCamera(camera)
                captureButton.isEnabled = true
                updateCalculateButton()
            } catch (exc: Exception) {
                Log.e(TAG, "Kamera baslatma hatasi", exc)
                captureButton.isEnabled = false
                Toast.makeText(this, "Kamera baslatilamadi", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateVerticalFovFromCamera(cam: Camera?) {
        if (cam == null) return
        try {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            val sensorSize = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorHeightMm = sensorSize?.height
            val focalMm = focalLengths?.firstOrNull()
            if (sensorHeightMm != null && focalMm != null && sensorHeightMm > 0f && focalMm > 0f) {
                val fovRad = 2.0 * atan((sensorHeightMm / (2.0 * focalMm)).toDouble())
                verticalFov = Math.toDegrees(fovRad).toFloat().coerceIn(25f, 100f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kamera FOV metadata okunamadi, varsayilan 60 derece kullaniliyor", e)
        }
    }

    private fun takePhotoForSelection() {
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
                    cameraExecutor.execute {
                        try {
                            val bitmap = imageToBitmap(image)
                            runOnUiThread {
                                setSelectedPhoto(bitmap, CaptureSource.CAMERA)
                                setProcessingState(false)
                                Toast.makeText(this@MainActivity, "Foto cekildi. HESAPLA'ya bas.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Kamera fotografi hazirlama hatasi", e)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Fotograf hazirlanamadi", Toast.LENGTH_SHORT).show()
                                setProcessingState(false)
                            }
                        } finally {
                            image.close()
                        }
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Fotograf cekme hatasi", exc)
                    setProcessingState(false)
                    Toast.makeText(this@MainActivity, "Fotograf cekilemedi", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setSelectedPhoto(bitmap: Bitmap, source: CaptureSource) {
        selectedBitmap?.recycle()
        selectedBitmap = bitmap
        selectedSource = source
        val srcLabel = if (source == CaptureSource.CAMERA) "KAMERA" else "GALERI"
        infoTextView.text = "Secilen kaynak: $srcLabel | HESAPLA butonuna bas"
        resultTextView.text = "Hazir"
        updateCalculateButton()
    }

    private fun onCalculatePressed() {
        val bitmap = selectedBitmap
        val source = selectedSource
        if (bitmap == null || source == null) {
            Toast.makeText(this, "Once fotograf cek veya galeriden sec", Toast.LENGTH_SHORT).show()
            return
        }

        val observationDateTime = resolveObservationDateTime()
        if (observationDateTime == null) {
            Toast.makeText(this, "Format: YYYY-MM-DD ve HH:mm", Toast.LENGTH_SHORT).show()
            return
        }

        setProcessingState(true)
        cameraExecutor.execute {
            processBitmap(bitmap, source, observationDateTime)
        }
    }

    private fun processBitmap(bitmap: Bitmap, source: CaptureSource, observationDateTime: LocalDateTime) {
        try {
            val stars = starDetector.detectStars(bitmap)
            if (stars.size < 4) {
                runOnUiThread {
                    resultTextView.text = "Yeterli yildiz bulunamadi"
                    setProcessingState(false)
                }
                return
            }

            val dayFraction = (observationDateTime.hour * 60 + observationDateTime.minute).toFloat() / 1440f
            val dayOfYearFloat = observationDateTime.dayOfYear.toFloat() + dayFraction
            val dayOfYearInt = observationDateTime.dayOfYear
            val dateLabel = String.format(
                Locale.US,
                "%s %02d:%02d",
                observationDateTime.toLocalDate().toString(),
                observationDateTime.hour,
                observationDateTime.minute
            )

            val patternResult = skyPatternMatcher.match(
                stars = stars,
                hemisphereMode = if (hemisphereMode == Hemisphere.NORTH) "north" else "south",
                dayOfYear = dayOfYearFloat
            )

            val (candidateYs, referenceConfidence, modeName) = if (hemisphereMode == Hemisphere.NORTH) {
                val scored = polarisFinder.scoreStars(stars, bitmap.height).take(8)
                if (scored.isEmpty()) {
                    Triple(emptyList(), 0f, "Polaris")
                } else {
                    val clustered = clusterNorthCandidates(scored.map { it.star.y to it.totalScore }, bitmap.height)
                    val ys = clustered.map { it.first to (0.15f + it.second).coerceIn(0.15f, 1f) }
                    val conf = scored.take(3).map { it.totalScore }.average().toFloat()
                    Triple(ys, conf, "Polaris")
                }
            } else {
                val southCandidates = southernCrossFinder.findSouthCelestialPoleCandidates(
                    stars,
                    bitmap.height,
                    bitmap.width,
                    maxCandidates = 8
                )
                if (southCandidates.isEmpty()) {
                    Triple(emptyList(), 0f, "Guney Haci")
                } else {
                    val clustered = clusterSouthCandidates(southCandidates.map { it.pole.y to it.score }, bitmap.height)
                    val ys = clustered.map { it.first to (0.15f + it.second).coerceIn(0.15f, 1f) }
                    val conf = southCandidates.take(3).map { it.score }.average().toFloat()
                    Triple(ys, conf, "Guney Haci")
                }
            }

            if (candidateYs.isEmpty()) {
                runOnUiThread {
                    resultTextView.text = "Referans yildiz bulunamadi"
                    setProcessingState(false)
                }
                return
            }

            val catalogConfidence = starCatalogMatcher.match(
                stars = stars,
                hemisphereMode = if (hemisphereMode == Hemisphere.NORTH) "north" else "south"
            )
            val combinedConfidence =
                (0.45f * referenceConfidence + 0.30f * patternResult.confidence + 0.25f * catalogConfidence)
                    .coerceIn(0f, 1f)
            if (combinedConfidence < 0.22f) {
                runOnUiThread {
                    resultTextView.text = "Guven dusuk, daha net bir gok fotografi dene"
                    setProcessingState(false)
                }
                return
            }

            val pitch = compass.getPitchDegrees()
            val result = latitudeSolver.calculateLatitudeFromCandidates(
                candidateYsWithWeight = candidateYs,
                imageHeight = bitmap.height,
                verticalFov = verticalFov,
                cameraPitchDeg = pitch,
                southernHemisphere = hemisphereMode == Hemisphere.SOUTH
            )

            runOnUiThread {
                val hemisphereLabel = if (result.latitude >= 0f) "K" else "G"
                val sourceLabel = if (source == CaptureSource.CAMERA) "KAMERA" else "GALERI"
                resultTextView.text = String.format(
                    Locale.US,
                    "%s | Enlem %.4f°%s | Hata +/-%.2f° | Guven %.2f",
                    modeName,
                    abs(result.latitude),
                    hemisphereLabel,
                    result.errorMargin,
                    combinedConfidence
                )
                infoTextView.text = String.format(
                    Locale.US,
                    "Kaynak: %s | Tarih: %s | Katalog %.2f | Gun %d | Yildiz %d",
                    sourceLabel,
                    dateLabel,
                    catalogConfidence,
                    dayOfYearInt,
                    stars.size
                )
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

    private fun resolveObservationDateTime(): LocalDateTime? {
        if (dateModeToday.isChecked) return LocalDateTime.now()
        return try {
            val d = LocalDate.parse(manualDateInput.text?.toString().orEmpty().trim())
            val t = LocalTime.parse(manualTimeInput.text?.toString().orEmpty().trim())
            LocalDateTime.of(d, t)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun setProcessingState(processing: Boolean) {
        isProcessing = processing
        captureButton.isEnabled = !processing && imageCapture != null
        galleryButton.isEnabled = !processing
        modeButton.isEnabled = !processing
        dateModeToday.isEnabled = !processing
        dateModeManual.isEnabled = !processing
        manualDateInput.isEnabled = !processing && dateModeManual.isChecked
        manualTimeInput.isEnabled = !processing && dateModeManual.isChecked
        updateCalculateButton()
    }

    private fun updateCalculateButton() {
        calculateButton.isEnabled = !isProcessing && selectedBitmap != null
    }

    private fun loadSkyPatternMatcher(): SkyPatternMatcher {
        return try {
            val json = assets.open("sky_patterns.json").bufferedReader().use { it.readText() }
            SkyPatternMatcher.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Sky pattern verisi okunamadi", e)
            SkyPatternMatcher(emptyList())
        }
    }

    private fun loadStarCatalogMatcher(): StarCatalogMatcher {
        return try {
            val json = assets.open("polar_star_catalog.json").bufferedReader().use { it.readText() }
            StarCatalogMatcher.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Yildiz katalog verisi okunamadi", e)
            StarCatalogMatcher.fromJson("[]")
        }
    }

    private fun clusterNorthCandidates(candidates: List<Pair<Float, Float>>, imageHeight: Int): List<Pair<Float, Float>> {
        if (candidates.isEmpty()) return emptyList()
        val bestY = candidates.maxByOrNull { it.second }!!.first
        val band = imageHeight * 0.10f
        val inBand = candidates.filter { abs(it.first - bestY) <= band }
        val chosen = if (inBand.size >= 3) inBand else candidates.sortedByDescending { it.second }.take(3)
        return chosen.sortedByDescending { it.second }
    }

    private fun clusterSouthCandidates(candidates: List<Pair<Float, Float>>, imageHeight: Int): List<Pair<Float, Float>> {
        if (candidates.isEmpty()) return emptyList()
        val bestY = candidates.maxByOrNull { it.second }!!.first
        val band = imageHeight * 0.14f
        val inBand = candidates.filter { abs(it.first - bestY) <= band }
        val chosen = if (inBand.size >= 3) inBand else candidates.sortedByDescending { it.second }.take(4)
        return chosen.sortedByDescending { it.second }
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
