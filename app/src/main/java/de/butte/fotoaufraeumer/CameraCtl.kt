@file:Suppress("UnsafeOptInUsageError", "DEPRECATION")

package de.butte.fotoaufraeumer

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

suspend fun <T> ListenableFuture<T>.await(ctx: Context): T = suspendCancellableCoroutine { c ->
    addListener({
        try { c.resume(get()) } catch (e: Throwable) { c.resumeWithException(e.cause ?: e) }
    }, ContextCompat.getMainExecutor(ctx))
}

/** Was die Kamera dieses Handys kann. */
data class Caps(
    val manualSensor: Boolean = false,
    val isoRange: Range<Int> = Range(100, 1600),
    val expRange: Range<Long> = Range(100_000L, 100_000_000L),
    val awbModes: Set<Int> = emptySet(),
    val afModes: Set<Int> = emptySet(),
    val sceneModes: Set<Int> = emptySet(),
    val nrModes: Set<Int> = emptySet(),
    val edgeModes: Set<Int> = emptySet(),
    val faceMode: Int = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
    val fpsRanges: List<Range<Int>> = emptyList(),
    val active: Rect = Rect(0, 0, 4000, 3000),
    val minFocus: Float = 0f,
    val maxAe: Int = 0,
    val maxAf: Int = 0,
    val antiband: Set<Int> = emptySet(),
    val flash: Boolean = false,
    val vendor: Set<Vendor> = emptySet(),
) {
    fun summary(): String = buildString {
        append(if (manualSensor) "Manuelle Belichtung ✓" else "Nur Automatik")
        append(" · ISO ${isoRange.lower}–${isoRange.upper}")
        append(" · bis ${expStr(expRange.upper / 1e9)}")
        if (vendor.isNotEmpty()) append(" · Hersteller: " + vendor.joinToString { vendorName(it) })
    }
}

fun vendorName(v: Vendor) = when (v) {
    Vendor.NIGHT -> "Nacht"; Vendor.HDR -> "HDR"; Vendor.BOKEH -> "Porträt"; Vendor.FACE_RETOUCH -> "Retusche"; Vendor.NONE -> ""
}

fun expStr(s: Double): String = when {
    s <= 0 -> "–"
    s >= 0.95 -> "${"%.1f".format(s).replace(".0", "")} s"
    else -> "1/${(1 / s).roundToInt()} s"
}

/** Steuert CameraX + Camera2: Vorschau, Szenen-Erkennung, Einstellungen, Aufnahme. */
class CamCtl(private val ctx: Context) {
    private val main = ContextCompat.getMainExecutor(ctx)
    private val worker = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var ext: ExtensionsManager? = null
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var pv: PreviewView? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null

    // ---------- für die Oberfläche ----------
    var ready by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var front by mutableStateOf(false)
    var detected by mutableStateOf(Scene.STANDARD)
    var reason by mutableStateOf("")
    var manual by mutableStateOf<Scene?>(null)
    val active: Scene get() = manual ?: detected
    var info by mutableStateOf("")
    var caps by mutableStateOf(Caps())
    var busy by mutableStateOf<String?>(null)
    var lastUri by mutableStateOf<Uri?>(null)
    var useVendor by mutableStateOf(true)
    var flashMode by mutableStateOf(-1)     // -1 = wie Szene, sonst ImageCapture.FLASH_MODE_*
    var shakeUi by mutableFloatStateOf(0f)
    var stillUi by mutableStateOf(false)
    var roll by mutableFloatStateOf(0f)
    var zoomUi by mutableFloatStateOf(1f)

    // ---------- Sensoren (vom Bildschirm gesetzt) ----------
    @Volatile var shake = 0f
    @Volatile var stillSince = 0L
    val still: Boolean get() = stillSince > 0 && System.currentTimeMillis() - stillSince > 1200

    // ---------- letzte Kamera-Werte ----------
    @Volatile private var lastIso = 0
    @Volatile private var lastExp = 0L
    @Volatile private var lastAperture = 1.8f
    @Volatile private var lastFocus = 0f
    @Volatile private var lastFaces: Array<android.hardware.camera2.params.Face> = emptyArray()
    @Volatile private var manualActive = false
    private var lastStats = FrameStats()
    private var candidate: Scene? = null
    private var candidateCount = 0
    private var lastTick = 0L
    private var appliedScene: Scene? = null
    private var appliedEvIdx = Int.MIN_VALUE
    private var regionKey = ""
    private var baseMap: Map<CaptureRequest.Key<*>, Any> = emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun opts(m: Map<CaptureRequest.Key<*>, Any>): CaptureRequestOptions {
        val b = CaptureRequestOptions.Builder()
        for ((k, v) in m) b.setCaptureRequestOption(k as CaptureRequest.Key<Any>, v)
        return b.build()
    }

    private fun sendBase() {
        val cam = camera ?: return
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts(baseMap))
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            if (manualActive) return
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExp = it }
            res.get(CaptureResult.LENS_APERTURE)?.let { lastAperture = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            lastFaces = res.get(CaptureResult.STATISTICS_FACES) ?: emptyArray()
        }
    }

    private val selector: CameraSelector
        get() = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    suspend fun start(owner: LifecycleOwner, pv: PreviewView) {
        this.owner = owner
        this.pv = pv
        try {
            val p = ProcessCameraProvider.getInstance(ctx).await(ctx)
            provider = p
            ext = try { ExtensionsManager.getInstanceAsync(ctx, p).await(ctx) } catch (e: Throwable) { null }
            bindNormal()
            ready = true
        } catch (e: Throwable) {
            error = "Kamera konnte nicht gestartet werden: ${e.message}"
        }
    }

    fun stop() {
        try { provider?.unbindAll() } catch (_: Throwable) {}
        camera = null
        ready = false
    }

    fun flip() {
        front = !front
        manual = null
        appliedScene = null
        try { bindNormal() } catch (e: Throwable) { error = e.message }
    }

    private fun bindNormal() {
        val p = provider ?: return
        val o = owner ?: return
        val pb = Preview.Builder()
        Camera2Interop.Extender(pb).setSessionCaptureCallback(callback)
        val pre = pb.build()
        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val an = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        an.setAnalyzer(worker) { img -> analyze(img) }
        p.unbindAll()
        val cam = p.bindToLifecycle(o, selector, pre, cap, an)
        pre.setSurfaceProvider(pv?.surfaceProvider)
        preview = pre; capture = cap; analysis = an; camera = cam
        caps = readCaps(cam)
        appliedScene = null
        appliedEvIdx = Int.MIN_VALUE
        applyScene(force = true)
    }

    private fun readCaps(cam: Camera): Caps {
        val ci = Camera2CameraInfo.from(cam.cameraInfo)
        fun <T> c(k: CameraCharacteristics.Key<T>): T? = try { ci.getCameraCharacteristic(k) } catch (e: Throwable) { null }
        val capsArr = c(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val faces = c(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0)
        val faceMode = when {
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in faces -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in faces -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
            else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        }
        val vendor = HashSet<Vendor>()
        ext?.let { e ->
            val m = mapOf(Vendor.NIGHT to ExtensionMode.NIGHT, Vendor.HDR to ExtensionMode.HDR,
                Vendor.BOKEH to ExtensionMode.BOKEH, Vendor.FACE_RETOUCH to ExtensionMode.FACE_RETOUCH)
            for ((v, mode) in m) if (try { e.isExtensionAvailable(selector, mode) } catch (t: Throwable) { false }) vendor += v
        }
        return Caps(
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsArr,
            isoRange = c(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600),
            expRange = c(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L),
            awbModes = (c(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)).toSet(),
            afModes = (c(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)).toSet(),
            sceneModes = (c(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)).toSet(),
            nrModes = (c(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)).toSet(),
            edgeModes = (c(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)).toSet(),
            faceMode = faceMode,
            fpsRanges = (c(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()).toList(),
            active = c(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000),
            minFocus = c(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            maxAe = c(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            maxAf = c(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            antiband = (c(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: IntArray(0)).toSet(),
            flash = c(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            vendor = vendor,
        )
    }

    // ---------- Szenen-Erkennung ----------

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastTick < 250 || busy != null) return
            lastTick = now
            val pl = img.planes[0]
            val st = SceneDetector.stats(pl.buffer, img.width, img.height, pl.rowStride, img.imageInfo.rotationDegrees)
            lastStats = st
            val meta = currentMeta()
            val (sc, why) = SceneDetector.classify(st, meta, still, shake, front)
            // Hysterese: erst nach 3 gleichen Ergebnissen umschalten
            if (sc == candidate) candidateCount++ else { candidate = sc; candidateCount = 1 }
            main.execute {
                if (candidateCount >= 3 && sc != detected) { detected = sc; reason = why }
                if (sc == detected) reason = why
                shakeUi = shake
                stillUi = still
                info = buildString {
                    append("ISO ${meta.iso} · ${expStr(meta.exposure)}")
                    val ev = evIndexFor(active) * evStep()
                    if (abs(ev) > 0.05f) append(" · EV ${if (ev > 0) "+" else ""}${"%.1f".format(ev)}")
                    if (meta.faceCount > 0) append(" · ${meta.faceCount} Gesicht${if (meta.faceCount > 1) "er" else ""}")
                    if (meta.focusDiopter > 0.5f) append(" · ${(100 / meta.focusDiopter).roundToInt()} cm")
                }
                applyScene()
            }
        } catch (_: Throwable) {
        } finally {
            img.close()
        }
    }

    private fun currentMeta(): CamMeta {
        val a = caps.active
        val area = a.width().toFloat() * a.height()
        val faces = lastFaces.filter { it.score >= 30 }
        return CamMeta(
            iso = lastIso,
            exposure = lastExp / 1e9,
            aperture = lastAperture,
            focusDiopter = lastFocus,
            faceCount = faces.size,
            faceArea = if (area > 0) faces.sumOf { it.bounds.width().toDouble() * it.bounds.height() }.toFloat() / area else 0f,
            evComp = (camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0) * evStep(),
        )
    }

    private fun evStep(): Float = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f

    /** Belichtungskorrektur für eine Szene inkl. dynamischem Lichter-Schutz. */
    private fun evIndexFor(sc: Scene): Int {
        val es = camera?.cameraInfo?.exposureState ?: return 0
        if (!es.isExposureCompensationSupported) return 0
        val step = es.exposureCompensationStep.toFloat()
        if (step <= 0f) return 0
        var ev = sc.r.ev
        val st = lastStats
        val bright = sc in setOf(Scene.BACKLIGHT, Scene.BACKLIGHT_PORTRAIT, Scene.SNOW, Scene.FOG)
        if (!bright && st.clipHigh > 0.06f) ev -= 0.3f   // Lichter schützen
        if (!bright && st.clipLow > 0.35f && st.clipHigh < 0.01f && sc !in setOf(Scene.MOON, Scene.STARS, Scene.FIREWORKS, Scene.STAGE)) ev += 0.3f
        return (ev / step).roundToInt().coerceIn(es.exposureCompensationRange.lower, es.exposureCompensationRange.upper)
    }

    /** Einstellungen der aktiven Szene an die Kamera geben (nur bei Änderung). */
    fun applyScene(force: Boolean = false) {
        val cam = camera ?: return
        if (busy != null && !force) return
        val sc = active
        val r = sc.r
        val k = caps
        // Messbereiche
        val faces = lastFaces.filter { it.score >= 30 }
        val regions: Array<MeteringRectangle>? = when {
            r.faceMetering && faces.isNotEmpty() -> faces.take(max(1, k.maxAe)).map { MeteringRectangle(it.bounds, 1000) }.toTypedArray()
            r.centerMetering -> {
                val a = k.active
                val w = a.width() * 3 / 10
                val h = a.height() * 3 / 10
                arrayOf(MeteringRectangle(Rect(a.centerX() - w, a.centerY() - h, a.centerX() + w, a.centerY() + h), 1000))
            }
            else -> null
        }
        val rk = regions?.joinToString { "${it.x / 200},${it.y / 200},${it.width / 200}" } ?: ""
        val evIdx = evIndexFor(sc)
        if (!force && sc == appliedScene && rk == regionKey && evIdx == appliedEvIdx) return

        if (sc != appliedScene || force) {
            val m = LinkedHashMap<CaptureRequest.Key<*>, Any>()
            if (CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ in k.antiband)
                m[CaptureRequest.CONTROL_AE_ANTIBANDING_MODE] = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            if (r.awb in k.awbModes) m[CaptureRequest.CONTROL_AWB_MODE] = r.awb
            if (r.focusInfinity && k.minFocus > 0f) {
                m[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_OFF
                m[CaptureRequest.LENS_FOCUS_DISTANCE] = 0f
            } else if (r.af in k.afModes && r.af != CameraMetadata.CONTROL_AF_MODE_OFF) {
                m[CaptureRequest.CONTROL_AF_MODE] = r.af
            }
            if (r.nr in k.nrModes) m[CaptureRequest.NOISE_REDUCTION_MODE] = r.nr
            if (r.edge in k.edgeModes) m[CaptureRequest.EDGE_MODE] = r.edge
            if (k.faceMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
                m[CaptureRequest.STATISTICS_FACE_DETECT_MODE] = k.faceMode
            fpsFor(r)?.let { m[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = it }
            // Android-Szenenmodus nur, wenn keine manuelle Belichtung möglich ist
            if (!k.manualSensor && r.sceneMode >= 0 && r.sceneMode in k.sceneModes) {
                m[CaptureRequest.CONTROL_MODE] = CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
                m[CaptureRequest.CONTROL_SCENE_MODE] = r.sceneMode
            }
            if (regions != null) {
                if (k.maxAe > 0) m[CaptureRequest.CONTROL_AE_REGIONS] = regions
                if (k.maxAf > 0 && !r.focusInfinity) m[CaptureRequest.CONTROL_AF_REGIONS] = regions
            }
            baseMap = m
            sendBase()
            // Zoom
            val zs = cam.cameraInfo.zoomState.value
            val prevZoom = appliedScene?.r?.zoom ?: 0f
            if (r.zoom > 0f && zs != null) {
                val z = min(zs.maxZoomRatio, r.zoom)
                cam.cameraControl.setZoomRatio(z); zoomUi = z
            } else if (prevZoom > 0f) {
                cam.cameraControl.setZoomRatio(1f); zoomUi = 1f
            }
            capture?.flashMode = flashFor(r)
        } else if (rk != regionKey && regions != null) {
            // nur Gesichter/Messbereich nachführen
            val m = LinkedHashMap(baseMap)
            if (k.maxAe > 0) m[CaptureRequest.CONTROL_AE_REGIONS] = regions
            if (k.maxAf > 0 && !r.focusInfinity) m[CaptureRequest.CONTROL_AF_REGIONS] = regions
            baseMap = m
            sendBase()
        }
        if (evIdx != appliedEvIdx) cam.cameraControl.setExposureCompensationIndex(evIdx)
        appliedScene = sc
        regionKey = rk
        appliedEvIdx = evIdx
    }

    private fun fpsFor(r: Recipe): Range<Int>? {
        val rs = caps.fpsRanges.filter { it.upper <= 30 }
        if (rs.isEmpty()) return null
        return when {
            r.fps < 0 -> rs.minWith(compareBy<Range<Int>>({ it.lower }, { -it.upper }))
            r.fps > 0 -> rs.maxWith(compareBy<Range<Int>>({ it.lower }, { it.upper }))
            else -> null
        }
    }

    private fun flashFor(r: Recipe): Int = when {
        !caps.flash -> ImageCapture.FLASH_MODE_OFF
        flashMode >= 0 -> flashMode
        r.flash == 1 -> ImageCapture.FLASH_MODE_AUTO
        else -> ImageCapture.FLASH_MODE_OFF
    }

    fun setScene(s: Scene?) {
        manual = s
        applyScene(force = true)
    }

    fun setFlash(m: Int) {
        flashMode = m
        capture?.flashMode = flashFor(active.r)
    }

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val f = pv?.meteringPointFactory ?: return
        val action = FocusMeteringAction.Builder(f.createPoint(x, y)).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun zoomBy(f: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val z = (zs.zoomRatio * f).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        cam.cameraControl.setZoomRatio(z)
        zoomUi = z
    }

    fun setZoom(z: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val v = z.coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
        cam.cameraControl.setZoomRatio(v)
        zoomUi = v
    }

    // ---------- Belichtungsplan ----------

    /**
     * Manuelle Belichtung (Zeit in ns, ISO) aus der aktuellen Messung.
     * Gleiche Bildhelligkeit, aber Zeit/ISO nach Szene verteilt. null = Automatik lassen.
     */
    private fun plan(sc: Scene, evShift: Float = 0f, force: Boolean = false): Pair<Long, Int>? {
        val k = caps
        if (!k.manualSensor || lastIso <= 0 || lastExp <= 0) return null
        val r = sc.r
        val isoMin = k.isoRange.lower
        val isoMax = max(isoMin, min(k.isoRange.upper, r.isoMax))
        val tLo = k.expRange.lower / 1e9
        val tHi = k.expRange.upper / 1e9
        val t0 = lastExp / 1e9
        val e = t0 * lastIso * 2.0.pow(evShift.toDouble()) // Belichtungsmenge (inkl. EV-Korrektur)
        var t: Double
        var iso: Double
        if (sc == Scene.MOON) {
            iso = r.isoPref.coerceIn(isoMin, isoMax).toDouble()
            val n = lastAperture.toDouble()
            t = n * n / 2.0.pow(14.5) * (100.0 / iso) * 2.0.pow(evShift.toDouble())
        } else {
            val tMaxEff = if (still) r.tMaxStill else r.tMax
            val isoTarget = (if (r.isoPref > 0) r.isoPref else isoMin).coerceIn(isoMin, isoMax).toDouble()
            t = if (r.tFix > 0) min(r.tFix, if (still) max(r.tFix, tMaxEff) else tMaxEff)
            else min(e / isoTarget, tMaxEff)
            // Wackeln: Zeit zusätzlich begrenzen
            if (!still && shake > 0.1f && r.tFix <= 0) t = min(t, 1.0 / (60 * (1 + shake * 6)))
            iso = e / t
        }
        t = t.coerceIn(tLo, tHi)
        iso = iso.coerceIn(isoMin.toDouble(), isoMax.toDouble())
        // Wenn ISO am Anschlag: Zeit verlängern, außer bei Action/Mond (dann lieber dunkler)
        if (sc.r.tMin <= 0 && sc != Scene.MOON && t * iso < e * 0.7) {
            val tMaxEff = if (still) sc.r.tMaxStill else sc.r.tMax
            t = min(e / iso, max(t, tMaxEff)).coerceIn(tLo, tHi)
        }
        val changed = abs(ln(t / t0)) > 0.25 || abs(ln(iso / lastIso)) > 0.25
        if (!force && !changed && sc != Scene.MOON && r.tFix <= 0) return null
        return (t * 1e9).toLong() to iso.roundToInt()
    }

    private suspend fun setManual(p: Pair<Long, Int>) {
        val cam = camera ?: return
        val m = LinkedHashMap(baseMap)
        m.remove(CaptureRequest.CONTROL_SCENE_MODE)
        m.remove(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        m[CaptureRequest.CONTROL_MODE] = CameraMetadata.CONTROL_MODE_AUTO
        m[CaptureRequest.CONTROL_AE_MODE] = CameraMetadata.CONTROL_AE_MODE_OFF
        m[CaptureRequest.SENSOR_EXPOSURE_TIME] = p.first
        m[CaptureRequest.SENSOR_SENSITIVITY] = p.second
        m[CaptureRequest.SENSOR_FRAME_DURATION] = max(p.first, 33_333_333L)
        manualActive = true
        try { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts(m)).await(ctx) } catch (_: Throwable) {}
        delay(min(2500L, p.first / 1_000_000 * 3 + 250))
    }

    private suspend fun clearManual() {
        val cam = camera ?: return
        manualActive = false
        try { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(opts(baseMap)).await(ctx) } catch (_: Throwable) {}
    }

    private suspend fun setEv(stops: Float) {
        val cam = camera ?: return
        val es = cam.cameraInfo.exposureState
        if (!es.isExposureCompensationSupported) return
        val idx = ((evIndexFor(active) * evStep() + stops) / es.exposureCompensationStep.toFloat()).roundToInt()
            .coerceIn(es.exposureCompensationRange.lower, es.exposureCompensationRange.upper)
        try { cam.cameraControl.setExposureCompensationIndex(idx).await(ctx) } catch (_: Throwable) {}
        delay(600)
    }

    // ---------- Aufnahme ----------

    private suspend fun takeJpeg(): Jpeg = suspendCancellableCoroutine { c ->
        val cap = capture ?: run { c.resumeWithException(IllegalStateException("Keine Kamera")); return@suspendCancellableCoroutine }
        cap.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    c.resume(Jpeg(bytes, image.imageInfo.rotationDegrees))
                } catch (e: Throwable) {
                    c.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
        })
    }

    private suspend fun takeToStore(sc: Scene): Uri? = suspendCancellableCoroutine { c ->
        val cap = capture ?: run { c.resume(null); return@suspendCancellableCoroutine }
        val opts = ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, CamProcess.contentValues(CamProcess.newName(sc))
        ).build()
        cap.takePicture(opts, worker, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(o: ImageCapture.OutputFileResults) { c.resume(o.savedUri) }
            override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
        })
    }

    private fun vendorMode(v: Vendor): Int? = when (v) {
        Vendor.NIGHT -> ExtensionMode.NIGHT
        Vendor.HDR -> ExtensionMode.HDR
        Vendor.BOKEH -> ExtensionMode.BOKEH
        Vendor.FACE_RETOUCH -> ExtensionMode.FACE_RETOUCH
        Vendor.NONE -> null
    }

    private suspend fun shootVendor(sc: Scene, mode: Int): Uri? {
        val p = provider ?: return null
        val o = owner ?: return null
        val e = ext ?: return null
        val sel = e.getExtensionEnabledCameraSelector(selector, mode)
        p.unbindAll()
        val pre = preview ?: return null
        val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        p.bindToLifecycle(o, sel, pre, cap)
        capture = cap
        cap.flashMode = flashFor(sc.r)
        delay(900)
        return try { takeToStore(sc) } finally { bindNormal() }
    }

    /** Wartet (bis 2,5 s), bis das Handy ruhig ist. */
    private suspend fun waitForStill() {
        val end = System.currentTimeMillis() + 2500
        while (shake > 0.06f && System.currentTimeMillis() < end) delay(50)
    }

    /** Ein Foto nach dem Rezept der aktiven Szene aufnehmen. */
    suspend fun shoot(): Uri? {
        if (busy != null || camera == null) return null
        val sc = active
        val r = sc.r
        busy = "${sc.emoji} ${sc.label} …"
        var uri: Uri? = null
        try {
            if (r.waitStill && shake > 0.08f) {
                busy = "Halte still …"
                waitForStill()
            }
            val vm = vendorMode(r.vendor)
            val vendorOk = useVendor && r.vendor in caps.vendor && !(sc == Scene.NIGHT_TRIPOD && caps.manualSensor)
            if (vm != null && vendorOk) {
                busy = "${sc.emoji} Hersteller-${vendorName(r.vendor)} …"
                uri = shootVendor(sc, vm)
            } else when (r.shot) {
                Shot.SINGLE -> {
                    val p = plan(sc, force = r.tFix > 0 || sc == Scene.MOON)
                    if (p != null) setManual(p)
                    try { uri = takeToStore(sc) } finally { if (p != null) clearManual() }
                }
                Shot.BURST -> {
                    val p = plan(sc)
                    if (p != null) setManual(p)
                    val frames = ArrayList<Jpeg>()
                    try {
                        for (i in 1..r.frames) {
                            busy = "Serie $i / ${r.frames} …"
                            frames += takeJpeg()
                        }
                    } finally { if (p != null) clearManual() }
                    busy = "Wähle das schärfste Bild …"
                    uri = withContext(Dispatchers.Default) { CamProcess.saveJpeg(ctx, CamProcess.sharpest(frames), sc) }
                }
                Shot.STACK -> {
                    val p = plan(sc, force = true)
                    if (p != null) setManual(p)
                    val frames = ArrayList<Jpeg>()
                    try {
                        for (i in 1..r.frames) {
                            busy = "Bild $i / ${r.frames} – ruhig halten …"
                            frames += takeJpeg()
                        }
                    } finally { if (p != null) clearManual() }
                    busy = "Kombiniere ${frames.size} Bilder …"
                    uri = withContext(Dispatchers.Default) {
                        val b = CamProcess.stack(frames) { busy = it }
                        val u = CamProcess.saveBitmap(ctx, b, sc)
                        b.recycle()
                        u
                    }
                }
                Shot.BRACKET -> {
                    val evs = listOf(-1.7f, 0f, 1.7f)
                    val frames = ArrayList<Jpeg>()
                    try {
                        for ((i, ev) in evs.withIndex()) {
                            busy = "Belichtungsreihe ${i + 1} / ${evs.size} …"
                            val p = plan(sc, evShift = ev, force = true)
                            if (p != null) setManual(p) else setEv(ev)
                            frames += takeJpeg()
                        }
                    } finally {
                        clearManual()
                        setEv(0f)
                    }
                    busy = "Erzeuge HDR …"
                    uri = withContext(Dispatchers.Default) {
                        val b = CamProcess.fuse(frames) { busy = it }
                        val u = CamProcess.saveBitmap(ctx, b, sc)
                        b.recycle()
                        u
                    }
                }
            }
        } catch (e: Throwable) {
            error = "Aufnahme fehlgeschlagen: ${e.message}"
        } finally {
            busy = null
            appliedEvIdx = Int.MIN_VALUE
            applyScene(force = true)
        }
        if (uri != null) lastUri = uri
        return uri
    }
}
