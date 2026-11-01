@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.butte.fotoaufraeumer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

fun dateStr(ms: Long): String = if (ms <= 0) "" else dateFmt.format(Date(ms))

fun sizeStr(b: Long): String = when {
    b >= 1_000_000 -> String.format(Locale.GERMANY, "%.1f MB", b / 1_000_000.0)
    else -> "${b / 1000} KB"
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
    }
}

@Composable
fun Thumb(
    p: Photo,
    selected: Boolean,
    label: String?,
    modifier: Modifier = Modifier.size(112.dp),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    cloud: Boolean = false,
    uploaded: Boolean = false,
    checked: Boolean = false,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .then(
                if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)
            )
    ) {
        AsyncImage(
            model = p.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x99C62828)))
            Icon(
                Icons.Default.Delete,
                contentDescription = "Ausgewählt",
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(36.dp),
            )
        }
        if (cloud) {
            Box(Modifier.fillMaxSize().background(Color(0x991565C0)))
            Text(
                "☁",
                color = Color.White,
                fontSize = 34.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (checked) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x661565C0))
                    .border(3.dp, Color(0xFF42A5F5), RoundedCornerShape(8.dp))
            )
            Text(
                "✓",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(Color(0xFF1E88E5), androidx.compose.foundation.shape.CircleShape)
                    .wrapContentSize(Alignment.Center),
            )
        }
        if (uploaded) {
            Text(
                "☁✓",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .background(Color(0xDD2E7D32), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (label != null) {
            Text(
                label,
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun PhotoPreview(
    p: Photo,
    selected: Boolean,
    onToggle: () -> Unit,
    onEnhance: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = p.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .statusBarsPadding()
                    .padding(12.dp)
            ) {
                Text(p.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${p.width}×${p.height} · ${sizeStr(p.size)} · ${dateStr(p.dateTaken)}\n${p.relPath}",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .navigationBarsPadding()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onToggle, modifier = Modifier.weight(1f)) {
                    Text(if (selected) "Abwählen" else "Auswählen", maxLines = 1)
                }
                FilledTonalButton(onClick = onEnhance, modifier = Modifier.weight(1f)) {
                    Text("✨ Verbessern", maxLines = 1)
                }
                FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Schließen", maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun LevelChips(current: Int, labels: List<String>, title: String, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        labels.forEachIndexed { i, l ->
            FilterChip(selected = current == i, onClick = { onSelect(i) }, label = { Text(l) })
        }
    }
}

/** Grünes Schild „schon auf der Platte“ oben links im großen Bild. */
@Composable
fun BoxScope.UploadedBadge(info: Uploaded?) {
    if (info == null) return
    val dir = info.folder.trim('/').substringAfterLast('/').ifEmpty { "Platte" }
    Text(
        "☁✓ $dir" + (if (info.time > 0) " · ${dateStr(info.time)}" else ""),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .background(Color(0xDD2E7D32), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
