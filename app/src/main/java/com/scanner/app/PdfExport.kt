package com.scanner.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val PAGE_W = 595
private const val PAGE_H = 842
private const val MARGIN = 24

/** Lato massimo (px) di foto e pagine PDF nella versione compressa. */
private const val PDF_SMALL_PHOTO = 1200
private const val PDF_SMALL_PAGE = 1200

/** Peso leggibile: "850 KB" sotto 1 MB, altrimenti "4,2 MB". */
fun formatSize(bytes: Long): String =
    if (bytes < 1_000_000) "${maxOf(1L, (bytes + 500) / 1000)} KB"
    else String.format(java.util.Locale.ITALY, "%.1f MB", bytes / 1_000_000.0)

/** Foto ruotata secondo l'EXIF e ridotta a [maxSide] pixel. */
private fun loadBitmap(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val rotation = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) {
        0f
    }
    val rotated = if (rotation == 0f) bmp else
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    // il campionamento a potenze di 2 non basta: porta il lato più lungo esattamente a maxSide
    val longest = max(rotated.width, rotated.height)
    if (longest <= maxSide) return rotated
    val k = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(rotated, (rotated.width * k).toInt(), (rotated.height * k).toInt(), true)
}

/**
 * Unisce tutti gli allegati della sessione (foto e pagine dei PDF) in un solo PDF.
 * Se ci sono note, la prima pagina contiene nome e note. Restituisce null se non c'è nulla da inserire.
 */
fun buildSessionPdf(context: Context, session: Session, photos: List<Photo>, compressed: Boolean = false): File? {
    val doc = PdfDocument()
    var pageNo = 0

    fun addBitmap(bmp: Bitmap) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
        val scale = min((PAGE_W - 2f * MARGIN) / bmp.width, (PAGE_H - 2f * MARGIN) / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (PAGE_W - w) / 2f
        val top = (PAGE_H - h) / 2f
        page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
    }

    if (session.notes.isNotBlank()) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
        val canvas: Canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val titlePaint = TextPaint().apply { color = Color.BLACK; textSize = 22f; isFakeBoldText = true; isAntiAlias = true }
        val bodyPaint = TextPaint().apply { color = Color.DKGRAY; textSize = 14f; isAntiAlias = true }
        val width = PAGE_W - 2 * MARGIN * 2
        canvas.save()
        canvas.translate(MARGIN * 2f, MARGIN * 2f)
        val titleLayout = StaticLayout.Builder.obtain(shortTitle(session.name), 0, shortTitle(session.name).length, titlePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        titleLayout.draw(canvas)
        canvas.translate(0f, titleLayout.height + 16f)
        StaticLayout.Builder.obtain(session.notes, 0, session.notes.length, bodyPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build().draw(canvas)
        canvas.restore()
        doc.finishPage(page)
    }

    for (p in photos) {
        val file = File(p.filePath)
        if (!file.exists()) continue
        if (p.isPdf) {
            for (i in 0 until pdfPageCount(file)) {
                renderPdfPage(file, i, if (compressed) PDF_SMALL_PAGE else 1240)?.let { addBitmap(it); it.recycle() }
            }
        } else {
            loadBitmap(file, if (compressed) PDF_SMALL_PHOTO else 2000)?.let { addBitmap(it); it.recycle() }
        }
    }

    if (pageNo == 0) {
        doc.close()
        return null
    }

    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val safe = shortTitle(session.name).replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "Scanner" }
    dir.listFiles()?.forEach { if (!it.name.startsWith(safe)) it.delete() }
    val out = File(dir, if (compressed) "${safe}_compresso.pdf" else "$safe.pdf")
    out.outputStream().use { doc.writeTo(it) }
    doc.close()
    return out
}
