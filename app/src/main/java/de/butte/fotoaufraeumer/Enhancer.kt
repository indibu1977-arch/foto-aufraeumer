package de.butte.fotoaufraeumer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Einstellungen zum Aufwerten. lo/hi = automatischer Tonwertausgleich. */
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpen: Float = 0f,
    val denoise: Float = 0f,
    val lo: Float = 0f,
    val hi: Float = 255f,
    /** Details/Klarheit: lokaler Kontrast (großer Radius), 0..1 */
    val detail: Float = 0f,
    /** Hochskalieren beim Speichern: 1 = aus, 2 = doppelt, 3 = dreifach */
    val upscale: Int = 1,
)

object Enhancer {
    fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > maxSide) {
                val s = maxSide.toFloat() / m
                dec.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        }
    }

    private fun matrix(a: Adjust): ColorMatrix {
        val range = max(1f, a.hi - a.lo)
        val ls = 255f / range
        val levels = ColorMatrix(
            floatArrayOf(
                ls, 0f, 0f, 0f, -a.lo * ls,
                0f, ls, 0f, 0f, -a.lo * ls,
                0f, 0f, ls, 0f, -a.lo * ls,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val k = 1f + a.contrast * 0.6f
        val off = 128f * (1f - k) + a.brightness * 70f
        val contrast = ColorMatrix(
            floatArrayOf(
                k, 0f, 0f, 0f, off,
                0f, k, 0f, 0f, off,
                0f, 0f, k, 0f, off,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val sat = ColorMatrix().apply { setSaturation(1f + a.saturation) }
        val r = 1f + a.warmth * 0.12f
        val b = 1f - a.warmth * 0.12f
        val warm = ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val cm = ColorMatrix(levels)
        cm.postConcat(contrast)
        cm.postConcat(sat)
        cm.postConcat(warm)
        return cm
    }

    fun apply(src: Bitmap, a: Adjust): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix(a)) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        if (a.denoise > 0.01f) denoise(out, a.denoise)
        if (a.detail > 0.01f) clarity(out, a.detail, max(2, kotlin.math.min(out.width, out.height) / 120))
        if (a.sharpen > 0.01f) sharpen(out, a.sharpen * 1.6f)
        return out
    }

    /** Höchstens so viele Pixel beim Hochskalieren (Speicher!). */
    private const val MAX_PIXELS = 24_000_000L

    /** Tatsächlicher Faktor für ein Bild w×h (wird bei sehr großen Fotos begrenzt). */
    fun upscaleFactor(w: Int, h: Int, wanted: Int): Float {
        if (wanted <= 1) return 1f
        val maxF = kotlin.math.sqrt(MAX_PIXELS.toDouble() / max(1L, w.toLong() * h)).toFloat()
        return kotlin.math.min(wanted.toFloat(), maxF).coerceAtLeast(1f)
    }

    /** Für das Speichern: Farben/Entrauschen → Hochskalieren → Details → Schärfe. */
    fun applyFull(src: Bitmap, a: Adjust): Bitmap {
        val f = upscaleFactor(src.width, src.height, a.upscale)
        if (f <= 1.05f) return apply(src, a)
        val base = apply(src, a.copy(detail = 0f, sharpen = 0f))
        val big = upscale(base, f)
        base.recycle()
        // nach dem Vergrößern immer etwas Details & Schärfe zurückholen
        val d = (a.detail + 0.15f).coerceAtMost(1f)
        val r = max(2, kotlin.math.min(big.width, big.height) / 120)
        val sh = (a.sharpen + 0.25f).coerceAtMost(1f) * 1.4f
        inStrips(big, r * 4) { clarity(it, d, r) }
        inStrips(big, 2) { sharpen(it, sh) }
        return big
    }

    /**
     * Hochskalieren mit bikubischer Interpolation (Catmull-Rom, getrennt waagerecht/senkrecht)
     * und kantenschonendem Nachschärfen – deutlich besser als einfaches Vergrößern.
     */
    fun upscale(src: Bitmap, factor: Float): Bitmap {
        val sw = src.width
        val sh = src.height
        val dw = max(1, (sw * factor).roundToInt())
        val dh = max(1, (sh * factor).roundToInt())
        val inPx = IntArray(sw * sh)
        src.getPixels(inPx, 0, sw, 0, 0, sw, sh)

        // 1. waagerecht: sw×sh → dw×sh (Zwischenergebnis gepackt, spart Speicher)
        val tmp = IntArray(dw * sh)
        val xIdx = IntArray(dw * 4)
        val xW = FloatArray(dw * 4)
        for (x in 0 until dw) {
            val fx = (x + 0.5f) / factor - 0.5f
            val ix = kotlin.math.floor(fx).toInt()
            val w = cubic(fx - ix)
            for (k in 0..3) {
                xIdx[x * 4 + k] = (ix - 1 + k).coerceIn(0, sw - 1)
                xW[x * 4 + k] = w[k]
            }
        }
        for (y in 0 until sh) {
            val row = y * sw
            val o = y * dw
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in 0..3) {
                    val c = inPx[row + xIdx[x * 4 + k]]
                    val w = xW[x * 4 + k]
                    r += ((c shr 16) and 255) * w
                    g += ((c shr 8) and 255) * w
                    b += (c and 255) * w
                }
                tmp[o + x] = pack(r, g, b)
            }
        }
        // 2. senkrecht: dw×sh → dw×dh
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val line = IntArray(dw)
        for (y in 0 until dh) {
            val fy = (y + 0.5f) / factor - 0.5f
            val iy = kotlin.math.floor(fy).toInt()
            val w = cubic(fy - iy)
            val r0 = (iy - 1).coerceIn(0, sh - 1) * dw
            val r1 = iy.coerceIn(0, sh - 1) * dw
            val r2 = (iy + 1).coerceIn(0, sh - 1) * dw
            val r3 = (iy + 2).coerceIn(0, sh - 1) * dw
            for (x in 0 until dw) {
                val a0 = tmp[r0 + x]; val a1 = tmp[r1 + x]; val a2 = tmp[r2 + x]; val a3 = tmp[r3 + x]
                val r = ((a0 shr 16) and 255) * w[0] + ((a1 shr 16) and 255) * w[1] + ((a2 shr 16) and 255) * w[2] + ((a3 shr 16) and 255) * w[3]
                val g = ((a0 shr 8) and 255) * w[0] + ((a1 shr 8) and 255) * w[1] + ((a2 shr 8) and 255) * w[2] + ((a3 shr 8) and 255) * w[3]
                val b = (a0 and 255) * w[0] + (a1 and 255) * w[1] + (a2 and 255) * w[2] + (a3 and 255) * w[3]
                line[x] = pack(r, g, b)
            }
            out.setPixels(line, 0, dw, 0, y, dw, 1)
        }
        return out
    }

    private fun pack(r: Float, g: Float, b: Float): Int =
        -0x1000000 or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    /**
     * Große Bilder streifenweise bearbeiten (mit Überlappung), damit der Speicher reicht.
     * fn bearbeitet den Streifen in place.
     */
    private fun inStrips(bmp: Bitmap, pad: Int, fn: (Bitmap) -> Unit) {
        val w = bmp.width
        val h = bmp.height
        val step = max(256, (4_000_000 / max(1, w)))
        if (h <= step) { fn(bmp); return }
        var y0 = 0
        val row = IntArray(w * step)
        while (y0 < h) {
            val y1 = kotlin.math.min(h, y0 + step)
            val a = max(0, y0 - pad)
            val b = kotlin.math.min(h, y1 + pad)
            val sub = Bitmap.createBitmap(bmp, 0, a, w, b - a)
            val strip = sub.copy(Bitmap.Config.ARGB_8888, true)
            if (sub !== bmp) sub.recycle()
            fn(strip)
            strip.getPixels(row, 0, w, 0, y0 - a, w, y1 - y0)
            bmp.setPixels(row, 0, w, 0, y0, w, y1 - y0)
            strip.recycle()
            y0 = y1
        }
    }

    private fun cubic(t: Float): FloatArray {
        // Catmull-Rom (a = -0.5)
        val t2 = t * t
        val t3 = t2 * t
        return floatArrayOf(
            -0.5f * t3 + t2 - 0.5f * t,
            1.5f * t3 - 2.5f * t2 + 1f,
            -1.5f * t3 + 2f * t2 + 0.5f * t,
            0.5f * t3 - 0.5f * t2,
        )
    }

    /**
     * Details/Klarheit: Unscharf-Maskieren mit großem Radius, nur auf die Helligkeit,
     * in den Mitteltönen am stärksten (Lichter/Schatten laufen nicht aus).
     */
    private fun clarity(bmp: Bitmap, amount: Float, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = ((c shr 16) and 255) * 0.299f + ((c shr 8) and 255) * 0.587f + (c and 255) * 0.114f
        }
        val blur = boxBlur(boxBlur(lum, w, h, radius), w, h, radius)
        val k = amount * 1.2f
        for (i in px.indices) {
            val l = lum[i]
            val mid = 1f - kotlin.math.abs(l - 128f) / 128f // 1 in den Mitteltönen
            val d = (l - blur[i]) * k * (0.35f + 0.65f * mid)
            val c = px[i]
            val r = (((c shr 16) and 255) + d).roundToInt().coerceIn(0, 255)
            val g = (((c shr 8) and 255) + d).roundToInt().coerceIn(0, 255)
            val b = ((c and 255) + d).roundToInt().coerceIn(0, 255)
            px[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Schneller Box-Weichzeichner (gleitende Summe), waagerecht + senkrecht. */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val n = 2 * r + 1f
        for (y in 0 until h) {
            val row = y * w
            var s = 0f
            for (k in -r..r) s += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = s / n
                s += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = s / n
                s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /** Kantenerhaltendes Entrauschen: nur ähnliche Nachbarn (5x5) mitteln. */
    private fun denoise(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 5 || h < 5) return
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()
        val th = 10 + (amount * 28).toInt()
        val blend = (0.5f + amount * 0.5f).coerceAtMost(1f)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val c = src[i]
                val r0 = (c shr 16) and 255
                val g0 = (c shr 8) and 255
                val b0 = c and 255
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -2..2) {
                    val base = i + dy * w
                    for (dx in -2..2) {
                        val d = src[base + dx]
                        val r = (d shr 16) and 255
                        val g = (d shr 8) and 255
                        val b = d and 255
                        if (abs(r - r0) + abs(g - g0) + abs(b - b0) <= th * 3) {
                            sr += r; sg += g; sb += b; n++
                        }
                    }
                }
                val nr = r0 + ((sr / n - r0) * blend).roundToInt()
                val ng = g0 + ((sg / n - g0) * blend).roundToInt()
                val nb = b0 + ((sb / n - b0) * blend).roundToInt()
                dst[i] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bmp.setPixels(dst, 0, w, 0, 0, w, h)
    }

    /** Unscharf-Maskieren mit 3x3-Kernel. */
    private fun sharpen(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                var sr = 0
                var sg = 0
                var sb = 0
                for (dy in -1..1) {
                    val base = i + dy * w
                    for (dx in -1..1) {
                        val c = src[base + dx]
                        sr += (c shr 16) and 255
                        sg += (c shr 8) and 255
                        sb += c and 255
                    }
                }
                val c = src[i]
                val cr = (c shr 16) and 255
                val cg = (c shr 8) and 255
                val cb = c and 255
                val nr = (cr + amount * (cr - sr / 9f)).roundToInt().coerceIn(0, 255)
                val ng = (cg + amount * (cg - sg / 9f)).roundToInt().coerceIn(0, 255)
                val nb = (cb + amount * (cb - sb / 9f)).roundToInt().coerceIn(0, 255)
                dst[i] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bmp.setPixels(dst, 0, w, 0, 0, w, h)
    }

    /** Automatische Werte aus dem Helligkeits-Histogramm. */
    fun auto(src: Bitmap): Adjust {
        val m = max(src.width, src.height)
        val s = if (m > 400) {
            val f = 400f / m
            Bitmap.createScaledBitmap(src, max(1, (src.width * f).roundToInt()), max(1, (src.height * f).roundToInt()), true)
        } else src
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        if (s !== src) s.recycle()
        val hist = IntArray(256)
        var sum = 0L
        for (c in px) {
            val l = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
            hist[l]++
            sum += l
        }
        val total = px.size
        fun pct(p: Float): Int {
            val target = (total * p).toLong()
            var acc = 0L
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        var lo = pct(0.005f).toFloat()
        var hi = pct(0.995f).toFloat()
        // Höchstens 2,5-fach strecken
        if (hi - lo < 102f) {
            val mid = (hi + lo) / 2f
            lo = (mid - 51f).coerceAtLeast(0f)
            hi = (mid + 51f).coerceAtMost(255f)
        }
        val mean = sum.toFloat() / max(1, total)
        val stretched = (mean - lo) * 255f / max(1f, hi - lo)
        val bright = ((120f - stretched) / 140f).coerceIn(-0.3f, 0.3f)
        return Adjust(
            brightness = bright,
            contrast = 0.08f,
            saturation = 0.2f,
            sharpen = 0.35f,
            lo = lo,
            hi = hi,
        )
    }
}
