@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.butte.fotoaufraeumer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class GalleryMode(val label: String, val per: Int) {
    GRID("Raster", 0), TWO("2 Bilder", 2), ONE("1 Bild", 1)
}

private val monthFmt = SimpleDateFormat("LLLL yyyy", Locale.GERMANY)

private fun monthOf(p: Photo?): String =
    if (p == null || p.dateTaken <= 0) "" else monthFmt.format(Date(p.dateTaken))

// ======================= Galerie =======================

@Composable
fun GalleryScreen(vm: AppVM) {
    val photos = vm.photos
    var mode by remember { mutableStateOf(GalleryMode.GRID) }
    var zoom by remember { mutableStateOf<Photo?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    val marks = vm.galleryMarks
    val markSet = remember(marks) { marks.toSet() }

    fun leave() { if (marks.isNotEmpty()) askLeave = true else vm.back() }

    BackHandler(enabled = marks.isNotEmpty()) { askLeave = true }
    // Später registriert = hat Vorrang: in der großen Ansicht führt „Zurück“ erst ins Raster
    BackHandler(enabled = mode != GalleryMode.GRID) { mode = GalleryMode.GRID }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Galerie")
                        Text("${photos.size} Fotos", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { BackButton { leave() } },
            )
        },
        bottomBar = { if (marks.isNotEmpty()) MarkBar(vm, marks.size) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                GalleryMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, GalleryMode.entries.size),
                        label = { Text(m.label, maxLines = 1) },
                    )
                }
            }
            Text(
                if (mode == GalleryMode.GRID) "Antippen = groß ansehen · lange drücken = zum Löschen markieren"
                else "⬅ ➡ weiter · ⬇ nach unten wischen = zum Löschen markieren",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (photos.isEmpty()) {
                    Text("Keine Fotos gefunden", Modifier.align(Alignment.Center))
                } else {
                    key(mode) {
                        if (mode == GalleryMode.GRID) {
                            GalleryGrid(vm, photos, markSet) { i ->
                                vm.galleryIndex = i
                                mode = GalleryMode.ONE
                            }
                        } else {
                            GalleryPager(
                                vm = vm,
                                photos = photos,
                                per = mode.per,
                                markSet = markSet,
                                onTap = { i, p ->
                                    when {
                                        p.id in markSet -> vm.unmark(p.id)
                                        mode == GalleryMode.TWO -> {
                                            vm.galleryIndex = i
                                            mode = GalleryMode.ONE
                                        }
                                        else -> zoom = p
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    zoom?.let { ZoomDialog(it) { zoom = null } }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("Markierte Fotos löschen?") },
            text = { Text("${marks.size} Fotos sind zum Löschen markiert. Sie kommen in den Papierkorb (30 Tage wiederherstellbar).") },
            confirmButton = {
                TextButton(onClick = {
                    askLeave = false
                    vm.deleteMarked(thenBack = true)
                }) { Text("Löschen") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        askLeave = false
                        vm.clearMarks()
                        vm.back()
                    }) { Text("Verwerfen") }
                    TextButton(onClick = { askLeave = false }) { Text("Abbrechen") }
                }
            },
        )
    }
}

@Composable
private fun MarkBar(vm: AppVM, count: Int) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "$count zum Löschen",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1,
            )
            TextButton(onClick = { vm.undoMark() }) { Text("↩", fontSize = 18.sp) }
            TextButton(onClick = { vm.clearMarks() }) { Text("Aufheben", maxLines = 1) }
            Button(
                onClick = { vm.deleteMarked() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Löschen", maxLines = 1)
            }
        }
    }
}

// ---------- Raster ----------

@Composable
private fun GalleryGrid(vm: AppVM, photos: List<Photo>, markSet: Set<Long>, onOpen: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberLazyGridState(
        initialFirstVisibleItemIndex = vm.galleryIndex.coerceIn(0, max(0, photos.size - 1))
    )
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { vm.galleryIndex = it }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
                Thumb(
                    p = p,
                    selected = p.id in markSet,
                    label = null,
                    modifier = Modifier.aspectRatio(1f),
                    onClick = { if (p.id in markSet) vm.unmark(p.id) else onOpen(i) },
                    onLongClick = { vm.toggleMark(p.id) },
                )
            }
        }
        FastScroller(
            total = photos.size,
            current = state.firstVisibleItemIndex,
            label = { monthOf(photos.getOrNull(it)) },
            onJump = { i -> scope.launch { state.scrollToItem(i) } },
        )
    }
}

// ---------- Große Ansicht (1 oder 2 Bilder) ----------

@Composable
private fun GalleryPager(
    vm: AppVM,
    photos: List<Photo>,
    per: Int,
    markSet: Set<Long>,
    onTap: (Int, Photo) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pages = (photos.size + per - 1) / per
    val state = rememberPagerState(
        initialPage = (vm.galleryIndex / per).coerceIn(0, max(0, pages - 1))
    ) { pages }
    LaunchedEffect(state) {
        snapshotFlow { state.currentPage }.collect { vm.galleryIndex = it * per }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = state,
            beyondViewportPageCount = 1,
            key = { page -> "p" + (photos.getOrNull(page * per)?.id ?: page) },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Column(
                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (k in 0 until per) {
                    val i = page * per + k
                    val p = photos.getOrNull(i)
                    if (p == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        GalleryCard(
                            p = p,
                            marked = p.id in markSet,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            onMark = {
                                vm.mark(p.id)
                                // In der 1er-Ansicht automatisch zum nächsten Foto
                                if (per == 1 && page + 1 < pages) {
                                    scope.launch { state.animateScrollToPage(page + 1) }
                                }
                            },
                            onUnmark = { vm.unmark(p.id) },
                            onTap = { onTap(i, p) },
                        )
                    }
                }
            }
        }
        FastScroller(
            total = pages,
            current = state.currentPage,
            label = { monthOf(photos.getOrNull(it * per)) },
            onJump = { pg -> scope.launch { state.scrollToPage(pg) } },
        )
    }
}

@Composable
private fun GalleryCard(
    p: Photo,
    marked: Boolean,
    modifier: Modifier,
    onMark: () -> Unit,
    onUnmark: () -> Unit,
    onTap: () -> Unit,
) {
    val th = with(LocalDensity.current) { 90.dp.toPx() }
    var dy by remember(p.id) { mutableFloatStateOf(0f) }
    val markNow by rememberUpdatedState(onMark)
    val unmarkNow by rememberUpdatedState(onUnmark)
    val tapNow by rememberUpdatedState(onTap)
    val isMarked by rememberUpdatedState(marked)
    val red = Color(0xFFC62828)

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .then(if (marked) Modifier.border(4.dp, red, RoundedCornerShape(12.dp)) else Modifier)
            .pointerInput(p.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dy > th && !isMarked) markNow()
                        else if (dy < -th && isMarked) unmarkNow()
                        dy = 0f
                    },
                    onDragCancel = { dy = 0f },
                ) { change, amount ->
                    change.consume()
                    dy += amount
                }
            }
            .pointerInput(p.id) { detectTapGestures(onTap = { tapNow() }) }
    ) {
        AsyncImage(
            model = p.uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dy
                    alpha = if (dy > 0) (1f - dy / (th * 3)).coerceIn(0.3f, 1f) else 1f
                },
        )
        Text(
            dateStr(p.dateTaken),
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x88000000), RoundedCornerShape(topEnd = 8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (marked) {
            Text(
                "🗑 Zum Löschen markiert",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .background(red, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        val hint = when {
            dy > th && !marked -> "⬇ Löschen"
            dy < -th && marked -> "⬆ Behalten"
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(if (marked) Color(0xDD2E7D32) else Color(0xDDC62828), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

// ---------- Schnellscroll-Leiste ----------

@Composable
private fun BoxScope.FastScroller(
    total: Int,
    current: Int,
    label: (Int) -> String,
    onJump: (Int) -> Unit,
) {
    if (total < 2) return
    val density = LocalDensity.current
    val thumbH = 52.dp
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val cur by rememberUpdatedState(current)
    val jump by rememberUpdatedState(onJump)
    val tot by rememberUpdatedState(total)

    BoxWithConstraints(Modifier.matchParentSize()) {
        val trackPx = max(1f, constraints.maxHeight - with(density) { thumbH.toPx() })
        val frac = if (dragging) dragFrac else cur.toFloat() / (total - 1)
        val y = (frac.coerceIn(0f, 1f) * trackPx).roundToInt()

        if (dragging) {
            val idx = (dragFrac * (total - 1)).roundToInt()
            Text(
                label(idx),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 44.dp.roundToPx() }, y + with(density) { 10.dp.roundToPx() }) }
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .size(width = 36.dp, height = thumbH)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragFrac = cur.toFloat() / max(1, tot - 1)
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        dragFrac = (dragFrac + amount / trackPx).coerceIn(0f, 1f)
                        jump((dragFrac * (tot - 1)).roundToInt())
                    }
                }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp)
                    .size(width = if (dragging) 10.dp else 7.dp, height = thumbH)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 1f else 0.75f),
                        RoundedCornerShape(5.dp),
                    )
            )
        }
    }
}

// ---------- Vollbild mit Zoom ----------

@Composable
private fun ZoomDialog(p: Photo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        off = if (scale <= 1f) Offset.Zero else off + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                off = Offset.Zero
                            } else scale = 2.5f
                        },
                        onTap = { onDismiss() },
                    )
                }
        ) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(p.uri).size(4096).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = off.x
                        translationY = off.y
                    },
            )
            Text(
                "${p.name}\n${dateStr(p.dateTaken)} · ${sizeStr(p.size)}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .statusBarsPadding()
                    .padding(10.dp),
            )
            Text(
                "Zwei Finger = zoomen · Doppeltipp = 2,5× · Tippen = schließen",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .navigationBarsPadding()
                    .padding(8.dp),
            )
        }
    }
}
