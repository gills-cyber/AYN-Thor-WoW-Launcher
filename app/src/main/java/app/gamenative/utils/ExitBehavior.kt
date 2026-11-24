package app.gamenative.utils

import android.content.Context
import android.os.SystemClock

/**
 * "Close the app when the game exits": after quitting WoW the launcher closes itself, so the
 * device goes straight back to whatever started it (the Cocoon frontend, the home screen)
 * instead of stopping on the launcher's "Launch WoW" screen.
 *
 * The app stays open when the game ends within the first minute with an error status, because
 * that is a failed launch and the launcher is where the error message is shown.
 */
object ExitBehavior {
    private const val PREFS = "wow_forever"
    private const val KEY_CLOSE_APP = "close_app_on_game_exit"

    /** A session at least this long that ends is treated as a normal exit, whatever the status. */
    private const val MIN_SESSION_MS = 60_000L

    @Volatile
    private var sessionStartedAt = 0L

    @Volatile
    private var lastExitStatus: Int? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLOSE_APP, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLOSE_APP, enabled).apply()

    /** Call when the game process is started. */
    fun onGameStarted() {
        sessionStartedAt = SystemClock.elapsedRealtime()
        lastExitStatus = null
    }

    /** Call with the game process's exit status when it ends by itself. */
    fun onGameTerminated(status: Int) {
        lastExitStatus = status
    }

    /** Whether the app should close now that the game has ended. */
    fun shouldCloseApp(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val started = sessionStartedAt
        val ranLongEnough = started > 0L && SystemClock.elapsedRealtime() - started >= MIN_SESSION_MS
        return lastExitStatus == 0 || ranLongEnough
    }
}
