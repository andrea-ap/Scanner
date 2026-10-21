package com.scanner.app

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF4DB6AC)
private val DeepTeal = Color(0xFF003731)

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Nome sessione") })
        },
        confirmButton = { TextButton({ onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text("Annulla") } },
    )
}

private val autoName = Regex("""^Sessione (\d{2})/(\d{2})/\d{4} (\d{2}:\d{2})$""")

/** Vecchio formato "Sessione 19/10/2026 20:41" -> "Sessione 19/10 · 20:41"; gli altri nomi restano invariati. */
fun shortTitle(name: String): String =
    autoName.matchEntire(name)?.let { "Sessione ${it.groupValues[1]}/${it.groupValues[2]} · ${it.groupValues[3]}" } ?: name

/** Chiave per raggruppare le etichette: "Reti" e "reti " sono la stessa. */
fun labelKey(name: String): String = shortTitle(name).trim().lowercase()

/** Nome di default da data e ora di creazione, es. "Sessione 19/10 · 20:41". */
fun defaultNameFor(ts: Long): String =
    "Sessione " + SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault()).format(Date(ts))

/** "Oggi, 20:41" / "Ieri, 18:02" / "19 ott, 20:41". */
fun relativeDate(ts: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    val day = Calendar.getInstance().apply { timeInMillis = ts }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(day, today) -> "Oggi, $time"
        sameDay(day, yesterday) -> "Ieri, $time"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts)) + ", $time"
    }
}

/** Frase trovata dalla ricerca, con la parola cercata evidenziata. */
class Hit(val text: AnnotatedString, val source: String, val photoId: Long? = null)

private fun snippet(text: String, idx: Int, len: Int): AnnotatedString {
    val start = maxOf(0, idx - 28)
    val end = minOf(text.length, idx + len + 56)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    val body = text.substring(start, end).replace('\n', ' ').replace('\r', ' ')
    return buildAnnotatedString {
        append(prefix + body + suffix)
        val hs = prefix.length + (idx - start)
        addStyle(
            SpanStyle(background = Color(0xFFFFB74D), color = Color(0xFF3E2400), fontWeight = FontWeight.SemiBold),
            hs, hs + len,
        )
    }
}

fun findHit(s: Session, photos: List<Photo>, query: String): Hit? {
    val n = query.trim()
    if (n.isEmpty()) return null
    var i = s.notes.indexOf(n, ignoreCase = true)
    if (i >= 0) return Hit(snippet(s.notes, i, n.length), "Nella nota")
    for (p in photos) {
        i = p.ocrText.indexOf(n, ignoreCase = true)
        if (i >= 0) return Hit(snippet(p.ocrText, i, n.length), if (p.isPdf) "Nel PDF" else "Nella foto", p.id)
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, nav: NavController) {
    val sessionsOrNull by vm.sessions.collectAsState()
    val photosOrNull by vm.allPhotos.collectAsState()
    val loaded = sessionsOrNull != null && photosOrNull != null
    val photos = photosOrNull ?: emptyList()
    val query by vm.query.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var sort by remember { mutableStateOf(AppPrefs.sort(context)) }
    var compact by remember { mutableStateOf(AppPrefs.compact(context)) }
    var lockEnabled by remember { mutableStateOf(AppPrefs.lockEnabled(context)) }
    var hidden by remember { mutableStateOf(setOf<Long>()) }

    // sessioni nascoste in attesa di conferma dell'eliminazione, poi ordinate
    val countById = remember(photos) { photos.groupingBy { it.sessionId }.eachCount() }
    val sessions = remember(sessionsOrNull, hidden, sort, countById) {
        val list = (sessionsOrNull ?: emptyList()).filter { it.id !in hidden }
        when (sort) {
            SortMode.RECENT -> list.sortedByDescending { it.updatedAt }
            SortMode.OLDEST -> list.sortedBy { it.updatedAt }
            SortMode.NAME -> list.sortedBy { labelKey(it.name) }
            SortMode.ATTACHMENTS -> list.sortedWith(
                compareByDescending<Session> { countById[it.id] ?: 0 }.thenByDescending { it.updatedAt }
            )
        }
    }

    // il "+" si nasconde scorrendo verso il basso e riappare scorrendo verso l'alto
    var fabVisible by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var last = 0
        snapshotFlow { listState.firstVisibleItemIndex * 100_000 + listState.firstVisibleItemScrollOffset }
            .collect { pos ->
                if (pos < last || pos < 50) fabVisible = true else if (pos > last + 8) fabVisible = false
                last = pos
            }
    }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }
    var showNew by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var pendingScan by remember { mutableStateOf<List<Uri>?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Ripristino in corso…", Toast.LENGTH_SHORT).show()
            scope.launch { Toast.makeText(context, vm.restore(uri), Toast.LENGTH_LONG).show() }
        }
    }
    val startScan = rememberDocScanner { pendingScan = it }

    if (showNew) {
        NameDialog("Nuovo documento", vm.defaultName(), "Crea", { showNew = false }) { name ->
            showNew = false
            scope.launch {
                val id = vm.createSession(name)
                nav.navigate("session/$id")
            }
        }
    }
    pendingScan?.let { pages ->
        NameDialog("Nome sessione", vm.defaultName(), "Salva", { pendingScan = null }) { name ->
            pendingScan = null
            scope.launch {
                val id = vm.createSession(name)
                pages.forEach { vm.addPhotoFromUri(id, it) }
                nav.navigate("session/$id")
            }
        }
    }

    // eliminazione con "Annulla": la sessione sparisce subito e viene cancellata solo se non si annulla
    fun requestDelete(s: Session) {
        hidden = hidden + s.id
        scope.launch {
            val r = snackbar.showSnackbar("Sessione eliminata", actionLabel = "Annulla", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                hidden = hidden - s.id
            } else {
                vm.deleteSession(s.id)
                hidden = hidden - s.id
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = fabVisible || menuOpen,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Column(
                    modifier = Modifier.navigationBarsPadding().padding(end = 4.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnimatedVisibility(
                        visible = menuOpen,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut() + slideOutVertically { it / 2 },
                    ) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ExtendedFloatingActionButton(
                                onClick = {
                                    menuOpen = false
                                    startScan()
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.DocumentScanner, null) },
                                text = { Text("Scansiona documento") },
                            )
                            ExtendedFloatingActionButton(
                                onClick = {
                                    menuOpen = false
                                    showNew = true
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.NoteAdd, null) },
                                text = { Text("Nuovo documento") },
                            )
                            ExtendedFloatingActionButton(
                                onClick = {
                                    menuOpen = false
                                    nav.navigate("camera/-1")
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = { Icon(Icons.Default.PhotoCamera, null) },
                                text = { Text("Scatta foto") },
                            )
                        }
                    }
                    val rotation by animateFloatAsState(if (menuOpen) 45f else 0f, label = "plus")
                    FloatingActionButton(
                        onClick = { menuOpen = !menuOpen },
                        shape = CircleShape,
                        containerColor = Color(0xFFFFD54F),
                        contentColor = Color(0xFF3E2C00),
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                        modifier = Modifier.size(44.dp),
                    ) { Icon(Icons.Default.Add, "Aggiungi", Modifier.size(24.dp).rotate(rotation)) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            Header(
                query, if (loaded) sessions.size else -1, collapsed,
                onQuery = { vm.query.value = it },
                onBackup = {
                    Toast.makeText(context, "Backup in corso…", Toast.LENGTH_SHORT).show()
                    scope.launch { Toast.makeText(context, vm.backup(), Toast.LENGTH_LONG).show() }
                },
                onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
                sort = sort,
                onSort = {
                    sort = it
                    AppPrefs.setSort(context, it)
                },
                compact = compact,
                onCompact = {
                    compact = !compact
                    AppPrefs.setCompact(context, compact)
                },
                lockEnabled = lockEnabled,
                onLock = {
                    if (lockEnabled) {
                        lockEnabled = false
                        AppPrefs.setLockEnabled(context, false)
                        Toast.makeText(context, "Blocco disattivato", Toast.LENGTH_SHORT).show()
                    } else {
                        val can = BiometricManager.from(context).canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        if (can == BiometricManager.BIOMETRIC_SUCCESS) {
                            lockEnabled = true
                            AppPrefs.setLockEnabled(context, true)
                            Toast.makeText(context, "Blocco attivato: ti verrà chiesto all'apertura", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Imposta prima un blocco schermo (PIN, schema o impronta) nelle impostazioni del telefono",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
            if (!loaded) {
                Box(Modifier.fillMaxSize())
            } else if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (query.isBlank()) "Nessuna sessione.\nTocca + per iniziare." else "Nessun risultato",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // etichette ripetute (stesso titolo su almeno 2 sessioni): quelle uniche non compaiono
                val repeated = sessions.groupBy { labelKey(it.name) }
                    .filterValues { it.size >= 2 }
                    .map { (key, list) -> Triple(key, shortTitle(list.first().name), list.size) }
                if (selectedLabel != null && repeated.none { it.first == selectedLabel }) selectedLabel = null
                val shown = selectedLabel?.let { sel -> sessions.filter { labelKey(it.name) == sel } } ?: sessions

                if (repeated.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(repeated, key = { it.first }) { (key, label, n) ->
                            val on = selectedLabel == key
                            Surface(
                                onClick = { selectedLabel = if (on) null else key },
                                shape = CircleShape,
                                color = if (on) Teal else Teal.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, if (on) Teal else Teal.copy(alpha = 0.6f)),
                            ) {
                                Row(
                                    Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Sell, null, Modifier.size(16.dp), tint = if (on) DeepTeal else Teal)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (on) DeepTeal else Teal,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier.size(22.dp).clip(CircleShape).background(if (on) DeepTeal else Teal),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "$n",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (on) Teal else DeepTeal,
                                        )
                                    }
                                    if (on) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.Close, "Togli filtro", Modifier.size(16.dp), tint = DeepTeal)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = if (repeated.isEmpty()) 16.dp else 4.dp, bottom = 210.dp,
                    ),
                ) {
                    items(shown, key = { it.id }) { s ->
                        val own = photos.filter { it.sessionId == s.id }
                        val hit = if (query.isNotBlank()) findHit(s, own, query) else null
                        val onLabel = {
                            val key = labelKey(s.name)
                            if (repeated.any { it.first == key }) selectedLabel = if (selectedLabel == key) null else key
                        }
                        val open = {
                            nav.navigate("session/${s.id}")
                            hit?.photoId?.let { nav.navigate("viewer/$it") }
                            Unit
                        }
                        SwipeToDelete(onDelete = { requestDelete(s) }) {
                            if (compact) {
                                SessionRow(s, own, hit, onLabel, open)
                            } else {
                                SessionCard(s, own, hit, onLabel, open)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Scorri la card verso sinistra per eliminarla (con possibilità di annullare). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            true
        } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xFFB3261E)).padding(end = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { Icon(Icons.Default.Delete, "Elimina", tint = Color.White) }
            }
        },
    ) { content() }
}

@Composable
private fun Header(
    query: String,
    count: Int,
    collapsed: Boolean,
    onQuery: (String) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    compact: Boolean,
    onCompact: () -> Unit,
    lockEnabled: Boolean,
    onLock: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF00695C), Color(0xFF00443A))),
                RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
            )
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = if (collapsed) 8.dp else 16.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DocumentScanner, null,
                tint = Color(0xFFFFD54F),
                modifier = Modifier.size(if (collapsed) 24.dp else 30.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Scanner",
                    style = if (collapsed) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD54F),
                )
                if (!collapsed && count >= 0) {
                    Text(
                        if (count == 1) "1 sessione" else "$count sessioni",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFE082),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }) {
                    Icon(Icons.Default.MoreVert, "Menu", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        "Ordina per",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    SortMode.entries.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m.label) },
                            leadingIcon = {
                                if (m == sort) Icon(Icons.Default.Check, null, tint = Teal) else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                menu = false
                                onSort(m)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (compact) "Vista a schede grandi" else "Vista compatta") },
                        leadingIcon = { Icon(if (compact) Icons.Default.ViewAgenda else Icons.Default.ViewList, null) },
                        onClick = {
                            menu = false
                            onCompact()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (lockEnabled) "Blocco app: attivo" else "Blocco app: disattivo") },
                        leadingIcon = { Icon(if (lockEnabled) Icons.Default.Lock else Icons.Default.LockOpen, null) },
                        onClick = {
                            menu = false
                            onLock()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Backup in Download") },
                        leadingIcon = { Icon(Icons.Default.Backup, null) },
                        onClick = {
                            menu = false
                            onBackup()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Ripristina da backup") },
                        leadingIcon = { Icon(Icons.Default.Restore, null) },
                        onClick = {
                            menu = false
                            onRestore()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(if (collapsed) 8.dp else 14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (collapsed) 38.dp else 44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.95f))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = Color(0xFF5F6F6C), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Cerca…", color = Color(0xFF7A8987))
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF1B2B29)),
                    cursorBrush = SolidColor(Color(0xFF00695C)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Default.Close, "Cancella", tint = Color(0xFF5F6F6C),
                    modifier = Modifier.size(20.dp).clickable { onQuery("") },
                )
            }
        }
    }
}

@Composable
private fun CountChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color = Color.Black.copy(alpha = 0.75f),
) {
    Surface(shape = CircleShape, color = color) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SessionCard(s: Session, own: List<Photo>, hit: Hit?, onLabel: () -> Unit, onClick: () -> Unit) {
    val cover = own.firstOrNull()?.displayPath
    val pdfCount = own.count { it.isPdf }
    val imgCount = own.size - pdfCount

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            if (cover != null) {
                AsyncImage(File(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                    Icon(
                        Icons.Default.Description, null,
                        Modifier.size(72.dp), tint = Color(0xFF80CBC4).copy(alpha = 0.6f),
                    )
                }
            }
            // fascia scura più marcata in basso: il testo resta leggibile anche su foto e documenti con scritte
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.2f),
                        0.35f to Color.Black.copy(alpha = 0.3f),
                        0.65f to Color.Black.copy(alpha = 0.82f),
                        1f to Color.Black.copy(alpha = 0.95f),
                    )
                )
            )
            val shadow = Shadow(Color.Black, Offset(0f, 2f), 6f)
            // il titolo è l'etichetta in alto: toccandola si filtrano le sessioni con la stessa etichetta
            Surface(
                onClick = onLabel,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 240.dp),
                shape = CircleShape,
                color = Teal,
            ) {
                Text(
                    shortTitle(s.name),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = DeepTeal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    if (hit != null) {
                        // ricerca attiva: la frase trovata con la parola evidenziata
                        Text(
                            hit.source,
                            style = MaterialTheme.typography.labelMedium,
                            color = Teal,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            hit.text,
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (s.notes.isNotBlank()) {
                        Text(
                            s.notes,
                            style = MaterialTheme.typography.titleMedium.copy(shadow = shadow, fontStyle = FontStyle.Italic),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            defaultNameFor(s.createdAt),
                            style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        relativeDate(s.updatedAt),
                        style = MaterialTheme.typography.bodySmall.copy(shadow = shadow),
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (pdfCount > 0) CountChip(Icons.Default.PictureAsPdf, "$pdfCount PDF", Color(0xFFC62828))
                    if (imgCount > 0) CountChip(Icons.Default.PhotoLibrary, "$imgCount foto")
                }
            }
        }
    }
}

/** Versione compatta: una riga per sessione, utile quando ce ne sono molte. */
@Composable
private fun SessionRow(s: Session, own: List<Photo>, hit: Hit?, onLabel: () -> Unit, onClick: () -> Unit) {
    val cover = own.firstOrNull()?.displayPath
    val pdfCount = own.count { it.isPdf }
    val imgCount = own.size - pdfCount

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    AsyncImage(File(cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                } else {
                    Icon(Icons.Default.Description, null, Modifier.size(32.dp), tint = Color(0xFF80CBC4).copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Surface(onClick = onLabel, shape = CircleShape, color = Teal, modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(
                        shortTitle(s.name),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepTeal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (hit != null) {
                    Text(hit.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else if (s.notes.isNotBlank()) {
                    Text(
                        s.notes,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(defaultNameFor(s.createdAt), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeDate(s.updatedAt) + if (imgCount > 0) " · $imgCount foto" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pdfCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = Color(0xFFC62828)) {
                            Text(
                                "$pdfCount PDF",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
