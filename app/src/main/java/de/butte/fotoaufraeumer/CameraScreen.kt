@file:Suppress("DEPRECATION")

package de.butte.fotoaufraeumer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Chip = Color(0xAA000000)
private val Accent = Color(0xFFFFC107)

/** Szenen, bei denen automatisch 2 s Selbstauslöser gesetzt wird (kein Verwackeln beim Antippen). */
private val autoTimerScenes = setOf(Scene.NIGHT_TRIPOD, Scene.STARS, Scene.LIGHT_TRAILS, Scene.FIREWORKS, Scene.MOON)

@Composable
fun CameraScreen(vm: AppVM) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("📸", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text("Der Kamera-Assistent braucht Zugriff auf die Kamera.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Kamera erlauben") }
            TextButton(onClick = { vm.back() }) { Text("Zurück") }
        }
        return
    }
    CameraContent(vm)
}

@Composable
private fun CameraContent(vm: AppVM) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val ctl = remember { CamCtl(ctx.applicationContext) }
    var pv by remember { mutableStateOf<PreviewView?>(null) }
    var grid by remember { mutableStateOf(true) }
    var timer by remember { mutableIntStateOf(0) }       // 0, 3, 10 s
    var countdown by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(pv) { pv?.let { ctl.start(owner, it) } }
    DisposableEffect(Unit) { onDispose { ctl.stop() } }
    SensorsEffect(ctl)
    LaunchedEffect(ctl.error) {
        ctl.error?.let { vm.message = it; ctl.error = null }
    }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(1200); focusPoint = null } }

    fun shoot() {
        if (ctl.busy != null || countdown > 0) return
        scope.launch {
            val t = if (timer > 0) timer else if (ctl.active in autoTimerScenes) 2 else 0
            for (i in t downTo 1) { countdown = i; delay(1000) }
            countdown = 0
            val sc = ctl.active
            val uri = ctl.shoot()
            if (uri != null) {
                vm.message = "${sc.emoji} Gespeichert (${sc.label})"
                vm.reload()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PreviewView(c).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }.also { pv = it }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gesten: Tippen = Fokus, zwei Finger = Zoom
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o -> ctl.focusAt(o.x, o.y); focusPoint = o }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) ctl.zoomBy(zoom) }
                }
        )
        // Raster + Wasserwaage + Fokuspunkt
        Canvas(Modifier.fillMaxSize()) {
            if (grid) {
                val c = Color(0x55FFFFFF)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                }
            }
            val r = ctl.roll
            val level = abs(r) < 1f
            val len = size.width * 0.18f
            val cx = size.width / 2
            val cy = size.height / 2
            val rad = Math.toRadians(r.toDouble())
            val dx = (len * kotlin.math.cos(rad)).toFloat()
            val dy = (len * kotlin.math.sin(rad)).toFloat()
            drawLine(if (level) Color(0xFF4CAF50) else Color.White, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), if (level) 5f else 3f)
            drawLine(Color(0x88FFFFFF), Offset(cx - len * 1.3f, cy), Offset(cx - len * 1.05f, cy), 2f)
            drawLine(Color(0x88FFFFFF), Offset(cx + len * 1.05f, cy), Offset(cx + len * 1.3f, cy), 2f)
            focusPoint?.let { drawCircle(Accent, 60f, it, style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) }
        }

        // ---------- oben ----------
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallBtn("←") { vm.back() }
                Spacer(Modifier.width(6.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Chip)
                        .clickable { showInfo = !showInfo }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    val sc = ctl.active
                    Text(
                        "${sc.emoji} ${sc.label}" + if (ctl.manual == null) "  · Auto" else "  · fest",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    )
                    Text(
                        if (ctl.manual == null) ctl.reason else sc.hint,
                        color = Color(0xFFDDDDDD), fontSize = 11.sp, maxLines = 1,
                    )
                    Text(ctl.info, color = Accent, fontSize = 11.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallBtn(if (timer == 0) "⏱ aus" else "⏱ ${timer}s") { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
                if (ctl.caps.flash) {
                    SmallBtn(
                        when (ctl.flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> "⚡ aus"
                            ImageCapture.FLASH_MODE_ON -> "⚡ an"
                            ImageCapture.FLASH_MODE_AUTO -> "⚡ auto"
                            else -> "⚡ Szene"
                        }
                    ) {
                        ctl.setFlash(
                            when (ctl.flashMode) {
                                -1 -> ImageCapture.FLASH_MODE_AUTO
                                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                                else -> -1
                            }
                        )
                    }
                }
                SmallBtn(if (grid) "▦ an" else "▦ aus") { grid = !grid }
                if (ctl.caps.vendor.isNotEmpty()) {
                    SmallBtn(if (ctl.useVendor) "🏭 an" else "🏭 aus") { ctl.useVendor = !ctl.useVendor }
                }
            }
            if (showInfo) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${ctl.active.hint}\n\nHandy: ${ctl.caps.summary()}\n🏭 = Nacht/HDR/Porträt-Modus des Herstellers nutzen, wenn verfügbar.\nTippen = Fokus & Belichtung, zwei Finger = Zoom.",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Chip).padding(10.dp),
                )
            }
            val longExp = ctl.active.r.tMaxStill >= 0.25 || ctl.active.r.shot == Shot.STACK
            if (ctl.shakeUi > 0.12f && (longExp || ctl.active.r.waitStill)) {
                Spacer(Modifier.height(6.dp))
                Hint("✋ Ruhig halten – bei Ruhe wird automatisch ausgelöst")
            } else if (ctl.stillUi && longExp) {
                Spacer(Modifier.height(6.dp))
                Hint("✓ Handy liegt ruhig – lange Belichtung möglich")
            }
        }

        // ---------- Mitte: Countdown / Arbeitet ----------
        if (countdown > 0) {
            Text(
                "$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ctl.busy?.let { b ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(10.dp))
                Text(b, color = Color.White, textAlign = TextAlign.Center)
            }
        }

        // ---------- unten ----------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(bottom = 8.dp)
        ) {
            // Zoom-Stufen
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                for (z in listOf(0.6f, 1f, 2f, 5f, 10f)) {
                    val sel = abs(ctl.zoomUi - z) < 0.15f
                    Text(
                        if (z < 1f) ".6" else "${z.roundToInt()}×",
                        color = if (sel) Accent else Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 4.dp).clip(CircleShape).background(Chip).clickable { ctl.setZoom(z) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    SceneChip("🤖 Auto", ctl.manual == null) { ctl.setScene(null) }
                }
                items(Scene.entries.toList(), key = { it.name }) { s ->
                    val isAutoPick = ctl.manual == null && ctl.detected == s
                    SceneChip("${s.emoji} ${s.label}", ctl.manual == s, highlight = isAutoPick) {
                        ctl.setScene(if (ctl.manual == s) null else s)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val last = ctl.lastUri
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray)
                        .clickable(enabled = last != null) {
                            val id = last?.let { try { ContentUris.parseId(it) } catch (e: Exception) { null } }
                            val p = vm.photos.firstOrNull { it.id == id }
                            if (p != null) vm.go(Screen.Enhance(p)) else vm.message = "Foto wird noch geladen …"
                        }
                ) {
                    if (last != null) AsyncImage(last, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(if (ctl.busy != null || countdown > 0) Color.Gray else Color.White)
                        .clickable(enabled = ctl.ready) { shoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ctl.active.emoji, fontSize = 26.sp)
                }
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Chip).clickable { ctl.flip() },
                    contentAlignment = Alignment.Center,
                ) { Text("🔄", fontSize = 24.sp) }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Chip).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Accent).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SceneChip(text: String, selected: Boolean, highlight: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 13.sp,
        fontWeight = if (selected || highlight) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else Chip)
            .then(if (highlight) Modifier.border(2.dp, Accent, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Gyroskop (Wackeln / Stativ) und Beschleunigung (Wasserwaage). */
@Composable
private fun SensorsEffect(ctl: CamCtl) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        var smooth = 0f
        var lastUi = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                        smooth = smooth * 0.85f + m * 0.15f
                        ctl.shake = smooth
                        val now = System.currentTimeMillis()
                        if (smooth < 0.025f) { if (ctl.stillSince == 0L) ctl.stillSince = now } else ctl.stillSince = 0L
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val now = System.currentTimeMillis()
                        if (now - lastUi < 80) return
                        lastUi = now
                        val x = e.values[0]
                        val y = e.values[1]
                        var deg = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                        // auf die nächste 90°-Lage beziehen (hoch/quer)
                        while (deg > 45f) deg -= 90f
                        while (deg < -45f) deg += 90f
                        ctl.roll = -deg
                        ctl.shakeUi = ctl.shake
                        ctl.stillUi = ctl.still
                    }
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
}
