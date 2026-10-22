package com.rameez.fit3zone.ble

import java.util.UUID

/** Bluetooth SIG assigned numbers used by this app. */
object GattUuids {
    private fun sig(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

    val HEART_RATE_SERVICE: UUID = sig(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = sig(0x2A37)
    val BODY_SENSOR_LOCATION: UUID = sig(0x2A38)
    val DEVICE_INFORMATION: UUID = sig(0x180A)
    val FIRMWARE_REVISION: UUID = sig(0x2A26)
    val SOFTWARE_REVISION: UUID = sig(0x2A28)
    val CCCD: UUID = sig(0x2902)

    /** SAP over BLE (Samsung Accessory Protocol); only read, never written. */
    val SAP_SERVICE: UUID = sig(0x1A1A)
    /** Band → phone SAP data (notify). We only ever listen on it. */
    val SAP_NOTIFY: UUID = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")

    /** SIG services the passive SAP listener ignores (HR is handled separately). */
    val STANDARD_SERVICES: Set<UUID> = setOf(sig(0x1800), sig(0x1801), DEVICE_INFORMATION, HEART_RATE_SERVICE)
}

fun bodySensorLocationName(code: Int?): String = when (code) {
    0 -> "Other"
    1 -> "Chest"
    2 -> "Wrist"
    3 -> "Finger"
    4 -> "Hand"
    5 -> "Ear lobe"
    6 -> "Foot"
    null -> "unknown"
    else -> "reserved ($code)"
}

/** Names for the GATT status codes commonly seen in disconnect callbacks. */
fun gattStatusName(status: Int): String = when (status) {
    0 -> "SUCCESS"
    5 -> "INSUFFICIENT_AUTHENTICATION"
    8 -> "CONN_TIMEOUT"
    15 -> "INSUFFICIENT_ENCRYPTION"
    19 -> "PEER_TERMINATED"
    22 -> "LOCAL_TERMINATED"
    62 -> "CONN_FAIL_ESTABLISH"
    133 -> "GATT_ERROR(133)"
    147 -> "CONN_TIMEOUT(147)"
    else -> "status $status"
}
