package com.minimal.carlauncher.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Neon
import com.minimal.carlauncher.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Overlay panel service that draws the bottom strip in the 170 px black area
 * between the two physical knobs on the K2401 head unit.
 *
 * **Visual design** (from feature/modern-knob-panel):
 * - Left Knob: White circular aperture with bold volume digit + curved VOL -/+ arc.
 * - Center: Battery voltage pill (cyan), quick-launcher gradient squircle buttons.
 * - Right Knob: White circular aperture with play/pause + curved Track Next/Prev arc.
 * - Clock: Lower-right with large text.
 *
 * **Positioning (proven and mandatory):**
 * - Screen: 1280x720 px at 160 dpi
 * - Firmware limits app windows to 550 px high, leaving a 170 px black strip
 * - The overlay uses Gravity.TOP | Gravity.LEFT with y = screenHeight - panelHeight
 * - DO NOT use Gravity.BOTTOM -- it lands too high due to the 550 px window limit
 * - Side padding: ~200 dp for the physical dual knobs
 *
 * **Lifecycle:**
 * This service is started by HomeActivity in its onResume.
 * Since the launcher is the HOME app, Android always restarts it after ACC on, which in turn
 * restarts this service. The NWD firmware force-stops all third-party apps on ACC off and sets
 * stopped=true, blocking all broadcasts -- but a HOME activity start is not a broadcast, so
 * this bypass works where BOOT_COMPLETED / ACC_ON receivers cannot.
 */
class PanelService : Service() {

    private var wm: WindowManager? = null
    private var am: AudioManager? = null
    private var panel: View? = null
    private var handle: View? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var hidden = false
    private var dens = 1f

    // Live-updated views
    private var knobsView: KnobsView? = null
    private var batteryDisplay: TextView? = null
    private var adjusting = false
    private var adjustTarget = 0          // 0 both (mirrored), 1 left, 2 right
    private var adjustReadout: TextView? = null
    private var isPlayingState = false
    private var lastReportedVolume = -1

    // Reverse camera: panel is removed while mcu_backcar_state != 0.
    private var reversing = false

    // Neon battery pill
    private var batteryBadgeView: View? = null
    private var battLabelView: TextView? = null
    private var tripView: TextView? = null
    /** The strip's neon top line: flashes amber / red for a camera while another app is in front. */
    private var stripLine: View? = null
    private var pulseAnim: android.animation.ObjectAnimator? = null
    private var shownBattState = -1
    private var pendingBattState = -1
    private var pendingSeqStart = 0
    private var voltSeq = 0          // bumps on every fresh reading
    private var lastHeartbeatMs = 0L

    // Fuel chip + GPS odometer
    private var fuelBadgeView: View? = null
    private var fuelText: TextView? = null
    private var fuelIcon: android.widget.ImageView? = null
    private var fuelLabel: TextView? = null
    private var shownFuelKey = ""

    // Weather (plain icon + temperature on the status line)
    private var weatherIcon: WeatherIconView? = null
    private var weatherTemp: TextView? = null
    private var weather: Weather? = null
    @Volatile private var weatherFetching = false

    /** elapsedRealtime of the current ignition (new trip), 0 = not seen since the service started. */
    private var ignitionElapsed = 0L
    private var lastWakeLogMs = 0L

    // The parts that do the work; this service wires them to the panel views.
    private val volts by lazy { VoltageReader(this) }
    private val battery by lazy {
        BatteryMonitor(this, handler, { ignitionElapsed }, { trips.movingNow() }) {
            shownBattState = -1          // re-style the pill
            updateBatteryDisplay()
        }
    }
    private val trips by lazy {
        TripTracker(this, { volts.engineMaybeRunning() }, { handler.post { liveLinkTick() } }) { cameras.onFix(it); updateCamSlot() }
    }
    private val cameras by lazy { CameraWatch(this) }
    private var flashSeen = 0
    private val cameraChanged: () -> Unit = {
        updateBatteryDisplay()
        val CS = com.minimal.carlauncher.core.CameraStore
        // A new camera or the final warning: flash the strip's top line - but only over other
        // apps (Android Auto); the home screen flashes its own cards.
        val otherApp = CS.current != null && ZlinkGuard.topApp(this) != packageName
        stripGlow(if (otherApp) (if (CS.over) Neon.RED else Neon.AMBER) else null)
        if (CS.flashSeq != flashSeen) {
            flashSeen = CS.flashSeq
            if (otherApp) flashStrip()
            if (CS.current != null) camSlotBlink()
        }
    }

    /** Phone GPS while the car's own has no fix: checked every 5 s. */
    private var ownFixChecks = 0
    private val phoneGpsCheck = object : Runnable {
        override fun run() {
            val pg = com.minimal.carlauncher.core.PhoneGps
            if (pg.ownFresh()) {
                if (pg.streaming && ++ownFixChecks >= 2) { pg.stop(); log("phone gps: car's own GPS has a fix") }
            } else {
                ownFixChecks = 0
                if (!pg.streaming && pg.available && trips.isListening)
                    pg.start({ l -> handler.post { trips.onPhoneFix(l) } }) { msg -> handler.post { log(msg) } }
            }
            updateCamSlot()                              // speed goes to "--" when fixes stop
            handler.postDelayed(this, 5_000L)
        }
    }
    private val zlink by lazy { ZlinkGuard(this, handler, { ignitionElapsed }, { reversing }) }
    private val cloud by lazy { CloudSync(this, handler) { onPhoneFillsAdded() } }

    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            checkReverse()
            zlink.check()
            checkViews()
            updateVolumeDisplay()
            updateBatteryDisplay()
            updateFuelDisplay()
            tripHeartbeat()
            maybeRefreshWeather()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // Periodic refresh for volume (AudioManager has no broadcast for volume changes on all ROMs)
    private val volumeWatcher = object : Runnable {
        override fun run() {
            updateVolumeDisplay()
            handler.postDelayed(this, VOLUME_POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        val action = intent?.action

        // Foreground notification -- MIN importance so it's silent and barely visible.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bottom panel", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bottom panel is running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        )

        if (!Settings.canDrawOverlays(this)) {
            log("overlay permission missing -- stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        AdbTunnel.log = { msg -> handler.post { log(msg) } }
        dens = resources.displayMetrics.density

        registerScreenReceiver()
        registerBatteryReceiver()
        registerVolumeReceiver()
        registerVolumeObserver()
        zlink.register()
        com.minimal.carlauncher.core.PhoneMedia.kick = { handler.post { liveLinkTick() } }
        com.minimal.carlauncher.core.ZlinkStatus.addListener(playSync)
        com.minimal.carlauncher.core.PhoneMedia.addListener(playSync)
        com.minimal.carlauncher.core.CameraStore.removeListener(cameraChanged)
        com.minimal.carlauncher.core.CameraStore.addListener(cameraChanged)
        handler.removeCallbacks(phoneGpsCheck)
        handler.postDelayed(phoneGpsCheck, 5_000L)
        keepAdbOn()
        lastHeartbeatMs = 0L
        tripHeartbeat()
        trips.start()
        if (weather == null) {
            weather = Prefs.weatherJson.takeIf { it.isNotEmpty() && System.currentTimeMillis() - Prefs.weatherAt < WEATHER_STALE_MS }
                ?.let { WeatherApi.parse(it) }
        }
        reversing = try { Settings.System.getInt(contentResolver, BACKCAR_KEY, 0) != 0 } catch (_: Exception) { false }

        // Wake action: always force-rebuild (called after ACC on via the launcher).
        if (action == ACTION_WAKE) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastWakeLogMs > 3 * 60_000L) {
                lastWakeLogMs = nowMs
                log("wake -- force rebuilding panel")
            }
            hidden = false
            build()
            restartWatchdog()
            startVolumeWatcher()
            zlink.scheduleChecks()
            return START_STICKY
        }

        // Ping (keep-alive): only rebuild if views are gone.
        if (action == ACTION_PING) {
            val ok = if (hidden) handle?.isAttachedToWindow == true
            else panel?.isAttachedToWindow == true
            if (ok) return START_STICKY
            log("ping -- views missing, rebuilding")
        }

        log(
            when {
                intent == null -> "service restarted by system"
                action == ACTION_PING -> "revived by keep-alive"
                else -> "service started"
            }
        )
        hidden = false
        build()
        restartWatchdog()
        startVolumeWatcher()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(volumeWatcher)
        unregisterScreenReceiver()
        unregisterBatteryReceiver()
        unregisterVolumeReceiver()
        unregisterVolumeObserver()
        zlink.unregister()
        battery.stop()
        trips.stop()
        com.minimal.carlauncher.core.CameraStore.removeListener(cameraChanged)
        handler.removeCallbacks(phoneGpsCheck)
        com.minimal.carlauncher.core.PhoneGps.stop()
        cameras.release()
        clear()
        log("service destroyed")
        super.onDestroy()
    }

    // --------------------------------------------------------------------- view management

    private fun build() {
        clear()
        val real = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(real) ?: return

        if (reversing) return   // reverse camera owns the screen
        if (hidden) {
            addHandle(real)
        } else {
            addModernPanel(real)
        }
    }

    /**
     * Builds the full-width modern panel matching the dual-knob automotive head unit.
     *
     * Layout (FrameLayout, absolute positioning):
     * - KnobsView (full strip): white disc on each knob centre + concentric arc (VOL -/+,
     *   Track Next/Prev). Geometry from Prefs, tuned via long-press on the battery pill.
     * - Battery pill: just past the left arc. Long-press it for knob adjust mode.
     * - Center buttons: Home, Apps, Nav, Phone (gradient squircle, 62dp)
     */
    private fun addModernPanel(real: DisplayMetrics) {
        val panelHeightPx = Prefs.panelHeight    // stored in px, default 170
        val h = panelHeightPx

        val root = FrameLayout(this).apply {
            clipChildren = false
            setBackgroundColor(if (Prefs.panelTransparent) PANEL_BG_TRANSPARENT else PANEL_BG)
        }

        // Top hairline divider for clean cockpit depth
        val topDivider = View(this).apply {
            // Faint neon hairline: cyan -> violet -> pink, the panel's signature accent.
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                Neon.GRADIENT.map { Neon.alpha(it, 150) }.toIntArray())
        }
        root.addView(topDivider, FrameLayout.LayoutParams(M, px(1.5f), Gravity.TOP))
        stripLine = topDivider

        // 1+2. KNOBS: discs + concentric arcs, one shared geometry (see KnobsView / Prefs).
        val knobs = KnobsView(
            context = this,
            leftTop = "\u2212",
            leftBottom = "+",
            rightTop = "\u25B6|",
            rightBottom = "|\u25C0",
            onLeftTop = { adjustVolume(up = false) },
            onLeftBottom = { adjustVolume(up = true) },
            onRightTop = { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) },
            onRightBottom = { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
            onLeftDisc = {
                val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                updateVolumeDisplay()
            },
            onRightDisc = {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                isPlayingState = !isPlayingState
                updatePlayPauseState()
            },
        ).apply {
            leftText = getCurrentVolumeDisplay()
            rightText = playGlyph()
            showGuides = adjusting
        }
        knobsView = knobs
        root.addView(knobs, FrameLayout.LayoutParams(M, M))

        // Side clusters sit just past each arc's labels, so they follow any recalibration.
        val sideLeft = Prefs.knobLeftX + Prefs.knobArcRadius + 46

        // 3. LOWER LEFT: Battery Voltage Pill Badge
        val leftCluster = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(px(6f), px(10f), px(10f), px(10f))
            // Let the pill's neon halo spill outside its bounds.
            clipChildren = false
            clipToPadding = false
        }

        val batteryBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = px(14f).toFloat() }
            elevation = px(8f).toFloat()
            setPadding(px(14f), px(6f), px(14f), px(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val curV = volts.display()
                Toast.makeText(this@PanelService, "Voltage $curV\nsource: ${volts.source}", Toast.LENGTH_LONG).show()
                updateBatteryDisplay()
            }
            // Long-press the pill to line the knob graphics up with the physical knobs.
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                adjusting = true
                build()
                true
            }
        }

        val voltText = TextView(this).apply {
            text = "\u26A1 " + volts.display()
            setTextColor(Color.rgb(0, 240, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        batteryDisplay = voltText
        batteryBadge.addView(voltText)

        val battSubtitle = TextView(this).apply {
            text = "BATTERY"
            setTextColor(Neon.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            includeFontPadding = false
        }
        val trip = TextView(this).apply {
            text = trips.tripText()
            setTextColor(Neon.TEXT_2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setPadding(px(4f), px(4f), px(4f), px(4f))
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                openTrips("trips")
            }
        }
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6f), px(6f), 0, 0)
            addView(battSubtitle)
            addView(trip, LinearLayout.LayoutParams(W, W).apply { leftMargin = px(12f) })
            // Weather: phone-style icon + temperature, plain (no pill). Tap = quick summary.
            val wIcon = WeatherIconView(this@PanelService)
            val wTemp = TextView(this@PanelService).apply {
                setTextColor(Neon.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
            val wBox = LinearLayout(this@PanelService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(4f), px(2f), px(4f), px(2f))
                isClickable = true
                setOnClickListener { showWeatherSummary() }
                addView(wIcon, LinearLayout.LayoutParams(px(24f), px(24f)).apply { rightMargin = px(5f) })
                addView(wTemp)
            }
            addView(wBox, LinearLayout.LayoutParams(W, W).apply { leftMargin = px(10f) })
            weatherIcon = wIcon
            weatherTemp = wTemp
        }
        leftCluster.addView(batteryBadge, LinearLayout.LayoutParams(W, W))
        leftCluster.addView(subRow, LinearLayout.LayoutParams(W, W))
        batteryBadgeView = batteryBadge
        battLabelView = battSubtitle
        tripView = trip
        applyWeather()
        shownBattState = -1          // force re-style on the new views
        applyBatteryState(classify(currentVolts()))

        val leftClusterParams = FrameLayout.LayoutParams(W, W, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = sideLeft
        }
        if (!adjusting) root.addView(leftCluster, leftClusterParams)

        // 3b. RIGHT: fuel range chip (software estimate; tap to set up / log a fill-up)
        val sideRight = Prefs.knobRightX + Prefs.knobArcRadius + 46
        val rightCluster = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(px(10f), px(10f), px(6f), px(10f))
            clipChildren = false
            clipToPadding = false
        }
        val fuelBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = px(14f).toFloat() }
            elevation = px(8f).toFloat()
            setPadding(px(12f), px(6f), px(14f), px(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                openFuelDialog()
            }
        }
        val fIcon = android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_panel_fuel) }
        fuelBadge.addView(fIcon, LinearLayout.LayoutParams(px(20f), px(20f)).apply { rightMargin = px(8f) })
        val fText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        fuelBadge.addView(fText)
        val fLabel = TextView(this).apply {
            setTextColor(Neon.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.14f
            includeFontPadding = false
            setPadding(0, px(6f), px(6f), 0)
        }
        rightCluster.addView(fuelBadge, LinearLayout.LayoutParams(W, W).apply { gravity = Gravity.END })
        rightCluster.addView(fLabel, LinearLayout.LayoutParams(W, W).apply { gravity = Gravity.END })
        fuelBadgeView = fuelBadge; fuelText = fText; fuelIcon = fIcon; fuelLabel = fLabel
        shownFuelKey = ""
        updateFuelDisplayNow()
        if (!adjusting) root.addView(rightCluster, FrameLayout.LayoutParams(W, W, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = sideRight
        })

        // 4. CENTER: Quick-Launcher Buttons (gradient squircle)
        val centerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        dockTiles.clear()
        val btnHome = dockButton(R.drawable.ic_panel_home, "Home") { openHomeDashboard() }
        val btnApps = dockButton(R.drawable.ic_panel_apps, "Apps") { openAppGrid() }
        val btnNav = dockButton(R.drawable.ic_panel_nav, "Maps") { openMaps() }
        shownDockActive = -2
        updateDockActive()

        for (b in listOf(btnHome, btnApps, btnNav)) {
            centerBar.addView(b, LinearLayout.LayoutParams(W, W).apply {
                leftMargin = px(10f); rightMargin = px(10f)
            })
        }
        // Where the Phone button was: speed, or the camera warning while one is ahead.
        centerBar.addView(buildCamSlot(), LinearLayout.LayoutParams(W, W).apply { leftMargin = px(10f); rightMargin = px(10f) })

        if (adjusting) {
            root.addView(buildAdjustBar(), FrameLayout.LayoutParams(W, h, Gravity.CENTER))
        } else {
            root.addView(centerBar, FrameLayout.LayoutParams(W, h, Gravity.CENTER))
        }

        try {
            wm?.addView(root, overlayParams(M, panelHeightPx, real.heightPixels - panelHeightPx))
            panel = root
        } catch (e: Exception) {
            log("panel addView failed: $e")
        }
    }

    // --------------------------------------------------------------------- knob adjust mode

    private fun playGlyph() = if (isPlayingState) "\u275A\u275A" else "\u25B6"

    /**
     * Replaces the centre buttons with nudge controls while adjusting. The discs sit under
     * the physical knobs where touch doesn't reach, so everything is moved with buttons.
     * Each press is 2 px; holding repeats. Values save immediately.
     */
    private fun buildAdjustBar(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val readout = TextView(this).apply {
            setTextColor(Neon.PINK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        adjustReadout = readout
        refreshReadout()
        col.addView(readout, LinearLayout.LayoutParams(W, W).apply { bottomMargin = px(6f) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun targetText() = when (adjustTarget) { 1 -> "LEFT"; 2 -> "RIGHT"; 3 -> "VOL"; 4 -> "PLAY"; else -> "BOTH" }
        row.addView(adjustButton(targetText(), wide = true, repeat = false) {
            adjustTarget = (adjustTarget + 1) % 5
            (it as TextView).text = targetText()
            refreshReadout()
        })
        row.addView(adjustButton("\u25C0") { nudge(dx = -2) })
        row.addView(adjustButton("\u25B2") { nudge(dy = -2) })
        row.addView(adjustButton("\u25BC") { nudge(dy = 2) })
        row.addView(adjustButton("\u25B6") { nudge(dx = 2) })
        row.addView(adjustButton("\u25CB\u2212") { Prefs.knobDisc = (Prefs.knobDisc - 2).coerceAtLeast(40); knobChanged() })
        row.addView(adjustButton("\u25CB+") { Prefs.knobDisc = (Prefs.knobDisc + 2).coerceAtMost(320); knobChanged() })
        row.addView(adjustButton("\u25E0\u2212") { Prefs.knobArcRadius = (Prefs.knobArcRadius - 2).coerceAtLeast(40); knobChanged() })
        row.addView(adjustButton("\u25E0+") { Prefs.knobArcRadius = (Prefs.knobArcRadius + 2).coerceAtMost(260); knobChanged() })
        row.addView(adjustButton("RESET", wide = true, repeat = false) { Prefs.resetKnobGeometry(); knobChanged() })
        row.addView(adjustButton("DONE", wide = true, repeat = false) {
            adjusting = false
            build()
        })
        col.addView(row)
        return col
    }

    /**
     * dx is in screen terms for LEFT / RIGHT (positive = move right). With BOTH selected the
     * knobs move as a mirrored pair: right-arrow moves both toward the screen centre, left-arrow both toward
     * their edges. Prefs.knobRightX is measured from the right edge.
     */
    private fun nudge(dx: Int = 0, dy: Int = 0) {
        // VOL / PLAY: move only the number or icon inside its lens (screen direction).
        if (adjustTarget == 3 || adjustTarget == 4) {
            if (adjustTarget == 3) {
                Prefs.volDx = (Prefs.volDx + dx).coerceIn(-80, 80)
                Prefs.volDy = (Prefs.volDy + dy).coerceIn(-80, 80)
            } else {
                Prefs.playDx = (Prefs.playDx + dx).coerceIn(-80, 80)
                Prefs.playDy = (Prefs.playDy + dy).coerceIn(-80, 80)
            }
            knobChanged()
            return
        }
        if (adjustTarget != 2) {
            Prefs.knobLeftX = (Prefs.knobLeftX + dx).coerceIn(-100, 400)
            Prefs.knobLeftY = (Prefs.knobLeftY + dy).coerceIn(-100, 300)
        }
        if (adjustTarget != 1) {
            // BOTH: mirrored (same distance from each edge). RIGHT alone: screen direction.
            val rx = if (adjustTarget == 0) dx else -dx
            Prefs.knobRightX = (Prefs.knobRightX + rx).coerceIn(-100, 400)
            Prefs.knobRightY = (Prefs.knobRightY + dy).coerceIn(-100, 300)
        }
        knobChanged()
    }

    private fun knobChanged() {
        knobsView?.invalidate()
        refreshReadout()
    }

    private fun refreshReadout() {
        adjustReadout?.text = String.format(
            Locale.US, "L x%d y%d   R x%d y%d   disc %d   arc %d   vol %+d,%+d   play %+d,%+d",
            Prefs.knobLeftX, Prefs.knobLeftY, Prefs.knobRightX, Prefs.knobRightY,
            Prefs.knobDisc, Prefs.knobArcRadius, Prefs.volDx, Prefs.volDy, Prefs.playDx, Prefs.playDy
        )
    }

    private fun adjustButton(
        label: String,
        wide: Boolean = false,
        repeat: Boolean = true,
        onClick: (View) -> Unit
    ): TextView {
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (wide) 13f else 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Neon.SURFACE_HI)
                setStroke(px(1f), Color.argb(90, 255, 255, 255))
                cornerRadius = px(10f).toFloat()
            }
        }
        val tick = object : Runnable {
            override fun run() { onClick(tv); handler.postDelayed(this, 90L) }
        }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    v.alpha = 0.6f
                    onClick(tv)
                    if (repeat) handler.postDelayed(tick, 400L)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    handler.removeCallbacks(tick)
                }
            }
            true
        }
        tv.layoutParams = LinearLayout.LayoutParams(px(if (wide) 64f else 46f), px(46f)).apply {
            leftMargin = px(3f); rightMargin = px(3f)
        }
        return tv
    }

    // --------------------------------------------------------------------- modern button helper

    /** One dock button's views, so the active app can be lit up without a rebuild. */
    private class DockTile(val tile: FrameLayout, val icon: android.widget.ImageView,
                           val caption: TextView, val glow: View)
    private val dockTiles = ArrayList<DockTile>()
    private var shownDockActive = -2
    private val dialerPkg: String? by lazy {
        try { packageManager.resolveActivity(Intent(Intent.ACTION_DIAL), 0)?.activityInfo?.packageName }
        catch (_: Exception) { null }
    }

    /** Which dock button matches the app in front: 0 Home, 2 Maps, 3 Phone, -1 none. */
    private fun dockIndexFor(pkg: String?): Int = when {
        pkg == null -> -1
        pkg == packageName -> 0
        pkg == (Prefs.navPackage?.takeIf { it.isNotBlank() } ?: "com.google.android.apps.maps") -> 2
        pkg == dialerPkg || pkg == "com.android.dialer" -> 3
        else -> -1
    }

    /**
     * Graphite + neon dock: neutral tiles with grey icons; the app in front gets a neon
     * gradient rim, a cyan icon and a glowing gradient line underneath.
     */
    private fun updateDockActive() {
        if (dockTiles.isEmpty()) return
        val active = dockIndexFor(ZlinkGuard.topApp(this))
        if (active == shownDockActive) return
        shownDockActive = active
        val radius = px(20f).toFloat()
        dockTiles.forEachIndexed { i, t ->
            val on = i == active
            t.tile.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    cornerRadius = radius; setColor(Neon.alpha(Neon.VIOLET, 70))
                })
                addState(intArrayOf(), if (on) {
                    // Gradient rim: gradient tile with a solid tile inset on top.
                    val rim = GradientDrawable(GradientDrawable.Orientation.TL_BR, Neon.GRADIENT).apply { cornerRadius = radius }
                    val fill = GradientDrawable().apply { cornerRadius = radius - px(2f); setColor(Neon.SURFACE) }
                    android.graphics.drawable.LayerDrawable(arrayOf(rim, fill)).apply {
                        setLayerInset(1, px(2f), px(2f), px(2f), px(2f))
                    }
                } else GradientDrawable().apply { cornerRadius = radius; setColor(Neon.SURFACE) })
            }
            t.icon.imageTintList = android.content.res.ColorStateList.valueOf(if (on) Neon.CYAN else Neon.ICON)
            t.caption.setTextColor(if (on) Neon.TEXT else Neon.DIM)
            t.glow.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Dock button: neutral tile, grey icon, caption, and a (hidden) neon glow line. */
    private fun dockButton(iconRes: Int, label: String, onClick: () -> Unit): View {
        val tileSize = px(64f)
        val tile = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            elevation = px(4f).toFloat()
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
        val icon = android.widget.ImageView(this).apply { setImageResource(iconRes) }
        tile.addView(icon, FrameLayout.LayoutParams(px(30f), px(30f), Gravity.CENTER))

        val caption = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            includeFontPadding = false
        }
        // Glow line: a soft wide bar behind a crisp thin one, both neon gradient.
        val glow = FrameLayout(this).apply {
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    Neon.GRADIENT.map { Neon.alpha(it, 70) }.toIntArray()).apply { cornerRadius = px(4f).toFloat() }
            }, FrameLayout.LayoutParams(px(40f), px(6f), Gravity.CENTER))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, Neon.GRADIENT)
                    .apply { cornerRadius = px(1.5f).toFloat() }
            }, FrameLayout.LayoutParams(px(30f), px(3f), Gravity.CENTER))
            visibility = View.INVISIBLE
        }
        dockTiles += DockTile(tile, icon, caption, glow)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tile, LinearLayout.LayoutParams(tileSize, tileSize))
            addView(caption, LinearLayout.LayoutParams(W, W).apply { topMargin = px(6f) })
            addView(glow, LinearLayout.LayoutParams(px(44f), px(6f)).apply { topMargin = px(2f) })
        }
    }

    /**
     * A small "Show panel" handle when the panel is hidden.
     */
    private fun addHandle(real: DisplayMetrics) {
        val panelHeightPx = Prefs.panelHeight
        val h = px(36f)

        val t = TextView(this).apply {
            text = "\u25B2 Show panel"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Neon.SURFACE_HI)
            setOnClickListener {
                hidden = false
                build()
            }
        }

        try {
            wm?.addView(
                t,
                overlayParams(px(140f), h, real.heightPixels - panelHeightPx + (panelHeightPx - h) / 2)
                    .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            )
            handle = t
        } catch (e: Exception) {
            log("handle addView failed: $e")
        }
    }

    // --------------------------------------------------------------------- live data: volume

    /**
     * Reads the current media volume.
     * Primary: AudioManager.getStreamVolume(STREAM_MUSIC)
     * Fallback: Settings.System keys for MCU hardware custom volumes.
     */
    private fun getCurrentVolumeDisplay(): String {
        // NWD MCU: the rotary knob's real volume lives in Settings.System "mcu_system_volume".
        try {
            val v = Settings.System.getInt(contentResolver, MCU_VOLUME_KEY, -1)
            if (v in 0..60) {
                lastReportedVolume = v
                return v.toString()
            }
        } catch (_: Exception) {}
        try {
            val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val curMusic = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (curMusic in 0..60) {
                lastReportedVolume = curMusic
                return curMusic.toString()
            }
            val curSys = audio.getStreamVolume(AudioManager.STREAM_SYSTEM)
            if (curSys in 0..60) {
                lastReportedVolume = curSys
                return curSys.toString()
            }
        } catch (_: Exception) {}

        if (lastReportedVolume in 0..60) {
            return lastReportedVolume.toString()
        }

        // MCU hardware custom keys (fallback if audio stream is uninitialized)
        val mcuKeys = listOf("volume_music_speaker", "car_volume", "sound_volume", "master_volume")
        for (k in mcuKeys) {
            try {
                val v = Settings.System.getInt(contentResolver, k, -1)
                if (v in 0..60) {
                    lastReportedVolume = v
                    return v.toString()
                }
            } catch (_: Exception) {}
        }

        return "10"
    }

    private fun updateVolumeDisplay() {
        handler.post {
            val vol = getCurrentVolumeDisplay()
            knobsView?.leftText = vol
        }
    }

    private fun adjustVolume(up: Boolean) {
        try {
            val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur in 0..60) lastReportedVolume = cur
            updateVolumeDisplay()
            val keyCode = if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            sendMediaKey(keyCode)
        } catch (e: Exception) {
            log("adjustVolume failed: $e")
        }
    }

    private fun startVolumeWatcher() {
        handler.removeCallbacks(volumeWatcher)
        handler.post(volumeWatcher)
    }

    private fun updatePlayPauseState() {
        handler.post {
            knobsView?.rightText = playGlyph()
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        try {
            val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Exception) {
            log("sendMediaKey failed: $e")
        }
    }

    private fun registerVolumeReceiver() {
        if (volumeReceiver != null) return
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val streamVal = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                if (streamVal in 0..60) {
                    lastReportedVolume = streamVal
                }
                for (key in listOf("volume", "value", "cur", "index", "vol")) {
                    val v = intent.getIntExtra(key, -1)
                    if (v in 0..60) {
                        lastReportedVolume = v
                        break
                    }
                }
                updateVolumeDisplay()
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_VOLUME_CHANGED_ACTION")
                addAction("android.media.RINGER_MODE_CHANGED")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("com.android.server.service.volume_changed")
                addAction("com.microntek.canbus.volume")
                addAction("com.syu.ms.volume")
                addAction("com.nwd.action.VOLUME_CHANGED")
                addAction("action.colink.volume_changed")
            }
            registerReceiver(volumeReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun unregisterVolumeReceiver() {
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
    }

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkReverse()
                zlink.check()
                updateVolumeDisplay()
                updateDockActive()
            }
        }
        try {
            contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver!!
            )
        } catch (e: Exception) {
            log("volumeObserver failed: $e")
        }
    }

    private fun unregisterVolumeObserver() {
        volumeObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        volumeObserver = null
    }

    // --------------------------------------------------------------------- live data: battery

    /** A CAN voltage broadcast: show it and feed the battery health monitor. */
    private fun onCanVoltage(intent: Intent) {
        volts.handleCan(intent)?.let { v ->
            voltSeq++
            battery.onReading(v)
        }
    }

    /**
     * Right-knob glyph follows Android Auto too: ZLink's audio on/off and the phone's player
     * state over the live link (ZLink itself never reports play/pause to the head unit).
     */
    private val playSync: () -> Unit = {
        val phone = com.minimal.carlauncher.core.PhoneMedia.fresh()
        val playing = phone?.playing ?: com.minimal.carlauncher.core.ZlinkStatus.audio
        if (playing != isPlayingState) {
            isPlayingState = playing
            updatePlayPauseState()
        }
    }

    /** ACTION_STREAM_MUSIC: EXTRA_MUSIC_STATE 1 = playing. Keeps the right-knob glyph honest. */
    private fun handleMusicState(intent: Intent) {
        val ex = intent.extras ?: return
        val st = ex.get("EXTRA_MUSIC_STATE") ?: return
        val playing = when (st) {
            is Int -> st == 1
            is Boolean -> st
            is String -> st.trim() == "1"
            else -> return
        }
        if (playing != isPlayingState) {
            isPlayingState = playing
            updatePlayPauseState()
        }
    }


    private fun updateBatteryDisplay() {
        handler.post {
            val volt = volts.display()
            // Non-broadcast sources are polled; treat each poll as a fresh reading.
            if (!volts.fromCan) voltSeq++
            evaluateBatteryState(volt.removeSuffix("V").toFloatOrNull())
            val icon = BATT_ICONS[shownBattState.coerceAtLeast(0)]
            batteryDisplay?.text = "$icon $volt"
            tripView?.apply { text = trips.tripText(); setTextColor(Neon.TEXT_2) }
            updateCamSlot()
        }
    }

    // --------------------------------------------------------------------- speed / camera slot

    private var camSlotTile: FrameLayout? = null
    private var camSlotBig: TextView? = null
    private var camSlotCaption: TextView? = null
    private var camSlotKey = ""
    private var camSlotPulse: android.animation.ValueAnimator? = null
    private var camSlotColor = 0

    /**
     * The strip's fourth tile (the Phone button's old place): the speed in km/h, so it's on
     * screen over Android Auto too; while a camera is ahead it turns amber (red when over the
     * limit) with the distance, and "AI CAM" / "LIMIT 50" underneath.
     */
    private fun buildCamSlot(): View {
        val tile = FrameLayout(this).apply { elevation = px(4f).toFloat(); minimumWidth = px(96f) }
        val big = TextView(this).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            setPadding(px(10f), 0, px(10f), 0)
        }
        tile.addView(big, FrameLayout.LayoutParams(W, M, Gravity.CENTER))
        val caption = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
        }
        camSlotTile = tile; camSlotBig = big; camSlotCaption = caption
        camSlotKey = ""
        updateCamSlot()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tile, LinearLayout.LayoutParams(W, px(64f)))
            addView(caption, LinearLayout.LayoutParams(W, W).apply { topMargin = px(6f) })
            addView(View(context), LinearLayout.LayoutParams(px(44f), px(6f)).apply { topMargin = px(2f) })
        }
    }

    /**
     * While a camera is ahead the tile breathes between full colour and a dark centre with a
     * thick coloured ring, and grows a little - hard to miss out of the corner of the eye.
     */
    private fun camSlotPulseOn(tile: View, color: Int) {
        if (camSlotPulse != null && camSlotColor == color) return
        camSlotPulse?.cancel()
        camSlotColor = color
        val bg = GradientDrawable().apply {
            cornerRadius = px(20f).toFloat(); setColor(color); setStroke(px(4f), color)
        }
        tile.background = bg
        camSlotPulse = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                val f = it.animatedValue as Float
                bg.setColor(Neon.alpha(color, (255 - 200 * f).toInt()))
                tile.scaleX = 1f + 0.08f * (1f - f); tile.scaleY = tile.scaleX
            }
            start()
        }
    }

    private fun camSlotPulseOff(tile: View) {
        camSlotPulse?.cancel(); camSlotPulse = null
        tile.scaleX = 1f; tile.scaleY = 1f
    }

    /** A fast triple blink of the tile at each alert (on top of the breathing). */
    private fun camSlotBlink() {
        val tile = camSlotTile ?: return
        android.animation.ObjectAnimator.ofFloat(tile, View.ALPHA, 1f, 0.15f, 1f, 0.15f, 1f, 0.15f, 1f).apply {
            duration = 1_500L; start()
        }
    }

    private fun updateCamSlot() {
        val tile = camSlotTile ?: return
        val big = camSlotBig ?: return
        val caption = camSlotCaption ?: return
        val cs = com.minimal.carlauncher.core.CameraStore
        val cam = cs.current
        val key: String
        if (cam != null) {
            val color = if (cs.over) Neon.RED else Neon.AMBER
            val dist = if (cam.distanceM >= 1000) String.format(Locale.US, "%.1f km", cam.distanceM / 1000.0)
                else "${(cam.distanceM / 10) * 10} m"
            key = "c$dist${cs.over}${cam.cam.id}"
            if (key == camSlotKey) return
            big.text = "\uD83D\uDCF7 $dist"
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            big.setTextColor(Color.WHITE)
            big.setShadowLayer(px(3f).toFloat(), 0f, 0f, Color.BLACK)
            big.paint.shader = null
            caption.text = when {
                cam.cam.limit > 0 -> "LIMIT ${cam.cam.limit}"
                cam.cam.kind == "speed" -> "SPEED CAM"
                else -> "AI CAM"
            } + (if (cs.another) " +1" else "")
            caption.setTextColor(color)
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            camSlotPulseOn(tile, color)
        } else {
            val f = trips.lastFix?.takeIf { System.currentTimeMillis() - it.time < 5_000L && it.hasSpeed() }
                ?: com.minimal.carlauncher.core.PhoneGps.fresh()
            val kmh = f?.speed?.let { (it * 3.6f).roundToInt() }
            key = "s$kmh"
            if (key == camSlotKey) return
            big.text = kmh?.toString() ?: "--"
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            big.setTextColor(Neon.TEXT)
            big.setShadowLayer(0f, 0f, 0f, 0)
            big.paint.shader = null
            caption.text = "KM/H"
            caption.setTextColor(Neon.DIM)
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            camSlotPulseOff(tile)
            tile.background = GradientDrawable().apply { cornerRadius = px(20f).toFloat(); setColor(Neon.SURFACE) }
        }
        camSlotKey = key
    }

    /**
     * While a camera is ahead and another app (Android Auto) is in front, the strip's top line
     * becomes a thick steady amber / red bar until the camera is passed; null puts the neon
     * hairline back.
     */
    private var stripGlowColor: Int? = null
    private var stripNormalBg: android.graphics.drawable.Drawable? = null
    private var stripNormalH = 0
    private var stripPulse: android.animation.ObjectAnimator? = null

    private fun stripGlow(color: Int?) {
        val v = stripLine ?: return
        if (color == stripGlowColor) return
        val lp = v.layoutParams ?: return
        if (stripGlowColor == null) { stripNormalBg = v.background; stripNormalH = lp.height }
        stripGlowColor = color
        if (color == null) {
            stripPulse?.cancel(); stripPulse = null
            v.alpha = 1f
            v.background = stripNormalBg
            lp.height = stripNormalH; v.layoutParams = lp
        } else {
            v.background = android.graphics.drawable.ColorDrawable(color)
            lp.height = px(5f); v.layoutParams = lp
        }
    }

    /** Three strong pulses of the bar at an alert. */
    private fun flashStrip() {
        val v = stripLine ?: return
        stripPulse?.cancel()
        stripPulse = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.1f, 1f, 0.1f, 1f, 0.1f, 1f).apply {
            duration = 2_400L
            start()
        }
    }

    /**
     * The head unit switches USB debugging off on some restarts, which kills the phone's ADB
     * bridge. With WRITE_SECURE_SETTINGS (granted once: adb shell pm grant <pkg>
     * android.permission.WRITE_SECURE_SETTINGS) it is switched straight back on.
     */
    private fun keepAdbOn() {
        val cr = contentResolver
        val on = try { android.provider.Settings.Global.getInt(cr, android.provider.Settings.Global.ADB_ENABLED, 0) == 1 } catch (_: Exception) { true }
        if (on) return
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("usb debugging is off (ADB bridge won't work) - turn it on in Developer options")
            return
        }
        try {
            android.provider.Settings.Global.putInt(cr, android.provider.Settings.Global.ADB_ENABLED, 1)
            log("usb debugging was off - switched it back on")
        } catch (e: Exception) { log("usb debugging: could not switch on ($e)") }
    }

    private fun currentVolts(): Float? = volts.display().removeSuffix("V").toFloatOrNull()

    /** 0 none, 1 charging, 2 normal, 3 low, 4 critical. */
    private fun classify(v: Float?): Int = when {
        v == null -> BATT_NONE
        v > 15.0f || v < 11.8f -> BATT_CRITICAL
        v < 12.2f -> BATT_LOW
        v < 13.2f -> BATT_NORMAL
        else -> BATT_CHARGING
    }

    /**
     * Good news shows at once; LOW / CRITICAL only after two separate readings agree, so a
     * single dip while cranking never flashes red.
     */
    private fun evaluateBatteryState(v: Float?) {
        val s = classify(v)
        if (shownBattState == -1 || s == shownBattState || s <= BATT_NORMAL) {
            pendingBattState = -1
            applyBatteryState(s)
            return
        }
        if (pendingBattState != s) {
            pendingBattState = s
            pendingSeqStart = voltSeq
            return
        }
        if (voltSeq - pendingSeqStart >= 1) applyBatteryState(s)
    }

    private fun applyBatteryState(state: Int) {
        // Not charging while driving shows amber even when the voltage itself looks normal.
        val chargeAlert = battery.chargeAlert
        val s = if (chargeAlert == 1 && state < BATT_LOW) BATT_LOW else state
        if (s == shownBattState) return
        shownBattState = s
        val color = BATT_COLORS[s]
        val badge = batteryBadgeView ?: return
        (badge.background as? GradientDrawable)?.apply {
            setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(px(2f), color)
        }
        // Coloured neon halo around the pill (API 28+ coloured elevation shadows).
        badge.outlineAmbientShadowColor = color
        badge.outlineSpotShadowColor = color
        batteryDisplay?.apply {
            setTextColor(color)
            setShadowLayer(12f, 0f, 0f, color)
        }
        battLabelView?.apply {
            text = when {
                chargeAlert == 1 && s != BATT_CRITICAL -> "NOT CHARGING"
                chargeAlert == 2 -> "OVERCHARGING"
                else -> BATT_LABELS[s]
            }
            setTextColor(if (s >= BATT_LOW || chargeAlert != 0) color else Neon.DIM)
        }
        pulseAnim?.cancel()
        pulseAnim = null
        badge.alpha = 1f
        if (s == BATT_CRITICAL) {
            pulseAnim = android.animation.ObjectAnimator.ofFloat(badge, "alpha", 1f, 0.45f).apply {
                duration = 650
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
    }

    // --------------------------------------------------------------------- fuel

    private fun openFuelDialog() = openTrips("fuel")

    /** Opens the Trips & fuel screen on the given tab ("fuel" or "trips"). */
    private fun openTrips(tab: String) {
        try {
            startActivity(
                Intent(this, Class.forName("com.minimal.carlauncher.ui.TripsActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("tab", tab)
            )
        } catch (e: Exception) {
            log("openTrips failed: $e")
        }
    }

    private fun updateFuelDisplay() { handler.post { updateFuelDisplayNow() } }

    private var fuelModelAt = 0L
    private var fuelModelRange = -1

    private fun updateFuelDisplayNow() {
        val now = SystemClock.elapsedRealtime()
        if (fuelModelAt == 0L || now - fuelModelAt > 20_000L || shownFuelKey.isEmpty()) {
            fuelModelAt = now
            fuelModelRange = try { Prefs.fuelModel().rangeKm } catch (_: Exception) {
                com.minimal.carlauncher.core.FuelEstimator.rangeKm(Prefs.fuelState, Prefs.gpsOdoKm)
            }
        }
        val range = fuelModelRange
        val level = com.minimal.carlauncher.core.FuelModel.level(range)
        val key = "$range/$level"
        if (key == shownFuelKey) return
        shownFuelKey = key
        val color = BATT_COLORS[level]
        (fuelBadgeView?.background as? GradientDrawable)?.apply {
            setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(px(2f), color)
        }
        fuelBadgeView?.outlineAmbientShadowColor = color
        fuelBadgeView?.outlineSpotShadowColor = color
        fuelIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)
        fuelText?.apply {
            text = if (range < 0) "Set up" else "\u2248 $range km"
            setTextColor(color)
            setShadowLayer(12f, 0f, 0f, color)
        }
        fuelLabel?.apply {
            text = when (level) { 0 -> "FUEL"; 3 -> "FUEL LOW"; 4 -> "FUEL RESERVE"; else -> "FUEL RANGE" }
            setTextColor(if (level >= 3) color else Neon.DIM)
        }
    }

    // --------------------------------------------------------------------- weather

    private fun applyWeather() {
        val w = weather
        val icon = weatherIcon ?: return
        val txt = weatherTemp ?: return
        if (w == null) {
            icon.visibility = View.GONE
            txt.text = ""
            return
        }
        icon.visibility = View.VISIBLE
        icon.kind = WeatherApi.kind(w.code)
        icon.isDay = w.isDay
        txt.text = "${w.temp}\u00B0"
    }

    private fun showWeatherSummary() {
        val w = weather
        val msg = if (w == null) {
            "Weather not available yet (needs internet and a location)"
        } else {
            val today = w.days.firstOrNull()
            buildString {
                append("${w.temp}\u00B0  ${WeatherApi.describe(w.code)}\n")
                append("Feels ${w.feels}\u00B0 \u00B7 Humidity ${w.humidity}% \u00B7 Wind ${w.windKmh} km/h")
                if (today != null) append("\nToday ${today.min}\u00B0 / ${today.max}\u00B0 \u00B7 Rain ${today.rainPct}%")
                w.hours.take(3).let { h ->
                    if (h.isNotEmpty()) append("\n" + h.joinToString("   ") { "${it.label} ${it.temp}\u00B0" })
                }
                append("\nUpdated ${w.updated}")
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Refreshes at most every 20 min, off the main thread, using a ~1 km rounded location. */
    private fun maybeRefreshWeather() {
        if (weatherFetching) return
        val now = System.currentTimeMillis()
        if (now - Prefs.weatherAt < WEATHER_REFRESH_MS && weather != null) return
        if (now - lastWeatherTry < WEATHER_RETRY_MS) return
        val loc = weatherLocation() ?: return
        lastWeatherTry = now
        weatherFetching = true
        Thread {
            val json = WeatherApi.fetch(loc.first, loc.second)
            val parsed = json?.let { WeatherApi.parse(it) }
            handler.post {
                weatherFetching = false
                if (json != null && parsed != null) {
                    Prefs.weatherJson = json
                    Prefs.weatherAt = System.currentTimeMillis()
                    weather = parsed
                    applyWeather()
                }
            }
        }.start()
    }

    private var lastWeatherTry = 0L

    /** Live GPS fix, else last known fix, else the map's last position. */
    private fun weatherLocation(): Pair<Double, Double>? {
        trips.lastFix?.let { return it.latitude to it.longitude }
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val m = getSystemService(android.location.LocationManager::class.java)
                for (p in listOf("gps", "network", "passive")) {
                    val l = m?.getLastKnownLocation(p) ?: continue
                    return l.latitude to l.longitude
                }
            }
        } catch (_: Exception) {}
        return Prefs.mapCamera()?.let { it.first to it.second }
    }

    // --------------------------------------------------------------------- trip timer

    /**
     * Trip = time since the car was started. A heartbeat is saved every ~30 s while the unit
     * is on; if the service (re)starts and the last heartbeat is older than TRIP_GAP_MS, the
     * car was off, so a new trip begins. Works whether ACC-off reboots or just sleeps the unit.
     */
    private fun tripHeartbeat() {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatMs < 30_000L) return
        lastHeartbeatMs = now
        val last = Prefs.tripLastSeen
        if (Prefs.tripStart <= 0L || last <= 0L || now - last > TRIP_GAP_MS || now < Prefs.tripStart) {
            trips.finishTrip()
            trips.beginTrip(now)
            ignitionElapsed = SystemClock.elapsedRealtime()
            battery.onIgnition(if (last > 0L) now - last else Long.MAX_VALUE)
            zlink.onIgnition()
            log("ignition on (new trip) -- ZLink guard active")
        }
        // Trip started before this version recorded its GPS start: begin counting from now.
        if (Prefs.curTripGpsStart <= 0.0) Prefs.curTripGpsStart = trips.odometerKm
        Prefs.tripLastSeen = now
        Prefs.tripLastGps = trips.odometerKm
        trips.flush(force = true)
        cloud.tick()
        liveLinkTick()
    }

    private var linkWasUp = false
    private val fastLink = Runnable { liveLinkTick() }

    /** Pushes live data straight to the phone app (no internet needed) and takes its fill-ups. */
    private fun liveLinkTick() {
        LiveLink.tick(this, { linkExtra() }) { r ->
            val up = r.reached != null
            // While music plays, ask the phone every 4 s so song changes show up quickly.
            val playing = com.minimal.carlauncher.core.ZlinkStatus.audio ||
                com.minimal.carlauncher.core.PhoneMedia.fresh()?.playing == true
            handler.removeCallbacks(fastLink)
            if (up && playing) handler.postDelayed(fastLink, 4_000)
            if (up != linkWasUp) { linkWasUp = up; log(if (up) "live link: phone at ${r.reached}" else "live link: phone not reachable") }
            r.note?.let { log("live link: $it") }
            if (r.added > 0) handler.post { onPhoneFillsAdded() }
            r.tyreWarn?.let { pos -> handler.post { onTyreWarn(pos, r.tyreLeak) } }
            r.installVer?.let { ver -> handler.post { installFromPhone(ver) } }
            r.update?.let { (tag, file) ->
                log("live link: launcher $tag received from the phone")
                handler.post {
                    (application as? com.minimal.carlauncher.CarLauncherApp)?.updateRepository?.offerDownloaded(tag, file)
                    Toast.makeText(this, "Launcher $tag received from your phone. Install it from About when parked.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** The launcher version the car has downloaded and could install now (empty = none). */
    private fun readyUpdateVersion(): String {
        val st = (application as? com.minimal.carlauncher.CarLauncherApp)?.updateRepository?.state?.value
        return (st as? com.minimal.carlauncher.update.UpdateState.ReadyToInstall)?.release?.tag.orEmpty()
    }

    /** The phone asked the car to install the update it already has. Root-install it (silent). */
    private fun installFromPhone(ver: String) {
        val st = (application as? com.minimal.carlauncher.CarLauncherApp)?.updateRepository?.state?.value
        val ready = st as? com.minimal.carlauncher.update.UpdateState.ReadyToInstall
        if (ready == null || ready.release.tag != ver) { log("install $ver from phone: not ready"); return }
        log("install $ver from phone: starting")
        Toast.makeText(this, "Installing launcher $ver from your phone...", Toast.LENGTH_LONG).show()
        val err = com.minimal.carlauncher.update.ApkInstaller.installRoot(this, ready.file)
        if (err == null) log("install $ver: done (root)")
        else {
            log("install $ver: root failed ($err), opening the installer")
            com.minimal.carlauncher.update.ApkInstaller.install(this, ready.file)
        }
    }

    /** A tyre dropped below the limit (or is leaking fast): speak it and flash the strip red. */
    private fun onTyreWarn(pos: String, leak: Boolean) {
        val t = com.minimal.carlauncher.core.TpmsStore.tyre(pos)
        val name = com.minimal.carlauncher.core.Tpms.posLabel(pos).lowercase(java.util.Locale.US)
        val psi = t?.psi?.let { " ${it.toInt()} PSI" } ?: ""
        cameras.speak(if (leak) "Warning, $name tyre losing pressure fast,$psi" else "Low pressure, $name tyre,$psi", 3)
        log("tyre warning: $pos ${if (leak) "fast leak" else "low"} ${t?.psi ?: -1} psi")
        if (ZlinkGuard.topApp(this) != packageName) { stripGlow(Neon.RED); flashStrip() }
        updateCamSlot()
    }

    /** Fill-ups logged on the phone were merged: refresh the fuel chip and say so. */
    private fun onPhoneFillsAdded() {
        fuelModelAt = 0L
        updateFuelDisplay()
        Toast.makeText(this, "Fill-up from your phone added to the fuel log", Toast.LENGTH_LONG).show()
    }

    /** Charge alert, position and a diagnostics snapshot for the phone app. */
    private fun linkExtra(): com.minimal.carlauncher.core.CarLink.CarExtra {
        val fix = trips.lastFix
        val chargeAlert = battery.chargeAlert
        val diag = buildString {
            appendLine("Launcher ${com.minimal.carlauncher.BuildConfig.VERSION_NAME}")
            appendLine("Voltage ${volts.canVolts ?: "--"} V   charge alert $chargeAlert")
            appendLine("App in front: ${ZlinkGuard.topApp(this@PanelService) ?: "-"}")
            appendLine("Cloud: ${Prefs.cloudStatus.ifEmpty { "-" }}")
            appendLine("Phone link: ${Prefs.linkPhone.ifEmpty { "-" }}")
            appendLine("GPS: ${if (fix == null) "no fix" else String.format(Locale.US, "±%.0f m", fix.accuracy)}  ${com.minimal.carlauncher.core.GnssInfo.label()}")
            appendLine()
            append(getSharedPreferences("panel_log", Context.MODE_PRIVATE).getString("log", "") ?: "")
        }
        return com.minimal.carlauncher.core.CarLink.CarExtra(
            appVersion = com.minimal.carlauncher.BuildConfig.VERSION_NAME,
            chargeAlert = chargeAlert,
            camVer = com.minimal.carlauncher.core.CameraStore.osmSet(this).version,
            stay = if (Prefs.stayOnConnect) 1 else 0,
            readyVer = readyUpdateVersion(),
            pos = fix?.let { com.minimal.carlauncher.core.CarLink.Pos(it.latitude, it.longitude, it.time, if (it.hasSpeed()) it.speed else 0f) },
            diag = diag,
        )
    }

    // --------------------------------------------------------------------- reverse camera

    /** Removes the panel while the reverse camera is on, restores it after. */
    private fun checkReverse() {
        val r = try {
            Settings.System.getInt(contentResolver, BACKCAR_KEY, 0) != 0
        } catch (_: Exception) { false }
        if (r == reversing) return
        reversing = r
        log(if (r) "reverse on -- hiding panel" else "reverse off -- restoring panel")
        if (r) clear() else build()
    }

    /** Listens for battery voltage changes so the display stays current. */
    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    NWD_VOLT_ACTION -> { onCanVoltage(intent); updateBatteryDisplay(); return }
                    NWD_MUSIC_ACTION -> { handleMusicState(intent); return }
                }
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) volts.handleVendor(intent)
                updateBatteryDisplay()
            }
        }
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(NWD_VOLT_ACTION)
                addAction(NWD_MUSIC_ACTION)
                VOLT_BROADCASTS.forEach { addAction(it) }
            }
            registerReceiver(batteryReceiver, filter)
            // If the NWD broadcasts are sticky, pick up the last value immediately.
            registerReceiver(null, IntentFilter(NWD_VOLT_ACTION))?.let { onCanVoltage(it) }
            // com.nwd.kernel.setting listens for this and answers with ACTION_CAN_BATTERY_VOLTAGE,
            // so the pill fills in now instead of after the next 30 s broadcast.
            sendBroadcast(Intent(NWD_VOLT_QUERY_ACTION))
            registerReceiver(null, IntentFilter(NWD_MUSIC_ACTION))?.let { handleMusicState(it) }
        } catch (_: Exception) {}
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
    }

    // --------------------------------------------------------------------- overlay window params

    /**
     * Creates WindowManager.LayoutParams anchored at TOP|LEFT with a computed y offset.
     * Using Gravity.BOTTOM does NOT work on this head unit -- the firmware's 550 px app window
     * limit causes bottom-anchored overlays to land too high.
     */
    private fun overlayParams(w: Int, h: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = 0
            this.y = y
        }

    // --------------------------------------------------------------------- app launchers

    private fun openHomeDashboard() {
        try {
            val intent = Intent(this, Class.forName("com.minimal.carlauncher.ui.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } catch (e: Exception) {
            log("openHomeDashboard failed: $e")
        }
    }

    private fun openAppGrid() {
        try {
            val intent = Intent(this, Class.forName("com.minimal.carlauncher.ui.HomeActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("open_drawer", true)
            startActivity(intent)
        } catch (e: Exception) {
            log("openAppGrid failed: $e")
        }
    }

    private fun openMaps() {
        val storedNav = Prefs.navPackage
        val launchIntent = if (!storedNav.isNullOrBlank()) {
            packageManager.getLaunchIntentForPackage(storedNav)
        } else null
        val intent = launchIntent
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            ?: packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        startSafely(intent)
    }

    private fun openDialer() {
        startSafely(Intent(Intent.ACTION_DIAL))
    }

    private fun startSafely(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --------------------------------------------------------------------- watchdog & screen receiver

    private fun checkViews() {
        if (wm == null || reversing) return
        if (hidden) {
            if (handle == null || handle?.isAttachedToWindow != true) {
                log("handle missing -- re-adding")
                build()
            }
        } else {
            if (panel == null || panel?.isAttachedToWindow != true) {
                log("panel window gone -- re-adding")
                build()
            }
        }
    }

    private fun restartWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (wm == null) return
                val act = intent.action
                if (act == Intent.ACTION_SCREEN_ON || act == Intent.ACTION_USER_PRESENT) {
                    if (!hidden && panel?.isAttachedToWindow != true) {
                        log("$act -- re-adding panel")
                        build()
                    } else if (hidden && handle?.isAttachedToWindow != true) {
                        log("$act -- re-adding handle")
                        build()
                    }
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, filter)
        } catch (_: Exception) {}
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    // --------------------------------------------------------------------- utility

    private fun px(dp: Float): Int = Math.round(dp * dens)

    private fun remove(v: View?) {
        if (v != null && wm != null) {
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
    }

    private fun clear() {
        remove(panel)
        remove(handle)
        panel = null
        handle = null
        knobsView = null
        batteryDisplay = null
        pulseAnim?.cancel(); pulseAnim = null
        batteryBadgeView = null
        battLabelView = null
        tripView = null
        camSlotPulse?.cancel(); camSlotPulse = null
        camSlotTile = null; camSlotBig = null; camSlotCaption = null
        stripLine = null
        stripGlowColor = null
        fuelBadgeView = null; fuelText = null; fuelIcon = null; fuelLabel = null
        weatherIcon = null; weatherTemp = null
        adjustReadout = null
    }

    companion object {
        private const val CHANNEL_ID = "bottom_panel"
        private const val NOTIFICATION_ID = 42
        private const val WATCHDOG_INTERVAL_MS = 1500L
        private const val VOLUME_POLL_INTERVAL_MS = 300L

        const val ACTION_WAKE = "com.minimal.carlauncher.panel.WAKE"
        const val ACTION_PING = "com.minimal.carlauncher.panel.PING"

        // Real K2401 / NWD sources (from the unit's logs).
        const val NWD_VOLT_ACTION = "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE"
        const val NWD_VOLT_QUERY_ACTION = "com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE"
        const val NWD_MUSIC_ACTION = "com.nwd.android.ACTION_STREAM_MUSIC"
        const val MCU_VOLUME_KEY = "mcu_system_volume"
        const val BACKCAR_KEY = "mcu_backcar_state"
        private const val TRIP_GAP_MS = 3 * 60_000L
        private const val WEATHER_REFRESH_MS = 20 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L
        private const val WEATHER_STALE_MS = 3 * 60 * 60_000L

        private const val BATT_NONE = 0
        private const val BATT_CHARGING = 1
        private const val BATT_NORMAL = 2
        private const val BATT_LOW = 3
        private const val BATT_CRITICAL = 4
        private val BATT_COLORS = intArrayOf(
            Neon.DIM,            // none   - neutral grey
            0xFF22D3EE.toInt(),  // charging - cyan (matches the knob arcs)
            0xFF4ADE80.toInt(),  // normal - green
            0xFFFBBF24.toInt(),  // low    - amber
            0xFFF43F5E.toInt(),  // critical - red, pulses
        )
        private val BATT_LABELS = arrayOf("BATTERY", "CHARGING", "BATTERY", "LOW", "CRITICAL")
        private val BATT_ICONS = arrayOf("\u26A1", "\u26A1", "\u25CF", "\u25B2", "!")

        private val VOLT_BROADCASTS = listOf(
            "com.nwd.action.BATTERY_VOLTAGE",
            "com.nwd.action.VOLTAGE_CHANGED",
            "com.nwd.mcu.voltage",
        )

        private const val PANEL_BG = Neon.BG
        private const val PANEL_BG_TRANSPARENT = 0x80000000.toInt()

        private const val W = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val M = ViewGroup.LayoutParams.MATCH_PARENT

        @Volatile
        var isRunning = false
            private set

        /** Simple one-line logger persisted in SharedPreferences for on-device debugging. */
        fun log(context: Context, msg: String) {
            val prefs = context.getSharedPreferences("panel_log", Context.MODE_PRIVATE)
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + msg
            val old = prefs.getString("log", "")?.split("\n") ?: emptyList()
            val combined = (listOf(line) + old.filter { it.isNotEmpty() }.take(59))
                .joinToString("\n")
            prefs.edit().putString("log", combined).apply()
        }

        fun log(msg: String) {
            android.util.Log.d("PanelService", msg)
        }
    }

    private fun log(msg: String) {
        Companion.log(this, msg)
    }
}
