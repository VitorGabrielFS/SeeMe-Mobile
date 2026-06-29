package br.com.seeme.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class HandLandmarkerHelper(context: Context) {
    private val landmarker: HandLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun detect(imageProxy: ImageProxy, timestampMs: Long): Detection {
        val bitmap = imageProxy.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(mpImage, timestampMs)
        val landmarks = result.landmarks().firstOrNull() ?: return Detection(0, false)
        val handedness = result.handedness().firstOrNull()?.firstOrNull()?.categoryName() ?: "Right"
        val fingers = FingerCounter.count(landmarks, handedness.equals("Right", ignoreCase = true))
        return Detection(fingers, true)
    }

    fun close() {
        landmarker.close()
    }

    data class Detection(val fingers: Int, val hasHand: Boolean)
}

private fun ImageProxy.toBitmap(): Bitmap {
    val nv21 = yuv420ToNv21()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val stream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, stream)
    val bytes = stream.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun ImageProxy.yuv420ToNv21(): ByteArray {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val ySize = width * height
    val nv21 = ByteArray(ySize + width * height / 2)

    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, nv21, 0, 1)

    var outputOffset = ySize
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vuOffset = row * vPlane.rowStride + col * vPlane.pixelStride
            val uuOffset = row * uPlane.rowStride + col * uPlane.pixelStride
            nv21[outputOffset++] = vBuffer.get(vuOffset)
            nv21[outputOffset++] = uBuffer.get(uuOffset)
        }
    }

    return nv21
}

private fun copyPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    output: ByteArray,
    offset: Int,
    outputPixelStride: Int
) {
    var outputOffset = offset
    for (row in 0 until height) {
        var inputOffset = row * rowStride
        for (col in 0 until width) {
            output[outputOffset] = buffer.get(inputOffset)
            outputOffset += outputPixelStride
            inputOffset += pixelStride
        }
    }
}
