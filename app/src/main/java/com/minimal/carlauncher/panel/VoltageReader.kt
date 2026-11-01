package com.minimal.carlauncher.panel

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import com.minimal.carlauncher.core.Prefs
import java.util.Locale

/**
 * Reads the car battery / ACC input voltage from every known source on the K2401.
 *
 * Chain (first valid value wins):
 * 0. NWD CAN broadcast com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE, extra "Voltage" (139 = 13.9V).
 * 1. BatteryManager.EXTRA_VOLTAGE from the sticky BATTERY_CHANGED broadcast.
 * 2. Linux sysfs power_supply nodes -- Allwinner T507/A133 SoCs expose the ACC input
 *    voltage here as microvolts (e.g. 12300000 = 12.3V). The AXP2202 PMIC and generic
 *    battery/ac/usb nodes are all tried.
 * 3. Settings.System / Settings.Global keys used by various MCU firmware versions.
 * 4. A plausible 'vol*' extra from a vendor voltage broadcast.
 * 5. Otherwise '--.-V' (never a made-up number). Tap the pill to see which source was used.
 */
internal class VoltageReader(private val context: Context) {

    /** Where the last displayed reading came from; shown when the pill is tapped. */
    var source = "none"
        private set

    /** Last value from the NWD CAN broadcast, or null before the first one. */
    @Volatile var canVolts: Float? = null
        private set
    private var canRaw = -1

    /** elapsedRealtime of the last CAN reading, 0 = none yet. */
    @Volatile var lastCanAt = 0L
        private set

    /** Extras of the last voltage broadcast, as "key:Type=value", for the pill's tap toast. */
    private var lastExtras = "none received yet"
    private var lastVendor: Pair<Float, String>? = null

    /** The voltage to show, e.g. "12.6V", from the best source available now. */
    fun display(): String {
        // --- Source 0: NWD CAN box broadcast (what the stock launcher shows) ---
        canVolts?.let { v ->
            if (plausible(v)) { source = "NWD CAN broadcast (Voltage=$canRaw)"; return fmt(v) }
        }
        Prefs.lastVoltage.takeIf { plausible(it) }?.let { v ->
            source = "last saved value, waiting for broadcast ($lastExtras)"
            return fmt(v)
        }

        // --- Source 1: BatteryManager sticky broadcast ---
        try {
            val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val mv = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (mv > 0) {
                val v = toVolts(mv.toLong())
                if (plausible(v)) { source = "BatteryManager ($mv)"; return fmt(v) }
            }
        } catch (_: Exception) {}

        // --- Source 2: explicit sysfs paths first, then every power_supply node ---
        val paths = LinkedHashSet<String>(EXTRA_VOLT_PATHS)
        try {
            java.io.File("/sys/class/power_supply").listFiles()?.forEach { dir ->
                for (n in listOf("voltage_now", "voltage_avg", "batt_vol", "input_voltage")) {
                    paths.add("${dir.path}/$n")
                }
            }
        } catch (_: Exception) {}
        for (path in paths) {
            try {
                val f = java.io.File(path)
                if (!f.canRead()) continue
                val raw = f.readText().trim().split(Regex("\\s+")).first().toLongOrNull() ?: continue
                if (raw <= 0) continue
                val v = toVolts(raw)
                if (plausible(v)) { source = "$path ($raw)"; return fmt(v) }
            } catch (_: Exception) {}
        }

        // --- Source 3: Settings.System / Settings.Global (MCU firmware keys) ---
        for (k in VOLT_SETTING_KEYS) {
            try {
                val str = Settings.System.getString(context.contentResolver, k)
                    ?: Settings.Global.getString(context.contentResolver, k) ?: continue
                val num = Regex("[0-9]+(\\.[0-9]+)?").find(str)?.value ?: continue
                val v = if (num.contains('.')) num.toFloat() else toVolts(num.toLong())
                if (plausible(v)) { source = "settings:$k ($str)"; return fmt(v) }
            } catch (_: Exception) {}
        }

        // --- Source 4: value pushed by a vendor broadcast ---
        lastVendor?.let { if (plausible(it.first)) { source = it.second; return fmt(it.first) } }

        source = "none found; last broadcast extras: $lastExtras"
        return "--.-V"
    }

    /** True when the last displayed value came from the CAN broadcast (not a polled source). */
    val fromCan: Boolean get() = source.startsWith("NWD CAN")

    /**
     * Parses ACTION_CAN_BATTERY_VOLTAGE (sent by com.nwd.kernel every ~30 s). The value is
     * tenths of a volt (142 = 14.2V). Accepts any numeric type or a string, under "Voltage" or
     * any key containing "volt". Returns the volts if a plausible value was found (and saved).
     */
    fun handleCan(intent: Intent): Float? {
        val ex = intent.extras ?: return null
        lastExtras = ex.keySet().joinToString { k ->
            @Suppress("DEPRECATION") val o = ex.get(k)
            "$k:${o?.javaClass?.simpleName}=$o"
        }
        val keys = listOf(NWD_VOLT_KEY) + ex.keySet().filter { it.contains("volt", ignoreCase = true) }
        for (k in keys) {
            @Suppress("DEPRECATION") val o = ex.get(k) ?: continue
            val v: Float = when (o) {
                is Float -> if (o > 40f) o / 10f else o
                is Double -> (if (o > 40.0) o / 10.0 else o).toFloat()
                is Number -> o.toLong().let { if (it > 1000) it / 1000f else it / 10f }
                is String -> o.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                    ?.let { if (it > 40f) it / 10f else it } ?: continue
                else -> continue
            }
            if (plausible(v)) {
                canRaw = (v * 10).toInt()
                canVolts = v
                Prefs.lastVoltage = v
                lastCanAt = SystemClock.elapsedRealtime()
                return v
            }
        }
        return null
    }

    /** A plausible 'vol*' extra from any other vendor voltage broadcast (fallback source 4). */
    fun handleVendor(intent: Intent) {
        val ex = intent.extras ?: return
        for (key in ex.keySet()) {
            if (!key.contains("vol", ignoreCase = true)) continue
            @Suppress("DEPRECATION")
            val v = when (val o = ex.get(key)) {
                is Float -> o
                is Double -> o.toFloat()
                is Int -> toVolts(o.toLong())
                is Long -> toVolts(o)
                is String -> o.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                else -> null
            } ?: continue
            if (plausible(v)) {
                lastVendor = v to "broadcast ${intent.action} [$key]"
                break
            }
        }
    }

    /** False only when a fresh CAN reading says the alternator is not charging. */
    fun engineMaybeRunning(): Boolean {
        if (lastCanAt == 0L || SystemClock.elapsedRealtime() - lastCanAt > 5 * 60_000L) return true
        return (canVolts ?: return true) >= ENGINE_ON_VOLTS
    }

    companion object {
        const val NWD_VOLT_KEY = "Voltage"
        /** Above this the alternator is charging, so the engine is running. */
        const val ENGINE_ON_VOLTS = 13.0f

        fun plausible(v: Float) = v in 6.0f..18.5f

        /** Converts a raw reading in uV, mV, tenths or volts to volts. */
        fun toVolts(raw: Long): Float = when {
            raw > 1_000_000 -> raw / 1_000_000f
            raw > 1_000 -> raw / 1_000f
            raw > 100 -> raw / 10f
            else -> raw.toFloat()
        }

        private fun fmt(v: Float) = String.format(Locale.US, "%.1fV", v)

        // Fallbacks for other firmware.
        private val EXTRA_VOLT_PATHS = listOf(
            "/sys/class/power_supply/axp2202-ac/voltage_now",
            "/sys/class/power_supply/axp2202-usb/voltage_now",
            "/sys/class/power_supply/axp2202-battery/voltage_now",
        )
        private val VOLT_SETTING_KEYS = listOf(
            "car_battery_voltage", "battery_voltage", "car_voltage", "voltage_battery",
            "system_battery_voltage", "mcu_battery_voltage", "battery_vol", "car_vol",
            "nwd_battery_voltage", "nwd_voltage", "acc_voltage", "power_voltage",
        )
    }
}
