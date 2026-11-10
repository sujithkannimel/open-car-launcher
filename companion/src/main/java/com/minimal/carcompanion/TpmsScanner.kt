package com.minimal.carcompanion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.minimal.carlauncher.core.Tpms

/**
 * Scans for JK Tyre TREEL tyre sensors, decodes pressure and temperature from each broadcast
 * ([Tpms]) and keeps the latest per sensor. The link service hands these to the car. The head
 * unit can't scan BLE itself, so the phone does it while the car link is up.
 *
 * Each sensor is identified by its Bluetooth MAC; the driver maps a MAC to a tyre position once
 * (seeded with the four known sensors). Scanning runs only while [start]ed, to spare the battery.
 */
object TpmsScanner {

    private data class Live(val r: Tpms.Reading, val at: Long)
    private val readings = HashMap<String, Live>()          // MAC (lowercase) -> latest
    @Volatile private var scanning = false
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    private fun sp(c: Context) = c.getSharedPreferences("tpms", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("on", on).apply()
        if (!on) { readings.clear() }
    }

    fun hasScanPermission(c: Context) =
        if (Build.VERSION.SDK_INT >= 31)
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canScan(c: Context) = enabled(c) && hasScanPermission(c) &&
        (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true)

    // ---- MAC -> tyre position mapping (seeded with the known four, editable) ----

    private val SEED = mapOf(
        "e3:e7:6f:71:a8:3f" to Tpms.FR,
        "ea:2f:9a:85:6f:91" to Tpms.FL,
        "f9:d1:95:ee:99:80" to Tpms.RL,
        "ed:2a:e9:ef:03:8d" to Tpms.RR,
    )

    fun positionOf(c: Context, mac: String): String? {
        val m = mac.lowercase()
        return sp(c).getString("pos_$m", null) ?: SEED[m]
    }

    fun setPosition(c: Context, mac: String, pos: String?) {
        val e = sp(c).edit(); val k = "pos_${mac.lowercase()}"
        if (pos == null) e.remove(k) else e.putString(k, pos)
        e.apply()
    }

    /** MACs we've heard from, newest first, for the mapping screen. */
    fun seenMacs(): List<String> = synchronized(readings) {
        readings.entries.sortedByDescending { it.value.at }.map { it.key }
    }

    fun lastReading(mac: String): Tpms.Reading? = synchronized(readings) { readings[mac.lowercase()]?.r }

    // ---- scanning ----

    fun start(c: Context) {
        if (scanning || !canScan(c)) return
        val s = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        scanner = s
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            s.startScan(null, settings, callback)      // no filter: TREEL adverts vary; decode sorts them
            scanning = true
            LinkStore.note(c, "tpms: scanning for tyre sensors")
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    fun stop() {
        val s = scanner ?: return
        try { s.stopScan(callback) } catch (_: Exception) {}
        scanning = false
    }

    private var appCtx: Context? = null
    fun attach(c: Context) { appCtx = c.applicationContext }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.bytes ?: return
            val reading = Tpms.decode(bytes) ?: return
            val mac = result.device.address.lowercase()
            synchronized(readings) { readings[mac] = Live(reading, SystemClock.elapsedRealtime()) }
        }
    }

    /** Readings for the four mapped tyres, freshest kept, as the car wants them. Drops stale (>3 min). */
    fun tyresFor(c: Context): List<Tpms.Tyre> {
        val now = SystemClock.elapsedRealtime()
        val out = ArrayList<Tpms.Tyre>()
        synchronized(readings) {
            for ((mac, live) in readings) {
                if (now - live.at > 180_000L) continue
                val pos = positionOf(c, mac) ?: continue
                out += Tpms.Tyre(pos, live.r.psi, live.r.tempC, live.r.batt, ((now - live.at) / 1000L).toInt())
            }
        }
        // One per position (freshest).
        return out.groupBy { it.pos }.map { (_, v) -> v.minByOrNull { it.ageSec }!! }
    }
}
