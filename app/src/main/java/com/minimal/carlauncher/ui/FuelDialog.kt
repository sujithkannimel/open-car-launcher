package com.minimal.carlauncher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.minimal.carlauncher.core.FuelEstimator
import com.minimal.carlauncher.core.FuelEvent
import com.minimal.carlauncher.core.Prefs
import java.util.Locale

/**
 * Fuel set-up, fill-up, odometer-correction and edit dialogs. Every save adds or changes a
 * [FuelEvent]; the fuel state is rebuilt from the list, so edits and deletes are exact.
 */
object FuelDialog {

    const val EXTRA_FUEL = "open_fuel"

    private const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** Opens the Trips & fuel screen (fuel tab). */
    fun show(a: Activity) {
        a.startActivity(Intent(a, TripsActivity::class.java).putExtra(TripsActivity.EXTRA_TAB, TripsActivity.TAB_FUEL))
    }

    // ------------------------------------------------------------------ set-up

    fun showSetup(a: Activity, onDone: () -> Unit = {}) {
        val cur = Prefs.fuelState
        val col = column(a)
        col.addView(label(a, "Tank size (litres)"))
        val tank = number(a, fmt(cur.tankL), decimal = true)
        col.addView(tank)
        col.addView(label(a, "Rough mileage (km per litre) - it corrects itself"))
        val kmpl = number(a, fmt(cur.kmpl), decimal = true)
        col.addView(kmpl)
        col.addView(label(a, "How full is the tank right now?"))
        val levels = listOf("Full" to 1f, "3/4" to 0.75f, "Half" to 0.5f, "1/4" to 0.25f, "Reserve" to 0.1f)
        val group = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        levels.forEachIndexed { i, (name, _) ->
            group.addView(RadioButton(a).apply { text = name; id = 100 + i; isChecked = i == 0 })
        }
        col.addView(group)
        col.addView(label(a, "Odometer km (optional)"))
        val odo = number(a, "", decimal = false)
        col.addView(odo)

        AlertDialog.Builder(a, THEME)
            .setTitle("Fuel set-up")
            .setMessage("This starts the fuel log again from now.")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                val t = tank.text.toString().toFloatOrNull()
                val k = kmpl.text.toString().toFloatOrNull()
                if (t == null || k == null) {
                    toast(a, "Enter tank size and mileage")
                    return@setPositiveButton
                }
                val frac = levels[(group.checkedRadioButtonId - 100).coerceIn(0, levels.lastIndex)].second
                // A new set-up starts a fresh log; keep earlier odometer readings for the estimate.
                Prefs.fuelEvents = listOf(
                    FuelEvent(
                        FuelEvent.KIND_SETUP, System.currentTimeMillis(), Prefs.gpsOdoKm,
                        odo = odo.text.toString().toIntOrNull() ?: -1,
                        tankL = t, kmpl = k, fraction = frac,
                    )
                )
                toast(a, "Fuel set up")
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ fill-up

    fun showFill(a: Activity, onDone: () -> Unit = {}) {
        if (!Prefs.fuelState.configured) { showSetup(a, onDone); return }
        val gps = Prefs.gpsOdoKm
        val r = Prefs.fuelReplay()
        val s = r.state
        val col = column(a)
        val m = Prefs.fuelModel()
        col.addView(TextView(a).apply {
            text = String.format(
                Locale.US, "About %d km left  -  %.1f L  -  %.1f km/l",
                m.rangeKm, m.litresLeft, m.kmplNow
            )
            setTextColor(Color.rgb(34, 211, 238))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(a, 12))
        })
        val group = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
        val full = RadioButton(a).apply { text = "Filled to full"; id = 1; isChecked = true }
        val part = RadioButton(a).apply { text = "Added some litres"; id = 2 }
        group.addView(full); group.addView(part)
        col.addView(group)
        col.addView(label(a, "Litres from the pump (optional for full - helps it learn)"))
        val litres = number(a, "", decimal = true)
        col.addView(litres)
        val est = r.estimatedOdo(gps)
        col.addView(label(a, if (est >= 0) "Odometer km (optional - about $est by GPS)" else "Odometer km (optional - more accurate than GPS)"))
        val odo = number(a, "", decimal = false)
        col.addView(odo)

        AlertDialog.Builder(a, THEME)
            .setTitle("Log fill-up")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                val isFull = group.checkedRadioButtonId == 1
                val l = litres.text.toString().toFloatOrNull()
                if (!isFull && (l == null || l <= 0f)) {
                    toast(a, "Enter the litres you added")
                    return@setPositiveButton
                }
                Prefs.addFuelEvent(
                    FuelEvent(
                        FuelEvent.KIND_FILL, System.currentTimeMillis(), Prefs.gpsOdoKm,
                        odo = odo.text.toString().toIntOrNull() ?: -1,
                        litres = l ?: -1f, full = isFull,
                    )
                )
                toast(a, String.format(Locale.US, "Saved - about %d km range", Prefs.fuelModel().rangeKm))
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ odometer

    fun showCorrectOdometer(a: Activity, onDone: () -> Unit = {}) {
        if (!Prefs.fuelState.configured) { showSetup(a, onDone); return }
        val est = Prefs.fuelReplay().estimatedOdo(Prefs.gpsOdoKm)
        val col = column(a)
        col.addView(label(a, if (est >= 0) "Odometer now (GPS thinks about $est km)" else "Odometer now (km)"))
        val odo = number(a, "", decimal = false)
        col.addView(odo)
        AlertDialog.Builder(a, THEME)
            .setTitle("Correct odometer")
            .setMessage("Use the reading on your instrument cluster. The range is re-based on it, fixing any distance GPS missed.")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                val o = odo.text.toString().toIntOrNull()
                if (o == null || o <= 0) { toast(a, "Enter the odometer reading"); return@setPositiveButton }
                Prefs.addFuelEvent(FuelEvent(FuelEvent.KIND_ODO, System.currentTimeMillis(), Prefs.gpsOdoKm, odo = o))
                toast(a, String.format(Locale.US, "Saved - about %d km range", Prefs.fuelModel().rangeKm))
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ edit / delete

    fun showEdit(a: Activity, index: Int, onDone: () -> Unit = {}) {
        val events = Prefs.fuelEvents
        val e = events.getOrNull(index) ?: return
        if (e.kind == FuelEvent.KIND_SETUP) { showSetup(a, onDone); return }
        val col = column(a)
        var group: RadioGroup? = null
        var litres: EditText? = null
        if (e.kind == FuelEvent.KIND_FILL) {
            group = RadioGroup(a).apply { orientation = RadioGroup.HORIZONTAL }
            group.addView(RadioButton(a).apply { text = "Filled to full"; id = 1; isChecked = e.full })
            group.addView(RadioButton(a).apply { text = "Added some litres"; id = 2; isChecked = !e.full })
            col.addView(group)
            col.addView(label(a, "Litres from the pump"))
            litres = number(a, if (e.litres > 0f) fmt(e.litres) else "", decimal = true)
            col.addView(litres)
        }
        col.addView(label(a, "Odometer km"))
        val odo = number(a, if (e.odo >= 0) e.odo.toString() else "", decimal = false)
        col.addView(odo)

        fun save(list: List<FuelEvent>) { Prefs.fuelEvents = list; onDone() }

        AlertDialog.Builder(a, THEME)
            .setTitle(if (e.kind == FuelEvent.KIND_FILL) "Edit fill-up" else "Edit odometer entry")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                val o = odo.text.toString().toIntOrNull() ?: -1
                val updated = if (e.kind == FuelEvent.KIND_FILL) {
                    val isFull = group?.checkedRadioButtonId == 1
                    val l = litres?.text.toString().toFloatOrNull() ?: -1f
                    if (!isFull && l <= 0f) { toast(a, "Enter the litres you added"); return@setPositiveButton }
                    e.copy(full = isFull, litres = l, odo = o)
                } else {
                    if (o <= 0) { toast(a, "Enter the odometer reading"); return@setPositiveButton }
                    e.copy(odo = o)
                }
                save(events.toMutableList().also { it[index] = updated })
                toast(a, "Updated")
            }
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(a, THEME)
                    .setMessage("Delete this entry?")
                    .setPositiveButton("Delete") { _, _ ->
                        save(events.toMutableList().also { it.removeAt(index) })
                        toast(a, "Deleted")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ petrol pump

    /** Opens Google Maps (or any map app) searching for nearby petrol pumps. */
    fun findPetrolPump(a: Activity) {
        val uri = Uri.parse("geo:0,0?q=" + Uri.encode("petrol pump"))
        val maps = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try {
            a.startActivity(maps)
        } catch (_: Exception) {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast(a, "No map app found") }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun column(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(a, 24), dp(a, 12), dp(a, 24), 0)
    }

    private fun label(a: Activity, t: String) = TextView(a).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(a, 10), 0, dp(a, 2))
    }

    private fun number(a: Activity, value: String, decimal: Boolean) = EditText(a).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or
            (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        setText(value)
        setSelectAllOnFocus(true)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(Locale.US, "%.1f", f)

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
}
