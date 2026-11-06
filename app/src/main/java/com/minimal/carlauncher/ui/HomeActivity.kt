package com.minimal.carlauncher.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.provider.AlarmClock
import android.provider.Settings
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.minimal.carlauncher.CarLauncherApp
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.location.HeadingSource
import com.minimal.carlauncher.location.VehicleState
import com.minimal.carlauncher.panel.PanelService
import com.minimal.carlauncher.update.ApkInstaller
import com.minimal.carlauncher.update.UpdateState
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.launch

/**
 * The HOME activity.
 *
 * Nothing heavy or fallible happens in onCreate: the app list loads asynchronously and every
 * pref decode falls back to defaults. A launcher that throws here on a head unit with no
 * second home app leaves the device recoverable only over adb.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()

    private lateinit var dock: DockController
    private lateinit var drawer: DrawerController
    private lateinit var cards: QuickCardsController
    private lateinit var map: MapPortalController
    private lateinit var radioWidget: RadioWidgetController
    private lateinit var liveCards: LiveCardsController

    private val app: CarLauncherApp get() = application as CarLauncherApp

    private var lastCardinal: String? = null
    private var leftAtElapsedMs = 0L

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.onPermissionResult()
    }

    private val unknownSources = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // The user may have declined; re-check rather than assuming.
        val state = app.updateRepository.state.value
        if (state is UpdateState.ReadyToInstall && ApkInstaller.canInstall(this)) {
            ApkInstaller.install(this, state.file)?.let { toast(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = app.appRepository

        dock = DockController(this, binding.dockStrip, repository, lifecycleScope)
        drawer = DrawerController(this, binding, repository, lifecycleScope) { entry ->
            dock.pin(entry)
        }
        cards = QuickCardsController(this, binding, repository, lifecycleScope)
        map = MapPortalController(this, binding) { isMoving() }
        radioWidget = RadioWidgetController(
            this, binding, app.radioRepository, repository, lifecycleScope
        )

        dock.bind()
        drawer.bind()
        cards.bind()
        map.bind()
        radioWidget.bind()
        liveCards = LiveCardsController(this, binding, cards)
        liveCards.bind()

        styleSporty()
        bindDashboardClicks()
        bindDockActions()
        bindBackBehaviour()
        observe()

        // Bottom panel's Apps button may have started us with this extra.
        if (intent.getBooleanExtra("open_drawer", false)) {
            binding.root.post { drawer.open() }
        }
        // Bottom panel's fuel chip.
        if (intent.getBooleanExtra(FuelDialog.EXTRA_FUEL, false)) {
            binding.root.post { FuelDialog.show(this) }
        }
    }

    // ------------------------------------------------------------------ wiring

    private fun bindDashboardClicks() {
        binding.gaugeSpeed.setOnClickListener { view ->
            val state = viewModel.vehicle.value
            if (com.minimal.carlauncher.core.CameraStore.current != null) { muteTap(); return@setOnClickListener }
            when {
                !state.permissionGranted -> requestLocation()
                !state.gpsEnabled -> IntentUtil.openLocationSettings(this)
                else -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.toggleSpeedUnit()
                    render(viewModel.vehicle.value)
                }
            }
        }
        binding.gaugeClock.setOnClickListener { openClock() }
        // Long-press the speed: timed acceleration runs.
        binding.gaugeSpeed.setOnLongClickListener {
            startActivity(android.content.Intent(this, TripsActivity::class.java)
                .putExtra(TripsActivity.EXTRA_TAB, TripsActivity.TAB_PERF))
            true
        }
    }

    private fun bindDockActions() {
        binding.dockStrip.btnAllApps.setOnClickListener { drawer.open() }
        binding.btnTheme.setOnClickListener { toggleTheme() }
        // Tap goes to the car / factory settings, long-press to this launcher's own settings -
        // so the common case stays one tap and the system dock keeps its three fixed actions.
        binding.btnSettings.setOnClickListener { openVehicleSettings() }
        binding.btnSettings.setOnLongClickListener {
            openSettings()
            true
        }
        binding.btnAbout.setOnClickListener { openAbout() }
    }

    /** Clock, world clock and alarms live in one app; AOSP DeskClock is the fallback. */
    private fun openClock() {
        if (IntentUtil.startSafely(this, Intent(AlarmClock.ACTION_SHOW_ALARMS))) return
        if (IntentUtil.launchPackage(this, "com.android.deskclock")) return
        toast(getString(R.string.could_not_launch))
    }

    /**
     * Head units put the equaliser, steering-wheel learning and CAN / vehicle options in a
     * vendor app rather than Android Settings, so the button opens whichever app the user
     * chose (launcher settings), falling back to Android's own Settings.
     */
    private fun openVehicleSettings() {
        Prefs.vehicleSettingsPackage?.let { stored ->
            if (IntentUtil.launchStored(this, stored, binding.btnSettings)) return
            Prefs.vehicleSettingsPackage = null   // it was uninstalled
        }
        if (!IntentUtil.openSystemSettings(this)) toast(getString(R.string.could_not_launch))
    }

    /** One-tap day / night switch. "Follow system" remains available in launcher settings. */
    private fun toggleTheme() {
        val nightNow = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val mode = if (nightNow) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        binding.btnTheme.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Prefs.themeMode = mode
        // AppCompat recreates the activity itself; the ViewModel keeps the GPS session alive.
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun isMoving(): Boolean = viewModel.vehicle.value.speedMps > MOVING_MPS

    /** A launcher must never finish itself - on some ROMs that leaves a blank screen. */
    private fun bindBackBehaviour() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isOpen) drawer.close() else map.handleBack()
            }
        })
    }

    private fun observe() {
        val repository = app.appRepository

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.apps.collect { apps ->
                    drawer.submitApps(apps)
                    dock.bind()
                    cards.refreshLabels()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.packageRemoved.collect { packageName ->
                    dock.onPackageRemoved(packageName)
                    cards.onPackageRemoved(packageName)
                    if (Prefs.radioPackage == packageName) Prefs.radioPackage = null
                    if (Prefs.vehicleSettingsPackage?.let { IntentUtil.packageOf(it) } == packageName) {
                        Prefs.vehicleSettingsPackage = null
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.vehicle.collect { render(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.radioRepository.state.collect { radioWidget.render(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.updateRepository.badgeVisible.collect { visible ->
                    binding.updateBadge.visibility =
                        if (visible) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ------------------------------------------------------------- dashboard render

    private var muteArmedUntil = 0L

    /** Tap the speed during a camera warning: a second tap within 4 s mutes that camera. */
    private fun muteTap() {
        val cam = com.minimal.carlauncher.core.CameraStore.current?.cam ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < muteArmedUntil) {
            muteArmedUntil = 0L
            com.minimal.carlauncher.core.CameraStore.mute(cam)
            Toast.makeText(this, "Camera muted. Unmute it in Trips \u2192 Cameras.", Toast.LENGTH_SHORT).show()
        } else {
            muteArmedUntil = now + 4_000L
            Toast.makeText(this, "Tap again to mute this camera", Toast.LENGTH_SHORT).show()
        }
    }

    private var flashSeen = 0
    private val cameraListener: () -> Unit = {
        render(viewModel.vehicle.value)
        val cs = com.minimal.carlauncher.core.CameraStore
        if (cs.flashSeq != flashSeen) {
            flashSeen = cs.flashSeq
            if (cs.current != null) flashCards(if (cs.over) com.minimal.carlauncher.core.Neon.RED else com.minimal.carlauncher.core.Neon.AMBER)
        }
    }

    /**
     * Camera chip in the circle's bottom-right corner: "AI CAMERA" / "SPEED 50" over the distance,
     * amber, red when over the limit. Gone once the camera is passed.
     */
    private fun renderCameraChip() {
        val cs = com.minimal.carlauncher.core.CameraStore
        val cam = cs.current
        val chip = binding.cameraChip
        if (cam == null) { chip.visibility = View.GONE; cardGlow(null); return }
        val color = if (cs.over) com.minimal.carlauncher.core.Neon.RED else com.minimal.carlauncher.core.Neon.AMBER
        cardGlow(color)
        binding.cameraChipTitle.text = "\uD83D\uDCF7 " + when {
            cam.cam.limit > 0 -> "SPEED ${cam.cam.limit}"
            cam.cam.kind == "speed" -> "SPEED CAM"
            else -> "AI CAMERA"
        } + (if (cs.another) " +1" else "")
        binding.cameraChipDistance.text = if (cam.distanceM >= 1000)
            String.format(java.util.Locale.US, "%.1f km", cam.distanceM / 1000.0) else "${(cam.distanceM / 10) * 10} m"
        binding.cameraChipTitle.setTextColor(color)
        binding.cameraChipDistance.setTextColor(color)
        chip.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18f * resources.displayMetrics.density
            setColor(0xE6101018.toInt())
            setStroke((2f * resources.displayMetrics.density).toInt(), color)
        }
        chip.visibility = View.VISIBLE
    }

    /** Border rings on the Phone Link and Music cards while a camera is ahead (null = none). */
    private var glowColor: Int? = null
    private var cardPulse: android.animation.ValueAnimator? = null
    private val glowRings = HashMap<View, android.graphics.drawable.GradientDrawable>()
    private val GLOW_STEADY = 190

    /** Steady amber / red border from the first alert until the camera is passed. */
    private fun cardGlow(color: Int?) {
        if (color == glowColor) return
        glowColor = color
        val d = resources.displayMetrics.density
        for (card in listOf<View>(binding.cardProjection, binding.cardMusic)) {
            if (color == null) { card.foreground = null; glowRings.remove(card); continue }
            val ring = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.corner)
                setStroke((4f * d).toInt(), color)
                setColor(com.minimal.carlauncher.core.Neon.alpha(color, 36))
                alpha = GLOW_STEADY
            }
            glowRings[card] = ring
            card.foreground = ring
        }
        if (color == null) { cardPulse?.cancel(); cardPulse = null }
    }

    /** Three strong pulses at an alert, then back to the steady glow. */
    private fun flashCards(color: Int) {
        cardGlow(color)
        cardPulse?.cancel()
        cardPulse = android.animation.ValueAnimator.ofInt(255, 30, 255, 30, 255, 30, GLOW_STEADY).apply {
            duration = 2_400L
            addUpdateListener { v -> glowRings.values.forEach { it.alpha = v.animatedValue as Int } }
            start()
        }
    }

    private var perfShownUntil = 0L
    private var statusColor = 0

    /** Shows a just-timed run under the speed for 8 s ("0-100 km/h 9.8 s - NEW BEST"). */
    private fun showPerf(run: com.minimal.carlauncher.core.PerfRun, best: Boolean) {
        perfShownUntil = android.os.SystemClock.elapsedRealtime() + 8_000L
        val st = binding.textSpeedStatus
        if (statusColor == 0) statusColor = st.currentTextColor
        st.text = String.format(java.util.Locale.US, "%s km/h  %.1f s%s", run.kind.replace("-", "\u2013"), run.sec,
            if (best) "  \u00B7  NEW BEST" else "")
        st.setTextColor(if (best) com.minimal.carlauncher.core.Neon.PINK else com.minimal.carlauncher.core.Neon.CYAN)
        st.postDelayed({
            if (android.os.SystemClock.elapsedRealtime() < perfShownUntil) return@postDelayed  // a newer run is showing
            st.setTextColor(statusColor)
            render(viewModel.vehicle.value)
        }, 8_100L)
    }

    /** Racing look: forward-leaning digits, neon seconds, glowing speed, segment bars. */
    private fun styleSporty() {
        val N = com.minimal.carlauncher.core.Neon
        val audiowide = try { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.audiowide) } catch (_: Exception) { null }
        val exo = try { androidx.core.content.res.ResourcesCompat.getFont(this, R.font.exo2_black_italic) } catch (_: Exception) { null }
        /** Neon gradient (cyan, violet, pink) across the text, with a soft violet glow. */
        fun neon(tv: android.widget.TextView, face: android.graphics.Typeface?, glow: Float) {
            face?.let { tv.setTypeface(it, android.graphics.Typeface.NORMAL) }
            tv.paint.textSkewX = 0f
            tv.setShadowLayer(glow, 0f, 0f, N.alpha(N.VIOLET, 150))
            tv.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                (v as android.widget.TextView).paint.shader = android.graphics.LinearGradient(
                    0f, 0f, (r - l).toFloat().coerceAtLeast(1f), 0f, N.GRADIENT, null, android.graphics.Shader.TileMode.CLAMP)
                v.invalidate()
            }
        }
        neon(binding.clockTime, audiowide, 14f)
        neon(binding.clockSeconds, audiowide, 8f)
        neon(binding.clockDay, audiowide, 6f)
        neon(binding.clockDate, audiowide, 6f)
        // Exo 2 Black Italic carries digits only; dashes fall back cleanly.
        neon(binding.textSpeed, exo, 20f)
        neon(binding.textSpeedUnit, audiowide, 6f)
        binding.textSpeed.setPadding(binding.textSpeed.paddingLeft, 0, binding.textSpeed.paddingRight + 10, 0)
        // The rev-counter arc round the map replaces the small bars.
        binding.secondsBar.visibility = View.GONE
        binding.speedBar.visibility = View.GONE
    }

    private fun render(state: VehicleState) {
        val unit = viewModel.speedUnit.value

        binding.textSpeed.text = Format.speedText(unit, state.speedMps, state.hasFix)
        binding.compassBezel.setSpeed(
            if (!state.hasFix || state.speedMps < com.minimal.carlauncher.core.Constants.SPEED_NOISE_GATE_MPS) 0f
            else state.speedMps * 3.6f)
        binding.textSpeedUnit.text = Format.unitLabel(unit)
        renderCameraChip()
        if (android.os.SystemClock.elapsedRealtime() >= perfShownUntil) {
            binding.textSpeedStatus.setText(
                when {
                    !state.permissionGranted -> R.string.gps_permission_needed
                    !state.gpsEnabled -> R.string.gps_disabled
                    !state.hasFix -> R.string.gps_acquiring
                    else -> R.string.gps_lock
                }
            )
            if (state.fromPhone) binding.textSpeedStatus.text = getString(R.string.gps_phone)
            // While searching, show how many satellites it sees: 0 under a roof, climbing in the open.
            if (state.permissionGranted && state.gpsEnabled && !state.hasFix) {
                com.minimal.carlauncher.core.GnssInfo.label().takeIf { it.isNotEmpty() }?.let {
                    binding.textSpeedStatus.text = getString(R.string.gps_acquiring) + " \u00B7 " + it
                }
            }
            binding.textSpeedStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        !state.permissionGranted || !state.gpsEnabled -> R.color.cockpit_badge
                        !state.hasFix -> R.color.cockpit_warn
                        else -> R.color.cockpit_ok
                    }
                )
            )
        }

        map.render(state)

        val heading = state.headingDeg
        if (heading == null) {
            lastCardinal = null
            binding.textCardinal.setText(R.string.speed_placeholder)
            binding.textDegrees.text = ""
            binding.textHeadingSource.setText(
                if (viewModel.compassAvailable) R.string.heading else R.string.no_compass
            )
        } else {
            val cardinal = Format.cardinalWithHysteresis(
                heading, lastCardinal, Prefs.compass16Point
            )
            lastCardinal = cardinal
            binding.textCardinal.text = cardinal
            binding.textDegrees.text = Format.degreesText(heading)
            // Showing the source keeps a wrong stationary magnetometer reading from being
            // mistaken for the truth.
            binding.textHeadingSource.setText(
                if (state.headingSource == HeadingSource.GPS) R.string.heading_source_gps
                else R.string.heading_source_mag
            )
        }
    }

    // -------------------------------------------------------------------- dialogs

    private fun openAbout() {
        AboutDialog.show(this, app.updateRepository) { state ->
            // Never put an update prompt in front of a driver who is moving.
            if (viewModel.vehicle.value.speedMps > MOVING_MPS) {
                toast(getString(R.string.update_deferred_while_driving))
                return@show
            }
            val release = when (state) {
                is UpdateState.Available -> state.release
                is UpdateState.ReadyToInstall -> state.release
                else -> return@show
            }
            UpdateDialog.show(this, app.updateRepository, release) {
                toast(getString(R.string.update_unknown_sources))
                unknownSources.launch(ApkInstaller.unknownSourcesIntent(this))
            }
        }
    }

    private fun openSettings() {
        SettingsDialog.show(
            activity = this,
            repository = app.appRepository,
            scope = lifecycleScope,
            onDockReset = { dock.reset() },
            onPickRadioApp = { radioWidget.pickRadioApp() },
            onRadioAccess = { radioWidget.requestSessionAccess() },
            onRadioDiagnostics = { radioWidget.showDiagnostics() },
            onPrefsChanged = {
                viewModel.refreshPrefs()
                lastCardinal = null
                render(viewModel.vehicle.value)
            }
        )
    }

    private fun requestLocation() {
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        // Coming back from another app after a while: a driver should never find a stale
        // search box waiting for them.
        if (leftAtElapsedMs > 0L &&
            SystemClock.elapsedRealtime() - leftAtElapsedMs > STALE_RETURN_MS
        ) {
            resetToDashboard()
        }
        app.updateRepository.maybeCheck()
    }

    override fun onResume() {
        super.onResume()
        com.minimal.carlauncher.core.PerfLive.listener = { run, best -> showPerf(run, best) }
        flashSeen = com.minimal.carlauncher.core.CameraStore.flashSeq
        com.minimal.carlauncher.core.CameraStore.addListener(cameraListener)
        viewModel.start()
        map.onResume()
        app.radioRepository.start()
        viewModel.refreshPrefs()
        cards.refreshLabels()
        liveCards.start()
        render(viewModel.vehicle.value)
        maybeAutoStartDashcam()
        maybeStartPanel()
    }

    private fun maybeStartPanel() {
        if (!Prefs.panelEnabled) return
        if (!Settings.canDrawOverlays(this)) return
        val intent = Intent(this, PanelService::class.java).apply {
            action = PanelService.ACTION_WAKE
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            PanelService.log(this, "panel start from launcher failed: $e")
        }
    }

    /**
     * Starts the DVR app once per launcher process so its floating overlay comes up on the
     * dashboard. Deliberately delayed: launching anything before the home screen has drawn
     * makes a head unit look like it is booting into the wrong app.
     */
    private fun maybeAutoStartDashcam() {
        if (app.dashcamAutoStartDone || !Prefs.dashcamAutoStart) return
        val stored = Prefs.dashcamPackage ?: return
        app.dashcamAutoStartDone = true

        binding.root.postDelayed({
            if (!IntentUtil.launchStored(this, stored, null)) {
                Prefs.dashcamPackage = null
                toast(getString(R.string.app_not_installed))
                return@postDelayed
            }
            if (Prefs.dashcamReturnHome) {
                // Best effort. Android 10+ restricts background activity starts, so some ROMs
                // will drop this and the user presses HOME once instead.
                binding.root.postDelayed({ returnToDashboard() }, DASHCAM_RETURN_DELAY_MS)
            }
        }, DASHCAM_START_DELAY_MS)
    }

    private fun returnToDashboard() {
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Blocked by the background-activity-start policy; nothing useful to do.
        }
    }

    override fun onPause() {
        super.onPause()
        com.minimal.carlauncher.core.PerfLive.listener = null
        com.minimal.carlauncher.core.CameraStore.removeListener(cameraListener)
        // Unregister the moment anything else comes to the foreground - this process is alive
        // for as long as the head unit is powered.
        viewModel.stop()
        map.onPause()
        app.radioRepository.stop()
        liveCards.stop()
        leftAtElapsedMs = SystemClock.elapsedRealtime()
    }

    override fun onDestroy() {
        map.onDestroy()
        super.onDestroy()
    }

    /** HOME pressed while already home: singleTask re-delivers the intent here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) resetToDashboard()
        if (intent.getBooleanExtra("open_drawer", false)) drawer.open()
        if (intent.getBooleanExtra(FuelDialog.EXTRA_FUEL, false)) FuelDialog.show(this)
    }

    private fun resetToDashboard() {
        drawer.reset()
        map.reset()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        /** ~5 km/h. */
        const val MOVING_MPS = 1.4f
        const val STALE_RETURN_MS = 30_000L
        const val DASHCAM_START_DELAY_MS = 1_500L
        const val DASHCAM_RETURN_DELAY_MS = 3_000L
    }
}
