package de.butte.fotoaufraeumer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ein aufgenommenes JPEG im Speicher. */
class Jpeg(val bytes: ByteArray, val rotation: Int)

object CamProcess {
    /** Höchstgröße für kombinierte Bilder (Stacking / HDR) – Speicher! */
    private const val MAX_COMBINE_PX = 12_500_000L

    fun decode(j: Jpeg, maxPx: Long = Long.MAX_VALUE): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(j.bytes, 0, j.bytes.size, o)
        var sample = 1
        while (o.outWidth.toLong() / sample * (o.outHeight.toLong() / sample) > maxPx) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val b = BitmapFactory.decodeByteArray(j.bytes, 0, j.bytes.size, opt)
        if (j.rotation == 0) return b
        val m = Matrix().apply { postRotate(j.rotation.toFloat()) }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return if (r.isMutable) r else r.copy(Bitmap.Config.ARGB_8888, true).also { r.recycle() }
    }

    /** Schärfe eines JPEGs (klein dekodiert). */
    fun sharpness(j: Jpeg): Float {
        val b = decode(j, 700_000)
        val s = Analyzer.sharpnessOf(b)
        b.recycle()
        return s
    }

    fun sharpest(list: List<Jpeg>): Jpeg = list.maxBy { sharpness(it) }

    // ---------------- Ausrichten ----------------

    private fun grayScaled(b: Bitmap, f: Int): Triple<IntArray, Int, Int> {
        val w = max(8, b.width / f)
        val h = max(8, b.height / f)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== b) s.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        return Triple(px, w, h)
    }

    private fun sad(a: IntArray, b: IntArray, w: Int, h: Int, dx: Int, dy: Int, margin: Int): Long {
        var s = 0L
        val step = max(1, min(w, h) / 120)
        var y = margin
        while (y < h - margin) {
            val yb = y + dy
            var x = margin
            while (x < w - margin) {
                s += abs(a[y * w + x] - b[yb * w + x + dx])
                x += step
            }
            y += step
        }
        return s
    }

    private fun search(a: IntArray, b: IntArray, w: Int, h: Int, cx: Int, cy: Int, r: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = cx
        var by = cy
        val margin = r + max(abs(cx), abs(cy)) + 2
        if (margin * 2 >= min(w, h)) return cx to cy
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            val s = sad(a, b, w, h, dx, dy, margin)
            if (s < best) { best = s; bx = dx; by = dy }
        }
        return bx to by
    }

    /** Verschiebung von b gegenüber ref (in Pixeln von ref), grob → fein. */
    fun shift(ref: Bitmap, b: Bitmap): Pair<Int, Int> {
        val (a8, w8, h8) = grayScaled(ref, 8)
        val (b8, _, _) = grayScaled(b, 8)
        val (sx8, sy8) = search(a8, b8, w8, h8, 0, 0, 12)
        val (a2, w2, h2) = grayScaled(ref, 2)
        val (b2, _, _) = grayScaled(b, 2)
        val (sx2, sy2) = search(a2, b2, w2, h2, sx8 * 4, sy8 * 4, 4)
        return sx2 * 2 to sy2 * 2
    }

    // ---------------- Stacking (Nacht) ----------------

    /**
     * Mehrere Bilder ausrichten und mitteln. Bewegte Stellen (zu großer Unterschied zum Referenzbild)
     * werden nicht mitgemittelt → keine Geisterbilder.
     */
    fun stack(frames: List<Jpeg>, onStep: (String) -> Unit): Bitmap {
        // Referenz = schärfstes Bild
        val refJ = sharpest(frames)
        val ref = decode(refJ, MAX_COMBINE_PX)
        val w = ref.width
        val h = ref.height
        val sum = ShortArray(w * h * 3)
        val cnt = ByteArray(w * h)
        val refPx = IntArray(w * h)
        ref.getPixels(refPx, 0, w, 0, 0, w, h)
        for (i in 0 until w * h) {
            val c = refPx[i]
            sum[i * 3] = ((c shr 16) and 255).toShort()
            sum[i * 3 + 1] = ((c shr 8) and 255).toShort()
            sum[i * 3 + 2] = (c and 255).toShort()
            cnt[i] = 1
        }
        val row = IntArray(w)
        var k = 1
        for (j in frames) {
            if (j === refJ) continue
            onStep("Kombiniere Bild ${++k} von ${frames.size} …")
            val b = decode(j, MAX_COMBINE_PX)
            if (b.width != w || b.height != h) { b.recycle(); continue }
            val (dx, dy) = shift(ref, b)
            for (y in 0 until h) {
                val sy = y + dy
                if (sy < 0 || sy >= h) continue
                b.getPixels(row, 0, w, 0, sy, w, 1)
                val o = y * w
                for (x in 0 until w) {
                    val sx = x + dx
                    if (sx < 0 || sx >= w) continue
                    val c = row[sx]
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val bl = c and 255
                    val rc = refPx[o + x]
                    val d = abs(r - ((rc shr 16) and 255)) + abs(g - ((rc shr 8) and 255)) + abs(bl - (rc and 255))
                    if (d > 90) continue // Bewegung → nicht mitteln
                    val i = o + x
                    sum[i * 3] = (sum[i * 3] + r).toShort()
                    sum[i * 3 + 1] = (sum[i * 3 + 1] + g).toShort()
                    sum[i * 3 + 2] = (sum[i * 3 + 2] + bl).toShort()
                    cnt[i] = (cnt[i] + 1).toByte()
                }
            }
            b.recycle()
        }
        onStep("Rechne Ergebnis …")
        for (i in 0 until w * h) {
            val n = cnt[i].toInt()
            val r = sum[i * 3] / n
            val g = sum[i * 3 + 1] / n
            val b = sum[i * 3 + 2] / n
            refPx[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        ref.setPixels(refPx, 0, w, 0, 0, w, h)
        return ref
    }

    // ---------------- HDR-Fusion ----------------

    /**
     * Belichtungsreihe zusammenführen (Exposure Fusion nach Mertens, vereinfacht):
     * Gewichte aus Kontrast, Sättigung und guter Belichtung, auf kleiner Auflösung berechnet,
     * weichgezeichnet und beim Mischen hochgerechnet → wenig Speicher, kaum Halos.
     */
    fun fuse(frames: List<Jpeg>, onStep: (String) -> Unit): Bitmap {
        val bmps = ArrayList<Bitmap>()
        for (j in frames) bmps += decode(j, MAX_COMBINE_PX / frames.size.coerceAtLeast(1) * 2)
        val ref = bmps[frames.size / 2]
        val w = ref.width
        val h = ref.height
        val list = bmps.filter { it.width == w && it.height == h }
        onStep("Richte Belichtungsreihe aus …")
        val shifts = list.map { if (it === ref) 0 to 0 else shift(ref, it) }

        // Gewichte auf 1/8 Größe
        val f = 8
        val sw = max(4, w / f)
        val sh = max(4, h / f)
        val weights = list.map { b ->
            val s = Bitmap.createScaledBitmap(b, sw, sh, true)
            val px = IntArray(sw * sh)
            s.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (s !== b) s.recycle()
            val l = FloatArray(sw * sh) { i ->
                val c = px[i]
                (((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f) / 255f
            }
            FloatArray(sw * sh) { i ->
                val x = i % sw
                val y = i / sw
                val c = px[i]
                val r = ((c shr 16) and 255) / 255f
                val g = ((c shr 8) and 255) / 255f
                val bl = (c and 255) / 255f
                val m = (r + g + bl) / 3f
                val sat = sqrt(((r - m) * (r - m) + (g - m) * (g - m) + (bl - m) * (bl - m)) / 3f)
                val lap = if (x in 1 until sw - 1 && y in 1 until sh - 1)
                    abs(4 * l[i] - l[i - 1] - l[i + 1] - l[i - sw] - l[i + sw]) else 0f
                fun we(v: Float) = exp(-((v - 0.5f) * (v - 0.5f)) / 0.08f)
                val wexp = we(r) * we(g) * we(bl)
                (lap + 0.05f) * (sat + 0.15f) * (wexp + 1e-4f)
            }
        }
        // weichzeichnen
        val blurred = weights.map { blur(blur(it, sw, sh, max(1, sw / 40)), sw, sh, max(1, sw / 40)) }
        onStep("Füge HDR zusammen …")
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = list.map { IntArray(w) }
        val line = IntArray(w)
        val wk = FloatArray(list.size)
        for (y in 0 until h) {
            for ((k, b) in list.withIndex()) {
                val sy = (y + shifts[k].second).coerceIn(0, h - 1)
                b.getPixels(rows[k], 0, w, 0, sy, w, 1)
            }
            val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt()
            val y1 = min(sh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt()
                val x1 = min(sw - 1, x0 + 1)
                val tx = fx - x0
                var tot = 0f
                for (k in list.indices) {
                    val wb = blurred[k]
                    val a = wb[y0 * sw + x0] * (1 - tx) + wb[y0 * sw + x1] * tx
                    val c = wb[y1 * sw + x0] * (1 - tx) + wb[y1 * sw + x1] * tx
                    wk[k] = a * (1 - ty) + c * ty
                    tot += wk[k]
                }
                var r = 0f; var g = 0f; var bl = 0f
                for (k in list.indices) {
                    val sx = (x + shifts[k].first).coerceIn(0, w - 1)
                    val c = rows[k][sx]
                    val ww = if (tot > 0f) wk[k] / tot else 1f / list.size
                    r += ((c shr 16) and 255) * ww
                    g += ((c shr 8) and 255) * ww
                    bl += (c and 255) * ww
                }
                line[x] = -0x1000000 or (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or bl.roundToInt().coerceIn(0, 255)
            }
            out.setPixels(line, 0, w, 0, y, w, 1)
        }
        bmps.forEach { it.recycle() }
        return out
    }

    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val n = 2 * r + 1f
        for (y in 0 until h) {
            var s = 0f
            for (k in -r..r) s += src[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = s / n
                s += src[y * w + min(w - 1, x + r + 1)] - src[y * w + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / n
                s += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
            }
        }
        return out
    }

    // ---------------- Speichern ----------------

    fun newName(scene: Scene): String {
        val t = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val tag = scene.name.lowercase(Locale.ROOT)
        return "IMG_${t}_$tag.jpg"
    }

    fun contentValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera/")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
    }

    fun saveBitmap(ctx: Context, b: Bitmap, scene: Scene): Uri? = save(ctx, scene) { out ->
        b.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

    fun saveJpeg(ctx: Context, j: Jpeg, scene: Scene): Uri? {
        if (j.rotation == 0) return save(ctx, scene) { it.write(j.bytes) }
        val b = decode(j)
        val u = saveBitmap(ctx, b, scene)
        b.recycle()
        return u
    }

    private fun save(ctx: Context, scene: Scene, write: (java.io.OutputStream) -> Unit): Uri? {
        val cr = ctx.contentResolver
        val v = contentValues(newName(scene)).apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { write(it) } ?: throw IllegalStateException()
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Throwable) {
            cr.delete(uri, null, null)
            null
        }
    }
}
