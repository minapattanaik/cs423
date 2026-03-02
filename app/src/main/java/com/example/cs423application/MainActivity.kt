package com.example.cs423application

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import org.opencv.android.OpenCVLoader
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.cs423application.ui.theme.CS423ApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()
        enableEdgeToEdge()
        setContent {
            CS423ApplicationTheme {
                ImagePipelineScreen()
            }
        }
    }
}

@Composable
fun ImagePipelineScreen(vm: ImageViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.processImage(it) } }

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { vm.onCropResult(it) }
        } else {
            android.util.Log.e("Crop", result.error?.message ?: "Crop failed")
        }
    }

    // react to crop requests sent from viewmodel
    val pendingCrop = state.pendingCropRequest
    LaunchedEffect(pendingCrop) {
        if (pendingCrop != null) {
            cropLauncher.launch(
                CropImageContractOptions(
                    uri              = pendingCrop.uri,
                    cropImageOptions = CropImageOptions().apply {
                        initialCropWindowRectangle = pendingCrop.rect
                        guidelines                 = CropImageView.Guidelines.ON
                        outputCompressQuality      = 95
                    }
                )
            )
            vm.onCropRequestHandled()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Image Pipeline", style = MaterialTheme.typography.headlineMedium)

            ImagePickerSection(onPick = { pickerLauncher.launch("image/*") })

            state.sourceUri?.let { uri ->
                if (state.correctedBitmap == null && !state.isProcessing) {
                    SourceImagePreview(uri = uri)
                }
            }

            if (state.isProcessing) {
                ProcessingIndicator()
            }

            state.correctedBitmap?.let { bmp ->
                GestureImageSection(
                    bitmap             = bmp,
                    isErasing          = state.isErasing,
                    onGestureCompleted = { points, containerSize ->
                        vm.onGestureCompleted(points, containerSize, bmp)
                    }
                )
                state.lastGestureLabel?.let { label ->
                    Text(
                        "Recognized: $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (state.canUndo) {
                    Button(onClick = { vm.undo() }) {
                        Text("Undo Erase")
                    }
                }
                Button(onClick = { vm.saveImage() }) {
                    Text("Save Copy")
                }
            }

            state.savedFileName?.let { name ->
                Text("Saved: Pictures/CS423/$name", color = MaterialTheme.colorScheme.primary)
            }

            state.error?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ImagePickerSection(onPick: () -> Unit) {
    Button(onClick = onPick) { Text("Pick Image") }
}

@Composable
private fun SourceImagePreview(uri: Uri) {
    Text("Loaded via Coil", style = MaterialTheme.typography.labelSmall)
    AsyncImage(
        model              = uri,
        contentDescription = "Original image",
        modifier           = Modifier.fillMaxWidth().height(300.dp),
        contentScale       = ContentScale.Fit
    )
}

@Composable
private fun ProcessingIndicator() {
    CircularProgressIndicator()
    Text("Stage 2: fixing orientation…")
}

/**
 * displays orientation-corrected bitmap with overlay
 * gesture recognition and coordinate math forwarded to ViewModel using
 * [onGestureCompleted]
 */
@Composable
private fun GestureImageSection(
    bitmap: Bitmap,
    isErasing: Boolean,
    onGestureCompleted: (points: List<Offset>, containerSize: IntSize) -> Unit
)

{
    Text(
        "Stage 2 done: ${bitmap.width}×${bitmap.height} (orientation corrected)",
        style = MaterialTheme.typography.labelSmall
    )

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val stroke1 = remember { mutableStateListOf<Offset>() }
    val stroke2 = remember { mutableStateListOf<Offset>() }
    var strokeCount by remember { mutableStateOf(0) }
    val displayPoints = remember { mutableStateListOf<Offset>() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .onSizeChanged { containerSize = it }
    ) {
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = "Orientation-corrected image",
            modifier           = Modifier.fillMaxSize(),
            contentScale       = ContentScale.Fit
        )
        if (isErasing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // draws stroke live then forwards to viewmodel on lift
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (strokeCount == 0) {
                                stroke1.clear()
                                stroke2.clear()
                                displayPoints.clear()
                                stroke1.add(offset)
                                displayPoints.add(offset)
                            } else {
                                stroke2.add(offset)
                                displayPoints.add(offset)
                            }
                        },
                        onDrag = { change, _ ->
                            if (strokeCount == 0) {
                                stroke1.add(change.position)
                            } else {
                                stroke2.add(change.position)
                            }
                            displayPoints.add(change.position)
                            change.consume()
                        },
                        onDragEnd = {
                            strokeCount++
                            if (strokeCount == 1) {
                                // check if it's already a rectangle with just one stroke
                                val (name, score) = ProtractorRecognizer.recognize(stroke1.map { GPoint(it.x, it.y) })
                                if (name == "rectangle" && score >= 2.0f) {
                                    onGestureCompleted(stroke1.toList(), containerSize)
                                    stroke1.clear()
                                    displayPoints.clear()
                                    strokeCount = 0
                                }
                                // otherwise keep stroke visible and wait for second stroke (X gesture)
                            } else if (strokeCount >= 2) {
                                val allPoints = stroke1 + stroke2
                                if (allPoints.size >= 4) {
                                    onGestureCompleted(allPoints, containerSize)
                                }
                                stroke1.clear()
                                stroke2.clear()
                                displayPoints.clear()
                                strokeCount = 0
                            }
                        },
                        onDragCancel = {
                            stroke1.clear()
                            stroke2.clear()
                            displayPoints.clear()
                            strokeCount = 0
                        }
                    )
                }
        ) {
            if (stroke1.size > 1) {
                val path1 = Path().apply {
                    moveTo(stroke1[0].x, stroke1[0].y)
                    stroke1.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path  = path1,
                    color = Color(0xBBFF4040),
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            // draw stroke 2 separately so they don't connect
            if (stroke2.size > 1) {
                val path2 = Path().apply {
                    moveTo(stroke2[0].x, stroke2[0].y)
                    stroke2.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path  = path2,
                    color = Color(0xBBFF4040),
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }

    Text(
        "Draw a rectangle on the image to crop •  Draw an X to erase",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
