package de.butte.fotoaufraeumer

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Ein Foto, das schon auf der Platte liegt. */
data class Uploaded(val folder: String, val remoteName: String, val time: Long)

/**
 * Merkt sich dauerhaft, welche Fotos schon hochgeladen wurden.
 * Schlüssel = Dateiname + Größe, damit es auch nach einem Neuaufbau der Galerie
 * (neue MediaStore-IDs) noch passt.
 */
object UploadLog {
    private const val FILE = "uploads.json"

    fun key(p: Photo) = "${p.name}|${p.size}"

    fun load(ctx: Context): Map<String, Uploaded> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            buildMap {
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    put(k, Uploaded(e.getString("f"), e.optString("n", ""), e.optLong("t", 0)))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun save(ctx: Context, m: Map<String, Uploaded>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONObject().put("f", v.folder).put("n", v.remoteName).put("t", v.time))
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }
}
