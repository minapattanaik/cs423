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
import org.opencv.photo.Photo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.*
import kotlin.math.hypot
private fun isArrowLike(points: List<Offset>): Boolean {
    if (points.size < 8) return false

    val start = points.first()
    val end = points.last()
    val directDist = hypot(end.x - start.x, end.y - start.y)

    // path length (how wiggly)
    var pathLen = 0.0
    for (i in 1 until points.size) {
        pathLen += hypot(
            (points[i].x - points[i - 1].x).toDouble(),
            (points[i].y - points[i - 1].y).toDouble()
        )
    }

    if (directDist < 60) return false // too short to be intentional

    // If the path is much longer than direct line distance, it's not a straight arrow stroke
    val wiggleRatio = pathLen / directDist
    return wiggleRatio < 1.25
}

data class CropRequest(val uri: Uri, val rect: Rect)

/** ui state snapshot to survive recomposition */
data class ImageUiState(
    val sourceUri: Uri? = null,
    val correctedBitmap: Bitmap? = null,
    val isProcessing: Boolean = false,
    val savedFileName: String? = null,
    val error: String? = null,
    val pendingCropRequest: CropRequest? = null,
    val isErasing: Boolean = false,
    val lastGestureLabel: String? = null,
    val canUndo: Boolean          = false
)

class ImageViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState: StateFlow<ImageUiState> = _uiState.asStateFlow()

    private val ctx get() = getApplication<Application>().applicationContext
    private val undoStack = ArrayDeque<Bitmap>()

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
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        _uiState.value = _uiState.value.copy(
            correctedBitmap = previous,
            canUndo = undoStack.isNotEmpty()
        )
    }

    /**
     * called from gesture overlay on gesture completion
     * runs protractor recognition, maps bounding box to image coords,
     * if rectangle detected, saves bitmap and sends to composable
     */
    fun onGestureCompleted(points: List<Offset>, containerSize: IntSize, bitmap: Bitmap) {
        // --- ARROW → BLUR/SHARPEN
        if (points.size >= 8) {
            val start = points.first()
            val end = points.last()
            val dx = end.x - start.x
            val dy = end.y - start.y
            val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len >= 60f) {
                val intent = computeArrowIntent(points)
                launchBlurOrSharpen(intent, bitmap)
                return
                return
            }
        }

        if (points.size < 4) return

        val gdPoints      = points.map { GPoint(it.x, it.y) }
        val (name, score) = ProtractorRecognizer.recognize(gdPoints)
        Log.d("Gesture", "recognized=$name score=${"%.2f".format(score)} pts=${points.size}")


        _uiState.value = _uiState.value.copy(
            lastGestureLabel = "$name (${"%.2f".format(score)})"
        )

        if (score < 2.0f) return

        val cropRect = mapViewRectToImageRect(points, containerSize, bitmap) ?: return
        val currentBitmap = _uiState.value.correctedBitmap ?: return

        when (name) {
            "rectangle" -> {
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
                        _uiState.value = _uiState.value.copy(error = "Crop prepare failed: ${e.message}")
                    }
                }
            }
            "x" -> launchErase(cropRect, currentBitmap)
        }
    }


    private fun launchErase(region: Rect, bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(isErasing = true, error = null)
        viewModelScope.launch {
            try {
                val erased = withContext(Dispatchers.IO) {
                    eraseRegionWithGrabCut(bitmap, region)
                }
                undoStack.addLast(bitmap)
                _uiState.value = _uiState.value.copy(correctedBitmap = erased, isErasing = false, canUndo = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    error = "Erase failed: ${e.message}"
                )
            }
        }
    }

    private fun eraseRegionWithGrabCut(src: Bitmap, region: Rect): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(src, mat)

        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)

        // clamp to image bounds
        val l = region.left.coerceIn(0, rgb.cols() - 2)
        val t = region.top.coerceIn(0, rgb.rows() - 2)
        val w = region.width().coerceIn(1, rgb.cols() - l)
        val h = region.height().coerceIn(1, rgb.rows() - t)
        val cvRect = org.opencv.core.Rect(l, t, w, h)

        // GrabCut to find the object mask
        val mask    = Mat(rgb.size(), CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))
        val bgModel = Mat()
        val fgModel = Mat()
        Imgproc.grabCut(rgb, mask, cvRect, bgModel, fgModel, 10, Imgproc.GC_INIT_WITH_RECT)

        // combine definite + probable foreground into one mask
        val fgMask   = Mat()
        val prFgMask = Mat()
        Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()),    fgMask,   Core.CMP_EQ)
        Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFgMask, Core.CMP_EQ)
        Core.bitwise_or(fgMask, prFgMask, fgMask)

        // dilate mask slightly to catch edge fringe pixels
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(3.0, 3.0)
        )
        Imgproc.dilate(fgMask, fgMask, kernel)

        // fill erased region with surrounding background
        val inpainted = Mat()
        Photo.inpaint(rgb, fgMask, inpainted, 15.0, Photo.INPAINT_TELEA)

        // convert back to bitmap
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        Imgproc.cvtColor(inpainted, rgba, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(rgba, result)

        // cleanup
        listOf(mat, rgb, mask, bgModel, fgModel, fgMask, prFgMask, inpainted, rgba, kernel)
            .forEach { it.release() }

        return result
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
    private data class ArrowIntent(
        val isBlur: Boolean,    // left = blur
        val strength: Float     // based on length
    )

    private fun computeArrowIntent(points: List<Offset>): ArrowIntent {
        val start = points.first()
        val end = points.last()

        val dx = end.x - start.x
        val dy = end.y - start.y
        val len = hypot(dx, dy)

        val isBlur = dx < 0f // leftwards arrow = blur

        //mapping:
        val strength = (len / 80f).coerceIn(1f, 20f)

        return ArrowIntent(isBlur = isBlur, strength = strength)
    }

    private fun launchBlurOrSharpen(intent: ArrowIntent, bitmap: Bitmap) {
        _uiState.value = _uiState.value.copy(isProcessing = true)

        viewModelScope.launch {
            try {
                val out = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val mat = org.opencv.core.Mat()
                    org.opencv.android.Utils.bitmapToMat(bitmap, mat)

                    if (intent.isBlur) {
                        // Gaussian blur kernel must be odd
                        val k = (intent.strength * 2).toInt()
                            .coerceAtLeast(3)
                            .let { if (it % 2 == 0) it + 1 else it }

                        org.opencv.imgproc.Imgproc.GaussianBlur(
                            mat, mat,
                            org.opencv.core.Size(k.toDouble(), k.toDouble()),
                            0.0
                        )
                    } else {
                        // Sharpen = unsharp mask
                        val blurred = org.opencv.core.Mat()
                        org.opencv.imgproc.Imgproc.GaussianBlur(
                            mat, blurred,
                            org.opencv.core.Size(0.0, 0.0),
                            3.0
                        )
                        org.opencv.core.Core.addWeighted(mat, 1.5, blurred, -0.5, 0.0, mat)
                        blurred.release()
                    }

                    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    org.opencv.android.Utils.matToBitmap(mat, result)
                    mat.release()
                    result
                }

                undoStack.addLast(bitmap)

                _uiState.value = _uiState.value.copy(
                    correctedBitmap = out,
                    isProcessing = false,
                    canUndo = true,
                    lastGestureLabel = if (intent.isBlur) "arrow-left (blur)" else "arrow-right (sharpen)"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Blur/sharpen failed: ${e.message}"
                )
            }
        }
    }
}
