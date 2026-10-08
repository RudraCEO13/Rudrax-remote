package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.controller.RudraXUiState
import com.example.model.DiscoveredBluetoothDevice
import com.example.model.GestureState
import com.example.model.HandTrackingFrame
import com.example.model.TvConnectionState
import com.example.ui.theme.RudraXAmber
import com.example.ui.theme.RudraXAzure
import com.example.ui.theme.RudraXAzureContainer
import com.example.ui.theme.RudraXBlack
import com.example.ui.theme.RudraXBorder
import com.example.ui.theme.RudraXCrimson
import com.example.ui.theme.RudraXCyan
import com.example.ui.theme.RudraXEmerald
import com.example.ui.theme.RudraXSurface
import com.example.ui.theme.RudraXSurfaceContainer
import com.example.ui.theme.RudraXSurfaceElevated
import com.example.ui.theme.RudraXTextMuted
import com.example.ui.theme.RudraXTextSecondary
import com.example.ui.theme.RudraXWhite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RudraXMainScreen(
    uiState: RudraXUiState,
    onRequestCameraPermission: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onCameraStreamStateChanged: (Boolean, String?) -> Unit,
    onFrontCameraUnavailable: () -> Unit,
    onHandFrameTracked: (HandTrackingFrame?) -> Unit,
    onStartCalibration: () -> Unit,
    onCancelOrFinishCalibration: () -> Unit,
    onOpenBluetoothSheet: () -> Unit,
    onCloseBluetoothSheet: () -> Unit,
    onStartBluetoothScan: () -> Unit,
    onStopBluetoothScan: () -> Unit,
    onSelectTvDevice: (DiscoveredBluetoothDevice) -> Unit,
    onRetryTvConnection: () -> Unit,
    onDisconnectTv: () -> Unit,
    onUpdateSensitivity: (Float) -> Unit
) {
    var showTuningDialog by remember { mutableStateOf(false) }

    if (uiState.isBluetoothSheetVisible) {
        BackHandler { onCloseBluetoothSheet() }
    }

    Scaffold(
        containerColor = RudraXBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            RudraXBlack,
                            RudraXSurface,
                            RudraXBlack
                        )
                    )
                )
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. TOP HEADER: RUDRAX AIR MOUSE
                RudraXHeaderBar(
                    isTuningActive = showTuningDialog,
                    onToggleTuning = { showTuningDialog = !showTuningDialog },
                    onOpenBluetoothSheet = onOpenBluetoothSheet
                )

                // 2. TV IDENTIFICATION & VERIFIED CONNECTION BANNER (Sections 5, 6, 7)
                TvIdentificationBanner(
                    uiState = uiState,
                    onOpenBluetoothSheet = onOpenBluetoothSheet,
                    onRequestEnableBluetooth = onRequestEnableBluetooth,
                    onRequestBluetoothPermissions = onRequestBluetoothPermissions,
                    onRetryConnection = onRetryTvConnection,
                    onDisconnectTv = onDisconnectTv
                )

                // 3. REAL-TIME SYSTEM STATUS STRIP (Section 18)
                RealTimeSystemStatusStrip(uiState = uiState)

                // Optional Pointer Sensitivity & Architecture Drawer
                if (showTuningDialog) {
                    SensitivityAndArchitecturePanel(
                        sensitivity = uiState.sensitivity,
                        isHidProfileReady = uiState.isHidProfileReady,
                        hidPacketsSent = uiState.hidPacketsSentCount,
                        onUpdateSensitivity = onUpdateSensitivity,
                        onStartCalibration = onStartCalibration,
                        onClose = { showTuningDialog = false }
                    )
                }

                // 4. FRONT CAMERA & REAL-TIME 21-LANDMARK HAND TRACKING VIEWPORT (Sections 3, 4, 8, 16)
                CameraHandTrackingViewport(
                    hasCameraPermission = uiState.hasCameraPermission,
                    hasFrontCameraHardware = uiState.hasFrontCameraHardware,
                    cameraErrorMessage = uiState.cameraErrorMessage,
                    handFrame = uiState.handTrackingFrame,
                    gestureSnapshot = uiState.gestureSnapshot,
                    calibrationBounds = uiState.calibrationBounds,
                    calibrationStep = uiState.calibrationStep,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onCameraStreamStateChanged = onCameraStreamStateChanged,
                    onFrontCameraUnavailable = onFrontCameraUnavailable,
                    onHandFrameTracked = onHandFrameTracked,
                    onStartCalibration = onStartCalibration,
                    onCancelOrFinishCalibration = onCancelOrFinishCalibration,
                    modifier = Modifier.weight(1f)
                )

                // 5. BOTTOM AIR MOUSE STATUS & 4 GESTURE INDICATORS (Section 17)
                BottomAirMouseControlDock(
                    uiState = uiState,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                )
            }
        }

        // Bluetooth Device Discovery & TV Selection Sheet
        if (uiState.isBluetoothSheetVisible) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = onCloseBluetoothSheet,
                sheetState = sheetState,
                containerColor = RudraXSurface,
                contentColor = RudraXWhite
            ) {
                BluetoothTvDiscoverySheetContent(
                    uiState = uiState,
                    onRequestEnableBluetooth = onRequestEnableBluetooth,
                    onRequestBluetoothPermissions = onRequestBluetoothPermissions,
                    onStartScan = onStartBluetoothScan,
                    onStopScan = onStopBluetoothScan,
                    onSelectDevice = onSelectTvDevice,
                    onRetryConnection = onRetryTvConnection,
                    onDisconnectTv = onDisconnectTv,
                    onClose = onCloseBluetoothSheet
                )
            }
        }
    }
}

@Composable
private fun RudraXHeaderBar(
    isTuningActive: Boolean,
    onToggleTuning: () -> Unit,
    onOpenBluetoothSheet: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(RudraXCyan)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RUDRAX AIR MOUSE",
                    style = MaterialTheme.typography.headlineMedium,
                    color = RudraXWhite,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "FRONT-CAMERA 21-LANDMARK BLUETOOTH HID TV CONTROLLER",
                style = MaterialTheme.typography.labelSmall,
                color = RudraXCyan
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(
                onClick = onToggleTuning,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isTuningActive) RudraXAzureContainer else RudraXSurfaceElevated)
                    .testTag("toggle_tuning_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Pointer Sensitivity & Architecture",
                    tint = if (isTuningActive) RudraXCyan else RudraXWhite,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onOpenBluetoothSheet,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(RudraXSurfaceElevated)
                    .testTag("open_bluetooth_sheet_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = "Bluetooth TV Connection",
                    tint = RudraXCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Section 7: TV IDENTIFICATION UI
 * Shows the detected & verified TV at the top of the application.
 * Never shows CONNECTED unless BluetoothHidDevice connection + verification callback succeeded.
 */
@Composable
private fun TvIdentificationBanner(
    uiState: RudraXUiState,
    onOpenBluetoothSheet: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit,
    onRetryConnection: () -> Unit,
    onDisconnectTv: () -> Unit
) {
    val isConnected = uiState.tvConnectionState == TvConnectionState.CONNECTED &&
        !uiState.connectedTvName.isNullOrBlank()

    val borderColor = when (uiState.tvConnectionState) {
        TvConnectionState.CONNECTED -> RudraXEmerald
        TvConnectionState.CONNECTING, TvConnectionState.RECONNECTING -> RudraXAmber
        TvConnectionState.CONNECTION_ERROR -> RudraXCrimson
        TvConnectionState.DISCONNECTED -> RudraXBorder
    }

    Surface(
        color = RudraXSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tv_identification_banner")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "TV Icon",
                        tint = if (isConnected) RudraXEmerald else RudraXCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isConnected) "📺 TV CONNECTED" else "📺 CONNECTED TV",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isConnected) RudraXEmerald else RudraXTextSecondary
                        )
                        Text(
                            text = if (isConnected) {
                                "Connected TV: ${uiState.connectedTvName}"
                            } else {
                                "No Verified TV Connection"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = RudraXWhite,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isConnected && !uiState.connectedTvAddress.isNullOrBlank()) {
                            Text(
                                text = "HID Host MAC: ${uiState.connectedTvAddress} · Pkts: ${uiState.hidPacketsSentCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = RudraXCyan
                            )
                        }
                    }
                }

                // Connection State Pill
                val pillColor = when (uiState.tvConnectionState) {
                    TvConnectionState.CONNECTED -> RudraXEmerald
                    TvConnectionState.CONNECTING, TvConnectionState.RECONNECTING -> RudraXAmber
                    TvConnectionState.CONNECTION_ERROR -> RudraXCrimson
                    TvConnectionState.DISCONNECTED -> RudraXTextMuted
                }
                Surface(
                    color = pillColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, pillColor.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "● ${uiState.tvConnectionState.displayLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = pillColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // Show error message or recovery action when not connected
            if (!uiState.bluetoothErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.bluetoothErrorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.tvConnectionState == TvConnectionState.CONNECTION_ERROR) {
                        RudraXCrimson
                    } else {
                        RudraXAmber
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    !uiState.isBluetoothHardwareSupported -> {
                        OutlinedButton(
                            onClick = onOpenBluetoothSheet,
                            border = BorderStroke(1.dp, RudraXCrimson),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("bt_hardware_info_button")
                        ) {
                            Text("View Bluetooth Hardware Status", style = MaterialTheme.typography.labelMedium, color = RudraXWhite)
                        }
                    }

                    !uiState.hasBluetoothPermissions -> {
                        Button(
                            onClick = onRequestBluetoothPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = RudraXCyan, contentColor = RudraXBlack),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("allow_bluetooth_button")
                        ) {
                            Text("Allow Bluetooth Permissions", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    !uiState.isBluetoothEnabled -> {
                        Button(
                            onClick = onRequestEnableBluetooth,
                            colors = ButtonDefaults.buttonColors(containerColor = RudraXCyan, contentColor = RudraXBlack),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("enable_bluetooth_button")
                        ) {
                            Text("Enable Bluetooth", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    uiState.tvConnectionState == TvConnectionState.CONNECTION_ERROR -> {
                        Button(
                            onClick = onRetryConnection,
                            colors = ButtonDefaults.buttonColors(containerColor = RudraXCrimson, contentColor = RudraXWhite),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("retry_connection_button")
                        ) {
                            Text("Retry Connection", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onOpenBluetoothSheet,
                            border = BorderStroke(1.dp, RudraXCyan),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Select TV", style = MaterialTheme.typography.labelMedium, color = RudraXCyan)
                        }
                    }

                    uiState.tvConnectionState == TvConnectionState.CONNECTED -> {
                        OutlinedButton(
                            onClick = onOpenBluetoothSheet,
                            border = BorderStroke(1.dp, RudraXCyan.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Switch TV", style = MaterialTheme.typography.labelMedium, color = RudraXCyan)
                        }
                        OutlinedButton(
                            onClick = onDisconnectTv,
                            border = BorderStroke(1.dp, RudraXCrimson.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("disconnect_tv_button")
                        ) {
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium, color = RudraXCrimson)
                        }
                    }

                    else -> {
                        Button(
                            onClick = onOpenBluetoothSheet,
                            colors = ButtonDefaults.buttonColors(containerColor = RudraXCyan, contentColor = RudraXBlack),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("discover_tv_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothSearching,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.tvConnectionState == TvConnectionState.CONNECTING) {
                                    "Connecting to TV..."
                                } else {
                                    "Discover & Connect TV"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Section 18: REAL-TIME STATUS
 * Shows actual hardware & tracking states. Never displays fake green status indicators.
 */
@Composable
private fun RealTimeSystemStatusStrip(uiState: RudraXUiState) {
    Surface(
        color = RudraXSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, RudraXBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("realtime_status_strip")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Camera Status
            val cameraOk = uiState.hasCameraPermission && uiState.hasFrontCameraHardware && uiState.isCameraStreamActive
            val cameraLabel = when {
                !uiState.hasCameraPermission -> "✕ Permission Req"
                !uiState.hasFrontCameraHardware -> "✕ No Front Cam"
                uiState.isCameraStreamActive -> "● Active"
                else -> "✕ Inactive"
            }
            StatusChipItem(
                title = "Camera",
                value = cameraLabel,
                isGood = cameraOk
            )

            // 2. Hand Tracking Status
            val handOk = uiState.isHandDetected
            val handLabel = if (handOk) "● Tracking" else "✕ Not Detected"
            StatusChipItem(
                title = "Hand Tracking",
                value = handLabel,
                isGood = handOk
            )

            // 3. Bluetooth Status
            val btConnected = uiState.tvConnectionState == TvConnectionState.CONNECTED
            val btLabel = when {
                !uiState.isBluetoothHardwareSupported -> "✕ No BT Adapter"
                !uiState.hasBluetoothPermissions -> "✕ Permission Req"
                !uiState.isBluetoothEnabled -> "✕ Disabled"
                uiState.tvConnectionState == TvConnectionState.CONNECTED -> "● Connected"
                uiState.tvConnectionState == TvConnectionState.CONNECTING -> "● Connecting"
                uiState.tvConnectionState == TvConnectionState.RECONNECTING -> "● Reconnecting"
                uiState.tvConnectionState == TvConnectionState.CONNECTION_ERROR -> "✕ Conn Error"
                else -> "✕ Disconnected"
            }
            StatusChipItem(
                title = "Bluetooth",
                value = btLabel,
                isGood = btConnected
            )

            // 4. Air Mouse Status
            val airMouseActive = uiState.isAirMouseActive
            StatusChipItem(
                title = "Air Mouse",
                value = if (airMouseActive) "● Active" else "✕ Standby",
                isGood = airMouseActive
            )
        }
    }
}

@Composable
private fun StatusChipItem(
    title: String,
    value: String,
    isGood: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RudraXTextSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = if (isGood) RudraXEmerald else RudraXCrimson,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Section 17: Bottom Air Mouse status & 4 Gesture Indicators (Pointer, Click, Scroll, Navigation).
 */
@Composable
private fun BottomAirMouseControlDock(
    uiState: RudraXUiState,
    modifier: Modifier = Modifier
) {
    val gestureState = uiState.gestureSnapshot.gestureState
    val isAirMouseActive = uiState.isAirMouseActive

    Surface(
        color = RudraXSurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isAirMouseActive) RudraXCyan else RudraXBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_air_mouse_dock")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isAirMouseActive) RudraXEmerald else RudraXAmber)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAirMouseActive) {
                            "AIR MOUSE  ● ACTIVE"
                        } else if (uiState.tvConnectionState != TvConnectionState.CONNECTED) {
                            "AIR MOUSE  ○ STANDBY (CONNECT TV TO SEND HID)"
                        } else {
                            "AIR MOUSE  ○ STANDBY (SHOW HAND IN CAMERA)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isAirMouseActive) RudraXEmerald else RudraXWhite
                    )
                }

                Text(
                    text = gestureState.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = RudraXCyan
                )
            }

            // 4 Gesture Mode Indicators: ☝ Pointer | 🤏 Click | 🖐 Scroll | ↔ Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isPointer = gestureState == GestureState.POINTER
                val isClick = gestureState == GestureState.CLICK_READY ||
                    gestureState == GestureState.CLICK ||
                    gestureState == GestureState.DOUBLE_CLICK
                val isScroll = gestureState == GestureState.SCROLL
                val isNav = gestureState == GestureState.HORIZONTAL_NAVIGATION

                GestureIndicatorCard(
                    symbol = "☝",
                    label = "Pointer",
                    subLabel = "Index Finger",
                    isActive = isPointer,
                    modifier = Modifier.weight(1f)
                )
                GestureIndicatorCard(
                    symbol = "🤏",
                    label = if (gestureState == GestureState.DOUBLE_CLICK) "2x Click" else "Click",
                    subLabel = "Air Pinch",
                    isActive = isClick,
                    modifier = Modifier.weight(1f)
                )
                GestureIndicatorCard(
                    symbol = "🖐",
                    label = "Scroll",
                    subLabel = "Open Palm ↕",
                    isActive = isScroll,
                    modifier = Modifier.weight(1f)
                )
                GestureIndicatorCard(
                    symbol = "↔",
                    label = "Navigation",
                    subLabel = "Horiz Sweep",
                    isActive = isNav,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GestureIndicatorCard(
    symbol: String,
    label: String,
    subLabel: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) RudraXAzureContainer else RudraXSurface,
        label = "gestureBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) RudraXCyan else RudraXBorder,
        label = "gestureBorder"
    )

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$symbol $label",
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) RudraXCyan else RudraXWhite,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) RudraXWhite else RudraXTextMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SensitivityAndArchitecturePanel(
    sensitivity: Float,
    isHidProfileReady: Boolean,
    hidPacketsSent: Long,
    onUpdateSensitivity: (Float) -> Unit,
    onStartCalibration: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = RudraXSurfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, RudraXCyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AIR MOUSE SENSITIVITY & NATIVE PIPELINE",
                    style = MaterialTheme.typography.labelLarge,
                    color = RudraXCyan
                )
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Panel",
                        tint = RudraXTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Pointer Gain: ${"%.2f".format(sensitivity)}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = RudraXWhite
                )
                Slider(
                    value = sensitivity,
                    onValueChange = onUpdateSensitivity,
                    valueRange = 0.5f..2.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = RudraXCyan,
                        activeTrackColor = RudraXCyan,
                        inactiveTrackColor = RudraXBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
                OutlinedButton(
                    onClick = onStartCalibration,
                    border = BorderStroke(1.dp, RudraXCyan),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        tint = RudraXCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Calibrate", style = MaterialTheme.typography.labelSmall, color = RudraXCyan)
                }
            }

            Text(
                text = "PIPELINE: FRONT CAMERA → MEDIAPIPE 21-LANDMARK DETECTOR → GESTURE ENGINE → INPUT MAPPER → AIR MOUSE CONTROLLER → ANDROID BLUETOOTH HID (ID=1) → TV",
                style = MaterialTheme.typography.labelSmall,
                color = RudraXTextSecondary
            )
            Text(
                text = "HID Mouse Profile Registered: ${if (isHidProfileReady) "YES (SubClass=MOUSE)" else "WAITING FOR BLUETOOTH ADAPTER"} · Sent Packets: $hidPacketsSent",
                style = MaterialTheme.typography.labelSmall,
                color = if (isHidProfileReady) RudraXEmerald else RudraXAmber
            )
        }
    }
}

/**
 * Sections 5, 6, 21, 23: Real Bluetooth Device Discovery & TV Connection Bottom Sheet.
 */
@Composable
private fun BluetoothTvDiscoverySheetContent(
    uiState: RudraXUiState,
    onRequestEnableBluetooth: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (DiscoveredBluetoothDevice) -> Unit,
    onRetryConnection: () -> Unit,
    onDisconnectTv: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "BLUETOOTH TV CONNECTION (HID HOST)",
                    style = MaterialTheme.typography.titleLarge,
                    color = RudraXWhite
                )
                Text(
                    text = "Real Android Bluetooth Discovery & BluetoothHidDevice Mouse Profile",
                    style = MaterialTheme.typography.labelSmall,
                    color = RudraXCyan
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Bluetooth Sheet",
                    tint = RudraXTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Current Connection Status Card inside Sheet
        Surface(
            color = RudraXSurfaceElevated,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, RudraXBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STATUS: ${uiState.tvConnectionState.displayLabel}",
                        style = MaterialTheme.typography.labelLarge,
                        color = when (uiState.tvConnectionState) {
                            TvConnectionState.CONNECTED -> RudraXEmerald
                            TvConnectionState.CONNECTING, TvConnectionState.RECONNECTING -> RudraXAmber
                            TvConnectionState.CONNECTION_ERROR -> RudraXCrimson
                            TvConnectionState.DISCONNECTED -> RudraXTextSecondary
                        }
                    )
                    if (uiState.tvConnectionState == TvConnectionState.CONNECTING ||
                        uiState.tvConnectionState == TvConnectionState.RECONNECTING
                    ) {
                        CircularProgressIndicator(
                            color = RudraXAmber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (uiState.tvConnectionState == TvConnectionState.CONNECTED && !uiState.connectedTvName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Connected TV: ${uiState.connectedTvName} (${uiState.connectedTvAddress})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RudraXEmerald,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!uiState.bluetoothErrorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uiState.bluetoothErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = RudraXCrimson
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!uiState.hasBluetoothPermissions) {
                        Button(
                            onClick = onRequestBluetoothPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = RudraXCyan, contentColor = RudraXBlack)
                        ) {
                            Text("Allow Bluetooth", fontWeight = FontWeight.Bold)
                        }
                    } else if (!uiState.isBluetoothEnabled && uiState.isBluetoothHardwareSupported) {
                        Button(
                            onClick = onRequestEnableBluetooth,
                            colors = ButtonDefaults.buttonColors(containerColor = RudraXCyan, contentColor = RudraXBlack)
                        ) {
                            Text("Enable Bluetooth", fontWeight = FontWeight.Bold)
                        }
                    } else if (uiState.isBluetoothHardwareSupported) {
                        Button(
                            onClick = { if (uiState.isScanningBluetooth) onStopScan() else onStartScan() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.isScanningBluetooth) RudraXAmber else RudraXCyan,
                                contentColor = RudraXBlack
                            ),
                            modifier = Modifier.testTag("sheet_scan_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isScanningBluetooth) "Stop Scanning" else "Scan for TVs",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (uiState.tvConnectionState == TvConnectionState.CONNECTION_ERROR) {
                        OutlinedButton(
                            onClick = onRetryConnection,
                            border = BorderStroke(1.dp, RudraXCrimson)
                        ) {
                            Text("Retry Connection", color = RudraXCrimson)
                        }
                    }

                    if (uiState.tvConnectionState == TvConnectionState.CONNECTED) {
                        OutlinedButton(
                            onClick = onDisconnectTv,
                            border = BorderStroke(1.dp, RudraXCrimson)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LinkOff,
                                contentDescription = null,
                                tint = RudraXCrimson,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Disconnect", color = RudraXCrimson)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "DISCOVERED & PAIRED BLUETOOTH DEVICES (${uiState.discoveredDevices.size})",
            style = MaterialTheme.typography.labelMedium,
            color = RudraXCyan
        )
        Text(
            text = "Put your TV into Bluetooth Pairing / Accessories mode so it appears below. Tap your TV to establish and verify a real Bluetooth HID Mouse connection.",
            style = MaterialTheme.typography.bodySmall,
            color = RudraXTextSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.discoveredDevices.isEmpty()) {
            Surface(
                color = RudraXSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, RudraXBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (uiState.isBluetoothHardwareSupported) {
                            Icons.Default.BluetoothSearching
                        } else {
                            Icons.Default.BluetoothDisabled
                        },
                        contentDescription = null,
                        tint = RudraXTextSecondary,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (!uiState.isBluetoothHardwareSupported) {
                            "No Bluetooth Controller Detected on This Environment"
                        } else if (uiState.isScanningBluetooth) {
                            "Scanning for nearby Bluetooth TVs..."
                        } else {
                            "No Bluetooth TVs found yet"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = RudraXWhite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (!uiState.isBluetoothHardwareSupported) {
                            "Platform Reality Notice: This device/emulator does not attach a physical Bluetooth HCI controller. Install RudraX Air Mouse on a physical Android 9+ phone to discover and control your TV via Bluetooth HID."
                        } else {
                            "Make sure your TV is turned on and open Settings → Remotes & Accessories → Pair Accessory."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = RudraXTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.discoveredDevices,
                    key = { it.address }
                ) { device ->
                    val isCurrentlyConnected = uiState.tvConnectionState == TvConnectionState.CONNECTED &&
                        uiState.connectedTvAddress == device.address

                    Surface(
                        color = if (isCurrentlyConnected) RudraXAzureContainer else RudraXSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = when {
                                isCurrentlyConnected -> RudraXEmerald
                                device.isTvCandidate -> RudraXCyan.copy(alpha = 0.7f)
                                else -> RudraXBorder
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDevice(device) }
                            .testTag("bt_device_${device.address}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isCurrentlyConnected) {
                                        Icons.Default.BluetoothConnected
                                    } else if (device.isTvCandidate) {
                                        Icons.Default.Tv
                                    } else {
                                        Icons.Default.Bluetooth
                                    },
                                    contentDescription = null,
                                    tint = if (isCurrentlyConnected) {
                                        RudraXEmerald
                                    } else if (device.isTvCandidate) {
                                        RudraXCyan
                                    } else {
                                        RudraXTextSecondary
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = device.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = RudraXWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (device.isTvCandidate) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = RudraXCyan.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "TV",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = RudraXCyan,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${device.address} · ${device.deviceClassLabel}" +
                                            (if (device.isPaired) " · Paired" else " · Available") +
                                            (if (device.rssi != null) " · ${device.rssi} dBm" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RudraXTextSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = { onSelectDevice(device) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCurrentlyConnected) RudraXEmerald else RudraXCyan,
                                    contentColor = RudraXBlack
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isCurrentlyConnected) "Connected" else "Connect",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = RudraXBorder)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = RudraXCyan,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Privacy & Security: Camera frames are processed strictly in local volatile RAM. Bluetooth uses native Android BluetoothHidDevice (HID Mouse ID=1).",
                style = MaterialTheme.typography.labelSmall,
                color = RudraXTextSecondary
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
