package de.butte.fotoaufraeumer

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Wie ein Foto aufgenommen wird. */
enum class Shot {
    SINGLE,   // ein Bild
    STACK,    // mehrere Bilder ausrichten & mitteln (weniger Rauschen bei Nacht)
    BRACKET,  // Belichtungsreihe → HDR-Fusion
    BURST,    // Serie → schärfstes Bild behalten
}

/** Hersteller-Modus (CameraX Extensions), falls das Handy ihn freigibt. */
enum class Vendor { NONE, NIGHT, HDR, BOKEH, FACE_RETOUCH }

/**
 * Rezept für eine Szene. Belichtungszeiten in Sekunden.
 * tMax = längste Zeit aus der Hand, tMaxStill = längste Zeit, wenn das Handy ruhig liegt (Stativ),
 * tMin = kürzeste nötige Zeit gegen Bewegungsunschärfe (0 = egal).
 */
data class Recipe(
    val ev: Float = 0f,
    val tMax: Double = 1.0 / 30,
    val tMaxStill: Double = 1.0 / 30,
    val tFix: Double = 0.0,          // feste Zeit (z. B. Bildschirm, Mond)
    val tMin: Double = 0.0,
    val isoMax: Int = 3200,
    val isoPref: Int = 0,            // bevorzugte ISO (0 = automatisch)
    val awb: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val af: Int = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
    val focusInfinity: Boolean = false,
    val nr: Int = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
    val edge: Int = CameraMetadata.EDGE_MODE_HIGH_QUALITY,
    val sceneMode: Int = -1,         // Android-Szenenmodus als Rückfall
    val fps: Int = 0,                // -1 = möglichst langsam (lange Zeiten), +1 = möglichst schnell
    val shot: Shot = Shot.SINGLE,
    val frames: Int = 1,
    val vendor: Vendor = Vendor.NONE,
    val faceMetering: Boolean = true,
    val centerMetering: Boolean = false,
    val flash: Int = 0,              // 0 aus, 1 auto
    val zoom: Float = 0f,            // 0 = nicht ändern
    val waitStill: Boolean = false,  // erst auslösen, wenn das Handy ruhig ist
)

enum class Scene(val emoji: String, val label: String, val hint: String, val r: Recipe) {
    STANDARD("📷", "Standard", "Ausgewogene Automatik", Recipe()),
    NIGHT("🌙", "Nacht", "Lange Belichtung aus der Hand, 6 Bilder kombiniert gegen Rauschen",
        Recipe(ev = 0f, tMax = 1.0 / 8, tMaxStill = 1.0 / 3, isoMax = 6400, fps = -1, shot = Shot.STACK, frames = 6,
            vendor = Vendor.NIGHT, sceneMode = CameraMetadata.CONTROL_SCENE_MODE_NIGHT, waitStill = true)),
    NIGHT_TRIPOD("🔭", "Nacht Stativ", "Handy liegt ruhig: sehr lange Belichtung, niedrige ISO",
        Recipe(tMax = 1.0 / 4, tMaxStill = 4.0, isoMax = 1600, isoPref = 100, fps = -1, shot = Shot.STACK, frames = 3,
            vendor = Vendor.NIGHT, waitStill = true)),
    STARS("✨", "Sterne", "Nur mit Stativ: bis 30 s, Fokus unendlich",
        Recipe(ev = 0.7f, tMax = 1.0 / 4, tMaxStill = 30.0, isoMax = 3200, isoPref = 800, focusInfinity = true,
            af = CameraMetadata.CONTROL_AF_MODE_OFF, fps = -1, shot = Shot.STACK, frames = 2, waitStill = true)),
    CITY_NIGHT("🌃", "Stadtlichter", "Lichter nicht ausbrennen lassen, Nacht-Kombination",
        Recipe(ev = -0.7f, tMax = 1.0 / 10, tMaxStill = 1.0, isoMax = 3200, fps = -1, shot = Shot.STACK, frames = 5,
            vendor = Vendor.NIGHT, sceneMode = CameraMetadata.CONTROL_SCENE_MODE_NIGHT, waitStill = true)),
    LIGHT_TRAILS("🚗", "Lichtspuren", "Langzeitbelichtung für Autolichter/Wasser – Stativ nötig",
        Recipe(ev = -0.3f, tMax = 1.0 / 4, tMaxStill = 8.0, isoMax = 400, isoPref = 50, fps = -1, waitStill = true)),
    FIREWORKS("🎆", "Feuerwerk", "1–2 s Belichtung, Fokus unendlich, dunkler Himmel",
        Recipe(ev = -1f, tFix = 1.0, tMax = 1.0 / 4, tMaxStill = 2.0, isoMax = 400, isoPref = 100, focusInfinity = true,
            af = CameraMetadata.CONTROL_AF_MODE_OFF, sceneMode = CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS, fps = -1)),
    MOON("🌕", "Mond", "Mond korrekt belichtet statt weißer Fleck, Zoom & Fokus unendlich",
        Recipe(ev = -2.5f, tMax = 1.0 / 125, tMaxStill = 1.0 / 60, isoPref = 50, isoMax = 400, focusInfinity = true,
            af = CameraMetadata.CONTROL_AF_MODE_OFF, centerMetering = true, faceMetering = false, zoom = 10f,
            shot = Shot.BURST, frames = 5, waitStill = true)),
    DUSK("🌆", "Dämmerung", "Blaue Stunde: Farben erhalten, ruhige Hand",
        Recipe(ev = -0.3f, tMax = 1.0 / 15, tMaxStill = 1.0 / 2, isoMax = 3200, fps = -1,
            awb = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, shot = Shot.STACK, frames = 4, vendor = Vendor.NIGHT, waitStill = true)),
    SUNSET("🌅", "Sonnenuntergang", "Warme Farben behalten, Himmel nicht ausbrennen",
        Recipe(ev = -0.7f, awb = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, sceneMode = CameraMetadata.CONTROL_SCENE_MODE_SUNSET,
            shot = Shot.BRACKET, vendor = Vendor.HDR)),
    BACKLIGHT("🔆", "Gegenlicht", "Belichtungsreihe + HDR: Vordergrund hell, Himmel erhalten",
        Recipe(ev = 0.7f, centerMetering = true, shot = Shot.BRACKET, vendor = Vendor.HDR,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_HDR)),
    BACKLIGHT_PORTRAIT("🧑‍🦱", "Gegenlicht-Porträt", "Belichtung aufs Gesicht, Hintergrund per HDR retten",
        Recipe(ev = 0.3f, faceMetering = true, shot = Shot.BRACKET, vendor = Vendor.HDR, flash = 1)),
    HDR("🌗", "Hoher Kontrast", "Helle und dunkle Bereiche gleichzeitig – HDR",
        Recipe(ev = -0.3f, shot = Shot.BRACKET, vendor = Vendor.HDR, sceneMode = CameraMetadata.CONTROL_SCENE_MODE_HDR)),
    PORTRAIT("🙂", "Porträt", "Belichtung & Fokus aufs Gesicht, Hauttöne etwas heller",
        Recipe(ev = 0.3f, tMax = 1.0 / 60, faceMetering = true, vendor = Vendor.BOKEH,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT, shot = Shot.BURST, frames = 3)),
    GROUP("👨‍👩‍👧", "Gruppe", "Mehrere Gesichter: Serie, schärfstes Bild wird behalten",
        Recipe(ev = 0.3f, tMax = 1.0 / 100, faceMetering = true, shot = Shot.BURST, frames = 4)),
    SELFIE("🤳", "Selfie", "Frontkamera, Gesicht hell, Haut weicher",
        Recipe(ev = 0.3f, tMax = 1.0 / 60, faceMetering = true, vendor = Vendor.FACE_RETOUCH, shot = Shot.BURST, frames = 3)),
    ACTION("🏃", "Sport / Action", "Sehr kurze Belichtung (≤ 1/500 s), Serie, schärfstes behalten",
        Recipe(tMin = 1.0 / 500, tMax = 1.0 / 500, isoMax = 6400, fps = 1, nr = CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_SPORTS, shot = Shot.BURST, frames = 6)),
    KIDS_PETS("🐕", "Kinder / Tiere", "Kurze Belichtung (≤ 1/250 s), Serie, Gesicht im Fokus",
        Recipe(ev = 0.3f, tMin = 1.0 / 250, tMax = 1.0 / 250, isoMax = 6400, fps = 1, shot = Shot.BURST, frames = 5)),
    MACRO("🌼", "Makro / Nah", "Nahfokus, ruhige Hand, mehr Details",
        Recipe(tMax = 1.0 / 60, af = CameraMetadata.CONTROL_AF_MODE_MACRO, centerMetering = true, shot = Shot.BURST, frames = 3,
            waitStill = true)),
    FOOD("🍝", "Essen", "Nah, etwas heller und wärmer, kräftige Farben",
        Recipe(ev = 0.3f, tMax = 1.0 / 30, af = CameraMetadata.CONTROL_AF_MODE_AUTO, centerMetering = true,
            awb = CameraMetadata.CONTROL_AWB_MODE_AUTO, shot = Shot.BURST, frames = 3)),
    DOCUMENT("📄", "Dokument / Text", "Gestochen scharf, Papier weiß",
        Recipe(ev = 0.5f, tMax = 1.0 / 60, af = CameraMetadata.CONTROL_AF_MODE_AUTO, centerMetering = true,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_BARCODE, shot = Shot.BURST, frames = 3, waitStill = true)),
    SCREEN("🖥️", "Bildschirm", "Keine Streifen: 1/50 s, etwas dunkler",
        Recipe(ev = -0.7f, tFix = 1.0 / 50, isoMax = 1600, centerMetering = true)),
    SNOW("⛄", "Schnee / Strand", "Weiß bleibt weiß statt grau (+1 EV)",
        Recipe(ev = 1.0f, awb = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, isoPref = 50,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_SNOW)),
    LANDSCAPE("🏞️", "Landschaft", "Niedrige ISO, maximale Schärfe, Himmel geschützt",
        Recipe(ev = -0.3f, isoPref = 50, isoMax = 400, faceMetering = false,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE, vendor = Vendor.HDR)),
    CLOUDY("☁️", "Bewölkt", "Graues Licht wärmer machen",
        Recipe(ev = 0.3f, awb = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)),
    FOG("🌫️", "Nebel / Dunst", "Etwas heller, damit Nebel nicht grau wirkt",
        Recipe(ev = 0.7f, awb = CameraMetadata.CONTROL_AWB_MODE_AUTO)),
    INDOOR("🏠", "Innen", "Kunstlicht, flimmerfrei (50 Hz), ruhige Hand",
        Recipe(tMax = 1.0 / 25, tMaxStill = 1.0 / 8, isoMax = 3200, fps = -1, shot = Shot.BURST, frames = 3)),
    INDOOR_WARM("💡", "Glühbirne / warmes Licht", "Gelbstich ausgleichen",
        Recipe(tMax = 1.0 / 25, isoMax = 3200, awb = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, fps = -1, shot = Shot.BURST, frames = 3)),
    FLUORESCENT("🔦", "Leuchtstoff", "Grünstich ausgleichen, flimmerfrei",
        Recipe(tMax = 1.0 / 50, awb = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)),
    PARTY("🥳", "Party / Kerzenlicht", "Dunkel innen: Blitz auf Auto, Stimmung behalten",
        Recipe(ev = -0.3f, tMax = 1.0 / 30, isoMax = 6400, flash = 1, sceneMode = CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT,
            shot = Shot.BURST, frames = 3)),
    STAGE("🎤", "Bühne / Konzert", "Scheinwerfer: dunkler belichten, kurze Zeit",
        Recipe(ev = -1.3f, tMax = 1.0 / 125, tMin = 1.0 / 125, isoMax = 6400, centerMetering = true,
            sceneMode = CameraMetadata.CONTROL_SCENE_MODE_THEATRE, shot = Shot.BURST, frames = 4)),
    SHAKY("🫨", "Unruhige Hand", "Wackeln erkannt: kürzere Zeit, Serie, schärfstes behalten",
        Recipe(tMax = 1.0 / 125, isoMax = 6400, shot = Shot.BURST, frames = 5, waitStill = true)),
    ;

    companion object {
        /** Szenen, die die Automatik erkennen kann (alles andere nur manuell). */
        val manualOnly = setOf(LIGHT_TRAILS, FIREWORKS, STARS, SELFIE)
    }
}

/** Kennwerte aus einem Vorschaubild (aufrecht, verkleinert). */
data class FrameStats(
    val mean: Float = 128f,          // mittlere Helligkeit 0..255
    val centerMean: Float = 128f,
    val borderMean: Float = 128f,
    val topMean: Float = 128f,
    val clipHigh: Float = 0f,        // Anteil > 250
    val clipLow: Float = 0f,         // Anteil < 10
    val spread: Float = 200f,        // 2..98-%-Spanne
    val rb: Float = 1f,              // Rot/Blau-Verhältnis (gesamt)
    val gm: Float = 1f,              // Grün / ((R+B)/2)
    val topRb: Float = 1f,           // Rot/Blau im oberen Drittel (Himmel)
    val sat: Float = 0.3f,           // mittlere Sättigung 0..1
    val motion: Float = 0f,          // Unterschied zum vorigen Bild 0..255
    val edges: Float = 0f,           // Kantendichte
    val pointLights: Int = 0,        // kleine helle Lichtpunkte
    val blob: Boolean = false,       // ein kleiner heller Fleck im Dunkeln (Mond)
    val whiteish: Float = 0f,        // Anteil helle, farblose Pixel
    val bimodal: Boolean = false,    // Papier + Schrift
)

/** Daten aus dem Kamera-Ergebnis. */
data class CamMeta(
    val iso: Int = 0,
    val exposure: Double = 0.0,      // Sekunden
    val aperture: Float = 1.8f,
    val focusDiopter: Float = 0f,    // 1/m, 0 = unendlich
    val faceCount: Int = 0,
    val faceArea: Float = 0f,        // Anteil am Bild
    val evComp: Float = 0f,          // aktuell eingestellte Korrektur
) {
    /** Szenenhelligkeit EV100 (ohne Einfluss der Belichtungskorrektur). */
    val ev100: Float
        get() {
            if (iso <= 0 || exposure <= 0) return 10f
            val ev = (ln(aperture.toDouble() * aperture / exposure) - ln(iso / 100.0)) / ln(2.0)
            return ev.toFloat() + evComp
        }
}

object SceneDetector {
    const val GW = 64
    const val GH = 48

    private var prev: FloatArray? = null

    /**
     * rgba: Zeilen des Vorschaubilds (RGBA_8888), rot = Drehung bis aufrecht.
     * Wird auf ein aufrechtes Raster (GW×GH bzw. GH×GW) abgetastet.
     */
    fun stats(buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int, rot: Int): FrameStats {
        val up90 = rot == 90 || rot == 270
        val uw = if (up90) GH else GW
        val uh = if (up90) GW else GH
        val n = uw * uh
        val lum = FloatArray(n)
        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        for (v in 0 until uh) for (u in 0 until uw) {
            // aufrechte Koordinate (u,v) → Rasterposition im Sensorbild
            val (gx, gy) = when (rot) {
                90 -> Pair(v, GH - 1 - u)
                180 -> Pair(GW - 1 - u, GH - 1 - v)
                270 -> Pair(GW - 1 - v, u)
                else -> Pair(u, v)
            }
            val x = ((gx + 0.5f) * w / GW).toInt().coerceIn(0, w - 1)
            val y = ((gy + 0.5f) * h / GH).toInt().coerceIn(0, h - 1)
            val o = y * rowStride + x * 4
            val r = (buf.get(o).toInt() and 255).toFloat()
            val g = (buf.get(o + 1).toInt() and 255).toFloat()
            val b = (buf.get(o + 2).toInt() and 255).toFloat()
            val i = v * uw + u
            rr[i] = r; gg[i] = g; bb[i] = b
            lum[i] = r * 0.299f + g * 0.587f + b * 0.114f
        }
        return compute(lum, rr, gg, bb, uw, uh)
    }

    private fun compute(lum: FloatArray, rr: FloatArray, gg: FloatArray, bb: FloatArray, w: Int, h: Int): FrameStats {
        val n = lum.size
        var sum = 0f; var cSum = 0f; var cN = 0; var bSum = 0f; var bN = 0; var tSum = 0f; var tN = 0
        var hi = 0; var lo = 0; var sr = 0f; var sg = 0f; var sb = 0f; var tr = 0f; var tb = 0f
        var satSum = 0f; var white = 0
        val hist = IntArray(256)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val l = lum[i]
            sum += l
            hist[l.toInt().coerceIn(0, 255)]++
            if (l > 250) hi++
            if (l < 10) lo++
            val inCenter = x > w * 0.3f && x < w * 0.7f && y > h * 0.3f && y < h * 0.75f
            if (inCenter) { cSum += l; cN++ } else { bSum += l; bN++ }
            if (y < h / 3) { tSum += l; tN++; tr += rr[i]; tb += bb[i] }
            sr += rr[i]; sg += gg[i]; sb += bb[i]
            val mx = max(rr[i], max(gg[i], bb[i]))
            val mn = min(rr[i], min(gg[i], bb[i]))
            val s = if (mx > 1f) (mx - mn) / mx else 0f
            satSum += s
            if (l > 190 && s < 0.12f) white++
        }
        fun pct(p: Float): Int {
            val t = (n * p).toInt()
            var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= t) return i }
            return 255
        }
        // Kanten
        var e = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            e += abs(lum[i] - lum[i + 1]) + abs(lum[i] - lum[i + w])
        }
        val edges = e / ((w - 2) * (h - 2))
        // Bewegung
        val p = prev
        var motion = 0f
        if (p != null && p.size == n) {
            var d = 0f
            for (i in 0 until n) d += abs(lum[i] - p[i])
            motion = d / n
        }
        prev = lum.copyOf()
        // Lichtpunkte: helle Pixel, deren Umgebung dunkel ist
        var points = 0
        var brightCount = 0
        var bx = 0f; var by = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (lum[i] > 235) {
                brightCount++
                bx += x; by += y
                val ring = (lum[i - 1] + lum[i + 1] + lum[i - w] + lum[i + w]) / 4f
                if (ring < 120) points++
            }
        }
        val mean = sum / n
        // Mond: wenige helle Pixel, eng beieinander, Rest dunkel
        var blob = false
        if (brightCount in 1..(n / 40) && mean < 45) {
            val cx = bx / brightCount
            val cy = by / brightCount
            var spreadB = 0f
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                if (lum[y * w + x] > 235) spreadB += sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            }
            blob = spreadB / brightCount < 4f && points <= 3
        }
        // Dokument: viel Weiß + dunkle Schrift, kaum Mitteltöne
        var mid = 0
        for (i in 60..180) mid += hist[i]
        val bimodal = white > n * 0.35f && mid < n * 0.25f && edges > 6f

        val avgR = sr / n; val avgG = sg / n; val avgB = sb / n
        return FrameStats(
            mean = mean,
            centerMean = if (cN > 0) cSum / cN else mean,
            borderMean = if (bN > 0) bSum / bN else mean,
            topMean = if (tN > 0) tSum / tN else mean,
            clipHigh = hi.toFloat() / n,
            clipLow = lo.toFloat() / n,
            spread = (pct(0.98f) - pct(0.02f)).toFloat(),
            rb = (avgR + 1f) / (avgB + 1f),
            gm = (avgG + 1f) / ((avgR + avgB) / 2f + 1f),
            topRb = (tr + 1f) / (tb + 1f),
            sat = satSum / n,
            motion = motion,
            edges = edges,
            pointLights = points,
            blob = blob,
            whiteish = white.toFloat() / n,
            bimodal = bimodal,
        )
    }

    /** Szene bestimmen. Gibt Szene + Begründung zurück. */
    fun classify(s: FrameStats, m: CamMeta, still: Boolean, shake: Float, front: Boolean): Pair<Scene, String> {
        val ev = m.ev100
        val evS = "EV ${"%.1f".format(ev)}"
        val faces = m.faceCount
        val near = m.focusDiopter > 6f      // näher als ~16 cm
        val closeish = m.focusDiopter > 2f  // näher als 50 cm
        val far = m.focusDiopter in 0f..0.3f

        if (front) return (if (s.borderMean - s.centerMean > 50) Scene.BACKLIGHT_PORTRAIT else Scene.SELFIE) to "Frontkamera"
        if (s.blob && ev < 6) return Scene.MOON to "kleiner heller Fleck im Dunkeln"
        if (ev < 2.5f) {
            return when {
                still -> Scene.NIGHT_TRIPOD to "sehr dunkel ($evS), Handy liegt ruhig"
                s.pointLights > 6 -> Scene.CITY_NIGHT to "dunkel mit vielen Lichtern ($evS)"
                else -> Scene.NIGHT to "sehr dunkel ($evS)"
            }
        }
        if (ev < 5f && s.pointLights > 8 && s.clipHigh > 0.004f) return Scene.CITY_NIGHT to "Lichter in der Dunkelheit ($evS)"
        if (ev < 6.5f && s.centerMean > s.borderMean + 60 && s.clipHigh > 0.03f && faces == 0)
            return Scene.STAGE to "Scheinwerfer auf dunklem Hintergrund"
        if (shake > 0.35f && ev < 10) return Scene.SHAKY to "starkes Wackeln erkannt"
        // Gegenlicht
        val backlit = (s.borderMean - s.centerMean > 45 && s.clipHigh > 0.02f) || (s.clipHigh > 0.08f && s.clipLow > 0.12f)
        if (backlit && faces > 0) return Scene.BACKLIGHT_PORTRAIT to "Gesicht vor hellem Hintergrund"
        if (backlit && ev > 7) return Scene.BACKLIGHT to "Motiv dunkel, Hintergrund hell"
        if (s.motion > 14f) {
            return if (faces > 0 || closeish) Scene.KIDS_PETS to "Bewegung erkannt" else Scene.ACTION to "schnelle Bewegung"
        }
        if (faces >= 3) return Scene.GROUP to "$faces Gesichter"
        if (faces > 0 && m.faceArea > 0.03f) return Scene.PORTRAIT to "Gesicht erkannt"
        if (s.bimodal && closeish) return Scene.DOCUMENT to "Papier mit Schrift"
        if (near) {
            return if (s.sat > 0.35f && s.rb > 1.15f && ev in 4f..11f) Scene.FOOD to "nah, warme kräftige Farben"
            else Scene.MACRO to "sehr nah (${(100 / max(0.1f, m.focusDiopter)).toInt()} cm)"
        }
        if (ev < 6.5f) {
            val blue = s.rb < 0.85f
            return when {
                blue && s.topMean > s.mean -> Scene.DUSK to "dämmrig, bläulich ($evS)"
                s.rb > 1.6f && s.sat > 0.3f -> Scene.PARTY to "dunkel, Kerzen/warmes Licht"
                s.rb > 1.35f -> Scene.INDOOR_WARM to "Glühbirnen-Licht ($evS)"
                s.gm > 1.12f -> Scene.FLUORESCENT to "Grünstich (Leuchtstoff)"
                else -> Scene.INDOOR to "drinnen / wenig Licht ($evS)"
            }
        }
        if (ev < 10f) {
            if (s.rb > 1.35f) return Scene.INDOOR_WARM to "warmes Kunstlicht ($evS)"
            if (s.gm > 1.12f) return Scene.FLUORESCENT to "Grünstich (Leuchtstoff)"
        }
        if (s.topRb > 1.45f && s.topMean > 110 && ev in 7f..13.5f) return Scene.SUNSET to "orangefarbener Himmel"
        if (ev in 4f..9f && s.rb < 0.85f && s.topMean > s.mean + 15) return Scene.DUSK to "blaue Stunde"
        if (ev > 13.5f && s.whiteish > 0.35f) return Scene.SNOW to "viel Weiß bei hellem Licht ($evS)"
        if (s.spread < 90 && s.mean > 100 && s.sat < 0.15f) return Scene.FOG to "wenig Kontrast, grau"
        if (s.clipHigh > 0.05f && s.clipLow > 0.08f) return Scene.HDR to "helle & dunkle Bereiche"
        if (ev in 10f..13f && s.sat < 0.18f && s.topMean > s.mean) return Scene.CLOUDY to "graues Tageslicht"
        if (ev > 11.5f && far) return Scene.LANDSCAPE to "Tageslicht, Fokus weit weg"
        if (ev < 10f) return Scene.INDOOR to "drinnen ($evS)"
        return Scene.STANDARD to "normales Licht ($evS)"
    }
}
