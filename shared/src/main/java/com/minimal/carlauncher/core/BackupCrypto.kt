package com.minimal.carlauncher.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional passphrase encryption for cloud backups: AES-256-GCM with a key derived by
 * PBKDF2-HMAC-SHA256 from the passphrase and a random salt. A wrong passphrase or any
 * tampering makes decryption fail instead of returning garbage.
 */
object BackupCrypto {

    private const val ITERATIONS = 120_000

    fun isEncrypted(text: String): Boolean = try {
        JSONObject(text).optString("enc") == "aes-256-gcm"
    } catch (_: Exception) {
        false
    }

    fun encrypt(plain: String, passphrase: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("app", "car_launcher").put("enc", "aes-256-gcm").put("iter", ITERATIONS)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8))))
            .toString()
    }

    /** The original text, or null if the passphrase is wrong or the file is damaged. */
    fun decrypt(text: String, passphrase: String): String? = try {
        val o = JSONObject(text)
        val b64 = Base64.getDecoder()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            key(passphrase, b64.decode(o.getString("salt")), o.optInt("iter", ITERATIONS)),
            GCMParameterSpec(128, b64.decode(o.getString("iv")))
        )
        String(c.doFinal(b64.decode(o.getString("data"))), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun key(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
