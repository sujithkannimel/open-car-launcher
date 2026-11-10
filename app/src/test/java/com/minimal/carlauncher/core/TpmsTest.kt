package com.minimal.carlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TpmsTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[it*2],16) shl 4) or Character.digit(s[it*2+1],16)).toByte() }

    @Test fun decryptsRealSensorPayloads() {
        Tpms.decode(hex("8A0F4C39FD1B42EC9EEAEB78612D3965")).let {
            assertNotNull(it); assertEquals(31f, it!!.psi, 0.01f); assertEquals(34f, it.tempC, 0.01f); assertEquals(100, it.batt)
        }
        Tpms.decode(hex("26AA7355A76506E54407FA4AFFF13EF5")).let {
            assertNotNull(it); assertEquals(30f, it!!.psi, 0.01f); assertEquals(33f, it.tempC, 0.01f)
        }
        Tpms.decode(hex("3C596B2691C1451F06EF6C5AD5753BBB")).let {
            assertNotNull(it); assertEquals(29f, it!!.psi, 0.01f); assertEquals(30f, it.tempC, 0.01f)
        }
    }

    @Test fun decodesWithMacPrefix() {
        val r = Tpms.decode(hex("EA2F9A856F91" + "8A0F4C39FD1B42EC9EEAEB78612D3965"))
        assertNotNull(r); assertEquals(31f, r!!.psi, 0.01f)
    }

    @Test fun rejectsNonSensorData() {
        assertNull(Tpms.decode(hex("0201060303E0FF")))
        assertNull(Tpms.decode(hex("00112233445566778899AABBCCDDEEFF")))
    }

    @Test fun linkRoundTrip() {
        val list = listOf(Tpms.Tyre("FL", 31.5f, 34f, 100, 2), Tpms.Tyre("RR", 28f, 30f, 95, 5))
        val back = Tpms.decodeList(Tpms.encode(list))
        assertEquals(2, back.size)
        assertEquals("FL", back[0].pos); assertEquals(31.5f, back[0].psi, 0.01f); assertEquals(100, back[0].batt)
        assertEquals(5, back[1].ageSec)
    }
}
