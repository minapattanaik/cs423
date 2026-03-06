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
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
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
    val pendingCropRequest: CropRequest? = null,
    val isErasing: Boolean = false,
    val lastGestureLabel: String? = null,
    val canUndo: Boolean = false,
    /** true while the VM is holding stroke 1 and waiting for the X second stroke */
    val awaitingXStroke: Boolean = false
)

class ImageViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState: StateFlow<ImageUiState> = _uiState.asStateFlow()

    private val ctx get() = getApplication<Application>().applicationContext
    private val undoStack = ArrayDeque<Bitmap>()

    /** holds first stroke's screen points while waiting for second stroke */
    private var storedStroke1: List<Offset>? = null

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
     * called after stroke completion
     *
     * single stroke: tries to recognize stroke
     *    - recognized as rectangle → handle immediately
     *    - diagonal but unrecognized → store as stroke 1, set awaitingXStroke
     *    - not a diagonal → discard
     *
     * two-stroke path: concatenates stored stroke with incoming and tries combined recognition
     *    - recognized as x → handle. else discard
     */
    fun onGestureCompleted(points: List<Offset>, containerSize: IntSize, bitmap: Bitmap) {
        if (points.size < 4) return

        val gdPoints      = points.map { GPoint(it.x, it.y) }
        val (name, score) = PDollarRecognizer.recognize(gdPoints)
        Log.d("Gesture", "recognized=$name score=${"%.2f".format(score)} pts=${points.size}")
        _uiState.value = _uiState.value.copy(lastGestureLabel = "$name (${"%.2f".format(score)})")

        if (name == "rectangle" && score >= 0.45f) {
            storedStroke1 = null
            _uiState.value = _uiState.value.copy(awaitingXStroke = false)
            handleRecognizedGesture(name, points, containerSize, bitmap)
            return
        }

        val prev = storedStroke1
        if (prev == null) {
            if (isLikelyDiagonal(points)) {
                storedStroke1 = points
                _uiState.value = _uiState.value.copy(awaitingXStroke = true)
                Log.d("Gesture", "stroke 1 stored, awaiting stroke 2")
            } else {
                Log.d("Gesture", "not a diagonal, discarding")
            }
        } else {
            val combined   = prev + points
            val combinedGd = combined.map { GPoint(it.x, it.y) }
            val (name2, score2) = PDollarRecognizer.recognize(combinedGd)
            Log.d("Gesture", "combined=$name2 score=${"%.2f".format(score2)} pts=${combined.size}")
            _uiState.value = _uiState.value.copy(
                lastGestureLabel = "$name2 (${"%.2f".format(score2)})",
                awaitingXStroke  = false
            )
            storedStroke1 = null
            // bug fixing for too strict x threshold -- since stroke 1 was already validated
            // fire erase whenever combined shape scores well enough
            if (score2 >= 0.13f) {
                handleRecognizedGesture("x", combined, containerSize, bitmap)
            }
        }
    }

    /**
     * returns true if [points] traces a roughly straight line.
     * ratio = bounding-box diagonal / path length: diagonal ≈ 1.0, closed shapes << 1.0
     */
    private fun isLikelyDiagonal(points: List<Offset>): Boolean {
        if (points.size < 4) return false
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val bboxDiag = sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY))
        if (bboxDiag < 1f) return false
        var pathLen = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLen += sqrt(dx * dx + dy * dy)
        }
        return bboxDiag / pathLen >= 0.7f
    }

    private fun handleRecognizedGesture(
        name: String,
        points: List<Offset>,
        containerSize: IntSize,
        bitmap: Bitmap
    ) {
        val cropRect      = mapViewRectToImageRect(points, containerSize, bitmap) ?: return
        val currentBitmap = _uiState.value.correctedBitmap ?: return
        when (name) {
            "rectangle" -> {
                Log.d("Gesture", "crop rect px: (${cropRect.left},${cropRect.top})-" +
                    "(${cropRect.right},${cropRect.bottom}) in ${bitmap.width}×${bitmap.height}")
                viewModelScope.launch {
                    try {
                        val uri = saveForCrop()
                        _uiState.value = _uiState.value.copy(pendingCropRequest = CropRequest(uri, cropRect))
                    } catch (e: Exception) {
                        Log.e("Crop", "saveForCrop failed", e)
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

        val l = region.left.coerceIn(0, rgb.cols() - 2)
        val t = region.top.coerceIn(0, rgb.rows() - 2)
        val w = region.width().coerceIn(1, rgb.cols() - l)
        val h = region.height().coerceIn(1, rgb.rows() - t)
        val cvRect = org.opencv.core.Rect(l, t, w, h)

        val mask    = Mat(rgb.size(), CvType.CV_8UC1, Scalar(Imgproc.GC_BGD.toDouble()))
        val bgModel = Mat()
        val fgModel = Mat()
        Imgproc.grabCut(rgb, mask, cvRect, bgModel, fgModel, 10, Imgproc.GC_INIT_WITH_RECT)

        val fgMask   = Mat()
        val prFgMask = Mat()
        Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()),    fgMask,   Core.CMP_EQ)
        Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), prFgMask, Core.CMP_EQ)
        Core.bitwise_or(fgMask, prFgMask, fgMask)

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(3.0, 3.0)
        )
        Imgproc.dilate(fgMask, fgMask, kernel)

        val inpainted = Mat()
        Photo.inpaint(rgb, fgMask, inpainted, 15.0, Photo.INPAINT_TELEA)

        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        Imgproc.cvtColor(inpainted, rgba, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(rgba, result)

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
}
