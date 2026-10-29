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

        private val BG = Color.rgb(13, 17, 23)
        private val CARD = Color.rgb(22, 27, 34)
        private val STROKE = Color.rgb(48, 54, 61)
        private val DIM = Color.rgb(130, 150, 170)
        private val TEXT = Color.rgb(225, 232, 240)
        private val CYAN = Color.rgb(34, 211, 238)
        private val GREEN = Color.rgb(74, 222, 128)
        private val AMBER = Color.rgb(251, 191, 36)
        private val RED = Color.rgb(244, 63, 94)
    }

    private lateinit var content: LinearLayout
    private lateinit var tabFuel: TextView
    private lateinit var tabTrips: TextView
    private var tab = TAB_FUEL
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (tab == TAB_TRIPS) render()
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
        rail.addView(tabFuel, LinearLayout.LayoutParams(px(130), px(56)).apply { bottomMargin = px(10) })
        rail.addView(tabTrips, LinearLayout.LayoutParams(px(130), px(56)))
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
        render()
    }

    private fun render() {
        content.removeAllViews()
        if (tab == TAB_FUEL) renderFuel() else renderTrips()
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

        val range = FuelEstimator.rangeKm(s, gps)
        val level = FuelEstimator.level(s, gps)
        val rangeColor = when (level) { 4 -> RED; 3 -> AMBER; else -> GREEN }
        val learned = r.tanks.size
        val est = r.estimatedOdo(gps)

        content.addView(title("Fuel"))
        content.addView(statRow(listOf(
            Stat("≈ $range km", "RANGE LEFT", rangeColor),
            Stat(String.format(Locale.US, "%.1f L", FuelEstimator.litresLeft(s, gps)), "IN TANK (OF ${fmt0(s.tankL)} L)", TEXT),
            Stat(String.format(Locale.US, "%.1f km/l", s.kmpl),
                if (learned == 0) "MILEAGE (YOUR ESTIMATE)" else "MILEAGE (LEARNED, $learned TANK${if (learned == 1) "" else "S"})", CYAN),
            Stat(if (est >= 0) String.format(Locale.US, "%,d km", est) else "--", "ODOMETER (BY GPS)", TEXT),
        )))
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
            )
            content.addView(logRow(dayTime.format(Date(t.start)), "", parts.joinToString("   "),
                if (t.idleSec > 60) "idle " + dur(t.idleSec) else "", null))
        }
        content.addView(hint("Distance and speed come from the head unit's GPS. Trips start when the ignition goes on."))
    }

    private fun liveTrip(now: Long): TripRecord? {
        val start = Prefs.tripStart
        if (start <= 0L) return null
        val km = (Prefs.gpsOdoKm - Prefs.curTripGpsStart).coerceAtLeast(0.0)
        return TripRecord(start, now, km, Prefs.curTripDriveSec, Prefs.curTripIdleSec, Prefs.curTripMaxKmh)
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
