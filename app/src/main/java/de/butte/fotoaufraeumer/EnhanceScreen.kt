@file:OptIn(ExperimentalMaterial3Api::class)

package de.butte.fotoaufraeumer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun EnhanceScreen(vm: AppVM, photo: Photo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var processed by remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    var adj by remember(photo.id) { mutableStateOf(Adjust()) }
    var failed by remember(photo.id) { mutableStateOf(false) }
    var mBefore by remember(photo.id) { mutableStateOf<Metrics?>(null) }
    var mAfter by remember(photo.id) { mutableStateOf<Metrics?>(null) }
    var levels by remember(photo.id) { mutableStateOf<Pair<Float, Float>?>(null) }
    var optimizing by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(photo.id) {
        val b = withContext(Dispatchers.IO) {
            try { Enhancer.decode(ctx, photo.uri, 1600) } catch (e: Throwable) { null }
        }
        if (b == null) { failed = true; return@LaunchedEffect }
        original = b
        withContext(Dispatchers.Default) {
            mBefore = Quality.measure(b)
            val a = Enhancer.auto(b)
            levels = a.lo to a.hi
        }
    }
    LaunchedEffect(original, adj) {
        val o = original ?: return@LaunchedEffect
        delay(40)
        processed = withContext(Dispatchers.Default) { Enhancer.apply(o, adj) }
    }
    LaunchedEffect(processed) {
        val p = processed ?: return@LaunchedEffect
        delay(200)
        mAfter = withContext(Dispatchers.Default) { Quality.measure(p) }
    }

    val changed = adj.copy(upscale = 1) != Adjust()
    val canSave = adj != Adjust()
    val current = if (changed) mAfter else mBefore
    val suggestions = remember(current, adj, levels) { current?.let { Quality.suggest(it, adj, levels) } ?: emptyList() }

    fun optimize() {
        val o = original ?: return
        optimizing = true
        scope.launch {
            val a = withContext(Dispatchers.Default) { Quality.optimize(o) }
            optimizing = false
            if (a == Adjust()) vm.message = "Das Foto ist schon gut – keine Verbesserung gefunden"
            else { adj = a; tab = 0 }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verbessern") },
                navigationIcon = { BackButton { vm.back() } },
                actions = {
                    TextButton(
                        onClick = { vm.saveEnhanced(photo, adj) },
                        enabled = !vm.saving && original != null && canSave,
                    ) { Text("Speichern") }
                },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                val o = original
                val p = processed
                when {
                    o != null && p != null && changed -> BeforeAfter(o, p, Modifier.fillMaxSize())
                    o != null -> {
                        val img = remember(o) { o.asImageBitmap() }
                        Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    failed -> Text("Bild konnte nicht geladen werden", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (vm.saving || optimizing) {
                    Column(
                        Modifier.matchParentSize().background(Color(0xAA000000)),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                optimizing -> "Teste Einstellungen …"
                                adj.upscale > 1 -> "Skaliere hoch & speichere … (kann etwas dauern)"
                                else -> "Speichere in voller Größe …"
                            },
                            color = Color.White,
                        )
                    }
                }
            }
            if (vm.savedFor == photo.id) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✓ Kopie gespeichert", Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { vm.compareSaved(photo) }) { Text("Vergleichen") }
                    TextButton(onClick = { vm.deleteOriginal(photo) }) { Text("Original löschen") }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                QualityCompare(mBefore, if (changed) mAfter else null)
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Regler") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (suggestions.isEmpty()) "Analyse ✓" else "Analyse (${suggestions.size})") },
                )
            }
            Column(
                Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (tab == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { optimize() }, enabled = original != null && !optimizing) { Text("✨ Automatisch") }
                        OutlinedButton(onClick = { adj = Adjust() }, enabled = canSave) { Text("Zurücksetzen") }
                    }
                    Slide("Helligkeit", adj.brightness, -1f..1f) { adj = adj.copy(brightness = it) }
                    Slide("Kontrast", adj.contrast, -1f..1f) { adj = adj.copy(contrast = it) }
                    Slide("Farbkraft", adj.saturation, -1f..1f) { adj = adj.copy(saturation = it) }
                    Slide("Wärme", adj.warmth, -1f..1f) { adj = adj.copy(warmth = it) }
                    Slide("Schärfe", adj.sharpen, 0f..1f) { adj = adj.copy(sharpen = it) }
                    Slide("Entrauschen", adj.denoise, 0f..1f) { adj = adj.copy(denoise = it) }
                    Slide("Details / Klarheit", adj.detail, 0f..1f) { adj = adj.copy(detail = it) }
                    Spacer(Modifier.height(4.dp))
                    Text("Hochskalieren (beim Speichern)", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        for ((f, l) in listOf(1 to "Aus", 2 to "2×", 3 to "3×")) {
                            FilterChip(selected = adj.upscale == f, onClick = { adj = adj.copy(upscale = f) }, label = { Text(l) })
                        }
                    }
                    if (adj.upscale > 1 && photo.width > 0 && photo.height > 0) {
                        val sw = minOf(photo.width, (photo.width * 4096f / maxOf(photo.width, photo.height)).roundToInt())
                        val sh = minOf(photo.height, (photo.height * 4096f / maxOf(photo.width, photo.height)).roundToInt())
                        val f = Enhancer.upscaleFactor(sw, sh, adj.upscale)
                        val ow = (sw * f).roundToInt()
                        val oh = (sh * f).roundToInt()
                        Text(
                            "${photo.width}×${photo.height} → ${ow}×${oh} (${(ow.toLong() * oh / 100_000) / 10.0} MP)" +
                                (if (f < adj.upscale - 0.05f) " · begrenzt wegen Speicher" else "") +
                                "\nDetails und Schärfe werden nach dem Vergrößern automatisch nachgezogen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (adj.lo > 0f || adj.hi < 255f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Tonwertausgleich aktiv (${adj.lo.roundToInt()}–${adj.hi.roundToInt()})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { adj = adj.copy(lo = 0f, hi = 255f) }) { Text("Aus") }
                        }
                    }
                    OutlinedButton(onClick = { vm.go(Screen.Erase(photo)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("🅰 Schrift entfernen (Datum, Wasserzeichen …)")
                    }
                } else {
                    AnalysisPanel(
                        metrics = current,
                        suggestions = suggestions,
                        busy = optimizing,
                        onApply = { s -> s.fix?.let { adj = it(adj) } },
                        onApplyAll = { adj = Quality.applyAll(adj, suggestions) },
                        onOptimize = { optimize() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(value * 100).roundToInt()}", style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
