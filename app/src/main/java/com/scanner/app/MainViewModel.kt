package com.scanner.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Pagine di un PDF lette dall'OCR (oltre, il testo non viene cercato). */
private const val MAX_OCR_PAGES = 40

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val photosDir = File(app.filesDir, "photos").apply { mkdirs() }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val query = MutableStateFlow("")

    // null = ancora in caricamento (evita il lampo "Nessuna sessione" all'avvio)
    val sessions: StateFlow<List<Session>?> = combine(
        query.debounce(200), dao.observeSessions(), dao.observeAllPhotos(),
    ) { q, all, photos ->
        val words = searchWords(q)
        if (words.isEmpty()) all else {
            val byId = photos.groupBy { it.sessionId }
            all.filter { s ->
                val texts = buildList {
                    add(foldText(s.name)); add(foldText(s.category)); add(foldText(s.notes))
                    byId[s.id]?.forEach { add(foldText(it.ocrText)) }
                }
                matchesAll(words, texts)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Tutte le sessioni, senza filtro di ricerca (per scegliere dove aggiungere file condivisi). */
    val allSessions: StateFlow<List<Session>> = dao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPhotos: StateFlow<List<Photo>?> = dao.observeAllPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun session(id: Long): Flow<Session?> = dao.observeSession(id)
    fun photos(id: Long): Flow<List<Photo>> = dao.observePhotos(id)

    fun newPhotoFile(): File = File(photosDir, "IMG_${System.currentTimeMillis()}.jpg")

    fun defaultName(): String =
        "Sessione " + SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault()).format(Date())

    /** Crea una sessione (con foto opzionale) e restituisce l'id. */
    suspend fun createSession(name: String, photo: File? = null, fromCamera: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertSession(Session(name = name.ifBlank { defaultName() }, createdAt = now, updatedAt = now))
        if (photo != null) addPhoto(id, photo, fromCamera)
        return id
    }

    private val backups = BackupManager(app, dao, photosDir)
    suspend fun backup(): String = withContext(Dispatchers.IO) { backups.backup() }
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) { backups.restore(uri) }

    /** [fromCamera]: la foto viene copiata anche nella Galleria. */
    suspend fun addPhoto(sessionId: Long, file: File, fromCamera: Boolean = false) {
        if (fromCamera) withContext(Dispatchers.IO) { saveToGallery(getApplication(), file) }
        val now = System.currentTimeMillis()
        val id = dao.insertPhoto(Photo(sessionId = sessionId, filePath = file.absolutePath, createdAt = now))
        dao.touch(sessionId, now)
        viewModelScope.launch(Dispatchers.Default) { runOcr(id, sessionId, file) }
    }

    suspend fun addPhotoFromUri(sessionId: Long, uri: Uri) {
        val file = withContext(Dispatchers.IO) {
            newPhotoFile().also { f ->
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    f.outputStream().use { o -> i.copyTo(o) }
                }
            }
        }
        addPhoto(sessionId, file)
    }

    suspend fun addPdfFromUri(sessionId: Long, uri: Uri) {
        val file = withContext(Dispatchers.IO) {
            File(photosDir, "DOC_${System.nanoTime()}.pdf").also { f ->
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    f.outputStream().use { o -> i.copyTo(o) }
                }
                // anteprima = prima pagina
                renderPage(f, 0, 600)?.let { bmp ->
                    File("${f.absolutePath}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }
            }
        }
        val now = System.currentTimeMillis()
        val id = dao.insertPhoto(Photo(sessionId = sessionId, filePath = file.absolutePath, createdAt = now))
        dao.touch(sessionId, now)
        viewModelScope.launch(Dispatchers.Default) { runPdfOcr(id, sessionId, file) }
    }

    private fun renderPage(file: File, index: Int, width: Int): Bitmap? = renderPdfPage(file, index, width)

    private suspend fun runPdfOcr(photoId: Long, sessionId: Long, file: File) {
        try {
            val parts = mutableListOf<String>()
            for (i in 0 until minOf(pdfPageCount(file), MAX_OCR_PAGES)) {
                val bmp = renderPage(file, i, 1400) ?: break
                parts += recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text
                bmp.recycle()
            }
            val text = parts.joinToString("\n")
            val p = dao.photosOf(sessionId).firstOrNull { it.id == photoId } ?: return
            dao.updatePhoto(p.copy(ocrText = text))
        } catch (e: Exception) {
            // OCR best effort
        }
    }

    /** Rilegge il testo di un allegato (utile per i PDF aggiunti quando si leggevano solo 5 pagine). */
    fun rerunOcr(p: Photo) {
        val f = File(p.filePath)
        viewModelScope.launch(Dispatchers.Default) {
            if (p.isPdf) runPdfOcr(p.id, p.sessionId, f) else runOcr(p.id, p.sessionId, f)
        }
    }

    private suspend fun runOcr(photoId: Long, sessionId: Long, file: File) {
        try {
            val image = InputImage.fromFilePath(getApplication(), Uri.fromFile(file))
            val text = recognizer.process(image).await().text
            val p = dao.photosOf(sessionId).firstOrNull { it.id == photoId } ?: return
            dao.updatePhoto(p.copy(ocrText = text))
        } catch (e: Exception) {
            // OCR best effort: la foto resta comunque salvata
        }
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) dao.rename(id, name.trim(), System.currentTimeMillis())
    }

    fun setCategory(id: Long, category: String) = viewModelScope.launch {
        dao.setCategory(id, category.trim())
    }

    fun setNotes(id: Long, notes: String) = viewModelScope.launch {
        dao.setNotes(id, notes, System.currentTimeMillis())
    }

    fun deletePhoto(p: Photo) = viewModelScope.launch {
        dao.deletePhoto(p)
        withContext(Dispatchers.IO) {
            File(p.filePath).delete()
            if (p.isPdf) File(p.displayPath).delete()
        }
    }

    /** Imposta o toglie (at = null) il promemoria e programma/annulla l'allarme. */
    fun setReminder(id: Long, at: Long?) = viewModelScope.launch {
        dao.setReminder(id, at)
        val ctx = getApplication<Application>()
        if (at != null) scheduleReminder(ctx, id, at) else cancelReminder(ctx, id)
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        cancelReminder(getApplication(), id)
        val files = dao.photosOf(id).flatMap { listOf(File(it.filePath), File(it.displayPath)) }
        dao.deleteSession(id)
        withContext(Dispatchers.IO) { files.forEach { it.delete() } }
    }
}
