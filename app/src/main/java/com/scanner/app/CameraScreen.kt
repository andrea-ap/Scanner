package com.scanner.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CameraScreen(vm: MainViewModel, nav: NavController, sessionId: Long) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var pending by remember { mutableStateOf<File?>(null) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    pending?.let { file ->
        NameDialog("Nome sessione", vm.defaultName(), "Salva", onDismiss = {
            file.delete()
            pending = null
        }) { name ->
            pending = null
            scope.launch {
                val id = vm.createSession(name, file, fromCamera = true)
                nav.navigate("session/$id") { popUpTo("home") }
            }
        }
    }

    if (!granted) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Serve il permesso fotocamera")
                Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text("Concedi") }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
        LargeFloatingActionButton(
            onClick = {
                val file = vm.newPhotoFile()
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (sessionId < 0) {
                                pending = file
                            } else {
                                scope.launch {
                                    vm.addPhoto(sessionId, file, fromCamera = true)
                                    nav.popBackStack()
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            file.delete()
                        }
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
        ) { Icon(Icons.Default.Camera, "Scatta") }
    }
}
