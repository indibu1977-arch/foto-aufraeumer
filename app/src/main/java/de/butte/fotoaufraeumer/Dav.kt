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
        )
    }

    fun save(ctx: Context, c: DavCfg) {
        val e = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString("url", c.url.trim().trimEnd('/'))
            .putString("user", c.user.trim())
            .putString("pass", c.pass.trim())
        if (c.pin == null) e.remove("pin") else e.putString("pin", c.pin)
        e.apply()
    }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

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
                    if (capture == null && fp != pin) throw CertificateException("Zertifikat hat sich geändert")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            b.sslSocketFactory(ssl.socketFactory, tm)
            // Der Fingerabdruck wird oben geprüft – der Name im Zertifikat passt bei IP-Adressen nicht
            b.hostnameVerifier { _, _ -> true }
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
    private fun url(c: DavCfg, path: String): String {
        val segs = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }
        return c.url.trimEnd('/') + "/remote.php/dav/files/" + enc(c.user) + "/" + segs
    }

    private fun req(c: DavCfg, path: String) =
        Request.Builder().url(url(c, path)).header("Authorization", Credentials.basic(c.user, c.pass, Charsets.UTF_8))

    private fun fail(code: Int, what: String): Nothing = throw DavException(
        when (code) {
            401 -> "Anmeldung abgelehnt – Benutzer oder App-Passwort prüfen"
            404 -> "$what: Ordner nicht gefunden"
            507 -> "Platte voll"
            else -> "$what: Fehler $code"
        }
    )

    /** Unterordner von [path] (relativ zum Benutzer-Stamm), sortiert. */
    fun listFolders(c: DavCfg, path: String): List<String> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val r = req(c, path).header("Depth", "1").method("PROPFIND", body.toRequestBody(XML)).build()
        client(c.pin).newCall(r).execute().use { resp ->
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
        client(c.pin).newCall(r).execute().use { resp ->
            if (resp.code != 201 && resp.code != 405) fail(resp.code, "Ordner anlegen") // 405 = gibt es schon
        }
    }

    /** Lädt ein Foto hoch, ohne Vorhandenes zu überschreiben. Gibt den verwendeten Dateinamen zurück. */
    fun upload(ctx: Context, c: DavCfg, folder: String, name: String, uri: Uri, size: Long): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val http = client(c.pin)
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
            http.newCall(r).execute().use { resp ->
                if (resp.code == 201 || resp.code == 204) return n
                if (resp.code != 412) fail(resp.code, "Hochladen")
            }
        }
        throw DavException("Hochladen: zu viele gleichnamige Dateien")
    }
}
