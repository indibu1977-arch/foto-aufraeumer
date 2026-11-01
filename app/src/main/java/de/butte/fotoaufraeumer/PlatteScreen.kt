@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:Suppress("UnsafeOptInUsageError")

package de.butte.fotoaufraeumer

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Zustand des Platten-Browsers (Dateien auf der Nextcloud-Platte am Home Assistant). */
class PlatteState(private val vm: AppVM) {
    private val ctx get() = vm.getApplication<Application>()

    private val sp get() = ctx.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)

    /** Ordner, in dem die Platte startet (Standard: „Dirk“ = die WD). */
    var startFolder by mutableStateOf(
        vm.getApplication<Application>().getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
            .getString("platte_start", "/Dirk") ?: "/Dirk"
    )
        private set

    var path by mutableStateOf(startFolder)
        private set
    var entries by mutableStateOf(listOf<DavEntry>())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var remote by mutableStateOf(false)
        private set
    /** Laufende Übertragung (Text für die Anzeige), null = nichts läuft. */
    var transfer by mutableStateOf<String?>(null)
        private set
    val selected = mutableStateListOf<String>()
    private var loaded = false

    val folderName: String get() = path.trim('/').substringAfterLast('/').ifEmpty { "Platte" }

    fun ensureLoaded() { if (!loaded) open(path) }

    fun open(p: String) {
        path = "/" + p.trim('/')
        selected.clear()
        reload()
    }

    fun up() = open(path.trim('/').substringBeforeLast('/', ""))

    /** Aktuellen Ordner als Startordner merken. */
    fun pinStart() {
        startFolder = path
        sp.edit().putString("platte_start", path).apply()
        vm.message = "Startordner: ${if (path == "/") "Hauptordner" else folderName}"
    }

    /** Hauptordner der Nextcloud liegt auf der SD-Karte des Pi, nicht auf der externen Platte. */
    val isRoot: Boolean get() = path == "/"

    fun reload() {
        val cfg = vm.dav
        val want = path
        appScope.launch {
            loading = true
            error = null
            var fallback = false
            try {
                val list = withContext(Dispatchers.IO) { Dav.base(cfg); Dav.list(cfg, want) }
                if (path == want) { entries = list; loaded = true }
            } catch (e: Exception) {
                Dav.resetBase()
                if (!loaded && want == startFolder && want != "/" && e is DavException && e.message?.contains("nicht gefunden") == true) {
                    // Startordner gibt es (noch) nicht – dann im Hauptordner beginnen
                    fallback = path == want
                } else if (path == want) {
                    entries = emptyList()
                    error = e.message ?: e.javaClass.simpleName
                }
            } finally {
                remote = Dav.usingRemote
                loading = false
            }
            if (fallback) open("/")
        }
    }

    fun toggle(e: DavEntry) { if (e.path in selected) selected.remove(e.path) else selected.add(e.path) }

    private suspend fun <T> retry(label: String, block: suspend () -> T): T {
        var wait = 3_000L
        for (attempt in 1..6) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                if (attempt == 6 || e is javax.net.ssl.SSLHandshakeException) throw e
                Dav.resetBase()
                transfer = "$label\nVerbindung weg – neuer Versuch in ${wait / 1000} s"
                delay(wait)
                wait = minOf(wait * 2, 60_000L)
            }
        }
        error("nicht erreichbar")
    }

    private fun finish(msg: String) {
        vm.message = msg
        if (!AppState.foreground) UploadService.done(ctx, msg)
    }

    // ---------- Hochladen ----------

    private fun meta(u: Uri): Pair<String, Long> {
        var name = u.lastPathSegment?.substringAfterLast('/') ?: "Datei"
        var size = -1L
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        return name to size
    }

    private fun deleteLocal(u: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, u)
    } catch (_: Exception) { false }

    /** Dateien vom Handy in den aktuellen Ordner laden. move = danach auf dem Handy löschen. */
    fun upload(uris: List<Uri>, move: Boolean) {
        if (uris.isEmpty()) return
        if (transfer != null) { vm.message = "Es läuft schon eine Übertragung …"; return }
        val folder = path
        val cfg = vm.dav
        val n = uris.size
        transfer = "Hochladen …"
        appScope.launch {
            var ok = 0
            var fail = 0
            var notDel = 0
            var lastErr: String? = null
            UploadService.start(ctx, "Lade $n Dateien hoch …")
            try {
                for ((i, u) in uris.withIndex()) {
                    val (name, size) = withContext(Dispatchers.IO) { meta(u) }
                    val label = "Hochladen ${i + 1}/$n: $name"
                    transfer = label
                    UploadService.progress(ctx, label, i, n)
                    try {
                        retry(label) { withContext(Dispatchers.IO) { Dav.upload(ctx, cfg, folder, name, u, size) } }
                        ok++
                        if (move && !withContext(Dispatchers.IO) { deleteLocal(u) }) notDel++
                    } catch (e: Exception) {
                        fail++
                        lastErr = e.message ?: e.javaClass.simpleName
                        if (e is java.io.IOException) { fail += n - i - 1; break }
                    }
                }
            } finally {
                transfer = null
                UploadService.stop(ctx)
            }
            finish(buildString {
                append("$ok von $n hochgeladen")
                if (move && ok > 0) append(if (notDel > 0) ", $notDel blieben auf dem Handy (nicht löschbar)" else ", vom Handy entfernt")
                if (fail > 0) append(", $fail fehlgeschlagen")
                lastErr?.let { append(" – $it") }
            })
            if (path == folder) reload()
        }
    }

    // ---------- Herunterladen ----------

    private fun saveToDownloads(cfg: DavCfg, e: DavEntry, onBytes: (Long) -> Unit): Uri {
        val r = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, e.name)
            put(MediaStore.MediaColumns.MIME_TYPE, e.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Platte")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw DavException("Speichern auf dem Handy nicht möglich")
        try {
            (r.openOutputStream(uri) ?: throw DavException("Speichern nicht möglich")).use { Dav.download(cfg, e.path, it, onBytes) }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (ex: Exception) {
            try { r.delete(uri, null, null) } catch (_: Exception) {}
            throw ex
        }
        return uri
    }

    /** Dateien nach „Download/Platte“ auf dem Handy laden. open = danach mit passender App öffnen. */
    fun download(list: List<DavEntry>, open: Boolean = false) {
        val files = list.filter { !it.isDir }
        if (files.isEmpty()) { vm.message = "Ordner bitte einzeln öffnen – nur Dateien werden geladen"; return }
        if (transfer != null) { vm.message = "Es läuft schon eine Übertragung …"; return }
        val cfg = vm.dav
        val n = files.size
        transfer = "Herunterladen …"
        appScope.launch {
            var ok = 0
            var fail = 0
            var lastErr: String? = null
            var lastUri: Uri? = null
            UploadService.start(ctx, "Lade $n Dateien herunter …", UploadService.TITLE_DOWN)
            try {
                for ((i, e) in files.withIndex()) {
                    val head = "Herunterladen ${i + 1}/$n: ${e.name}"
                    transfer = head
                    UploadService.progress(ctx, head, i, n, UploadService.TITLE_DOWN)
                    try {
                        lastUri = retry(head) {
                            withContext(Dispatchers.IO) {
                                saveToDownloads(cfg, e) { b ->
                                    val t = if (e.size > 0) "$head\n${sizeStr(b)} von ${sizeStr(e.size)}" else "$head\n${sizeStr(b)}"
                                    transfer = t
                                }
                            }
                        }
                        ok++
                    } catch (ex: Exception) {
                        fail++
                        lastErr = ex.message ?: ex.javaClass.simpleName
                        if (ex is java.io.IOException) { fail += n - i - 1; break }
                    }
                }
            } finally {
                transfer = null
                UploadService.stop(ctx)
            }
            selected.clear()
            finish(buildString {
                append("$ok von $n gespeichert in „Download/Platte“")
                if (fail > 0) append(", $fail fehlgeschlagen")
                lastErr?.let { append(" – $it") }
            })
            val u = lastUri
            if (open && u != null && ok == 1 && AppState.foreground) {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(u, files[0].mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    vm.message = "Gespeichert – keine App zum Öffnen gefunden"
                }
            }
        }
    }

    // ---------- Ordner & Löschen ----------

    fun mkdir(name: String) {
        val n = name.trim().replace("/", "")
        if (n.isEmpty()) return
        val cfg = vm.dav
        val p = path.trimEnd('/') + "/" + n
        appScope.launch {
            try {
                withContext(Dispatchers.IO) { Dav.mkdir(cfg, p) }
                reload()
            } catch (e: Exception) {
                vm.message = "Ordner anlegen: ${e.message}"
            }
        }
    }

    fun deleteSelected() {
        val paths = selected.toList()
        if (paths.isEmpty()) return
        val cfg = vm.dav
        appScope.launch {
            var ok = 0
            var err: String? = null
            for (p in paths) {
                try { withContext(Dispatchers.IO) { Dav.delete(cfg, p) }; ok++ } catch (e: Exception) { err = e.message }
            }
            selected.clear()
            vm.message = "$ok gelöscht (Nextcloud-Papierkorb)" + (err?.let { " – Fehler: $it" } ?: "")
            reload()
        }
    }
}

// ======================= Oberfläche =======================

@Composable
private fun rememberDavLoader(vm: AppVM): ImageLoader {
    val ctx = LocalContext.current
    val cfg = vm.dav
    return remember(cfg) { ImageLoader.Builder(ctx).okHttpClient { Dav.http(cfg) }.crossfade(true).build() }
}

@Composable
private fun DavImage(vm: AppVM, loader: ImageLoader, url: String, scale: ContentScale, modifier: Modifier) {
    val ctx = LocalContext.current
    val cfg = vm.dav
    val req = remember(url) {
        ImageRequest.Builder(ctx).data(url).addHeader("Authorization", Dav.auth(cfg)).build()
    }
    AsyncImage(model = req, imageLoader = loader, contentDescription = null, contentScale = scale, modifier = modifier)
}

private fun iconFor(e: DavEntry) = when {
    e.isDir -> "📁"
    e.isImage -> "🖼️"
    e.isVideo -> "🎬"
    e.isAudio -> "🎵"
    e.name.endsWith(".pdf", true) -> "📕"
    else -> "📄"
}

@Composable
fun PlatteScreen(vm: AppVM) {
    val st = vm.platte
    val loader = rememberDavLoader(vm)
    var askMove by remember { mutableStateOf<List<Uri>?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var askFolder by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) askMove = uris
    }
    LaunchedEffect(Unit) { if (vm.dav.ok) st.ensureLoaded() }

    BackHandler(enabled = st.selected.isNotEmpty() || st.path != "/") {
        if (st.selected.isNotEmpty()) st.selected.clear() else st.up()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (st.selected.isNotEmpty()) "${st.selected.size} ausgewählt" else st.folderName,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (st.remote) "🌍 unterwegs (Tailscale)" else "🏠 zuhause") + " · " + st.path,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    BackButton {
                        when {
                            st.selected.isNotEmpty() -> st.selected.clear()
                            st.path != "/" -> st.up()
                            else -> vm.back()
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { st.pinStart() }, enabled = vm.dav.ok && st.path != st.startFolder) { Text("📌", maxLines = 1) }
                    TextButton(onClick = { askFolder = true }, enabled = vm.dav.ok) { Text("＋ Ordner", maxLines = 1) }
                    TextButton(onClick = { Dav.resetBase(); st.reload() }, enabled = vm.dav.ok) { Text("⟳") }
                },
            )
        },
        floatingActionButton = {
            if (vm.dav.ok && st.selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    text = { Text("⬆ Hochladen") },
                    icon = {},
                )
            }
        },
        bottomBar = {
            if (st.selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { st.download(st.entries.filter { it.path in st.selected }) },
                            modifier = Modifier.weight(1f),
                        ) { Text("⬇ Aufs Handy", maxLines = 1) }
                        OutlinedButton(onClick = { askDelete = true }, modifier = Modifier.weight(1f)) {
                            Text("🗑 Löschen", maxLines = 1)
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.dav.ok) {
                Card(
                    onClick = { vm.go(Screen.Settings) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        "Noch keine Verbindung zur Platte. Hier antippen und unter „Direkt auf die Platte (Nextcloud)“ Server, Benutzer und App-Passwort eintragen.",
                        Modifier.padding(16.dp),
                    )
                }
                return@Column
            }
            st.transfer?.let { t ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Läuft auch weiter, wenn du die App verlässt.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (st.isRoot && st.entries.any { it.isDir && it.name == "Dirk" }) {
                Card(
                    onClick = { st.open("/Dirk") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        "Hauptordner = SD-Karte. Deine Festplatte ist der Ordner „Dirk“ – hier antippen.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            st.error?.let { err ->
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("✗ $err", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Unterwegs: Ist Tailscale auf dem Handy eingeschaltet? Die Unterwegs-Adresse steht in den Einstellungen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { Dav.resetBase(); st.reload() }) { Text("Nochmal versuchen") }
                }
            }
            if (!st.loading && st.error == null && st.entries.isEmpty()) {
                Text(
                    "Ordner ist leer. Mit „⬆ Hochladen“ Dateien vom Handy hierher schieben.",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val images = remember(st.entries) { st.entries.filter { it.isImage } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(st.entries, key = { it.path }) { e ->
                    val sel = e.path in st.selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    when {
                                        st.selected.isNotEmpty() -> st.toggle(e)
                                        e.isDir -> st.open(e.path)
                                        e.isImage -> vm.go(Screen.PlatteView(images, images.indexOf(e).coerceAtLeast(0)))
                                        e.isVideo || e.isAudio -> vm.go(Screen.PlattePlay(e))
                                        else -> st.download(listOf(e), open = true)
                                    }
                                },
                                onLongClick = { st.toggle(e) },
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (sel) "✓" else iconFor(e), fontSize = 24.sp)
                            if (!sel && (e.isImage || e.isVideo)) {
                                DavImage(vm, loader, Dav.previewUrl(vm.dav, e.path), ContentScale.Crop, Modifier.fillMaxSize())
                                if (e.isVideo) Text("▶", color = Color.White, fontSize = 20.sp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (e.isDir) FontWeight.SemiBold else FontWeight.Normal)
                            val sub = listOfNotNull(
                                if (!e.isDir) sizeStr(e.size) else null,
                                if (e.modified > 0) dateStr(e.modified) else null,
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!e.isDir && st.selected.isEmpty()) {
                            TextButton(onClick = { st.download(listOf(e)) }) { Text("⬇") }
                        }
                    }
                }
            }
        }
    }

    askMove?.let { uris ->
        AlertDialog(
            onDismissRequest = { askMove = null },
            title = { Text("${uris.size} Dateien nach „${st.folderName}“") },
            text = {
                Column {
                    if (st.isRoot) {
                        Text(
                            "⚠ Das ist der Hauptordner – er liegt auf der SD-Karte des Home Assistant, nicht auf deiner Festplatte. " +
                                "Für die WD vorher den Ordner „Dirk“ öffnen.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Kopie: Die Dateien bleiben zusätzlich auf dem Handy.\nVerschieben: Nach erfolgreichem Hochladen werden sie auf dem Handy gelöscht.")
                }
            },
            confirmButton = {
                TextButton(onClick = { askMove = null; st.upload(uris, move = true) }) { Text("Verschieben") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { askMove = null }) { Text("Abbrechen") }
                    TextButton(onClick = { askMove = null; st.upload(uris, move = false) }) { Text("Kopie") }
                }
            },
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("${st.selected.size} auf der Platte löschen?") },
            text = { Text("Sie landen im Nextcloud-Papierkorb und lassen sich dort wiederherstellen.") },
            confirmButton = { TextButton(onClick = { askDelete = false; st.deleteSelected() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (askFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askFolder = false },
            title = { Text("Neuer Ordner in „${st.folderName}“") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { askFolder = false; st.mkdir(name) }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { askFolder = false }) { Text("Abbrechen") } },
        )
    }
}

/** Fotos auf der Platte ansehen – wischen zum nächsten Bild. */
@Composable
fun PlatteViewScreen(vm: AppVM, folder: List<DavEntry>, start: Int) {
    val loader = rememberDavLoader(vm)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, maxOf(0, folder.size - 1))) { folder.size }
    val cur = folder.getOrNull(pager.currentPage)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(cur?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} von ${folder.size}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { BackButton { vm.back() } },
                actions = {
                    TextButton(onClick = { cur?.let { vm.platte.download(listOf(it)) } }) { Text("⬇ Aufs Handy", maxLines = 1) }
                },
            )
        }
    ) { pad ->
        HorizontalPager(pager, Modifier.padding(pad).fillMaxSize().background(Color.Black)) { i ->
            val e = folder[i]
            // Große Vorschau statt Original – lädt unterwegs viel schneller
            DavImage(vm, loader, Dav.previewUrl(vm.dav, e.path, 2048).replace("&mode=cover", ""), ContentScale.Fit, Modifier.fillMaxSize())
        }
    }
}

/** Video oder Musik direkt von der Platte abspielen (streamen, ohne Herunterladen). */
@Composable
fun PlattePlayScreen(vm: AppVM, e: DavEntry) {
    val ctx = LocalContext.current
    val cfg = vm.dav
    val player = remember(e.path) {
        val ds = OkHttpDataSource.Factory(Dav.http(cfg))
            .setDefaultRequestProperties(mapOf("Authorization" to Dav.auth(cfg)))
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Dav.fileUrl(cfg, e.path)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton { vm.back() } },
                actions = {
                    TextButton(onClick = { vm.platte.download(listOf(e)) }) { Text("⬇ Aufs Handy", maxLines = 1) }
                },
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true } },
                modifier = Modifier.fillMaxSize(),
            )
            if (e.isAudio) Text("🎵", fontSize = 64.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
        }
    }
}
