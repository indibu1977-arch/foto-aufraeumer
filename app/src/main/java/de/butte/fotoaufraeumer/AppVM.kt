package de.butte.fotoaufraeumer

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

enum class Dir(val label: String, val arrow: String) {
    LEFT("Links", "⬅"), UP("Oben", "⬆"), RIGHT("Rechts", "➡"), DOWN("Unten", "⬇")
}

sealed interface Screen {
    data object Home : Screen
    data object Scan : Screen
    data object SwipePick : Screen
    data object Swipe : Screen
    data object Settings : Screen
    data class Enhance(val photo: Photo) : Screen
    data object Vault : Screen
    data class Erase(val photo: Photo) : Screen
    data object Edited : Screen
    data class Compare(val before: Photo, val after: Photo, val free: Boolean = false) : Screen
    data object PickCompare : Screen
    data object Gallery : Screen
}

class IntentReq(val sender: IntentSender, val result: CompletableDeferred<Boolean>)

class AppVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val sp = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    // ---------- Navigation ----------
    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
        private set
    val screen: Screen get() = stack.last()
    fun go(s: Screen) { stack = stack + s }
    fun replaceTop(s: Screen) { stack = stack.dropLast(1) + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }

    // ---------- Allgemein ----------
    var hasPermission by mutableStateOf(false)
        private set
    var canManageMedia by mutableStateOf(false)
        private set
    var photos by mutableStateOf(listOf<Photo>())
        private set
    var message by mutableStateOf<String?>(null)
    var intentReq by mutableStateOf<IntentReq?>(null)
        private set

    private suspend fun ask(pi: PendingIntent): Boolean {
        val d = CompletableDeferred<Boolean>()
        intentReq = IntentReq(pi.intentSender, d)
        return d.await()
    }

    fun onIntentResult(ok: Boolean) {
        val r = intentReq
        intentReq = null
        r?.result?.complete(ok)
    }

    fun onResume() {
        canManageMedia = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(ctx)
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (!hasPermission && ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onPermission(true)
        }
    }

    fun onPermission(granted: Boolean) {
        hasPermission = granted
        if (granted) reload()
    }

    fun reload() {
        viewModelScope.launch {
            photos = withContext(Dispatchers.IO) { MediaRepo.loadAll(ctx) }
        }
    }

    // ---------- Wischrichtungen ----------
    var targets by mutableStateOf(Dir.entries.associateWith { sp.getString("target_${it.name}", null) })
        private set

    fun setTarget(d: Dir, path: String?) {
        sp.edit().apply {
            if (path == null) remove("target_${d.name}") else putString("target_${d.name}", path)
        }.apply()
        targets = targets + (d to path)
    }

    fun dirLabel(d: Dir): String = when {
        d == Dir.DOWN -> "${d.arrow} Löschen"
        targets[d] != null -> "${d.arrow} ${targetName(targets[d]!!)}"
        else -> "${d.arrow} frei"
    }

    fun targetName(path: String): String =
        if (path == Vault.TARGET) "🔒 Privat" else MediaRepo.folderName(path)

    // Auswahl für „Zwei Fotos vergleichen“ (bleibt beim Zurückkehren erhalten)
    var comparePick by mutableStateOf(listOf<Photo>())
    var compareAlbum by mutableStateOf<String?>(null)

    // ---------- Scan ----------
    var scanning by mutableStateOf(false)
        private set
    var scanDone by mutableStateOf(false)
        private set
    var scanProgress by mutableFloatStateOf(0f)
        private set
    var scanText by mutableStateOf("")
        private set
    private var features: Map<Long, Features> = emptyMap()
    var dupGroups by mutableStateOf(listOf<List<Photo>>())
        private set
    var simGroups by mutableStateOf(listOf<List<Photo>>())
        private set
    var quality by mutableStateOf(listOf<QualityItem>())
        private set
    var simLevel by mutableIntStateOf(1)
        private set
    var blurLevel by mutableIntStateOf(1)
        private set
    var selection by mutableStateOf(setOf<Long>())
        private set

    private val simThresholds = intArrayOf(5, 9, 13)
    private val blurThresholds = floatArrayOf(40f, 90f, 160f)

    fun startScan() {
        if (scanning) return
        scanning = true
        scanProgress = 0f
        scanText = "Lade Fotoliste …"
        viewModelScope.launch {
            try {
                val all = withContext(Dispatchers.IO) { MediaRepo.loadAll(ctx) }
                photos = all
                val cache = withContext(Dispatchers.IO) { FeatureCache.load(ctx) }
                val feats = ConcurrentHashMap<Long, Features>()
                val todo = ArrayList<Photo>()
                for (p in all) {
                    val e = cache[p.id]
                    if (e != null && e.mod == p.dateModified && e.size == p.size) feats[p.id] = e.f else todo.add(p)
                }
                val total = all.size
                val done = AtomicInteger(total - todo.size)
                val idx = AtomicInteger(0)
                val ticker = launch {
                    while (true) {
                        scanProgress = done.get().toFloat() / max(1, total)
                        scanText = "Analysiere Fotos … ${done.get()} von $total"
                        delay(250)
                    }
                }
                val workers = List(4) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val i = idx.getAndIncrement()
                            if (i >= todo.size) break
                            val p = todo[i]
                            Analyzer.analyze(ctx, p)?.let { feats[p.id] = it }
                            done.incrementAndGet()
                        }
                    }
                }
                workers.joinAll()
                ticker.cancel()
                features = HashMap(feats)
                withContext(Dispatchers.IO) {
                    val newCache = HashMap<Long, FeatureCache.Entry>()
                    for (p in all) feats[p.id]?.let { newCache[p.id] = FeatureCache.Entry(p.dateModified, p.size, it) }
                    FeatureCache.save(ctx, newCache)
                }
                scanProgress = 1f
                scanText = "Suche exakte Duplikate …"
                dupGroups = withContext(Dispatchers.IO) { Grouper.duplicates(ctx, all) }
                scanText = "Suche ähnliche Fotos …"
                recomputeSimilar()
                recomputeQuality()
                selection = emptySet()
                scanDone = true
            } catch (e: Exception) {
                message = "Fehler beim Durchsuchen: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    private suspend fun recomputeSimilar() {
        val dupExtra = dupGroups.flatMap { it.drop(1) }.map { it.id }.toSet()
        val cands = photos.filter { it.id !in dupExtra }
        val th = simThresholds[simLevel]
        val f = features
        simGroups = withContext(Dispatchers.Default) { Grouper.similar(cands, f, th) }
    }

    private suspend fun recomputeQuality() {
        val f = features
        val lim = blurThresholds[blurLevel]
        val p = photos
        quality = withContext(Dispatchers.Default) { Grouper.quality(p, f, lim) }
    }

    fun changeSimLevel(l: Int) {
        simLevel = l
        viewModelScope.launch { recomputeSimilar() }
    }

    fun changeBlurLevel(l: Int) {
        blurLevel = l
        viewModelScope.launch { recomputeQuality() }
    }

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() { selection = emptySet() }

    fun selectAll(ids: List<Long>) { selection = selection + ids }

    /** In jeder Gruppe das beste Foto behalten, den Rest auswählen. */
    fun autoSelect(groups: List<List<Photo>>, keepSharpest: Boolean) {
        val sel = selection.toMutableSet()
        for (g in groups) {
            val keep = if (keepSharpest) {
                g.maxWith(compareBy<Photo>({ features[it.id]?.blur ?: 0f }, { it.width.toLong() * it.height }))
            } else g.first()
            for (p in g) if (p.id == keep.id) sel -= p.id else sel += p.id
        }
        selection = sel
    }

    fun deleteSelected() {
        val sel = selection
        if (sel.isEmpty()) return
        viewModelScope.launch {
            val uris = photos.filter { it.id in sel }.map { it.uri }
            if (uris.isEmpty()) return@launch
            val ok = try { ask(MediaRepo.trashRequest(ctx, uris)) } catch (e: Exception) { false }
            if (ok) {
                removeIds(sel)
                selection = emptySet()
                message = "${uris.size} Fotos in den Papierkorb verschoben"
            }
        }
    }

    private fun removeIds(ids: Set<Long>) {
        photos = photos.filterNot { it.id in ids }
        dupGroups = dupGroups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        simGroups = simGroups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        quality = quality.filterNot { it.photo.id in ids }
        selection = selection - ids
        galleryMarks = galleryMarks.filterNot { it in ids }
        if (swipeList.any { it.id in ids }) {
            val before = swipeList.take(swipeIndex).count { it.id in ids }
            swipeList = swipeList.filterNot { it.id in ids }
            queue = queue.filterNot { it.first.id in ids }
            swipeIndex = max(0, swipeIndex - before)
        }
    }

    // ---------- Galerie ----------
    /** Zum Löschen markierte Fotos – Reihenfolge wird für „Rückgängig“ gebraucht. */
    var galleryMarks by mutableStateOf(listOf<Long>())
        private set
    /** Aktuelle Position in der Galerie (bleibt beim Umschalten der Ansicht erhalten). */
    var galleryIndex by mutableIntStateOf(0)

    fun mark(id: Long) { if (id !in galleryMarks) galleryMarks = galleryMarks + id }
    fun unmark(id: Long) { galleryMarks = galleryMarks - id }
    fun toggleMark(id: Long) { if (id in galleryMarks) unmark(id) else mark(id) }
    fun undoMark() { galleryMarks = galleryMarks.dropLast(1) }
    fun clearMarks() { galleryMarks = emptyList() }

    fun deleteMarked(thenBack: Boolean = false) {
        val ids = galleryMarks.toSet()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val uris = photos.filter { it.id in ids }.map { it.uri }
            if (uris.isEmpty()) {
                galleryMarks = emptyList()
                return@launch
            }
            val ok = try { ask(MediaRepo.trashRequest(ctx, uris)) } catch (e: Exception) { false }
            if (ok) {
                removeIds(ids)
                galleryMarks = emptyList()
                message = "${uris.size} Fotos in den Papierkorb verschoben"
                if (thenBack) back()
            }
        }
    }

    // ---------- Wisch-Sortierer ----------
    var swipeSource by mutableStateOf<String?>(null)
        private set
    var swipeList by mutableStateOf(listOf<Photo>())
        private set
    var swipeIndex by mutableIntStateOf(0)
        private set
    var queue by mutableStateOf(listOf<Pair<Photo, Dir?>>())
        private set
    private val seen = HashSet<Long>()

    val pendingCount: Int get() = queue.count { it.second != null }
    val swipeSourceName: String get() = swipeSource?.let { MediaRepo.folderName(it) } ?: "Alle Fotos"

    fun startSwipe(path: String?) {
        swipeSource = path
        queue = emptyList()
        buildSwipeList()
        go(Screen.Swipe)
    }

    private fun buildSwipeList() {
        val src = swipeSource
        swipeList = photos.filter { (src == null || it.relPath == src) && it.id !in seen }
        swipeIndex = 0
    }

    fun restartSwipe() {
        val src = swipeSource
        seen.removeAll(photos.filter { src == null || it.relPath == src }.map { it.id }.toSet())
        buildSwipeList()
    }

    /** dir == null bedeutet „Behalten". */
    fun swipe(dir: Dir?): Boolean {
        val p = swipeList.getOrNull(swipeIndex) ?: return false
        if (dir != null && dir != Dir.DOWN && targets[dir] == null) {
            message = "Richtung „${dir.label}“ hat noch keinen Ordner – in den Einstellungen festlegen"
            return false
        }
        if (dir != null && dir != Dir.DOWN && targets[dir] == p.relPath) {
            // liegt schon im Zielordner
            queue = queue + (p to null)
        } else {
            queue = queue + (p to dir)
        }
        swipeIndex++
        return true
    }

    fun undo() {
        if (queue.isEmpty()) return
        queue = queue.dropLast(1)
        swipeIndex = max(0, swipeIndex - 1)
    }

    fun discardSwipe() {
        queue = emptyList()
        back()
    }

    fun applySwipe(thenBack: Boolean = false) {
        val q = queue
        viewModelScope.launch {
            val dels = q.filter { it.second == Dir.DOWN }.map { it.first }
            val t0 = targets
            val all = q.filter { it.second != null && it.second != Dir.DOWN }
            val privat = all.filter { t0[it.second!!] == Vault.TARGET }.map { it.first }
            val moves = all.filter { t0[it.second!!] != Vault.TARGET }
            var trashed = 0
            var moved = 0
            var hidden = 0
            var failed = 0
            val notDone = HashSet<Long>()
            if (dels.isNotEmpty()) {
                val ok = try { ask(MediaRepo.trashRequest(ctx, dels.map { it.uri })) } catch (e: Exception) { false }
                if (ok) trashed = dels.size else notDone += dels.map { it.id }
            }
            if (moves.isNotEmpty()) {
                val ok = try { ask(MediaRepo.writeRequest(ctx, moves.map { it.first.uri })) } catch (e: Exception) { false }
                if (ok) {
                    val t = targets
                    withContext(Dispatchers.IO) {
                        for ((p, d) in moves) {
                            val target = t[d!!]
                            if (target != null && MediaRepo.move(ctx, p.uri, target)) moved++ else failed++
                        }
                    }
                } else {
                    notDone += moves.map { it.first.id }
                }
            }
            if (privat.isNotEmpty()) {
                // 1. Kopie in den privaten Ordner, 2. Original endgültig löschen (nicht Papierkorb – dort wäre es sichtbar)
                val copied = withContext(Dispatchers.IO) {
                    privat.mapNotNull { p -> Vault.importPhoto(ctx, p)?.let { p to it } }
                }
                failed += privat.size - copied.size
                notDone += privat.filter { p -> copied.none { it.first.id == p.id } }.map { it.id }
                if (copied.isNotEmpty()) {
                    val ok = try { ask(MediaRepo.deleteRequest(ctx, copied.map { it.first.uri })) } catch (e: Exception) { false }
                    if (ok) {
                        hidden = copied.size
                    } else {
                        withContext(Dispatchers.IO) { copied.forEach { it.second.delete() } }
                        notDone += copied.map { it.first.id }
                    }
                }
                refreshVault()
            }
            for ((p, _) in q) if (p.id !in notDone) seen += p.id
            queue = emptyList()
            photos = withContext(Dispatchers.IO) { MediaRepo.loadAll(ctx) }
            buildSwipeList()
            message = buildString {
                append("Erledigt: $trashed gelöscht, $moved verschoben")
                if (hidden > 0) append(", $hidden privat")
                if (failed > 0) append(", $failed fehlgeschlagen")
                if (notDone.isNotEmpty()) append(", ${notDone.size} abgebrochen")
            }
            if (thenBack) back()
        }
    }

    // ---------- Verbessern ----------
    var saving by mutableStateOf(false)
        private set
    var savedFor by mutableStateOf<Long?>(null)
        private set
    private var savedCopyId: Long? = null

    /** Gerade gespeicherte Kopie mit dem Original vergleichen. */
    fun compareSaved(orig: Photo) {
        val c = photos.firstOrNull { it.id == savedCopyId }
        if (c == null) message = "Kopie nicht gefunden" else go(Screen.Compare(orig, c))
    }

    /** Ein Foto in den Papierkorb und zurück zum vorigen Bildschirm. */
    fun trashOne(p: Photo, msg: String) {
        viewModelScope.launch {
            val ok = try { ask(MediaRepo.trashRequest(ctx, listOf(p.uri))) } catch (e: Exception) { false }
            if (ok) {
                removeIds(setOf(p.id))
                if (savedFor == p.id) savedFor = null
                message = msg
                back()
            }
        }
    }

    fun saveEnhanced(p: Photo, a: Adjust) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                try {
                    val full = Enhancer.decode(ctx, p.uri, 4096)
                    val out = Enhancer.apply(full, a)
                    full.recycle()
                    val u = MediaRepo.saveCopy(ctx, out, p)
                    out.recycle()
                    u
                } catch (e: Throwable) {
                    null
                }
            }
            saving = false
            if (uri == null) {
                message = "Speichern fehlgeschlagen"
            } else {
                savedFor = p.id
                savedCopyId = try { android.content.ContentUris.parseId(uri) } catch (e: Exception) { null }
                message = "Als Kopie gespeichert (…_verbessert.jpg)"
                photos = withContext(Dispatchers.IO) { MediaRepo.loadAll(ctx) }
            }
        }
    }

    /** Schrift in voller Größe entfernen und als Kopie speichern. overlay = Maske in Vorschaugröße. */
    fun saveErased(p: Photo, overlay: Bitmap) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                try {
                    val full = Enhancer.decode(ctx, p.uri, 4096)
                    val big = Bitmap.createScaledBitmap(overlay, full.width, full.height, true)
                    overlay.recycle()
                    val mask = overlayToMask(big)
                    big.recycle()
                    val out = TextEraser.inpaint(full, mask)
                    full.recycle()
                    val u = MediaRepo.saveCopy(ctx, out, p, "_ohneText")
                    out.recycle()
                    u
                } catch (e: Throwable) {
                    null
                }
            }
            saving = false
            if (uri == null) {
                message = "Speichern fehlgeschlagen"
            } else {
                savedFor = p.id
                savedCopyId = try { android.content.ContentUris.parseId(uri) } catch (e: Exception) { null }
                message = "Als Kopie gespeichert (…_ohneText.jpg)"
                photos = withContext(Dispatchers.IO) { MediaRepo.loadAll(ctx) }
            }
        }
    }

    fun deleteOriginal(p: Photo) {
        viewModelScope.launch {
            val ok = try { ask(MediaRepo.trashRequest(ctx, listOf(p.uri))) } catch (e: Exception) { false }
            if (ok) {
                removeIds(setOf(p.id))
                savedFor = null
                message = "Original in den Papierkorb verschoben"
                back()
            }
        }
    }

    // ---------- Privater Ordner ----------
    var vaultItems by mutableStateOf(listOf<VaultItem>())
        private set
    var vaultUnlocked by mutableStateOf(false)
        private set
    val vaultHasPin: Boolean get() = Vault.hasPin(ctx)

    fun refreshVault() {
        viewModelScope.launch { vaultItems = withContext(Dispatchers.IO) { Vault.list(ctx) } }
    }

    fun unlockVault(pin: String, newPin: Boolean): Boolean {
        if (newPin) Vault.setPin(ctx, pin) else if (!Vault.checkPin(ctx, pin)) return false
        vaultUnlocked = true
        refreshVault()
        return true
    }

    /** Beim Verlassen der App oder des privaten Ordners wieder sperren. */
    fun lockVault() {
        vaultUnlocked = false
        if (screen == Screen.Vault) back()
    }

    fun restoreFromVault(items: List<VaultItem>) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { items.count { Vault.restore(ctx, it) != null } }
            refreshVault()
            photos = withContext(Dispatchers.IO) { MediaRepo.loadAll(ctx) }
            message = if (ok == items.size) "$ok zurück in die Galerie (Ordner „Wiederhergestellt“)"
            else "$ok von ${items.size} wiederhergestellt"
        }
    }

    fun deleteFromVault(items: List<VaultItem>) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { items.count { Vault.delete(it) } }
            refreshVault()
            message = "$ok endgültig gelöscht"
        }
    }
}
