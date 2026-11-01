package com.minimal.carlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CarLinkTest {

    private val backup = """{"app":"car_launcher","v":1,"created":1000,"prefs":{}}"""

    @Test fun sealOpenRoundTripAndWrongKey() {
        val k = CarLink.newKey()
        val line = CarLink.seal(k, "hello")
        assertEquals("hello", CarLink.open(k, line))
        assertNull(CarLink.open(CarLink.newKey(), line))
        assertNotEquals(line, CarLink.seal(k, "hello"))          // fresh IV every time
        assertEquals(false, line.contains('\n'))
    }

    @Test fun tamperedLineIsRefused() {
        val k = CarLink.newKey()
        val line = CarLink.seal(k, "fuel 20 L")
        val bytes = java.util.Base64.getDecoder().decode(line)
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        assertNull(CarLink.open(k, java.util.Base64.getEncoder().encodeToString(bytes)))
        assertNull(CarLink.open(k, "not base64 at all"))
    }

    @Test fun carMessageCarriesBackupLiveTripAndAcks() {
        val now = 1_800_000_000_000L
        val live = TripRecord(now - 600_000, now, 7.5, 500, 60, 72f, 2.0)
        val text = CarLink.carMessage(backup, live, 13.9f, now, listOf("a1", "b2"))
        val m = CarLink.parseCar(text, now + 1_000)!!
        assertEquals(7.5, m.live!!.km, 0.001)
        assertEquals(13.9f, m.voltage, 0.01f)
        assertEquals(listOf("a1", "b2"), m.acked)
        assertNotNull(org.json.JSONObject(m.backupJson).getJSONObject("prefs"))
        assertNull(CarLink.parseCar(text, now + CarLink.MAX_AGE_MS + 1))      // stale
        assertNull(CarLink.parseCar(CarLink.carMessage(backup, null, -1f, now, emptyList()).replace("\"car\"", "\"x\""), now))
        assertNull(CarLink.parseCar(CarLink.carMessage(backup, null, -1f, now, emptyList()), now)!!.live)
    }

    @Test fun phoneMessageCarriesFills() {
        val now = 1_800_000_000_000L
        val f = InboxFill("id1", now, true, 31.5f, 60_400)
        val fills = CarLink.parsePhone(CarLink.phoneMessage(listOf(f), now), now)!!
        assertEquals(listOf(f), fills)
        assertEquals(emptyList<InboxFill>(), CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now), now))
        assertNull(CarLink.parsePhone(CarLink.phoneMessage(listOf(f), now), now + CarLink.MAX_AGE_MS + 1))
    }

    @Test fun linkFileRoundTrip() {
        val k = CarLink.newKey()
        assertEquals(k, CarLink.parseLinkFile(CarLink.linkFile(k)))
        assertNull(CarLink.parseLinkFile("""{"app":"other","key":"x"}"""))
    }
}
