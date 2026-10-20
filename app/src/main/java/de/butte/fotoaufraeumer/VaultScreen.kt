@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package de.butte.fotoaufraeumer

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage

@Composable
fun VaultScreen(vm: AppVM) {
    // Keine Screenshots und kein Vorschaubild in „Zuletzt verwendet“
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler { vm.lockVault() }

    if (!vm.vaultUnlocked) PinScreen(vm) else VaultGrid(vm)
}

@Composable
private fun PinScreen(vm: AppVM) {
    val newPin = remember { !vm.vaultHasPin }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        error = when {
            pin.length < 4 -> "Mindestens 4 Ziffern"
            newPin && pin != pin2 -> "PINs stimmen nicht überein"
            !vm.unlockVault(pin, newPin) -> { pin = ""; "Falsche PIN" }
            else -> null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privater Ordner") },
                navigationIcon = { BackButton { vm.lockVault() } },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔒", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                if (newPin) "PIN für den privaten Ordner festlegen" else "PIN eingeben",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            val kb = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(12) },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = kb,
            )
            if (newPin) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin2,
                    onValueChange = { v -> pin2 = v.filter { it.isDigit() }.take(12) },
                    label = { Text("PIN wiederholen") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = kb,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { submit() }) { Text(if (newPin) "PIN speichern" else "Öffnen") }
            if (newPin) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Wichtig: Die Fotos liegen nur in dieser App. Beim Deinstallieren der App oder Löschen der App-Daten sind sie weg – vorher „Zurück in Galerie“ machen.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VaultGrid(vm: AppVM) {
    val list = vm.vaultItems
    var sel by remember { mutableStateOf(setOf<String>()) }
    var view by remember { mutableStateOf<VaultItem?>(null) }
    var confirmDelete by remember { mutableStateOf<List<VaultItem>?>(null) }
    LaunchedEffect(list) { sel = sel.filter { p -> list.any { it.file.path == p } }.toSet() }
    val selected = list.filter { it.file.path in sel }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🔒 Privat (${list.size})") },
                navigationIcon = { BackButton { vm.lockVault() } },
                actions = {
                    if (list.isNotEmpty()) {
                        TextButton(onClick = {
                            sel = if (sel.size == list.size) emptySet() else list.map { it.file.path }.toSet()
                        }) { Text(if (sel.size == list.size) "Keine" else "Alle") }
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { vm.restoreFromVault(selected); sel = emptySet() }, modifier = Modifier.weight(1f)) {
                            Text("Zurück in Galerie (${selected.size})", maxLines = 1)
                        }
                        Button(
                            onClick = { confirmDelete = selected },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("Löschen") }
                    }
                }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (list.isEmpty())
                        "Noch leer. Im Wisch-Sortierer eine Richtung auf „🔒 Privater Ordner“ legen – die Fotos verschwinden dann aus der Galerie und liegen nur hier."
                    else "Antippen = groß ansehen, lange drücken = auswählen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(list, key = { it.file.path }) { it ->
                val isSel = it.file.path in sel
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                        .combinedClickable(
                            onClick = {
                                if (sel.isNotEmpty()) sel = if (isSel) sel - it.file.path else sel + it.file.path
                                else view = it
                            },
                            onLongClick = { sel = if (isSel) sel - it.file.path else sel + it.file.path },
                        )
                ) {
                    AsyncImage(
                        model = it.file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isSel) {
                        Box(Modifier.fillMaxSize().background(Color(0x881565C0)))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Ausgewählt",
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(36.dp),
                        )
                    }
                }
            }
        }
    }

    view?.let { v ->
        Dialog(onDismissRequest = { view = null }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(model = v.file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Text(
                    "${v.name} · ${sizeStr(v.size)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Color(0xAA000000))
                        .statusBarsPadding()
                        .padding(12.dp),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xAA000000))
                        .navigationBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { vm.restoreFromVault(listOf(v)); view = null }, modifier = Modifier.weight(1f)) {
                        Text("In Galerie", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { confirmDelete = listOf(v); view = null }, modifier = Modifier.weight(1f)) {
                        Text("Löschen", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { view = null }, modifier = Modifier.weight(1f)) {
                        Text("Schließen", maxLines = 1)
                    }
                }
            }
        }
    }

    confirmDelete?.let { del ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Endgültig löschen?") },
            text = { Text("${del.size} Fotos werden ohne Papierkorb gelöscht. Das lässt sich nicht rückgängig machen.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFromVault(del)
                    sel = emptySet()
                    confirmDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}
