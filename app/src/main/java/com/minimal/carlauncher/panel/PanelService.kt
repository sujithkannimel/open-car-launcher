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
import android.os.BatteryManager
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
import com.minimal.carlauncher.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            checkViews()
            updateVolumeDisplay()
            updateBatteryDisplay()
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
        dens = resources.displayMetrics.density

        registerScreenReceiver()
        registerBatteryReceiver()
        registerVolumeReceiver()
        registerVolumeObserver()

        // Wake action: always force-rebuild (called after ACC on via the launcher).
        if (action == ACTION_WAKE) {
            log("wake -- force rebuilding panel")
            hidden = false
            build()
            restartWatchdog()
            startVolumeWatcher()
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
        clear()
        log("service destroyed")
        super.onDestroy()
    }

    // --------------------------------------------------------------------- view management

    private fun build() {
        clear()
        val real = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(real) ?: return

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
            setBackgroundColor(if (Prefs.panelTransparent) PANEL_BG_TRANSPARENT else PANEL_BG)
        }

        // Top hairline divider for clean cockpit depth
        val topDivider = View(this).apply {
            setBackgroundColor(Color.argb(35, 255, 255, 255))
        }
        root.addView(topDivider, FrameLayout.LayoutParams(M, px(1.5f), Gravity.TOP))

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
            setPadding(px(6f), 0, 0, 0)
        }

        val batteryBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(70, 0, 229, 255))
                setStroke(px(1.2f), Color.argb(160, 0, 229, 255))
                cornerRadius = px(12f).toFloat()
            }
            setPadding(px(12f), px(4f), px(12f), px(4f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val curV = getBatteryVoltageDisplay()
                Toast.makeText(this@PanelService, "Voltage $curV\nsource: $voltSource", Toast.LENGTH_LONG).show()
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
            text = "\u26A1 " + getBatteryVoltageDisplay()
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
            setTextColor(Color.rgb(130, 150, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT
            gravity = Gravity.START
            setPadding(px(4f), px(2f), 0, 0)
            letterSpacing = 0.08f
            includeFontPadding = false
        }
        leftCluster.addView(batteryBadge)
        leftCluster.addView(battSubtitle)

        val leftClusterParams = FrameLayout.LayoutParams(W, W, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = sideLeft
        }
        if (!adjusting) root.addView(leftCluster, leftClusterParams)

        // 4. CENTER: Quick-Launcher Buttons (gradient squircle)
        val centerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnHome = dockButton(R.drawable.ic_panel_home, 0xFFFF7A45.toInt(), "Home") { openHomeDashboard() }
        val btnApps = dockButton(R.drawable.ic_panel_apps, 0xFF4D9FFF.toInt(), "Apps") { openAppGrid() }
        val btnNav = dockButton(R.drawable.ic_panel_nav, 0xFF2ED3C6.toInt(), "Maps") { openMaps() }
        val btnPhone = dockButton(R.drawable.ic_panel_phone, 0xFF3DDC84.toInt(), "Phone") { openDialer() }

        for (b in listOf(btnHome, btnApps, btnNav, btnPhone)) {
            centerBar.addView(b, LinearLayout.LayoutParams(W, W).apply {
                leftMargin = px(10f); rightMargin = px(10f)
            })
        }

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
            setTextColor(Color.rgb(255, 64, 129))
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
        fun targetText() = when (adjustTarget) { 1 -> "LEFT"; 2 -> "RIGHT"; else -> "BOTH" }
        row.addView(adjustButton(targetText(), wide = true, repeat = false) {
            adjustTarget = (adjustTarget + 1) % 3
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
     * knobs move as a mirrored pair: ▶ moves both toward the screen centre, ◀ both toward
     * their edges. Prefs.knobRightX is measured from the right edge.
     */
    private fun nudge(dx: Int = 0, dy: Int = 0) {
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
            Locale.US, "L x%d y%d   R x%d y%d   disc %d   arc %d",
            Prefs.knobLeftX, Prefs.knobLeftY, Prefs.knobRightX, Prefs.knobRightY,
            Prefs.knobDisc, Prefs.knobArcRadius
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
                setColor(Color.rgb(38, 46, 58))
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

    /**
     * Modern dock button: dark glass tile with a hairline accent border, a crisp vector
     * icon in the accent colour, and a small label underneath. Press = accent wash.
     */
    private fun dockButton(iconRes: Int, accent: Int, label: String, onClick: () -> Unit): View {
        val tileSize = px(64f)
        val radius = px(20f).toFloat()
        fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        val idle = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.rgb(24, 31, 42))
            setStroke(px(1.5f), withAlpha(accent, 110))
        }
        val pressed = GradientDrawable().apply {
            cornerRadius = radius
            setColor(withAlpha(accent, 70))
            setStroke(px(1.5f), accent)
        }
        val tile = FrameLayout(this).apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), idle)
            }
            isClickable = true
            isFocusable = true
            elevation = px(4f).toFloat()
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
        // Soft accent glow dot behind the icon for depth (flat, no blur).
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(accent, 38))
            }
        }
        tile.addView(halo, FrameLayout.LayoutParams(px(44f), px(44f), Gravity.CENTER))
        val icon = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        tile.addView(icon, FrameLayout.LayoutParams(px(30f), px(30f), Gravity.CENTER))

        val caption = TextView(this).apply {
            text = label
            setTextColor(Color.rgb(150, 165, 182))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            includeFontPadding = false
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(tile, LinearLayout.LayoutParams(tileSize, tileSize))
            addView(caption, LinearLayout.LayoutParams(W, W).apply { topMargin = px(6f) })
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
            setBackgroundColor(Color.rgb(60, 66, 76))
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
                updateVolumeDisplay()
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

    /**
     * Reads the car battery voltage.
     * Primary: BatteryManager.EXTRA_VOLTAGE from the sticky BATTERY_CHANGED broadcast.
     * Fallback: Settings.System/Global keys for MCU hardware voltages.
     * No source: shows '--.-V' (never a made-up number).
     */
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
     * 5. Otherwise '--.-V'. Tap the pill to see which source was used.
     */
    /** Where the last voltage reading came from; shown when the pill is tapped. */
    private var voltSource = "none"

    private fun fmtVolts(v: Float) = String.format(Locale.US, "%.1fV", v)

    /** Converts a raw reading in µV, mV, tenths or volts to volts. */
    private fun toVolts(raw: Long): Float = when {
        raw > 1_000_000 -> raw / 1_000_000f
        raw > 1_000 -> raw / 1_000f
        raw > 100 -> raw / 10f
        else -> raw.toFloat()
    }

    private fun plausible(v: Float) = v in 6.0f..18.5f

    private fun getBatteryVoltageDisplay(): String {
        // --- Source 0: NWD CAN box broadcast (what the stock launcher shows) ---
        canVolts?.let { v ->
            if (plausible(v)) { voltSource = "NWD CAN broadcast (Voltage=${canRaw})"; return fmtVolts(v) }
        }
        Prefs.lastVoltage.takeIf { plausible(it) }?.let { v ->
            voltSource = "last saved value, waiting for broadcast (${lastVoltExtras})"
            return fmtVolts(v)
        }

        // --- Source 1: BatteryManager sticky broadcast ---
        try {
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val mv = bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (mv > 0) {
                val v = toVolts(mv.toLong())
                if (plausible(v)) { voltSource = "BatteryManager ($mv)"; return fmtVolts(v) }
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
                if (plausible(v)) { voltSource = "$path ($raw)"; return fmtVolts(v) }
            } catch (_: Exception) {}
        }

        // --- Source 3: Settings.System / Settings.Global (MCU firmware keys) ---
        for (k in VOLT_SETTING_KEYS) {
            try {
                val str = Settings.System.getString(contentResolver, k)
                    ?: Settings.Global.getString(contentResolver, k) ?: continue
                val num = Regex("[0-9]+(\\.[0-9]+)?").find(str)?.value ?: continue
                val v = if (num.contains('.')) num.toFloat() else toVolts(num.toLong())
                if (plausible(v)) { voltSource = "settings:$k ($str)"; return fmtVolts(v) }
            } catch (_: Exception) {}
        }

        // --- Source 4: value pushed by a firmware broadcast (see registerBatteryReceiver) ---
        lastBroadcastVolts?.let { if (plausible(it.first)) { voltSource = it.second; return fmtVolts(it.first) } }

        // No real source: say so instead of showing a made-up number.
        voltSource = "none found; last broadcast extras: $lastVoltExtras"
        return "--.-V"
    }

    private var lastBroadcastVolts: Pair<Float, String>? = null
    private var canVolts: Float? = null
    private var canRaw: Int = -1

    /** Extras of the last voltage broadcast, as "key:Type=value", for the pill's tap toast. */
    private var lastVoltExtras = "none received yet"

    /**
     * Parses ACTION_CAN_BATTERY_VOLTAGE (sent by com.nwd.kernel every ~30 s). The value is
     * tenths of a volt (142 = 14.2V). Accepts any numeric type (Int, Short, Byte, Long,
     * Float...) or a string, under "Voltage" or any key containing "volt".
     */
    private fun handleCanVoltage(intent: Intent) {
        val ex = intent.extras ?: return
        lastVoltExtras = ex.keySet().joinToString { k ->
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
                return
            }
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
            val volt = getBatteryVoltageDisplay()
            batteryDisplay?.text = "\u26A1 $volt"
        }
    }

    /** Listens for battery voltage changes so the display stays current. */
    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    NWD_VOLT_ACTION -> { handleCanVoltage(intent); updateBatteryDisplay(); return }
                    NWD_MUSIC_ACTION -> { handleMusicState(intent); return }
                }
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) {
                    intent.extras?.let { ex ->
                        for (key in ex.keySet()) {
                            if (!key.contains("vol", ignoreCase = true)) continue
                            val v = when (val o = ex.get(key)) {
                                is Float -> o
                                is Double -> o.toFloat()
                                is Int -> toVolts(o.toLong())
                                is Long -> toVolts(o)
                                is String -> o.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                                else -> null
                            } ?: continue
                            if (plausible(v)) {
                                lastBroadcastVolts = v to "broadcast ${intent.action} [$key]"
                                break
                            }
                        }
                    }
                }
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
            registerReceiver(null, IntentFilter(NWD_VOLT_ACTION))?.let { handleCanVoltage(it) }
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
        if (wm == null) return
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
        const val NWD_VOLT_KEY = "Voltage"
        const val NWD_VOLT_QUERY_ACTION = "com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE"
        const val NWD_MUSIC_ACTION = "com.nwd.android.ACTION_STREAM_MUSIC"
        const val MCU_VOLUME_KEY = "mcu_system_volume"

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
        private val VOLT_BROADCASTS = listOf(
            "com.nwd.action.BATTERY_VOLTAGE",
            "com.nwd.action.VOLTAGE_CHANGED",
            "com.nwd.mcu.voltage",
        )

        private const val PANEL_BG = 0xFF0D1117.toInt()
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
            val combined = (listOf(line) + old.filter { it.isNotEmpty() }.take(19))
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
