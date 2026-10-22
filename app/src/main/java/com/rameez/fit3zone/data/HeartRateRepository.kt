package com.rameez.fit3zone.data

import android.os.SystemClock
import com.rameez.fit3zone.core.DuplicateFilter
import com.rameez.fit3zone.core.Freshness
import com.rameez.fit3zone.core.HeartRateMeasurementParser
import com.rameez.fit3zone.core.HeartRatePlausibility
import com.rameez.fit3zone.core.HeartRateReading
import com.rameez.fit3zone.core.ParseResult
import com.rameez.fit3zone.core.ReadingSource
import com.rameez.fit3zone.core.SensorContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object BluetoothOff : BleConnectionState
    data object MissingPermission : BleConnectionState
    data class Connecting(val attempt: Int) : BleConnectionState
    data object DiscoveringServices : BleConnectionState
    data object Subscribing : BleConnectionState
    /** Notifications are enabled. This alone does NOT mean HR data is arriving. */
    data object Subscribed : BleConnectionState
    data class WaitingToReconnect(val delayMs: Long, val attempt: Int, val reason: String) : BleConnectionState
}

data class PacketStats(
    val total: Long = 0,
    val valid: Long = 0,
    val rejected: Long = 0,
    val malformed: Long = 0,
    val duplicates: Long = 0,
    val lastRejectReason: String? = null,
)

data class LastPacket(val hex: String, val decoded: String, val elapsedMs: Long, val wallClockMs: Long)

data class DeviceInfo(
    val name: String? = null,
    val address: String? = null,
    val firmwareRevision: String? = null,
    val softwareRevision: String? = null,
    val bodySensorLocation: String? = null,
    val services: List<String> = emptyList(),
)

/** Process-wide holder of live heart-rate state. Written by the BLE layer/simulator, read by service and UI. */
object HeartRateRepository {

    @Volatile var freshness: Freshness = Freshness()

    private val _connection = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connection: StateFlow<BleConnectionState> = _connection.asStateFlow()

    /** Last *valid* reading. Consumers must check [isFresh] before treating it as current. */
    private val _latest = MutableStateFlow<HeartRateReading?>(null)
    val latest: StateFlow<HeartRateReading?> = _latest.asStateFlow()

    /** Contact state from the most recent well-formed packet, even if it was rejected. */
    private val _contact = MutableStateFlow<SensorContact?>(null)
    val contact: StateFlow<SensorContact?> = _contact.asStateFlow()

    private val _stats = MutableStateFlow(PacketStats())
    val stats: StateFlow<PacketStats> = _stats.asStateFlow()

    private val _lastPacket = MutableStateFlow<LastPacket?>(null)
    val lastPacket: StateFlow<LastPacket?> = _lastPacket.asStateFlow()

    private val _deviceInfo = MutableStateFlow(DeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    val log = DiagnosticsLog()

    private val duplicates = DuplicateFilter()

    fun isFresh(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        freshness.isFresh(_latest.value, nowElapsedMs)

    fun setConnection(state: BleConnectionState) {
        if (_connection.value != state) {
            _connection.value = state
            log.add("state", state.toString())
        }
    }

    fun updateDeviceInfo(transform: (DeviceInfo) -> DeviceInfo) = _deviceInfo.update(transform)

    /** Called on the BLE thread for every 0x2A37 notification. */
    fun onBlePacket(bytes: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val hex = bytes.toHex()

        if (duplicates.isDuplicate(bytes, now)) {
            _stats.update { it.copy(total = it.total + 1, duplicates = it.duplicates + 1) }
            return
        }

        when (val result = HeartRateMeasurementParser.parse(bytes)) {
            is ParseResult.Invalid -> {
                _stats.update { it.copy(total = it.total + 1, malformed = it.malformed + 1, lastRejectReason = result.reason) }
                _lastPacket.value = LastPacket(hex, "malformed: ${result.reason}", now, wall)
                log.add("packet", "malformed $hex: ${result.reason}")
            }
            is ParseResult.Valid -> {
                val m = result.measurement
                _contact.value = m.contact
                val reject = HeartRatePlausibility.rejectionReason(m)
                val decoded = buildString {
                    append("flags=0x%02x bpm=%d contact=%s".format(m.flags, m.bpm, m.contact))
                    m.energyExpendedKj?.let { append(" energy=${it}kJ") }
                    if (m.rrIntervalsMs.isNotEmpty()) append(" rr=${m.rrIntervalsMs}")
                    reject?.let { append(" REJECTED: $it") }
                }
                _lastPacket.value = LastPacket(hex, decoded, now, wall)
                if (reject != null) {
                    _stats.update { it.copy(total = it.total + 1, rejected = it.rejected + 1, lastRejectReason = reject) }
                    log.add("packet", "$hex rejected: $reject")
                } else {
                    _stats.update { it.copy(total = it.total + 1, valid = it.valid + 1) }
                    _latest.value = HeartRateReading(m.bpm, m.contact, now, wall, ReadingSource.BLE)
                }
            }
        }
    }

    fun onSimulatedBpm(bpm: Int) {
        _contact.value = SensorContact.DETECTED
        _latest.value = HeartRateReading(
            bpm, SensorContact.DETECTED, SystemClock.elapsedRealtime(), System.currentTimeMillis(), ReadingSource.SIMULATED,
        )
        log.add("sim", "simulated $bpm BPM")
    }

    /** Forget the stream when the user disconnects so the UI never shows an old BPM as current. */
    fun clearReadings() {
        _latest.value = null
        _contact.value = null
        _lastPacket.value = null
        duplicates.reset()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
