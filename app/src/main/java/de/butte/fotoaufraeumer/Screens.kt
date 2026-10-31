@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.butte.fotoaufraeumer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ======================= Start =======================

@Composable
fun HomeScreen(vm: AppVM) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Foto-Aufräumer") },
                actions = {
                    IconButton(onClick = { vm.go(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${vm.photos.size} Fotos auf dem Handy", style = MaterialTheme.typography.bodyLarge)
            BigCard("📸  Kamera-Assistent", "Erkennt Nacht, Gegenlicht, Dämmerung, Bewegung, Gesichter … und stellt die Kamera automatisch optimal ein") {
                vm.go(Screen.Camera)
            }
            BigCard("🔍  Fotos durchsuchen", "Doppelte, ähnliche und schlechte Fotos finden und löschen") {
                vm.go(Screen.Scan)
                if (!vm.scanDone && !vm.scanning) vm.startScan()
            }
            BigCard("👆  Wisch-Sortierer", "Foto für Foto: nach unten löschen, sonst in Ordner einsortieren") {
                vm.go(Screen.SwipePick)
            }
            BigCard("🖼️  Galerie", "Durch alle Fotos scrollen – Raster, 2 oder 1 Bild – nach unten wischen zum Löschen") {
                vm.go(Screen.Gallery)
            }
            val edited = remember(vm.photos) { vm.photos.count { isEdited(it) } }
            BigCard("🆚  Fotos vergleichen", "Gleiche & ähnliche suchen, Bearbeitete ($edited) oder zwei beliebige Fotos – mit Schieber und Qualitätswerten") {
                vm.go(Screen.Edited)
            }
            BigCard("🔒  Privater Ordner", "Fotos, die nur du in dieser App siehst – mit PIN geschützt") {
                vm.go(Screen.Vault)
            }
            BigCard(
                "⚙️  Wischrichtungen",
                listOf(Dir.LEFT, Dir.UP, Dir.RIGHT, Dir.DOWN).joinToString("   ") { vm.dirLabel(it) },
            ) { vm.go(Screen.Settings) }
            Text(
                "Tipp: Fotos lange gedrückt halten für die große Ansicht und „✨ Verbessern“.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BigCard(title: String, sub: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ======================= Durchsuchen =======================

@Composable
fun ScanScreen(vm: AppVM) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Photo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Durchsuchen") },
                navigationIcon = { BackButton { vm.back() } },
                actions = {
                    if (!vm.scanning) {
                        IconButton(onClick = { vm.startScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Neu durchsuchen")
                        }
                    }
                },
            )
        },
        bottomBar = { if (vm.selection.isNotEmpty()) SelectionBar(vm) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.scanning) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(progress = { vm.scanProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text(vm.scanText, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Beim ersten Mal dauert das etwas. Danach geht es viel schneller.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (!vm.scanDone) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { vm.startScan() }) { Text("Jetzt durchsuchen") }
                }
            } else {
                val tabs = listOf(
                    "Doppelt (${vm.dupGroups.sumOf { it.size - 1 }})",
                    "Ähnlich (${vm.simGroups.size})",
                    "Qualität (${vm.quality.size})",
                )
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(t, maxLines = 1, fontSize = 13.sp) },
                        )
                    }
                }
                when (tab) {
                    0 -> GroupList(
                        vm = vm,
                        groups = vm.dupGroups,
                        info = "Exakt gleiche Dateien. „Beste behalten“ markiert alle Kopien außer der ältesten.",
                        keepSharpest = false,
                        onPreview = { preview = it },
                    )
                    1 -> Column {
                        Spacer(Modifier.height(6.dp))
                        LevelChips(vm.simLevel, listOf("Streng", "Normal", "Locker"), "Ähnlichkeit:") { vm.changeSimLevel(it) }
                        GroupList(
                            vm = vm,
                            groups = vm.simGroups,
                            info = "Serienbilder & Fast-Gleiches. „Beste behalten“ behält jeweils das schärfste Foto.",
                            keepSharpest = true,
                            onPreview = { preview = it },
                        )
                    }
                    else -> Column {
                        Spacer(Modifier.height(6.dp))
                        LevelChips(vm.blurLevel, listOf("Streng", "Normal", "Locker"), "Unschärfe:") { vm.changeBlurLevel(it) }
                        QualityGrid(vm) { preview = it }
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
            onEnhance = {
                preview = null
                vm.go(Screen.Enhance(p))
            },
            onDismiss = { preview = null },
        )
    }
}

@Composable
fun SelectionBar(vm: AppVM) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.clearSelection() }) { Text("Auswahl aufheben") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.deleteSelected() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("${vm.selection.size} löschen")
            }
        }
    }
}

@Composable
fun GroupList(
    vm: AppVM,
    groups: List<List<Photo>>,
    info: String,
    keepSharpest: Boolean,
    onPreview: (Photo) -> Unit,
    onCompare: ((Photo, Photo) -> Unit)? = null,
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column {
                Text(info, style = MaterialTheme.typography.bodySmall)
                if (groups.isNotEmpty()) {
                    Button(onClick = { vm.autoSelect(groups, keepSharpest) }) { Text("Beste behalten (alle Gruppen)") }
                }
            }
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    "Nichts gefunden 🎉",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(groups, key = { g -> "g" + g.first().id }) { g ->
            val best = remember(g) { if (keepSharpest) vm.bestOf(g) else g.first() }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${g.size} Fotos · ${dateStr(g.first().dateTaken)}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (onCompare != null) {
                            TextButton(onClick = { onCompare(best, g.first { it.id != best.id }) }) { Text("🆚") }
                        }
                        TextButton(onClick = { vm.autoSelect(listOf(g), keepSharpest) }) { Text("Beste behalten") }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(g, key = { it.id }) { p ->
                            Thumb(
                                p = p,
                                selected = p.id in vm.selection,
                                label = (if (p.id == best.id) "★ Vorschlag behalten\n" else "") + "${p.width}×${p.height} · ${sizeStr(p.size)}",
                                onClick = { vm.toggle(p.id) },
                                onLongClick = { onPreview(p) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QualityGrid(vm: AppVM, onPreview: (Photo) -> Unit) {
    val list = vm.quality
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    "Unscharfe, zu dunkle oder überbelichtete Fotos. Antippen = auswählen, lange drücken = groß ansehen / verbessern.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (list.isNotEmpty()) {
                    TextButton(onClick = { vm.selectAll(list.map { it.photo.id }) }) { Text("Alle auswählen") }
                } else {
                    Text("Nichts gefunden 🎉", Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
                }
            }
        }
        items(list, key = { it.photo.id }) { q ->
            Thumb(
                p = q.photo,
                selected = q.photo.id in vm.selection,
                label = q.reason,
                modifier = Modifier.aspectRatio(1f),
                onClick = { vm.toggle(q.photo.id) },
                onLongClick = { onPreview(q.photo) },
            )
        }
    }
}

// ======================= Wisch-Sortierer =======================

@Composable
fun SwipePickScreen(vm: AppVM) {
    val albums = remember(vm.photos) { MediaRepo.albums(vm.photos) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Was sortieren?") },
                navigationIcon = { BackButton { vm.back() } },
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (listOf(Dir.LEFT, Dir.UP, Dir.RIGHT).all { vm.targets[it] == null }) {
                item {
                    Card(
                        onClick = { vm.go(Screen.Settings) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Text(
                            "Noch keine Ordner für links/oben/rechts festgelegt. Hier antippen zum Einrichten. (Nach unten = Löschen geht sofort.)",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Alle Fotos", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("${vm.photos.size} Fotos, neueste zuerst") },
                    modifier = Modifier.clickable { vm.startSwipe(null) },
                )
                HorizontalDivider()
            }
            items(albums, key = { it.path }) { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = { Text("${a.count} Fotos · ${a.path}") },
                    modifier = Modifier.clickable { vm.startSwipe(a.path) },
                )
            }
        }
    }
}

private fun dirFor(o: Offset, th: Float): Dir? = when {
    abs(o.x) > abs(o.y) && o.x > th -> Dir.RIGHT
    abs(o.x) > abs(o.y) && o.x < -th -> Dir.LEFT
    abs(o.y) >= abs(o.x) && o.y > th -> Dir.DOWN
    abs(o.y) >= abs(o.x) && o.y < -th -> Dir.UP
    else -> null
}

@Composable
fun SwipeScreen(vm: AppVM) {
    val current = vm.swipeList.getOrNull(vm.swipeIndex)
    var askLeave by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.pendingCount > 0) { askLeave = true }
    BackHandler(enabled = jump) { jump = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.swipeSourceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${min(vm.swipeIndex + 1, vm.swipeList.size)} von ${vm.swipeList.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { BackButton { if (vm.pendingCount > 0) askLeave = true else vm.back() } },
                actions = {
                    FilledTonalButton(
                        onClick = { jump = !jump },
                        enabled = vm.swipeList.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.padding(end = 6.dp),
                    ) { Text(if (jump) "✕ Galerie" else "▦ Galerie", maxLines = 1) }
                    if (vm.pendingCount > 0) {
                        Button(onClick = { vm.applySwipe() }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Anwenden (${vm.pendingCount})")
                        }
                    }
                },
            )
        }
    ) { pad ->
        if (jump) {
            Box(Modifier.padding(pad).fillMaxSize()) { SwipeJumpGrid(vm) { jump = false } }
        } else Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                vm.dirLabel(Dir.UP),
                Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SideLabel(vm.dirLabel(Dir.LEFT))
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                    if (current == null) {
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Alles durchgesehen 🎉", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            if (vm.pendingCount > 0) {
                                Button(onClick = { vm.applySwipe() }) { Text("${vm.pendingCount} Änderungen anwenden") }
                            } else {
                                OutlinedButton(onClick = { vm.restartSwipe() }) { Text("Von vorn beginnen") }
                            }
                        }
                    } else {
                        SwipeCard(vm, current)
                    }
                }
                SideLabel(vm.dirLabel(Dir.RIGHT))
            }
            Text(
                vm.dirLabel(Dir.DOWN),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.undo() },
                    enabled = vm.queue.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("↩ Zurück", maxLines = 1) }
                OutlinedButton(
                    onClick = { current?.let { vm.go(Screen.Enhance(it)) } },
                    enabled = current != null,
                    modifier = Modifier.weight(1f),
                ) { Text("✨ Verbessern", maxLines = 1) }
                Button(
                    onClick = { vm.swipe(null) },
                    enabled = current != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Behalten ›", maxLines = 1) }
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("Änderungen anwenden?") },
            text = { Text("${vm.pendingCount} Fotos sind zum Löschen oder Verschieben vorgemerkt.") },
            confirmButton = {
                TextButton(onClick = {
                    askLeave = false
                    vm.applySwipe(thenBack = true)
                }) { Text("Anwenden") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        askLeave = false
                        vm.discardSwipe()
                    }) { Text("Verwerfen") }
                    TextButton(onClick = { askLeave = false }) { Text("Abbrechen") }
                }
            },
        )
    }
}

@Composable
private fun SideLabel(text: String) {
    Text(
        text,
        modifier = Modifier.width(44.dp).padding(horizontal = 2.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SwipeCard(vm: AppVM, p: Photo) {
    val th = with(LocalDensity.current) { 90.dp.toPx() }
    var offset by remember(p.id) { mutableStateOf(Offset.Zero) }
    val dir = dirFor(offset, th)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(p.id) {
                detectDragGestures(
                    onDragEnd = {
                        val d = dirFor(offset, th)
                        if (d != null) vm.swipe(d)
                        offset = Offset.Zero
                    },
                    onDragCancel = { offset = Offset.Zero },
                ) { change, amount ->
                    change.consume()
                    offset += amount
                }
            }
            .graphicsLayer {
                translationX = offset.x
                translationY = offset.y
                rotationZ = offset.x / 30f
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        AsyncImage(
            model = p.uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "${dateStr(p.dateTaken)} · ${p.bucket}",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(6.dp),
            textAlign = TextAlign.Center,
        )
        if (dir != null) {
            val ok = dir == Dir.DOWN || (vm.targets[dir] != null && !vm.isDisabled(vm.targets[dir]))
            val bg = when {
                !ok -> Color(0xDD616161)
                dir == Dir.DOWN -> Color(0xDDC62828)
                else -> Color(0xDD1565C0)
            }
            Text(
                if (ok) vm.dirLabel(dir) else "${dir.arrow} nicht belegt",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(bg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

// ======================= Einstellungen =======================

@Composable
fun SettingsScreen(vm: AppVM) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Dir?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = { BackButton { vm.back() } },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)
        ) {
            Text(
                "Wischrichtungen",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (d in listOf(Dir.LEFT, Dir.UP, Dir.RIGHT)) {
                val t = vm.targets[d]
                ListItem(
                    headlineContent = { Text("${d.arrow}  ${d.label}") },
                    supportingContent = {
                        Text(
                            when (t) {
                                null -> "nicht belegt – antippen"
                                Vault.TARGET -> "🔒 Privater Ordner (nur in dieser App sichtbar)"
                                NEXTCLOUD_TARGET -> "☁ In Nextcloud hochladen (Foto bleibt in der Galerie)"
                                PLATTE_DIRK, PLATTE_INES ->
                                    vm.targetName(t) + if (vm.isDisabled(t)) " – auf diesem Handy aus" else " – wird im WLAN hochgeladen"
                                else -> if (isDavTarget(t)) vm.targetName(t) + " – direkt auf die Platte"
                                else "${MediaRepo.folderName(t)}  ($t)"
                            }
                        )
                    },
                    modifier = Modifier.clickable { editing = d },
                )
            }
            ListItem(
                headlineContent = { Text("${Dir.DOWN.arrow}  ${Dir.DOWN.label}") },
                supportingContent = { Text("Löschen (Papierkorb, nach 30 Tagen endgültig weg)") },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DavSettings(vm)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Festplatten am Home Assistant",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Wem gehört dieses Handy? Die Platte des anderen wird hier abgeschaltet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in Owner.entries) {
                    FilterChip(
                        selected = vm.owner == o,
                        onClick = { vm.updateOwner(o) },
                        label = { Text(o.label) },
                    )
                }
            }
            Text(
                "Fotos landen erst im Ordner „Platte Dirk“ bzw. „Platte Ines“ auf dem Handy. Die Nextcloud-App lädt diesen Ordner im WLAN automatisch auf die Platte hoch.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Ohne Nachfrage arbeiten",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Android fragt beim Löschen und Verschieben jedes Mal nach. Mit der Freigabe „Medienverwaltung“ fällt diese Rückfrage weg.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (Build.VERSION.SDK_INT >= 31) {
                Button(
                    onClick = {
                        try {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}"))
                            )
                        } catch (_: Exception) {
                            vm.message = "Einstellung konnte nicht geöffnet werden"
                        }
                    },
                    enabled = !vm.canManageMedia,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(if (vm.canManageMedia) "✓ Freigegeben" else "Medienverwaltung freigeben") }
            } else {
                Text(
                    "Auf diesem Android nicht verfügbar.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
    editing?.let { d -> FolderDialog(vm, d) { editing = null } }
}

@Composable
fun FolderDialog(vm: AppVM, d: Dir, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var davPick by remember { mutableStateOf(false) }
    if (davPick) {
        DavFolderDialog(vm, onPick = { path ->
            vm.setTarget(d, DAV_PREFIX + path)
            davPick = false
            onDismiss()
        }, onDismiss = { davPick = false })
        return
    }
    val albums = remember(vm.photos) { MediaRepo.albums(vm.photos) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${d.arrow} ${d.label}: Zielordner") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.replace("/", "").replace("\\", "") },
                    label = { Text("Neuer Ordner") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        vm.setTarget(d, Vault.TARGET)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🔒 Privater Ordner (nur ich)") }
                FilledTonalButton(
                    onClick = {
                        vm.setTarget(d, NEXTCLOUD_TARGET)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("☁ In Nextcloud hochladen") }
                Button(
                    onClick = { davPick = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("💾 Ordner auf der Platte wählen …") }
                for (p in listOf(PLATTE_DIRK, PLATTE_INES)) {
                    if (!vm.isDisabled(p)) {
                        FilledTonalButton(
                            onClick = {
                                vm.setTarget(d, p)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(vm.targetName(p)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        vm.setTarget(d, "Pictures/${newName.trim()}/")
                        onDismiss()
                    },
                ) { Text("Neuen Ordner verwenden") }
                Text("Oder vorhandenen Ordner wählen:", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(albums, key = { it.path }) { a ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setTarget(d, a.path)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(a.name)
                            Text(
                                "${a.count} Fotos · ${a.path}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        dismissButton = {
            TextButton(onClick = {
                vm.setTarget(d, null)
                onDismiss()
            }) { Text("Belegung entfernen") }
        },
    )
}


// ======================= Nextcloud direkt =======================

@Composable
fun DavSettings(vm: AppVM) {
    var url by remember { mutableStateOf(vm.dav.url) }
    var user by remember { mutableStateOf(vm.dav.user) }
    var pass by remember { mutableStateOf(vm.dav.pass) }
    var show by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Direkt auf die Platte (Nextcloud)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Fotos werden beim Wischen sofort in einen Ordner auf deiner Festplatte hochgeladen. " +
                "Das App-Passwort erstellst du in Nextcloud unter Einstellungen → Sicherheit → „App-Passwort erstellen“.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it },
            label = { Text("App-Passwort") },
            singleLine = true,
            visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "verbergen" else "zeigen") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !vm.davBusy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
                onClick = { vm.connectDav(url, user, pass) },
            ) { Text("Speichern & verbinden") }
            if (vm.davBusy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        vm.davStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Checkbox(checked = vm.davDeleteAfter, onCheckedChange = { vm.updateDavDeleteAfter(it) })
            Text("Nach dem Hochladen vom Handy löschen (Papierkorb, 30 Tage)", style = MaterialTheme.typography.bodyMedium)
        }
    }
    vm.davPendingPin?.let { fp ->
        AlertDialog(
            onDismissRequest = { vm.acceptPin(false) },
            title = { Text("Zertifikat vertrauen?") },
            text = {
                Column {
                    Text("Dein Server benutzt ein selbst ausgestelltes Zertifikat. Das ist bei Nextcloud zu Hause normal.")
                    Spacer(Modifier.height(8.dp))
                    Text("Nur bestätigen, wenn du gerade im eigenen WLAN bist. Ändert sich das Zertifikat später, verweigert die App die Verbindung.")
                    Spacer(Modifier.height(8.dp))
                    Text("SHA-256: $fp", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.acceptPin(true) }) { Text("Vertrauen") } },
            dismissButton = { TextButton(onClick = { vm.acceptPin(false) }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun DavFolderDialog(vm: AppVM, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (vm.dav.ok) vm.davOpen("/") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordner auf der Platte") },
        text = {
            Column {
                if (!vm.dav.ok || vm.dav.url.startsWith("https") && vm.dav.pin == null) {
                    Text("Erst in den Einstellungen unter „Direkt auf die Platte“ Server, Benutzer und App-Passwort eintragen und verbinden.")
                    return@Column
                }
                Text("📁 " + vm.davPath.trim('/').ifEmpty { "(Start)" }, style = MaterialTheme.typography.labelLarge)
                vm.davError?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error) }
                if (vm.davBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    if (vm.davPath.trim('/').isNotEmpty()) {
                        item {
                            Text(
                                "⬆  eine Ebene höher",
                                modifier = Modifier.fillMaxWidth().clickable {
                                    vm.davOpen("/" + vm.davPath.trim('/').substringBeforeLast('/', ""))
                                }.padding(vertical = 10.dp),
                            )
                        }
                    }
                    items(vm.davFolders, key = { it }) { f ->
                        Text(
                            "📁  $f",
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.davOpen(vm.davPath.trimEnd('/') + "/" + f)
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName, { newName = it.replace("/", "") },
                        label = { Text("Neuer Ordner hier") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newName.isNotBlank() && !vm.davBusy,
                        onClick = { vm.davCreate(newName); newName = "" },
                    ) { Text("Anlegen") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = vm.dav.ok && vm.davPath.trim('/').isNotEmpty() && !vm.davBusy,
                onClick = { onPick(vm.davPath) },
            ) { Text("Diesen Ordner wählen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
