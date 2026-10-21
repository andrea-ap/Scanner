package com.scanner.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

fun pdfPageCount(file: File): Int = try {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    try {
        renderer.pageCount
    } finally {
        renderer.close()
        pfd.close()
    }
} catch (e: Exception) {
    0
}

/** Disegna una pagina del PDF su sfondo bianco, larga [width] pixel. */
fun renderPdfPage(file: File, index: Int, width: Int): Bitmap? = try {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    try {
        if (index >= renderer.pageCount) null else {
            val page = renderer.openPage(index)
            try {
                val h = (width.toFloat() * page.height / page.width).toInt()
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        }
    } finally {
        renderer.close()
        pfd.close()
    }
} catch (e: Exception) {
    null
}
