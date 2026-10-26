package de.butte.fotoaufraeumer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

private const val NEXTCLOUD_PKG = "com.nextcloud.client"

/**
 * Übergibt Fotos an die Nextcloud-App. Dort Zielordner wählen → „Hochladen“.
 * Ist Nextcloud nicht installiert, erscheint die normale Teilen-Auswahl.
 * Gibt false zurück, wenn gar nichts geöffnet werden konnte.
 */
fun uploadToNextcloud(ctx: Context, uris: List<Uri>): Boolean {
    if (uris.isEmpty()) return false
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = "image/*"
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    // ClipData sorgt dafür, dass die Leseberechtigung für ALLE Fotos mitgegeben wird
    intent.clipData = ClipData.newUri(ctx.contentResolver, "Fotos", uris[0]).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    return try {
        ctx.startActivity(Intent(intent).setPackage(NEXTCLOUD_PKG))
        true
    } catch (e: ActivityNotFoundException) {
        try {
            ctx.startActivity(
                Intent.createChooser(intent, "Hochladen mit …").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e2: Exception) {
            false
        }
    }
}
