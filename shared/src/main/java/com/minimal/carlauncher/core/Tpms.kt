package com.minimal.carlauncher.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JK Tyre TREEL tyre-pressure sensors. They broadcast pressure and temperature over BLE, so the
 * phone (which can scan BLE; the head unit's Bluetooth can't) reads them and sends the readings
 * to the car over the live link, like the phone-GPS assist.
 *
 * The broadcast payload is AES-128-ECB with a fixed key that is the same in every TREEL sensor
 * and is published openly (github.com/pankajsammal/jktyre_treel_esp32_oled); reading one's own
 * sensors with it is interoperability, not breaking a secret. Decoded to the car, nothing of the
 * crypto matters - the car just shows four numbers.
 */
object Tpms {

    const val FL = "FL"; const val FR = "FR"; const val RL = "RL"; const val RR = "RR"
    val POSITIONS = listOf(FL, FR, RL, RR)
    fun posLabel(p: String) = when (p) {
        FL -> "Front left"; FR -> "Front right"; RL -> "Rear left"; RR -> "Rear right"; else -> p
    }

    /** The published global key, ASCII "#@Trl2018-lespl$". */
    private val KEY = "#@Trl2018-lespl$".toByteArray(Charsets.US_ASCII)

    /** One sensor's reading. [psi]/[tempC] are -1 when unknown; [batt] is 0..100, -1 unknown. */
    data class Reading(val psi: Float, val tempC: Float, val batt: Int)

    private fun decryptBlock(cipher16: ByteArray, off: Int): ByteArray? = try {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"))
        c.doFinal(cipher16, off, 16)
    } catch (_: Exception) { null }

    /**
     * Decodes a reading from a raw BLE advertisement. Slides a 16-byte window over the record and
     * decrypts each: a TREEL telemetry block starts with tag 0x16. Also reads the plaintext
     * iBeacon form some sensors use. Returns null when the record isn't a TREEL sensor.
     */
    fun decode(adv: ByteArray): Reading? {
        decodeEncrypted(adv)?.let { return it }
        return decodeBeacon(adv)
    }

    private fun decodeEncrypted(adv: ByteArray): Reading? {
        if (adv.size < 16) return null
        var off = 0
        while (off <= adv.size - 16) {
            val d = decryptBlock(adv, off)
            if (d != null && d[0].toInt() and 0xFF == 0x16) {
                val tempRaw = (d[1].toInt() and 0xFF) or ((d[2].toInt() and 0xFF) shl 8)
                val pressRaw = (d[3].toInt() and 0xFF) or ((d[4].toInt() and 0xFF) shl 8)
                val batt = minOf(100, d[5].toInt() and 0xFF)
                val temp = when {
                    tempRaw == 0xFFFF -> -1f
                    tempRaw <= 32768 -> tempRaw / 100f
                    else -> -((tempRaw - 32768) / 100f)
                }
                val psi = if (pressRaw == 0xFFFF) -1f else pressRaw / 100f
                // Guard against a chance 0x16 at the wrong offset.
                if (psi in 0f..120f && (temp == -1f || temp in -40f..150f)) return Reading(psi, temp, batt)
            }
            off++
        }
        return null
    }

    private val BEACON_UUID = ByteArray(16) { if (it == 15) 0xE0.toByte() else 0xFF.toByte() }

    private fun decodeBeacon(adv: ByteArray): Reading? {
        var o = 0
        while (o <= adv.size - 23) {
            if (adv[o].toInt() and 0xFF == 0x02 && adv[o + 1].toInt() and 0xFF == 0x15 &&
                regionEquals(adv, o + 2, BEACON_UUID)) {
                val minor = ((adv[o + 20].toInt() and 0xFF) shl 8) or (adv[o + 21].toInt() and 0xFF)
                val tx = adv[o + 22].toInt() and 0xFF
                val psi = (minor and 0xFF).toFloat()
                val temp = if (tx > 65) (tx - 110).toFloat() else -1f
                if (psi in 0f..120f) return Reading(psi, temp, -1)
            }
            o++
        }
        return null
    }

    private fun regionEquals(a: ByteArray, off: Int, b: ByteArray): Boolean {
        if (off + b.size > a.size) return false
        for (i in b.indices) if (a[off + i] != b[i]) return false
        return true
    }

    // ---------------------------------------------------------------- link serialisation

    /** A reading plus which tyre and how old, as sent to the car. */
    data class Tyre(val pos: String, val psi: Float, val tempC: Float, val batt: Int, val ageSec: Int)

    fun encode(tyres: List<Tyre>): String = org.json.JSONArray().apply {
        tyres.forEach {
            put(org.json.JSONArray().put(it.pos).put(it.psi.toDouble()).put(it.tempC.toDouble()).put(it.batt).put(it.ageSec))
        }
    }.toString()

    fun decodeList(json: String?): List<Tyre> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = org.json.JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val r = a.optJSONArray(i) ?: return@mapNotNull null
                Tyre(r.getString(0), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getInt(3), r.optInt(4, 0))
            }
        } catch (_: Exception) { emptyList() }
    }
}
