package com.expenselens.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.storage.BillStorage
import com.expenselens.extract.ExtractionPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Inject

sealed class CaptureStage {
    data object Idle : CaptureStage()
    data class Processing(val label: String) : CaptureStage()
    data class Done(val draftId: String) : CaptureStage()
    data class Error(val message: String) : CaptureStage()
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val pipeline: ExtractionPipeline
) : ViewModel() {

    private val _stage = MutableStateFlow<CaptureStage>(CaptureStage.Idle)
    val stage: StateFlow<CaptureStage> = _stage.asStateFlow()

    fun processFile(context: android.content.Context, file: File, mime: String?) {
        viewModelScope.launch {
            _stage.value = CaptureStage.Processing("Reading document...")
            try {
                val result = pipeline.run(file, mime)
                val draft = DraftStore.save(context, result, file.absolutePath, mime)
                _stage.value = CaptureStage.Done(draft)
            } catch (t: Throwable) {
                _stage.value = CaptureStage.Error(t.message ?: "Could not process the file.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    onReview: (String) -> Unit,
    onManual: () -> Unit,
    vm: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val stage by vm.stage.collectAsState()
    var hasCameraPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCameraPermission = it }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        hasCameraPermission = granted
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri)
            viewModelScope.launch {
                val file = BillStorage.persistCopy(context, uri, uri.lastPathSegment)
                vm.processFile(context, file, mime)
            }
        }
    }

    LaunchedEffect(stage) {
        if (stage is CaptureStage.Done) onReview((stage as CaptureStage.Done).draftId)
    }

    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    val executor: Executor = remember { ContextCompat.getMainExecutor(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capture") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { inner ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(inner)) {

            if (hasCameraPermission) {
                CameraPreview(
                    onImageCaptureReady = { imageCaptureRef.value = it },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Camera permission needed to scan bills.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val s = stage) {
                    is CaptureStage.Processing -> Text(
                        text = s.label,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    is CaptureStage.Error -> Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                    else -> {}
                }
                Button(
                    onClick = {
                        val ic = imageCaptureRef.value ?: return@Button
                        val out = File(BillStorage.billsDir(context), "${UUID.randomUUID()}.jpg")
                        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
                        ic.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                vm.processFile(context, out, "image/jpeg")
                            }
                            override fun onError(exception: ImageCaptureException) {
                                // No-op: user can retry or pick a file.
                            }
                        })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasCameraPermission
                ) {
                    Icon(Icons.Default.Camera, null); Text("  Capture bill")
                }
                OutlinedButton(
                    onClick = {
                        pickFile.launch(
                            arrayOf(
                                "image/*",
                                "application/pdf",
                                "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, null); Text("  Upload PDF / image / doc")
                }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.NoteAdd, null); Text("  Enter manually")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onImageCaptureReady: (ImageCapture) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val view = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                    onImageCaptureReady(imageCapture)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}
