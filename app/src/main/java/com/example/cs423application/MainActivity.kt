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
import androidx.compose.ui.platform.LocalConfiguration
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
                    awaitingXStroke    = state.awaitingXStroke,
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
    val imageHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
    Text("Loaded via Coil", style = MaterialTheme.typography.labelSmall)
    AsyncImage(
        model              = uri,
        contentDescription = "Original image",
        modifier           = Modifier.fillMaxWidth().height(imageHeight),
        contentScale       = ContentScale.Fit
    )
}

@Composable
private fun ProcessingIndicator() {
    CircularProgressIndicator()
    Text("Stage 2: fixing orientation…")
}

/**
 * displays orientation-corrected bitmap with two stroke overlay
 */
@Composable
private fun GestureImageSection(
    bitmap: Bitmap,
    isErasing: Boolean,
    awaitingXStroke: Boolean,
    onGestureCompleted: (points: List<Offset>, containerSize: IntSize) -> Unit
) {
    Text(
        "Stage 2 done: ${bitmap.width}×${bitmap.height} (orientation corrected)",
        style = MaterialTheme.typography.labelSmall
    )

    val imageHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
    var containerSize    by remember { mutableStateOf(IntSize.Zero) }
    val gesturePoints     = remember { mutableStateListOf<Offset>() }
    var savedFirstStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    LaunchedEffect(awaitingXStroke) {
        if (!awaitingXStroke) savedFirstStroke = emptyList()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(imageHeight)
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

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            gesturePoints.clear()
                            gesturePoints.add(offset)
                        },
                        onDrag = { change, _ ->
                            gesturePoints.add(change.position)
                            change.consume()
                        },
                        onDragEnd = {
                            if (gesturePoints.size >= 4) {
                                savedFirstStroke = gesturePoints.toList()
                                onGestureCompleted(gesturePoints.toList(), containerSize)
                            }
                            gesturePoints.clear()
                        },
                        onDragCancel = { gesturePoints.clear() }
                    )
                }
        ) {
            if (awaitingXStroke && savedFirstStroke.size > 1) {
                val path1 = Path().apply {
                    moveTo(savedFirstStroke[0].x, savedFirstStroke[0].y)
                    savedFirstStroke.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path  = path1,
                    color = Color(0x55FF4040),
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            if (gesturePoints.size > 1) {
                val path2 = Path().apply {
                    moveTo(gesturePoints[0].x, gesturePoints[0].y)
                    gesturePoints.drop(1).forEach { lineTo(it.x, it.y) }
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
        "Draw a rectangle on the image to crop  •  Draw an X to erase",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
