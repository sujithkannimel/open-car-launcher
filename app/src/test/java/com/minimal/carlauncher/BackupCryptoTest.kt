package com.minimal.carlauncher

import com.minimal.carlauncher.core.Backup
import com.minimal.carlauncher.core.BackupCrypto
import com.minimal.carlauncher.core.Prefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val plain = Backup.toJson(mapOf(Prefs.KEY_KNOB_LX to 20), 1L)

    @Test
    fun encryptDecryptRoundTrip() {
        val enc = BackupCrypto.encrypt(plain, "my pass 123")
        assertTrue(BackupCrypto.isEncrypted(enc))
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertFalse(enc.contains(Prefs.KEY_KNOB_LX))
        assertEquals(plain, BackupCrypto.decrypt(enc, "my pass 123"))
    }

    @Test
    fun wrongPassphraseOrTamperingFails() {
        val enc = BackupCrypto.encrypt(plain, "right")
        assertNull(BackupCrypto.decrypt(enc, "wrong"))
        val o = JSONObject(enc)
        val d = o.getString("data")
        val flipped = (if (d[0] == 'A') 'B' else 'A') + d.substring(1)
        assertNull(BackupCrypto.decrypt(o.put("data", flipped).toString(), "right"))
    }

    @Test
    fun secretsNeverInBackupFile() {
        val json = Backup.toJson(mapOf(
            Prefs.KEY_CLOUD_TOKEN to "github_pat_SECRET",
            Prefs.KEY_CLOUD_PASS to "secretpass",
            Prefs.KEY_CLOUD_REPO to "me/car-backups",
            Prefs.KEY_KNOB_LX to 20,
        ), 1L)
        assertFalse(json.contains("github_pat_SECRET"))
        assertFalse(json.contains("secretpass"))
        assertFalse(json.contains(Prefs.KEY_CLOUD_TOKEN))
        assertTrue(json.contains(Prefs.KEY_KNOB_LX))
    }
}
