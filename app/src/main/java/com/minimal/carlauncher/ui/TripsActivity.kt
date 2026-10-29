package com.minimal.carlauncher.ui

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import com.minimal.carlauncher.core.BackupStore
import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.TripLog
import com.minimal.carlauncher.core.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Trips & fuel": fuel range, fill-up log with per-tank km/l and a mileage chart, plus the
 * live trip, trip history and totals. Everything is local to the head unit.
 */
class TripsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_FUEL = "fuel"
        const val TAB_TRIPS = "trips"
        const val TAB_BATTERY = "battery"

        private val BG = Color.rgb(13, 17, 23)
        private val CARD = Color.rgb(22, 27, 34)
        private val STROKE = Color.rgb(48, 54, 61)
        private val DIM = Color.rgb(130, 150, 170)
        private val TEXT = Color.rgb(225, 232, 240)
        private val CYAN = Color.rgb(34, 211, 238)
        private val GREEN = Color.rgb(74, 222, 128)
        private val AMBER = Color.rgb(251, 191, 36)
        private val RED = Color.rgb(244, 63, 94)
        private const val REQ_READ = 41
        private const val DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert
    }

    private lateinit var content: LinearLayout
    private lateinit var tabFuel: TextView
    private lateinit var tabTrips: TextView
    private lateinit var tabBattery: TextView
    private var tab = TAB_FUEL
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (tab == TAB_TRIPS || tab == TAB_BATTERY) render()
            handler.postDelayed(this, 5_000L)
        }
    }
    private val day = SimpleDateFormat("d MMM", Locale.US)
    private val dayTime = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = intent.getStringExtra(EXTRA_TAB) ?: TAB_FUEL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(16), px(12), px(16))
        }
        tabFuel = railButton("Fuel") { select(TAB_FUEL) }
        tabTrips = railButton("Trips") { select(TAB_TRIPS) }
        tabBattery = railButton("Battery") { select(TAB_BATTERY) }
        rail.addView(tabFuel, LinearLayout.LayoutParams(px(130), px(56)).apply { bottomMargin = px(10) })
        rail.addView(tabTrips, LinearLayout.LayoutParams(px(130), px(56)).apply { bottomMargin = px(10) })
        rail.addView(tabBattery, LinearLayout.LayoutParams(px(130), px(56)))
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(railButton("Close") { finish() }, LinearLayout.LayoutParams(px(130), px(48)))
        root.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(16), px(20), px(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it }
    }

    override fun onResume() {
        super.onResume()
        select(tab)
        handler.postDelayed(ticker, 5_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun select(t: String) {
        tab = t
        styleRail(tabFuel, t == TAB_FUEL)
        styleRail(tabTrips, t == TAB_TRIPS)
        styleRail(tabBattery, t == TAB_BATTERY)
        render()
    }

    private fun render() {
        content.removeAllViews()
        when (tab) {
            TAB_FUEL -> renderFuel()
            TAB_BATTERY -> renderBattery()
            else -> renderTrips()
        }
    }

    // ================================================================== fuel

    private fun renderFuel() {
        val gps = Prefs.gpsOdoKm
        val r = Prefs.fuelReplay()
        val s = r.state

        if (!s.configured) {
            content.addView(title("Fuel"))
            content.addView(body("Set up the fuel estimate once: tank size, rough mileage and how full the tank is now."))
            content.addView(buttonRow(listOf("Set up fuel" to { FuelDialog.showSetup(this) { render() } })))
            return
        }

        val now = System.currentTimeMillis()
        val m = Prefs.fuelModel(now)
        val range = m.rangeKm
        val level = com.minimal.carlauncher.core.FuelModel.level(range)
        val rangeColor = when (level) { 4 -> RED; 3 -> AMBER; else -> GREEN }
        val learned = r.tanks.size
        val est = r.estimatedOdo(gps)

        content.addView(title("Fuel"))
        content.addView(statRow(listOf(
            Stat("≈ $range km", "RANGE LEFT", rangeColor),
            Stat(String.format(Locale.US, "%.1f L", m.litresLeft), "IN TANK (OF ${fmt0(s.tankL)} L)", TEXT),
            Stat(String.format(Locale.US, "%.1f km/l", m.kmplNow),
                if (learned == 0) "MILEAGE (YOUR ESTIMATE)" else "MILEAGE NOW (LEARNED, $learned TANK${if (learned == 1) "" else "S"})", CYAN),
            Stat(if (est >= 0) String.format(Locale.US, "%,d km", est) else "--", "ODOMETER (BY GPS)", TEXT),
        )))
        val split = m.tanksUsed > 0
        val refuel = com.minimal.carlauncher.core.FuelModel.refuelBy(range, m.dailyKm, now)
        val refuelText = when {
            range in 0..com.minimal.carlauncher.core.FuelModel.RESERVE_KM -> "Refuel now"
            refuel == null -> "--"
            refuel - now < 86_400_000L -> "Today"
            else -> SimpleDateFormat("EEE d MMM", Locale.US).format(Date(refuel))
        }
        content.addView(LinearLayout(this).apply { setPadding(0, px(10), 0, 0); addView(statRow(listOf(
            Stat(if (split) String.format(Locale.US, "%.1f km/l", m.kmplCity) else "learning", "CITY", TEXT),
            Stat(if (split) String.format(Locale.US, "%.1f km/l", m.kmplHwy) else "learning", "HIGHWAY (55+ KM/H)", TEXT),
            Stat(refuelText, if (m.dailyKm > 0) String.format(Locale.US, "REFUEL BY (~%.0f KM/DAY)", m.dailyKm) else "REFUEL BY (NEEDS 3 DAYS)",
                if (range in 0..90) AMBER else TEXT),
            Stat(String.format(Locale.US, "%.1f L", m.idleLitresSinceFill), "IDLING SINCE FILL (EST.)",
                if (m.idleLitresSinceFill > 1f) AMBER else TEXT),
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        if (!split) content.addView(hint("City and highway mileage are learned after a full-to-full tank with litres entered, driven with this version."))
        else if (m.recentHwyShare >= 0) content.addView(hint(String.format(Locale.US,
            "Recent driving: %.0f%% highway. Range uses this mix.", m.recentHwyShare * 100)))
        content.addView(buttonRow(listOf(
            "Log fill-up" to { FuelDialog.showFill(this) { render() } },
            "Correct odometer" to { FuelDialog.showCorrectOdometer(this) { render() } },
            "Find petrol pump" to { FuelDialog.findPetrolPump(this) },
            "Set-up" to { FuelDialog.showSetup(this) { render() } },
        )))

        content.addView(section("Mileage per tank"))
        val chart = KmplChartView(this).apply {
            points = r.tanks.takeLast(12).map { (if (it.time > 0) day.format(Date(it.time)) else "") to it.kmpl }
        }
        content.addView(card(chart, px(170)))

        content.addView(section("Fill-up log"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = r.rows.asReversed()
        if (rows.isEmpty()) list.addView(body("No entries yet."))
        rows.forEach { row ->
            val e = row.event
            val what = when (e.kind) {
                FuelEvent.KIND_SETUP -> "Set-up"
                FuelEvent.KIND_ODO -> "Odometer corrected"
                else -> if (e.full) "Filled to full" else "Top-up"
            }
            val parts = ArrayList<String>()
            if (e.kind == FuelEvent.KIND_FILL && e.litres > 0f) parts += String.format(Locale.US, "%.1f L", e.litres)
            if (row.odoAt >= 0) parts += String.format(Locale.US, "%s%,d km", if (row.odoEstimated) "≈" else "", row.odoAt)
            if (row.kmSincePrev >= 0) parts += String.format(Locale.US, "+%.0f km", row.kmSincePrev)
            val right = if (row.measuredKmpl > 0f) String.format(Locale.US, "%.1f km/l", row.measuredKmpl) else ""
            val whenTxt = if (e.time > 0) dayTime.format(Date(e.time)) else "Earlier"
            list.addView(logRow(whenTxt, what, parts.joinToString("   "), right) {
                FuelDialog.showEdit(this, row.index) { render() }
            })
        }
        content.addView(list)
        content.addView(hint("Tap an entry to edit or delete it."))
    }

    // ================================================================== trips

    private fun renderTrips() {
        val now = System.currentTimeMillis()
        val history = Prefs.tripHistory
        val live = liveTrip(now)
        val fm = Prefs.fuelModel(now)
        fun litres(t: TripRecord) = com.minimal.carlauncher.core.FuelModel.tripLitres(t, fm.kmplCity, fm.kmplHwy, fm.recentHwyShare)

        content.addView(title("This trip"))
        if (live == null) {
            content.addView(body("Starts when you switch the car on."))
        } else {
            content.addView(statRow(listOf(
                Stat(String.format(Locale.US, "%.1f km", live.km), "DISTANCE", CYAN),
                Stat(dur(live.durationSec), "SINCE START", TEXT),
                Stat(dur(live.driveSec), "DRIVING", TEXT),
                Stat(dur(live.idleSec), "IDLING", if (live.idleSec > 600) AMBER else TEXT),
                Stat(speed(live.avgKmh), "AVG SPEED", TEXT),
                Stat(speed(live.maxKmh), "TOP SPEED", TEXT),
            )))
            content.addView(hint(String.format(Locale.US,
                "Fuel this trip \u2248 %.2f L (idling %.2f L)%s", litres(live),
                com.minimal.carlauncher.core.FuelModel.idleLitres(live.idleSec),
                if (live.hwyKm >= 0 && live.km > 0.5) String.format(Locale.US, " \u00B7 %.0f%% highway", live.hwyKm / live.km * 100) else "")))
        }

        val all = if (live != null) history + live else history
        content.addView(section("Totals"))
        content.addView(statRow(listOf(
            totalStat(TripLog.totals(all, TripLog.startOfToday(now)), "TODAY"),
            totalStat(TripLog.totals(all, TripLog.startOfWeek(now)), "THIS WEEK"),
            totalStat(TripLog.totals(all, TripLog.startOfMonth(now)), "THIS MONTH"),
            totalStat(TripLog.totals(all, 0L), "ALL RECORDED"),
        )))
        content.addView(buttonRow(listOf("Export CSV" to { export() })))

        content.addView(section("Recent trips"))
        if (history.isEmpty()) content.addView(body("Finished trips appear here."))
        history.asReversed().take(60).forEach { t ->
            val parts = listOf(
                String.format(Locale.US, "%.1f km", t.km),
                dur(t.durationSec),
                "avg " + speed(t.avgKmh),
                "top " + speed(t.maxKmh),
                String.format(Locale.US, "\u2248%.1f L", litres(t)),
            )
            content.addView(logRow(dayTime.format(Date(t.start)), "", parts.joinToString("   "),
                if (t.idleSec > 60) "idle " + dur(t.idleSec) else "", null))
        }
        content.addView(hint("Distance and speed come from the head unit's GPS. Trips start when the ignition goes on."))

        content.addView(section("Backup"))
        val last = Prefs.lastAutoBackupDay
        content.addView(body(
            (if (last.isEmpty()) "No automatic backup yet." else "Last automatic backup: $last.") +
                " Trips, fuel log and panel settings are saved daily to Download/CarLauncher (last 7 kept)."
        ))
        content.addView(buttonRow(listOf(
            "Back up now" to { backupNow() },
            "Restore" to { restore(askPermission = true) },
        )))

        content.addView(section("Cloud backup (private GitHub repo)"))
        content.addView(body(
            if (!Prefs.cloudConfigured) "Not set up. Uploads one backup a day to your private repo when there is internet."
            else "${Prefs.cloudRepo}" + (if (Prefs.cloudPass.isNotEmpty()) " \u00B7 encrypted" else "") +
                "\n" + Prefs.cloudStatus.ifEmpty { "No upload yet." }
        ))
        content.addView(buttonRow(listOf(
            "Cloud settings" to { cloudSettings(Prefs.cloudRepo.ifEmpty { "sujithkannimel/car-backups" }, "", Prefs.cloudPass) },
            "Upload now" to { cloudUploadNow() },
            "Restore from cloud" to { cloudRestore() },
        )))
    }

    // ================================================================== cloud

    private fun cloudSettings(repoIn: String, tokenIn: String, passIn: String) {
        val a = this
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(8), px(24), 0) }
        fun label(t: String) = TextView(a).apply { text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, px(10), 0, px(2)) }
        fun field(v: String, hint: String, secret: Boolean) = android.widget.EditText(a).apply {
            setText(v); this.hint = hint; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
        }
        col.addView(label("Private repo (owner/name)"))
        val repo = field(repoIn, "sujithkannimel/car-backups", false); col.addView(repo)
        col.addView(label("Token (github_pat_...)"))
        val token = field(tokenIn, if (Prefs.cloudToken.isNotEmpty()) "Saved - leave empty to keep it" else "Tap here, then paste with ADB", true)
        col.addView(token)
        col.addView(label("Passphrase (optional - encrypts the cloud copy; can't be recovered if forgotten)"))
        val pass = field(passIn, "Leave empty for none", true); col.addView(pass)

        AlertDialog.Builder(a, DIALOG)
            .setTitle("Cloud backup")
            .setView(col)
            .setPositiveButton("Test & save") { _, _ ->
                val r = repo.text.toString().trim()
                val t = token.text.toString().trim().ifEmpty { Prefs.cloudToken }
                val p = pass.text.toString()
                if (t.isEmpty()) { Toast.makeText(a, "Paste the token first", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                Toast.makeText(a, "Testing...", Toast.LENGTH_SHORT).show()
                Thread {
                    val res = com.minimal.carlauncher.core.CloudBackup.test(r, t)
                    runOnUiThread {
                        if (res.ok) {
                            Prefs.cloudRepo = r; Prefs.cloudToken = t; Prefs.cloudPass = p
                            Prefs.cloudLastDay = ""   // upload today's backup at the next chance
                            Toast.makeText(a, res.message + ". Saved.", Toast.LENGTH_LONG).show()
                            render()
                        } else {
                            AlertDialog.Builder(a, DIALOG).setTitle("Not saved").setMessage(res.message)
                                .setPositiveButton("Edit") { _, _ -> cloudSettings(r, token.text.toString(), p) }
                                .setNegativeButton("Cancel", null).show()
                        }
                    }
                }.start()
            }
            .setNeutralButton("Remove") { _, _ ->
                Prefs.cloudToken = ""; Prefs.cloudRepo = ""; Prefs.cloudPass = ""; Prefs.cloudStatus = ""
                Toast.makeText(a, "Cloud backup removed from this head unit", Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
        token.requestFocus()
    }

    private fun cloudUploadNow() {
        if (!Prefs.cloudConfigured) { cloudSettings("sujithkannimel/car-backups", "", ""); return }
        Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show()
        Thread {
            val cb = com.minimal.carlauncher.core.CloudBackup
            val res = cb.upload(Prefs.cloudRepo, Prefs.cloudToken, cb.manualName(), cb.payload())
            Prefs.cloudStatus = res.message + " (" + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date()) + ")"
            runOnUiThread { Toast.makeText(this, res.message, Toast.LENGTH_LONG).show(); render() }
        }.start()
    }

    private fun cloudRestore() {
        if (!Prefs.cloudConfigured) { cloudSettings("sujithkannimel/car-backups", "", ""); return }
        Toast.makeText(this, "Loading cloud backups...", Toast.LENGTH_SHORT).show()
        Thread {
            val names = com.minimal.carlauncher.core.CloudBackup.list(Prefs.cloudRepo, Prefs.cloudToken)
            runOnUiThread {
                when {
                    names == null -> Toast.makeText(this, "Couldn't reach GitHub", Toast.LENGTH_LONG).show()
                    names.isEmpty() -> Toast.makeText(this, "No cloud backups yet", Toast.LENGTH_LONG).show()
                    else -> {
                        val shown = names.take(30)
                        AlertDialog.Builder(this, DIALOG)
                            .setTitle("Restore which cloud backup?")
                            .setItems(shown.map { it.removePrefix("car_launcher_backup_").removeSuffix(".json").replace("_", "  ") }
                                .toTypedArray()) { _, i -> cloudFetch(shown[i]) }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }
        }.start()
    }

    private fun cloudFetch(name: String) {
        Thread {
            val text = com.minimal.carlauncher.core.CloudBackup.download(Prefs.cloudRepo, Prefs.cloudToken, name)
            runOnUiThread {
                if (text == null) { Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                openBackupText(name, text, Prefs.cloudPass)
            }
        }.start()
    }

    /** Decrypts if needed (asking for the passphrase when the saved one doesn't fit), then confirms. */
    private fun openBackupText(name: String, text: String, pass: String) {
        val crypto = com.minimal.carlauncher.core.BackupCrypto
        val plain = if (crypto.isEncrypted(text)) {
            if (pass.isEmpty()) null else crypto.decrypt(text, pass)
        } else text
        if (plain == null) {
            val f = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Passphrase"
            }
            AlertDialog.Builder(this, DIALOG)
                .setTitle(if (pass.isEmpty()) "Encrypted backup" else "Wrong passphrase")
                .setView(LinearLayout(this).apply { setPadding(px(24), px(8), px(24), 0); addView(f) })
                .setPositiveButton("Open") { _, _ ->
                    val p = f.text.toString()
                    if (p.isEmpty()) return@setPositiveButton
                    openBackupText(name, text, p)
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val parsed = com.minimal.carlauncher.core.Backup.parse(plain)
        if (parsed == null) { Toast.makeText(this, "That file is not a launcher backup", Toast.LENGTH_LONG).show(); return }
        confirmParsed(parsed, name)
    }

    // ================================================================== backup

    private fun backupNow() {
        Thread {
            val name = BackupStore.write(this, auto = false)
            runOnUiThread {
                Toast.makeText(this, if (name != null) "Saved $name in Download/CarLauncher" else "Backup failed",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private val readPermission = android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun restore(askPermission: Boolean) {
        val files = BackupStore.list(this)
        val granted = checkSelfPermission(readPermission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted && askPermission) {
            // Needed to see backups from an earlier install or copied in over ADB.
            requestPermissions(arrayOf(readPermission), REQ_READ)
            return
        }
        if (files.isEmpty()) {
            AlertDialog.Builder(this, DIALOG)
                .setTitle("No backups found")
                .setMessage("Put a backup file (car_launcher_backup_....json) in Download/CarLauncher on the head unit, then try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val shown = files.take(20)
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Restore which backup?")
            .setItems(shown.map { it.name.removePrefix("car_launcher_backup_").removeSuffix(".json").replace("_", "  ") }
                .toTypedArray()) { _, which -> confirmRestore(shown[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRestore(e: BackupStore.Entry) {
        val parsed = BackupStore.read(this, e)
        if (parsed == null) {
            Toast.makeText(this, "That file is not a launcher backup", Toast.LENGTH_LONG).show()
            return
        }
        confirmParsed(parsed, e.name)
    }

    private fun confirmParsed(parsed: com.minimal.carlauncher.core.Backup.Parsed, label: String) {
        val trips = com.minimal.carlauncher.core.TripLog.decode(parsed.values[Prefs.KEY_TRIP_HISTORY] as? String).size
        val fuel = com.minimal.carlauncher.core.FuelEstimator.decode(parsed.values[Prefs.KEY_FUEL_EVENTS] as? String).size
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Restore this backup?")
            .setMessage("$label\n\n$trips trips and $fuel fuel entries. This replaces the current trip history, fuel log and panel settings. The trip in progress keeps counting.")
            .setPositiveButton("Restore") { _, _ ->
                Prefs.restore(parsed)
                // Rebuild the panel with the restored knob and panel settings.
                try {
                    startService(android.content.Intent(this, com.minimal.carlauncher.panel.PanelService::class.java)
                        .setAction(com.minimal.carlauncher.panel.PanelService.ACTION_WAKE))
                } catch (_: Exception) {}
                Toast.makeText(this, "Restored", Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ) restore(askPermission = false)
    }

    private fun liveTrip(now: Long): TripRecord? = Prefs.liveTrip(now)

    // ================================================================== battery

    private fun renderBattery() {
        val bh = com.minimal.carlauncher.core.BatteryHealth
        val days = Prefs.batteryLog
        val v = Prefs.lastVoltage
        val lastRest = days.lastOrNull { it.resting > 0f }
        val lastCharge = days.lastOrNull { it.chargeN > 0 }
        val trend = bh.trendPer30Days(days)

        content.addView(title("Battery"))
        val band = lastRest?.let { bh.restingBand(it.resting) }
        val bandColor = when (band) {
            com.minimal.carlauncher.core.BatteryHealth.Rest.GOOD -> GREEN
            com.minimal.carlauncher.core.BatteryHealth.Rest.OK -> CYAN
            com.minimal.carlauncher.core.BatteryHealth.Rest.WEAK -> AMBER
            com.minimal.carlauncher.core.BatteryHealth.Rest.POOR -> RED
            null -> TEXT
        }
        val chargeOk = lastCharge?.let { it.chargeMin >= bh.CHARGE_LOW && it.chargeMax <= bh.CHARGE_HIGH }
        content.addView(statRow(listOf(
            Stat(if (v > 0f) String.format(Locale.US, "%.1f V", v) else "--", "NOW", CYAN),
            Stat(lastRest?.let { String.format(Locale.US, "%.2f V", it.resting) } ?: "--",
                "RESTING (${band?.label?.uppercase(Locale.US) ?: "NO READING YET"})", bandColor),
            Stat(lastCharge?.let { String.format(Locale.US, "%.1f V", it.chargeAvg) } ?: "--",
                when (chargeOk) { true -> "CHARGING (HEALTHY)"; false -> "CHARGING (CHECK)"; null -> "CHARGING (DRIVE TO MEASURE)" },
                when (chargeOk) { true -> GREEN; false -> AMBER; null -> TEXT }),
            Stat(lastCharge?.let { String.format(Locale.US, "%.1f-%.1f", it.chargeMin, it.chargeMax) } ?: "--",
                "CHARGING RANGE, V (${lastCharge?.day?.let { day.format(java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)!!) } ?: "-"})", TEXT),
        )))

        content.addView(section("Resting voltage at start"))
        val chart = KmplChartView(this).apply {
            emptyText = "Appears after a start with the engine not yet running"
            valueFormat = "%.2f"; axisFormat = "%.1f"; minSpan = 0.6f
            points = days.filter { it.resting > 0f }.takeLast(30).map { d ->
                (bh.dayNumber(d.day)?.let { day.format(Date(it * 86_400_000L)) } ?: "") to d.resting
            }
        }
        content.addView(card(chart, px(140)))
        content.addView(body("Trend: " + bh.trendLabel(trend)))

        content.addView(section("Charging while driving (daily average)"))
        val chg = KmplChartView(this).apply {
            emptyText = "Appears after driving with the engine running"
            valueFormat = "%.1f"; axisFormat = "%.1f"; minSpan = 1.0f
            points = days.filter { it.chargeN > 0 }.takeLast(30).map { d ->
                (bh.dayNumber(d.day)?.let { day.format(Date(it * 86_400_000L)) } ?: "") to d.chargeAvg
            }
        }
        content.addView(card(chg, px(140)))
        content.addView(hint("Healthy: resting 12.4 V or more; charging 13.2 - 15.0 V. Read at the head unit's power wire, " +
            "about 0.1 - 0.2 V below the battery terminals. For the most telling resting value, check the first start of the day."))
    }

    private fun totalStat(t: com.minimal.carlauncher.core.TripTotals, label: String) =
        Stat(String.format(Locale.US, "%.0f km", t.km), "$label · ${dur(t.driveSec)}", TEXT)

    // ================================================================== export

    private fun export() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val trips = buildString {
            append("start,end,km,drive_min,idle_min,avg_kmh,top_kmh\n")
            Prefs.tripHistory.forEach { t ->
                append(String.format(Locale.US, "%s,%s,%.2f,%.1f,%.1f,%.1f,%.1f\n",
                    iso.format(Date(t.start)), iso.format(Date(t.end)), t.km,
                    t.driveSec / 60.0, t.idleSec / 60.0, t.avgKmh, t.maxKmh))
            }
        }
        val fuel = buildString {
            append("date,entry,litres,odometer_km,odometer_estimated,km_since_previous,kmpl_measured\n")
            Prefs.fuelReplay().rows.forEach { r ->
                val e = r.event
                append(String.format(Locale.US, "%s,%s,%s,%s,%s,%s,%s\n",
                    if (e.time > 0) iso.format(Date(e.time)) else "",
                    when (e.kind) { FuelEvent.KIND_SETUP -> "setup"; FuelEvent.KIND_ODO -> "odometer"; else -> if (e.full) "full" else "top-up" },
                    if (e.litres > 0f) String.format(Locale.US, "%.2f", e.litres) else "",
                    if (r.odoAt >= 0) r.odoAt.toString() else "",
                    if (r.odoEstimated) "yes" else "no",
                    if (r.kmSincePrev >= 0) String.format(Locale.US, "%.1f", r.kmSincePrev) else "",
                    if (r.measuredKmpl > 0f) String.format(Locale.US, "%.2f", r.measuredKmpl) else ""))
            }
        }
        val ok = save("car_trips_$stamp.csv", trips) && save("car_fuel_$stamp.csv", fuel)
        Toast.makeText(this, if (ok) "Saved to Download/CarLauncher" else "Export failed", Toast.LENGTH_LONG).show()
    }

    private fun save(name: String, text: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/CarLauncher")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri != null && (contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()); true } ?: false)
    } catch (_: Exception) {
        false
    }

    // ================================================================== UI helpers

    private data class Stat(val value: String, val label: String, val color: Int)

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int = STROKE, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat(); setStroke(px(1), stroke)
    }

    private fun railButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        background = rounded(CARD)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun styleRail(v: TextView, active: Boolean) {
        v.background = rounded(if (active) Color.argb(50, 34, 211, 238) else CARD, if (active) CYAN else STROKE)
        v.setTextColor(if (active) CYAN else TEXT)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD; setPadding(px(4), 0, 0, px(10))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.DEFAULT_BOLD; setPadding(px(4), px(18), 0, px(8))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(px(4), px(4), 0, px(8))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(px(4), px(8), 0, 0)
    }

    private fun card(child: View, height: Int) = FrameLayout(this).apply {
        background = rounded(CARD)
        setPadding(px(8), px(8), px(8), px(8))
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private fun statRow(stats: List<Stat>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.forEachIndexed { i, s ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD)
                setPadding(px(14), px(12), px(14), px(12))
            }
            box.addView(TextView(this).apply {
                text = s.value; setTextColor(s.color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            })
            box.addView(TextView(this).apply {
                text = s.label; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(4), 0, 0)
            })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = px(10)
            })
        }
        return row
    }

    private fun buttonRow(buttons: List<Pair<String, () -> Unit>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(12), 0, 0)
        }
        buttons.forEachIndexed { i, (label, action) ->
            row.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; setTextColor(CYAN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
                background = rounded(Color.argb(30, 34, 211, 238), Color.argb(140, 34, 211, 238), 12)
                setPadding(px(18), 0, px(18), 0)
                isClickable = true
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48)).apply {
                if (i > 0) leftMargin = px(10)
            })
        }
        return row
    }

    private fun logRow(whenTxt: String, what: String, detail: String, right: String, onClick: (() -> Unit)?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, STROKE, 10)
            setPadding(px(14), px(10), px(14), px(10))
            if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = if (what.isEmpty()) whenTxt else "$what  ·  $whenTxt"
            setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
        })
        if (detail.isNotEmpty()) left.addView(TextView(this).apply {
            text = detail; setTextColor(DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, px(2), 0, 0)
        })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right.isNotEmpty()) row.addView(TextView(this).apply {
            text = right; setTextColor(CYAN); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, px(8))
            addView(row)
        }
    }

    private fun dur(sec: Long): String {
        val m = (sec / 60).coerceAtLeast(0)
        return if (m < 60) "$m min" else String.format(Locale.US, "%dh %02dm", m / 60, m % 60)
    }

    private fun speed(kmh: Float) = if (kmh <= 0f) "--" else String.format(Locale.US, "%.0f km/h", kmh)

    private fun fmt0(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(Locale.US, "%.1f", f)
}
