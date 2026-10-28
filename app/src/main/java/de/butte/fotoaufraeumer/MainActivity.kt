package de.butte.fotoaufraeumer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && vm.intentReq == null) vm.lockVault()
    }
}

@Composable
fun App(vm: AppVM) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = scheme) {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.onPermission(it)
        }
        LaunchedEffect(Unit) {
            if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) vm.onPermission(true)
            else permLauncher.launch(perm)
        }

        val senderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { res -> vm.onIntentResult(res.resultCode == Activity.RESULT_OK) }
        val req = vm.intentReq
        LaunchedEffect(req) {
            if (req != null) {
                try {
                    senderLauncher.launch(IntentSenderRequest.Builder(req.sender).build())
                } catch (e: Exception) {
                    vm.onIntentResult(false)
                }
            }
        }

        val snack = remember { SnackbarHostState() }
        val msg = vm.message
        LaunchedEffect(msg) {
            if (msg != null) {
                snack.showSnackbar(msg)
                vm.message = null
            }
        }

        BackHandler(enabled = vm.stack.size > 1) { vm.back() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if (!vm.hasPermission) {
                    PermissionScreen(
                        onRequest = { permLauncher.launch(perm) },
                        onSettings = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            )
                        },
                    )
                } else {
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.Scan -> ScanScreen(vm)
                        Screen.SwipePick -> SwipePickScreen(vm)
                        Screen.Swipe -> SwipeScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        is Screen.Enhance -> EnhanceScreen(vm, s.photo)
                        Screen.Vault -> VaultScreen(vm)
                        is Screen.Erase -> EraseScreen(vm, s.photo)
                        Screen.Edited -> EditedListScreen(vm)
                        is Screen.Compare -> CompareScreen(vm, s.before, s.after, s.free)
                        Screen.PickCompare -> PickCompareScreen(vm)
                        Screen.Gallery -> GalleryScreen(vm)
                        Screen.FindSame -> FindSameScreen(vm)
                        Screen.Camera -> CameraScreen(vm)
                        is Screen.SimilarTo -> SimilarToScreen(vm, s.photo)
                    }
                }
                SnackbarHost(
                    snack,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)
                )
            }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📷", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Der Foto-Aufräumer braucht Zugriff auf deine Fotos.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Bitte „Alle zulassen“ wählen – mit „Ausgewählte Fotos“ findet die App kaum etwas.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Zugriff erlauben") }
        TextButton(onClick = onSettings) { Text("App-Einstellungen öffnen") }
    }
}
