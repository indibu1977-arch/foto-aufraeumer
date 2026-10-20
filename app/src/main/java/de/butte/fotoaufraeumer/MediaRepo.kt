package de.butte.fotoaufraeumer

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import java.security.MessageDigest

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long,
    val dateModified: Long,
    val relPath: String,
    val bucket: String,
)

data class Album(val path: String, val name: String, val count: Int)

object MediaRepo {
    val collection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val insertCollection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun loadAll(ctx: Context): List<Photo> {
        val proj = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<Photo>()
        ctx.contentResolver.query(collection, proj, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken).let { if (it > 0) it else c.getLong(iAdded) * 1000L }
                out += Photo(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(iName) ?: "",
                    size = c.getLong(iSize),
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    dateTaken = taken,
                    dateModified = c.getLong(iMod),
                    relPath = c.getString(iPath) ?: "",
                    bucket = c.getString(iBucket) ?: "",
                )
            }
        }
        out.sortByDescending { it.dateTaken }
        return out
    }

    fun albums(photos: List<Photo>): List<Album> =
        photos.groupBy { it.relPath }
            .filter { it.key.isNotBlank() }
            .map { Album(it.key, folderName(it.key), it.value.size) }
            .sortedByDescending { it.count }

    fun folderName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    fun trashRequest(ctx: Context, uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(ctx.contentResolver, uris, true)

    /** Endgültig löschen (ohne Papierkorb) – für Fotos, die in den privaten Ordner verschoben wurden. */
    fun deleteRequest(ctx: Context, uris: List<Uri>): PendingIntent =
        MediaStore.createDeleteRequest(ctx.contentResolver, uris)

    fun writeRequest(ctx: Context, uris: List<Uri>): PendingIntent =
        MediaStore.createWriteRequest(ctx.contentResolver, uris)

    fun move(ctx: Context, uri: Uri, relPath: String): Boolean = try {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relPath) }
        ctx.contentResolver.update(uri, cv, null, null) > 0
    } catch (e: Exception) {
        false
    }

    fun md5(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** Speichert das Bild als Kopie neben dem Original. */
    fun saveCopy(ctx: Context, bmp: Bitmap, original: Photo): Uri? {
        val base = original.name.substringBeforeLast('.').ifBlank { "Foto" }
        val name = "${base}_verbessert.jpg"
        val first = original.relPath.ifBlank { "Pictures/" }
        return insertJpeg(ctx, bmp, name, first, original.dateTaken)
            ?: insertJpeg(ctx, bmp, name, "Pictures/Verbessert/", original.dateTaken)
    }

    private fun insertJpeg(ctx: Context, bmp: Bitmap, name: String, relPath: String, taken: Long): Uri? {
        val cr = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            cr.insert(insertCollection, cv)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }
}
