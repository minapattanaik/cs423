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

    /**
     * Last rectangle-gesture region (image-pixel coordinates).
     * Set when a rectangle gesture is drawn; used as the target region for
     * blur/sharpen if an arrow is drawn afterward.  Cleared on new image load
     * or after the crop result is received (bitmap has changed).
     */
    private var lastRectRegion: android.graphics.Rect? = null

    fun processImage(uri: Uri) {
        lastRectRegion = null
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

        val closedPoints  = closeGapIfNeeded(points)
        val gdPoints      = closedPoints.map { GPoint(it.x, it.y) }
        val (name, score) = PDollarRecognizer.recognize(gdPoints)
        Log.d("Gesture", "recognized=$name score=${"%.2f".format(score)} pts=${points.size}")
        _uiState.value = _uiState.value.copy(lastGestureLabel = "$name (${"%.2f".format(score)})")

        if (name == "rectangle" && score >= 0.38f) {
            storedStroke1 = null
            _uiState.value = _uiState.value.copy(awaitingXStroke = false)
            handleRecognizedGesture(name, points, containerSize, bitmap)
            return
        }

        // single-stroke complete arrow (needs higher confidence)
        if (name == "arrow" && score >= 0.32f && isLikelyArrow(points)) {
            storedStroke1 = null
            _uiState.value = _uiState.value.copy(awaitingXStroke = false)
            handleArrowGesture(points, containerSize, bitmap)
            return
        }

        // single-stroke X (looping/cursive/corner-connected) — skip plain diagonals
        // which are valid first strokes of a two-stroke X
        if (name == "x" && score >= 0.30f && !isLikelyDiagonal(closedPoints)) {
            storedStroke1 = null
            _uiState.value = _uiState.value.copy(awaitingXStroke = false)
            handleRecognizedGesture("x", points, containerSize, bitmap)
            return
        }

        val prev = storedStroke1
        if (prev == null) {
            // store any unmatched stroke as potential first X stroke
            storedStroke1 = points
            _uiState.value = _uiState.value.copy(awaitingXStroke = true)
            Log.d("Gesture", "stroke 1 stored, awaiting stroke 2")
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
            // arrow priority: combined arrowhead + line (either order) scores as "arrow"
            if (name2 == "arrow" && score2 >= 0.15f) {
                handleArrowGesture(combined, containerSize, bitmap)
            } else if (name2 != "arrow" && score2 >= 0.15f) {
                handleRecognizedGesture("x", combined, containerSize, bitmap)
            }
        }
    }

    /**
     * closes a near-complete shape by interpolating points across the gap between
     * first and last point. skips diagonals/arrows (pathLen/bboxDiag < 2.0) and
     * shapes too open to close confidently (gap/bboxDiag > 0.20)
     */
    private fun closeGapIfNeeded(points: List<Offset>): List<Offset> {
        if (points.size < 4) return points
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val bboxDiag = sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY))
        if (bboxDiag < 1f) return points

        var pathLen = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLen += sqrt(dx * dx + dy * dy)
        }
        if (pathLen / bboxDiag < 2.0f) return points

        val dx  = points.first().x - points.last().x
        val dy  = points.first().y - points.last().y
        val gap = sqrt(dx * dx + dy * dy)
        if (gap / bboxDiag > 0.20f) return points

        val closed = points.toMutableList()
        val steps  = 8
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            closed.add(Offset(
                points.last().x + t * dx,
                points.last().y + t * dy
            ))
        }
        Log.d("Gesture", "gap closed: gap/bbox=${"%.2f".format(gap / bboxDiag)}")
        return closed
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
        if (bboxDiag < 12f) return false
        var pathLen = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            pathLen += sqrt(dx * dx + dy * dy)
        }
        return bboxDiag / pathLen >= 0.35f
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
                lastRectRegion = cropRect
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
                    eraseRegion(bitmap, region)
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
    private fun eraseRegion(src: Bitmap, region: Rect): Bitmap {

        val maxDim = 500f
        val scaleFactor = minOf(maxDim / src.width, maxDim / src.height, 1.0f)
        val scaledW = (src.width * scaleFactor).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scaleFactor).toInt().coerceAtLeast(1)

        val scaledSrc = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val scaledRegion = Rect(
            (region.left * scaleFactor).toInt().coerceIn(0, scaledW - 1),
            (region.top * scaleFactor).toInt().coerceIn(0, scaledH - 1),
            (region.right * scaleFactor).toInt().coerceIn(0, scaledW),
            (region.bottom * scaleFactor).toInt().coerceIn(0, scaledH)
        )

        val mat = Mat()
        Utils.bitmapToMat(scaledSrc, mat)
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)


        val centerX = ((scaledRegion.left + scaledRegion.right) / 2).coerceIn(0, scaledW - 1)
        val centerY = ((scaledRegion.top + scaledRegion.bottom) / 2).coerceIn(0, scaledH - 1)
        val centerColor = hsv.get(centerY, centerX) ?: doubleArrayOf(0.0, 0.0, 0.0)

        val colorMask = Mat()
        val tolerance = 40.0
        Core.inRange(
            hsv,
            Scalar((centerColor[0] - tolerance).coerceAtLeast(0.0), (centerColor[1] - 80.0).coerceAtLeast(0.0), (centerColor[2] - 80.0).coerceAtLeast(0.0)),
            Scalar((centerColor[0] + tolerance).coerceAtMost(180.0), (centerColor[1] + 80.0).coerceAtMost(255.0), (centerColor[2] + 80.0).coerceAtMost(255.0)),
            colorMask
        )

        val regionMask = Mat.zeros(rgb.size(), CvType.CV_8UC1)
        val roiRect = org.opencv.core.Rect(scaledRegion.left, scaledRegion.top, (scaledRegion.right - scaledRegion.left).coerceAtLeast(1), (scaledRegion.bottom - scaledRegion.top).coerceAtLeast(1))
        val roi = regionMask.submat(roiRect)
        roi.setTo(Scalar(255.0))
        roi.release()

        val fgMask = Mat()
        Core.bitwise_and(colorMask, regionMask, fgMask)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(9.0, 9.0))
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.dilate(fgMask, fgMask, kernel, org.opencv.core.Point(-1.0, -1.0), 2)

        // Inpaint at low res
        val inpaintedScaled = Mat()
        Photo.inpaint(rgb, fgMask, inpaintedScaled, 5.0, Photo.INPAINT_NS)

        val alphaMask = Mat()
        fgMask.convertTo(alphaMask, CvType.CV_32F, 1.0 / 255.0)
        Imgproc.GaussianBlur(alphaMask, alphaMask, org.opencv.core.Size(15.0, 15.0), 0.0)

        val fRgb = Mat(); rgb.convertTo(fRgb, CvType.CV_32F)
        val fInp = Mat(); inpaintedScaled.convertTo(fInp, CvType.CV_32F)
        val ones = Mat.ones(alphaMask.size(), alphaMask.type())
        val invAlpha = Mat()
        Core.subtract(ones, alphaMask, invAlpha)

        val blended = Mat(fRgb.size(), fRgb.type())
        val channelsSrc = mutableListOf<Mat>(); Core.split(fRgb, channelsSrc)
        val channelsInp = mutableListOf<Mat>(); Core.split(fInp, channelsInp)
        val channelsOut = mutableListOf<Mat>()

        for (i in 0 until 3) {
            val out = Mat()
            val p1 = Mat(); Core.multiply(channelsInp[i], alphaMask, p1)
            val p2 = Mat(); Core.multiply(channelsSrc[i], invAlpha, p2)
            Core.add(p1, p2, out)
            channelsOut.add(out)
            listOf(p1, p2).forEach { it.release() }
        }
        Core.merge(channelsOut, blended)
        val finalScaled = Mat()
        blended.convertTo(finalScaled, CvType.CV_8U)

        val fullRepair = Mat()
        Imgproc.resize(finalScaled, fullRepair, org.opencv.core.Size(src.width.toDouble(), src.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val fullMat = Mat()
        Utils.bitmapToMat(src, fullMat)
        val fullRgb = Mat()
        Imgproc.cvtColor(fullMat, fullRgb, Imgproc.COLOR_RGBA2RGB)

        // Create a high-res mask for the final cut
        val fullMask = Mat()
        Imgproc.resize(fgMask, fullMask, org.opencv.core.Size(src.width.toDouble(), src.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        // Paste only the repaired area onto the original high-res image
        fullRepair.copyTo(fullRgb, fullMask)

        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        Imgproc.cvtColor(fullRgb, rgba, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(rgba, result)

        // Cleanup
        listOf(mat, rgb, hsv, colorMask, regionMask, fgMask, kernel, inpaintedScaled, alphaMask, fRgb, fInp, ones, invAlpha, blended, finalScaled, fullRepair, fullMat, fullRgb, fullMask, rgba).forEach { it.release() }
        channelsSrc.forEach { it.release() }; channelsInp.forEach { it.release() }; channelsOut.forEach { it.release() }
        scaledSrc.recycle()

        return result
    }

    fun onCropRequestHandled() {
        _uiState.value = _uiState.value.copy(pendingCropRequest = null)
    }

    fun onCropResult(uri: Uri) {
        lastRectRegion = null
        val preCropBitmap = _uiState.value.correctedBitmap
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
                if (preCropBitmap != null) undoStack.addLast(preCropBitmap)
                _uiState.value = _uiState.value.copy(
                    correctedBitmap = bitmap,
                    isProcessing    = false,
                    canUndo         = undoStack.isNotEmpty()
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
     * returns true when stroke is horizontal & wide enough to be arrow gesture
     */
    private fun isLikelyArrow(points: List<Offset>): Boolean {
        if (points.size < 4) return false
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val width  = maxX - minX
        val height = maxY - minY
        return width > 80f && width > height * 2f
    }

    /**
     * determines blur/sharpen direction & strength from stroke
     * dir: endX > startX = right, otherwise left
     * strength: euclidean distance/width
     */
    private fun handleArrowGesture(
        points: List<Offset>,
        containerSize: IntSize,
        bitmap: Bitmap
    ) {
        val startX = points.first().x
        val startY = points.first().y
        val endX   = points.last().x
        val endY   = points.last().y

        val direction = if (endX >= startX) "right" else "left"

        val dx       = endX - startX
        val dy       = endY - startY
        val arrowLen = sqrt(dx * dx + dy * dy)
        val strength = (arrowLen / containerSize.width).coerceIn(0f, 1f)

        Log.d("Gesture", "arrow dir=$direction strength=${"%.2f".format(strength)}")

        val currentBitmap = _uiState.value.correctedBitmap ?: return

        // use last rect region if still valid
        val region = lastRectRegion?.takeIf { r ->
            r.left   >= 0 && r.top    >= 0 &&
            r.right  <= currentBitmap.width &&
            r.bottom <= currentBitmap.height &&
            r.width() > 0 && r.height() > 0
        }

        launchBlurSharpen(direction, strength, region, currentBitmap)
    }

    private fun launchBlurSharpen(
        direction: String,
        strength: Float,
        region: android.graphics.Rect?,
        bitmap: Bitmap
    ) {
        _uiState.value = _uiState.value.copy(isErasing = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    if (direction == "left") blurRegion(bitmap, region, strength)
                    else                     sharpenRegion(bitmap, region, strength)
                }
                undoStack.addLast(bitmap)
                _uiState.value = _uiState.value.copy(
                    correctedBitmap = result,
                    isErasing       = false,
                    canUndo         = true
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isErasing = false,
                    error     = "Filter failed: ${e.message}"
                )
            }
        }
    }

    /**
     * applies gaussian blur (kernel size = 3 + strength x 20)
     */
    private fun blurRegion(src: Bitmap, region: android.graphics.Rect?, strength: Float): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(src, mat)

        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)

        val kRaw = (3 + strength * 20).toInt()
        val k    = if (kRaw % 2 == 0) kRaw + 1 else kRaw
        val kSz  = org.opencv.core.Size(k.toDouble(), k.toDouble())

        if (region != null) {
            val roi     = org.opencv.core.Rect(region.left, region.top, region.width(), region.height())
            val submat  = rgb.submat(roi)
            Imgproc.GaussianBlur(submat, submat, kSz, 0.0)
            submat.release()
        } else {
            Imgproc.GaussianBlur(rgb, rgb, kSz, 0.0)
        }

        val rgba   = Mat()
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)

        listOf(mat, rgb, rgba).forEach { it.release() }
        return result
    }

    /**
     * applies a variable-strength sharpening convolution to [region] (or the full image).
     *
     * kernel (brightness-preserving, sums to 1):
     *   [  0      -s      0  ]
     *   [ -s   1+4s      -s  ]
     *   [  0      -s      0  ]
     * s ranges from 0.1 (min) to 1.0 (max strength).
     */
    private fun sharpenRegion(src: Bitmap, region: android.graphics.Rect?, strength: Float): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(src, mat)

        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)

        val s      = (strength * 2f).coerceIn(0.1f, 2f)
        val kernel = Mat(3, 3, org.opencv.core.CvType.CV_32F)
        kernel.put(
            0, 0,
            0.0,            (-s).toDouble(),  0.0,
            (-s).toDouble(), (1.0 + 4.0 * s), (-s).toDouble(),
            0.0,            (-s).toDouble(),  0.0
        )

        if (region != null) {
            val roi    = org.opencv.core.Rect(region.left, region.top, region.width(), region.height())
            val submat = rgb.submat(roi)
            Imgproc.filter2D(submat, submat, -1, kernel)
            submat.release()
        } else {
            Imgproc.filter2D(rgb, rgb, -1, kernel)
        }

        val rgba   = Mat()
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)

        listOf(mat, rgb, rgba, kernel).forEach { it.release() }
        return result
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
