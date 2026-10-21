package com.scanner.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Condivide un solo PDF con qualunque app (WhatsApp, email, Drive…). */
fun sharePdf(context: Context, file: File, session: Session) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        putExtra(Intent.EXTRA_SUBJECT, shortTitle(session.name))
        if (session.notes.isNotBlank()) putExtra(Intent.EXTRA_TEXT, session.notes)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Condividi PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Condivide foto, PDF e note con qualunque app. */
fun shareToAnyApp(context: Context, session: Session, photos: List<Photo>) {
    val text = buildString {
        append(shortTitle(session.name))
        if (session.notes.isNotBlank()) append("\n\n").append(session.notes)
    }
    val uris = ArrayList<Uri>(photos.map { uriFor(context, File(it.filePath)) })
    val intent = if (uris.isEmpty()) {
        Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (photos.any { it.isPdf }) "*/*" else "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }.apply {
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Condivide un solo allegato (foto o PDF). */
fun shareOne(context: Context, photo: Photo) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (photo.isPdf) "application/pdf" else "image/*"
        putExtra(Intent.EXTRA_STREAM, uriFor(context, File(photo.filePath)))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openPdf(context: Context, photo: Photo) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(photo.filePath))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Nessuna app per aprire PDF", Toast.LENGTH_SHORT).show()
    }
}

fun shareToWhatsApp(context: Context, session: Session, photos: List<Photo>) {
    val text = buildString {
        append(session.name)
        if (session.notes.isNotBlank()) append("\n\n").append(session.notes)
    }
    val uris = ArrayList<Uri>(photos.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it.filePath))
    })
    val intent = if (uris.isEmpty()) {
        Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (photos.any { it.isPdf }) "*/*" else "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }.apply {
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent(intent).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(intent, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
