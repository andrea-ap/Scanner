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

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val photosDir = File(app.filesDir, "photos").apply { mkdirs() }
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val query = MutableStateFlow("")

    // null = ancora in caricamento (evita il lampo "Nessuna sessione" all'avvio)
    val sessions: StateFlow<List<Session>?> = query.debounce(200).flatMapLatest { q ->
        if (q.isBlank()) dao.observeSessions() else dao.search("%${q.trim()}%")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allPhotos: StateFlow<List<Photo>?> = dao.observeAllPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun session(id: Long): Flow<Session?> = dao.observeSession(id)
    fun photos(id: Long): Flow<List<Photo>> = dao.observePhotos(id)

    fun newPhotoFile(): File = File(photosDir, "IMG_${System.currentTimeMillis()}.jpg")

    fun defaultName(): String =
        "Sessione " + SimpleDateFormat("dd/MM · HH:mm", Locale.getDefault()).format(Date())

    /** [at] null = rimuove il promemoria. */
    fun setReminder(p: Photo, at: Long?) = viewModelScope.launch {
        dao.setReminder(p.id, at)
        if (at == null) Reminders.cancel(getApplication(), p.id) else Reminders.schedule(getApplication(), p.id, at)
    }

    /** Crea una sessione (con foto opzionale) e restituisce l'id. */
    suspend fun createSession(name: String, photo: File? = null, fromCamera: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertSession(Session(name = name.ifBlank { defaultName() }, createdAt = now, updatedAt = now))
        if (photo != null) addPhoto(id, photo, fromCamera)
        return id
    }

    private val backups = BackupManager(app, dao, photosDir)
    suspend fun backup(): String = withContext(Dispatchers.IO) { backups.backup() }
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        backups.restore(uri).also { Reminders.rescheduleAll(getApplication()) }
    }

    init {
        // le sveglie si perdono se l'app viene chiusa forzatamente: si ripianificano all'avvio
        viewModelScope.launch(Dispatchers.IO) { Reminders.rescheduleAll(app) }
    }

    /** [fromCamera]: la foto viene copiata anche nella Galleria. */
    suspend fun addPhoto(sessionId: Long, file: File, fromCamera: Boolean = false) {
        if (fromCamera) withContext(Dispatchers.IO) { saveToGallery(getApplication(), file) }
        val now = System.currentTimeMillis()
        val id = dao.insertPhoto(Photo(sessionId = sessionId, filePath = file.absolutePath, createdAt = now))
        dao.touch(sessionId, now)
        viewModelScope.launch(Dispatchers.Default) { runOcr(id, file) }
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
        viewModelScope.launch(Dispatchers.Default) { runPdfOcr(id, file) }
    }

    private fun renderPage(file: File, index: Int, width: Int): Bitmap? = renderPdfPage(file, index, width)

    private suspend fun runPdfOcr(photoId: Long, file: File) {
        try {
            val parts = mutableListOf<String>()
            for (i in 0 until 5) {
                val bmp = renderPage(file, i, 1400) ?: break
                parts += recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text
            }
            // aggiorna solo il testo: non sovrascrive un promemoria impostato nel frattempo
            dao.setOcrText(photoId, parts.joinToString("\n"))
        } catch (e: Exception) {
            // OCR best effort
        }
    }

    private suspend fun runOcr(photoId: Long, file: File) {
        try {
            val image = InputImage.fromFilePath(getApplication(), Uri.fromFile(file))
            dao.setOcrText(photoId, recognizer.process(image).await().text)
        } catch (e: Exception) {
            // OCR best effort: la foto resta comunque salvata
        }
    }

    fun rename(id: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) dao.rename(id, name.trim(), System.currentTimeMillis())
    }

    fun setNotes(id: Long, notes: String) = viewModelScope.launch {
        dao.setNotes(id, notes, System.currentTimeMillis())
    }

    fun deletePhoto(p: Photo) = viewModelScope.launch {
        Reminders.cancel(getApplication(), p.id)
        dao.deletePhoto(p)
        withContext(Dispatchers.IO) {
            File(p.filePath).delete()
            if (p.isPdf) File(p.displayPath).delete()
        }
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        val photos = dao.photosOf(id)
        photos.forEach { Reminders.cancel(getApplication(), it.id) }
        val files = photos.flatMap { listOf(File(it.filePath), File(it.displayPath)) }
        dao.deleteSession(id)
        withContext(Dispatchers.IO) { files.forEach { it.delete() } }
    }
}
