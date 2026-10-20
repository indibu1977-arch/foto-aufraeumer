package de.butte.fotoaufraeumer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Eine Datei im privaten Ordner. */
data class VaultItem(val file: File, val name: String, val size: Long, val added: Long)

/**
 * Privater Ordner: Fotos liegen im internen App-Speicher.
 * Andere Apps (Galerie, Google Fotos, Dateimanager) sehen sie nicht.
 */
object Vault {
    /** Sonderwert für eine Wischrichtung. */
    const val TARGET = "@privat"

    private fun dir(ctx: Context): File = File(ctx.filesDir, "privat").apply { mkdirs() }

    fun list(ctx: Context): List<VaultItem> =
        (dir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { VaultItem(it, it.name.substringAfter('_'), it.length(), it.lastModified()) }
            .sortedByDescending { it.added }

    /** Kopiert ein Foto in den privaten Ordner. Gibt die Datei zurück, wenn die Kopie vollständig ist. */
    fun importPhoto(ctx: Context, p: Photo): File? {
        val safe = p.name.ifBlank { "Foto.jpg" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir(ctx), "${System.currentTimeMillis()}${p.id}_$safe")
        val tmp = File(target.path + ".tmp")
        return try {
            val n = ctx.contentResolver.openInputStream(p.uri)?.use { ins ->
                tmp.outputStream().use { ins.copyTo(it, 64 * 1024) }
            } ?: return null
            if (n <= 0 || (p.size > 0 && n != p.size)) {
                tmp.delete(); null
            } else if (tmp.renameTo(target)) {
                target
            } else {
                tmp.delete(); null
            }
        } catch (e: Exception) {
            tmp.delete(); null
        }
    }

    /** Legt das Foto wieder in der Galerie ab (Pictures/Wiederhergestellt) und entfernt es aus dem privaten Ordner. */
    fun restore(ctx: Context, item: VaultItem): Uri? {
        val cr = ctx.contentResolver
        val mime = when (item.name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Wiederhergestellt/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            cr.openOutputStream(uri)?.use { out -> item.file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException()
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            item.file.delete()
            uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    fun delete(item: VaultItem): Boolean = item.file.delete()

    // ---------- PIN ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("privat", Context.MODE_PRIVATE)

    fun hasPin(ctx: Context): Boolean = prefs(ctx).contains("pin_hash")

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs(ctx).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString("pin_salt", null) ?: return false
        return p.getString("pin_hash", null) == hash(salt, pin)
    }

    private fun hash(salt: String, pin: String): String {
        var b = (salt + pin).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(10_000) { b = md.digest(b) }
        return b.toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
