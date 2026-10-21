package com.riconelson.cyclocomp.platform.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BleDataParsersTest {

    @Test
    fun `parses 8-bit heart rate measurement`() {
        // Flags: 0x00 (8-bit HR value), Value: 145 bpm
        val bytes = byteArrayOf(0x00, 145.toByte())
        val hr = BleDataParsers.parseHeartRate(bytes)
        assertEquals(145, hr)
    }

    @Test
    fun `parses 16-bit heart rate measurement`() {
        // Flags: 0x01 (16-bit HR value), Value: 180 bpm (0x00B4)
        val bytes = byteArrayOf(0x01, 0xB4.toByte(), 0x00)
        val hr = BleDataParsers.parseHeartRate(bytes)
        assertEquals(180, hr)
    }

    @Test
    fun `parses CSC measurement with crank and wheel`() {
        // Flags: 0x03 (wheel & crank present)
        // Wheel: 1000 revs (0x000003E8), time 2048 1/1024s (2 sec, 0x0800)
        // Crank: 500 revs (0x01F4), time 2048 1/1024s (2 sec, 0x0800)
        val bytes = byteArrayOf(
            0x03,
            0xE8.toByte(), 0x03, 0x00, 0x00, // Wheel revs 1000
            0x00, 0x08,                      // Wheel time 2048
            0xF4.toByte(), 0x01,             // Crank revs 500
            0x00, 0x08,                      // Crank time 2048
        )

        val csc = BleDataParsers.parseCsc(bytes)
        assertNotNull(csc)
        assertEquals(1000L, csc!!.wheel?.cumulativeRevolutions)
        assertEquals(2048, csc.wheel?.lastEventTime1024ths)
        assertEquals(500, csc.crank?.cumulativeRevolutions)
        assertEquals(2048, csc.crank?.lastEventTime1024ths)
    }

    @Test
    fun `computes cadence in RPM from crank readings`() {
        val prev = CrankReading(cumulativeRevolutions = 100, lastEventTime1024ths = 1024)
        // 3 revolutions in 2 seconds (2048 1/1024s delta) -> 90 RPM
        val current = CrankReading(cumulativeRevolutions = 103, lastEventTime1024ths = 3072)

        val rpm = BleDataParsers.computeCadenceRpm(prev, current)
        assertEquals(90, rpm)
    }

    @Test
    fun `computes wheel speed from wheel readings`() {
        val prev = WheelReading(cumulativeRevolutions = 1000, lastEventTime1024ths = 1024)
        // 5 revolutions in 1 second (1024 delta) with 2.096m wheel -> 10.48 m/s (~37.7 km/h)
        val current = WheelReading(cumulativeRevolutions = 1005, lastEventTime1024ths = 2048)

        val speedMps = BleDataParsers.computeWheelSpeedMps(prev, current, wheelCircumferenceMeters = 2.096)
        assertNotNull(speedMps)
        assertEquals(10.48, speedMps!!, 0.01)
    }

    @Test
    fun `parses cycling power measurement`() {
        // Flags: 0x0000, Power: 250 Watts (0x00FA)
        val bytes = byteArrayOf(0x00, 0x00, 0xFA.toByte(), 0x00)
        val power = BleDataParsers.parsePower(bytes)
        assertNotNull(power)
        assertEquals(250, power!!.instantaneousPowerWatts)
    }

    @Test
    fun `parses battery level`() {
        val bytes = byteArrayOf(85.toByte())
        val battery = BleDataParsers.parseBatteryLevel(bytes)
        assertEquals(85, battery)
    }
}
