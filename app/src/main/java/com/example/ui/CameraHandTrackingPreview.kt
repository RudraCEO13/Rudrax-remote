package com.example.ui

import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.CalibrationBounds
import com.example.model.CalibrationStep
import com.example.model.GestureEngineSnapshot
import com.example.model.GestureState
import com.example.model.HandTrackingFrame
import com.example.ui.theme.RudraXAmber
import com.example.ui.theme.RudraXAzure
import com.example.ui.theme.RudraXBlack
import com.example.ui.theme.RudraXBorder
import com.example.ui.theme.RudraXCrimson
import com.example.ui.theme.RudraXCyan
import com.example.ui.theme.RudraXEmerald
import com.example.ui.theme.RudraXSurface
import com.example.ui.theme.RudraXSurfaceElevated
import com.example.ui.theme.RudraXTextSecondary
import com.example.ui.theme.RudraXWhite
import com.example.vision.HandLandmarkDetector
import java.util.concurrent.Executors

@Composable
fun CameraHandTrackingViewport(
    hasCameraPermission: Boolean,
    hasFrontCameraHardware: Boolean,
    cameraErrorMessage: String?,
    handFrame: HandTrackingFrame?,
    gestureSnapshot: GestureEngineSnapshot,
    calibrationBounds: CalibrationBounds,
    calibrationStep: CalibrationStep,
    onRequestCameraPermission: () -> Unit,
    onCameraStreamStateChanged: (Boolean, String?) -> Unit,
    onFrontCameraUnavailable: () -> Unit,
    onHandFrameTracked: (HandTrackingFrame?) -> Unit,
    onStartCalibration: () -> Unit,
    onCancelOrFinishCalibration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RudraXSurface)
            .border(
                width = 1.5.dp,
                color = when {
                    !hasCameraPermission || cameraErrorMessage != null -> RudraXCrimson.copy(alpha = 0.65f)
                    handFrame != null -> RudraXCyan.copy(alpha = 0.85f)
                    else -> RudraXBorder
                },
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("camera_tracking_viewport")
    ) {
        if (!hasCameraPermission) {
            CameraPermissionPromptCard(
                onAllowCamera = onRequestCameraPermission,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!hasFrontCameraHardware) {
            CameraHardwareUnavailableCard(
                message = cameraErrorMessage ?: "Front-facing camera hardware not detected on this device.",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
            val detector = remember {
                HandLandmarkDetector(
                    context = context,
                    onFrameTracked = onHandFrameTracked,
                    onDetectorError = { err ->
                        onCameraStreamStateChanged(false, err)
                    }
                )
            }

            val previewView = remember {
                PreviewView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }

            DisposableEffect(lifecycleOwner, hasCameraPermission) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                val mainExecutor = ContextCompat.getMainExecutor(context)

                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        if (!hasFront) {
                            onFrontCameraUnavailable()
                            return@addListener
                        }

                        val preview = Preview.Builder()
                            .build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(640, 480))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                    detector.analyzeFrame(imageProxy, isFrontCameraMirrored = true)
                                }
                            }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageAnalysis
                        )

                        if (detector.isInitialized) {
                            onCameraStreamStateChanged(true, null)
                        } else {
                            onCameraStreamStateChanged(
                                false,
                                detector.initializationError ?: "Hand Landmarker failed to initialize."
                            )
                        }
                    } catch (t: Throwable) {
                        onCameraStreamStateChanged(
                            false,
                            "Unable to open front camera: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }
                }, mainExecutor)

                onDispose {
                    try {
                        cameraProviderFuture.get().unbindAll()
                    } catch (_: Throwable) {
                    }
                    detector.close()
                    analysisExecutor.shutdown()
                }
            }

            // Live Front Camera Feed
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // Real-Time 21-Landmark Skeleton & TV Coordinate Reticle Canvas
            HandTrackingCanvasOverlay(
                handFrame = handFrame,
                gestureSnapshot = gestureSnapshot,
                calibrationBounds = calibrationBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Top-Left & Top-Right Telemetry HUD Overlay
            ViewportTopTelemetryBar(
                handFrame = handFrame,
                gestureSnapshot = gestureSnapshot,
                calibrationBounds = calibrationBounds,
                onStartCalibration = onStartCalibration,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(10.dp)
            )

            // Bottom Coordinate & Velocity Telemetry Strip
            ViewportBottomCoordinateBar(
                handFrame = handFrame,
                gestureSnapshot = gestureSnapshot,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(10.dp)
            )

            // Action Burst Indicator (e.g., LEFT CLICK, DOUBLE CLICK, SCROLL, NAV)
            val activeBadge = gestureSnapshot.lastActionBadge
            if (activeBadge != null) {
                Surface(
                    color = RudraXBlack.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, RudraXCyan),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 80.dp)
                ) {
                    Text(
                        text = "⚡ $activeBadge",
                        style = MaterialTheme.typography.titleMedium,
                        color = RudraXCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                }
            }

            // Camera / Detector Error Banner if applicable
            if (cameraErrorMessage != null) {
                Surface(
                    color = RudraXBlack.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RudraXCrimson),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "Camera error",
                            tint = RudraXCrimson,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = cameraErrorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RudraXWhite,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Interactive 5-Step Calibration Overlay (Section 16)
            if (calibrationStep != CalibrationStep.INACTIVE) {
                CalibrationInteractiveOverlay(
                    step = calibrationStep,
                    handDetected = handFrame != null,
                    indexExtended = handFrame?.fingerStates?.indexExtended == true,
                    onClose = onCancelOrFinishCalibration,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HandTrackingCanvasOverlay(
    handFrame: HandTrackingFrame?,
    gestureSnapshot: GestureEngineSnapshot,
    calibrationBounds: CalibrationBounds,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 1. Draw Calibrated Active Zone Boundary
        val leftX = calibrationBounds.minX * w
        val rightX = calibrationBounds.maxX * w
        val topY = calibrationBounds.minY * h
        val bottomY = calibrationBounds.maxY * h
        val zoneWidth = (rightX - leftX).coerceAtLeast(10f)
        val zoneHeight = (bottomY - topY).coerceAtLeast(10f)

        drawRoundRect(
            color = RudraXCyan.copy(alpha = 0.28f),
            topLeft = Offset(leftX, topY),
            size = ComposeSize(zoneWidth, zoneHeight),
            cornerRadius = CornerRadius(16f, 16f),
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            )
        )

        // Subtle crosshair grid inside calibrated zone
        val midX = leftX + zoneWidth / 2f
        val midY = topY + zoneHeight / 2f
        drawLine(
            color = RudraXCyan.copy(alpha = 0.12f),
            start = Offset(midX, topY),
            end = Offset(midX, bottomY),
            strokeWidth = 1f
        )
        drawLine(
            color = RudraXCyan.copy(alpha = 0.12f),
            start = Offset(leftX, midY),
            end = Offset(rightX, midY),
            strokeWidth = 1f
        )

        if (handFrame == null || handFrame.landmarks.size < 21) return@Canvas

        val pts = handFrame.landmarks

        // 2. Draw all 21-landmark skeletal connections
        for ((startIdx, endIdx) in HandLandmarkDetector.HAND_CONNECTIONS) {
            val a = pts[startIdx]
            val b = pts[endIdx]
            val isIndexBone = startIdx in 5..8 && endIdx in 5..8
            val isThumbBone = startIdx in 1..4 && endIdx in 1..4
            val boneColor = when {
                isIndexBone -> RudraXCyan
                isThumbBone -> RudraXAzure
                else -> RudraXWhite.copy(alpha = 0.65f)
            }
            drawLine(
                color = boneColor,
                start = Offset(a.x * w, a.y * h),
                end = Offset(b.x * w, b.y * h),
                strokeWidth = if (isIndexBone) 5.5f else 3.2f,
                cap = StrokeCap.Round
            )
        }

        // 3. Draw Palm Center node
        val palmOffset = Offset(handFrame.palmCenter.first * w, handFrame.palmCenter.second * h)
        drawCircle(
            color = if (gestureSnapshot.gestureState == GestureState.SCROLL) RudraXEmerald else RudraXAzure,
            radius = if (gestureSnapshot.gestureState == GestureState.SCROLL) 14f else 8f,
            center = palmOffset
        )
        if (gestureSnapshot.gestureState == GestureState.SCROLL) {
            drawCircle(
                color = RudraXEmerald.copy(alpha = 0.45f),
                radius = 26f,
                center = palmOffset,
                style = Stroke(width = 3f)
            )
        }

        // 4. Draw all 21 landmark joints
        for (lm in pts) {
            val pos = Offset(lm.x * w, lm.y * h)
            val isIndexTip = lm.index == 8
            val isThumbTip = lm.index == 4
            val isWrist = lm.index == 0

            val jointColor = when {
                isIndexTip -> RudraXCyan
                isThumbTip -> if (gestureSnapshot.isPinchEngaged) RudraXEmerald else RudraXAmber
                isWrist -> RudraXAzure
                else -> RudraXWhite
            }
            val radius = when {
                isIndexTip -> 10f
                isThumbTip -> 8f
                isWrist -> 8f
                else -> 4.8f
            }
            drawCircle(color = jointColor, radius = radius, center = pos)
        }

        // 5. Draw Pinch Vector Line between Thumb Tip (4) and Index Tip (8)
        val thumbTipPos = Offset(pts[4].x * w, pts[4].y * h)
        val indexTipPos = Offset(pts[8].x * w, pts[8].y * h)
        if (gestureSnapshot.pinchRatio < 0.55f) {
            val pinchColor = if (gestureSnapshot.isPinchEngaged) RudraXEmerald else RudraXAmber
            drawLine(
                color = pinchColor,
                start = thumbTipPos,
                end = indexTipPos,
                strokeWidth = if (gestureSnapshot.isPinchEngaged) 6f else 3f
            )
        }

        // 6. Draw Stabilized Normalized TV Cursor Reticle (mapped into viewport)
        val cursorCanvasX = leftX + gestureSnapshot.normalizedCursorX * zoneWidth
        val cursorCanvasY = topY + gestureSnapshot.normalizedCursorY * zoneHeight
        val cursorCenter = Offset(cursorCanvasX, cursorCanvasY)
        val reticleColor = when (gestureSnapshot.gestureState) {
            GestureState.CLICK, GestureState.DOUBLE_CLICK -> RudraXEmerald
            GestureState.CLICK_READY -> RudraXAmber
            GestureState.SCROLL -> RudraXAzure
            else -> RudraXCyan
        }

        drawCircle(
            color = reticleColor.copy(alpha = 0.22f),
            radius = 24f,
            center = cursorCenter
        )
        drawCircle(
            color = reticleColor,
            radius = 16f,
            center = cursorCenter,
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = RudraXWhite,
            radius = 4f,
            center = cursorCenter
        )
    }
}

@Composable
private fun ViewportTopTelemetryBar(
    handFrame: HandTrackingFrame?,
    gestureSnapshot: GestureEngineSnapshot,
    calibrationBounds: CalibrationBounds,
    onStartCalibration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: State Machine & Hand Confidence Badge
        Surface(
            color = RudraXBlack.copy(alpha = 0.78f),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, RudraXBorder)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                val dotColor = when (gestureSnapshot.gestureState) {
                    GestureState.POINTER, GestureState.CLICK, GestureState.DOUBLE_CLICK,
                    GestureState.SCROLL, GestureState.HORIZONTAL_NAVIGATION -> RudraXEmerald
                    GestureState.TRACKING, GestureState.CLICK_READY -> RudraXCyan
                    GestureState.HAND_LOST, GestureState.ERROR -> RudraXCrimson
                    GestureState.IDLE -> RudraXAmber
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "STATE: ${gestureSnapshot.gestureState.displayLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = RudraXWhite
                )
                if (handFrame != null) {
                    Text(
                        text = " · ${(handFrame.confidence * 100).toInt()}% · ${handFrame.inferenceLatencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = RudraXCyan
                    )
                }
            }
        }

        // Right: Calibrate / Recalibrate Button
        Surface(
            onClick = onStartCalibration,
            color = RudraXBlack.copy(alpha = 0.80f),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (calibrationBounds.isCustomCalibrated) RudraXCyan.copy(alpha = 0.7f) else RudraXBorder
            ),
            modifier = Modifier.testTag("calibrate_hand_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Calibrate Hand Area",
                    tint = RudraXCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.size(5.dp))
                Text(
                    text = if (calibrationBounds.isCustomCalibrated) "RECALIBRATE" else "CALIBRATE",
                    style = MaterialTheme.typography.labelMedium,
                    color = RudraXWhite
                )
            }
        }
    }
}

@Composable
private fun ViewportBottomCoordinateBar(
    handFrame: HandTrackingFrame?,
    gestureSnapshot: GestureEngineSnapshot,
    modifier: Modifier = Modifier
) {
    Surface(
        color = RudraXBlack.copy(alpha = 0.80f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RudraXBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (handFrame != null) {
                    "TV X: ${"%.2f".format(gestureSnapshot.normalizedCursorX)}  Y: ${"%.2f".format(gestureSnapshot.normalizedCursorY)}"
                } else {
                    "TV X: --  Y: -- (NO HAND)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (handFrame != null) RudraXCyan else RudraXTextSecondary
            )

            Text(
                text = if (handFrame != null) {
                    "PALM: ${(handFrame.palmOpenness * 100).toInt()}% · PINCH: ${"%.2f".format(handFrame.pinchRatio)}"
                } else {
                    "HOLD HAND IN FRONT CAMERA"
                },
                style = MaterialTheme.typography.labelSmall,
                color = RudraXWhite
            )

            Text(
                text = gestureSnapshot.movementDirectionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = RudraXAmber
            )
        }
    }
}

@Composable
private fun CalibrationInteractiveOverlay(
    step: CalibrationStep,
    handDetected: Boolean,
    indexExtended: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = RudraXBlack.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, RudraXCyan),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HAND CALIBRATION · STEP ${step.stepIndex}/${step.totalSteps}",
                    style = MaterialTheme.typography.labelMedium,
                    color = RudraXCyan
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Calibration",
                        tint = RudraXTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { step.stepIndex.toFloat() / step.totalSteps.toFloat() },
                color = RudraXCyan,
                trackColor = RudraXSurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = step.instruction,
                style = MaterialTheme.typography.titleLarge,
                color = RudraXWhite,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = step.subInstruction,
                style = MaterialTheme.typography.bodyMedium,
                color = RudraXTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))
            val statusText = when {
                step == CalibrationStep.COMPLETE -> "✓ Tracking boundaries locked and active"
                !handDetected -> "✕ Hand not detected — raise your hand in front of camera"
                !indexExtended -> "☝ Extend your index finger clearly"
                else -> "● Tracking index fingertip..."
            }
            val statusColor = when {
                step == CalibrationStep.COMPLETE -> RudraXEmerald
                !handDetected -> RudraXCrimson
                !indexExtended -> RudraXAmber
                else -> RudraXCyan
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )

            if (step == CalibrationStep.COMPLETE) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RudraXCyan,
                        contentColor = RudraXBlack
                    ),
                    modifier = Modifier.testTag("finish_calibration_button")
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionPromptCard(
    onAllowCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CameraFront,
            contentDescription = "Front Camera Required",
            tint = RudraXCyan,
            modifier = Modifier.size(42.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "FRONT CAMERA PERMISSION REQUIRED",
            style = MaterialTheme.typography.titleMedium,
            color = RudraXWhite,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "RudraX Air Mouse uses the front-facing camera for real-time 21-landmark hand tracking. All camera frames are processed strictly in on-device volatile memory and never saved or uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = RudraXTextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onAllowCamera,
            colors = ButtonDefaults.buttonColors(
                containerColor = RudraXCyan,
                contentColor = RudraXBlack
            ),
            modifier = Modifier.testTag("allow_camera_button")
        ) {
            Text("Allow Camera", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraHardwareUnavailableCard(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = "Front Camera Unavailable",
            tint = RudraXCrimson,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "FRONT CAMERA UNAVAILABLE",
            style = MaterialTheme.typography.titleMedium,
            color = RudraXWhite,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = RudraXTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
