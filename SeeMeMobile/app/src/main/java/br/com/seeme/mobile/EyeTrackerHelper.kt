package br.com.seeme.mobile

import android.content.Context
import android.graphics.PointF
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.hypot

class EyeTrackerHelper(context: Context) {
    private val landmarker: FaceLandmarker
    private var neutralNose: PointF? = null

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.55f)
            .setMinFacePresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun detect(imageProxy: ImageProxy, timestampMs: Long): EyeFrame {
        val bitmap = imageProxy.toJpegBitmap(60)
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(mpImage, timestampMs)
        val face = result.faceLandmarks().firstOrNull() ?: return EyeFrame.NoFace

        val nose = face[1]
        if (neutralNose == null) {
            neutralNose = PointF(nose.x(), nose.y())
        }

        val neutral = neutralNose ?: PointF(nose.x(), nose.y())
        val offsetX = (nose.x() - neutral.x)
        val offsetY = (nose.y() - neutral.y)
        val leftEar = eyeAspectRatio(face, intArrayOf(159, 145, 33, 133))
        val rightEar = eyeAspectRatio(face, intArrayOf(386, 374, 362, 263))

        return EyeFrame.Face(
            offsetX = offsetX,
            offsetY = offsetY,
            leftEar = leftEar,
            rightEar = rightEar
        )
    }

    fun recalibrate() {
        neutralNose = null
    }

    fun close() {
        landmarker.close()
    }

    private fun eyeAspectRatio(landmarks: List<NormalizedLandmark>, points: IntArray): Float {
        val vertical = distance(landmarks[points[0]], landmarks[points[1]])
        val horizontal = distance(landmarks[points[2]], landmarks[points[3]])
        return vertical / (horizontal + 0.00001f)
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        return hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble()).toFloat()
    }
}

sealed class EyeFrame {
    data object NoFace : EyeFrame()
    data class Face(
        val offsetX: Float,
        val offsetY: Float,
        val leftEar: Float,
        val rightEar: Float
    ) : EyeFrame()
}
