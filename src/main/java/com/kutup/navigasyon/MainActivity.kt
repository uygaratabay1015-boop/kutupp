package com.kutup.navigasyon

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    enum class Hemisphere { NORTH, SOUTH }
    enum class CaptureSource { CAMERA, GALLERY }
    data class OrientationCalibration(
        val pitchDeg: Float,
        val rollDeg: Float,
        val azimuthDeg: Float?,
        val note: String
    )
    data class SouthPointerRefinement(
        val refinedPole: Pair<Float, Float>,
        val pointerA: Star,
        val pointerB: Star,
        val score: Float
    )
    data class PolarStation(
        val name: String,
        val latitude: Double,
        val longitude: Double
    )

    companion object {
        private const val TAG = "KutupNav"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var previewView: PreviewView
    private lateinit var controlPanel: ViewGroup
    private lateinit var togglePanelButton: Button
    private lateinit var captureButton: Button
    private lateinit var galleryButton: Button
    private lateinit var calculateButton: Button
    private lateinit var modeButton: Button
    private lateinit var showMapButton: Button
    private lateinit var compassStatusTextView: TextView
    private lateinit var infoTextView: TextView
    private lateinit var resultTextView: TextView
    private lateinit var dateModeToday: RadioButton
    private lateinit var dateModeManual: RadioButton
    private lateinit var manualDateInput: EditText
    private lateinit var manualTimeInput: EditText
    private lateinit var manualPitchInput: EditText
    private lateinit var manualAzimuthInput: EditText
    private lateinit var manualHorizonPercentInput: EditText
    private lateinit var manualRollInput: EditText
    private lateinit var horizonPickButton: Button
    private lateinit var offlinePlateSwitch: SwitchCompat

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
    private var horizontalFov = 70f
    private var selectedBitmap: Bitmap? = null
    private var selectedSource: CaptureSource? = null
    private var selectedCameraPitchDeg: Float = 0f
    private var selectedCameraRollDeg: Float = 0f
    private var selectedCameraAzimuthDeg: Float? = null
    private var selectedCaptureDateTime: LocalDateTime? = null
    private var selectedExifDateTime: LocalDateTime? = null
    private var selectedExifAzimuthDeg: Float? = null
    private var latestAnnotatedBitmap: Bitmap? = null
    private var polarStations: List<PolarStation> = emptyList()
    private var lastEstimatedLatitude: Double? = null
    private var lastEstimatedLongitude: Double? = null
    private var isProcessing = false

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "Gorsel secilmedi", Toast.LENGTH_SHORT).show()
            setProcessingState(false)
            return@registerForActivityResult
        }

        cameraExecutor.execute {
            try {
                val exifMeta = readExifMetadata(uri)
                val bitmap = decodeBitmapFromUri(uri)
                if (bitmap == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Gorsel okunamadi", Toast.LENGTH_SHORT).show()
                        setProcessingState(false)
                    }
                    return@execute
                }
                runOnUiThread {
                    selectedExifDateTime = exifMeta.first
                    selectedExifAzimuthDeg = exifMeta.second
                    setSelectedPhoto(
                        bitmap = bitmap,
                        source = CaptureSource.GALLERY,
                        capturePitchDeg = null,
                        captureAzimuthDeg = selectedExifAzimuthDeg,
                        captureDateTime = selectedExifDateTime ?: nowMinutePrecision()
                    )
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
        polarStations = loadPolarStations()

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
        latestAnnotatedBitmap?.recycle()
        compass.stopListening()
        cameraExecutor.shutdown()
    }

    private fun initializeUI() {
        previewView = findViewById(R.id.previewView)
        controlPanel = findViewById(R.id.controlPanel)
        togglePanelButton = findViewById(R.id.togglePanelButton)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        calculateButton = findViewById(R.id.calculateButton)
        modeButton = findViewById(R.id.modeButton)
        showMapButton = findViewById(R.id.showMapButton)
        compassStatusTextView = findViewById(R.id.compassStatus)
        infoTextView = findViewById(R.id.azimutuResult)
        resultTextView = findViewById(R.id.latitudeResult)
        dateModeToday = findViewById(R.id.dateModeToday)
        dateModeManual = findViewById(R.id.dateModeManual)
        manualDateInput = findViewById(R.id.manualDateInput)
        manualTimeInput = findViewById(R.id.manualTimeInput)
        manualPitchInput = findViewById(R.id.manualPitchInput)
        manualAzimuthInput = findViewById(R.id.manualAzimuthInput)
        manualHorizonPercentInput = findViewById(R.id.manualHorizonPercentInput)
        manualRollInput = findViewById(R.id.manualRollInput)
        horizonPickButton = findViewById(R.id.horizonPickButton)
        offlinePlateSwitch = findViewById(R.id.offlinePlateSwitch)

        val now = LocalDateTime.now()
        manualDateInput.setText(now.toLocalDate().toString())
        manualTimeInput.setText(String.format(Locale.US, "%02d:%02d", now.hour, now.minute))
        manualPitchInput.setText("")
        manualAzimuthInput.setText("")
        manualHorizonPercentInput.setText("")
        manualRollInput.setText("")
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
        showMapButton.isEnabled = false

        captureButton.setOnClickListener { takePhotoForSelection() }
        galleryButton.setOnClickListener {
            setProcessingState(true)
            pickImageLauncher.launch("image/*")
        }
        horizonPickButton.setOnClickListener {
            val bmp = selectedBitmap
            if (bmp == null) {
                Toast.makeText(this, "Once fotograf sec veya cek", Toast.LENGTH_SHORT).show()
            } else {
                showHorizonPickerDialog(bmp)
            }
        }
        togglePanelButton.setOnClickListener { toggleControlPanel() }
        calculateButton.setOnClickListener { onCalculatePressed() }
        showMapButton.setOnClickListener { showEstimatedLocationOnMap() }
        modeButton.setOnClickListener {
            hemisphereMode = if (hemisphereMode == Hemisphere.NORTH) Hemisphere.SOUTH else Hemisphere.NORTH
            updateModeText()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun toggleControlPanel() {
        val isVisible = controlPanel.visibility == View.VISIBLE
        controlPanel.visibility = if (isVisible) View.GONE else View.VISIBLE
        togglePanelButton.text = if (isVisible) "Panel Goster" else "Panel Gizle"
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
                    "Pusula: %s %.0f° | Pitch %.1f° | Roll %.1f°",
                    direction,
                    azimuth,
                    compass.getPitchDegrees(),
                    compass.getRollDegrees()
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
            val sensorWidthMm = sensorSize?.width
            val focalMm = focalLengths?.firstOrNull()
            if (sensorHeightMm != null && focalMm != null && sensorHeightMm > 0f && focalMm > 0f) {
                val fovRad = 2.0 * atan((sensorHeightMm / (2.0 * focalMm)).toDouble())
                verticalFov = Math.toDegrees(fovRad).toFloat().coerceIn(25f, 100f)
            }
            if (sensorWidthMm != null && focalMm != null && sensorWidthMm > 0f && focalMm > 0f) {
                val hFovRad = 2.0 * atan((sensorWidthMm / (2.0 * focalMm)).toDouble())
                horizontalFov = Math.toDegrees(hFovRad).toFloat().coerceIn(35f, 120f)
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
                            val rawBitmap = imageToBitmap(image)
                            val bitmap = rotateBitmap(rawBitmap, image.imageInfo.rotationDegrees)
                            runOnUiThread {
                                setSelectedPhoto(
                                    bitmap = bitmap,
                                    source = CaptureSource.CAMERA,
                                    capturePitchDeg = compass.getPitchDegrees(),
                                    captureRollDeg = compass.getRollDegrees(),
                                    captureAzimuthDeg = compass.getAzimuth(),
                                    captureDateTime = nowMinutePrecision()
                                )
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

    private fun setSelectedPhoto(
        bitmap: Bitmap,
        source: CaptureSource,
        capturePitchDeg: Float? = null,
        captureRollDeg: Float? = null,
        captureAzimuthDeg: Float? = null,
        captureDateTime: LocalDateTime? = null
    ) {
        selectedBitmap?.recycle()
        selectedBitmap = bitmap
        selectedSource = source
        selectedCameraPitchDeg = capturePitchDeg ?: compass.getPitchDegrees()
        selectedCameraRollDeg = captureRollDeg ?: compass.getRollDegrees()
        selectedCameraAzimuthDeg = captureAzimuthDeg ?: compass.getAzimuth()
        selectedCaptureDateTime = captureDateTime ?: nowMinutePrecision()
        if (source == CaptureSource.CAMERA) {
            selectedExifDateTime = null
            selectedExifAzimuthDeg = null
        }
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

        val observationDateTime = resolveObservationDateTime(source)
        if (observationDateTime == null) {
            Toast.makeText(this, "Format: YYYY-MM-DD ve HH:mm", Toast.LENGTH_SHORT).show()
            return
        }

        val calibration = resolveOrientationCalibration(source, bitmap.height, verticalFov, hemisphereMode)

        setProcessingState(true)
        val useOfflinePlate = offlinePlateSwitch.isChecked
        cameraExecutor.execute {
            processBitmap(
                bitmap = bitmap,
                source = source,
                observationDateTime = observationDateTime,
                pitchForSolve = calibration.pitchDeg,
                rollForSolve = calibration.rollDeg,
                azimuthForSolve = calibration.azimuthDeg,
                calibrationNote = calibration.note,
                useOfflinePlate = useOfflinePlate
            )
        }
    }

    private fun processBitmap(
        bitmap: Bitmap,
        source: CaptureSource,
        observationDateTime: LocalDateTime,
        pitchForSolve: Float,
        rollForSolve: Float,
        azimuthForSolve: Float?,
        calibrationNote: String,
        useOfflinePlate: Boolean
    ) {
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
            if (!useOfflinePlate && patternResult.confidence < 0.18f) {
                runOnUiThread {
                    resultTextView.text = "Tarih/desen uyumu dusuk, tarih-saati kontrol et"
                    setProcessingState(false)
                }
                return
            }
            if (
                hemisphereMode == Hemisphere.SOUTH &&
                source == CaptureSource.GALLERY &&
                calibrationNote.contains("varsayim", ignoreCase = true)
            ) {
                runOnUiThread {
                    resultTextView.text = "Guney modunda galeriden hesap icin Pitch gir veya ufuk yuzdesi ver"
                    setProcessingState(false)
                }
                return
            }

            var southPointerRefinement: SouthPointerRefinement? = null
            var southPoleCandidatesForOverlay: List<Pair<Float, Float>> = emptyList()

            val (baseCandidatePoints, referenceConfidence, modeNameBase) = if (hemisphereMode == Hemisphere.NORTH) {
                val scored = polarisFinder.scoreStars(stars, bitmap.height, bitmap.width).take(8)
                if (scored.isEmpty()) {
                    Triple(emptyList(), 0f, "Polaris")
                } else {
                    val northHeading = sanitizedHeadingForHemisphere(azimuthForSolve, Hemisphere.NORTH)
                    val rescored = scored.map {
                        val azScore = if (northHeading == null) {
                            0.5f
                        } else {
                            azimuthConsistencyScore(
                                starX = it.star.x,
                                imageWidth = bitmap.width,
                                headingAzimuthDeg = northHeading,
                                targetAzimuthDeg = 0f
                            )
                        }
                        val yNorm = (it.star.y / bitmap.height.toFloat()).coerceIn(0f, 1f)
                        val verticalPenalty = when {
                            yNorm > 0.90f -> 0.20f
                            yNorm > 0.78f -> 0.45f
                            yNorm > 0.65f -> 0.75f
                            else -> 1.0f
                        }
                        val merged = ((0.55f * it.totalScore + 0.45f * azScore) * verticalPenalty).coerceIn(0f, 1f)
                        Triple(it.star.x, it.star.y, merged)
                    }
                    val clustered = clusterNorthCandidates(rescored, bitmap.height)
                    val weighted = clustered.map { Triple(it.first, it.second, (0.15f + it.third).coerceIn(0.15f, 1f)) }
                    val conf = clustered.take(3).map { it.third }.average().toFloat().coerceIn(0f, 1f)
                    Triple(weighted, conf, "Polaris")
                }
            } else {
                val southCandidates = southernCrossFinder.findSouthCelestialPoleCandidates(
                    stars,
                    bitmap.height,
                    bitmap.width,
                    maxCandidates = 8
                )
                southPoleCandidatesForOverlay = southCandidates.take(5).map { Pair(it.pole.x, it.pole.y) }
                if (southCandidates.isEmpty()) {
                    Triple(emptyList(), 0f, "Guney Haci")
                } else {
                    val southHeading = sanitizedHeadingForHemisphere(azimuthForSolve, Hemisphere.SOUTH)
                    val rescored = southCandidates.map {
                        val azScore = if (southHeading == null) {
                            0.5f
                        } else {
                            azimuthConsistencyScore(
                                starX = it.pole.x,
                                imageWidth = bitmap.width,
                                headingAzimuthDeg = southHeading,
                                targetAzimuthDeg = 180f
                            )
                        }
                        val merged = (0.70f * it.score + 0.30f * azScore).coerceIn(0f, 1f)
                        Triple(it.pole.x, it.pole.y, merged)
                    }
                    val clustered = clusterSouthCandidates(rescored, bitmap.height)
                    val weighted = clustered.map { Triple(it.first, it.second, (0.15f + it.third).coerceIn(0.15f, 1f)) }
                    val conf = clustered.take(3).map { it.third }.average().toFloat().coerceIn(0f, 1f)
                    Triple(weighted, conf, "Guney Haci")
                }
            }
            var modeName = modeNameBase

            val plateSolve = starCatalogMatcher.estimatePolePoint(
                stars = stars,
                hemisphereMode = if (hemisphereMode == Hemisphere.NORTH) "north" else "south"
            )
            val plateSolveConfidence = plateSolve?.confidence ?: 0f
            val candidatePoints = baseCandidatePoints.toMutableList()
            if (plateSolve != null) {
                val marginX = bitmap.width * 0.30f
                val marginY = bitmap.height * 0.30f
                val plausible =
                    plateSolve.poleX in (-marginX)..(bitmap.width + marginX) &&
                        plateSolve.poleY in (-marginY)..(bitmap.height + marginY)
                if (plausible) {
                    val plateWeight = (0.35f + 0.65f * plateSolveConfidence).coerceIn(0.22f, 1f)
                    candidatePoints.add(Triple(plateSolve.poleX, plateSolve.poleY, plateWeight))
                }
            }
            val plateSolveOk = plateSolve != null && plateSolveConfidence >= 0.45f
            if (useOfflinePlate && plateSolveOk) {
                candidatePoints.clear()
                candidatePoints.add(Triple(plateSolve!!.poleX, plateSolve.poleY, 1.0f))
                modeName = "PlateSolve"
            } else if (useOfflinePlate) {
                modeName = "${modeNameBase} (Fallback)"
            }
            var pointerConfidence = 0f
            if (hemisphereMode == Hemisphere.SOUTH) {
                val initialSouthPole = candidatePoints.maxByOrNull { it.third }?.let { Pair(it.first, it.second) }
                if (initialSouthPole != null) {
                    southPointerRefinement = refineSouthPoleWithPointers(
                        stars = stars,
                        initialSouthPole = initialSouthPole,
                        imageHeight = bitmap.height
                    )
                    if (southPointerRefinement != null) {
                        pointerConfidence = southPointerRefinement!!.score.coerceIn(0f, 1f)
                        val w = (0.28f + 0.72f * pointerConfidence).coerceIn(0.22f, 1f)
                        candidatePoints.add(
                            Triple(
                                southPointerRefinement!!.refinedPole.first,
                                southPointerRefinement!!.refinedPole.second,
                                w
                            )
                        )
                    }
                }
            }

            if (candidatePoints.isEmpty()) {
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
                if (useOfflinePlate && plateSolveOk) {
                    (0.55f * plateSolveConfidence + 0.45f * catalogConfidence).coerceIn(0f, 1f)
                } else {
                    (
                        if (hemisphereMode == Hemisphere.NORTH) {
                            0.37f * referenceConfidence +
                                0.25f * patternResult.confidence +
                                0.20f * catalogConfidence +
                                0.18f * plateSolveConfidence
                        } else {
                            0.32f * referenceConfidence +
                                0.20f * patternResult.confidence +
                                0.18f * catalogConfidence +
                                0.15f * plateSolveConfidence +
                                0.15f * pointerConfidence
                        }
                        )
                        .coerceIn(0f, 1f)
                }
            if (!useOfflinePlate && hemisphereMode == Hemisphere.NORTH) {
                val p = patternResult.matchedPattern.lowercase(Locale.US)
                val northPatternOk = p.contains("ursa")
                if (!northPatternOk && referenceConfidence < 0.48f) {
                    runOnUiThread {
                        resultTextView.text = "Kuzeyde Polaris/Kucuk Ayi secilemedi, kadraji yeniden ayarla"
                        setProcessingState(false)
                    }
                    return
                }
                if (referenceConfidence < 0.30f) {
                    runOnUiThread {
                        resultTextView.text = "Polaris guveni dusuk, net ve daha karanlik gok fotografi dene"
                        setProcessingState(false)
                    }
                    return
                }
            }
            if (!useOfflinePlate && hemisphereMode == Hemisphere.SOUTH && referenceConfidence < 0.32f) {
                runOnUiThread {
                    resultTextView.text = "Guney Haci geometri guveni dusuk, kalibrasyonu kontrol et"
                    setProcessingState(false)
                }
                return
            }
            val minCombinedConfidence = if (hemisphereMode == Hemisphere.NORTH) 0.28f else 0.22f
            if (!useOfflinePlate && combinedConfidence < minCombinedConfidence) {
                runOnUiThread {
                    resultTextView.text = "Guven dusuk, daha net bir gok fotografi dene"
                    setProcessingState(false)
                }
                return
            }

            val result = latitudeSolver.calculateLatitudeFromCandidatePoints(
                candidatePointsWithWeight = candidatePoints,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                verticalFov = verticalFov,
                horizontalFov = horizontalFov,
                cameraPitchDeg = pitchForSolve,
                cameraRollDeg = rollForSolve,
                southernHemisphere = hemisphereMode == Hemisphere.SOUTH
            )
            val nearPole = kotlin.math.abs(result.latitude) >= 88f
            if (nearPole && !useOfflinePlate && (referenceConfidence < 0.55f || catalogConfidence < 0.45f || plateSolveConfidence < 0.35f)) {
                runOnUiThread {
                    resultTextView.text = "Kutup sonucu guvensiz, kalibrasyon/tarih hatali olabilir"
                    setProcessingState(false)
                }
                return
            }

            runOnUiThread {
                val hemisphereLabel = if (result.latitude >= 0f) "K" else "G"
                val sourceLabel = if (source == CaptureSource.CAMERA) "KAMERA" else "GALERI"
                val estimatedLongitude = calculateLongitudeFromUtcTime(observationDateTime)
                val constellation = constellationLabel(
                    patternName = patternResult.matchedPattern,
                    mode = hemisphereMode,
                    referenceConfidence = referenceConfidence,
                    patternConfidence = patternResult.confidence
                )
                val modeTag = if (useOfflinePlate && plateSolveOk) "Offline" else if (useOfflinePlate) "Offline-Fallback" else "Klasik"
                resultTextView.text = String.format(
                    Locale.US,
                    "%s | Enlem %.4fÂ°%s | Hata +/-%.2fÂ° | Guven %.2f",
                    modeName,
                    abs(result.latitude),
                    hemisphereLabel,
                    result.errorMargin,
                    combinedConfidence
                )
                infoTextView.text = String.format(
                    Locale.US,
                    "Kaynak: %s | %s | Mod: %s | Takimyildiz: %s | Desen: %s | Tarih: %s | Katalog %.2f | Plate %.2f | Gun %d | Yildiz %d",
                    sourceLabel,
                    calibrationNote,
                    modeTag,
                    constellation,
                    patternResult.matchedPattern,
                    dateLabel,
                    catalogConfidence,
                    plateSolveConfidence,
                    dayOfYearInt,
                    stars.size
                )
                lastEstimatedLatitude = result.latitude.toDouble()
                lastEstimatedLongitude = estimatedLongitude
                showMapButton.isEnabled = true
                val overlayStars = selectConstellationOverlayStars(
                    stars = stars,
                    hemisphere = hemisphereMode,
                    candidatePoints = candidatePoints,
                    imageWidth = bitmap.width,
                    pointerRefinement = southPointerRefinement
                )
                val polePoint = if (hemisphereMode == Hemisphere.NORTH) {
                    candidatePoints.maxByOrNull { it.third }?.let { Pair(it.first, it.second) }
                } else {
                    candidatePoints.maxByOrNull { it.third }?.let { Pair(it.first, it.second) }
                }
                val annotated = buildAnnotatedSkyBitmap(
                    sourceBitmap = bitmap,
                    allStars = stars,
                    overlayStars = overlayStars,
                    patternName = constellation,
                    polePoint = polePoint,
                    southPoleCandidates = southPoleCandidatesForOverlay,
                    pointerRefinement = southPointerRefinement
                )
                latestAnnotatedBitmap?.recycle()
                latestAnnotatedBitmap = annotated
                showAnnotatedSkyDialog(annotated)
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

    private fun resolveObservationDateTime(source: CaptureSource): LocalDateTime? {
        if (dateModeToday.isChecked) {
            return if (source == CaptureSource.GALLERY) {
                selectedExifDateTime ?: selectedCaptureDateTime ?: nowMinutePrecision()
            } else {
                selectedCaptureDateTime ?: nowMinutePrecision()
            }
        }
        return try {
            val d = LocalDate.parse(manualDateInput.text?.toString().orEmpty().trim())
            val t = LocalTime.parse(manualTimeInput.text?.toString().orEmpty().trim())
            LocalDateTime.of(d, t)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun resolveOrientationCalibration(
        source: CaptureSource,
        imageHeight: Int,
        vFov: Float,
        hemisphereMode: Hemisphere
    ): OrientationCalibration {
        val manualPitch = parseFloatOrNull(manualPitchInput.text?.toString().orEmpty())
        val manualRoll = parseFloatOrNull(manualRollInput.text?.toString().orEmpty())
        val manualAzimuth = parseFloatOrNull(manualAzimuthInput.text?.toString().orEmpty())?.let { normalize360(it) }
        val horizonPercentRaw = parseFloatOrNull(manualHorizonPercentInput.text?.toString().orEmpty())
        val horizonPercent = if (horizonPercentRaw == null &&
            manualPitch == null &&
            hemisphereMode == Hemisphere.SOUTH &&
            source == CaptureSource.GALLERY
        ) {
            50f
        } else {
            horizonPercentRaw
        }

        val horizonPitch = horizonPercent?.let {
            if (it in 0f..100f) {
                val horizonY = imageHeight * (it / 100f)
                val centerY = imageHeight / 2f
                val degPerPixel = vFov / imageHeight.toFloat()
                (horizonY - centerY) * degPerPixel
            } else null
        }

        val pitch = when {
            manualPitch != null -> manualPitch
            horizonPitch != null -> horizonPitch
            source == CaptureSource.CAMERA -> selectedCameraPitchDeg
            else -> 0f
        }

        val azimuth = when {
            manualAzimuth != null -> manualAzimuth
            source == CaptureSource.CAMERA -> selectedCameraAzimuthDeg
            else -> selectedExifAzimuthDeg
        }

        val roll = when {
            manualRoll != null -> manualRoll
            source == CaptureSource.CAMERA -> selectedCameraRollDeg
            else -> 0f
        }

        val note = when {
            manualPitch != null && manualRoll != null -> "Pitch/Roll: Manuel"
            manualPitch != null -> "Pitch: Manuel"
            horizonPitch != null && manualRoll != null -> "Pitch: Ufuk, Roll: Manuel"
            horizonPitch != null && horizonPercentRaw == null -> "Pitch: Ufuk (varsayim)"
            horizonPitch != null -> "Pitch: Ufuk"
            source == CaptureSource.CAMERA -> "Pitch/Roll: Sensor"
            else -> "Pitch/Roll: Otomatik varsayim (0/0)"
        }
        return OrientationCalibration(
            pitchDeg = pitch.coerceIn(-85f, 85f),
            rollDeg = roll.coerceIn(-85f, 85f),
            azimuthDeg = azimuth,
            note = note
        )
    }

    private fun parseFloatOrNull(raw: String): Float? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return try {
            t.replace(',', '.').toFloat()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun nowMinutePrecision(): LocalDateTime {
        val n = LocalDateTime.now()
        return LocalDateTime.of(n.toLocalDate(), LocalTime.of(n.hour, n.minute))
    }

    private fun constellationLabel(
        patternName: String,
        mode: Hemisphere,
        referenceConfidence: Float,
        patternConfidence: Float
    ): String {
        if (mode == Hemisphere.NORTH && referenceConfidence >= 0.42f) {
            return "Kucuk Ayi (Ursa Minor / Polaris)"
        }
        if (mode == Hemisphere.SOUTH && referenceConfidence >= 0.42f) {
            return "Guney Haci (Crux)"
        }

        val p = patternName.lowercase(Locale.US)
        return when {
            p.contains("ursa") && patternConfidence >= 0.25f -> "Kucuk Ayi (Ursa Minor)"
            p.contains("cassiopeia") && patternConfidence >= 0.25f -> "Cassiopeia"
            p.contains("crux") && patternConfidence >= 0.25f -> "Guney Haci (Crux)"
            else -> if (mode == Hemisphere.NORTH) "Kuzey kutup bolgesi" else "Guney kutup bolgesi"
        }
    }

    private fun setProcessingState(processing: Boolean) {
        isProcessing = processing
        captureButton.isEnabled = !processing && imageCapture != null
        galleryButton.isEnabled = !processing
        modeButton.isEnabled = !processing
        showMapButton.isEnabled = !processing && lastEstimatedLatitude != null && lastEstimatedLongitude != null
        dateModeToday.isEnabled = !processing
        dateModeManual.isEnabled = !processing
        manualDateInput.isEnabled = !processing && dateModeManual.isChecked
        manualTimeInput.isEnabled = !processing && dateModeManual.isChecked
        manualRollInput.isEnabled = !processing
        horizonPickButton.isEnabled = !processing && selectedBitmap != null
        updateCalculateButton()
    }

    private fun updateCalculateButton() {
        calculateButton.isEnabled = !isProcessing && selectedBitmap != null
    }

    private fun showEstimatedLocationOnMap() {
        val lat = lastEstimatedLatitude
        val lon = lastEstimatedLongitude
        if (lat == null || lon == null) {
            Toast.makeText(this, "Once hesaplama yap", Toast.LENGTH_SHORT).show()
            return
        }
        val markers = mutableListOf<MapMarker>()
        markers.add(
            MapMarker(
                name = "Tahmini",
                latitude = lat,
                longitude = lon,
                color = Color.parseColor("#FF5252"),
                radiusPx = 8f
            )
        )
        val nearest = nearestStation(lat, lon)
        if (nearest != null) {
            markers.add(
                MapMarker(
                    name = nearest.name,
                    latitude = nearest.latitude,
                    longitude = nearest.longitude,
                    color = Color.parseColor("#FFD54F"),
                    radiusPx = 6f
                )
            )
        }
        for (s in polarStations.take(10)) {
            markers.add(
                MapMarker(
                    name = s.name,
                    latitude = s.latitude,
                    longitude = s.longitude,
                    color = Color.parseColor("#42A5F5"),
                    radiusPx = 4f
                )
            )
        }
        val label = String.format(
            Locale.US,
            "Tahmini: %.2f°, %.2f°",
            lat,
            lon
        )
        MiniMapView.showFullscreenMap(this, markers, label)
    }

    private fun calculateLongitudeFromUtcTime(dateTime: LocalDateTime): Double {
        val totalMinutes = dateTime.hour * 60.0 + dateTime.minute.toDouble()
        var longitude = (totalMinutes - 720.0) * (360.0 / 1440.0)
        if (longitude > 180.0) longitude -= 360.0
        if (longitude < -180.0) longitude += 360.0
        return longitude
    }

    private fun nearestStation(latitude: Double, longitude: Double): PolarStation? {
        if (polarStations.isEmpty()) return null
        return polarStations.minByOrNull { s ->
            val dLat = latitude - s.latitude
            val dLon = longitude - s.longitude
            dLat.pow(2) + dLon.pow(2)
        }
    }

    private fun loadPolarStations(): List<PolarStation> {
        return try {
            val json = assets.open("polar_stations.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            val out = mutableListOf<PolarStation>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    PolarStation(
                        name = o.optString("name", "Istasyon"),
                        latitude = o.optDouble("latitude", 0.0),
                        longitude = o.optDouble("longitude", 0.0)
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Polar station verisi okunamadi", e)
            emptyList()
        }
    }

    private fun showHorizonPickerDialog(bitmap: Bitmap) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this)
        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }

        var selectedY: Float? = null
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(240, 255, 85, 85)
            strokeWidth = 4f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            setShadowLayer(6f, 1f, 1f, Color.BLACK)
        }
        val overlay = object : android.view.View(this) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val y = selectedY ?: return
                canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
                canvas.drawText("Ufuk cizgisi secildi - Dokunarak guncelle", 26f, 56f, textPaint)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                    event.action == android.view.MotionEvent.ACTION_MOVE
                ) {
                    selectedY = event.y.coerceIn(0f, height.toFloat())
                    invalidate()
                    true
                } else if (event.action == android.view.MotionEvent.ACTION_UP) {
                    selectedY = event.y.coerceIn(0f, height.toFloat())
                    val horizonPercent = mapTouchYToBitmapPercent(imageView, selectedY!!, bitmap)
                    if (horizonPercent != null) {
                        manualHorizonPercentInput.setText(String.format(Locale.US, "%.1f", horizonPercent))
                        Toast.makeText(
                            this@MainActivity,
                            String.format(Locale.US, "Ufuk: %.1f%% kaydedildi", horizonPercent),
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@MainActivity, "Goruntu alanina dokun", Toast.LENGTH_SHORT).show()
                    }
                    true
                } else {
                    false
                }
            }
        }

        root.addView(imageView)
        root.addView(overlay)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun mapTouchYToBitmapPercent(
        imageView: ImageView,
        touchY: Float,
        bitmap: Bitmap
    ): Float? {
        val drawable = imageView.drawable ?: return null
        val values = FloatArray(9)
        imageView.imageMatrix.getValues(values)
        val scaleY = values[Matrix.MSCALE_Y]
        val transY = values[Matrix.MTRANS_Y]
        val shownHeight = drawable.intrinsicHeight * scaleY
        if (shownHeight <= 1f) return null
        if (touchY < transY || touchY > transY + shownHeight) return null
        val relY = ((touchY - transY) / shownHeight).coerceIn(0f, 1f)
        return (relY * 100f).coerceIn(0f, 100f)
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

    private fun clusterNorthCandidates(
        candidates: List<Triple<Float, Float, Float>>,
        imageHeight: Int
    ): List<Triple<Float, Float, Float>> {
        if (candidates.isEmpty()) return emptyList()
        val topHalf = candidates.filter { it.second <= imageHeight * 0.70f }
        val pool = if (topHalf.isNotEmpty()) topHalf else candidates
        val bestY = pool.maxByOrNull { it.third }!!.second
        val band = imageHeight * 0.10f
        val inBand = pool.filter { abs(it.second - bestY) <= band }
        val chosen = if (inBand.size >= 3) inBand else pool.sortedByDescending { it.third }.take(3)
        return chosen.sortedByDescending { it.third }
    }

    private fun clusterSouthCandidates(
        candidates: List<Triple<Float, Float, Float>>,
        imageHeight: Int
    ): List<Triple<Float, Float, Float>> {
        if (candidates.isEmpty()) return emptyList()
        val bestY = candidates.maxByOrNull { it.third }!!.second
        val band = imageHeight * 0.14f
        val inBand = candidates.filter { abs(it.second - bestY) <= band }
        val chosen = if (inBand.size >= 3) inBand else candidates.sortedByDescending { it.third }.take(4)
        return chosen.sortedByDescending { it.third }
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
            val decoded = BitmapFactory.decodeStream(input, null, opts) ?: return null
            val rotation = readExifRotation(uri)
            return rotateBitmap(decoded, rotation)
        }
    }

    private fun readExifRotation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) return 0
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun readExifMetadata(uri: Uri): Pair<LocalDateTime?, Float?> {
        return try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) return Pair(null, null)
                val exif = ExifInterface(input)

                val dtRaw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val dt = parseExifDateTime(dtRaw)

                val az = exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN)
                val azimuth = if (az.isNaN()) null else normalize360(az.toFloat())
                Pair(dt, azimuth)
            }
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun parseExifDateTime(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val formatters = listOf(
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        )
        for (f in formatters) {
            try {
                return LocalDateTime.parse(text, f)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) source.recycle()
        return rotated
    }

    private fun refineSouthPoleWithPointers(
        stars: List<Star>,
        initialSouthPole: Pair<Float, Float>,
        imageHeight: Int
    ): SouthPointerRefinement? {
        if (stars.size < 8 || imageHeight <= 0) return null
        val bright = stars.sortedByDescending { it.brightness }.take(22)
        var bestPair: Pair<Star, Star>? = null
        var bestScore = 0f

        for (i in 0 until bright.size) {
            for (j in i + 1 until bright.size) {
                val a = bright[i]
                val b = bright[j]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d < imageHeight * 0.04f || d > imageHeight * 0.45f) continue

                val num = kotlin.math.abs((initialSouthPole.second - a.y) * dx - (initialSouthPole.first - a.x) * dy)
                val lineDist = num / d.coerceAtLeast(1e-3f)
                val lineScore = (1f - lineDist / (imageHeight * 0.18f)).coerceIn(0f, 1f)
                val brightnessScore = (((a.brightness + b.brightness) / 2f) / 255f).coerceIn(0f, 1f)
                val score = (0.62f * lineScore + 0.38f * brightnessScore).coerceIn(0f, 1f)

                if (score > bestScore) {
                    bestScore = score
                    bestPair = Pair(a, b)
                }
            }
        }
        val pair = bestPair ?: return null
        if (bestScore < 0.38f) return null

        val a = pair.first
        val b = pair.second
        val dx = b.x - a.x
        val dy = b.y - a.y
        val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val ux = dx / d
        val uy = dy / d
        val k = 2.8f
        val candA = Pair(a.x + k * d * ux, a.y + k * d * uy)
        val candB = Pair(b.x - k * d * ux, b.y - k * d * uy)
        val da = hypot((candA.first - initialSouthPole.first).toDouble(), (candA.second - initialSouthPole.second).toDouble())
        val db = hypot((candB.first - initialSouthPole.first).toDouble(), (candB.second - initialSouthPole.second).toDouble())
        val pointerTarget = if (da <= db) candA else candB
        val refined = Pair(
            0.68f * initialSouthPole.first + 0.32f * pointerTarget.first,
            0.68f * initialSouthPole.second + 0.32f * pointerTarget.second
        )
        return SouthPointerRefinement(
            refinedPole = refined,
            pointerA = a,
            pointerB = b,
            score = bestScore
        )
    }

    private fun selectConstellationOverlayStars(
        stars: List<Star>,
        hemisphere: Hemisphere,
        candidatePoints: List<Triple<Float, Float, Float>>,
        imageWidth: Int,
        pointerRefinement: SouthPointerRefinement?
    ): List<Star> {
        if (stars.isEmpty()) return emptyList()
        val seed = candidatePoints.maxByOrNull { it.third }?.let { Pair(it.first, it.second) }
            ?: Pair(imageWidth / 2f, stars.minOfOrNull { it.y } ?: 0f)
        val nearest = stars
            .sortedBy { hypot((it.x - seed.first).toDouble(), (it.y - seed.second).toDouble()) }
            .take(if (hemisphere == Hemisphere.NORTH) 7 else 6)
            .toMutableList()
        if (hemisphere == Hemisphere.SOUTH && pointerRefinement != null) {
            nearest.add(pointerRefinement.pointerA)
            nearest.add(pointerRefinement.pointerB)
        }
        return nearest.distinctBy { "${it.x.toInt()}_${it.y.toInt()}" }
    }

    private fun buildAnnotatedSkyBitmap(
        sourceBitmap: Bitmap,
        allStars: List<Star>,
        overlayStars: List<Star>,
        patternName: String,
        polePoint: Pair<Float, Float>?,
        southPoleCandidates: List<Pair<Float, Float>>,
        pointerRefinement: SouthPointerRefinement?
    ): Bitmap {
        val mutable = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val allStarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 180, 180, 180)
            style = Paint.Style.FILL
        }
        val overlayStarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(240, 255, 214, 10)
            style = Paint.Style.FILL
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 214, 10)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val polePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(245, 255, 64, 64)
            style = Paint.Style.STROKE
            strokeWidth = 3.2f
        }
        val candidatePolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 124, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (mutable.width * 0.032f).coerceIn(26f, 44f)
            setShadowLayer(6f, 1f, 1f, Color.BLACK)
        }

        for (s in allStars.take(120)) {
            val r = (1.3f + (s.brightness / 255f) * 2.1f).coerceIn(1.2f, 4.0f)
            canvas.drawCircle(s.x, s.y, r, allStarPaint)
        }

        val orderedOverlay = overlayStars.sortedBy { it.y }
        for (i in orderedOverlay.indices) {
            val s = orderedOverlay[i]
            canvas.drawCircle(s.x, s.y, 5.2f, overlayStarPaint)
            if (i > 0) {
                val p = orderedOverlay[i - 1]
                canvas.drawLine(p.x, p.y, s.x, s.y, linePaint)
            }
        }
        for (c in southPoleCandidates) {
            canvas.drawCircle(c.first, c.second, 9f, candidatePolePaint)
        }
        if (pointerRefinement != null) {
            val a = pointerRefinement.pointerA
            val b = pointerRefinement.pointerB
            canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }

        if (polePoint != null) {
            val px = polePoint.first
            val py = polePoint.second
            canvas.drawCircle(px, py, 18f, polePaint)
            canvas.drawLine(px - 24f, py, px + 24f, py, polePaint)
            canvas.drawLine(px, py - 24f, px, py + 24f, polePaint)
            canvas.drawText("SCP / Kutup", px + 24f, py - 16f, labelPaint)
        }

        val top = (mutable.height * 0.06f).coerceAtLeast(40f)
        canvas.drawText("Takimyildiz bolgesi: $patternName", 24f, top, labelPaint)
        return mutable
    }

    private fun showAnnotatedSkyDialog(annotatedBitmap: Bitmap) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(annotatedBitmap)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }

    private fun azimuthConsistencyScore(
        starX: Float,
        imageWidth: Int,
        headingAzimuthDeg: Float,
        targetAzimuthDeg: Float
    ): Float {
        if (imageWidth <= 0) return 0.5f
        if (starX < -0.25f * imageWidth || starX > 1.25f * imageWidth) return 0.5f
        val centerX = imageWidth / 2f
        val deltaPx = starX - centerX
        val degPerPixel = horizontalFov / imageWidth.toFloat()
        val candidateAz = normalize360(headingAzimuthDeg + deltaPx * degPerPixel)
        val diff = angularDistance(candidateAz, targetAzimuthDeg)
        return (1f - (diff / 90f)).coerceIn(0f, 1f)
    }

    private fun normalize360(deg: Float): Float {
        var d = deg
        while (d < 0f) d += 360f
        while (d >= 360f) d -= 360f
        return d
    }

    private fun angularDistance(a: Float, b: Float): Float {
        var d = abs(a - b)
        if (d > 180f) d = 360f - d
        return d
    }

    private fun sanitizedHeadingForHemisphere(heading: Float?, mode: Hemisphere): Float? {
        if (heading == null) return null
        val target = if (mode == Hemisphere.NORTH) 0f else 180f
        val diff = angularDistance(heading, target)
        // Mod ile cok celisen manuel azimutu cezalandirmak yerine yok say.
        return if (diff <= 70f) heading else null
    }
}



