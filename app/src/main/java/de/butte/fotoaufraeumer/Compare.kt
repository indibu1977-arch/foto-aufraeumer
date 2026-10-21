@file:OptIn(ExperimentalMaterial3Api::class)

package de.butte.fotoaufraeumer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private val Good = Color(0xFF2E7D32)
private val Bad = Color(0xFFC62828)

// ======================= Vorher/Nachher-Schieber =======================

/**
 * Vorher (links) / Nachher (rechts) mit verschiebbarer Trennlinie.
 * Ein Finger = Schieber bewegen, zwei Finger = zoomen und verschieben.
 */
@Composable
fun BeforeAfter(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier, labels: Pair<String, String> = "Vorher" to "Nachher") {
    var pos by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val b = remember(before) { before.asImageBitmap() }
    val a = remember(after) { after.asImageBitmap() }

    Box(modifier.background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var multi = false
                        pos = (down.position.x / size.width).coerceIn(0f, 1f)
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                multi = true
                                zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 6f)
                                val maxX = size.width * (zoom - 1f) / 2f
                                val maxY = size.height * (zoom - 1f) / 2f
                                val p = pan + ev.calculatePan()
                                pan = Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                                if (zoom <= 1.01f) pan = Offset.Zero
                            } else if (pressed.size == 1 && !multi) {
                                pos = (pressed[0].position.x / size.width).coerceIn(0f, 1f)
                            }
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        ) {
            // Jedes Bild für sich einpassen (unterschiedliche Formate werden nicht verzerrt)
            fun fit(bmp: Bitmap): Pair<IntOffset, IntSize> {
                val s = min(size.width / bmp.width, size.height / bmp.height)
                val dw = (bmp.width * s).roundToInt()
                val dh = (bmp.height * s).roundToInt()
                return IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()) to IntSize(dw, dh)
            }
            val (offB, sizeB) = fit(before)
            val (offA, sizeA) = fit(after)
            val split = size.width * pos
            val tf: androidx.compose.ui.graphics.drawscope.DrawTransform.() -> Unit = {
                translate(pan.x, pan.y)
                scale(zoom, zoom, center)
            }
            clipRect(right = split) {
                withTransform(tf) { drawImage(b, dstOffset = offB, dstSize = sizeB) }
            }
            clipRect(left = split) {
                withTransform(tf) { drawImage(a, dstOffset = offA, dstSize = sizeA) }
            }
            drawLine(Color.White, Offset(split, 0f), Offset(split, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 15.dp.toPx(), center = Offset(split, size.height / 2))
            drawCircle(Color(0xFF424242), radius = 12.dp.toPx(), center = Offset(split, size.height / 2))
        }
        Tag(labels.first, Modifier.align(Alignment.TopStart).padding(8.dp))
        Tag(labels.second, Modifier.align(Alignment.TopEnd).padding(8.dp))
        if (zoom > 1.01f) {
            AssistChip(
                onClick = { zoom = 1f; pan = Offset.Zero },
                label = { Text("${(zoom * 10).roundToInt() / 10f}× · zurück") },
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xCC000000), labelColor = Color.White),
            )
        } else {
            Text(
                "Schieben = vergleichen · 2 Finger = zoomen",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
                    .background(Color(0x88000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ======================= Qualität vorher/nachher =======================

@Composable
fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    val c = when {
        score >= 70 -> Good
        score >= 50 -> Color(0xFFF9A825)
        else -> Bad
    }
    Box(
        modifier.size(40.dp).clip(CircleShape).background(c),
        contentAlignment = Alignment.Center,
    ) { Text("$score", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** Kompakte Zeile „Qualität 61 → 78 (+17)“, aufklappbar mit allen Messwerten. */
@Composable
fun QualityCompare(before: Metrics?, after: Metrics?, startOpen: Boolean = false) {
    var open by remember { mutableStateOf(startOpen) }
    if (before == null) {
        Text("Messe Qualität …", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Qualität", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
            ScoreBadge(before.score)
            if (after != null) {
                Text("  →  ", style = MaterialTheme.typography.titleMedium)
                ScoreBadge(after.score)
                val d = after.score - before.score
                Text(
                    "  ${if (d > 0) "+" else ""}$d",
                    color = when { d > 0 -> Good; d < 0 -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(if (open) "▲ Details" else "▼ Details", style = MaterialTheme.typography.labelMedium)
        }
        if (open) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("", Modifier.weight(1.3f))
                Text("Vorher", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
                if (after != null) Text("Nachher", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
            val ra = after?.ratings
            before.ratings.forEachIndexed { i, rb ->
                val r2 = ra?.get(i)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(rb.label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                    RatingCell(rb, Modifier.weight(1f))
                    if (r2 != null) {
                        val d = r2.value - rb.value
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when { d > 0.03f -> "▲ "; d < -0.03f -> "▼ "; else -> "" },
                                color = if (d > 0) Good else Bad,
                                fontSize = 10.sp,
                            )
                            RatingCell(r2, Modifier)
                        }
                    }
                }
            }
            Text(
                "Punkte 0–100. Grün = gut, Gelb = mittel, Rot = verbesserungswürdig.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RatingCell(r: Rating, modifier: Modifier) {
    val c = when {
        r.value >= 0.75f -> Good
        r.value >= 0.45f -> Color(0xFFF9A825)
        else -> Bad
    }
    Row(modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(r.shown, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
    }
}

// ======================= Analyse & Vorschläge =======================

@Composable
fun AnalysisPanel(
    metrics: Metrics?,
    suggestions: List<Suggestion>,
    busy: Boolean,
    onApply: (Suggestion) -> Unit,
    onApplyAll: () -> Unit,
    onOptimize: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (metrics == null) {
            Text("Analysiere …", style = MaterialTheme.typography.bodySmall)
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreBadge(metrics.score)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Bewertung: ${Quality.grade(metrics.score)}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (suggestions.isEmpty()) "Keine Schwächen gefunden ✓" else "${suggestions.size} Vorschläge",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Button(onClick = onOptimize, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Optimiere …" else "✨ Selbst verbessern (testet & wählt das Beste)")
        }
        val fixable = suggestions.filter { it.fix != null }
        if (fixable.size > 1) {
            OutlinedButton(onClick = onApplyAll, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Alle ${fixable.size} Vorschläge anwenden")
            }
        }
        for (s in suggestions) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (s.severe) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (s.severe) "⚠ " else "• ") + s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    if (s.fix != null) TextButton(onClick = { onApply(s) }, enabled = !busy) { Text("Anwenden") }
                }
            }
        }
    }
}

// ======================= Bearbeitete Bilder finden =======================

private val editSuffixes = listOf("_verbessert", "_ohneText")
private val copyNo = Regex("""\s*\(\d+\)$""")

private fun base(name: String) = name.substringBeforeLast('.').replace(copyNo, "")

fun isEdited(p: Photo): Boolean = editSuffixes.any { base(p.name).endsWith(it) }

/** Paare (Original, Bearbeitung). Bei mehrfach bearbeiteten Bildern ist das „Original“ die Vorstufe. */
fun editedPairs(photos: List<Photo>): List<Pair<Photo, Photo>> {
    val byBase = photos.groupBy { base(it.name).lowercase() }
    val out = ArrayList<Pair<Photo, Photo>>()
    for (p in photos) {
        var b = base(p.name)
        if (editSuffixes.none { b.endsWith(it) }) continue
        var orig: Photo? = null
        while (orig == null) {
            val suf = editSuffixes.firstOrNull { b.endsWith(it) } ?: break
            b = b.removeSuffix(suf)
            val c = byBase[b.lowercase()]?.filter { it.id != p.id } ?: continue
            orig = c.firstOrNull { it.relPath == p.relPath } ?: c.firstOrNull()
        }
        if (orig != null) out += orig to p
    }
    return out.sortedByDescending { it.second.id }
}

@Composable
fun EditedListScreen(vm: AppVM) {
    val pairs = remember(vm.photos) { editedPairs(vm.photos) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bearbeitete vergleichen") },
                navigationIcon = { BackButton { vm.back() } },
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(onClick = { vm.go(Screen.PickCompare) }, modifier = Modifier.fillMaxWidth()) {
                    Text("📷 Zwei Fotos selbst auswählen")
                }
            }
            if (pairs.isEmpty()) {
                item {
                    Text(
                        "Noch keine bearbeiteten Fotos gefunden. Sie entstehen über „✨ Verbessern“ oder „Schrift entfernen“ (Dateien …_verbessert.jpg / …_ohneText.jpg).",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(pairs, key = { it.second.id }) { (o, e) ->
                Card(onClick = { vm.go(Screen.Compare(o, e)) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(o.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
                        Text(" → ", style = MaterialTheme.typography.titleMedium)
                        AsyncImage(e.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text("${dateStr(e.dateTaken)} · ${e.bucket}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompareScreen(vm: AppVM, before: Photo, after: Photo, free: Boolean = false) {
    val la = if (free) "Bild A" else "Vorher"
    val lb = if (free) "Bild B" else "Nachher"
    val ctx = LocalContext.current
    var bb by remember(before.id) { mutableStateOf<Bitmap?>(null) }
    var ab by remember(after.id) { mutableStateOf<Bitmap?>(null) }
    var mb by remember(before.id) { mutableStateOf<Metrics?>(null) }
    var ma by remember(after.id) { mutableStateOf<Metrics?>(null) }
    var failed by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Photo?>(null) }

    LaunchedEffect(before.id, after.id) {
        val (x, y) = withContext(Dispatchers.IO) {
            try { Enhancer.decode(ctx, before.uri, 1600) to Enhancer.decode(ctx, after.uri, 1600) } catch (e: Throwable) { null to null }
        }
        if (x == null || y == null) { failed = true; return@LaunchedEffect }
        bb = x; ab = y
        mb = withContext(Dispatchers.Default) { Quality.measure(x) }
        ma = withContext(Dispatchers.Default) { Quality.measure(y) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vergleichen") },
                navigationIcon = { BackButton { vm.back() } },
                actions = {
                    TextButton(onClick = { vm.replaceTop(Screen.Compare(after, before, free)) }) { Text("⇄ Tauschen") }
                    if (free) TextButton(onClick = { vm.back() }) { Text("Andere") }
                    else TextButton(onClick = { vm.go(Screen.Enhance(after)) }) { Text("Bearbeiten") }
                },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                val x = bb
                val y = ab
                when {
                    x != null && y != null -> BeforeAfter(x, y, Modifier.fillMaxSize(), la to lb)
                    failed -> Text("Bild konnte nicht geladen werden", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                QualityCompare(mb, ma, startOpen = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    "$la: ${before.name}\n   ${before.width}×${before.height} · ${sizeStr(before.size)} · ${dateStr(before.dateTaken)}\n$lb: ${after.name}\n   ${after.width}×${after.height} · ${sizeStr(after.size)} · ${dateStr(after.dateTaken)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ask = before }, modifier = Modifier.weight(1f)) { Text(if (free) "A löschen" else "Original löschen", maxLines = 1) }
                    OutlinedButton(onClick = { ask = after }, modifier = Modifier.weight(1f)) { Text(if (free) "B löschen" else "Bearbeitung löschen", maxLines = 1) }
                }
            }
        }
    }

    ask?.let { p ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(if (free) "${if (p.id == before.id) la else lb} löschen?" else if (p.id == before.id) "Original löschen?" else "Bearbeitung löschen?") },
            text = { Text("${p.name} kommt in den Papierkorb (30 Tage wiederherstellbar).") },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    vm.trashOne(p, "${p.name} in den Papierkorb verschoben")
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Abbrechen") } },
        )
    }
}

// ======================= Zwei Fotos frei auswählen =======================

@Composable
fun PickCompareScreen(vm: AppVM) {
    var album by vm::compareAlbum
    var picked by vm::comparePick
    val albums = remember(vm.photos) { MediaRepo.albums(vm.photos) }
    val shown = remember(vm.photos, album) { vm.photos.filter { album == null || it.relPath == album } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Zwei Fotos wählen")
                        Text(
                            when (picked.size) { 0 -> "Erst Bild A antippen"; 1 -> "Jetzt Bild B antippen"; else -> "Bereit – oder antippen zum Ändern" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { BackButton { vm.back() } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (i in 0..1) {
                        val p = picked.getOrNull(i)
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p != null) AsyncImage(p.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text(if (i == 0) "A" else "B", color = Color.White, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 4.dp))
                        }
                    }
                    TextButton(onClick = { picked = emptyList() }, enabled = picked.isNotEmpty()) { Text("Leeren") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { vm.go(Screen.Compare(picked[0], picked[1], free = true)) },
                        enabled = picked.size == 2,
                    ) { Text("Vergleichen") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { FilterChip(selected = album == null, onClick = { album = null }, label = { Text("Alle (${vm.photos.size})") }) }
                items(albums, key = { it.path }) { a ->
                    FilterChip(selected = album == a.path, onClick = { album = a.path }, label = { Text("${a.name} (${a.count})") })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(shown, key = { it.id }) { p ->
                    val idx = picked.indexOfFirst { it.id == p.id }
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.DarkGray)
                            .clickable {
                                picked = when {
                                    idx >= 0 -> picked.filterNot { it.id == p.id }
                                    picked.size < 2 -> picked + p
                                    else -> listOf(picked[0], p) // B ersetzen
                                }
                            }
                    ) {
                        AsyncImage(p.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (idx >= 0) {
                            Box(Modifier.fillMaxSize().background(Color(0x661565C0)))
                            Text(
                                if (idx == 0) "A" else "B",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1565C0))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
