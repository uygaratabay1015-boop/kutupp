package com.kutup.navigasyon

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

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

        private val POLAR_STATIONS = listOf(
            PolarStation("Longyearbyen", 78.2232, 15.6469),
            PolarStation("Ny-Alesund", 78.9236, 11.9233),
            PolarStation("Utqiagvik", 71.2906, -156.7886),
            PolarStation("Tiksi", 71.6366, 128.8718),
            PolarStation("Alert", 82.5018, -62.3481),
            PolarStation("McMurdo", -77.8419, 166.6863),
            PolarStation("Rothera", -67.5681, -68.1236),
            PolarStation("South Pole", -90.0, 0.0)
        )
    }

    private lateinit var previewView: PreviewView
    private lateinit var mapView: MapView
    private lateinit var captureButton: Button
    private lateinit var galleryButton: Button
    private lateinit var compassStatusTextView: TextView
    private lateinit var azimutuResultTextView: TextView
    private lateinit var latitudeResultTextView: TextView

    private lateinit var compass: CompassSensor
    private lateinit var starDetector: StarDetector
    private lateinit var polarisFinder: PolarisFinder
    private lateinit var southernCrossFinder: SouthernCrossFinder
    private lateinit var latitudeSolver: LatitudeSolver

    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var cachedUserLocation: Location? = null
    private var nearestStationName: String = "-"
    private var nearestStationDistanceKm: Float = 0f
    private var nearestStationBearing: Float = 0f

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
        initializeModules()

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (allPermissionsGranted()) {
            startCamera()
            requestFreshLocationUpdates()
            requestImmediateLocation()
            updateLocationAndStationInfo()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        compass.startListening()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        compass.stopListening()
        stopLocationUpdates()
        cameraExecutor.shutdown()
    }

    private fun initializeUI() {
        previewView = findViewById(R.id.previewView)
        mapView = findViewById(R.id.mapView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        compassStatusTextView = findViewById(R.id.compassStatus)
        azimutuResultTextView = findViewById(R.id.azimutuResult)
        latitudeResultTextView = findViewById(R.id.latitudeResult)

        captureButton.isEnabled = false
        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener { pickImageLauncher.launch("image/*") }

        cameraExecutor = Executors.newSingleThreadExecutor()

        initMap(mapView)
        mapView.setOnClickListener { openFullScreenMap() }
    }

    private fun initMap(target: MapView) {
        Configuration.getInstance().userAgentValue = packageName
        target.setTileSource(TileSourceFactory.MAPNIK)
        target.setMultiTouchControls(true)
        target.controller.setZoom(2.5)
        target.controller.setCenter(GeoPoint(20.0, 0.0))
    }

    private fun openFullScreenMap() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val fullMap = MapView(this)
        initMap(fullMap)
        updateMapMarkers(fullMap, cachedUserLocation)
        fullMap.controller.setZoom(3.2)
        dialog.setContentView(fullMap)
        fullMap.setOnLongClickListener {
            dialog.dismiss()
            true
        }
        dialog.show()
        Toast.makeText(this, "Kapatmak icin uzun bas", Toast.LENGTH_SHORT).show()
    }

    private fun initializeModules() {
        compass = CompassSensor(this)
        starDetector = StarDetector()
        polarisFinder = PolarisFinder()
        southernCrossFinder = SouthernCrossFinder()
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
                requestFreshLocationUpdates()
                requestImmediateLocation()
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
                    latitudeResultTextView.text = "Yildiz bulunamadi"
                    setProcessingState(false)
                }
                return
            }

            val loc = cachedUserLocation ?: findBestRecentLocation()
            val isSouth = (loc?.latitude ?: 0.0) < 0.0

            val (refStar, confidence, modeName) = if (isSouth) {
                val (scp, c) = southernCrossFinder.findSouthCelestialPole(stars, bitmap.height, bitmap.width)
                Triple(scp, c, "Guney Haci")
            } else {
                val (polaris, c) = polarisFinder.findPolaris(stars, bitmap.height, bitmap.width)
                Triple(polaris, c, "Polaris")
            }

            var result = latitudeSolver.calculateLatitude(refStar.y, bitmap.height, verticalFov)

            if (isSouth) {
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
                latitudeResultTextView.text = String.format(
                    Locale.US,
                    "%s | Enlem %.4f | Hata +/-%.2f | Guven %.2f",
                    modeName,
                    result.latitude,
                    result.errorMargin,
                    confidence
                )
                setProcessingState(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap isleme hatasi", e)
            runOnUiThread {
                latitudeResultTextView.text = "Isleme hatasi"
                setProcessingState(false)
            }
        }
    }

    private fun requestFreshLocationUpdates() {
        if (!allPermissionsGranted()) return

        val listener = LocationListener { location ->
            val current = cachedUserLocation
            if (current == null || isBetterLocation(location, current)) {
                cachedUserLocation = location
                runOnUiThread { updateLocationAndStationInfo() }
            }
        }
        locationListener = listener

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, listener, mainLooper)
            }
        } catch (_: Exception) {
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, listener, mainLooper)
            }
        } catch (_: Exception) {
        }
    }

    private fun requestImmediateLocation() {
        if (!allPermissionsGranted()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER, CancellationSignal(), mainExecutor) { loc ->
                if (loc != null && (cachedUserLocation == null || isBetterLocation(loc, cachedUserLocation!!))) {
                    cachedUserLocation = loc
                    updateLocationAndStationInfo()
                }
            }
        } catch (_: Exception) {
        }

        try {
            locationManager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, CancellationSignal(), mainExecutor) { loc ->
                if (loc != null && (cachedUserLocation == null || isBetterLocation(loc, cachedUserLocation!!))) {
                    cachedUserLocation = loc
                    updateLocationAndStationInfo()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopLocationUpdates() {
        val listener = locationListener ?: return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
        }
        locationListener = null
    }

    private fun isBetterLocation(newLoc: Location, current: Location): Boolean {
        val timeDelta = newLoc.time - current.time
        val isSignificantlyNewer = timeDelta > 120_000
        val isSignificantlyOlder = timeDelta < -120_000
        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val accuracyDelta = newLoc.accuracy - current.accuracy
        val isMoreAccurate = accuracyDelta < 0
        val isNewer = timeDelta > 0

        return isMoreAccurate || (isNewer && accuracyDelta <= 10f)
    }

    private fun findBestRecentLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        val now = System.currentTimeMillis()

        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (now - loc.time > 15 * 60 * 1000) continue
                if (best == null || isBetterLocation(loc, best)) {
                    best = loc
                }
            } catch (_: Exception) {
            }
        }
        return best
    }

    private fun updateLocationAndStationInfo() {
        if (!allPermissionsGranted()) {
            azimutuResultTextView.text = "Konum izni yok"
            return
        }

        val user = cachedUserLocation ?: findBestRecentLocation()
        if (user == null) {
            azimutuResultTextView.text = "Konum bekleniyor"
            return
        }
        cachedUserLocation = user

        var nearestName = "-"
        var nearestDistance = Float.MAX_VALUE
        var nearestBearing = 0f

        val userLoc = Location("user").apply {
            latitude = user.latitude
            longitude = user.longitude
        }

        for (station in POLAR_STATIONS) {
            val stationLoc = Location("station").apply {
                latitude = station.latitude
                longitude = station.longitude
            }
            val dist = userLoc.distanceTo(stationLoc)
            if (dist < nearestDistance) {
                nearestDistance = dist
                nearestName = station.name
                nearestBearing = userLoc.bearingTo(stationLoc)
            }
        }

        nearestStationName = nearestName
        nearestStationDistanceKm = nearestDistance / 1000f
        nearestStationBearing = normalizeBearing(nearestBearing)

        val dir = bearingToDirection(nearestStationBearing)
        val acc = if (user.hasAccuracy()) String.format(Locale.US, "%.0fm", user.accuracy) else "?"

        azimutuResultTextView.text = String.format(
            Locale.US,
            "Konum: %.5f, %.5f (%s)\nEn yakin: %s | %.1f km | %s",
            user.latitude,
            user.longitude,
            acc,
            nearestStationName,
            nearestStationDistanceKm,
            dir
        )

        updateMapMarkers(mapView, user)
    }

    private fun updateMapMarkers(targetMap: MapView, user: Location?) {
        targetMap.overlays.removeAll { it is Marker }

        for (station in POLAR_STATIONS) {
            val marker = Marker(targetMap)
            marker.position = GeoPoint(station.latitude, station.longitude)
            marker.title = station.name
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = ContextCompat.getDrawable(
                this,
                if (station.name == nearestStationName) android.R.drawable.presence_away else android.R.drawable.presence_online
            )
            targetMap.overlays.add(marker)
        }

        if (user != null) {
            val me = Marker(targetMap)
            me.position = GeoPoint(user.latitude, user.longitude)
            me.title = "Siz"
            me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            me.icon = ContextCompat.getDrawable(this, android.R.drawable.presence_busy)
            targetMap.overlays.add(me)
            targetMap.controller.setCenter(GeoPoint(user.latitude, user.longitude))
            targetMap.controller.setZoom(4.0)
        }

        targetMap.invalidate()
    }

    private fun setProcessingState(isProcessing: Boolean) {
        captureButton.isEnabled = !isProcessing && imageCapture != null
        galleryButton.isEnabled = !isProcessing
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

    private fun updateCompassDisplay(azimuth: Float) {
        runOnUiThread {
            val direction = compass.getCardinalDirection()
            compassStatusTextView.text = String.format(Locale.US, "Pusula: %s %.0f°", direction, azimuth)
        }
    }

    private fun normalizeBearing(bearing: Float): Float {
        var b = bearing % 360f
        if (b < 0f) b += 360f
        return b
    }

    private fun bearingToDirection(bearing: Float): String {
        val dirs = listOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")
        val idx = (((bearing + 22.5f) / 45f).toInt()) % 8
        return dirs[idx]
    }
}
