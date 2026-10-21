package de.butte.fotoaufraeumer

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Kennwerte eines Fotos: Bild-Fingerabdruck, Schärfe, Helligkeit. */
data class Features(val dhash: Long, val blur: Float, val bright: Float)

data class QualityItem(val photo: Photo, val reason: String, val score: Float)

object Analyzer {
    fun analyze(ctx: Context, p: Photo): Features? {
        var bmp: Bitmap = try {
            ctx.contentResolver.loadThumbnail(p.uri, Size(320, 320), null)
        } catch (e: Throwable) {
            return null
        }
        if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        return try {
            val (blur, bright) = sharpness(bmp)
            Features(dHash(bmp), blur, bright)
        } catch (e: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    private fun gray(c: Int): Float =
        ((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f

    /** 64-Bit-Differenz-Hash: ähnliche Bilder haben ähnliche Bitmuster. */
    private fun dHash(bmp: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        val px = IntArray(72)
        s.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (s !== bmp) s.recycle()
        var h = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (gray(px[y * 9 + x]) > gray(px[y * 9 + x + 1])) h = h or (1L shl bit)
                bit++
            }
        }
        return h
    }

    fun sharpnessOf(bmp: Bitmap): Float = sharpness(bmp).first

    /** Schärfe (Varianz des Laplace-Filters) und mittlere Helligkeit. */
    private fun sharpness(bmp: Bitmap): Pair<Float, Float> {
        val w = 256
        val h = min(1024, max(8, (bmp.height * 256f / max(1, bmp.width)).roundToInt()))
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== bmp) s.recycle()
        val g = FloatArray(w * h) { gray(px[it]) }
        var sumB = 0.0
        for (v in g) sumB += v
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        val mean = if (n > 0) sum / n else 0.0
        val variance = if (n > 0) sumSq / n - mean * mean else 0.0
        return Pair(variance.toFloat(), (sumB / g.size).toFloat())
    }
}

object Grouper {
    /** Exakt gleiche Dateien (gleiche Größe + gleicher Inhalt). */
    fun duplicates(ctx: Context, all: List<Photo>): List<List<Photo>> {
        val out = ArrayList<List<Photo>>()
        for ((size, g) in all.groupBy { it.size }) {
            if (size <= 0 || g.size < 2) continue
            val byHash = g.mapNotNull { p -> MediaRepo.md5(ctx, p.uri)?.let { it to p } }
                .groupBy({ it.first }, { it.second })
            for (grp in byHash.values) if (grp.size > 1) out += grp.sortedBy { it.dateTaken }
        }
        return out.sortedByDescending { it.first().size * (it.size - 1) }
    }

    /** Ähnliche Fotos über den Hash-Abstand gruppieren. */
    fun similar(items: List<Photo>, feats: Map<Long, Features>, threshold: Int): List<List<Photo>> {
        val list = items.filter { p ->
            val f = feats[p.id] ?: return@filter false
            val bits = java.lang.Long.bitCount(f.dhash)
            bits in 6..58 // einfarbige/leere Bilder ausschließen
        }
        val n = list.size
        val hashes = LongArray(n) { feats[list[it].id]!!.dhash }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var y = x
            while (parent[y] != r) {
                val nx = parent[y]
                parent[y] = r
                y = nx
            }
            return r
        }
        for (i in 0 until n) {
            val hi = hashes[i]
            for (j in i + 1 until n) {
                if (java.lang.Long.bitCount(hi xor hashes[j]) <= threshold) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[a] = b
                }
            }
        }
        return (0 until n).groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { g -> g.map { list[it] }.sortedBy { it.dateTaken } }
            .sortedByDescending { it.size }
    }

    fun quality(items: List<Photo>, feats: Map<Long, Features>, blurLimit: Float): List<QualityItem> {
        val out = ArrayList<QualityItem>()
        for (p in items) {
            val f = feats[p.id] ?: continue
            when {
                f.blur < blurLimit -> out += QualityItem(p, "Unscharf (${f.blur.roundToInt()})", f.blur)
                f.bright < 35f -> out += QualityItem(p, "Zu dunkel", 10000f + f.bright)
                f.bright > 225f -> out += QualityItem(p, "Überbelichtet", 20000f + (255f - f.bright))
            }
        }
        return out.sortedBy { it.score }
    }
}

/** Zwischenspeicher, damit ein zweiter Scan nur neue Fotos analysiert. */
object FeatureCache {
    data class Entry(val mod: Long, val size: Long, val f: Features)

    private fun file(ctx: Context) = File(ctx.filesDir, "features_v1.txt")

    fun load(ctx: Context): Map<Long, Entry> {
        val map = HashMap<Long, Entry>()
        try {
            file(ctx).takeIf { it.exists() }?.forEachLine { line ->
                val p = line.split(';')
                if (p.size == 6) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    val e = Entry(
                        p[1].toLongOrNull() ?: 0,
                        p[2].toLongOrNull() ?: 0,
                        Features(p[3].toLongOrNull() ?: 0, p[4].toFloatOrNull() ?: 0f, p[5].toFloatOrNull() ?: 0f),
                    )
                    map[id] = e
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    fun save(ctx: Context, map: Map<Long, Entry>) {
        try {
            file(ctx).bufferedWriter().use { w ->
                for ((id, e) in map) {
                    w.write("$id;${e.mod};${e.size};${e.f.dhash};${e.f.blur};${e.f.bright}\n")
                }
            }
        } catch (_: Exception) {
        }
    }
}
