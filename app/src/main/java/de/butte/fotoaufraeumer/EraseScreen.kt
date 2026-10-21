@file:OptIn(ExperimentalMaterial3Api::class)

package de.butte.fotoaufraeumer

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private const val MASK_COLOR = 0xFFFF1744.toInt()

/** Maske (rote Pixel im Overlay) → true = entfernen. */
fun overlayToMask(ov: Bitmap): BooleanArray {
    val px = IntArray(ov.width * ov.height)
    ov.getPixels(px, 0, ov.width, 0, 0, ov.width, ov.height)
    return BooleanArray(px.size) { (px[it] ushr 24) > 40 }
}

@Composable
fun EraseScreen(vm: AppVM, photo: Photo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var original by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var overlay by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var result by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var erasing by remember { mutableStateOf(false) }   // Radierer statt Pinsel
    var brush by remember { mutableFloatStateOf(18f) }   // dp
    var busy by remember { mutableStateOf<String?>(null) }
    var failed by remember(photo.id) { mutableStateOf(false) }
    val undo = remember(photo.id) { ArrayDeque<Bitmap>() }

    fun pushUndo() {
        overlay?.let { undo.addLast(it.copy(Bitmap.Config.ARGB_8888, true)) }
        while (undo.size > 15) undo.removeFirst()
    }

    fun autoDetect(bmp: Bitmap, ov: Bitmap) {
        scope.launch {
            busy = "Suche Schrift …"
            val lines = TextEraser.detectLines(bmp)
            val mask = withContext(Dispatchers.Default) { TextEraser.maskFromLines(bmp, lines) }
            pushUndo()
            val px = IntArray(ov.width * ov.height)
            ov.getPixels(px, 0, ov.width, 0, 0, ov.width, ov.height)
            for (i in px.indices) if (mask[i]) px[i] = MASK_COLOR
            ov.setPixels(px, 0, ov.width, 0, 0, ov.width, ov.height)
            result = null
            version++
            busy = null
            vm.message = if (lines.isEmpty()) "Keine Schrift erkannt – mit dem Pinsel markieren (beim ersten Mal lädt die Texterkennung evtl. noch, später „🔍 Erkennen“ erneut tippen)"
            else "${lines.size} Textzeile(n) markiert – prüfen und „Entfernen“ tippen"
        }
    }

    LaunchedEffect(photo.id) {
        val b = withContext(Dispatchers.IO) {
            try { Enhancer.decode(ctx, photo.uri, 1600) } catch (e: Throwable) { null }
        }
        if (b == null) { failed = true; return@LaunchedEffect }
        val ov = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        original = b
        overlay = ov
        autoDetect(b, ov)
    }

    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MASK_COLOR; style = Paint.Style.FILL }
    }
    val clear = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schrift entfernen") },
                navigationIcon = { BackButton { vm.back() } },
                actions = {
                    TextButton(
                        onClick = { overlay?.let { vm.saveErased(photo, it.copy(Bitmap.Config.ARGB_8888, false)) } },
                        enabled = result != null && !vm.saving,
                    ) { Text("Speichern") }
                },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                val o = original
                val ov = overlay
                if (o != null && ov != null) {
                    val res = result
                    if (res != null) {
                        BeforeAfter(o, res, Modifier.fillMaxSize())
                    } else {
                    val shown = o
                    val showMask = true
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(o) {
                                fun map(p: Offset): Pair<Float, Float>? {
                                    val s = min(size.width / o.width.toFloat(), size.height / o.height.toFloat())
                                    val ox = (size.width - o.width * s) / 2f
                                    val oy = (size.height - o.height * s) / 2f
                                    return ((p.x - ox) / s) to ((p.y - oy) / s)
                                }
                                fun radius(): Float {
                                    val s = min(size.width / o.width.toFloat(), size.height / o.height.toFloat())
                                    return with(density) { brush.dp.toPx() } / s
                                }
                                var last: Pair<Float, Float>? = null
                                detectDragGestures(
                                    onDragStart = { p ->
                                        if (result != null) { result = null }
                                        pushUndo()
                                        last = map(p)
                                        last?.let { (x, y) ->
                                            android.graphics.Canvas(ov).drawCircle(x, y, radius(), if (erasing) clear else paint)
                                        }
                                        version++
                                    },
                                    onDragEnd = { last = null },
                                ) { change, _ ->
                                    change.consume()
                                    val p = map(change.position) ?: return@detectDragGestures
                                    val c = android.graphics.Canvas(ov)
                                    val pr = if (erasing) clear else paint
                                    val l = last ?: p
                                    pr.strokeWidth = radius() * 2
                                    pr.strokeCap = Paint.Cap.ROUND
                                    pr.style = Paint.Style.STROKE
                                    c.drawLine(l.first, l.second, p.first, p.second, pr)
                                    pr.style = Paint.Style.FILL
                                    last = p
                                    version++
                                }
                            }
                    ) {
                        version // Neu zeichnen, wenn sich die Maske ändert
                        val s = min(size.width / o.width, size.height / o.height)
                        val dw = (o.width * s).roundToInt()
                        val dh = (o.height * s).roundToInt()
                        val off = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt())
                        drawImage(shown.asImageBitmap(), dstOffset = off, dstSize = IntSize(dw, dh))
                        if (showMask) drawImage(ov.asImageBitmap(), dstOffset = off, dstSize = IntSize(dw, dh), alpha = 0.55f)
                    }
                    }
                } else if (failed) {
                    Text("Bild konnte nicht geladen werden", color = Color.White, modifier = Modifier.align(Alignment.Center))
                }
                val b = busy ?: if (vm.saving) "Speichere in voller Größe …" else null
                if (b != null || (original == null && !failed)) {
                    Column(
                        Modifier.matchParentSize().background(Color(0xAA000000)),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(b ?: "Lade Bild …", color = Color.White)
                    }
                }
            }
            if (vm.savedFor == photo.id) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✓ Kopie gespeichert", Modifier.weight(1f))
                    TextButton(onClick = { vm.compareSaved(photo) }) { Text("Vergleichen") }
                    TextButton(onClick = { vm.deleteOriginal(photo) }) { Text("Original löschen") }
                }
            }
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (result == null) {
                    Text(
                        "Rot = wird entfernt. Mit dem Finger nachmalen oder mit dem Radierer korrigieren.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ergebnis – passt es? Dann „Speichern“.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { result = null }) { Text("✏ Weiter markieren") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !erasing, onClick = { erasing = false }, label = { Text("🖌 Pinsel") })
                    FilterChip(selected = erasing, onClick = { erasing = true }, label = { Text("🧽 Radierer") })
                    Slider(value = brush, onValueChange = { brush = it }, valueRange = 4f..50f, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            undo.removeLastOrNull()?.let { prev ->
                                overlay?.let { ov ->
                                    val px = IntArray(ov.width * ov.height)
                                    prev.getPixels(px, 0, ov.width, 0, 0, ov.width, ov.height)
                                    ov.setPixels(px, 0, ov.width, 0, 0, ov.width, ov.height)
                                }
                                result = null
                                version++
                            }
                        },
                        enabled = busy == null,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text("↩", maxLines = 1) }
                    OutlinedButton(
                        onClick = { val o = original; val ov = overlay; if (o != null && ov != null) autoDetect(o, ov) },
                        enabled = busy == null && original != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("🔍 Erkennen", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            overlay?.let { pushUndo(); it.eraseColor(0); result = null; version++ }
                        },
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Leeren", maxLines = 1) }
                    Button(
                        onClick = {
                            val o = original ?: return@Button
                            val ov = overlay ?: return@Button
                            scope.launch {
                                busy = "Entferne Schrift …"
                                val mask = withContext(Dispatchers.Default) { overlayToMask(ov) }
                                if (mask.none { it }) {
                                    vm.message = "Erst etwas rot markieren"
                                } else {
                                    result = withContext(Dispatchers.Default) { TextEraser.inpaint(o, mask) }
                                }
                                busy = null
                            }
                        },
                        enabled = busy == null && original != null,
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("✂ Entfernen", maxLines = 1) }
                }
            }
        }
    }
}
