package com.rameez.fit3zone.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.rameez.fit3zone.core.ReconnectBackoff
import com.rameez.fit3zone.data.BandData
import com.rameez.fit3zone.data.BleConnectionState
import com.rameez.fit3zone.data.HeartRateRepository
import com.rameez.fit3zone.data.SapCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * GATT client for the standard Heart Rate Service on a Galaxy Fit3 (or any HRP sensor).
 *
 * Threading: all GATT callbacks, timers and state live on one HandlerThread, so no locking is
 * needed and nothing here ever runs on the main thread.
 *
 * Every Bluetooth call is guarded by [hasConnectPermission]; lint's MissingPermission check cannot
 * see that, hence the class-level suppression.
 */
@SuppressLint("MissingPermission")
class Fit3BleManager(
    private val context: Context,
    private val repo: HeartRateRepository = HeartRateRepository,
) {
    private val thread = HandlerThread("fit3-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var wanted = false
    private val backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 30_000)
    private var reconnectJob: Job? = null
    private var setupWatchdog: Job? = null
    private var receiverRegistered = false
    private val sapRegistered = mutableListOf<BluetoothGattCharacteristic>()

    private val ops = ArrayDeque<GattOp>()
    private var opInFlight: GattOp? = null
    private var opTimeout: Job? = null

    init {
        // The Diagnostics toggles (file capture, band data) need the passive listener; follow them on
        // the current link (local-only change).
        SapCapture.load(context)
        BandData.load(context)
        scope.launch {
            combine(SapCapture.state.map { it.enabled }, BandData.state.map { it.enabled }) { a, b -> a || b }
                .distinctUntilChanged().drop(1).collect { on ->
                    val g = gatt ?: return@collect
                    if (g.services.isNullOrEmpty()) return@collect
                    if (on) registerSapListeners(g) else unregisterSapListeners(g)
                }
        }
    }

    private sealed interface GattOp {
        data class EnableNotifications(val ch: BluetoothGattCharacteristic) : GattOp
        data class Read(val ch: BluetoothGattCharacteristic) : GattOp
    }

    fun connect(address: String) = scope.launch {
        registerBluetoothReceiver()
        wanted = true
        if (targetAddress != address) repo.clearReadings()
        targetAddress = address
        backoff.reset()
        reconnectJob?.cancel()
        openGatt()
    }

    fun disconnect() = scope.launch {
        wanted = false
        reconnectJob?.cancel()
        closeGatt()
        repo.clearReadings()
        repo.setConnection(BleConnectionState.Idle)
    }

    /** Releases everything. The manager cannot be reused afterwards. */
    fun shutdown() {
        scope.launch {
            wanted = false
            reconnectJob?.cancel()
            closeGatt()
            repo.clearReadings()
            repo.setConnection(BleConnectionState.Idle)
            if (receiverRegistered) {
                runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
                receiverRegistered = false
            }
            scope.cancel()
            thread.quitSafely()
        }
    }

    // --- connection lifecycle -------------------------------------------------------------

    private fun openGatt() {
        closeGatt()
        val address = targetAddress ?: return
        if (!hasConnectPermission()) {
            repo.setConnection(BleConnectionState.MissingPermission)
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            repo.setConnection(BleConnectionState.BluetoothOff)
            return
        }
        val device = runCatching { bt.getRemoteDevice(address) }.getOrElse {
            repo.log.add("ble", "invalid address $address: ${it.message}")
            repo.setConnection(BleConnectionState.Idle)
            return
        }
        repo.updateDeviceInfo { it.copy(name = device.name, address = address) }
        repo.setConnection(BleConnectionState.Connecting(backoff.attempt))
        // autoConnect=false: direct connection, fails fast and lets our backoff decide the retry cadence.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        if (gatt == null) {
            scheduleReconnect("connectGatt returned null")
            return
        }
        // Connect + discovery + subscribe must finish in time, otherwise the stack is wedged.
        setupWatchdog = scope.launch {
            delay(SETUP_TIMEOUT_MS)
            repo.log.add("ble", "setup did not complete within ${SETUP_TIMEOUT_MS / 1000}s")
            closeGatt()
            scheduleReconnect("setup timeout")
        }
    }

    private fun closeGatt() {
        setupWatchdog?.cancel()
        opTimeout?.cancel()
        ops.clear()
        opInFlight = null
        gatt?.let { g ->
            if (sapRegistered.isNotEmpty()) SapCapture.note("link closed")
            sapRegistered.clear()
            SapCapture.setListening(emptyList())
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    private fun scheduleReconnect(reason: String) {
        if (!wanted) return
        if (adapter?.isEnabled != true) {
            repo.setConnection(BleConnectionState.BluetoothOff)
            return // the adapter receiver reconnects when Bluetooth comes back
        }
        val attempt = backoff.attempt + 1
        val delayMs = backoff.nextDelayMs()
        repo.setConnection(BleConnectionState.WaitingToReconnect(delayMs, attempt, reason))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wanted) openGatt()
        }
    }

    // --- GATT operation queue (Android allows only one outstanding operation) -----------

    private fun enqueue(op: GattOp) {
        ops.addLast(op)
        if (opInFlight == null) runNextOp()
    }

    private fun runNextOp() {
        opTimeout?.cancel()
        val g = gatt
        val op = ops.removeFirstOrNull()
        opInFlight = op
        if (op == null || g == null) return
        val started = when (op) {
            is GattOp.EnableNotifications -> writeCccd(g, op.ch)
            is GattOp.Read -> g.readCharacteristic(op.ch)
        }
        if (!started) {
            onOpFailed(op, "could not start")
            return
        }
        opTimeout = scope.launch {
            delay(OP_TIMEOUT_MS)
            onOpFailed(op, "timed out")
        }
    }

    private fun onOpDone(op: GattOp) {
        if (opInFlight != op) return
        opInFlight = null
        runNextOp()
    }

    private fun onOpFailed(op: GattOp, why: String) {
        if (opInFlight != op) return
        repo.log.add("ble", "$op $why")
        opInFlight = null
        if (op is GattOp.EnableNotifications) {
            // Without the subscription there is no HR stream: start over.
            closeGatt()
            scheduleReconnect("subscribe failed ($why)")
        } else {
            runNextOp()
        }
    }

    private fun writeCccd(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(GattUuids.CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // --- callbacks (delivered on [handler]) ----------------------------------------------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                repo.log.add("ble", "connected, discovering services")
                repo.setConnection(BleConnectionState.DiscoveringServices)
                // A short pause before discovery avoids lost callbacks on bonded devices.
                scope.launch {
                    delay(300)
                    if (g == gatt && !g.discoverServices()) {
                        closeGatt()
                        scheduleReconnect("discoverServices failed to start")
                    }
                }
            } else {
                repo.log.add("ble", "disconnected status=$status (${gattStatusName(status)}) newState=$newState")
                closeGatt()
                scheduleReconnect("link lost: ${gattStatusName(status)}")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                scheduleReconnect("service discovery failed: ${gattStatusName(status)}")
                return
            }
            val services = g.services.map { s -> "${s.uuid} (${s.characteristics.size} chars)" }
            repo.updateDeviceInfo { it.copy(services = services) }
            repo.log.add("ble", "discovered ${services.size} services")

            val hrm = g.getService(GattUuids.HEART_RATE_SERVICE)?.getCharacteristic(GattUuids.HEART_RATE_MEASUREMENT)
            if (hrm == null || hrm.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                repo.log.add("ble", "Heart Rate Measurement (0x2A37) with NOTIFY not found on this device")
                closeGatt()
                scheduleReconnect("no heart-rate service")
                return
            }
            repo.setConnection(BleConnectionState.Subscribing)
            enqueue(GattOp.EnableNotifications(hrm))
            listOfNotNull(
                g.getService(GattUuids.HEART_RATE_SERVICE)?.getCharacteristic(GattUuids.BODY_SENSOR_LOCATION),
                g.getService(GattUuids.DEVICE_INFORMATION)?.getCharacteristic(GattUuids.FIRMWARE_REVISION),
                g.getService(GattUuids.DEVICE_INFORMATION)?.getCharacteristic(GattUuids.SOFTWARE_REVISION),
            ).filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                .forEach { enqueue(GattOp.Read(it)) }
            if (sapWanted) registerSapListeners(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g != gatt) return
            val op = opInFlight as? GattOp.EnableNotifications ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setupWatchdog?.cancel()
                backoff.reset()
                repo.setConnection(BleConnectionState.Subscribed)
                repo.log.add("ble", "HR notifications enabled; waiting for samples")
                onOpDone(op)
            } else {
                onOpFailed(op, "CCCD write status ${gattStatusName(status)}")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (g != gatt) return
            if (ch.uuid == GattUuids.HEART_RATE_MEASUREMENT) repo.onBlePacket(value)
            else if (ch in sapRegistered) {
                SapCapture.onFrame(ch.service.uuid, ch.uuid, value)
                if (ch.uuid == GattUuids.SAP_NOTIFY) BandData.onSapNotification(value)
            }
        }

        @Deprecated("Called on API < 33 only")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, ch, ch.value ?: return)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g != gatt) return
            val op = opInFlight as? GattOp.Read ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) recordRead(ch, value)
            else repo.log.add("ble", "read ${ch.uuid} failed: ${gattStatusName(status)}")
            onOpDone(op)
        }

        @Deprecated("Called on API < 33 only")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, ch, ch.value ?: ByteArray(0), status)
        }
    }

    // --- passive SAP listener ----------------------------------------------------------------

    /**
     * Registers locally for every notifying characteristic on the band's proprietary services.
     * Only [BluetoothGatt.setCharacteristicNotification] is called: Android then forwards
     * notifications the band already sends on this shared link (Galaxy Wearable enabled the CCCD).
     * No CCCD write and never a write to the SAP characteristic: read-only by design.
     */
    private val sapWanted: Boolean get() = SapCapture.isEnabled || BandData.isEnabled

    private fun registerSapListeners(g: BluetoothGatt) {
        if (sapRegistered.isNotEmpty()) return
        val notifying = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val chars = g.services.filter { it.uuid !in GattUuids.STANDARD_SERVICES }
            .flatMap { it.characteristics }
            .filter { it.properties and notifying != 0 }
        val labels = chars.mapNotNull { ch ->
            if (!g.setCharacteristicNotification(ch, true)) {
                repo.log.add("sap", "local register failed for ${ch.uuid}")
                return@mapNotNull null
            }
            sapRegistered += ch
            "${ch.service.uuid} / ${ch.uuid} props=0x%02x".format(ch.properties)
        }
        SapCapture.setListening(labels)
        SapCapture.note("listening on ${labels.size} characteristic(s): ${labels.joinToString("; ")}")
        repo.log.add("sap", "passive listener on ${labels.size} characteristic(s)")
    }

    private fun unregisterSapListeners(g: BluetoothGatt) {
        // Local deregistration only; the band's CCCD is left as Galaxy Wearable set it.
        sapRegistered.forEach { runCatching { g.setCharacteristicNotification(it, false) } }
        sapRegistered.clear()
        SapCapture.setListening(emptyList())
        repo.log.add("sap", "passive listener off")
    }

    private fun recordRead(ch: BluetoothGattCharacteristic, value: ByteArray) {
        val text = value.toString(Charsets.UTF_8).trim()
        when (ch.uuid) {
            GattUuids.FIRMWARE_REVISION -> repo.updateDeviceInfo { it.copy(firmwareRevision = text) }
            GattUuids.SOFTWARE_REVISION -> repo.updateDeviceInfo { it.copy(softwareRevision = text) }
            GattUuids.BODY_SENSOR_LOCATION -> repo.updateDeviceInfo {
                it.copy(bodySensorLocation = bodySensorLocationName(value.firstOrNull()?.toInt()?.and(0xFF)))
            }
        }
        val shown = if (ch.uuid == GattUuids.BODY_SENSOR_LOCATION) value.joinToString("") { "%02x".format(it) } else text
        repo.log.add("ble", "read ${ch.uuid}: $shown")
    }

    // --- Bluetooth adapter on/off ----------------------------------------------------------

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    reconnectJob?.cancel()
                    closeGatt()
                    if (wanted) repo.setConnection(BleConnectionState.BluetoothOff)
                }
                BluetoothAdapter.STATE_ON -> if (wanted) {
                    repo.log.add("ble", "Bluetooth back on, reconnecting")
                    backoff.reset()
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        delay(1_000) // let the stack settle
                        if (wanted) openGatt()
                    }
                }
            }
        }
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            null, handler, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun hasConnectPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val SETUP_TIMEOUT_MS = 25_000L
        const val OP_TIMEOUT_MS = 5_000L
    }
}
