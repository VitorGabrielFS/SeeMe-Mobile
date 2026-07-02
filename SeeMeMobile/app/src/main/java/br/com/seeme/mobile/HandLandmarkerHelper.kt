package br.com.seeme.mobile

import android.content.Context
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

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
        val bitmap = imageProxy.toJpegBitmap()
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
