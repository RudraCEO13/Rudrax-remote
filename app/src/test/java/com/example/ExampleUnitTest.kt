package com.example

import com.example.gesture.GestureEngine
import com.example.model.AirMouseCommand
import com.example.model.CalibrationBounds
import com.example.model.FingerExtensionState
import com.example.model.GestureState
import com.example.model.HandLandmarkPoint
import com.example.model.HandTrackingFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private fun buildFrame(
        indexX: Float = 0.5f,
        indexY: Float = 0.5f,
        palmX: Float = 0.5f,
        palmY: Float = 0.5f,
        confidence: Float = 0.92f,
        fingerStates: FingerExtensionState = FingerExtensionState(
            thumbExtended = false,
            indexExtended = true,
            middleExtended = false,
            ringExtended = false,
            pinkyExtended = false
        ),
        palmOpenness: Float = 0.35f,
        orientationDeg: Float = 0f,
        pinchRatio: Float = 0.85f,
        timestampMs: Long = 1000L
    ): HandTrackingFrame {
        val dummyLandmarks = (0..20).map { idx ->
            HandLandmarkPoint(idx, "LM_$idx", indexX, indexY, 0f)
        }
        return HandTrackingFrame(
            landmarks = dummyLandmarks,
            wrist = palmX to (palmY + 0.1f),
            palmCenter = palmX to palmY,
            indexTip = indexX to indexY,
            thumbTip = (indexX - 0.05f) to indexY,
            confidence = confidence,
            handedness = "Right",
            fingerStates = fingerStates,
            palmOpenness = palmOpenness,
            handOrientationDegrees = orientationDeg,
            pinchRatio = pinchRatio,
            inferenceLatencyMs = 12L,
            timestampMs = timestampMs
        )
    }

    @Test
    fun gestureEngine_tracksPointerAndStopsOnHandLost() {
        val engine = GestureEngine()
        // Frame 1: Anchor
        val s1 = engine.processFrame(buildFrame(indexX = 0.50f, indexY = 0.50f, timestampMs = 100L), 100L)
        assertEquals(GestureState.TRACKING, s1.gestureState)

        // Frame 2: Move index finger right
        val s2 = engine.processFrame(buildFrame(indexX = 0.65f, indexY = 0.50f, timestampMs = 130L), 130L)
        assertEquals(GestureState.POINTER, s2.gestureState)
        assertTrue(s2.command is AirMouseCommand.MoveCursor)

        // Frame 3: Hand disappears -> HAND_LOST and StopInput emitted once
        val s3 = engine.processFrame(null, 160L)
        assertEquals(GestureState.HAND_LOST, s3.gestureState)
        assertTrue(s3.command is AirMouseCommand.StopInput)
    }

    @Test
    fun gestureEngine_singleClickAndDoubleClickWithDebounce() {
        val engine = GestureEngine()
        engine.processFrame(buildFrame(timestampMs = 100L), 100L)

        // Pinch starts at t=400ms
        val sReady = engine.processFrame(buildFrame(pinchRatio = 0.25f, timestampMs = 400L), 400L)
        assertEquals(GestureState.CLICK_READY, sReady.gestureState)

        // Pinch held past min duration (35ms) at t=450ms -> single LeftClick
        val sClick1 = engine.processFrame(buildFrame(pinchRatio = 0.24f, timestampMs = 450L), 450L)
        assertEquals(GestureState.CLICK, sClick1.gestureState)
        assertTrue(sClick1.command is AirMouseCommand.LeftClick)

        // Release pinch at t=500ms
        engine.processFrame(buildFrame(pinchRatio = 0.75f, timestampMs = 500L), 500L)

        // Second pinch after cooldown (210ms) but within doubleClickWindow (460ms)
        engine.processFrame(buildFrame(pinchRatio = 0.25f, timestampMs = 730L), 730L)
        val sClick2 = engine.processFrame(buildFrame(pinchRatio = 0.24f, timestampMs = 775L), 775L)
        assertEquals(GestureState.DOUBLE_CLICK, sClick2.gestureState)
        assertTrue(sClick2.command is AirMouseCommand.DoubleClick)
    }

    @Test
    fun gestureEngine_openPalmScrollsVertically() {
        val engine = GestureEngine()
        val openPalmFingers = FingerExtensionState(true, true, true, true, true)

        engine.processFrame(buildFrame(palmY = 0.40f, fingerStates = openPalmFingers, palmOpenness = 0.85f, timestampMs = 100L), 100L)
        engine.processFrame(buildFrame(palmY = 0.41f, fingerStates = openPalmFingers, palmOpenness = 0.85f, timestampMs = 130L), 130L)
        engine.processFrame(buildFrame(palmY = 0.42f, fingerStates = openPalmFingers, palmOpenness = 0.85f, timestampMs = 160L), 160L)

        // Move open palm downward -> SCROLL mode with negative wheel delta (scroll down)
        val sScroll = engine.processFrame(buildFrame(palmY = 0.52f, fingerStates = openPalmFingers, palmOpenness = 0.85f, timestampMs = 200L), 200L)
        assertEquals(GestureState.SCROLL, sScroll.gestureState)
        assertNotNull(sScroll.command)
        val scrollCmd = sScroll.command as AirMouseCommand.VerticalScroll
        assertTrue(scrollCmd.wheelDelta < 0)
    }

    @Test
    fun calibrationBounds_normalizesCoordinatesCorrectly() {
        val bounds = CalibrationBounds(minX = 0.20f, maxX = 0.80f, minY = 0.25f, maxY = 0.75f, isCustomCalibrated = true)
        assertEquals(0.0f, bounds.normalizeX(0.20f), 0.001f)
        assertEquals(0.5f, bounds.normalizeX(0.50f), 0.001f)
        assertEquals(1.0f, bounds.normalizeX(0.80f), 0.001f)
    }
}
