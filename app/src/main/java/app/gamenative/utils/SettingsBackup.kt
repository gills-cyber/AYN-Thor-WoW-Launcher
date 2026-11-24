package app.gamenative.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Backs up the launcher's settings and the game's settings folder, and restores them.
 *
 * A backup is one zip in `<game folder>/LauncherBackups`. That folder is on shared storage, so it
 * survives uninstalling the app. A backup holds:
 *  - the launcher's own preferences (switches, frame cap, button pad layout and remaps),
 *  - the game's `WTF` folder (key bindings, interface and addon settings, macros).
 *
 * The saved Battle.net login is never included: it is encrypted with a key that does not leave
 * the device's keystore, and a password does not belong in a file on shared storage.
 */
object SettingsBackup {
    const val DIR_NAME = "LauncherBackups"

    /** How many backups are kept; the oldest are deleted. */
    const val KEEP = 7

    private const val PREFIX = "settings-"
    private const val SUFFIX = ".zip"
    private const val PREFS_ENTRY = "prefs.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val GAME_PREFIX = "game/"
    private const val WTF = "WTF"

    private const val STATE_PREFS = "wow_forever"
    private const val KEY_LAST_AUTO = "last_auto_backup"
    private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** The preference files that are backed up. */
    private val PREF_FILES = listOf("wow_forever", "pad_settings")

    /** Keys that describe this install, not the user's choices: never restored over the current values. */
    private fun isLocalKey(key: String) = key.startsWith("game_path") || key == KEY_LAST_AUTO

    class Result(val file: File, val gameFiles: Int)

    fun dir(gameRoot: File) = File(gameRoot, DIR_NAME)

    /** Existing backups, newest first. */
    fun list(gameRoot: File): List<File> =
        dir(gameRoot).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** "2026-11-24 18:30" for a backup file, or its file name if it does not follow the pattern. */
    fun label(file: File): String {
        val stamp = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
        return runCatching {
            val date = checkNotNull(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(stamp))
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)
        }.getOrDefault(file.name)
    }

    /**
     * Writes a new backup of the launcher preferences and of [flavorDir]'s `WTF` folder.
     * The zip is written under a temporary name first, so a backup that is cut short never
     * looks like a complete one.
     */
    @Synchronized
    fun create(context: Context, gameRoot: File, flavorDir: String, prune: Boolean = true): Result {
        val dir = dir(gameRoot)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.path}")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "$PREFIX$stamp$SUFFIX")
        val temp = File(dir, "$PREFIX$stamp$SUFFIX.tmp")
        var gameFiles = 0
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(
                    JSONObject()
                        .put("format", 1)
                        .put("created", System.currentTimeMillis())
                        .put("flavorDir", flavorDir)
                        .toString().toByteArray(),
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(PREFS_ENTRY))
                zip.write(exportPrefs(context).toString().toByteArray())
                zip.closeEntry()

                val wtf = File(File(gameRoot, flavorDir), WTF)
                if (wtf.isDirectory) {
                    wtf.walkTopDown()
                        // The game keeps a ".bak" twin of most files; leaving them out halves the backup.
                        .filter { it.isFile && !it.name.endsWith(".bak", ignoreCase = true) }
                        .forEach { file ->
                            val rel = file.relativeTo(wtf).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry("$GAME_PREFIX$flavorDir/$WTF/$rel"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            gameFiles++
                        }
                }
            }
            if (!temp.renameTo(target)) throw IOException("Can't finish ${target.path}")
        } finally {
            temp.delete()
        }
        if (prune) prune(gameRoot)
        return Result(target, gameFiles)
    }

    /** Makes a backup when the last automatic one is older than a day. Never throws: a failed backup must not block a launch. */
    fun autoBackupIfDue(context: Context, gameRoot: File, flavorDir: String) {
        runCatching {
            val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_AUTO, 0L)
            if (last in 1..now && now - last < AUTO_INTERVAL_MS) return
            // Nothing worth keeping before the game has run once.
            if (!File(File(gameRoot, flavorDir), WTF).isDirectory) return
            create(context, gameRoot, flavorDir)
            prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
        }.onFailure { Timber.w(it, "Automatic settings backup failed") }
    }

    /**
     * Puts the launcher preferences and the game's `WTF` files from [backup] back. Files of the
     * game folder that are not in the backup are left alone. The current state is backed up first.
     * The caller must restart the app afterwards so every screen reads the restored values.
     *
     * @return the number of game files written.
     */
    @Synchronized
    fun restore(context: Context, gameRoot: File, flavorDir: String, backup: File): Int {
        // A safety copy of what is about to be replaced. Not pruned here, so it cannot push out the backup being restored.
        runCatching { create(context, gameRoot, flavorDir, prune = false) }
            .onFailure { Timber.w(it, "Could not back up the current settings before restoring") }

        val rootPath = gameRoot.canonicalFile
        var written = 0
        ZipFile(backup).use { zip ->
            // Read the preferences before touching anything, so a damaged backup fails early.
            val prefsJson = zip.getEntry(PREFS_ENTRY)?.let { entry ->
                JSONObject(zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
            }
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(GAME_PREFIX)) continue
                val rel = entry.name.removePrefix(GAME_PREFIX)
                val parts = rel.split('/')
                // Only "<client folder>/WTF/..." is ever written, and never outside the game folder.
                if (parts.size < 3 || parts[1] != WTF || parts.any { it.isEmpty() || it == "." || it == ".." }) continue
                val target = File(rootPath, rel).canonicalFile
                if (!target.path.startsWith(rootPath.path + File.separator)) continue
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".restore")
                try {
                    zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
                    if (!temp.renameTo(target)) {
                        target.delete()
                        if (!temp.renameTo(target)) throw IOException("Can't write ${target.path}")
                    }
                } finally {
                    temp.delete()
                }
                written++
            }
            prefsJson?.let { importPrefs(context, it) }
        }
        prune(gameRoot)
        return written
    }

    private fun prune(gameRoot: File) {
        list(gameRoot).drop(KEEP).forEach { it.delete() }
        // Leftovers of a backup that was cut short.
        dir(gameRoot).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".tmp") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000 }
            ?.forEach { it.delete() }
    }

    private fun exportPrefs(context: Context): JSONObject {
        val out = JSONObject()
        for (name in PREF_FILES) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val item = encode(value) ?: continue
                values.put(key, item)
            }
            out.put(name, values)
        }
        return out
    }

    internal fun encode(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    private fun importPrefs(context: Context, json: JSONObject) {
        for (name in PREF_FILES) {
            val values = json.optJSONObject(name) ?: continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // The backup replaces the file's contents, apart from the keys that belong to this install.
            prefs.all.keys.filterNot(::isLocalKey).forEach { editor.remove(it) }
            for (key in values.keys()) {
                if (isLocalKey(key)) continue
                val item = values.optJSONObject(key) ?: continue
                put(editor, key, item)
            }
            if (!editor.commit()) throw IOException("Can't save the restored $name settings")
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, item: JSONObject) {
        when (item.optString("t")) {
            "b" -> editor.putBoolean(key, item.getBoolean("v"))
            "i" -> editor.putInt(key, item.getInt("v"))
            "l" -> editor.putLong(key, item.getLong("v"))
            "f" -> editor.putFloat(key, item.getDouble("v").toFloat())
            "s" -> editor.putString(key, item.getString("v"))
            "ss" -> {
                val array = item.getJSONArray("v")
                editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
            }
        }
    }
}
