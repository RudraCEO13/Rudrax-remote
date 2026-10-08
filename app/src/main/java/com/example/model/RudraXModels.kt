package com.example.model

/**
 * Represents a single normalized 3D hand landmark [0..20] in front-camera mirror-compensated space.
 * X in [0f, 1f] (0 = Left side of TV, 1 = Right side of TV)
 * Y in [0f, 1f] (0 = Top of TV, 1 = Bottom of TV)
 */
data class HandLandmarkPoint(
    val index: Int,
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * Extension state of each finger computed from joint geometry.
 */
data class FingerExtensionState(
    val thumbExtended: Boolean = false,
    val indexExtended: Boolean = false,
    val middleExtended: Boolean = false,
    val ringExtended: Boolean = false,
    val pinkyExtended: Boolean = false
) {
    val extendedCount: Int
        get() = listOf(thumbExtended, indexExtended, middleExtended, ringExtended, pinkyExtended).count { it }

    val isOnlyIndexExtended: Boolean
        get() = indexExtended && !middleExtended && !ringExtended && !pinkyExtended

    val isIndexAndMiddleOnly: Boolean
        get() = indexExtended && middleExtended && !ringExtended && !pinkyExtended

    val isFullOpenPalm: Boolean
        get() = indexExtended && middleExtended && ringExtended && pinkyExtended
}

/**
 * Real-time hand landmark tracking frame produced by the HandLandmarkDetector.
 */
data class HandTrackingFrame(
    val landmarks: List<HandLandmarkPoint>,
    val wrist: Pair<Float, Float>,
    val palmCenter: Pair<Float, Float>,
    val indexTip: Pair<Float, Float>,
    val thumbTip: Pair<Float, Float>,
    val confidence: Float,
    val handedness: String,
    val fingerStates: FingerExtensionState,
    val palmOpenness: Float,
    val handOrientationDegrees: Float,
    val pinchRatio: Float, // Normalized distance between index tip (8) and thumb tip (4) relative to palm size
    val inferenceLatencyMs: Long,
    val timestampMs: Long
)

/**
 * Strict Gesture State Machine states (Section 14).
 * Only one primary gesture mode controls the TV at a time.
 */
enum class GestureState(val displayLabel: String) {
    IDLE("IDLE"),
    TRACKING("TRACKING"),
    POINTER("POINTER"),
    CLICK_READY("CLICK_READY"),
    CLICK("CLICK"),
    DOUBLE_CLICK("DOUBLE_CLICK"),
    SCROLL("SCROLL"),
    HORIZONTAL_NAVIGATION("HORIZONTAL_NAVIGATION"),
    HAND_LOST("HAND_LOST"),
    ERROR("ERROR")
}

/**
 * Strict TV Bluetooth Connection States (Section 6).
 */
enum class TvConnectionState(val displayLabel: String) {
    DISCONNECTED("DISCONNECTED"),
    CONNECTING("CONNECTING"),
    CONNECTED("CONNECTED"),
    RECONNECTING("RECONNECTING"),
    CONNECTION_ERROR("CONNECTION ERROR")
}

/**
 * Real discovered or bonded Bluetooth device read from Android Bluetooth APIs.
 */
data class DiscoveredBluetoothDevice(
    val name: String,
    val address: String,
    val isPaired: Boolean,
    val isTvCandidate: Boolean,
    val deviceClassLabel: String,
    val rssi: Short? = null
)

/**
 * Horizontal navigation direction.
 */
enum class HorizontalDirection(val label: String) {
    LEFT("LEFT"),
    RIGHT("RIGHT")
}

/**
 * Calibration workflow steps (Section 16).
 */
enum class CalibrationStep(
    val stepIndex: Int,
    val totalSteps: Int,
    val instruction: String,
    val subInstruction: String
) {
    INACTIVE(0, 5, "Calibration Inactive", ""),
    RAISE_INDEX(
        1,
        5,
        "Raise your index finger.",
        "Hold your index finger clearly in front of the front camera until locked."
    ),
    MOVE_LEFT(
        2,
        5,
        "Move your finger to the left.",
        "Reach comfortably toward the left boundary of your natural control zone."
    ),
    MOVE_RIGHT(
        3,
        5,
        "Move your finger to the right.",
        "Reach comfortably toward the right boundary of your natural control zone."
    ),
    MOVE_UP(
        4,
        5,
        "Move your finger up.",
        "Reach comfortably toward the top boundary of your natural control zone."
    ),
    MOVE_DOWN(
        5,
        5,
        "Move your finger down.",
        "Reach comfortably toward the bottom boundary of your natural control zone."
    ),
    COMPLETE(5, 5, "Calibration Complete", "Usable tracking area mapped to TV 0..1 coordinates.")
}

/**
 * Calibrated active camera bounding box that maps to normalized TV [0..1] x [0..1] coordinates.
 */
data class CalibrationBounds(
    val minX: Float = 0.15f,
    val maxX: Float = 0.85f,
    val minY: Float = 0.15f,
    val maxY: Float = 0.85f,
    val isCustomCalibrated: Boolean = false
) {
    fun normalizeX(rawMirroredX: Float): Float {
        val span = (maxX - minX).coerceAtLeast(0.15f)
        return ((rawMirroredX - minX) / span).coerceIn(0f, 1f)
    }

    fun normalizeY(rawY: Float): Float {
        val span = (maxY - minY).coerceAtLeast(0.15f)
        return ((rawY - minY) / span).coerceIn(0f, 1f)
    }
}

/**
 * Decoupled Air Mouse commands emitted by InputMapper and consumed by AirMouseController -> Bluetooth HID Layer.
 */
sealed interface AirMouseCommand {
    data class MoveCursor(
        val normalizedTvX: Float,
        val normalizedTvY: Float,
        val hidDeltaX: Int,
        val hidDeltaY: Int,
        val velocityMagnitude: Float
    ) : AirMouseCommand

    data class LeftClick(val timestampMs: Long) : AirMouseCommand

    data class DoubleClick(val timestampMs: Long) : AirMouseCommand

    data class VerticalScroll(
        val wheelDelta: Int, // Positive = Scroll Up, Negative = Scroll Down
        val velocityY: Float
    ) : AirMouseCommand

    data class HorizontalNavigate(
        val direction: HorizontalDirection,
        val panDelta: Int,
        val velocityX: Float
    ) : AirMouseCommand

    data class StopInput(val reason: String) : AirMouseCommand
}

/**
 * Processed gesture telemetry snapshot for UI & AirMouseController.
 */
data class GestureEngineSnapshot(
    val gestureState: GestureState = GestureState.IDLE,
    val normalizedCursorX: Float = 0.5f,
    val normalizedCursorY: Float = 0.5f,
    val smoothedVelocityX: Float = 0f,
    val smoothedVelocityY: Float = 0f,
    val movementDirectionLabel: String = "STATIONARY",
    val gestureStability: Float = 0f,
    val pinchRatio: Float = 1f,
    val isPinchEngaged: Boolean = false,
    val lastActionBadge: String? = null,
    val lastActionTimestampMs: Long = 0L,
    val command: AirMouseCommand? = null
)
