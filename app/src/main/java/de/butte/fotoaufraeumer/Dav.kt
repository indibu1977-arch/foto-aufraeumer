package de.butte.fotoaufraeumer

import android.content.Context
import android.net.Uri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Wisch-Ziel: Ordner direkt in Nextcloud, z. B. "@dav:/Dirk/Urlaub". */
const val DAV_PREFIX = "@dav:"

fun isDavTarget(t: String?) = t != null && t.startsWith(DAV_PREFIX)
fun davPath(t: String) = t.removePrefix(DAV_PREFIX)

data class DavCfg(
    val url: String,      // z. B. https://192.168.178.60:8099
    val user: String,
    val pass: String,     // Nextcloud-App-Passwort
    val pin: String?,     // SHA-256 des Server-Zertifikats (selbst signiert)
    val remote: String = "", // Unterwegs-Adresse (Tailscale), z. B. https://homeassistant.taileb4fed.ts.net:8099
) {
    val ok get() = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
}

class DavException(msg: String) : Exception(msg)

/**
 * Minimaler WebDAV-Client für Nextcloud.
 * Ein selbst signiertes Zertifikat wird nur akzeptiert, wenn sein Fingerabdruck
 * exakt dem einmal bestätigten (gepinnten) Wert entspricht.
 */
object Dav {
    private const val SP = "dav"
    private val XML = "application/xml; charset=utf-8".toMediaType()

    fun load(ctx: Context): DavCfg {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return DavCfg(
            sp.getString("url", "https://192.168.178.60:8099") ?: "",
            sp.getString("user", "") ?: "",
            sp.getString("pass", "") ?: "",
            sp.getString("pin", null),
            sp.getString("remote", "https://homeassistant.taileb4fed.ts.net:8099") ?: "",
        )
    }

    fun save(ctx: Context, c: DavCfg) {
        val e = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString("url", c.url.trim().trimEnd('/'))
            .putString("user", c.user.trim())
            .putString("pass", c.pass.trim())
            .putString("remote", c.remote.trim().trimEnd('/'))
        if (c.pin == null) e.remove("pin") else e.putString("pin", c.pin)
        e.apply()
    }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    private val sysTm: X509TrustManager by lazy {
        val f = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as java.security.KeyStore?)
        f.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    @Volatile private var cachedClient: Pair<String?, OkHttpClient>? = null

    /** Gemeinsamer Client (auch fürs Streamen und für Vorschaubilder). */
    fun http(c: DavCfg): OkHttpClient {
        cachedClient?.let { if (it.first == c.pin) return it.second }
        return client(c.pin).also { cachedClient = c.pin to it }
    }

    fun auth(c: DavCfg): String = Credentials.basic(c.user, c.pass, Charsets.UTF_8)

    private fun client(pin: String?, capture: ((String) -> Unit)? = null): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
        if (pin != null || capture != null) {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                    val fp = sha256(c[0])
                    capture?.invoke(fp)
                    if (capture != null || fp == pin) return
                    // Sonst nur ein offiziell gültiges Zertifikat (z. B. Tailscale) akzeptieren
                    try { sysTm.checkServerTrusted(c, a) } catch (e: Exception) {
                        throw CertificateException("Zertifikat hat sich geändert")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = sysTm.acceptedIssuers
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            b.sslSocketFactory(ssl.socketFactory, tm)
            // Der Fingerabdruck wird oben geprüft – der Name im Zertifikat passt bei IP-Adressen nicht
            b.hostnameVerifier { host, session ->
                val peer = try { session.peerCertificates.firstOrNull() as? X509Certificate } catch (_: Exception) { null }
                capture != null || (peer != null && sha256(peer) == pin) ||
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
        }
        return b.build()
    }

    /** Holt den Zertifikats-Fingerabdruck, ohne Zugangsdaten zu senden. null bei http. */
    fun fetchFingerprint(baseUrl: String): String? {
        val url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("https://")) return null
        var seen: String? = null
        client(null) { seen = it }.newCall(Request.Builder().url("$url/status.php").build()).execute().close()
        return seen
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    // ---------- Zuhause (WLAN) oder unterwegs (Tailscale)? ----------
    @Volatile private var chosen: String? = null
    @Volatile private var chosenAt = 0L
    @Volatile var usingRemote = false
        private set

    /** Neu prüfen, welche Adresse erreichbar ist (z. B. nach Netzwechsel). */
    fun resetBase() { chosen = null }

    private fun reachable(c: DavCfg, base: String): Boolean = try {
        http(c).newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("$base/status.php").build()).execute().use { it.code < 500 }
    } catch (_: Exception) { false }

    /** Erreichbare Server-Adresse. Blockiert kurz – nur im Hintergrund aufrufen. */
    fun base(c: DavCfg): String {
        val now = System.currentTimeMillis()
        chosen?.let { if (now - chosenAt < 120_000) return it }
        val lan = c.url.trim().trimEnd('/')
        val rem = c.remote.trim().trimEnd('/')
        val pick = when {
            rem.isEmpty() || rem == lan -> lan
            reachable(c, lan) -> lan
            reachable(c, rem) -> rem
            else -> lan
        }
        chosen = pick
        chosenAt = now
        usingRemote = pick != lan
        return pick
    }

    /** Wie [base], aber ohne Netzprüfung (für die Oberfläche). */
    fun lastBase(c: DavCfg): String = chosen ?: c.url.trim().trimEnd('/')

    private fun davUrl(base: String, c: DavCfg, path: String): String {
        val segs = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }
        return base + "/remote.php/dav/files/" + enc(c.user) + "/" + segs
    }

    private fun url(c: DavCfg, path: String): String = davUrl(base(c), c, path)

    /** Datei-Adresse fürs Streamen (Adresse vorher mit [base] bestimmen). */
    fun fileUrl(c: DavCfg, path: String): String = davUrl(lastBase(c), c, path)

    /** Vorschaubild von Nextcloud. */
    fun previewUrl(c: DavCfg, path: String, px: Int = 192): String =
        lastBase(c) + "/index.php/core/preview.png?file=" + enc(path) + "&x=$px&y=$px&a=1&mode=cover"


    private fun req(c: DavCfg, path: String) =
        Request.Builder().url(url(c, path)).header("Authorization", auth(c))

    private fun fail(code: Int, what: String): Nothing = throw DavException(
        when (code) {
            401 -> "Anmeldung abgelehnt – Benutzer oder App-Passwort prüfen"
            404 -> "$what: Ordner nicht gefunden"
            409 -> "$what: Zielordner auf der Platte nicht erreichbar (409) – in Nextcloud unter Externer Speicher prüfen, ob der Ordner grün ist"
            507 -> "Platte voll"
            else -> "$what: Fehler $code"
        }
    )

    /** Unterordner von [path] (relativ zum Benutzer-Stamm), sortiert. */
    fun listFolders(c: DavCfg, path: String): List<String> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val r = req(c, path).header("Depth", "1").method("PROPFIND", body.toRequestBody(XML)).build()
        http(c).newCall(r).execute().use { resp ->
            if (resp.code != 207) fail(resp.code, "Ordner lesen")
            val own = path.split('/').filter { it.isNotEmpty() }
            return parseFolders(resp.body!!.byteStream())
                .map { URLDecoder.decode(it, "UTF-8").trimEnd('/') }
                .mapNotNull { href ->
                    // href = /remote.php/dav/files/<user>/<a>/<b>
                    val rel = href.substringAfter("/remote.php/dav/files/").substringAfter('/', "")
                    val parts = rel.split('/').filter { it.isNotEmpty() }
                    if (parts.size == own.size + 1) parts.last() else null
                }
                .sortedBy { it.lowercase() }
        }
    }

    private fun parseFolders(input: InputStream): List<String> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(input, "UTF-8")
        val out = mutableListOf<String>()
        var href: String? = null
        var isDir = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "response" -> { href = null; isDir = false }
                    "href" -> href = p.nextText()
                    "collection" -> isDir = true
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "response") {
                href?.let { if (isDir) out += it }
            }
            ev = p.next()
        }
        return out
    }

    fun mkdir(c: DavCfg, path: String) {
        val r = req(c, path).method("MKCOL", null).build()
        http(c).newCall(r).execute().use { resp ->
            if (resp.code != 201 && resp.code != 405) fail(resp.code, "Ordner anlegen") // 405 = gibt es schon
        }
    }

    /** Größe einer Datei auf der Platte, null wenn es sie nicht gibt. */
    fun remoteSize(c: DavCfg, path: String): Long? {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/></d:prop></d:propfind>"""
        val r = req(c, path).header("Depth", "0").method("PROPFIND", body.toRequestBody(XML)).build()
        http(c).newCall(r).execute().use { resp ->
            if (resp.code == 404) return null
            if (resp.code != 207) fail(resp.code, "Prüfen")
            val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            p.setInput(resp.body!!.byteStream(), "UTF-8")
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "getcontentlength") return p.nextText().trim().toLongOrNull() ?: -1L
                ev = p.next()
            }
            return -1L
        }
    }

    /** Lädt ein Foto hoch, ohne Vorhandenes zu überschreiben. Gibt den verwendeten Dateinamen zurück. */
    fun upload(ctx: Context, c: DavCfg, folder: String, name: String, uri: Uri, size: Long): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val hc = http(c)
        for (i in 0..50) {
            val n = if (i == 0) name else "${base}_$i$ext"
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = if (size > 0) size else -1L
                override fun writeTo(sink: BufferedSink) {
                    val inp = ctx.contentResolver.openInputStream(uri) ?: throw DavException("Foto nicht lesbar")
                    inp.source().use { sink.writeAll(it) }
                }
            }
            val r = req(c, folder.trimEnd('/') + "/" + n).header("If-None-Match", "*").put(body).build()
            hc.newCall(r).execute().use { resp ->
                if (resp.code == 201 || resp.code == 204) return n
                if (resp.code != 412) fail(resp.code, "Hochladen")
            }
        }
        throw DavException("Hochladen: zu viele gleichnamige Dateien")
    }

    // ---------- Dateien (Platten-Browser) ----------

    /** Inhalt eines Ordners: Unterordner zuerst, dann Dateien – jeweils nach Name. */
    fun list(c: DavCfg, path: String): List<DavEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/>""" +
            """</d:prop></d:propfind>"""
        val r = req(c, path).header("Depth", "1").method("PROPFIND", body.toRequestBody(XML)).build()
        http(c).newCall(r).execute().use { resp ->
            if (resp.code != 207) fail(resp.code, "Ordner lesen")
            val own = path.split('/').filter { it.isNotEmpty() }
            val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            p.setInput(resp.body!!.byteStream(), "UTF-8")
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            val out = mutableListOf<DavEntry>()
            var href: String? = null
            var dir = false
            var size = 0L
            var mod = 0L
            var type = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "response" -> { href = null; dir = false; size = 0; mod = 0; type = "" }
                        "href" -> href = p.nextText()
                        "collection" -> dir = true
                        "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0
                        "getlastmodified" -> mod = try { fmt.parse(p.nextText().trim())?.time ?: 0 } catch (_: Exception) { 0 }
                        "getcontenttype" -> type = p.nextText().trim()
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "response") {
                    href?.let { h ->
                        val rel = URLDecoder.decode(h.replace("+", "%2B"), "UTF-8").trimEnd('/')
                            .substringAfter("/remote.php/dav/files/").substringAfter('/', "")
                        val parts = rel.split('/').filter { it.isNotEmpty() }
                        if (parts.size == own.size + 1) {
                            out += DavEntry(parts.last(), "/" + parts.joinToString("/"), dir, size, mod, type)
                        }
                    }
                }
                ev = p.next()
            }
            return out.sortedWith(compareBy<DavEntry>({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    /** Datei herunterladen. onBytes meldet die bisher geladenen Bytes. */
    fun download(c: DavCfg, path: String, out: java.io.OutputStream, onBytes: (Long) -> Unit = {}) {
        val r = req(c, path).get().build()
        http(c).newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) fail(resp.code, "Herunterladen")
            resp.body!!.byteStream().use { inp ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total - last > 512 * 1024) { onBytes(total); last = total }
                }
                onBytes(total)
            }
        }
    }

    /** Datei/Ordner auf der Platte verschieben (gleicher Server). */
    fun move(c: DavCfg, from: String, to: String) {
        val r = req(c, from).method("MOVE", null)
            .header("Destination", url(c, to))
            .header("Overwrite", "F")
            .build()
        http(c).newCall(r).execute().use { resp ->
            if (resp.code == 412) throw DavException("„${to.substringAfterLast('/')}“ gibt es dort schon")
            if (resp.code == 409 && !isDir(c, to.trimEnd('/').substringBeforeLast('/', "/"))) fail(409, "Verschieben")
            if (resp.code != 201 && resp.code != 204) {
                val msg = try { Regex("<s:message>(.*?)</s:message>").find(resp.body?.string() ?: "")?.groupValues?.get(1) } catch (_: Exception) { null }
                throw DavException("Verschieben: Fehler ${resp.code}" + (msg?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            }
        }
    }

    /**
     * Ordner/Datei verschieben – auch zwischen SD-Karte und Festplatte.
     * Klappt MOVE nicht (Nextcloud lehnt z. B. Ordner über Speichergrenzen ab), wird Datei für Datei
     * verschoben bzw. über das Handy kopiert und das Original danach gelöscht.
     */
    fun moveTree(c: DavCfg, from: String, to: String, tmp: java.io.File, onFile: (String) -> Unit = {}): Int {
        try { move(c, from, to); return 1 } catch (e: DavException) {
            if (e.message?.contains("gibt es dort schon") == true && !isDir(c, from)) throw e
        }
        if (!isDir(c, from)) { copyFile(c, from, to, tmp); delete(c, from); onFile(from); return 1 }
        mkdir(c, to)
        var n = 0
        for (e in list(c, from)) {
            val dst = to.trimEnd('/') + "/" + e.name
            if (e.isDir) n += moveTree(c, e.path, dst, tmp, onFile)
            else {
                if (remoteSize(c, dst) == e.size) { delete(c, e.path); n++; onFile(e.name); continue } // schon dort
                try { move(c, e.path, dst) } catch (_: DavException) { copyFile(c, e.path, dst, tmp); delete(c, e.path) }
                n++
                onFile(e.name)
            }
        }
        if (list(c, from).isEmpty()) delete(c, from)
        return n
    }

    private fun isDir(c: DavCfg, path: String): Boolean = try {
        val r = req(c, path).header("Depth", "0").method("PROPFIND",
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>""".toRequestBody(XML)).build()
        http(c).newCall(r).execute().use { it.code == 207 && (it.body?.string() ?: "").contains("collection") }
    } catch (_: Exception) { false }

    /** Datei über das Handy kopieren (herunterladen in den Cache, wieder hochladen, Größe prüfen). */
    private fun copyFile(c: DavCfg, from: String, to: String, tmp: java.io.File) {
        val f = java.io.File(tmp, "mv_" + System.nanoTime())
        try {
            f.outputStream().use { download(c, from, it) }
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = f.length()
                override fun writeTo(sink: BufferedSink) { f.inputStream().source().use { sink.writeAll(it) } }
            }
            val r = req(c, to).put(body).build()
            http(c).newCall(r).execute().use { resp ->
                if (resp.code != 201 && resp.code != 204) fail(resp.code, "Kopieren")
            }
            if (remoteSize(c, to) != f.length()) throw DavException("Kopieren: Größe stimmt nicht – Original bleibt")
        } finally { f.delete() }
    }

    fun delete(c: DavCfg, path: String) {
        val r = req(c, path).delete().build()
        http(c).newCall(r).execute().use { resp ->
            if (resp.code != 204 && resp.code != 200 && resp.code != 404) fail(resp.code, "Löschen")
        }
    }
}

/** Eintrag im Platten-Browser. path = relativ zum Benutzer-Stamm, z. B. "/Dirk/Urlaub/a.jpg". */
data class DavEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val type: String,
) {
    private val ext get() = name.substringAfterLast('.', "").lowercase()
    val isImage get() = type.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
    val isVideo get() = type.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp")
    val isAudio get() = type.startsWith("audio/") || ext in setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")
    val mime get() = type.ifEmpty {
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
