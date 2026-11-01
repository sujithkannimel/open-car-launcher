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
        val msg = CarLink.parsePhone(CarLink.phoneMessage(listOf(f), now), now)!!
        assertEquals(listOf(f), msg.fills)
        assertNull(msg.offer)
        assertEquals(emptyList<InboxFill>(), CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now), now)!!.fills)
        val offer = CarLink.Offer("0.37", 1234L, "ab".repeat(32))
        assertEquals(offer, CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now, offer), now)!!.offer)
        assertNull(CarLink.parsePhone(CarLink.phoneMessage(listOf(f), now), now + CarLink.MAX_AGE_MS + 1))
    }

    @Test fun extrasAndApkRequest() {
        val now = 1_800_000_000_000L
        val ex = CarLink.CarExtra("0.36", 1, CarLink.Pos(9.05, 76.53, now, 0.5f), "log line")
        val m = CarLink.parseCar(CarLink.carMessage(backup, null, 12.4f, now, emptyList(), ex), now)!!
        assertEquals(ex, m.extra)
        assertNull(CarLink.parseCar(CarLink.carMessage(backup, null, 12.4f, now, emptyList()), now)!!.extra.pos)
        assertEquals("0.37", CarLink.parseApkRequest(CarLink.apkRequest("0.37", now), now))
        assertNull(CarLink.parseApkRequest(CarLink.apkRequest("0.37", now), now + CarLink.MAX_AGE_MS + 1))
        val f = java.io.File.createTempFile("apk", ".bin").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CarLink.sha256(f))
        f.delete()
    }

    @Test fun nowPlayingAndCommandsRoundTrip() {
        val now = 1_800_000_000_000L
        val np = CarLink.NowPlaying("com.google.android.apps.youtube.music", "YT Music", "Song", "Artist", "Album",
            true, 30_000, 200_000, now, "k1", "QUJD", listOf(CarLink.QueueItem(5, "Next song", "Someone")), 4)
        val back = CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now, null, np), now)!!.np
        assertEquals(np, back)
        assertEquals(40_000L, np.positionAt(now + 10_000))
        assertEquals(30_000L, np.copy(playing = false).positionAt(now + 10_000))
        assertEquals(200_000L, np.positionAt(now + 999_000))
        assertNull(CarLink.parsePhone(CarLink.phoneMessage(emptyList(), now), now)!!.np)

        val cmd = CarLink.Cmd(7, "queue", 5)
        val ex = CarLink.CarExtra(appVersion = "0.38", npArtKey = "k1", cmd = cmd)
        val m = CarLink.parseCar(CarLink.carMessage(backup, null, 13.8f, now, emptyList(), ex), now)!!
        assertEquals(cmd, m.extra.cmd)
        assertEquals("k1", m.extra.npArtKey)
    }

    @Test fun linkFileRoundTrip() {
        val k = CarLink.newKey()
        assertEquals(k, CarLink.parseLinkFile(CarLink.linkFile(k)))
        assertNull(CarLink.parseLinkFile("""{"app":"other","key":"x"}"""))
    }
}
