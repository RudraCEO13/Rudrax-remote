package com.example.controller

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BluetoothTvHidController
import com.example.data.CalibrationPreferences
import com.example.gesture.GestureEngine
import com.example.model.AirMouseCommand
import com.example.model.CalibrationBounds
import com.example.model.CalibrationStep
import com.example.model.DiscoveredBluetoothDevice
import com.example.model.GestureEngineSnapshot
import com.example.model.GestureState
import com.example.model.HandTrackingFrame
import com.example.model.TvConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Complete UI & Hardware state for RudraX Air Mouse V1.
 * Every status indicator reflects the genuine state of the underlying hardware & vision layers.
 */
data class RudraXUiState(
    val hasCameraPermission: Boolean = false,
    val hasFrontCameraHardware: Boolean = true,
    val isCameraStreamActive: Boolean = false,
    val cameraErrorMessage: String? = null,
    val handTrackingFrame: HandTrackingFrame? = null,
    val gestureSnapshot: GestureEngineSnapshot = GestureEngineSnapshot(),
    val calibrationBounds: CalibrationBounds = CalibrationBounds(),
    val calibrationStep: CalibrationStep = CalibrationStep.INACTIVE,
    val sensitivity: Float = 1.35f,
    val isBluetoothHardwareSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasBluetoothPermissions: Boolean = false,
    val isScanningBluetooth: Boolean = false,
    val discoveredDevices: List<DiscoveredBluetoothDevice> = emptyList(),
    val tvConnectionState: TvConnectionState = TvConnectionState.DISCONNECTED,
    val connectedTvName: String? = null,
    val connectedTvAddress: String? = null,
    val isHidProfileReady: Boolean = false,
    val bluetoothErrorMessage: String? = null,
    val hidPacketsSentCount: Long = 0L,
    val isBluetoothSheetVisible: Boolean = false
) {
    /**
     * Air Mouse mode is ONLY active when:
     * 1. TV connection state is verified CONNECTED
     * 2. Camera stream is active
     * 3. Hand is actively detected & tracked with sufficient confidence
     */
    val isAirMouseActive: Boolean
        get() = tvConnectionState == TvConnectionState.CONNECTED &&
            isCameraStreamActive &&
            handTrackingFrame != null &&
            gestureSnapshot.gestureState != GestureState.HAND_LOST &&
            gestureSnapshot.gestureState != GestureState.IDLE &&
            gestureSnapshot.gestureState != GestureState.ERROR

    val isHandDetected: Boolean
        get() = handTrackingFrame != null &&
            gestureSnapshot.gestureState != GestureState.HAND_LOST &&
            gestureSnapshot.gestureState != GestureState.IDLE &&
            gestureSnapshot.gestureState != GestureState.ERROR
}

class RudraXAirMouseViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val calibrationPreferences = CalibrationPreferences(appContext)
    val gestureEngine = GestureEngine()
    val bluetoothController = BluetoothTvHidController(appContext)

    private val _hasCameraPermission = MutableStateFlow(checkCameraPermission())
    private val _hasFrontCameraHardware = MutableStateFlow(checkFrontCameraHardware())
    private val _isCameraStreamActive = MutableStateFlow(false)
    private val _cameraErrorMessage = MutableStateFlow<String?>(null)

    private val _handTrackingFrame = MutableStateFlow<HandTrackingFrame?>(null)
    private val _gestureSnapshot = MutableStateFlow(GestureEngineSnapshot())
    private val _calibrationBounds = MutableStateFlow(CalibrationBounds())
    private val _calibrationStep = MutableStateFlow(CalibrationStep.INACTIVE)
    private val _sensitivity = MutableStateFlow(1.35f)
    private val _isBluetoothSheetVisible = MutableStateFlow(false)

    // Input throttling timestamp (Section 15)
    private var lastHidMoveSentUptimeMs = 0L
    private val minHidMoveIntervalMs = 14L // ~70Hz max HID report rate

    val uiState: StateFlow<RudraXUiState> = combine(
        combine(
            _hasCameraPermission,
            _hasFrontCameraHardware,
            _isCameraStreamActive,
            _cameraErrorMessage,
            _handTrackingFrame
        ) { camPerm, frontHw, streamActive, camErr, frame ->
            CameraVisionSlice(camPerm, frontHw, streamActive, camErr, frame)
        },
        combine(
            _gestureSnapshot,
            _calibrationBounds,
            _calibrationStep,
            _sensitivity,
            _isBluetoothSheetVisible
        ) { gesture, bounds, step, sens, sheetVisible ->
            GestureCalibSlice(gesture, bounds, step, sens, sheetVisible)
        },
        combine(
            bluetoothController.isBluetoothHardwareSupported,
            bluetoothController.isBluetoothEnabled,
            bluetoothController.hasBluetoothPermissions,
            bluetoothController.isScanning,
            bluetoothController.discoveredDevices
        ) { btSupported, btEnabled, btPerm, scanning, devices ->
            BluetoothDiscoverySlice(btSupported, btEnabled, btPerm, scanning, devices)
        },
        combine(
            bluetoothController.connectionState,
            bluetoothController.connectedTvName,
            bluetoothController.connectedTvAddress,
            bluetoothController.isHidProfileReady,
            bluetoothController.errorMessage,
            bluetoothController.hidPacketsSentCount
        ) { arr ->
            BluetoothConnectionSlice(
                state = arr[0] as TvConnectionState,
                tvName = arr[1] as String?,
                tvAddress = arr[2] as String?,
                hidReady = arr[3] as Boolean,
                error = arr[4] as String?,
                packets = arr[5] as Long
            )
        }
    ) { camSlice, gestureSlice, btDiscSlice, btConnSlice ->
        RudraXUiState(
            hasCameraPermission = camSlice.hasCameraPermission,
            hasFrontCameraHardware = camSlice.hasFrontCameraHardware,
            isCameraStreamActive = camSlice.isCameraStreamActive,
            cameraErrorMessage = camSlice.cameraErrorMessage,
            handTrackingFrame = camSlice.handTrackingFrame,
            gestureSnapshot = gestureSlice.gestureSnapshot,
            calibrationBounds = gestureSlice.calibrationBounds,
            calibrationStep = gestureSlice.calibrationStep,
            sensitivity = gestureSlice.sensitivity,
            isBluetoothHardwareSupported = btDiscSlice.isSupported,
            isBluetoothEnabled = btDiscSlice.isEnabled,
            hasBluetoothPermissions = btDiscSlice.hasPermissions,
            isScanningBluetooth = btDiscSlice.isScanning,
            discoveredDevices = btDiscSlice.devices,
            tvConnectionState = btConnSlice.state,
            connectedTvName = btConnSlice.tvName,
            connectedTvAddress = btConnSlice.tvAddress,
            isHidProfileReady = btConnSlice.hidReady,
            bluetoothErrorMessage = btConnSlice.error,
            hidPacketsSentCount = btConnSlice.packets,
            isBluetoothSheetVisible = gestureSlice.isBluetoothSheetVisible
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RudraXUiState(
            hasCameraPermission = checkCameraPermission(),
            hasFrontCameraHardware = checkFrontCameraHardware()
        )
    )

    init {
        viewModelScope.launch {
            calibrationPreferences.calibrationBoundsFlow.collect { savedBounds ->
                gestureEngine.updateCalibrationBounds(savedBounds)
                _calibrationBounds.value = savedBounds
            }
        }
        viewModelScope.launch {
            calibrationPreferences.sensitivityFlow.collect { savedSensitivity ->
                gestureEngine.sensitivityMultiplier = savedSensitivity
                _sensitivity.value = savedSensitivity
            }
        }
    }

    fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkFrontCameraHardware(): Boolean {
        return appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _hasCameraPermission.value = granted
        if (!granted) {
            _isCameraStreamActive.value = false
            _cameraErrorMessage.value = "Camera permission is required for real-time front-camera hand tracking."
        } else {
            _cameraErrorMessage.value = null
        }
    }

    fun onBluetoothPermissionsResult() {
        bluetoothController.refreshPermissionsAndInitHid()
    }

    fun onCameraStreamStateChanged(active: Boolean, error: String? = null) {
        _isCameraStreamActive.value = active
        if (error != null) {
            _cameraErrorMessage.value = error
            _handTrackingFrame.value = null
            _gestureSnapshot.value = gestureEngine.reportDetectorError()
            bluetoothController.stopAllHidOutput()
        } else if (active) {
            _cameraErrorMessage.value = null
        }
    }

    fun onFrontCameraUnavailable() {
        _hasFrontCameraHardware.value = false
        _isCameraStreamActive.value = false
        _cameraErrorMessage.value = "Front-facing camera was not detected on this device."
        bluetoothController.stopAllHidOutput()
    }

    /**
     * Receives real-time landmark frames from [com.example.vision.HandLandmarkDetector],
     * passes them through [GestureEngine], and dispatches resulting [AirMouseCommand]s
     * to the Bluetooth HID layer ONLY when a verified TV connection is active.
     */
    fun onHandFrameTracked(frame: HandTrackingFrame?) {
        val nowMs = SystemClock.uptimeMillis()
        _handTrackingFrame.value = frame

        val snapshot = gestureEngine.processFrame(frame, nowMs)
        _gestureSnapshot.value = snapshot

        val currentCalibStep = gestureEngine.calibrationStep
        if (_calibrationStep.value != currentCalibStep) {
            _calibrationStep.value = currentCalibStep
            if (currentCalibStep == CalibrationStep.COMPLETE) {
                val updatedBounds = gestureEngine.calibrationBounds
                _calibrationBounds.value = updatedBounds
                viewModelScope.launch {
                    calibrationPreferences.saveCalibrationBounds(updatedBounds)
                }
            }
        }

        // Dispatch command to Bluetooth HID layer ONLY if TV is genuinely CONNECTED
        // and user is not in the middle of interactive calibration
        val isCalibrating = currentCalibStep != CalibrationStep.INACTIVE &&
            currentCalibStep != CalibrationStep.COMPLETE
        if (!isCalibrating) {
            dispatchAirMouseCommand(snapshot.command, nowMs)
        }
    }

    private fun dispatchAirMouseCommand(command: AirMouseCommand?, nowMs: Long) {
        if (command == null) return

        // Safety gate: If Bluetooth is not verified CONNECTED, never transmit commands
        if (bluetoothController.connectionState.value != TvConnectionState.CONNECTED) {
            return
        }

        when (command) {
            is AirMouseCommand.MoveCursor -> {
                if ((nowMs - lastHidMoveSentUptimeMs) >= minHidMoveIntervalMs) {
                    lastHidMoveSentUptimeMs = nowMs
                    bluetoothController.sendMouseReport(
                        buttonsMask = 0,
                        deltaX = command.hidDeltaX,
                        deltaY = command.hidDeltaY,
                        wheel = 0,
                        horizontalPan = 0
                    )
                }
            }

            is AirMouseCommand.LeftClick -> {
                bluetoothController.performLeftClick()
            }

            is AirMouseCommand.DoubleClick -> {
                bluetoothController.performDoubleClick()
            }

            is AirMouseCommand.VerticalScroll -> {
                bluetoothController.sendMouseReport(
                    buttonsMask = 0,
                    deltaX = 0,
                    deltaY = 0,
                    wheel = command.wheelDelta,
                    horizontalPan = 0
                )
            }

            is AirMouseCommand.HorizontalNavigate -> {
                bluetoothController.sendMouseReport(
                    buttonsMask = 0,
                    deltaX = 0,
                    deltaY = 0,
                    wheel = 0,
                    horizontalPan = command.panDelta
                )
            }

            is AirMouseCommand.StopInput -> {
                bluetoothController.stopAllHidOutput()
            }
        }
    }

    fun startCalibration() {
        gestureEngine.startCalibration()
        _calibrationStep.value = gestureEngine.calibrationStep
    }

    fun cancelOrFinishCalibration() {
        gestureEngine.cancelCalibration()
        _calibrationStep.value = CalibrationStep.INACTIVE
    }

    fun updateSensitivity(newSensitivity: Float) {
        val clamped = newSensitivity.coerceIn(0.5f, 2.5f)
        gestureEngine.sensitivityMultiplier = clamped
        _sensitivity.value = clamped
        viewModelScope.launch {
            calibrationPreferences.saveSensitivity(clamped)
        }
    }

    fun setBluetoothSheetVisible(visible: Boolean) {
        _isBluetoothSheetVisible.value = visible
        if (visible) {
            bluetoothController.refreshPermissionsAndInitHid()
            if (bluetoothController.isBluetoothEnabled.value &&
                bluetoothController.hasBluetoothPermissions.value
            ) {
                bluetoothController.startDeviceDiscovery()
            }
        } else {
            bluetoothController.stopDeviceDiscovery()
        }
    }

    fun startBluetoothScan() {
        bluetoothController.startDeviceDiscovery()
    }

    fun stopBluetoothScan() {
        bluetoothController.stopDeviceDiscovery()
    }

    fun selectAndConnectTv(device: DiscoveredBluetoothDevice) {
        bluetoothController.connectToTvDevice(device.address, isRetry = false)
    }

    fun retryTvConnection() {
        bluetoothController.retryLastConnection()
    }

    fun disconnectTv() {
        bluetoothController.disconnectCurrentTv()
    }

    fun refreshSystemStates() {
        _hasCameraPermission.value = checkCameraPermission()
        _hasFrontCameraHardware.value = checkFrontCameraHardware()
        bluetoothController.refreshPermissionsAndInitHid()
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothController.release()
    }

    private data class CameraVisionSlice(
        val hasCameraPermission: Boolean,
        val hasFrontCameraHardware: Boolean,
        val isCameraStreamActive: Boolean,
        val cameraErrorMessage: String?,
        val handTrackingFrame: HandTrackingFrame?
    )

    private data class GestureCalibSlice(
        val gestureSnapshot: GestureEngineSnapshot,
        val calibrationBounds: CalibrationBounds,
        val calibrationStep: CalibrationStep,
        val sensitivity: Float,
        val isBluetoothSheetVisible: Boolean
    )

    private data class BluetoothDiscoverySlice(
        val isSupported: Boolean,
        val isEnabled: Boolean,
        val hasPermissions: Boolean,
        val isScanning: Boolean,
        val devices: List<DiscoveredBluetoothDevice>
    )

    private data class BluetoothConnectionSlice(
        val state: TvConnectionState,
        val tvName: String?,
        val tvAddress: String?,
        val hidReady: Boolean,
        val error: String?,
        val packets: Long
    )
}
