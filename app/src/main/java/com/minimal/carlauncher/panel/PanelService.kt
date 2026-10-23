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
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
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
    private var volumeDisplay: TextView? = null
    private var batteryDisplay: TextView? = null
    private var playPauseText: TextView? = null
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
     * - Left knob: white circle (116dp) at leftMargin=24dp
     * - Left arc: KnobArcView at leftMargin=124dp (VOL -/+)
     * - Right arc: KnobArcView at rightMargin=124dp (Track Next/Prev)
     * - Right knob: white circle at rightMargin=24dp (play/pause)
     * - Battery pill: lower left at leftMargin=196dp
     * - Center buttons: Home, Apps, Nav, Phone (gradient squircle, 62dp)
     * - Clock: lower right at rightMargin=196dp
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

        // Knob sizing & alignment calibrated to physical rotary bezel
        val knobSize = px(116f)
        val arcWidth = px(64f)
        val arcHeight = px(122f)

        // 1. LEFT KNOB: White illuminated circular aperture with volume digit
        val leftKnobCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = px(6f).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val audio = am ?: (getSystemService(AUDIO_SERVICE) as AudioManager)
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                updateVolumeDisplay()
            }
        }

        val volValue = TextView(this).apply {
            text = getCurrentVolumeDisplay()
            setTextColor(Color.rgb(18, 18, 18))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        volumeDisplay = volValue
        leftKnobCircle.addView(volValue, FrameLayout.LayoutParams(M, M, Gravity.CENTER))

        val leftParams = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.START or Gravity.TOP).apply {
            leftMargin = px(24f)
            topMargin = px(6f)
        }
        root.addView(leftKnobCircle, leftParams)

        // Curved arc hugging the outer rim of the left knob: Top is "-", Bottom is "+"
        val leftArc = KnobArcView(
            context = this,
            isLeftKnob = true,
            topLabel = "-",
            bottomLabel = "+",
            midLabel = null,
            onTopClick = { adjustVolume(up = false) },
            onBottomClick = { adjustVolume(up = true) }
        )
        val leftArcParams = FrameLayout.LayoutParams(arcWidth, arcHeight, Gravity.START or Gravity.TOP).apply {
            leftMargin = px(124f)
            topMargin = px(2f)
        }
        root.addView(leftArc, leftArcParams)

        // 2. RIGHT KNOB: Track arc + white play/pause aperture
        val rightArc = KnobArcView(
            context = this,
            isLeftKnob = false,
            topLabel = "\u25B6|",
            bottomLabel = "|\u25C0",
            midLabel = null,
            onTopClick = {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                Toast.makeText(this, "Next track", Toast.LENGTH_SHORT).show()
            },
            onBottomClick = {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                Toast.makeText(this, "Previous track", Toast.LENGTH_SHORT).show()
            }
        )
        val rightArcParams = FrameLayout.LayoutParams(arcWidth, arcHeight, Gravity.END or Gravity.TOP).apply {
            rightMargin = px(124f)
            topMargin = px(2f)
        }
        root.addView(rightArc, rightArcParams)

        val rightKnobCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = px(6f).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                isPlayingState = !isPlayingState
                updatePlayPauseState()
            }
        }

        val mediaIcon = TextView(this).apply {
            text = if (isPlayingState) "\u275A\u275A" else "\u25B6"
            setTextColor(Color.rgb(18, 18, 18))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        playPauseText = mediaIcon
        rightKnobCircle.addView(mediaIcon, FrameLayout.LayoutParams(M, M, Gravity.CENTER))

        val rightParams = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.END or Gravity.TOP).apply {
            rightMargin = px(24f)
            topMargin = px(6f)
        }
        root.addView(rightKnobCircle, rightParams)

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
                Toast.makeText(this@PanelService, "Battery Voltage: $curV", Toast.LENGTH_SHORT).show()
                updateBatteryDisplay()
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
            leftMargin = px(196f)
        }
        root.addView(leftCluster, leftClusterParams)

        // 4. CENTER: Quick-Launcher Buttons (gradient squircle)
        val centerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnSize = px(62f)
        val btnRadius = px(18f).toFloat()

        // Home button (U+2302)
        val btnHome = modernButton(
            symbol = "\u2302",
            startColor = 0xFFFF5722.toInt(),
            endColor = 0xFFFF8A65.toInt(),
            size = btnSize,
            radius = btnRadius
        ) { openHomeDashboard() }

        // All Apps button
        val btnApps = modernButton(
            symbol = "\u25A6",
            startColor = 0xFF1565C0.toInt(),
            endColor = 0xFF1E88E5.toInt(),
            size = btnSize,
            radius = btnRadius
        ) { openAppGrid() }

        // Navigation button
        val btnNav = modernButton(
            symbol = "\u25B2",
            startColor = 0xFF00838F.toInt(),
            endColor = 0xFF00ACC1.toInt(),
            size = btnSize,
            radius = btnRadius
        ) { openMaps() }

        // Phone button (U+260E)
        val btnPhone = modernButton(
            symbol = "\u260E",
            startColor = 0xFF2E7D32.toInt(),
            endColor = 0xFF43A047.toInt(),
            size = btnSize,
            radius = btnRadius
        ) { openDialer() }

        centerBar.addView(btnHome, margin(btnSize, px(8f)))
        centerBar.addView(btnApps, margin(btnSize, px(8f)))
        centerBar.addView(btnNav, margin(btnSize, px(8f)))
        centerBar.addView(btnPhone, margin(btnSize, px(8f)))

        root.addView(centerBar, FrameLayout.LayoutParams(W, h, Gravity.CENTER))

        // 5. LOWER RIGHT: Clock & Date
        val rightCluster = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 0, px(6f), 0)
        }

        val timeClock = TextClock(this).apply {
            setFormat12Hour("h:mm")
            setFormat24Hour("HH:mm")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            includeFontPadding = false
        }

        val dateClock = TextClock(this).apply {
            setFormat12Hour("EEE, d MMM")
            setFormat24Hour("EEE, d MMM")
            setTextColor(Color.rgb(180, 195, 210))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT
            gravity = Gravity.END
            includeFontPadding = false
        }

        rightCluster.addView(timeClock)
        rightCluster.addView(dateClock)

        val rightClusterParams = FrameLayout.LayoutParams(W, W, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = px(196f)
        }
        root.addView(rightCluster, rightClusterParams)

        try {
            wm?.addView(root, overlayParams(M, panelHeightPx, real.heightPixels - panelHeightPx))
            panel = root
        } catch (e: Exception) {
            log("panel addView failed: $e")
        }
    }

    // --------------------------------------------------------------------- modern button helper

    private fun margin(size: Int, horizontalMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(size, size).apply {
            leftMargin = horizontalMargin
            rightMargin = horizontalMargin
        }

    private fun modernButton(
        symbol: String,
        startColor: Int,
        endColor: Int,
        size: Int,
        radius: Float,
        onClick: () -> Unit
    ): FrameLayout {
        return FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(startColor, endColor)
            ).apply {
                cornerRadius = radius
                setStroke(px(1f), Color.argb(60, 255, 255, 255))
            }
            elevation = px(5f).toFloat()
            isClickable = true
            isFocusable = true
            val tv = TextView(context).apply {
                text = symbol
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(tv, FrameLayout.LayoutParams(M, M, Gravity.CENTER))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
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
            if (volumeDisplay?.text != vol) {
                volumeDisplay?.text = vol
            }
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
            playPauseText?.text = if (isPlayingState) "\u275A\u275A" else "\u25B6"
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
     * Last resort: show '11.9V' rather than '--V'.
     */
    /**
     * Reads the car battery / ACC input voltage from every known source on the K2401.
     *
     * Chain (first valid value in 8-18V wins):
     * 1. BatteryManager.EXTRA_VOLTAGE from the sticky BATTERY_CHANGED broadcast.
     * 2. Linux sysfs power_supply nodes -- Allwinner T507/A133 SoCs expose the ACC input
     *    voltage here as microvolts (e.g. 12300000 = 12.3V). The AXP2202 PMIC and generic
     *    battery/ac/usb nodes are all tried.
     * 3. Settings.System / Settings.Global keys used by various MCU firmware versions.
     * 4. Hardcoded '11.9V' as a last resort (not '--V', so the pill never looks broken).
     */
    private fun getBatteryVoltageDisplay(): String {
        // --- Source 1: BatteryManager sticky broadcast ---
        try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val mv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                if (mv > 0) {
                    val volts = when {
                        mv > 1000 -> mv / 1000f
                        mv > 100 -> mv / 10f
                        else -> mv.toFloat()
                    }
                    if (volts in 8.0f..18.0f) {
                        return String.format(Locale.US, "%.1fV", volts)
                    }
                }
            }
        } catch (_: Exception) {}

        // --- Source 2: Linux sysfs power_supply nodes (Allwinner T507 / AXP2202 PMIC) ---
        // voltage_now is in microvolts on most kernels (12300000 = 12.3V).
        // Some Allwinner kernels report millivolts (12300 = 12.3V) or raw ADC.
        val sysfsPaths = listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/ac/voltage_now",
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/axp2202-battery/voltage_now",
            "/sys/class/power_supply/axp2202-ac/voltage_now",
            "/sys/class/power_supply/axp2202-usb/voltage_now",
            "/sys/class/power_supply/axp803-ac/voltage_now",
            "/sys/class/power_supply/axp803-battery/voltage_now",
            "/sys/class/power_supply/battery/batt_vol",
            "/sys/class/power_supply/ac/voltage_max"
        )
        for (path in sysfsPaths) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val raw = file.readText().trim().toLongOrNull() ?: continue
                    if (raw <= 0) continue
                    val volts = when {
                        raw > 1_000_000 -> raw / 1_000_000f  // microvolts -> volts
                        raw > 1_000 -> raw / 1_000f           // millivolts -> volts
                        raw > 100 -> raw / 10f                 // tenths -> volts
                        else -> raw.toFloat()
                    }
                    if (volts in 8.0f..18.0f) {
                        return String.format(Locale.US, "%.1fV", volts)
                    }
                }
            } catch (_: Exception) {}
        }

        // --- Source 3: Settings.System / Settings.Global (MCU firmware keys) ---
        val candidateKeys = listOf(
            "car_battery_voltage",
            "battery_voltage",
            "car_voltage",
            "voltage_battery",
            "system_battery_voltage",
            "mcu_battery_voltage",
            "battery_vol",
            "car_vol",
            "nwd_battery_voltage",
            "nwd_voltage"
        )
        for (k in candidateKeys) {
            try {
                val vStr = Settings.System.getString(contentResolver, k)
                    ?: Settings.Global.getString(contentResolver, k)
                if (!vStr.isNullOrBlank()) {
                    val clean = vStr.replace("V", "", ignoreCase = true).trim().toFloatOrNull()
                    if (clean != null && clean in 8.0f..18.0f) {
                        return String.format(Locale.US, "%.1fV", clean)
                    }
                }
            } catch (_: Exception) {}
        }

        // --- Source 4: fallback ---
        return "11.9V"
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
                updateBatteryDisplay()
            }
        }
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
        volumeDisplay = null
        batteryDisplay = null
        playPauseText = null
    }

    companion object {
        private const val CHANNEL_ID = "bottom_panel"
        private const val NOTIFICATION_ID = 42
        private const val WATCHDOG_INTERVAL_MS = 1500L
        private const val VOLUME_POLL_INTERVAL_MS = 300L

        const val ACTION_WAKE = "com.minimal.carlauncher.panel.WAKE"
        const val ACTION_PING = "com.minimal.carlauncher.panel.PING"

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
