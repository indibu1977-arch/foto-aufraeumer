package de.butte.fotoaufraeumer

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Messwerte eines Bildes. */
data class Metrics(
    val brightness: Float,   // mittlere Helligkeit 0..255
    val contrast: Float,     // Standardabweichung der Helligkeit
    val range: Float,        // Tonwertumfang (0,5 %..99,5 %)
    val shadows: Float,      // % abgesoffene Schatten
    val highlights: Float,   // % ausgefressene Lichter
    val saturation: Float,   // mittlere Sättigung 0..1
    val sharpness: Float,    // Laplace-Varianz (wie im Scan)
    val noise: Float,        // geschätztes Rauschen (Sigma)
    val castWarm: Float,     // >0 gelb/orange, <0 blau
    val castGreen: Float,    // >0 grün, <0 magenta
) {
    val ratings: List<Rating> by lazy { Quality.rate(this) }
    val score: Int by lazy { Quality.score(ratings) }
}

/** Ein bewerteter Messwert. value = 0..1 (1 = sehr gut). */
data class Rating(val key: String, val label: String, val shown: String, val value: Float, val weight: Float)

/** Ein Verbesserungsvorschlag. fix == null → nur Hinweis. */
data class Suggestion(
    val key: String,
    val title: String,
    val detail: String,
    val severe: Boolean,
    val fix: ((Adjust) -> Adjust)?,
)

object Quality {

    // ======================= Messen =======================

    fun measure(src: Bitmap): Metrics {
        val m = max(src.width, src.height)
        val s = if (m > 512) {
            val f = 512f / m
            Bitmap.createScaledBitmap(src, max(1, (src.width * f).roundToInt()), max(1, (src.height * f).roundToInt()), true)
        } else src
        val w = s.width
        val h = s.height
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)

        val lum = FloatArray(px.size)
        val hist = IntArray(256)
        var sumL = 0.0
        var sumSq = 0.0
        var sumSat = 0.0
        var nr = 0.0; var ng = 0.0; var nb = 0.0; var nn = 0
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val l = r * 0.299f + g * 0.587f + b * 0.114f
            lum[i] = l
            hist[l.toInt().coerceIn(0, 255)]++
            sumL += l
            sumSq += l * l
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            sumSat += sat
            // Farbstich: nur fast neutrale, mittelhelle Pixel (Grauwelt-Annahme)
            if (sat < 0.25f && l in 40f..220f) { nr += r; ng += g; nb += b; nn++ }
        }
        val n = px.size.toFloat()
        val mean = (sumL / n).toFloat()
        val std = sqrt(max(0.0, sumSq / n - mean.toDouble() * mean)).toFloat()
        fun pct(p: Float): Int {
            val target = (n * p).toLong()
            var acc = 0L
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        val shadows = (0..4).sumOf { hist[it] } * 100f / n
        val highlights = (251..255).sumOf { hist[it] } * 100f / n

        var warm = 0f
        var green = 0f
        if (nn > px.size / 50) {
            val ar = (nr / nn).toFloat(); val ag = (ng / nn).toFloat(); val ab = (nb / nn).toFloat()
            val avg = max(1f, (ar + ag + ab) / 3f)
            warm = (ar - ab) / avg
            green = (ag - (ar + ab) / 2f) / avg
        }

        // Rauschen nach Immerkær (schnelle Schätzung)
        var acc = 0.0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = lum[i - w - 1] - 2 * lum[i - w] + lum[i - w + 1] -
                2 * lum[i - 1] + 4 * lum[i] - 2 * lum[i + 1] +
                lum[i + w - 1] - 2 * lum[i + w] + lum[i + w + 1]
            acc += abs(v)
        }
        val noise = if (w > 2 && h > 2) (acc * sqrt(Math.PI / 2) / (6.0 * (w - 2) * (h - 2))).toFloat() else 0f

        val sharp = Analyzer.sharpnessOf(s)
        if (s !== src) s.recycle()

        return Metrics(
            brightness = mean,
            contrast = std,
            range = (pct(0.995f) - pct(0.005f)).toFloat(),
            shadows = shadows,
            highlights = highlights,
            saturation = (sumSat / n).toFloat(),
            sharpness = sharp,
            noise = noise,
            castWarm = warm,
            castGreen = green,
        )
    }

    // ======================= Bewerten =======================

    private fun lin(v: Float, bad: Float, good: Float): Float = ((v - bad) / (good - bad)).coerceIn(0f, 1f)

    fun rate(m: Metrics): List<Rating> {
        val expo = 1f - ((abs(m.brightness - 118f) - 15f).coerceAtLeast(0f) / 90f).coerceAtMost(1f)
        val con = if (m.contrast <= 80f) lin(m.contrast, 18f, 45f) else 1f - lin(m.contrast, 80f, 120f)
        val sat = when {
            m.saturation < 0.22f -> lin(m.saturation, 0.05f, 0.22f)
            m.saturation > 0.55f -> 1f - lin(m.saturation, 0.55f, 0.8f)
            else -> 1f
        }
        val sharp = lin(ln(max(1f, m.sharpness)), ln(40f), ln(250f))
        val noise = 1f - lin(m.noise, 3f, 10f)
        val cast = 1f - lin(sqrt(m.castWarm * m.castWarm + m.castGreen * m.castGreen), 0.04f, 0.16f)
        val clip = 1f - (lin(m.shadows, 2f, 14f) + lin(m.highlights, 1f, 9f)).coerceAtMost(1f)
        return listOf(
            Rating("expo", "Belichtung", "${m.brightness.roundToInt()}", expo, 20f),
            Rating("contrast", "Kontrast", "${m.contrast.roundToInt()}", con, 15f),
            Rating("range", "Tonwertumfang", "${m.range.roundToInt()}", lin(m.range, 120f, 210f), 10f),
            Rating("clip", "Abgeschnitten", "${fmt1(m.shadows)} % / ${fmt1(m.highlights)} %", clip, 15f),
            Rating("sat", "Farbkraft", "${(m.saturation * 100).roundToInt()} %", sat, 10f),
            Rating("sharp", "Schärfe", "${m.sharpness.roundToInt()}", sharp, 15f),
            Rating("noise", "Rauschen", fmt1(m.noise), noise, 10f),
            Rating("cast", "Farbstich", castText(m), cast, 5f),
        )
    }

    fun score(r: List<Rating>): Int =
        (r.sumOf { (it.value * it.weight).toDouble() } / r.sumOf { it.weight.toDouble() } * 100).roundToInt()

    private fun fmt1(v: Float) = String.format(java.util.Locale.GERMANY, "%.1f", v)

    private fun castText(m: Metrics): String = when {
        abs(m.castWarm) < 0.04f && abs(m.castGreen) < 0.04f -> "neutral"
        abs(m.castWarm) >= abs(m.castGreen) -> if (m.castWarm > 0) "gelblich" else "bläulich"
        else -> if (m.castGreen > 0) "grünlich" else "rötlich"
    }

    fun grade(score: Int): String = when {
        score >= 85 -> "sehr gut"
        score >= 70 -> "gut"
        score >= 55 -> "mittel"
        score >= 40 -> "schwach"
        else -> "schlecht"
    }

    // ======================= Vorschläge =======================

    /**
     * Vorschläge auf Basis des aktuell bearbeiteten Bildes.
     * a = aktuelle Einstellungen, levels = automatischer Tonwertausgleich des Originals.
     */
    fun suggest(m: Metrics, a: Adjust, levels: Pair<Float, Float>?): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        val sharpR = m.ratings.first { it.key == "sharp" }.value

        if (m.brightness < 95f) {
            val d = ((118f - m.brightness) / 140f).coerceIn(0.05f, 0.4f)
            out += Suggestion("bright", "Zu dunkel", "Helligkeit um ${(d * 100).roundToInt()} anheben", m.brightness < 70f) {
                it.copy(brightness = (it.brightness + d).coerceIn(-1f, 1f))
            }
        } else if (m.brightness > 150f) {
            val d = ((m.brightness - 118f) / 140f).coerceIn(0.05f, 0.4f)
            out += Suggestion("bright", "Zu hell", "Helligkeit um ${(d * 100).roundToInt()} senken", m.brightness > 180f) {
                it.copy(brightness = (it.brightness - d).coerceIn(-1f, 1f))
            }
        }

        if (m.range < 170f && levels != null && a.lo == 0f && a.hi == 255f && levels.second - levels.first < 240f) {
            out += Suggestion("levels", "Flau / grauer Schleier", "Tonwerte automatisch spreizen (${levels.first.roundToInt()}–${levels.second.roundToInt()} → 0–255)", m.range < 140f) {
                it.copy(lo = levels.first, hi = levels.second)
            }
        }
        if (m.contrast < 40f) {
            val d = ((50f - m.contrast) / 60f).coerceIn(0.05f, 0.4f)
            out += Suggestion("contrast", "Wenig Kontrast", "Kontrast um ${(d * 100).roundToInt()} erhöhen", m.contrast < 28f) {
                it.copy(contrast = (it.contrast + d).coerceIn(-1f, 1f))
            }
        } else if (m.contrast > 85f) {
            out += Suggestion("contrast", "Sehr harter Kontrast", "Kontrast um 20 senken", false) {
                it.copy(contrast = (it.contrast - 0.2f).coerceIn(-1f, 1f))
            }
        }

        if (m.highlights > 3f) {
            val byEdit = a.brightness > 0.05f || a.contrast > 0.1f || a.lo > 0f || a.hi < 255f
            out += Suggestion(
                "hi", "Helle Stellen ausgefressen (${m.highlights.roundToInt()} %)",
                if (byEdit) "Helligkeit/Kontrast etwas zurücknehmen" else "Im Original schon weiß – nur begrenzt zu retten",
                m.highlights > 8f,
                { it.copy(brightness = (it.brightness - 0.08f).coerceIn(-1f, 1f), contrast = (it.contrast - 0.08f).coerceIn(-1f, 1f), hi = min(255f, it.hi + 10f)) },
            )
        }
        if (m.shadows > 4f) {
            out += Suggestion(
                "lo", "Dunkle Stellen abgesoffen (${m.shadows.roundToInt()} %)", "Schatten aufhellen", m.shadows > 12f,
            ) { it.copy(brightness = (it.brightness + 0.06f).coerceIn(-1f, 1f), contrast = (it.contrast - 0.08f).coerceIn(-1f, 1f), lo = max(0f, it.lo - 8f)) }
        }

        if (m.saturation < 0.18f) {
            val d = ((0.3f - m.saturation) / 0.4f).coerceIn(0.1f, 0.5f)
            out += Suggestion("sat", "Blasse Farben", "Farbkraft um ${(d * 100).roundToInt()} erhöhen", m.saturation < 0.1f) {
                it.copy(saturation = (it.saturation + d).coerceIn(-1f, 1f))
            }
        } else if (m.saturation > 0.6f) {
            out += Suggestion("sat", "Übersättigt", "Farbkraft um 20 senken", m.saturation > 0.7f) {
                it.copy(saturation = (it.saturation - 0.2f).coerceIn(-1f, 1f))
            }
        }

        if (m.castWarm > 0.06f || m.castWarm < -0.06f) {
            val d = (-m.castWarm / 0.24f * 0.8f).coerceIn(-0.6f, 0.6f)
            out += Suggestion(
                "warm", if (m.castWarm > 0) "Gelb-/Orangestich" else "Blaustich",
                "Weißabgleich: Wärme ${if (d > 0) "+" else ""}${(d * 100).roundToInt()}",
                abs(m.castWarm) > 0.14f,
            ) { it.copy(warmth = (it.warmth + d).coerceIn(-1f, 1f)) }
        }
        if (abs(m.castGreen) > 0.06f) {
            out += Suggestion(
                "green", if (m.castGreen > 0) "Leichter Grünstich" else "Leichter Magentastich",
                "Kommt oft von Kunstlicht – mit den Reglern hier nicht korrigierbar", false, null,
            )
        }

        if (m.noise > 6f) {
            out += Suggestion("noise", "Bildrauschen", "Entrauschen und weniger nachschärfen", m.noise > 9f) {
                it.copy(denoise = max(it.denoise, (it.denoise + 0.4f).coerceAtMost(1f)), sharpen = min(it.sharpen, 0.2f))
            }
        } else if (sharpR < 0.6f && a.sharpen < 0.8f) {
            val veryBlurry = m.sharpness < 25f
            out += Suggestion(
                "sharp", if (veryBlurry) "Stark unscharf" else "Etwas weich",
                if (veryBlurry) "Nachschärfen hilft nur begrenzt – ggf. aussortieren" else "Nachschärfen",
                veryBlurry,
            ) { it.copy(sharpen = (it.sharpen + 0.3f).coerceAtMost(1f)) }
        }
        return out.sortedByDescending { it.severe }
    }

    fun applyAll(a: Adjust, list: List<Suggestion>): Adjust =
        list.fold(a) { acc, s -> s.fix?.invoke(acc) ?: acc }

    // ======================= Selbst verbessern =======================

    /**
     * Sucht automatisch gute Einstellungen: misst, wendet Vorschläge an, misst erneut,
     * und behält nur, was die Bewertung wirklich verbessert.
     */
    fun optimize(original: Bitmap): Adjust {
        val m = max(original.width, original.height)
        val small = if (m > 512) {
            val f = 512f / m
            Bitmap.createScaledBitmap(original, max(1, (original.width * f).roundToInt()), max(1, (original.height * f).roundToInt()), true)
        } else original
        try {
            val auto = Enhancer.auto(small)
            val levels = auto.lo to auto.hi
            fun eval(a: Adjust): Metrics {
                val b = Enhancer.apply(small, a)
                return measure(b).also { b.recycle() }
            }
            // Startpunkte: unverändert und die bisherige Automatik – der bessere gewinnt
            var best = Adjust()
            var bestM = eval(best)
            val autoM = eval(auto)
            if (autoM.score > bestM.score) { best = auto; bestM = autoM }

            repeat(5) {
                val sugg = suggest(bestM, best, levels).filter { it.fix != null }
                if (sugg.isEmpty()) return best
                // Erst alle zusammen, sonst einzeln das Beste nehmen
                var improved = false
                val all = applyAll(best, sugg)
                val allM = eval(all)
                if (allM.score > bestM.score) {
                    best = all; bestM = allM; improved = true
                } else {
                    for (s in sugg) {
                        val c = s.fix!!(best)
                        val cm = eval(c)
                        if (cm.score > bestM.score) { best = c; bestM = cm; improved = true }
                    }
                }
                if (!improved) return best
            }
            return best
        } finally {
            if (small !== original) small.recycle()
        }
    }
}
