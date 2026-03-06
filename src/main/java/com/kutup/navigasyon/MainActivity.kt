package com.kutup.navigasyon

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.exifinterface.media.ExifInterface
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

class MainActivity : AppCompatActivity() {

    enum class Hemisphere { NORTH, SOUTH }
    enum class CaptureSource { CAMERA, GALLERY }
    data class OrientationCalibration(
        val pitchDeg: Float,
        val rollDeg: Float,
        val azimuthDeg: Float?,
        val note: String
    )

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
    private lateinit var manualPitchInput: EditText
    private lateinit var manualAzimuthInput: EditText
    private lateinit var manualHorizonPercentInput: EditText
    private lateinit var manualRollInput: EditText

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
        manualPitchInput = findViewById(R.id.manualPitchInput)
        manualAzimuthInput = findViewById(R.id.manualAzimuthInput)
        manualHorizonPercentInput = findViewById(R.id.manualHorizonPercentInput)
        manualRollInput = findViewById(R.id.manualRollInput)

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

        val calibration = resolveOrientationCalibration(source, bitmap.height, verticalFov)

        setProcessingState(true)
        cameraExecutor.execute {
            processBitmap(
                bitmap = bitmap,
                source = source,
                observationDateTime = observationDateTime,
                pitchForSolve = calibration.pitchDeg,
                rollForSolve = calibration.rollDeg,
                azimuthForSolve = calibration.azimuthDeg,
                calibrationNote = calibration.note
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
        calibrationNote: String
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
            if (patternResult.confidence < 0.18f) {
                runOnUiThread {
                    resultTextView.text = "Tarih/desen uyumu dusuk, tarih-saati kontrol et"
                    setProcessingState(false)
                }
                return
            }

            val (baseCandidatePoints, referenceConfidence, modeName) = if (hemisphereMode == Hemisphere.NORTH) {
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
                (
                    0.37f * referenceConfidence +
                        0.25f * patternResult.confidence +
                        0.20f * catalogConfidence +
                        0.18f * plateSolveConfidence
                    )
                    .coerceIn(0f, 1f)
            if (hemisphereMode == Hemisphere.NORTH) {
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
            if (hemisphereMode == Hemisphere.SOUTH && referenceConfidence < 0.32f) {
                runOnUiThread {
                    resultTextView.text = "Guney Haci geometri guveni dusuk, kalibrasyonu kontrol et"
                    setProcessingState(false)
                }
                return
            }
            val minCombinedConfidence = if (hemisphereMode == Hemisphere.NORTH) 0.28f else 0.22f
            if (combinedConfidence < minCombinedConfidence) {
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
            if (nearPole && (referenceConfidence < 0.55f || catalogConfidence < 0.45f || plateSolveConfidence < 0.35f)) {
                runOnUiThread {
                    resultTextView.text = "Kutup sonucu guvensiz, kalibrasyon/tarih hatali olabilir"
                    setProcessingState(false)
                }
                return
            }

            runOnUiThread {
                val hemisphereLabel = if (result.latitude >= 0f) "K" else "G"
                val sourceLabel = if (source == CaptureSource.CAMERA) "KAMERA" else "GALERI"
                val constellation = constellationLabel(
                    patternName = patternResult.matchedPattern,
                    mode = hemisphereMode,
                    referenceConfidence = referenceConfidence,
                    patternConfidence = patternResult.confidence
                )
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
                    "Kaynak: %s | %s | Takimyildiz: %s | Desen: %s | Tarih: %s | Katalog %.2f | Plate %.2f | Gun %d | Yildiz %d",
                    sourceLabel,
                    calibrationNote,
                    constellation,
                    patternResult.matchedPattern,
                    dateLabel,
                    catalogConfidence,
                    plateSolveConfidence,
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
        vFov: Float
    ): OrientationCalibration {
        val manualPitch = parseFloatOrNull(manualPitchInput.text?.toString().orEmpty())
        val manualRoll = parseFloatOrNull(manualRollInput.text?.toString().orEmpty())
        val manualAzimuth = parseFloatOrNull(manualAzimuthInput.text?.toString().orEmpty())?.let { normalize360(it) }
        val horizonPercent = parseFloatOrNull(manualHorizonPercentInput.text?.toString().orEmpty())

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
        dateModeToday.isEnabled = !processing
        dateModeManual.isEnabled = !processing
        manualDateInput.isEnabled = !processing && dateModeManual.isChecked
        manualTimeInput.isEnabled = !processing && dateModeManual.isChecked
        manualRollInput.isEnabled = !processing
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



