package com.scanner.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionScreen(vm: MainViewModel, nav: NavController, id: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by vm.session(id).collectAsState(initial = null)
    val loadedPhotos by vm.photos(id).collectAsState(initial = null)
    val photos = loadedPhotos.orEmpty()

    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<Photo?>(null) }
    var reminderFor by remember { mutableStateOf<Photo?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // appena si aggiunge un allegato (scansione, foto, PDF, fotocamera) si propone il promemoria
    var lastSeenId by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(loadedPhotos) {
        val list = loadedPhotos ?: return@LaunchedEffect
        val newest = list.maxByOrNull { it.id }
        val previous = lastSeenId
        lastSeenId = newest?.id ?: 0L
        if (previous != null && newest != null && newest.id > previous) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss() // con più pagine resta solo l'ultimo avviso
                val r = snackbar.showSnackbar(
                    if (newest.isPdf) "PDF aggiunto" else "Allegato aggiunto",
                    actionLabel = "Promemoria", withDismissAction = true, duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) reminderFor = newest
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        scope.launch { uris.forEach { vm.addPhotoFromUri(id, it) } }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { uris.forEach { vm.addPdfFromUri(id, it) } }
    }

    val startScan = rememberDocScanner { pages -> scope.launch { pages.forEach { vm.addPhotoFromUri(id, it) } } }

    val s = session
    if (s == null) {
        // sessione eliminata o non ancora caricata
        Box(Modifier.fillMaxSize())
        return
    }

    // PDF unico preparato in background: il peso mostrato nel menu è quello esatto
    val combined by produceState<File?>(null, photos.map { it.id }, s.notes, s.name) {
        value = null
        if (photos.isEmpty() && s.notes.isBlank()) return@produceState
        delay(800)
        value = withContext(Dispatchers.IO) { buildSessionPdf(context, s, photos) }
    }
    // versione compressa, preparata dopo l'originale
    val combinedSmall by produceState<File?>(null, combined) {
        value = null
        if (combined == null) return@produceState
        value = withContext(Dispatchers.IO) { buildSessionPdf(context, s, photos, compressed = true) }
    }
    var showPdfChoice by remember { mutableStateOf(false) }

    if (showPdfChoice) {
        val choices = listOf("Originale" to combined, "Compresso" to combinedSmall)
        AlertDialog(
            onDismissRequest = { showPdfChoice = false },
            title = { Text("Condividi PDF unico") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scegli la versione da condividere. Il compresso è più leggero ma con qualità ridotta.")
                    choices.forEach { (label, file) ->
                        FilledTonalButton(
                            onClick = {
                                showPdfChoice = false
                                if (file != null && file.exists()) sharePdf(context, file, s)
                            },
                            enabled = file != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("$label · " + (file?.let { formatSize(it.length()) } ?: "calcolo…"))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ showPdfChoice = false }) { Text("Annulla") } },
        )
    }

    var notes by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.notes) { if (notes == null) notes = s.notes }
    LaunchedEffect(notes) {
        val n = notes ?: return@LaunchedEffect
        if (n != s.notes) {
            delay(500)
            vm.setNotes(id, n)
        }
    }

    if (showRename) {
        NameDialog("Rinomina sessione", s.name, "Salva", { showRename = false }) {
            vm.rename(id, it)
            showRename = false
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Eliminare la sessione?") },
            text = { Text("Verranno eliminate anche tutte le foto.") },
            confirmButton = {
                TextButton({
                    showDelete = false
                    nav.popBackStack()
                    vm.deleteSession(id)
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton({ showDelete = false }) { Text("Annulla") } },
        )
    }
    reminderFor?.let { p -> ReminderPicker(vm, p) { reminderFor = null } }
    photoToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { photoToDelete = null },
            title = { Text("Eliminare la foto?") },
            confirmButton = {
                TextButton({
                    vm.deletePhoto(p)
                    photoToDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton({ photoToDelete = null }) { Text("Annulla") } },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                title = { Text(shortTitle(s.name), maxLines = 1) },
                navigationIcon = {
                    IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    var shareMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ shareMenu = true }) { Icon(Icons.Default.Share, "Condividi") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("PDF unico (tutti gli allegati)")
                                        if (photos.isNotEmpty() || s.notes.isNotBlank()) {
                                            Text(
                                                combined?.let { formatSize(it.length()) } ?: "calcolo del peso…",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = {
                                    shareMenu = false
                                    if (photos.isEmpty() && s.notes.isBlank()) {
                                        Toast.makeText(context, "Non c'è nulla da inserire nel PDF", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showPdfChoice = true
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Foto e note con altre app") },
                                leadingIcon = { Icon(Icons.Default.IosShare, null) },
                                onClick = {
                                    shareMenu = false
                                    shareToAnyApp(context, s, photos)
                                },
                            )
                        }
                    }
                    IconButton({ showRename = true }) { Icon(Icons.Default.Edit, "Rinomina") }
                    IconButton({ showDelete = true }) { Icon(Icons.Default.Delete, "Elimina") }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { shareToWhatsApp(context, s, photos) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Condividi su WhatsApp")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = notes ?: "",
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Note") },
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ startScan() }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.DocumentScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scansiona documento")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ nav.navigate("camera/$id") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scatta")
                }
                FilledTonalButton({
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Foto")
                }
                FilledTonalButton({ pdfPicker.launch(arrayOf("application/pdf")) }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PDF")
                }
            }
            Spacer(Modifier.height(16.dp))
            val pdfCount = photos.count { it.isPdf }
            val imgCount = photos.size - pdfCount
            Text(
                "Allegati" + if (photos.isEmpty()) "" else " · " +
                    listOfNotNull(
                        if (pdfCount > 0) "$pdfCount PDF" else null,
                        if (imgCount > 0) "$imgCount foto" else null,
                    ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (photos.isEmpty()) {
                Text("Nessun allegato. Scansiona un documento, scatta una foto o aggiungi un PDF.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(photos, key = { it.id }) { p ->
                        Box(Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).combinedClickable(
                            onClick = { nav.navigate("viewer/${p.id}") },
                            onLongClick = { menuFor = p.id },
                        )) {
                            DropdownMenu(expanded = menuFor == p.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(
                                    text = { Text(if (p.reminderAt != null) "Modifica promemoria" else "Promemoria…") },
                                    leadingIcon = { Icon(Icons.Default.NotificationsActive, null) },
                                    onClick = {
                                        menuFor = null
                                        reminderFor = p
                                    },
                                )
                                if (p.reminderAt != null) {
                                    DropdownMenuItem(
                                        text = { Text("Rimuovi promemoria") },
                                        leadingIcon = { Icon(Icons.Default.NotificationsOff, null) },
                                        onClick = {
                                            menuFor = null
                                            vm.setReminder(p, null)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Elimina") },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = {
                                        menuFor = null
                                        photoToDelete = p
                                    },
                                )
                            }
                            AsyncImage(
                                File(p.displayPath), null, Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                            )
                            val size = remember(p.filePath) { formatSize(File(p.filePath).length()) }
                            Text(
                                size, color = Color.White, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.75f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            p.reminderAt?.let { at ->
                                Row(
                                    Modifier.align(Alignment.TopStart).padding(8.dp)
                                        .clip(CircleShape).background(MaterialTheme.colorScheme.secondary)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Notifications, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondary)
                                    Spacer(Modifier.width(3.dp))
                                    Text(Reminders.format(at), color = MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (p.isPdf) {
                                val pages by produceState(0, p.filePath) {
                                    value = withContext(Dispatchers.IO) { pdfPageCount(File(p.filePath)) }
                                }
                                val firstLine = remember(p.ocrText) {
                                    p.ocrText.lineSequence().map { it.trim() }.firstOrNull { it.length > 3 }
                                }
                                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(8.dp)
                                            .clip(CircleShape).background(Color.Black.copy(alpha = 0.75f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(16.dp), tint = Color(0xFFEF5350))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            if (pages > 0) "PDF · $pages pag." else "PDF",
                                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    if (firstLine != null) {
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.75f))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                firstLine, color = Color.White, maxLines = 2,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
