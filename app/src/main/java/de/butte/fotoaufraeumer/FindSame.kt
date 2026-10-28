@file:OptIn(ExperimentalMaterial3Api::class)

package de.butte.fotoaufraeumer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.roundToInt

/** Fortschritt, solange der Fingerabdruck-Scan läuft. */
@Composable
private fun ScanWait(vm: AppVM) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (vm.scanning) {
            LinearProgressIndicator(progress = { vm.scanProgress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text(vm.scanText, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Alle Fotos bekommen einen Bild-Fingerabdruck. Beim ersten Mal dauert das etwas, danach geht es schnell.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        } else {
            Button(onClick = { vm.startScan() }) { Text("Fotos jetzt durchsuchen") }
        }
    }
}

// ======================= Gleiche & ähnliche suchen =======================

@Composable
fun FindSameScreen(vm: AppVM) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Photo?>(null) }
    LaunchedEffect(Unit) { if (!vm.scanDone && !vm.scanning) vm.startScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gleiche & ähnliche") },
                navigationIcon = { BackButton { vm.back() } },
            )
        },
        bottomBar = { if (vm.selection.isNotEmpty()) SelectionBar(vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.scanDone || vm.scanning) {
                ScanWait(vm)
                return@Column
            }
            val tabs = listOf(
                "Identisch (${vm.dupGroups.size})",
                "Ähnlich (${vm.simGroups.size})",
                "Zu einem Foto",
            )
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1, fontSize = 13.sp) })
                }
            }
            val compare: (Photo, Photo) -> Unit = { a, b -> vm.go(Screen.Compare(a, b, free = true)) }
            when (tab) {
                0 -> GroupList(
                    vm = vm,
                    groups = vm.dupGroups,
                    info = "Komplett identische Dateien. ★ = Vorschlag zum Behalten, 🆚 = mit Schieber vergleichen. Antippen = auswählen, lange drücken = groß.",
                    keepSharpest = false,
                    onPreview = { preview = it },
                    onCompare = compare,
                )
                1 -> Column {
                    Spacer(Modifier.height(6.dp))
                    LevelChips(vm.simLevel, listOf("Streng", "Normal", "Locker"), "Ähnlichkeit:") { vm.changeSimLevel(it) }
                    GroupList(
                        vm = vm,
                        groups = vm.simGroups,
                        info = "Serienbilder & Fast-Gleiches. ★ = schärfstes Foto (Vorschlag zum Behalten). „Beste behalten“ wählt den Rest zum Löschen aus.",
                        keepSharpest = true,
                        onPreview = { preview = it },
                        onCompare = compare,
                    )
                }
                else -> PickReference(vm)
            }
        }
    }

    preview?.let { p ->
        PhotoPreview(
            p = p,
            selected = p.id in vm.selection,
            onToggle = { vm.toggle(p.id) },
            onEnhance = { preview = null; vm.go(Screen.Enhance(p)) },
            onDismiss = { preview = null },
        )
    }
}

/** Ein Ausgangsfoto antippen – danach werden alle gleichen/ähnlichen gezeigt. */
@Composable
private fun PickReference(vm: AppVM) {
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    val albums = remember(vm.photos) { MediaRepo.albums(vm.photos) }
    val shown = remember(vm.photos, album) { vm.photos.filter { album == null || it.relPath == album } }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Foto antippen – die App sucht alle identischen und ähnlichen Fotos dazu.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
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
                Thumb(
                    p = p,
                    selected = false,
                    label = null,
                    modifier = Modifier.aspectRatio(1f),
                    onClick = { vm.go(Screen.SimilarTo(p)) },
                    onLongClick = { vm.go(Screen.SimilarTo(p)) },
                )
            }
        }
    }
}

// ======================= Ähnliche zu einem Foto =======================

private val levelNames = listOf("Identisch", "Sehr ähnlich", "Ähnlich", "Locker")
private val levelDist = intArrayOf(0, 6, 11, 16)

private fun matchLabel(m: Match): String = when {
    m.identical -> "100 % identisch"
    m.dist == 0 -> "gleiches Motiv"
    else -> "${(100 - m.dist * 100 / 64f).roundToInt()} % ähnlich"
}

@Composable
fun SimilarToScreen(vm: AppVM, ref: Photo) {
    var level by rememberSaveable { mutableIntStateOf(2) }
    var matches by remember(ref.id) { mutableStateOf<List<Match>?>(null) }
    var preview by remember { mutableStateOf<Photo?>(null) }
    val refAlive = vm.photos.any { it.id == ref.id }
    LaunchedEffect(Unit) { if (!vm.scanDone && !vm.scanning) vm.startScan() }
    LaunchedEffect(ref.id, level, vm.photos, vm.scanDone) {
        if (!vm.scanDone) return@LaunchedEffect
        matches = null
        val all = vm.similarTo(ref, levelDist[level])
        matches = if (level == 0) all.filter { it.identical } else all
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ähnliche suchen")
                        Text(ref.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = { BackButton { vm.back() } },
            )
        },
        bottomBar = { if (vm.selection.isNotEmpty()) SelectionBar(vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.scanDone || vm.scanning) {
                ScanWait(vm)
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(levelNames.size) { i ->
                    FilterChip(selected = level == i, onClick = { level = i }, label = { Text(levelNames[i]) })
                }
            }
            val ms = matches
            if (ms == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val group = remember(ms, refAlive) { (if (refAlive) listOf(ref) else emptyList()) + ms.map { it.photo } }
            val best = remember(group) { if (group.isNotEmpty()) vm.bestOf(group) else null }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val nId = ms.count { it.identical }
                        Text(
                            if (ms.isEmpty()) "Keine ${levelNames[level].lowercase()}en Fotos gefunden. Stufe lockern?"
                            else "${ms.size} Treffer" + (if (nId > 0) " · davon $nId komplett identisch" else ""),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (ms.isNotEmpty() && best != null) {
                            Text(
                                "Vorschlag: ★ behalten (schärfstes / größtes), Rest löschen. Antippen = auswählen, lange drücken = mit dem Ausgangsfoto vergleichen.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.suggestDelete(group) }) { Text("Vorschlag übernehmen") }
                                if (nId > 0) OutlinedButton(onClick = {
                                    vm.suggestDelete((if (refAlive) listOf(ref) else emptyList()) + ms.filter { it.identical }.map { it.photo })
                                }) { Text("Nur Identische") }
                            }
                        }
                    }
                }
                gridItems(group, key = { it.id }) { p ->
                    val m = ms.firstOrNull { it.photo.id == p.id }
                    val isRef = p.id == ref.id
                    Box {
                        Thumb(
                            p = p,
                            selected = p.id in vm.selection,
                            label = (if (p.id == best?.id) "★ " else "") +
                                (if (isRef) "Ausgangsfoto" else m?.let { matchLabel(it) } ?: "") +
                                "\n${p.width}×${p.height} · ${sizeStr(p.size)}",
                            modifier = Modifier.aspectRatio(1f),
                            onClick = { vm.toggle(p.id) },
                            onLongClick = {
                                if (isRef) preview = p else vm.go(Screen.Compare(ref, p, free = true))
                            },
                        )
                        if (isRef) {
                            Text(
                                "A",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF1565C0))
                                    .padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    preview?.let { p ->
        PhotoPreview(
            p = p,
            selected = p.id in vm.selection,
            onToggle = { vm.toggle(p.id) },
            onEnhance = { preview = null; vm.go(Screen.Enhance(p)) },
            onDismiss = { preview = null },
        )
    }
}
