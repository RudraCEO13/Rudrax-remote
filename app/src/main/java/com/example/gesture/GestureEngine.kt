package com.example.gesture

import com.example.model.AirMouseCommand
import com.example.model.CalibrationBounds
import com.example.model.CalibrationStep
import com.example.model.GestureEngineSnapshot
import com.example.model.GestureState
import com.example.model.HandTrackingFrame
import com.example.model.HorizontalDirection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Decoupled Gesture Engine & Input Mapper (Sections 4, 8, 9, 10, 11, 12, 13, 14, 15, 16).
 *
 * Converts raw 21-landmark [HandTrackingFrame] telemetry into stabilized TV coordinates
 * and mutually-exclusive [AirMouseCommand] actions with full tremor filtering, debounce,
 * release hysteresis, and lost-hand safety.
 */
class GestureEngine {

    // Configurable parameters
    var sensitivityMultiplier: Float = 1.35f
    var doubleClickWindowMs: Long = 460L
    var clickCooldownMs: Long = 210L
    var minPinchDurationMs: Long = 35L
    var minConfidenceThreshold: Float = 0.55f

    // Pinch hysteresis thresholds (normalized by palm scale)
    private val pinchReadyThreshold = 0.46f
    private val pinchEngageThreshold = 0.33f
    private val pinchReleaseThreshold = 0.44f

    // Pointer dead-zone & HID clamping
    private val pointerDeadZoneNormalized = 0.0024f
    private val maxHidDeltaPerFrame = 85

    // Smoothed state variables
    private var hasAnchor = false
    private var smoothedIndexX = 0.5f
    private var smoothedIndexY = 0.5f
    private var prevNormCursorX = 0.5f
    private var prevNormCursorY = 0.5f

    private var smoothedPalmX = 0.5f
    private var smoothedPalmY = 0.5f
    private var prevPalmX = 0.5f
    private var prevPalmY = 0.5f
    private var prevOrientationDeg = 0f

    private var smoothedVelX = 0f
    private var smoothedVelY = 0f
    private var smoothedScrollVelY = 0f
    private var lastFrameTimestampMs = 0L

    // Gesture state machine counters & timers
    private var currentState: GestureState = GestureState.IDLE
    private var openPalmConsecutiveFrames = 0
    private var pinchStartTimestampMs = 0L
    private var isPinchArmed = true
    private var isPinchCurrentlyDown = false
    private var lastClickDispatchedMs = 0L
    private var lastClickReleasedMs = 0L
    private var pendingDoubleClickFirstTapMs = 0L
    private var lastHorizontalNavMs = 0L
    private var lastScrollEmitMs = 0L

    private var lastBadgeLabel: String? = null
    private var lastBadgeTimeMs: Long = 0L

    // Calibration tracking
    var calibrationBounds: CalibrationBounds = CalibrationBounds()
        private set
    var calibrationStep: CalibrationStep = CalibrationStep.INACTIVE
        private set

    private var calibSampleCount = 0
    private var calibLeftMin = 0.30f
    private var calibRightMax = 0.70f
    private var calibUpMin = 0.30f
    private var calibDownMax = 0.70f

    fun updateCalibrationBounds(bounds: CalibrationBounds) {
        calibrationBounds = bounds
    }

    fun startCalibration() {
        calibrationStep = CalibrationStep.RAISE_INDEX
        calibSampleCount = 0
        calibLeftMin = 0.45f
        calibRightMax = 0.55f
        calibUpMin = 0.45f
        calibDownMax = 0.55f
    }

    fun cancelCalibration() {
        calibrationStep = CalibrationStep.INACTIVE
        calibSampleCount = 0
    }

    fun reportDetectorError(): GestureEngineSnapshot {
        currentState = GestureState.ERROR
        hasAnchor = false
        return GestureEngineSnapshot(
            gestureState = GestureState.ERROR,
            normalizedCursorX = prevNormCursorX,
            normalizedCursorY = prevNormCursorY,
            command = AirMouseCommand.StopInput("Detector error")
        )
    }

    /**
     * Processes a single camera tracking result (or null when no hand is detected).
     */
    fun processFrame(frame: HandTrackingFrame?, nowMs: Long): GestureEngineSnapshot {
        // 1. Lost-hand / Low-confidence protection (Sections 9 & 15)
        if (frame == null || frame.confidence < minConfidenceThreshold) {
            val wasActive = currentState != GestureState.IDLE && currentState != GestureState.HAND_LOST
            if (currentState != GestureState.IDLE) {
                currentState = GestureState.HAND_LOST
            }
            hasAnchor = false
            openPalmConsecutiveFrames = 0
            pinchStartTimestampMs = 0L
            isPinchCurrentlyDown = false
            isPinchArmed = true
            smoothedVelX = 0f
            smoothedVelY = 0f
            smoothedScrollVelY = 0f

            return GestureEngineSnapshot(
                gestureState = currentState,
                normalizedCursorX = prevNormCursorX,
                normalizedCursorY = prevNormCursorY,
                smoothedVelocityX = 0f,
                smoothedVelocityY = 0f,
                movementDirectionLabel = "HAND NOT DETECTED",
                gestureStability = 0f,
                pinchRatio = 1f,
                isPinchEngaged = false,
                lastActionBadge = activeBadge(nowMs),
                lastActionTimestampMs = lastBadgeTimeMs,
                command = if (wasActive) AirMouseCommand.StopInput("Hand lost or confidence below threshold") else null
            )
        }

        val dtSeconds = if (lastFrameTimestampMs > 0L) {
            ((frame.timestampMs - lastFrameTimestampMs).coerceIn(8L, 120L)) / 1000f
        } else {
            1f / 30f
        }
        lastFrameTimestampMs = frame.timestampMs

        val rawIndexX = frame.indexTip.first
        val rawIndexY = frame.indexTip.second
        val rawPalmX = frame.palmCenter.first
        val rawPalmY = frame.palmCenter.second

        // First frame after hand appears: anchor coordinates without generating a jump delta
        if (!hasAnchor) {
            hasAnchor = true
            smoothedIndexX = rawIndexX
            smoothedIndexY = rawIndexY
            smoothedPalmX = rawPalmX
            smoothedPalmY = rawPalmY
            prevPalmX = rawPalmX
            prevPalmY = rawPalmY
            prevOrientationDeg = frame.handOrientationDegrees
            prevNormCursorX = calibrationBounds.normalizeX(rawIndexX)
            prevNormCursorY = calibrationBounds.normalizeY(rawIndexY)
            currentState = GestureState.TRACKING
            return GestureEngineSnapshot(
                gestureState = GestureState.TRACKING,
                normalizedCursorX = prevNormCursorX,
                normalizedCursorY = prevNormCursorY,
                gestureStability = frame.confidence,
                pinchRatio = frame.pinchRatio,
                lastActionBadge = activeBadge(nowMs),
                lastActionTimestampMs = lastBadgeTimeMs,
                command = null
            )
        }

        // Handle interactive calibration workflow if active (Section 16)
        if (calibrationStep != CalibrationStep.INACTIVE && calibrationStep != CalibrationStep.COMPLETE) {
            processCalibrationStep(frame, rawIndexX, rawIndexY)
        }

        // Velocity-adaptive smoothing filter (tremor rejection at low speed, responsive at high speed)
        val rawInstSpeed = hypot(rawIndexX - smoothedIndexX, rawIndexY - smoothedIndexY) / dtSeconds
        val adaptiveAlpha = (0.16f + (rawInstSpeed * 0.45f)).coerceIn(0.16f, 0.70f)

        // Freeze index cursor anchor while user is pinching in CLICK_READY so pinch doesn't pull cursor off target
        val isApproachingPinch = frame.pinchRatio < pinchReadyThreshold && frame.fingerStates.indexExtended
        if (!isApproachingPinch) {
            smoothedIndexX += adaptiveAlpha * (rawIndexX - smoothedIndexX)
            smoothedIndexY += adaptiveAlpha * (rawIndexY - smoothedIndexY)
        }

        smoothedPalmX += 0.28f * (rawPalmX - smoothedPalmX)
        smoothedPalmY += 0.28f * (rawPalmY - smoothedPalmY)

        // Normalize into TV screen coordinates [0..1]
        val normX = calibrationBounds.normalizeX(smoothedIndexX)
        val normY = calibrationBounds.normalizeY(smoothedIndexY)

        val normDeltaX = normX - prevNormCursorX
        val normDeltaY = normY - prevNormCursorY
        val palmDeltaX = smoothedPalmX - prevPalmX
        val palmDeltaY = smoothedPalmY - prevPalmY

        val instVelX = normDeltaX / dtSeconds
        val instVelY = normDeltaY / dtSeconds
        smoothedVelX = 0.35f * instVelX + 0.65f * smoothedVelX
        smoothedVelY = 0.35f * instVelY + 0.65f * smoothedVelY

        val orientationDelta = abs(frame.handOrientationDegrees - prevOrientationDeg)
        prevOrientationDeg = frame.handOrientationDegrees
        val stabilityScore = (frame.confidence * (1f - (orientationDelta / 90f).coerceIn(0f, 0.5f))).coerceIn(0f, 1f)

        val directionLabel = computeDirectionLabel(smoothedVelX, smoothedVelY)

        // Evaluate mutually-exclusive gesture states (Section 14)
        var emittedCommand: AirMouseCommand? = null

        // 1. Check Open-Palm Vertical Scroll Mode (Section 12)
        val isOpenPalmCandidate = frame.fingerStates.isFullOpenPalm &&
            frame.palmOpenness >= 0.66f &&
            frame.pinchRatio > 0.52f &&
            orientationDelta < 22f

        if (isOpenPalmCandidate) {
            openPalmConsecutiveFrames = (openPalmConsecutiveFrames + 1).coerceAtMost(20)
        } else {
            openPalmConsecutiveFrames = 0
        }

        if (openPalmConsecutiveFrames >= 3) {
            currentState = GestureState.SCROLL
            isPinchCurrentlyDown = false
            pinchStartTimestampMs = 0L

            // Vertical hand movement delta controls vertical TV scrolling
            // Hand moves DOWN (palmDeltaY > 0) -> TV content scrolls DOWN (negative wheel delta)
            // Hand moves UP (palmDeltaY < 0) -> TV content scrolls UP (positive wheel delta)
            val rawPalmVelY = palmDeltaY / dtSeconds
            smoothedScrollVelY = 0.40f * rawPalmVelY + 0.60f * smoothedScrollVelY

            if (abs(palmDeltaY) > 0.0035f && (nowMs - lastScrollEmitMs) >= 28L) {
                val speedFactor = (1f + abs(smoothedScrollVelY) * 1.8f).coerceIn(1f, 4.5f)
                // Invert sign so moving hand DOWN produces negative wheel step (scroll down)
                val rawWheel = (-palmDeltaY * 140f * speedFactor).roundToInt()
                val clampedWheel = rawWheel.coerceIn(-12, 12)
                if (clampedWheel != 0) {
                    lastScrollEmitMs = nowMs
                    val dirText = if (clampedWheel < 0) "SCROLL DOWN" else "SCROLL UP"
                    recordBadge(dirText, nowMs)
                    emittedCommand = AirMouseCommand.VerticalScroll(
                        wheelDelta = clampedWheel,
                        velocityY = smoothedScrollVelY
                    )
                }
            }

            prevPalmX = smoothedPalmX
            prevPalmY = smoothedPalmY
            prevNormCursorX = normX
            prevNormCursorY = normY

            return GestureEngineSnapshot(
                gestureState = GestureState.SCROLL,
                normalizedCursorX = prevNormCursorX,
                normalizedCursorY = prevNormCursorY,
                smoothedVelocityX = 0f,
                smoothedVelocityY = smoothedScrollVelY,
                movementDirectionLabel = if (smoothedScrollVelY > 0.08f) "SCROLL DOWN" else if (smoothedScrollVelY < -0.08f) "SCROLL UP" else "SCROLL READY",
                gestureStability = stabilityScore,
                pinchRatio = frame.pinchRatio,
                isPinchEngaged = false,
                lastActionBadge = activeBadge(nowMs),
                lastActionTimestampMs = lastBadgeTimeMs,
                command = emittedCommand
            )
        }

        // 2. Check Air Click & Double Click (Sections 10 & 11)
        // Release hysteresis check first
        if (!isPinchArmed && frame.pinchRatio >= pinchReleaseThreshold) {
            isPinchArmed = true
            isPinchCurrentlyDown = false
            pinchStartTimestampMs = 0L
            lastClickReleasedMs = nowMs
        }

        val canEvaluatePinch = frame.fingerStates.indexExtended || isPinchCurrentlyDown || frame.pinchRatio < pinchReadyThreshold
        if (canEvaluatePinch && frame.pinchRatio <= pinchEngageThreshold) {
            if (isPinchArmed) {
                if (pinchStartTimestampMs == 0L) {
                    pinchStartTimestampMs = nowMs
                    currentState = GestureState.CLICK_READY
                } else if ((nowMs - pinchStartTimestampMs) >= minPinchDurationMs &&
                    (nowMs - lastClickReleasedMs) >= clickCooldownMs &&
                    (nowMs - lastClickDispatchedMs) >= clickCooldownMs
                ) {
                    // Confirmed deliberate air tap!
                    isPinchArmed = false
                    isPinchCurrentlyDown = true
                    lastClickDispatchedMs = nowMs

                    val isDoubleClick = pendingDoubleClickFirstTapMs > 0L &&
                        (nowMs - pendingDoubleClickFirstTapMs) <= doubleClickWindowMs

                    if (isDoubleClick) {
                        pendingDoubleClickFirstTapMs = 0L
                        currentState = GestureState.DOUBLE_CLICK
                        recordBadge("DOUBLE CLICK", nowMs)
                        emittedCommand = AirMouseCommand.DoubleClick(nowMs)
                    } else {
                        pendingDoubleClickFirstTapMs = nowMs
                        currentState = GestureState.CLICK
                        recordBadge("LEFT CLICK", nowMs)
                        emittedCommand = AirMouseCommand.LeftClick(nowMs)
                    }
                }
            } else {
                // Holding pinch down after click fired -> stay stable and do not fire repeated clicks
                currentState = if (pendingDoubleClickFirstTapMs == 0L && (nowMs - lastClickDispatchedMs) < 180L) {
                    GestureState.DOUBLE_CLICK
                } else {
                    GestureState.CLICK
                }
            }

            prevPalmX = smoothedPalmX
            prevPalmY = smoothedPalmY
            return GestureEngineSnapshot(
                gestureState = currentState,
                normalizedCursorX = prevNormCursorX,
                normalizedCursorY = prevNormCursorY,
                smoothedVelocityX = 0f,
                smoothedVelocityY = 0f,
                movementDirectionLabel = "PINCH LOCKED",
                gestureStability = stabilityScore,
                pinchRatio = frame.pinchRatio,
                isPinchEngaged = true,
                lastActionBadge = activeBadge(nowMs),
                lastActionTimestampMs = lastBadgeTimeMs,
                command = emittedCommand
            )
        } else if (canEvaluatePinch && frame.pinchRatio < pinchReadyThreshold && isPinchArmed) {
            currentState = GestureState.CLICK_READY
            prevPalmX = smoothedPalmX
            prevPalmY = smoothedPalmY
            return GestureEngineSnapshot(
                gestureState = GestureState.CLICK_READY,
                normalizedCursorX = prevNormCursorX,
                normalizedCursorY = prevNormCursorY,
                smoothedVelocityX = 0f,
                smoothedVelocityY = 0f,
                movementDirectionLabel = "CLICK READY",
                gestureStability = stabilityScore,
                pinchRatio = frame.pinchRatio,
                isPinchEngaged = false,
                lastActionBadge = activeBadge(nowMs),
                lastActionTimestampMs = lastBadgeTimeMs,
                command = null
            )
        } else {
            pinchStartTimestampMs = 0L
        }

        // 3. Check Horizontal Navigation Mode (Section 13)
        // Triggered when hand is rotated horizontally (> 42 deg tilt) OR two-finger horizontal navigation posture
        // with clear horizontal movement velocity exceeding vertical velocity and cooldown protection
        val palmVelX = (smoothedPalmX - prevPalmX) / dtSeconds
        val palmVelY = (smoothedPalmY - prevPalmY) / dtSeconds
        val isHorizontalPosture = frame.fingerStates.isIndexAndMiddleOnly || abs(frame.handOrientationDegrees) >= 42f
        val isStrongHorizontalMotion = abs(palmVelX) > 0.38f && abs(palmVelX) > abs(palmVelY) * 2.1f

        if (isHorizontalPosture && isStrongHorizontalMotion && (nowMs - lastHorizontalNavMs) >= 320L) {
            lastHorizontalNavMs = nowMs
            currentState = GestureState.HORIZONTAL_NAVIGATION
            val direction = if (palmVelX < 0f) HorizontalDirection.LEFT else HorizontalDirection.RIGHT
            val panStep = (palmVelX * 18f).roundToInt().coerceIn(-10, 10).let {
                if (it == 0) (if (direction == HorizontalDirection.LEFT) -2 else 2) else it
            }
            recordBadge("NAV ${direction.label}", nowMs)
            emittedCommand = AirMouseCommand.HorizontalNavigate(
                direction = direction,
                panDelta = panStep,
                velocityX = palmVelX
            )

            prevPalmX = smoothedPalmX
            prevPalmY = smoothedPalmY
            prevNormCursorX = normX
            prevNormCursorY = normY

            return GestureEngineSnapshot(
                gestureState = GestureState.HORIZONTAL_NAVIGATION,
                normalizedCursorX = prevNormCursorX,
                normalizedCursorY = prevNormCursorY,
                smoothedVelocityX = palmVelX,
                smoothedVelocityY = palmVelY,
                movementDirectionLabel = "NAV ${direction.label}",
                gestureStability = stabilityScore,
                pinchRatio = frame.pinchRatio,
                isPinchEngaged = false,
                lastActionBadge = activeBadge(nowMs),
                lastActionTimestampMs = lastBadgeTimeMs,
                command = emittedCommand
            )
        }

        // 4. Index Finger Pointer Control (Sections 8 & 9)
        if (frame.fingerStates.indexExtended) {
            currentState = GestureState.POINTER
            val moveMagnitude = hypot(normDeltaX, normDeltaY)

            if (moveMagnitude >= pointerDeadZoneNormalized) {
                val velocityMag = hypot(smoothedVelX, smoothedVelY)
                // Controlled pointer acceleration curve
                val accelGain = (1.0f + (velocityMag.pow(0.85f) * 0.95f)).coerceIn(1.0f, 3.4f)
                val effectiveGain = 950f * sensitivityMultiplier * accelGain

                val rawDx = (normDeltaX * effectiveGain).roundToInt()
                val rawDy = (normDeltaY * effectiveGain).roundToInt()

                val clampedDx = rawDx.coerceIn(-maxHidDeltaPerFrame, maxHidDeltaPerFrame)
                val clampedDy = rawDy.coerceIn(-maxHidDeltaPerFrame, maxHidDeltaPerFrame)

                prevNormCursorX = normX
                prevNormCursorY = normY

                if (clampedDx != 0 || clampedDy != 0) {
                    emittedCommand = AirMouseCommand.MoveCursor(
                        normalizedTvX = normX,
                        normalizedTvY = normY,
                        hidDeltaX = clampedDx,
                        hidDeltaY = clampedDy,
                        velocityMagnitude = velocityMag
                    )
                }
            }
        } else {
            // Hand is visible and tracked, but index finger is curled and palm is closed -> neutral TRACKING state
            currentState = GestureState.TRACKING
        }

        prevPalmX = smoothedPalmX
        prevPalmY = smoothedPalmY

        return GestureEngineSnapshot(
            gestureState = currentState,
            normalizedCursorX = prevNormCursorX,
            normalizedCursorY = prevNormCursorY,
            smoothedVelocityX = smoothedVelX,
            smoothedVelocityY = smoothedVelY,
            movementDirectionLabel = directionLabel,
            gestureStability = stabilityScore,
            pinchRatio = frame.pinchRatio,
            isPinchEngaged = false,
            lastActionBadge = activeBadge(nowMs),
            lastActionTimestampMs = lastBadgeTimeMs,
            command = emittedCommand
        )
    }

    private fun processCalibrationStep(frame: HandTrackingFrame, rawX: Float, rawY: Float) {
        if (!frame.fingerStates.indexExtended) return

        when (calibrationStep) {
            CalibrationStep.RAISE_INDEX -> {
                if (rawX in 0.25f..0.75f && rawY in 0.20f..0.80f) {
                    calibSampleCount++
                    if (calibSampleCount >= 12) {
                        calibSampleCount = 0
                        calibrationStep = CalibrationStep.MOVE_LEFT
                    }
                }
            }
            CalibrationStep.MOVE_LEFT -> {
                calibLeftMin = minOf(calibLeftMin, rawX)
                if (rawX < 0.34f) {
                    calibSampleCount++
                    if (calibSampleCount >= 10) {
                        calibSampleCount = 0
                        calibrationStep = CalibrationStep.MOVE_RIGHT
                    }
                }
            }
            CalibrationStep.MOVE_RIGHT -> {
                calibRightMax = maxOf(calibRightMax, rawX)
                if (rawX > 0.66f) {
                    calibSampleCount++
                    if (calibSampleCount >= 10) {
                        calibSampleCount = 0
                        calibrationStep = CalibrationStep.MOVE_UP
                    }
                }
            }
            CalibrationStep.MOVE_UP -> {
                calibUpMin = minOf(calibUpMin, rawY)
                if (rawY < 0.34f) {
                    calibSampleCount++
                    if (calibSampleCount >= 10) {
                        calibSampleCount = 0
                        calibrationStep = CalibrationStep.MOVE_DOWN
                    }
                }
            }
            CalibrationStep.MOVE_DOWN -> {
                calibDownMax = maxOf(calibDownMax, rawY)
                if (rawY > 0.66f) {
                    calibSampleCount++
                    if (calibSampleCount >= 10) {
                        calibSampleCount = 0
                        calibrationBounds = CalibrationBounds(
                            minX = calibLeftMin.coerceIn(0.05f, 0.40f),
                            maxX = calibRightMax.coerceIn(0.60f, 0.95f),
                            minY = calibUpMin.coerceIn(0.05f, 0.40f),
                            maxY = calibDownMax.coerceIn(0.60f, 0.95f),
                            isCustomCalibrated = true
                        )
                        calibrationStep = CalibrationStep.COMPLETE
                    }
                }
            }
            else -> Unit
        }
    }

    private fun computeDirectionLabel(vx: Float, vy: Float): String {
        val speed = hypot(vx, vy)
        if (speed < 0.04f) return "STATIONARY"
        return if (abs(vx) > abs(vy) * 1.25f) {
            if (sign(vx) > 0) "RIGHT →" else "← LEFT"
        } else if (abs(vy) > abs(vx) * 1.25f) {
            if (sign(vy) > 0) "DOWN ↓" else "↑ UP"
        } else {
            val h = if (vx > 0) "R" else "L"
            val v = if (vy > 0) "D" else "U"
            "DIAG $v-$h"
        }
    }

    private fun recordBadge(label: String, nowMs: Long) {
        lastBadgeLabel = label
        lastBadgeTimeMs = nowMs
    }

    private fun activeBadge(nowMs: Long): String? {
        return if (lastBadgeLabel != null && (nowMs - lastBadgeTimeMs) <= 950L) {
            lastBadgeLabel
        } else {
            null
        }
    }
}
