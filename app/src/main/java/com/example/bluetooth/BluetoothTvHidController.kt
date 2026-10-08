package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.DiscoveredBluetoothDevice
import com.example.model.TvConnectionState
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real Android Bluetooth Discovery & Bluetooth HID Device Profile Controller (Sections 5, 6, 7, 15, 21, 23).
 *
 * Uses Android's native [BluetoothAdapter] for device discovery/bonding and [BluetoothHidDevice]
 * (API 28+) to register the phone as a real Bluetooth HID Mouse + Horizontal/Vertical Scroll controller
 * for Android TV / Smart TVs.
 *
 * Strictly enforces zero-fake-state policy: [TvConnectionState.CONNECTED] is ONLY set after the real
 * [BluetoothHidDevice.Callback.onConnectionStateChanged] confirms [BluetoothProfile.STATE_CONNECTED]
 * AND a real HID verification report (`sendReport`) succeeds.
 */
@SuppressLint("MissingPermission", "NewApi")
class BluetoothTvHidController(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hidExecutor = Executors.newSingleThreadExecutor()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDeviceProfile: BluetoothHidDevice? = null
    private var isHidAppRegistered: Boolean = false
    private var activeBluetoothDevice: BluetoothDevice? = null
    private var lastTargetAddress: String? = null
    private var connectionTimeoutJob: Job? = null

    private val discoveredDevicesMap = linkedMapOf<String, BluetoothDevice>()
    private val rssiMap = mutableMapOf<String, Short>()

    private val _isBluetoothHardwareSupported = MutableStateFlow(bluetoothAdapter != null)
    val isBluetoothHardwareSupported: StateFlow<Boolean> = _isBluetoothHardwareSupported.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _hasBluetoothPermissions = MutableStateFlow(checkBluetoothPermissions())
    val hasBluetoothPermissions: StateFlow<Boolean> = _hasBluetoothPermissions.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(TvConnectionState.DISCONNECTED)
    val connectionState: StateFlow<TvConnectionState> = _connectionState.asStateFlow()

    private val _connectedTvName = MutableStateFlow<String?>(null)
    val connectedTvName: StateFlow<String?> = _connectedTvName.asStateFlow()

    private val _connectedTvAddress = MutableStateFlow<String?>(null)
    val connectedTvAddress: StateFlow<String?> = _connectedTvAddress.asStateFlow()

    private val _isHidProfileReady = MutableStateFlow(false)
    val isHidProfileReady: StateFlow<Boolean> = _isHidProfileReady.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _hidPacketsSentCount = MutableStateFlow(0L)
    val hidPacketsSentCount: StateFlow<Long> = _hidPacketsSentCount.asStateFlow()

    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = state == BluetoothAdapter.STATE_ON
                    _isBluetoothEnabled.value = enabled
                    if (!enabled) {
                        handleBluetoothDisabled()
                    } else {
                        _errorMessage.value = null
                        refreshPermissionsAndInitHid()
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (device != null && !device.address.isNullOrBlank()) {
                        discoveredDevicesMap[device.address] = device
                        if (rssi != Short.MIN_VALUE) {
                            rssiMap[device.address] = rssi
                        }
                        publishDeviceList()
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    publishDeviceList()

                    if (device != null && device.address == lastTargetAddress) {
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                // Proceed to HID host connection now that pairing succeeded
                                initiateHidConnect(device)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                if (_connectionState.value == TvConnectionState.CONNECTING) {
                                    _connectionState.value = TvConnectionState.CONNECTION_ERROR
                                    _errorMessage.value = "Bluetooth pairing rejected or cancelled by TV (${resolveRealDeviceName(device)})."
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isHidAppRegistered = registered
            _isHidProfileReady.value = registered
            if (!registered) {
                Log.w(TAG, "BluetoothHidDevice app unregistered")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (device == null) return
            scope.launch {
                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionState.value = TvConnectionState.CONNECTING
                        _errorMessage.value = null
                    }

                    BluetoothProfile.STATE_CONNECTED -> {
                        // STEP 8: Perform real connection verification before showing CONNECTED
                        verifyAndActivateTvConnection(device)
                    }

                    BluetoothProfile.STATE_DISCONNECTING -> {
                        _connectionState.value = TvConnectionState.DISCONNECTED
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionTimeoutJob?.cancel()
                        val wasConnectedToThis = activeBluetoothDevice?.address == device.address
                        if (wasConnectedToThis) {
                            activeBluetoothDevice = null
                            _connectedTvName.value = null
                            _connectedTvAddress.value = null
                            _connectionState.value = TvConnectionState.DISCONNECTED
                            _errorMessage.value = "TV disconnected (${resolveRealDeviceName(device)}). All Air Mouse HID output stopped."
                        } else if (_connectionState.value == TvConnectionState.CONNECTING ||
                            _connectionState.value == TvConnectionState.RECONNECTING
                        ) {
                            _connectionState.value = TvConnectionState.CONNECTION_ERROR
                            _errorMessage.value = "Failed to establish HID connection with ${resolveRealDeviceName(device)}. Ensure the TV is discoverable and accepts Bluetooth input devices."
                        }
                    }
                }
            }
        }
    }

    private val profileServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDeviceProfile = proxy as? BluetoothHidDevice
                registerHidMouseApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDeviceProfile = null
                isHidAppRegistered = false
                _isHidProfileReady.value = false
                if (_connectionState.value == TvConnectionState.CONNECTED) {
                    _connectionState.value = TvConnectionState.DISCONNECTED
                    _connectedTvName.value = null
                    _connectedTvAddress.value = null
                    activeBluetoothDevice = null
                    _errorMessage.value = "Bluetooth HID service disconnected."
                }
            }
        }
    }

    init {
        registerBroadcastReceivers()
        refreshPermissionsAndInitHid()
    }

    fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun refreshPermissionsAndInitHid() {
        _isBluetoothHardwareSupported.value = bluetoothAdapter != null
        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        val granted = checkBluetoothPermissions()
        _hasBluetoothPermissions.value = granted

        if (bluetoothAdapter == null) {
            _errorMessage.value = "Bluetooth hardware adapter is unavailable on this device/emulator."
            return
        }

        if (!_isBluetoothEnabled.value) {
            _errorMessage.value = "Bluetooth is disabled. Enable Bluetooth to discover and control your TV."
            return
        }

        if (!granted) {
            _errorMessage.value = "Bluetooth permissions are required to discover and connect to your TV."
            return
        }

        _errorMessage.value = null
        loadBondedDevices()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDeviceProfile == null) {
            try {
                bluetoothAdapter.getProfileProxy(context, profileServiceListener, BluetoothProfile.HID_DEVICE)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to acquire BluetoothProfile.HID_DEVICE proxy", t)
                _errorMessage.value = "Bluetooth HID Device profile is not supported by this Android build: ${t.message}"
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _errorMessage.value = "Android 9.0 (API 28)+ is required for native Bluetooth HID Device profile."
        }
    }

    private fun registerHidMouseApp() {
        val hid = hidDeviceProfile ?: return
        if (!checkBluetoothPermissions()) return

        try {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                "RudraX Air Mouse",
                "RudraX Real-Time Hand Tracking TV Air Mouse",
                "RudraX",
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                HID_MOUSE_AND_SCROLL_REPORT_DESCRIPTOR
            )

            val qosOut = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800,
                9,
                0,
                11250,
                BluetoothHidDeviceAppQosSettings.MAX
            )

            hid.registerApp(sdpSettings, null, qosOut, hidExecutor, hidCallback)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register HID Mouse SDP descriptor", t)
            _errorMessage.value = "Unable to register Bluetooth HID peripheral profile: ${t.message}"
        }
    }

    fun startDeviceDiscovery() {
        refreshPermissionsAndInitHid()
        val adapter = bluetoothAdapter ?: return
        if (!_isBluetoothEnabled.value || !_hasBluetoothPermissions.value) return

        try {
            loadBondedDevices()
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val started = adapter.startDiscovery()
            _isScanning.value = started
            if (!started) {
                _errorMessage.value = "Bluetooth discovery could not start. Make sure Location/Bluetooth services are enabled and TV is in pairing mode."
            } else {
                _errorMessage.value = null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting Bluetooth discovery", t)
            _errorMessage.value = "Bluetooth scan error: ${t.message}"
        }
    }

    fun stopDeviceDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (!_hasBluetoothPermissions.value) return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            _isScanning.value = false
        } catch (_: Throwable) {
        }
    }

    private fun loadBondedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (!checkBluetoothPermissions()) return
        try {
            val bonded = adapter.bondedDevices ?: emptySet()
            for (device in bonded) {
                if (!device.address.isNullOrBlank()) {
                    discoveredDevicesMap[device.address] = device
                }
            }
            publishDeviceList()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed reading bonded devices", t)
        }
    }

    /**
     * Connects to the selected Bluetooth device (Sections 5, 6, 7).
     * Never fakes connection success.
     */
    fun connectToTvDevice(address: String, isRetry: Boolean = false) {
        if (!checkBluetoothPermissions()) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Bluetooth Connect permission required."
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Bluetooth is turned off. Please enable Bluetooth first."
            return
        }

        stopDeviceDiscovery()

        val device = discoveredDevicesMap[address] ?: try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }

        if (device == null) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Target TV device ($address) not found. Make TV discoverable and scan again."
            return
        }

        lastTargetAddress = address
        _errorMessage.value = null
        _connectionState.value = if (isRetry) TvConnectionState.RECONNECTING else TvConnectionState.CONNECTING

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Unsupported Bluetooth profile: Android 9 (API 28+) required for Bluetooth HID Mouse."
            return
        }

        if (hidDeviceProfile == null || !isHidAppRegistered) {
            registerHidMouseApp()
        }

        // Start a 16-second connection verification timeout so UI never hangs indefinitely
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(16_000L)
            if (_connectionState.value == TvConnectionState.CONNECTING ||
                _connectionState.value == TvConnectionState.RECONNECTING
            ) {
                _connectionState.value = TvConnectionState.CONNECTION_ERROR
                _errorMessage.value = "Connection timed out for ${resolveRealDeviceName(device)}. Make sure your TV is awake, in Bluetooth Pairing Mode, and accepts HID input."
            }
        }

        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                val bondInitiated = device.createBond()
                if (!bondInitiated) {
                    initiateHidConnect(device)
                }
            } else {
                initiateHidConnect(device)
            }
        } catch (t: Throwable) {
            connectionTimeoutJob?.cancel()
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Connection error with ${resolveRealDeviceName(device)}: ${t.message}"
        }
    }

    private fun initiateHidConnect(device: BluetoothDevice) {
        val hid = hidDeviceProfile
        if (hid == null || !isHidAppRegistered) {
            connectionTimeoutJob?.cancel()
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Bluetooth HID profile is not registered on this device. Please ensure Bluetooth is enabled and retry."
            return
        }

        val initiated = hid.connect(device)
        if (!initiated) {
            connectionTimeoutJob?.cancel()
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Bluetooth HID connect() rejected for ${resolveRealDeviceName(device)}. Verify TV supports Bluetooth HID Mouse profile."
        }
    }

    /**
     * STEP 8: Real Connection Verification (Section 6).
     * Confirms actual BluetoothProfile.STATE_CONNECTED state and sends a verification zero-delta HID packet.
     */
    private suspend fun verifyAndActivateTvConnection(device: BluetoothDevice) {
        connectionTimeoutJob?.cancel()
        val hid = hidDeviceProfile
        if (hid == null) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "HID profile lost during verification."
            return
        }

        // Small settle delay for L2CAP HID interrupt channel establishment
        delay(150L)

        val actualProfileState = hid.getConnectionState(device)
        if (actualProfileState != BluetoothProfile.STATE_CONNECTED) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "Connection verification failed: TV HID channel did not remain connected."
            return
        }

        // Send a real 5-byte zero-op HID verification report [buttons=0, dx=0, dy=0, wheel=0, pan=0]
        val verificationReport = byteArrayOf(0, 0, 0, 0, 0)
        val reportAccepted = try {
            hid.sendReport(device, REPORT_ID_MOUSE, verificationReport)
        } catch (t: Throwable) {
            false
        }

        if (!reportAccepted) {
            _connectionState.value = TvConnectionState.CONNECTION_ERROR
            _errorMessage.value = "TV connected over Bluetooth, but rejected the HID Mouse verification report."
            return
        }

        val realName = resolveRealDeviceName(device)
        activeBluetoothDevice = device
        lastTargetAddress = device.address
        _connectedTvName.value = realName
        _connectedTvAddress.value = device.address
        _errorMessage.value = null
        _connectionState.value = TvConnectionState.CONNECTED
    }

    fun retryLastConnection() {
        val target = lastTargetAddress ?: activeBluetoothDevice?.address
        if (target != null) {
            connectToTvDevice(target, isRetry = true)
        } else {
            startDeviceDiscovery()
        }
    }

    fun disconnectCurrentTv() {
        connectionTimeoutJob?.cancel()
        val hid = hidDeviceProfile
        val device = activeBluetoothDevice
        if (hid != null && device != null && checkBluetoothPermissions()) {
            try {
                // Send zero button/movement release report before disconnecting
                hid.sendReport(device, REPORT_ID_MOUSE, byteArrayOf(0, 0, 0, 0, 0))
                hid.disconnect(device)
            } catch (_: Throwable) {
            }
        }
        activeBluetoothDevice = null
        _connectedTvName.value = null
        _connectedTvAddress.value = null
        _connectionState.value = TvConnectionState.DISCONNECTED
    }

    /**
     * Transmits a real 5-byte relative HID Mouse report to the verified TV.
     * Immediately refuses transmission if not in [TvConnectionState.CONNECTED].
     */
    fun sendMouseReport(
        buttonsMask: Int = 0,
        deltaX: Int = 0,
        deltaY: Int = 0,
        wheel: Int = 0,
        horizontalPan: Int = 0
    ): Boolean {
        if (_connectionState.value != TvConnectionState.CONNECTED) return false
        val hid = hidDeviceProfile ?: return false
        val device = activeBluetoothDevice ?: return false
        if (!checkBluetoothPermissions()) return false

        if (hid.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
            // Real-time connection drop detected
            activeBluetoothDevice = null
            _connectedTvName.value = null
            _connectedTvAddress.value = null
            _connectionState.value = TvConnectionState.DISCONNECTED
            _errorMessage.value = "Connection lost with TV. Stopped sending Air Mouse commands."
            return false
        }

        val report = byteArrayOf(
            (buttonsMask and 0x07).toByte(),
            deltaX.coerceIn(-127, 127).toByte(),
            deltaY.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
            horizontalPan.coerceIn(-127, 127).toByte()
        )

        val sent = try {
            hid.sendReport(device, REPORT_ID_MOUSE, report)
        } catch (t: Throwable) {
            Log.e(TAG, "Error sending HID report", t)
            false
        }

        if (sent) {
            _hidPacketsSentCount.value += 1L
        }
        return sent
    }

    /**
     * Performs a single Left Click (Press + Release) over Bluetooth HID.
     */
    fun performLeftClick() {
        if (_connectionState.value != TvConnectionState.CONNECTED) return
        scope.launch(Dispatchers.IO) {
            sendMouseReport(buttonsMask = 0x01, deltaX = 0, deltaY = 0, wheel = 0, horizontalPan = 0)
            delay(35L)
            sendMouseReport(buttonsMask = 0x00, deltaX = 0, deltaY = 0, wheel = 0, horizontalPan = 0)
        }
    }

    /**
     * Performs a Double Click (Two Left Click Press+Release cycles) over Bluetooth HID.
     */
    fun performDoubleClick() {
        if (_connectionState.value != TvConnectionState.CONNECTED) return
        scope.launch(Dispatchers.IO) {
            sendMouseReport(buttonsMask = 0x01)
            delay(30L)
            sendMouseReport(buttonsMask = 0x00)
            delay(65L)
            sendMouseReport(buttonsMask = 0x01)
            delay(30L)
            sendMouseReport(buttonsMask = 0x00)
        }
    }

    /**
     * Immediately zeroes any in-flight HID state.
     */
    fun stopAllHidOutput() {
        if (_connectionState.value == TvConnectionState.CONNECTED) {
            sendMouseReport(buttonsMask = 0, deltaX = 0, deltaY = 0, wheel = 0, horizontalPan = 0)
        }
    }

    private fun handleBluetoothDisabled() {
        connectionTimeoutJob?.cancel()
        _isScanning.value = false
        activeBluetoothDevice = null
        _connectedTvName.value = null
        _connectedTvAddress.value = null
        _connectionState.value = TvConnectionState.DISCONNECTED
        _errorMessage.value = "Bluetooth was turned off. Air Mouse input stopped immediately."
    }

    private fun publishDeviceList() {
        if (!checkBluetoothPermissions()) return
        val list = discoveredDevicesMap.values.map { device ->
            val realName = resolveRealDeviceName(device)
            val btClass = device.bluetoothClass
            val majorClass = btClass?.majorDeviceClass
            val deviceClass = btClass?.deviceClass

            val isAudioVideo = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
            val nameLower = realName.lowercase()
            val hasTvKeyword = nameLower.contains("tv") ||
                nameLower.contains("bravia") ||
                nameLower.contains("tizen") ||
                nameLower.contains("webos") ||
                nameLower.contains("chromecast") ||
                nameLower.contains("shield") ||
                nameLower.contains("fire") ||
                nameLower.contains("roku") ||
                nameLower.contains("display") ||
                nameLower.contains("monitor")

            val isTvCandidate = isAudioVideo || hasTvKeyword
            val classLabel = when {
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> "TV / Video Display"
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> "Set-Top Box / TV"
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "Video Monitor / TV"
                isAudioVideo -> "Audio / Video Device"
                majorClass == BluetoothClass.Device.Major.COMPUTER -> "Computer / Media Host"
                majorClass == BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
                else -> "Bluetooth Device"
            }

            DiscoveredBluetoothDevice(
                name = realName,
                address = device.address,
                isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
                isTvCandidate = isTvCandidate,
                deviceClassLabel = classLabel,
                rssi = rssiMap[device.address]
            )
        }.sortedWith(
            compareByDescending<DiscoveredBluetoothDevice> { it.isTvCandidate }
                .thenByDescending { it.isPaired }
                .thenBy { it.name }
        )

        _discoveredDevices.value = list
    }

    private fun resolveRealDeviceName(device: BluetoothDevice): String {
        val rawName = try {
            device.name?.trim()
        } catch (_: SecurityException) {
            null
        }
        return if (!rawName.isNullOrEmpty()) {
            rawName
        } else {
            "Bluetooth Device (${device.address})"
        }
    }

    private fun registerBroadcastReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    fun release() {
        connectionTimeoutJob?.cancel()
        stopDeviceDiscovery()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        val hid = hidDeviceProfile
        if (hid != null) {
            try {
                if (checkBluetoothPermissions()) {
                    hid.unregisterApp()
                }
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (_: Throwable) {
            }
            hidDeviceProfile = null
        }
    }

    companion object {
        private const val TAG = "BluetoothTvHidCtrl"
        const val REPORT_ID_MOUSE = 1

        /**
         * Standard USB HID Report Descriptor for a 3-Button Relative Mouse with
         * Vertical Scroll Wheel and Horizontal AC Pan (5-byte report payload, Report ID = 1).
         */
        val HID_MOUSE_AND_SCROLL_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), REPORT_ID_MOUSE.toByte(), // Report ID (1)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            // Byte 0: 3 Buttons (Left, Right, Middle) + 5-bit padding
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Buttons)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x01.toByte(), //     Input (Constant) - 5-bit padding
            // Bytes 1, 2, 3: X, Y, Vertical Wheel (-127 to 127)
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            // Byte 4: Horizontal AC Pan (-127 to 127)
            0x05.toByte(), 0x0C.toByte(), //     Usage Page (Consumer Devices)
            0x0A.toByte(), 0x38.toByte(), 0x02.toByte(), // Usage (AC Pan)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                //   End Collection (Physical)
            0xC0.toByte()                 // End Collection (Application)
        )
    }
}
