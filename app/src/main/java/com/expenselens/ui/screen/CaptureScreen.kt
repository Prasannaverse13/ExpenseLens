package com.expenselens.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenselens.data.storage.BillStorage
import com.expenselens.extract.ExtractionPipeline
import com.expenselens.ui.common.ExpenseLensPrimaryButton
import com.expenselens.ui.common.ExpenseLensTopBar
import com.expenselens.ui.theme.GlassEdge
import com.expenselens.ui.theme.GlassInner
import com.expenselens.ui.theme.GlassLight
import com.expenselens.ui.common.GrainientBackground
import com.expenselens.ui.theme.Emerald800
import com.expenselens.ui.theme.Emerald500
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

    fun reportError(message: String) {
        _stage.value = CaptureStage.Error(message)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

    val coroutineScope = rememberCoroutineScope()
    val executor: Executor = remember { ContextCompat.getMainExecutor(context) }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/*"
            coroutineScope.launch {
                val file = BillStorage.persistCopy(context, uri, uri.lastPathSegment)
                vm.processFile(context, file, mime)
            }
        }
    }

    LaunchedEffect(stage) {
        if (stage is CaptureStage.Done) onReview((stage as CaptureStage.Done).draftId)
    }

    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }

    GrainientBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                ExpenseLensTopBar(title = "Capture", onBack = onBack)

                if (hasCameraPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(androidx.compose.ui.graphics.Color.Black)
                    ) {
                        CameraPreview(
                            onImageCaptureReady = { ic ->
                                imageCaptureRef.value = ic
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (imageCaptureRef.value == null) {
                            Text(
                                text = "Starting camera…",
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Camera permission needed to scan bills.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (val s = stage) {
                        is CaptureStage.Processing -> Text(
                            text = s.label,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        is CaptureStage.Error -> Text(
                            text = s.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> {}
                    }
                    ExpenseLensPrimaryButton(
                        text = "Capture bill",
                        onClick = {
                            val ic = imageCaptureRef.value
                            if (ic == null) {
                                // Camera not ready — fall back to file picker so the
                                // user can still process an image from the gallery.
                                pickFile.launch(
                                    arrayOf(
                                        "image/*",
                                        "application/pdf",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    )
                                )
                                return@ExpenseLensPrimaryButton
                            }
                            val out = File(BillStorage.billsDir(context), "${UUID.randomUUID()}.jpg")
                            val opts = ImageCapture.OutputFileOptions.Builder(out).build()
                            ic.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    vm.processFile(context, out, "image/jpeg")
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    vm.reportError(exception.message ?: "Camera error")
                                }
                            })
                        },
                        enabled = hasCameraPermission
                    )
                    GlassActionRow(
                        onPick = {
                            pickFile.launch(
                                arrayOf(
                                    "image/*",
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                )
                            )
                        },
                        onManual = onManual
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassActionRow(onPick: () -> Unit, onManual: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassActionTile(
            label = "Upload",
            icon = Icons.Default.FileUpload,
            modifier = Modifier.weight(1f),
            onClick = onPick
        )
        GlassActionTile(
            label = "Manual",
            icon = Icons.Default.NoteAdd,
            modifier = Modifier.weight(1f),
            onClick = onManual
        )
    }
}

@Composable
private fun GlassActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassLight)
            .border(1.dp, GlassInner, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )
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
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }
            // Defer the camera binding until the view is attached to a
            // window — otherwise the surface provider is not yet ready and
            // the preview comes up black.
            view.post {
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(view.surfaceProvider)
                        }
                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        provider.unbindAll()
                        val selector = when {
                            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                                CameraSelector.DEFAULT_BACK_CAMERA
                            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            else -> {
                                Log.e("CameraPreview", "No camera available on this device")
                                return@addListener
                            }
                        }
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                        onImageCaptureReady(imageCapture)
                    }.onFailure { t ->
                        Log.e("CameraPreview", "Failed to bind camera", t)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
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
