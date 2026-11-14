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
    @Volatile private var fastMode = false          // low-latency while the phone app is open
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    private fun sp(c: Context) = c.getSharedPreferences("tpms", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("on", on).apply()
        if (!on) {
            readings.clear()
            val e = sp(c).edit(); sp(c).all.keys.filter { it.startsWith("last_") }.forEach { e.remove(it) }; e.apply()
        }
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

    /** Last reading per mapped wheel, any age (for the phone card). Position -> (reading, ageSec). */
    fun displayFor(c: Context): Map<String, Pair<Tpms.Reading, Int>> {
        load(c)
        val now = SystemClock.elapsedRealtime()
        val out = HashMap<String, Pair<Tpms.Reading, Int>>()
        synchronized(readings) {
            for ((mac, live) in readings) {
                val pos = positionOf(c, mac) ?: continue
                val age = ((now - live.at) / 1000L).toInt()
                val prev = out[pos]
                if (prev == null || age < prev.second) out[pos] = live.r to age
            }
        }
        return out
    }

    // ---- persistence: last reading per sensor survives an app restart ----

    @Volatile private var loaded = false
    private val savedAt = HashMap<String, Long>()          // MAC -> elapsed time of last save

    /** Restores saved readings (under 12 h old) once per process. */
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        appCtx = c.applicationContext
        val wallNow = System.currentTimeMillis(); val elNow = SystemClock.elapsedRealtime()
        synchronized(readings) {
            for ((k, v) in sp(c).all) {
                if (!k.startsWith("last_") || v !is String) continue
                val f = v.split(",")
                if (f.size != 4) continue
                try {
                    val age = wallNow - f[3].toLong()
                    if (age < 0 || age > 12 * 3_600_000L) continue
                    val mac = k.removePrefix("last_")
                    if (readings[mac] == null)
                        readings[mac] = Live(Tpms.Reading(f[0].toFloat(), f[1].toFloat(), f[2].toInt()), elNow - age)
                } catch (_: Exception) {}
            }
        }
    }

    private fun save(mac: String, r: Tpms.Reading) {
        val c = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        val prev = savedAt[mac]
        if (prev != null && now - prev < 10_000L) return     // at most one write per sensor per 10 s
        savedAt[mac] = now
        sp(c).edit().putString("last_$mac", "${r.psi},${r.tempC},${r.batt},${System.currentTimeMillis()}").apply()
    }

    // ---- scanning ----

    /** True while the phone app is on screen (set by the activity). */
    @Volatile private var foreground = false
    private var lastStartAt = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun setForeground(c: Context, on: Boolean) {
        foreground = on
        if (on || scanning) start(c)
    }

    /**
     * Background filters: Android pauses a scan with no filter whenever the screen is off, which
     * is most of a drive. Match the TREEL service UUID and each known sensor's address instead.
     */
    private fun backgroundFilters(c: Context): List<android.bluetooth.le.ScanFilter> {
        val out = ArrayList<android.bluetooth.le.ScanFilter>()
        out += android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")).build()
        val macs = HashSet(SEED.keys)
        sp(c).all.keys.filter { it.startsWith("pos_") }.forEach { macs += it.removePrefix("pos_") }
        for (m in macs) try {
            out += android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(m.uppercase()).build()
        } catch (_: Exception) {}
        return out
    }

    /**
     * Starts (or re-modes) the scan. App on screen: low-latency and unfiltered, so every wheel
     * shows within a second or two and new sensors can be found. Otherwise balanced and filtered,
     * which keeps working with the screen off. Only the activity's [setForeground] picks the
     * mode, so the link service calling this every few seconds never downgrades it. Restarts are
     * spaced out: Android silently blocks an app that starts more than 5 scans in 30 s.
     */
    fun start(c: Context) {
        load(c)
        val fast = foreground
        if (scanning && fast == fastMode) return
        if (!canScan(c)) return
        val now = SystemClock.elapsedRealtime()
        val since = now - lastStartAt
        if (lastStartAt != 0L && since < 7_000L) {
            val app = c.applicationContext
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ if (scanning || foreground) start(app) }, 7_200L - since)
            return
        }
        val s = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        if (scanning) stop()                            // restart to change mode
        scanner = s
        fastMode = fast
        lastStartAt = now
        val settings = ScanSettings.Builder()
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0L)
            .build()
        try {
            s.startScan(if (fast) null else backgroundFilters(c), settings, callback)
            scanning = true
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    /** The refresh button: restart the scan, unless it was (re)started moments ago. */
    fun refresh(c: Context) {
        if (SystemClock.elapsedRealtime() - lastStartAt < 7_000L) return
        stop(); start(c)
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
            save(mac, reading)
        }
    }

    /** Readings for the four mapped tyres, freshest kept, as the car wants them. Drops stale (>3 min). */
    fun tyresFor(c: Context): List<Tpms.Tyre> {
        load(c)
        val now = SystemClock.elapsedRealtime()
        val out = ArrayList<Tpms.Tyre>()
        synchronized(readings) {
            for ((mac, live) in readings) {
                if (now - live.at > 3_600_000L) continue   // keep last-known up to 1 h; sensors sleep when parked
                val pos = positionOf(c, mac) ?: continue
                out += Tpms.Tyre(pos, live.r.psi, live.r.tempC, live.r.batt, ((now - live.at) / 1000L).toInt())
            }
        }
        // One per position (freshest).
        return out.groupBy { it.pos }.map { (_, v) -> v.minByOrNull { it.ageSec }!! }
    }
}
