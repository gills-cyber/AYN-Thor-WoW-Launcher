package app.gamenative.utils

import android.content.Context
import android.os.SystemClock

/**
 * "Close the app when the game exits": after quitting WoW the launcher closes itself, so the
 * device goes straight back to whatever started it (the Cocoon frontend, the home screen)
 * instead of stopping on the launcher's "Launch WoW" screen.
 *
 * The app stays open when the game never showed a window, or ends with an error status before its
 * window has been up for a minute, because that is a failed launch.
 *
 * A finished session always ends the app process (the next launch needs a clean one). Normally
 * the app is restarted straight away; with this option it is closed instead.
 */
object ExitBehavior {
    private const val PREFS = "wow_forever"
    private const val KEY_CLOSE_APP = "close_app_on_game_exit"

    /** A session whose game window was up at least this long is treated as a normal exit, whatever the status. */
    private const val MIN_SESSION_MS = 60_000L

    /** When the game's window first appeared (uptime, so time asleep does not count), 0 = not yet. */
    @Volatile
    private var windowShownAt = 0L

    @Volatile
    private var lastExitStatus: Int? = null

    /** Set by the exit code for the navigation code, which closes the app instead of restarting it. */
    @Volatile
    private var closeRequested = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLOSE_APP, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLOSE_APP, enabled).apply()

    /** Call when the game process is started. */
    fun onGameStarted() {
        windowShownAt = 0L
        lastExitStatus = null
        closeRequested = false
    }

    /** Call when a game window appears. Only the first one of a session counts. */
    fun onGameWindowShown() {
        if (windowShownAt == 0L) windowShownAt = SystemClock.uptimeMillis()
    }

    /** Call with the game process's exit status when it ends by itself. */
    fun onGameTerminated(status: Int) {
        lastExitStatus = status
    }

    /** Whether the app should close now that the game has ended by itself. */
    fun shouldCloseApp(context: Context): Boolean {
        if (!isEnabled(context)) return false
        // The game never got as far as showing a window: a failed launch, stay on the launcher.
        val shown = windowShownAt
        if (shown == 0L) return false
        val ranLongEnough = SystemClock.uptimeMillis() - shown >= MIN_SESSION_MS
        return lastExitStatus == 0 || ranLongEnough
    }

    /** Records that this exit should close the app. */
    fun requestClose() {
        closeRequested = true
    }

    /** True once after [requestClose]. */
    fun consumeCloseRequest(): Boolean {
        val requested = closeRequested
        closeRequested = false
        return requested
    }
}
