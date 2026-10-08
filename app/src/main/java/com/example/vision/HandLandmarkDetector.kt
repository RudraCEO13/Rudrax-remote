package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.model.FingerExtensionState
import com.example.model.HandLandmarkPoint
import com.example.model.HandTrackingFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Real-time on-device Hand Landmark Detector backed by Google MediaPipe Tasks Vision
 * (`hand_landmarker.task` bundled in APK assets).
 *
 * Processes front-camera frames strictly in volatile memory without saving or uploading frames.
 */
class HandLandmarkDetector(
    private val context: Context,
    private val onFrameTracked: (HandTrackingFrame?) -> Unit,
    private val onDetectorError: (String) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var initializationError: String? = null
        private set

    init {
        initializeLandmarker()
    }

    private fun initializeLandmarker() {
        try {
            // Try CPU delegate first for maximum device & emulator stability and deterministic low latency
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.55f)
                .setMinHandPresenceConfidence(0.55f)
                .setMinTrackingConfidence(0.55f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::handleLivestreamResult)
                .setErrorListener { error: RuntimeException ->
                    Log.e(TAG, "MediaPipe HandLandmarker runtime error", error)
                    onDetectorError(error.message ?: "Hand tracking runtime error")
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
            initializationError = null
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize MediaPipe HandLandmarker", t)
            isInitialized = false
            val reason = "MediaPipe HandLandmarker failed to initialize (${t.javaClass.simpleName}: ${t.message ?: "Native vision library unavailable on this ABI"})."
            initializationError = reason
            onDetectorError(reason)
        }
    }

    /**
     * Analyzes a live CameraX [ImageProxy] in RGBA_8888 format.
     * Always closes [imageProxy] immediately after copying/transforming in volatile RAM.
     */
    fun analyzeFrame(imageProxy: ImageProxy, isFrontCameraMirrored: Boolean = true) {
        val detector = handLandmarker
        if (!isInitialized || detector == null) {
            imageProxy.close()
            return
        }

        val frameTimeMs = SystemClock.uptimeMillis()
        try {
            val rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (isFrontCameraMirrored) {
                    // Compensate for front-camera mirroring so moving hand right moves X toward 1.0
                    postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
                }
            }

            val orientedBitmap = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )

            val mpImage: MPImage = BitmapImageBuilder(orientedBitmap).build()
            detector.detectAsync(mpImage, frameTimeMs)
        } catch (t: Throwable) {
            try {
                imageProxy.close()
            } catch (_: Throwable) {
            }
            Log.e(TAG, "Frame analysis exception", t)
        }
    }

    private fun handleLivestreamResult(result: HandLandmarkerResult, input: MPImage) {
        val nowMs = SystemClock.uptimeMillis()
        val latencyMs = (nowMs - result.timestampMs()).coerceAtLeast(1L)

        val landmarksList = result.landmarks()
        if (landmarksList.isNullOrEmpty() || landmarksList[0].size < 21) {
            onFrameTracked(null)
            return
        }

        val rawHand = landmarksList[0]
        val handednessCategory = result.handednesses().firstOrNull()?.firstOrNull()
        val confidence = handednessCategory?.score() ?: 0.85f
        val handednessLabel = handednessCategory?.categoryName() ?: "Hand"

        val points = rawHand.mapIndexed { idx, lm ->
            HandLandmarkPoint(
                index = idx,
                name = LANDMARK_NAMES.getOrElse(idx) { "JOINT_$idx" },
                x = lm.x().coerceIn(0f, 1f),
                y = lm.y().coerceIn(0f, 1f),
                z = lm.z()
            )
        }

        val wrist = points[0]
        val thumbCmc = points[1]
        val thumbMcp = points[2]
        val thumbIp = points[3]
        val thumbTip = points[4]

        val indexMcp = points[5]
        val indexPip = points[6]
        val indexDip = points[7]
        val indexTip = points[8]

        val middleMcp = points[9]
        val middlePip = points[10]
        val middleTip = points[12]

        val ringMcp = points[13]
        val ringPip = points[14]
        val ringTip = points[16]

        val pinkyMcp = points[17]
        val pinkyPip = points[18]
        val pinkyTip = points[20]

        // Calculate Palm Center from Wrist(0) and MCP knuckles (5, 9, 13, 17)
        val palmCenterX = (wrist.x + indexMcp.x + middleMcp.x + ringMcp.x + pinkyMcp.x) / 5f
        val palmCenterY = (wrist.y + indexMcp.y + middleMcp.y + ringMcp.y + pinkyMcp.y) / 5f

        // Reference scale of the hand in camera frame (Wrist to Middle MCP distance)
        val palmScale = dist2D(wrist, middleMcp).coerceAtLeast(0.04f)

        // Finger extension evaluation using joint distances to wrist & PIP/MCP geometry
        val indexExtended = isFingerExtended(wrist, indexMcp, indexPip, indexTip) &&
            dist2D(indexTip, indexDip) > 0.005f
        val middleExtended = isFingerExtended(wrist, middleMcp, middlePip, middleTip)
        val ringExtended = isFingerExtended(wrist, ringMcp, ringPip, ringTip)
        val pinkyExtended = isFingerExtended(wrist, pinkyMcp, pinkyPip, pinkyTip)

        // Thumb extension: distance from thumb tip to pinky MCP relative to index MCP to pinky MCP
        val palmWidth = dist2D(indexMcp, pinkyMcp).coerceAtLeast(0.03f)
        val thumbExtended = dist2D(thumbTip, pinkyMcp) > (palmWidth * 1.25f) &&
            dist2D(thumbTip, thumbCmc) > dist2D(thumbIp, thumbCmc) * 1.12f

        val fingerStates = FingerExtensionState(
            thumbExtended = thumbExtended,
            indexExtended = indexExtended,
            middleExtended = middleExtended,
            ringExtended = ringExtended,
            pinkyExtended = pinkyExtended
        )

        // Palm openness [0f..1f] based on mean fingertip distance from palm center normalized by palmScale
        val avgTipToPalm = (
            distXY(thumbTip.x, thumbTip.y, palmCenterX, palmCenterY) +
                distXY(indexTip.x, indexTip.y, palmCenterX, palmCenterY) +
                distXY(middleTip.x, middleTip.y, palmCenterX, palmCenterY) +
                distXY(ringTip.x, ringTip.y, palmCenterX, palmCenterY) +
                distXY(pinkyTip.x, pinkyTip.y, palmCenterX, palmCenterY)
            ) / 5f
        val palmOpenness = ((avgTipToPalm / (palmScale * 1.75f)) - 0.35f)
            .div(0.65f)
            .coerceIn(0f, 1f)

        // Hand orientation angle in degrees: 0° = pointing straight up
        val dxOrientation = middleMcp.x - wrist.x
        val dyOrientation = wrist.y - middleMcp.y // Invert Y so upward is positive
        val handOrientationDegrees = Math.toDegrees(atan2(dxOrientation.toDouble(), dyOrientation.toDouble())).toFloat()

        // Pinch ratio: distance between Index Tip [8] and Thumb Tip [4] normalized by palmScale
        val rawPinchDist = dist3D(indexTip, thumbTip)
        val pinchRatio = (rawPinchDist / palmScale).coerceIn(0f, 2.5f)

        val frame = HandTrackingFrame(
            landmarks = points,
            wrist = wrist.x to wrist.y,
            palmCenter = palmCenterX to palmCenterY,
            indexTip = indexTip.x to indexTip.y,
            thumbTip = thumbTip.x to thumbTip.y,
            confidence = confidence,
            handedness = handednessLabel,
            fingerStates = fingerStates,
            palmOpenness = palmOpenness,
            handOrientationDegrees = handOrientationDegrees,
            pinchRatio = pinchRatio,
            inferenceLatencyMs = latencyMs,
            timestampMs = nowMs
        )

        onFrameTracked(frame)
    }

    private fun isFingerExtended(
        wrist: HandLandmarkPoint,
        mcp: HandLandmarkPoint,
        pip: HandLandmarkPoint,
        tip: HandLandmarkPoint
    ): Boolean {
        val tipToWrist = dist2D(tip, wrist)
        val pipToWrist = dist2D(pip, wrist)
        val mcpToWrist = dist2D(mcp, wrist)
        return tipToWrist > pipToWrist * 1.08f && tipToWrist > mcpToWrist * 1.25f
    }

    private fun dist2D(a: HandLandmarkPoint, b: HandLandmarkPoint): Float {
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun dist3D(a: HandLandmarkPoint, b: HandLandmarkPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = (a.z - b.z) * 0.5f
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun distXY(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x1 - x2, y1 - y2)
    }

    fun close() {
        try {
            handLandmarker?.close()
        } catch (_: Throwable) {
        }
        handLandmarker = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "HandLandmarkDetector"
        private const val MODEL_ASSET_PATH = "hand_landmarker.task"

        val LANDMARK_NAMES = listOf(
            "WRIST",
            "THUMB_CMC", "THUMB_MCP", "THUMB_IP", "THUMB_TIP",
            "INDEX_MCP", "INDEX_PIP", "INDEX_DIP", "INDEX_TIP",
            "MIDDLE_MCP", "MIDDLE_PIP", "MIDDLE_DIP", "MIDDLE_TIP",
            "RING_MCP", "RING_PIP", "RING_DIP", "RING_TIP",
            "PINKY_MCP", "PINKY_PIP", "PINKY_DIP", "PINKY_TIP"
        )

        // Bone connections for rendering the 21-point hand skeleton overlay
        val HAND_CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,       // Thumb
            0 to 5, 5 to 6, 6 to 7, 7 to 8,       // Index
            5 to 9, 9 to 10, 10 to 11, 11 to 12,  // Middle
            9 to 13, 13 to 14, 14 to 15, 15 to 16,// Ring
            13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20 // Pinky & Palm base
        )
    }
}
