package com.scanner.app

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.IntentCompat
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()

    private val locked = mutableStateOf(false)
    private var lastStop = 0L
    private var authenticating = false
    // sessione da aprire perché è stata toccata la notifica di un promemoria
    private val openSession = mutableStateOf<Long?>(null)
    // foto e PDF arrivati da "Condividi" di un'altra app, in attesa di scegliere dove metterli
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // intestazione colorata scura: icone chiare nella barra di stato
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        locked.value = AppPrefs.lockEnabled(this)
        readSessionExtra(intent)
        readShared(intent)
        setContent {
            ScannerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (locked.value) LockScreen(onUnlock = { authenticate() }) else AppNav(vm, openSession, sharedUris)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSessionExtra(intent)
        readShared(intent)
    }

    private fun readShared(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) sharedUris.value = uris
    }

    private fun readSessionExtra(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        if (id >= 0) openSession.value = id
    }

    override fun onStart() {
        super.onStart()
        if (!AppPrefs.lockEnabled(this)) {
            locked.value = false
            return
        }
        // si richiede lo sblocco all'apertura e quando si torna dopo più di 30 secondi
        // (camera, scanner e selezione file non bloccano l'app)
        if (lastStop == 0L || SystemClock.elapsedRealtime() - lastStop > 30_000) locked.value = true
        if (locked.value) authenticate()
    }

    override fun onStop() {
        super.onStop()
        lastStop = SystemClock.elapsedRealtime()
    }

    private fun authenticate() {
        if (authenticating) return
        authenticating = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    locked.value = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Scanner")
                .setSubtitle("Sblocca per vedere i tuoi documenti")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1514)), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = Color(0xFFFFD54F))
            Spacer(Modifier.height(16.dp))
            Text("Scanner è bloccato", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F), contentColor = Color(0xFF3E2C00)),
            ) { Text("Sblocca") }
        }
    }
}

@Composable
fun AppNav(vm: MainViewModel, openSession: MutableState<Long?>, sharedUris: MutableState<List<Uri>>) {
    val nav = rememberNavController()
    LaunchedEffect(openSession.value) {
        val id = openSession.value ?: return@LaunchedEffect
        openSession.value = null
        nav.navigate("session/$id") { launchSingleTop = true }
    }
    if (sharedUris.value.isNotEmpty()) ShareTargetDialog(vm, nav, sharedUris)
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(vm, nav) }
        // sessionId = -1 -> nuova sessione dopo lo scatto
        composable(
            "camera/{sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { CameraScreen(vm, nav, it.arguments!!.getLong("sessionId")) }
        composable(
            "viewer/{photoId}",
            arguments = listOf(navArgument("photoId") { type = NavType.LongType }),
        ) { ViewerScreen(vm, nav, it.arguments!!.getLong("photoId")) }
        composable(
            "session/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { SessionScreen(vm, nav, it.arguments!!.getLong("id")) }
    }
}

/** Dopo "Condividi -> Scanner": sceglie la sessione in cui mettere i file ricevuti. */
@Composable
private fun ShareTargetDialog(vm: MainViewModel, nav: NavController, sharedUris: MutableState<List<Uri>>) {
    val uris = sharedUris.value
    val sessions by vm.allSessions.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun import(sessionId: Long?) {
        if (busy) return
        busy = true
        scope.launch {
            val id = sessionId ?: vm.createSession("")
            uris.forEach { u ->
                val mime = context.contentResolver.getType(u).orEmpty()
                if (mime == "application/pdf" || u.toString().endsWith(".pdf", ignoreCase = true)) vm.addPdfFromUri(id, u)
                else vm.addPhotoFromUri(id, u)
            }
            sharedUris.value = emptyList()
            nav.navigate("session/$id")
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) sharedUris.value = emptyList() },
        title = { Text(if (uris.size == 1) "Aggiungi 1 file a\u2026" else "Aggiungi ${uris.size} file a\u2026") },
        text = {
            if (busy) {
                Text("Importo\u2026")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        TextButton({ import(null) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Nuova sessione", Modifier.weight(1f))
                        }
                    }
                    items(sessions, key = { it.id }) { s ->
                        TextButton({ import(s.id) }, Modifier.fillMaxWidth()) {
                            Text(labelOf(s), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            Text(relativeDate(s.updatedAt), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { if (!busy) TextButton({ sharedUris.value = emptyList() }) { Text("Annulla") } },
    )
}
