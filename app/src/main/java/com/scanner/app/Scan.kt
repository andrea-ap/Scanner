package com.scanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Scansione documento (bordi automatici, raddrizzamento, filtri) con lo scanner di Google ML Kit.
 * Restituisce la funzione da chiamare per avviarla; [onPages] riceve le pagine scansionate come immagini.
 */
@Composable
fun rememberDocScanner(onPages: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onPages)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val pages = result?.pages?.map { it.imageUri }.orEmpty()
            if (pages.isNotEmpty()) callback(pages)
        }
    }
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(20)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
    }
    return {
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, "Scanner non disponibile", Toast.LENGTH_SHORT).show()
        } else {
            scanner.getStartScanIntent(activity)
                .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener {
                    Toast.makeText(context, "Scanner non disponibile: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
