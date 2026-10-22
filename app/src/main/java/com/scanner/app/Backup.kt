package com.scanner.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Copia la foto scattata nella Galleria (Immagini/Scanner). Richiede Android 10+. */
fun saveToGallery(context: Context, file: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Scanner")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (e: Exception) {
        // la foto resta comunque salvata nell'app
    }
}

class BackupManager(private val context: Context, private val dao: ScannerDao, private val photosDir: File) {

    /** Crea Scanner_backup_<data>.zip nella cartella Download. Restituisce il messaggio per l'utente. */
    suspend fun backup(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Il backup richiede Android 10 o superiore"
        val sessions = dao.allSessions()
        if (sessions.isEmpty()) return "Niente da salvare: non ci sono sessioni"

        val manifest = JSONObject().put("version", 1)
        val arr = JSONArray()
        val files = LinkedHashSet<File>()
        for (s in sessions) {
            val photos = dao.photosOf(s.id)
            val pa = JSONArray()
            for (p in photos) {
                val f = File(p.filePath)
                if (!f.exists()) continue
                files += f
                if (p.isPdf) File(p.displayPath).takeIf { it.exists() }?.let { files += it }
                pa.put(JSONObject().put("file", f.name).put("ocrText", p.ocrText).put("createdAt", p.createdAt).put("reminderAt", p.reminderAt))
            }
            arr.put(
                JSONObject().put("name", s.name).put("notes", s.notes)
                    .put("createdAt", s.createdAt).put("updatedAt", s.updatedAt).put("photos", pa)
            )
        }
        manifest.put("sessions", arr)

        val name = "Scanner_backup_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) + ".zip"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return "Impossibile creare il file in Download"
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("backup.json"))
                    zip.write(manifest.toString().toByteArray())
                    zip.closeEntry()
                    for (f in files) {
                        zip.putNextEntry(ZipEntry("files/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return "Backup non riuscito: ${e.message}"
        }
        return "Backup salvato in Download: $name (${sessions.size} sessioni)"
    }

    /**
     * Ripristina da un backup. Non cancella nulla: le sessioni già presenti
     * (stesso nome e data di creazione) vengono saltate.
     */
    suspend fun restore(uri: Uri): String {
        try {
            // 1) estrae lo zip in una cartella temporanea
            val tmp = File(context.cacheDir, "restore_${System.nanoTime()}").apply { mkdirs() }
            try {
                var manifestText: String? = null
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            val base = File(e.name).name // evita percorsi con ".."
                            if (e.name == "backup.json") {
                                manifestText = zip.readBytes().decodeToString()
                            } else if (e.name.startsWith("files/") && base.isNotEmpty()) {
                                File(tmp, base).outputStream().use { zip.copyTo(it) }
                            }
                            e = zip.nextEntry
                        }
                    }
                }
                val text = manifestText ?: return "Il file scelto non è un backup di Scanner"
                val sessions = JSONObject(text).getJSONArray("sessions")

                // 2) importa
                val existing = dao.allSessions().map { it.name to it.createdAt }.toSet()
                var imported = 0
                var skipped = 0
                for (i in 0 until sessions.length()) {
                    val so = sessions.getJSONObject(i)
                    val name = so.getString("name")
                    val created = so.getLong("createdAt")
                    if ((name to created) in existing) {
                        skipped++
                        continue
                    }
                    val sid = dao.insertSession(
                        Session(name = name, notes = so.optString("notes"), createdAt = created, updatedAt = so.getLong("updatedAt"))
                    )
                    val pa = so.getJSONArray("photos")
                    for (j in 0 until pa.length()) {
                        val po = pa.getJSONObject(j)
                        val fileName = File(po.getString("file")).name
                        val src = File(tmp, fileName)
                        if (!src.exists()) continue
                        var dest = File(photosDir, fileName)
                        if (dest.exists()) dest = File(photosDir, "R${System.nanoTime()}_$fileName")
                        src.copyTo(dest, overwrite = true)
                        File(tmp, "$fileName.png").takeIf { it.exists() }?.copyTo(File("${dest.absolutePath}.png"), overwrite = true)
                        dao.insertPhoto(
                            Photo(sessionId = sid, filePath = dest.absolutePath, ocrText = po.optString("ocrText"), createdAt = po.getLong("createdAt"),
                                // i promemoria già passati non si ripristinano
                                reminderAt = po.optLong("reminderAt", 0L).takeIf { it > System.currentTimeMillis() })
                        )
                    }
                    imported++
                }
                return "Ripristino completato: $imported sessioni importate" +
                    if (skipped > 0) ", $skipped già presenti (saltate)" else ""
            } finally {
                tmp.deleteRecursively()
            }
        } catch (e: Exception) {
            return "Ripristino non riuscito: ${e.message}"
        }
    }
}
