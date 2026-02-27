package com.example.cs423application

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class CropRequest(val uri: Uri, val rect: Rect)

/** ui state snapshot to survive recomposition */
data class ImageUiState(
    val sourceUri: Uri? = null,
    val correctedBitmap: Bitmap? = null,
    val isProcessing: Boolean = false,
    val savedFileName: String? = null,
    val error: String? = null,
    val pendingCropRequest: CropRequest? = null
)

class ImageViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState: StateFlow<ImageUiState> = _uiState.asStateFlow()

    private val ctx get() = getApplication<Application>().applicationContext

    fun processImage(uri: Uri) {
        _uiState.value = ImageUiState(sourceUri = uri, isProcessing = true)
        viewModelScope.launch {
            try {
                val bitmap = ImagePipeline.loadAndFixOrientation(ctx, uri)
                _uiState.value = _uiState.value.copy(correctedBitmap = bitmap, isProcessing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error        = "Load failed: ${e.message}",
                    isProcessing = false
                )
            }
        }
    }

    fun saveImage() {
        val bitmap = _uiState.value.correctedBitmap ?: return
        _uiState.value = _uiState.value.copy(isProcessing = true, savedFileName = null)
        viewModelScope.launch {
            try {
                val name = ImagePipeline.saveCopy(ctx, bitmap)
                _uiState.value = _uiState.value.copy(savedFileName = name, isProcessing = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error        = "Save failed: ${e.message}",
                    isProcessing = false
                )
            }
        }
    }

    /**
     * called from gesture overlay on gesture completion
     * runs protractor recognition, maps bounding box to image coords,
     * if rectangle detected, saves bitmap and sends to composable
     */
    fun onGestureCompleted(points: List<Offset>, containerSize: IntSize, bitmap: Bitmap) {
        if (points.size < 4) return

        val gdPoints      = points.map { GPoint(it.x, it.y) }
        val (name, score) = ProtractorRecognizer.recognize(gdPoints)
        Log.d("Gesture", "recognized=$name score=${"%.2f".format(score)} pts=${points.size}")

        if (name != "rectangle" || score < 2.0f) return

        val cropRect = mapViewRectToImageRect(points, containerSize, bitmap) ?: return
        Log.d(
            "Gesture",
            "crop rect px: (${cropRect.left},${cropRect.top})-" +
                "(${cropRect.right},${cropRect.bottom}) in ${bitmap.width}×${bitmap.height}"
        )

        viewModelScope.launch {
            try {
                val uri = saveForCrop()
                _uiState.value = _uiState.value.copy(
                    pendingCropRequest = CropRequest(uri, cropRect)
                )
            } catch (e: Exception) {
                Log.e("Crop", "saveForCrop failed", e)
                _uiState.value = _uiState.value.copy(
                    error = "Crop prepare failed: ${e.message}"
                )
            }
        }
    }

    fun onCropRequestHandled() {
        _uiState.value = _uiState.value.copy(pendingCropRequest = null)
    }

    fun onCropResult(uri: Uri) {
        _uiState.value = _uiState.value.copy(isProcessing = true, savedFileName = null)
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (uri.scheme == "file") {
                        BitmapFactory.decodeFile(uri.path)
                    } else {
                        ctx.contentResolver.openInputStream(uri)
                            ?.use { stream -> BitmapFactory.decodeStream(stream) }
                    } ?: error("Cannot decode cropped image")
                }
                _uiState.value = _uiState.value.copy(
                    correctedBitmap = bitmap,
                    isProcessing    = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error        = "Crop load failed: ${e.message}",
                    isProcessing = false
                )
            }
        }
    }

    /**
     * maps list of screen-space gesture points to rectangle
     * returns null if mapped rectangle is zero w/h
     */
    private fun mapViewRectToImageRect(
        points: List<Offset>,
        containerSize: IntSize,
        bitmap: Bitmap
    ): Rect? {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val scale = minOf(
            containerSize.width.toFloat()  / bitmap.width,
            containerSize.height.toFloat() / bitmap.height
        )
        val padX = (containerSize.width  - bitmap.width  * scale) / 2f
        val padY = (containerSize.height - bitmap.height * scale) / 2f

        val imgLeft   = ((minX - padX) / scale).toInt().coerceIn(0, bitmap.width)
        val imgTop    = ((minY - padY) / scale).toInt().coerceIn(0, bitmap.height)
        val imgRight  = ((maxX - padX) / scale).toInt().coerceIn(0, bitmap.width)
        val imgBottom = ((maxY - padY) / scale).toInt().coerceIn(0, bitmap.height)

        return if (imgRight > imgLeft && imgBottom > imgTop) {
            Rect(imgLeft, imgTop, imgRight, imgBottom)
        } else null
    }

    /**
     * writes bitmap to temp JPEG and returns URI
     * must be called from coroutine
     */
    private suspend fun saveForCrop(): Uri = withContext(Dispatchers.IO) {
        val bitmap = _uiState.value.correctedBitmap
            ?: error("No corrected bitmap available to crop")

        val cropDir = File(ctx.cacheDir, "crop").also { it.mkdirs() }
        val srcFile = File(cropDir, "crop_src.jpg")
        FileOutputStream(srcFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            srcFile
        )
    }
}
