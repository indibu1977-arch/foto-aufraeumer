package de.butte.fotoaufraeumer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/** Einstellungen zum Aufwerten. lo/hi = automatischer Tonwertausgleich. */
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val sharpen: Float = 0f,
    val lo: Float = 0f,
    val hi: Float = 255f,
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
        if (a.sharpen > 0.01f) sharpen(out, a.sharpen * 1.6f)
        return out
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
