package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.controller.RudraXAirMouseViewModel
import com.example.ui.RudraXMainScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val airMouseViewModel: RudraXAirMouseViewModel = viewModel()
                val uiState by airMouseViewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                // Camera runtime permission launcher
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    airMouseViewModel.onCameraPermissionResult(isGranted)
                }

                // Bluetooth runtime permissions launcher (Android 12+ SCAN/CONNECT/ADVERTISE or legacy FINE_LOCATION)
                val bluetoothPermissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    airMouseViewModel.onBluetoothPermissionsResult()
                }

                // System Enable Bluetooth intent launcher
                val enableBluetoothLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    airMouseViewModel.onBluetoothPermissionsResult()
                }

                // Request front-camera permission automatically on first launch if not yet granted
                LaunchedEffect(Unit) {
                    if (!airMouseViewModel.checkCameraPermission()) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                // Refresh hardware and permission states whenever activity resumes
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        airMouseViewModel.refreshSystemStates()
                    }
                }

                RudraXMainScreen(
                    uiState = uiState,
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onRequestBluetoothPermissions = {
                        bluetoothPermissionsLauncher.launch(
                            airMouseViewModel.bluetoothController.requiredBluetoothPermissions()
                        )
                    },
                    onRequestEnableBluetooth = {
                        if (airMouseViewModel.bluetoothController.checkBluetoothPermissions()) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                        } else {
                            bluetoothPermissionsLauncher.launch(
                                airMouseViewModel.bluetoothController.requiredBluetoothPermissions()
                            )
                        }
                    },
                    onCameraStreamStateChanged = { active, error ->
                        airMouseViewModel.onCameraStreamStateChanged(active, error)
                    },
                    onFrontCameraUnavailable = {
                        airMouseViewModel.onFrontCameraUnavailable()
                    },
                    onHandFrameTracked = { frame ->
                        airMouseViewModel.onHandFrameTracked(frame)
                    },
                    onStartCalibration = {
                        airMouseViewModel.startCalibration()
                    },
                    onCancelOrFinishCalibration = {
                        airMouseViewModel.cancelOrFinishCalibration()
                    },
                    onOpenBluetoothSheet = {
                        airMouseViewModel.setBluetoothSheetVisible(true)
                    },
                    onCloseBluetoothSheet = {
                        airMouseViewModel.setBluetoothSheetVisible(false)
                    },
                    onStartBluetoothScan = {
                        airMouseViewModel.startBluetoothScan()
                    },
                    onStopBluetoothScan = {
                        airMouseViewModel.stopBluetoothScan()
                    },
                    onSelectTvDevice = { device ->
                        airMouseViewModel.selectAndConnectTv(device)
                    },
                    onRetryTvConnection = {
                        airMouseViewModel.retryTvConnection()
                    },
                    onDisconnectTv = {
                        airMouseViewModel.disconnectTv()
                    },
                    onUpdateSensitivity = { newGain ->
                        airMouseViewModel.updateSensitivity(newGain)
                    }
                )
            }
        }
    }
}
