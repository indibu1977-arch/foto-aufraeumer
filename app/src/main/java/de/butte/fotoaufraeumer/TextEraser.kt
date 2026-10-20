package de.butte.fotoaufraeumer

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Random
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Schrift im Bild finden und die Stellen aus der Umgebung auffüllen.
 * Alles läuft auf dem Handy, nichts wird hochgeladen.
 */
object TextEraser {

    // ======================= Erkennen =======================

    /** Findet Textzeilen (auf dem Handy, ML Kit). */
    suspend fun detectLines(bmp: Bitmap): List<Rect> {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { t ->
                        cont.resume(t.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox } })
                    }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }
        } finally {
            rec.close()
        }
    }

    /**
     * Baut aus den Textzeilen eine Maske (true = entfernen).
     * Innerhalb jeder Zeile werden nur die Buchstaben-Pixel markiert (plus etwas Rand),
     * damit möglichst viel echtes Bild erhalten bleibt.
     */
    fun maskFromLines(bmp: Bitmap, lines: List<Rect>): BooleanArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val mask = BooleanArray(w * h)
        for (r0 in lines) {
            val pad = max(2, (r0.height() * 0.15f).roundToInt())
            val l = (r0.left - pad).coerceIn(0, w - 1)
            val t = (r0.top - pad).coerceIn(0, h - 1)
            val r = (r0.right + pad).coerceIn(0, w - 1)
            val b = (r0.bottom + pad).coerceIn(0, h - 1)
            if (r <= l || b <= t) continue

            // Hintergrundfarbe = Median der Randpixel
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            fun add(x: Int, y: Int) { val c = px[y * w + x]; rs += (c shr 16) and 255; gs += (c shr 8) and 255; bs += c and 255 }
            for (x in l..r) { add(x, t); add(x, b) }
            for (y in t..b) { add(l, y); add(r, y) }
            val mr = rs.sorted()[rs.size / 2]; val mg = gs.sorted()[gs.size / 2]; val mb = bs.sorted()[bs.size / 2]
            val dists = rs.indices.map { abs(rs[it] - mr) + abs(gs[it] - mg) + abs(bs[it] - mb) }.sorted()
            val thr = max(60, dists[(dists.size * 0.8f).toInt().coerceAtMost(dists.size - 1)] + 30)

            val local = BooleanArray((r - l + 1) * (b - t + 1))
            var count = 0
            for (y in t..b) for (x in l..r) {
                val c = px[y * w + x]
                val d = abs(((c shr 16) and 255) - mr) + abs(((c shr 8) and 255) - mg) + abs((c and 255) - mb)
                if (d > thr) { local[(y - t) * (r - l + 1) + (x - l)] = true; count++ }
            }
            val frac = count.toFloat() / local.size
            val rad = max(2, (r0.height() / 8f).roundToInt())
            if (frac < 0.02f || frac > 0.65f) {
                // Unsicher (z. B. Schrift auf unruhigem Hintergrund) → ganze Zeile
                for (y in t..b) for (x in l..r) mask[y * w + x] = true
            } else {
                // Buchstaben etwas aufdicken (Kanten, Schatten, Umrandung)
                val lw = r - l + 1
                for (y in t..b) for (x in l..r) {
                    if (!local[(y - t) * lw + (x - l)]) continue
                    for (dy in -rad..rad) {
                        val yy = y + dy
                        if (yy < 0 || yy >= h) continue
                        for (dx in -rad..rad) {
                            val xx = x + dx
                            if (xx < 0 || xx >= w || dx * dx + dy * dy > rad * rad) continue
                            mask[yy * w + xx] = true
                        }
                    }
                }
            }
        }
        return mask
    }

    // ======================= Auffüllen =======================

    /** Füllt alle markierten Stellen aus der Umgebung auf. Liefert ein neues Bitmap. */
    fun inpaint(src: Bitmap, mask: BooleanArray): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        for (box in regions(mask, w, h)) {
            val bw = box.width()
            val bh = box.height()
            val px = IntArray(bw * bh)
            out.getPixels(px, 0, bw, box.left, box.top, bw, bh)
            val known = BooleanArray(bw * bh) { i -> !mask[(box.top + i / bw) * w + box.left + i % bw] }
            val rr = FloatArray(bw * bh) { ((px[it] shr 16) and 255).toFloat() }
            val gg = FloatArray(bw * bh) { ((px[it] shr 8) and 255).toFloat() }
            val bb = FloatArray(bw * bh) { (px[it] and 255).toFloat() }
            val grain = grain(rr, gg, bb, known, bw, bh)
            fill(rr, gg, bb, known, bw, bh)
            val rnd = Random(box.hashCode().toLong())
            for (i in px.indices) {
                if (known[i]) continue
                val n = (rnd.nextGaussian() * grain).toFloat()
                px[i] = (0xFF shl 24) or
                    (clamp(rr[i] + n) shl 16) or
                    (clamp(gg[i] + n) shl 8) or
                    clamp(bb[i] + n)
            }
            out.setPixels(px, 0, bw, box.left, box.top, bw, bh)
        }
        return out
    }

    private fun clamp(v: Float): Int = v.roundToInt().coerceIn(0, 255)

    /** Bereiche um zusammenhängende Maskenflächen, mit Rand als Vorlage, zusammengefasst. */
    private fun regions(mask: BooleanArray, w: Int, h: Int): List<Rect> {
        val seen = BooleanArray(w * h)
        val boxes = ArrayList<Rect>()
        var stack = IntArray(4096)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            fun push(i: Int) {
                if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                stack[sp++] = i
            }
            push(start)
            seen[start] = true
            var l = w; var t = h; var r = 0; var b = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; push(i - 1) }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; push(i + 1) }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; push(i - w) }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; push(i + w) }
            }
            val m = 16 + max(r - l, b - t) / 6
            boxes += Rect(max(0, l - m), max(0, t - m), min(w, r + m + 1), min(h, b + m + 1))
        }
        // Überlappende Bereiche zusammenfassen
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                if (Rect.intersects(boxes[i], boxes[j])) {
                    boxes[i].union(boxes[j])
                    boxes.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return boxes
    }

    /** Wie körnig ist die Umgebung? Damit die Füllung nicht wie Plastik aussieht. */
    private fun grain(r: FloatArray, g: FloatArray, b: FloatArray, known: BooleanArray, w: Int, h: Int): Float {
        var sum = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!known[i] || !known[i - 1] || !known[i + 1] || !known[i - w] || !known[i + w]) continue
            val lum = { k: Int -> 0.3f * r[k] + 0.59f * g[k] + 0.11f * b[k] }
            val d = lum(i) - (lum(i - 1) + lum(i + 1) + lum(i - w) + lum(i + w)) / 4f
            sum += d * d
            n++
        }
        if (n == 0) return 0f
        return (sqrt(sum / n).toFloat() * 0.6f).coerceAtMost(12f)
    }

    /** Mehrstufiges Auffüllen: grob vorfüllen, dann auf jeder Stufe glätten. */
    private fun fill(r: FloatArray, g: FloatArray, b: FloatArray, known: BooleanArray, w: Int, h: Int) {
        val unknown = known.indices.filter { !known[it] }.toIntArray()
        if (unknown.isEmpty()) return
        if (unknown.size == known.size || w <= 2 || h <= 2) {
            var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            for (i in known.indices) if (known[i]) { sr += r[i]; sg += g[i]; sb += b[i]; n++ }
            val cr = if (n > 0) sr / n else 128f
            val cg = if (n > 0) sg / n else 128f
            val cb = if (n > 0) sb / n else 128f
            for (i in unknown) { r[i] = cr; g[i] = cg; b[i] = cb }
        } else {
            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            val cr = FloatArray(cw * ch); val cg = FloatArray(cw * ch); val cb = FloatArray(cw * ch)
            val ck = BooleanArray(cw * ch)
            for (y in 0 until ch) for (x in 0 until cw) {
                var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = 2 * x + dx; val yy = 2 * y + dy
                    if (xx >= w || yy >= h) continue
                    val i = yy * w + xx
                    if (!known[i]) continue
                    sr += r[i]; sg += g[i]; sb += b[i]; n++
                }
                val c = y * cw + x
                if (n > 0) { cr[c] = sr / n; cg[c] = sg / n; cb[c] = sb / n; ck[c] = true }
            }
            fill(cr, cg, cb, ck, cw, ch)
            // Bilinear hochrechnen
            for (i in unknown) {
                val fx = ((i % w) - 0.5f) / 2f
                val fy = ((i / w) - 0.5f) / 2f
                val x0 = fx.toInt().coerceIn(0, cw - 1); val y0 = fy.toInt().coerceIn(0, ch - 1)
                val x1 = min(x0 + 1, cw - 1); val y1 = min(y0 + 1, ch - 1)
                val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
                fun bil(a: FloatArray): Float {
                    val top = a[y0 * cw + x0] * (1 - ax) + a[y0 * cw + x1] * ax
                    val bot = a[y1 * cw + x0] * (1 - ax) + a[y1 * cw + x1] * ax
                    return top * (1 - ay) + bot * ay
                }
                r[i] = bil(cr); g[i] = bil(cg); b[i] = bil(cb)
            }
        }
        // Glätten (Gauss-Seidel), bekannte Pixel bleiben fest
        repeat(12) {
            for (i in unknown) {
                val x = i % w
                val y = i / w
                val l = if (x > 0) i - 1 else i
                val rt = if (x < w - 1) i + 1 else i
                val up = if (y > 0) i - w else i
                val dn = if (y < h - 1) i + w else i
                r[i] = (r[l] + r[rt] + r[up] + r[dn]) * 0.25f
                g[i] = (g[l] + g[rt] + g[up] + g[dn]) * 0.25f
                b[i] = (b[l] + b[rt] + b[up] + b[dn]) * 0.25f
            }
        }
    }
}
