package app.gamenative.utils

import android.content.Context
import com.winlator.core.WineRegistryEditor
import java.io.File
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID
import timber.log.Timber

/**
 * A stable "Windows machine identity" that is unique to this installation.
 *
 * The Wine prefix image ships with one fixed MachineGuid that every install of
 * the app shares, and the DNS shim made the Windows computer name follow the
 * device's IP address, so it changed with every network. Battle.net then
 * rejects the login with error BLZ51914400 (see
 * jaredgei/wow-forever-android issue 57). The values are generated once,
 * kept in filesDir/wow_identity.properties and written into the prefix before
 * every Wine start, so they survive a prefix re-extraction.
 */
object InstallIdentity {
    /** Environment variable read by gethostname() in gamenative_dns_v4mapped.c. */
    const val HOSTNAME_ENV = "GAMENATIVE_HOSTNAME"

    private const val FILE_NAME = "wow_identity.properties"
    private const val KEY_GUID = "machineGuid"
    private const val KEY_NAME = "computerName"
    private const val KEY_SERIAL = "volumeSerial"
    private const val KEY_PRODUCT_ID = "productId"
    private const val KEY_INSTALL_DATE = "installDate"

    data class Identity(
        val machineGuid: String,
        val computerName: String,
        val volumeSerial: String,
        val productId: String,
        val installDate: Int,
    )

    @Volatile
    private var cached: Identity? = null

    @Synchronized
    fun get(context: Context): Identity {
        cached?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val props = Properties()
        if (file.isFile) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                Timber.w(e, "Could not read %s", FILE_NAME)
            }
        }

        val random = SecureRandom()
        var changed = false
        fun value(key: String, valid: (String) -> Boolean, generate: () -> String): String {
            val existing = props.getProperty(key)
            if (existing != null && valid(existing)) return existing
            changed = true
            return generate().also { props.setProperty(key, it) }
        }

        val guid = value(KEY_GUID, { Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it) }) {
            UUID.randomUUID().toString().lowercase()
        }
        val name = value(KEY_NAME, { Regex("[A-Z0-9-]{1,15}").matches(it) }) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            "DESKTOP-" + (1..7).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
        val serial = value(KEY_SERIAL, { Regex("[0-9A-F]{8}").matches(it) }) {
            String.format("%08X", random.nextInt())
        }
        val productId = value(KEY_PRODUCT_ID, { Regex("[0-9]{5}-[0-9]{5}-[0-9]{5}-[A-Z0-9]{5}").matches(it) }) {
            fun digits() = String.format("%05d", random.nextInt(100000))
            "00330-" + digits() + "-" + digits() + "-AA" + String.format("%03d", random.nextInt(1000))
        }
        val installDate = value(KEY_INSTALL_DATE, { it.toIntOrNull()?.let { v -> v > 0 } == true }) {
            // Some time in the two years before first use.
            val now = (System.currentTimeMillis() / 1000L).toInt()
            (now - 86_400 - random.nextInt(2 * 365 * 86_400)).toString()
        }.toInt()

        if (changed) {
            try {
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                tmp.outputStream().use { props.store(it, "Per-install Windows identity. Do not share.") }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // Still usable for this run; a new one is made next time.
                Timber.e(e, "Could not save %s", FILE_NAME)
            }
        }
        return Identity(guid, name, serial, productId, installDate).also { cached = it }
    }

    /**
     * Writes the identity into the Wine prefix. Call only while no Wine
     * process is running, right before the environment starts.
     */
    fun applyToPrefix(context: Context, prefixDir: File) {
        try {
            val id = get(context)
            val systemReg = File(prefixDir, "system.reg")
            if (systemReg.isFile && systemReg.length() > 0L) {
                WineRegistryEditor(systemReg).use { reg ->
                    reg.setStringValue("Software\\Microsoft\\Cryptography", "MachineGuid", id.machineGuid)
                    val currentVersion = "Software\\Microsoft\\Windows NT\\CurrentVersion"
                    reg.setStringValue(currentVersion, "ProductId", id.productId)
                    reg.setDwordValue(currentVersion, "InstallDate", id.installDate)
                }
            } else {
                Timber.w("system.reg missing in %s, identity not written to the registry", prefixDir)
            }

            val driveC = File(prefixDir, "drive_c")
            if (driveC.isDirectory) {
                val serialFile = File(driveC, ".windows-serial")
                val wanted = id.volumeSerial + "\n"
                if (!serialFile.isFile || serialFile.readText() != wanted) serialFile.writeText(wanted)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not apply install identity to the Wine prefix")
        }
    }
}
