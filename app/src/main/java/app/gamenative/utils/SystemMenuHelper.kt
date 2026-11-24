package app.gamenative.utils

import android.content.Context
import com.winlator.xserver.XKeycode
import java.io.File
import timber.log.Timber

/**
 * Keeps the button pad's "System" button working when WoW's Escape key stops opening the game menu.
 *
 * WoW has a long-standing client bug where, after some hours, Escape still closes windows but no
 * longer opens the game menu, while the menu's own icon in the micro bar keeps working. The pad's
 * System button sends Escape, so it dies with it.
 *
 * The launcher therefore installs a tiny addon ("ThorSystemButton") into the game's AddOns folder.
 * The addon binds one otherwise unused key combination to a click on the game's own menu icon,
 * through a secure button, so the menu opens the same way as when the icon is tapped (no addon
 * taint, works in combat). With the helper on, the System button sends that combination.
 *
 * In case the addon is not loaded (switched off in game, or the game refuses it), a second tap on
 * System within [FALLBACK_WINDOW_MS] sends plain Escape, which is what the button did before.
 */
object SystemMenuHelper {
    private const val PREFS = "wow_forever"
    private const val KEY_ENABLED = "system_menu_helper"

    const val ADDON = "ThorSystemButton"

    /** The key combination the addon listens for. WoW spells it [WOW_BINDING]. */
    val COMBO_KEY = XKeycode.KEY_F10
    val COMBO_MODS = listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_ALT_L, XKeycode.KEY_SHIFT_L)
    private const val WOW_BINDING = "ALT-CTRL-SHIFT-F10"

    const val FALLBACK_WINDOW_MS = 600L

    /** True once the addon has been installed for the game that is about to run in this process. */
    @Volatile
    var active = false
        private set

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /**
     * Installs the addon into [clientDir] (`<game folder>/_forever_` and the like) when the helper is on,
     * or removes it when it is off. [gameVersion] is the "Version" of `.build.info` ("12.0.1.65432").
     * Never throws: the game must start either way.
     */
    fun prepare(context: Context, clientDir: File, gameVersion: String?) {
        active = false
        val addonDir = File(clientDir, "Interface/AddOns/$ADDON")
        runCatching {
            if (!isEnabled(context)) {
                if (addonDir.isDirectory) addonDir.deleteRecursively()
                return
            }
            val interfaceVersion = interfaceVersion(gameVersion) ?: run {
                Timber.w("System menu helper: can't read the game version '$gameVersion', not installing the addon")
                return
            }
            if (!addonDir.isDirectory && !addonDir.mkdirs()) error("Can't create ${addonDir.path}")
            writeIfChanged(File(addonDir, "$ADDON.toc"), toc(interfaceVersion))
            writeIfChanged(File(addonDir, "$ADDON.lua"), LUA)
            active = true
        }.onFailure { Timber.w(it, "System menu helper: addon install failed") }
    }

    /** "12.0.1.65432" gives 120001, the number WoW expects in an addon's "## Interface" line. */
    fun interfaceVersion(gameVersion: String?): Int? {
        val parts = gameVersion?.trim()?.split('.')?.take(3)?.map { it.toIntOrNull() ?: return null } ?: return null
        if (parts.size < 3 || parts[0] <= 0 || parts[1] !in 0..99 || parts[2] !in 0..99) return null
        return parts[0] * 10000 + parts[1] * 100 + parts[2]
    }

    private fun writeIfChanged(file: File, text: String) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return
        file.writeText(text)
    }

    private fun toc(interfaceVersion: Int) = """
        |## Interface: $interfaceVersion
        |## Title: Thor System Button
        |## Notes: Lets the launcher's System button open the game menu even when Escape stops doing so. Installed by the WoW launcher.
        |## Author: AYN Thor WoW Launcher
        |## Version: 1
        |
        |$ADDON.lua
        |""".trimMargin()

    // Kept deliberately small. Nothing here touches the game menu from addon code: the key is bound
    // to a secure button whose only action is to click the game's own menu icon.
    private val LUA = """
        |-- Installed by the WoW launcher. Turn off "System button helper" in the launcher to remove it.
        |local KEY = "$WOW_BINDING"
        |local NAME = "ThorSystemMenuButton"
        |
        |local ok, button = pcall(CreateFrame, "Button", NAME, UIParent, "SecureActionButtonTemplate")
        |if not ok or not button then return end
        |
        |-- Act once per key press whatever the "cast on key down" setting is.
        |button:RegisterForClicks("AnyDown", "AnyUp")
        |button:SetAttribute("useOnKeyDown", false)
        |button:SetAttribute("type", "click")
        |
        |local events = CreateFrame("Frame")
        |
        |local function apply()
        |    -- Secure buttons and bindings cannot be changed in combat: try again when it ends.
        |    if InCombatLockdown() then
        |        events:RegisterEvent("PLAYER_REGEN_ENABLED")
        |        return
        |    end
        |    local target = _G["MainMenuMicroButton"]
        |    if not target then return end
        |    button:SetAttribute("clickbutton", target)
        |    ClearOverrideBindings(button)
        |    SetOverrideBindingClick(button, true, KEY, NAME, "LeftButton")
        |end
        |
        |events:RegisterEvent("PLAYER_LOGIN")
        |events:RegisterEvent("PLAYER_ENTERING_WORLD")
        |events:SetScript("OnEvent", function(self, event)
        |    if event == "PLAYER_REGEN_ENABLED" then self:UnregisterEvent(event) end
        |    pcall(apply)
        |end)
        |""".trimMargin()
}
