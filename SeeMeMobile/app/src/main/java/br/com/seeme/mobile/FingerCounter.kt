package br.com.seeme.mobile

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

object FingerCounter {
    private val fingerTips = intArrayOf(8, 12, 16, 20)
    private val fingerPips = intArrayOf(6, 10, 14, 18)

    fun count(landmarks: List<NormalizedLandmark>, isRightHand: Boolean): Int {
        if (landmarks.size < 21) return 0

        var total = 0
        val thumbTip = landmarks[4]
        val thumbIp = landmarks[3]
        val thumbOpen = if (isRightHand) {
            thumbTip.x() < thumbIp.x()
        } else {
            thumbTip.x() > thumbIp.x()
        }
        if (thumbOpen) total++

        for (i in fingerTips.indices) {
            if (landmarks[fingerTips[i]].y() < landmarks[fingerPips[i]].y()) {
                total++
            }
        }

        return total.coerceIn(0, 5)
    }
}
