package com.rameez.fit3zone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FoundDevice(
    val address: String,
    val name: String?,
    val bonded: Boolean,
    val advertisesHeartRate: Boolean,
    val rssi: Int?,
) {
    val looksLikeFit3: Boolean get() = name?.contains("Fit3", ignoreCase = true) == true
}

/**
 * Lists bonded devices and runs a short foreground scan. The scan is unfiltered on purpose: it is
 * not documented whether the Fit3 advertises 0x180D, so filtering on it could hide the band.
 * Callers must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun loadBonded() {
        val bonded = adapter?.bondedDevices.orEmpty()
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            .map { FoundDevice(it.address, it.name, bonded = true, advertisesHeartRate = false, rssi = null) }
        _devices.update { current -> merge(current, bonded) }
    }

    fun startScan(durationMs: Long = 12_000) {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        _scanning.value = true
        main.postDelayed(::stopScan, durationMs)
    }

    fun stopScan() {
        main.removeCallbacksAndMessages(null)
        if (!_scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hr = result.scanRecord?.serviceUuids?.contains(HR_UUID) == true
            val found = FoundDevice(
                address = result.device.address,
                name = result.device.name ?: result.scanRecord?.deviceName,
                bonded = result.device.bondState == BluetoothDevice.BOND_BONDED,
                advertisesHeartRate = hr,
                rssi = result.rssi,
            )
            _devices.update { merge(it, listOf(found)) }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    private fun merge(current: List<FoundDevice>, incoming: List<FoundDevice>): List<FoundDevice> {
        val byAddress = current.associateBy { it.address }.toMutableMap()
        for (d in incoming) {
            val old = byAddress[d.address]
            byAddress[d.address] = if (old == null) d else old.copy(
                name = d.name ?: old.name,
                bonded = old.bonded || d.bonded,
                advertisesHeartRate = old.advertisesHeartRate || d.advertisesHeartRate,
                rssi = d.rssi ?: old.rssi,
            )
        }
        return byAddress.values.sortedWith(
            compareByDescending<FoundDevice> { it.looksLikeFit3 }
                .thenByDescending { it.advertisesHeartRate }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE },
        )
    }

    private companion object {
        val HR_UUID: ParcelUuid = ParcelUuid(GattUuids.HEART_RATE_SERVICE)
    }
}
