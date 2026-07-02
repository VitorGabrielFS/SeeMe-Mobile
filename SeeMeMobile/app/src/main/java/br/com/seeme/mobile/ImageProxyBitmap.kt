package br.com.seeme.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

fun ImageProxy.toJpegBitmap(quality: Int = 70): Bitmap {
    val nv21 = yuv420ToNv21()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val stream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, stream)
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
            nv21[outputOffset++] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
            nv21[outputOffset++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
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
