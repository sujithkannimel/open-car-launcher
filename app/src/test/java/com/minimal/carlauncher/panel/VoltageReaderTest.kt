package com.minimal.carlauncher.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageReaderTest {

    @Test fun rawUnitsBecomeVolts() {
        assertEquals(12.3f, VoltageReader.toVolts(12_300_000), 0.001f)   // microvolts (sysfs)
        assertEquals(12.3f, VoltageReader.toVolts(12_300), 0.001f)       // millivolts
        assertEquals(13.9f, VoltageReader.toVolts(139), 0.001f)          // tenths (NWD CAN)
        assertEquals(12f, VoltageReader.toVolts(12), 0.001f)             // volts
    }

    @Test fun onlyCarVoltagesArePlausible() {
        assertTrue(VoltageReader.plausible(12.6f))
        assertTrue(VoltageReader.plausible(14.4f))
        assertFalse(VoltageReader.plausible(4.2f))                       // a phone-style Li-ion value
        assertFalse(VoltageReader.plausible(25f))
    }
}
