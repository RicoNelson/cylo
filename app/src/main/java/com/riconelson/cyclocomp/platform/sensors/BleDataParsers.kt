package com.riconelson.cyclocomp.platform.sensors

import java.util.UUID

data class WheelReading(
    val cumulativeRevolutions: Long, // UINT32
    val lastEventTime1024ths: Int,  // UINT16
)

data class CrankReading(
    val cumulativeRevolutions: Int, // UINT16
    val lastEventTime1024ths: Int,  // UINT16
)

data class CscMeasurement(
    val wheel: WheelReading? = null,
    val crank: CrankReading? = null,
)

data class PowerMeasurement(
    val instantaneousPowerWatts: Int,
    val crank: CrankReading? = null,
)

object BleDataParsers {
    val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    val CSC_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    val CSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")

    val POWER_SERVICE_UUID: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
    val POWER_MEASUREMENT_UUID: UUID = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")

    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Parse Bluetooth Heart Rate Measurement characteristic (0x2A37).
     * Returns Heart Rate in BPM.
     */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val is16BitFormat = (flags and 0x01) != 0
        return if (is16BitFormat) {
            if (data.size < 3) null
            else (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            if (data.size < 2) null
            else data[1].toInt() and 0xFF
        }
    }

    /**
     * Parse Cycling Speed and Cadence (CSC) Measurement characteristic (0x2A5B).
     */
    fun parseCsc(data: ByteArray): CscMeasurement? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val wheelPresent = (flags and 0x01) != 0
        val crankPresent = (flags and 0x02) != 0

        var offset = 1
        var wheel: WheelReading? = null
        if (wheelPresent) {
            if (data.size < offset + 6) return null
            val wheelRevs = (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
            val wheelTime = (data[offset + 4].toInt() and 0xFF) or
                ((data[offset + 5].toInt() and 0xFF) shl 8)
            wheel = WheelReading(wheelRevs, wheelTime)
            offset += 6
        }

        var crank: CrankReading? = null
        if (crankPresent) {
            if (data.size < offset + 4) return null
            val crankRevs = (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
            val crankTime = (data[offset + 2].toInt() and 0xFF) or
                ((data[offset + 3].toInt() and 0xFF) shl 8)
            crank = CrankReading(crankRevs, crankTime)
        }

        return CscMeasurement(wheel, crank)
    }

    /**
     * Parse Cycling Power Measurement characteristic (0x2A63).
     */
    fun parsePower(data: ByteArray): PowerMeasurement? {
        if (data.size < 4) return null
        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val powerWatts = (data[2].toInt() and 0xFF) or (data[3].toInt() shl 8) // SINT16

        var offset = 4
        val pedalPowerBalancePresent = (flags and (1 shl 0)) != 0
        val accumulatedTorquePresent = (flags and (1 shl 2)) != 0
        val wheelRevPresent = (flags and (1 shl 4)) != 0
        val crankRevPresent = (flags and (1 shl 5)) != 0

        if (pedalPowerBalancePresent) offset += 1
        if (accumulatedTorquePresent) offset += 2
        if (wheelRevPresent) offset += 6

        var crank: CrankReading? = null
        if (crankRevPresent) {
            if (data.size >= offset + 4) {
                val crankRevs = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                val crankTime = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)
                crank = CrankReading(crankRevs, crankTime)
            }
        }

        return PowerMeasurement(powerWatts, crank)
    }

    /**
     * Parse Battery Level characteristic (0x2A19). Returns 0..100 %.
     */
    fun parseBatteryLevel(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        return (data[0].toInt() and 0xFF).coerceIn(0, 100)
    }

    /**
     * Compute crank cadence (RPM) between two consecutive CrankReadings.
     */
    fun computeCadenceRpm(prev: CrankReading?, current: CrankReading): Int? {
        if (prev == null) return null
        val revDelta = (current.cumulativeRevolutions - prev.cumulativeRevolutions) and 0xFFFF
        val timeDelta1024ths = (current.lastEventTime1024ths - prev.lastEventTime1024ths) and 0xFFFF
        if (timeDelta1024ths == 0 || revDelta == 0) return 0
        val timeDeltaSeconds = timeDelta1024ths / 1024.0
        val rpm = (revDelta / timeDeltaSeconds) * 60.0
        return if (rpm.isFinite() && rpm in 0.0..300.0) rpm.toInt() else null
    }

    /**
     * Compute wheel speed (m/s) between two consecutive WheelReadings.
     * Default wheel circumference: 2.096 m (700x28c).
     */
    fun computeWheelSpeedMps(
        prev: WheelReading?,
        current: WheelReading,
        wheelCircumferenceMeters: Double = 2.096,
    ): Double? {
        if (prev == null) return null
        val revDelta = (current.cumulativeRevolutions - prev.cumulativeRevolutions) and 0xFFFFFFFFL
        val timeDelta1024ths = (current.lastEventTime1024ths - prev.lastEventTime1024ths) and 0xFFFF
        if (timeDelta1024ths == 0 || revDelta == 0L) return 0.0
        val timeDeltaSeconds = timeDelta1024ths / 1024.0
        val distanceMeters = revDelta * wheelCircumferenceMeters
        val mps = distanceMeters / timeDeltaSeconds
        return if (mps.isFinite() && mps in 0.0..40.0) mps else null
    }
}
