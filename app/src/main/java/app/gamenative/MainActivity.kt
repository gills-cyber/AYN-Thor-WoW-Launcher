package app.gamenative

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.PluviaMain
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarHostController
import app.gamenative.utils.LocaleHelper
import app.gamenative.ui.util.SnackbarManager
import com.winlator.core.AppUtils
import com.winlator.inputcontrols.ControllerManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.EnumSet
import timber.log.Timber

/** How long the app may be off screen before the game is stopped. */
private const val GAME_PAUSE_DELAY_MS = 1_500L
private const val EXTRA_MOVED_TO_PRIMARY = "app.gamenative.MOVED_TO_PRIMARY_DISPLAY"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val onSetSystemUi: (AndroidEvent.SetSystemUIVisibility) -> Unit = {
        desiredSystemUiVisible = it.visible
        applyImmersiveMode()
    }

    private val onSetAllowedOrientation: (AndroidEvent.SetAllowedOrientation) -> Unit = {
        setOrientationTo(it.orientations)
    }

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        finishAndRemoveTask()
    }

    private var controllerInputManager: InputManager? = null
    private val controllerDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            ControllerManager.getInstance().onDeviceConnected(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            ControllerManager.getInstance().onDeviceDisconnected(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            ControllerManager.getInstance().onDeviceConnected(deviceId)
        }
    }

    private var desiredSystemUiVisible: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        // Initialize PrefManager to read language setting
        PrefManager.init(newBase)

        // Apply the saved language preference before creating the activity
        val languageCode = PrefManager.appLanguage
        val context = LocaleHelper.applyLanguage(newBase, languageCode)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Full immersive mode - transparent system bars for console-like experience
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Dual-screen handhelds (AYN Thor): always start on the main (top) screen.
        if (savedInstanceState == null && moveToPrimaryDisplayIfNeeded()) return

        // stale keepAlive from a prior crash/swipe — no container is actually running
        if (PluviaApp.keepAlive && PluviaApp.xEnvironment == null) {
            Timber.w("onCreate: clearing stale keepAlive — no container running")
            PluviaApp.shutdownEnvironment()
        }

        // Apply immersive mode based on user preference
        applyImmersiveMode()

        // Initialize the controller management system
        ControllerManager.getInstance().init(applicationContext)
        controllerInputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        controllerInputManager?.registerInputDeviceListener(controllerDeviceListener, null)
        AppUtils.keepScreenOn(this)

        PluviaApp.events.on<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.on<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        setContent {
            val snackbarController = remember { SnackbarHostController() }
            CompositionLocalProvider(
                LocalSnackbarHostController provides snackbarController,
            ) {
                PluviaMain()
            }
        }
    }
    /**
     * When the launcher was opened on a secondary screen (the Thor's bottom screen), reopen it
     * on the primary one and close this instance. Returns true when this instance is closing.
     * Never done while a game is running, because closing the activity would end the game.
     */
    private fun moveToPrimaryDisplayIfNeeded(): Boolean {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            if (PluviaApp.keepAlive || PluviaApp.xEnvironment != null) return false
            if (intent?.getBooleanExtra(EXTRA_MOVED_TO_PRIMARY, false) == true) return false
            @Suppress("DEPRECATION")
            val current = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay)
                ?: return false
            if (current.displayId == Display.DEFAULT_DISPLAY) return false

            val relaunch = Intent(intent ?: Intent()).apply {
                setClass(this@MainActivity, MainActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(EXTRA_MOVED_TO_PRIMARY, true)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            Timber.i("Started on display %d, reopening on the primary display", current.displayId)
            startActivity(relaunch, options.toBundle())
            redirectedToPrimary = true
            finishAndRemoveTask()
            return true
        } catch (e: Exception) {
            Timber.w(e, "Could not move the launcher to the primary display")
            redirectedToPrimary = false
            return false
        }
    }

    override fun onDestroy() {
        if (redirectedToPrimary) {
            // Nothing was set up in onCreate, and the replacement activity must not see ActivityDestroyed.
            super.onDestroy()
            return
        }
        // emit before super so Compose DisposableEffects (which unregister
        // listeners during super.onDestroy's lifecycle transition) still fire
        if (!isChangingConfigurations) {
            PluviaApp.events.emit(AndroidEvent.ActivityDestroyed)

            // if exit() didn't run (listener already unregistered, race, etc.)
            // force-clear so the app isn't stuck on next launch
            if (PluviaApp.keepAlive) {
                Timber.w("onDestroy: keepAlive still set after ActivityDestroyed — forcing cleanup")
                PluviaApp.shutdownEnvironment()
            }
        }

        super.onDestroy()

        pauseHandler.removeCallbacks(gamePauseRunnable)
        if (pendingGamePause) {
            // This activity is going away (it is being rebuilt) with a pause still owed: do it now
            // rather than lose it. The next onResume resumes the game as usual.
            gamePauseRunnable.run()
        }

        controllerInputManager?.unregisterInputDeviceListener(controllerDeviceListener)
        controllerInputManager = null

        PluviaApp.events.off<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.off<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
    }

    private fun hasReadyGameLifecycleState(action: String): Boolean {
        if (!PluviaApp.keepAlive) return false
        if (!PluviaApp.hasValidSuspendPolicyState()) {
            Timber.d("Skipping game %s because suspend policy state is not initialized", action)
            return false
        }
        if (PluviaApp.xEnvironment == null) {
            Timber.d("Skipping game %s because xEnvironment is not ready", action)
            return false
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        PluviaApp.isActivityInForeground = true

        // Re-apply immersive mode to ensure fullscreen persists
        if (!desiredSystemUiVisible) {
            applyImmersiveMode()
        }

        // The app came back before the delayed pause ran: the game was never stopped.
        val pauseWasPending = pendingGamePause
        if (pauseWasPending) {
            pendingGamePause = false
            pauseHandler.removeCallbacks(gamePauseRunnable)
            Timber.d("Game pause cancelled: app returned within the grace period")
        }

        // Resume game according to the active suspend policy.
        if (!pauseWasPending && hasReadyGameLifecycleState("resume")) {
            when {
                PluviaApp.isNeverSuspendMode() -> {
                    Timber.d("Game resume skipped due to suspend policy=never")
                }
                PluviaApp.isOverlayPaused -> {
                    if (PluviaApp.isBootingSplashShowing) {
                        // The Resume overlay sits under the booting splash, so the user could
                        // never press it; nothing is being played yet, so just carry on booting.
                        PluviaApp.xEnvironment?.onResume()
                        PluviaApp.isOverlayPaused = false
                        Timber.d("Game resumed automatically: still booting behind the splash")
                    } else if (PluviaApp.isManualSuspendMode()) {
                        Timber.d("Game remains suspended until user presses Resume")
                    }
                }
                else -> {
                    PluviaApp.xEnvironment?.onResume()
                    Timber.d("Game resumed")
                }
            }
        }
    }

    private val pauseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGamePause = false
    private var redirectedToPrimary = false

    /**
     * Stops the game a moment after the app loses the screen instead of at once, so a brief
     * interruption (a system dialog, a quick glance at another window) does not stop and restart it.
     */
    private val gamePauseRunnable = Runnable {
        pendingGamePause = false
        if (PluviaApp.isActivityInForeground) return@Runnable
        if (hasReadyGameLifecycleState("pause")) {
            when {
                PluviaApp.isNeverSuspendMode() -> {
                    Timber.d("Game pause skipped due to suspend policy=never")
                }
                else -> {
                    PluviaApp.xEnvironment?.onPause()
                    if (PluviaApp.isManualSuspendMode()) {
                        PluviaApp.isOverlayPaused = true
                        Timber.d("Game paused due to app backgrounded (manual resume required)")
                    } else {
                        Timber.d("Game paused due to app backgrounded")
                    }
                }
            }
        }
    }

    override fun onPause() {
        PluviaApp.isActivityInForeground = false
        pauseHandler.removeCallbacks(gamePauseRunnable)
        pendingGamePause = true
        pauseHandler.postDelayed(gamePauseRunnable, GAME_PAUSE_DELAY_MS)
        super.onPause()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        var eventDispatched = PluviaApp.events.emit(AndroidEvent.KeyEvent(event)) { keyEvent ->
            keyEvent.any { it }
        } == true

        if (!eventDispatched) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && PluviaApp.keepAlive) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    PluviaApp.events.emit(AndroidEvent.BackPressed)
                    eventDispatched = true
                } else if (BuildConfig.MODERN_ANDROID && event.action == KeyEvent.ACTION_UP) {
                    eventDispatched = true
                }
            }
        }

        return if (!eventDispatched) super.dispatchKeyEvent(event) else true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        val eventDispatched = PluviaApp.events.emit(AndroidEvent.MotionEvent(ev)) { event ->
            event.any { it }
        } == true

        return if (!eventDispatched) super.dispatchGenericMotionEvent(ev) else true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Log.d("MainActivity", "Requested orientation: $requestedOrientation => ${Orientation.fromActivityInfoValue(requestedOrientation)}")
    }

    private fun applyImmersiveMode() {
        if (desiredSystemUiVisible) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !desiredSystemUiVisible) {
            applyImmersiveMode()
        }
    }

    private fun setOrientationTo(conformTo: EnumSet<Orientation>) {
        requestedOrientation = when {
            conformTo.contains(Orientation.PORTRAIT) -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Fixed landscape, not sensor landscape: the tilt sensor could flip the picture
            // upside down on a clamshell handheld.
            conformTo.contains(Orientation.LANDSCAPE) && conformTo.contains(Orientation.REVERSE_LANDSCAPE) ->
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            conformTo.contains(Orientation.LANDSCAPE) -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            conformTo.contains(Orientation.REVERSE_LANDSCAPE) -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
