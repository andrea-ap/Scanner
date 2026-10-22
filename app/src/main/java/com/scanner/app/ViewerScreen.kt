package com.scanner.app

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_ZOOM = 4f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: MainViewModel, nav: NavController, photoId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by vm.allPhotos.collectAsState()
    var sid by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(all) { if (sid == null) sid = all?.firstOrNull { it.id == photoId }?.sessionId }

    // tutti gli allegati della stessa sessione: si sfogliano scorrendo a destra e sinistra
    val list = remember(all, sid) {
        all.orEmpty().filter { it.sessionId == sid }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }
    val sidValue = sid
    if (sidValue == null || list.isEmpty()) {
        LaunchedEffect(sidValue, all, list.isEmpty()) {
            if (sidValue != null && all != null && list.isEmpty()) nav.popBackStack()
        }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val startIndex = remember(sidValue) { list.indexOfFirst { it.id == photoId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { list.size }
    val current = list.getOrNull(pagerState.currentPage) ?: list.last()
    var confirmDelete by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    if (showReminder) ReminderPicker(vm, current) { showReminder = false }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (current.isPdf) "Eliminare il PDF?" else "Eliminare la foto?") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    vm.deletePhoto(current)
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annulla") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = {
                Column {
                    Text((if (current.isPdf) "PDF" else "Foto") + "  ·  ${pagerState.currentPage + 1} / ${list.size}")
                    current.reminderAt?.let {
                        Text(
                            "Promemoria ${Reminders.format(it)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            navigationIcon = {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            },
            actions = {
                IconButton({ showReminder = true }) {
                    if (current.reminderAt != null) {
                        Icon(Icons.Default.NotificationsActive, "Promemoria", tint = MaterialTheme.colorScheme.secondary)
                    } else {
                        Icon(Icons.Default.Notifications, "Promemoria")
                    }
                }
                IconButton({ shareOne(context, current) }) { Icon(Icons.Default.IosShare, "Condividi") }
                if (current.isPdf) {
                    IconButton({ openPdf(context, current) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Apri con un'altra app")
                    }
                } else {
                    IconButton({
                        scope.launch {
                            withContext(Dispatchers.IO) { saveToGallery(context, File(current.filePath)) }
                            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                "Salvata nella Galleria (cartella Scanner)" else "Salvataggio in Galleria non supportato su questo Android"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.SaveAlt, "Salva nella Galleria") }
                }
                IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Elimina") }
            },
        )
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
            val p = list.getOrNull(page)
            if (p != null) {
                key(p.id) {
                    if (p.isPdf) PdfPages(File(p.filePath)) else ImageViewer(p.filePath)
                }
            }
        }
    }
}

/** Come leggere/spostare la posizione verticale del contenuto durante lo zoom. */
private class VerticalAdapter(val offset: () -> Float, val scrollBy: suspend (Float) -> Unit)

/**
 * Zoom vero: il contenuto diventa più grande (larghezza = schermo x zoom) e si scorre
 * liberamente in tutte le direzioni (alto/basso e sinistra/destra) con un dito.
 * Pizzico a due dita per lo zoom, doppio tocco per ingrandire o tornare normale.
 */
@Composable
private fun ZoomHost(
    vertical: VerticalAdapter,
    content: @Composable (viewW: Dp, viewH: Dp, scale: Float) -> Unit,
) {
    val density = LocalDensity.current
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewW = maxWidth
        val viewH = maxHeight
        val vw = with(density) { viewW.toPx() }
        val vh = with(density) { viewH.toPx() }

        fun zoomTo(target: Float) {
            val n = target.coerceIn(1f, MAX_ZOOM)
            val ratio = n / scale
            if (ratio == 1f) return
            val oldH = hScroll.value.toFloat()
            val oldV = vertical.offset()
            scale = n
            scope.launch {
                // aspetta che il nuovo layout sia pronto, poi mantiene il centro della schermata
                withFrameNanos { }
                hScroll.scrollTo(((oldH + vw / 2) * ratio - vw / 2).toInt().coerceAtLeast(0))
                vertical.scrollBy((oldV + vh / 2) * (ratio - 1f))
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { zoomTo(if (scale > 1.05f) 1f else 2.5f) })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) zoomTo(scale * z)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .horizontalScroll(hScroll),
        ) { content(viewW, viewH, scale) }
    }
}

@Composable
private fun ImageViewer(path: String) {
    val vScroll = rememberScrollState()
    ZoomHost(VerticalAdapter({ vScroll.value.toFloat() }, { vScroll.scrollBy(it) })) { viewW, viewH, scale ->
        Box(Modifier.width(viewW * scale).fillMaxHeight().verticalScroll(vScroll)) {
            AsyncImage(
                File(path), null,
                Modifier.fillMaxWidth().height(viewH * scale),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun PdfPages(file: File) {
    val count = remember(file) { pdfPageCount(file) }
    val listState = rememberLazyListState()
    val adapter = remember(listState) {
        VerticalAdapter({ listState.firstVisibleItemScrollOffset.toFloat() }, { listState.scrollBy(it) })
    }
    ZoomHost(adapter) { viewW, _, scale ->
        LazyColumn(
            state = listState,
            modifier = Modifier.width(viewW * scale).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items((0 until count).toList()) { index ->
                val bitmap by produceState<ImageBitmap?>(null, file, index) {
                    value = withContext(Dispatchers.IO) { renderPdfPage(file, index, 2000)?.asImageBitmap() }
                }
                val b = bitmap
                if (b != null) {
                    Image(b, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                } else {
                    Box(Modifier.fillMaxWidth().height(400.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}
