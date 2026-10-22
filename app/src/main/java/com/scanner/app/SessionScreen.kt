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
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    val photosOrNull by vm.photos(id).collectAsState(initial = null)
    val photos = photosOrNull ?: emptyList()

    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<Photo?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        scope.launch { uris.forEach { vm.addPhotoFromUri(id, it) } }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { uris.forEach { vm.addPdfFromUri(id, it) } }
    }

    val startScan = rememberDocScanner { pages -> scope.launch { pages.forEach { vm.addPhotoFromUri(id, it) } } }

    var showReminderMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<Calendar?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Senza il permesso per le notifiche il promemoria non potrà avvisarti", Toast.LENGTH_LONG).show()
    }
    fun setReminderAt(at: Long) {
        if (at <= System.currentTimeMillis()) {
            Toast.makeText(context, "Scegli una data e un'ora future", Toast.LENGTH_SHORT).show()
            return
        }
        vm.setReminder(id, at)
        if (!hasNotificationPermission(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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

    // dopo l'aggiunta di allegati propone (senza obbligo) un promemoria
    var lastCount by remember(id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(photosOrNull?.size) {
        val n = photosOrNull?.size ?: return@LaunchedEffect
        val before = lastCount
        lastCount = n
        if (before != null && n > before && s.reminderAt == null) {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Vuoi un promemoria?", actionLabel = "Imposta", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) showReminderMenu = true
        }
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

    if (showReminderMenu) {
        fun at(daysFromNow: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysFromNow)
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        AlertDialog(
            onDismissRequest = { showReminderMenu = false },
            title = { Text("Promemoria") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ showReminderMenu = false; setReminderAt(at(1)) }, Modifier.fillMaxWidth()) { Text("Domani alle 9:00") }
                    FilledTonalButton({ showReminderMenu = false; setReminderAt(at(7)) }, Modifier.fillMaxWidth()) { Text("Tra una settimana, alle 9:00") }
                    FilledTonalButton({ showReminderMenu = false; showDatePicker = true }, Modifier.fillMaxWidth()) { Text("Scegli data e ora…") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ showReminderMenu = false }) { Text("Annulla") } },
        )
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton({
                    val utc = state.selectedDateMillis
                    showDatePicker = false
                    if (utc != null) pickedDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                }) { Text("Avanti") }
            },
            dismissButton = { TextButton({ showDatePicker = false }) { Text("Annulla") } },
        ) { DatePicker(state) }
    }
    pickedDate?.let { day ->
        val time = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            title = { Text("Che ora?") },
            text = { Box(Modifier.fillMaxWidth(), Alignment.Center) { TimePicker(time) } },
            confirmButton = {
                TextButton({
                    val at = Calendar.getInstance().apply {
                        clear()
                        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), time.hour, time.minute, 0)
                    }.timeInMillis
                    pickedDate = null
                    setReminderAt(at)
                }) { Text("Imposta") }
            },
            dismissButton = { TextButton({ pickedDate = null }) { Text("Annulla") } },
        )
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
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showReminderMenu = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val r = s.reminderAt
                Icon(
                    if (r != null) Icons.Default.Notifications else Icons.Default.NotificationsNone, null,
                    tint = if (r != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (r != null) "Promemoria: " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALY).format(Date(r))
                    else "Promemoria · Aggiungi",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                if (r != null) {
                    IconButton({ vm.setReminder(id, null) }) { Icon(Icons.Default.Close, "Togli promemoria") }
                }
            }
            Spacer(Modifier.height(4.dp))
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
                            onLongClick = { photoToDelete = p },
                        )) {
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
