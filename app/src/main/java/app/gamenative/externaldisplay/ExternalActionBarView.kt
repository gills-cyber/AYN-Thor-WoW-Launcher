package app.gamenative.externaldisplay

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The main second-screen pad.
 *
 * Left half: a column of window shortcuts (Map, Character, Spellbook, Talents, Skills, Quest Log,
 * Social, System).
 * Right half, top to bottom: the modifier buttons (see [setModifierRow]), the 12 action buttons
 * (4 rows of 3), and F1-F12 in two rows.
 *
 * Every button sends WoW's default key, so the pad works with the stock bindings and needs no addon.
 * To use different keys, change the lists in the companion object.
 *
 * One touch is exactly one key press and one key release. There are no macros, sequences or timed
 * repeats here: anything multi-step belongs in WoW's own macro system.
 */
class ExternalActionBarView(
    context: Context,
    private val xServer: XServer,
    private val theme: PadTheme,
    /** Called after a button's key has been released, e.g. so one-shot modifiers can let go. */
    private val onKeyTapped: () -> Unit = {},
    /** Lets go of the Shift, Ctrl and Alt the user is holding on the pad, and returns which ones were down. */
    private val onSuspendModifiers: () -> List<XKeycode> = { emptyList() },
    /** Presses back the modifiers [onSuspendModifiers] returned, those the user is still holding. */
    private val onResumeModifiers: (List<XKeycode>) -> Unit = {},
    /** Called after the Chat, Slash or Reply button is tapped, so the pad can bring up the keyboard for typing. */
    private val onChatTyping: () -> Unit = {},
    /** The page shown first (index into the pages). */
    initialPage: Int = 0,
) : LinearLayout(context) {

    /**
     * [mods] are held around [key] (Shift+Tab). An [isolated] button ignores any modifier the user is
     * holding on the pad, so only its own modifiers reach the game.
     */
    private data class Slot(val label: String, val key: XKeycode, val mods: List<XKeycode> = emptyList(), val isolated: Boolean = false)

    private class Down(val combo: KeyCombo, val isolated: Boolean, val pressed: List<XKeycode> = combo.mods)

    private val downKeys = mutableMapOf<XKeycode, Down>()
    private var isolatedDown = 0
    private var suspendedModifiers: List<XKeycode> = emptyList()
    private val rightColumn: LinearLayout
    private val modifierGroup: LinearLayout

    /** A backing panel that hides itself while it has nothing in it (e.g. the modifiers moved to the trackpad). */
    private class Group(context: Context) : LinearLayout(context) {
        override fun onViewAdded(child: View?) {
            super.onViewAdded(child)
            visibility = VISIBLE
        }

        override fun onViewRemoved(child: View?) {
            super.onViewRemoved(child)
            if (childCount == 0) visibility = GONE
        }
    }

    private val pageHost: LinearLayout
    /** Buttons an armed Shift, Ctrl or Alt applies to: they glow while one is armed. */
    private val modifierAffected = mutableListOf<TextView>()
    private var pageIndex = initialPage.coerceIn(PAGES.indices)

    init {
        orientation = HORIZONTAL
        isMotionEventSplittingEnabled = true
        setBackgroundColor(theme.background)
        val pad = dp(8)
        // 5 dp here, plus the tab bar's 1 dp and the panels' own 3 dp, makes a 9 dp gap to the tab bar.
        val near = dp(5)
        // Side by side has no tab bar on the pad itself, so it gets the full margin all round.
        if (PadSettings.bool(PadSettings.SIDE_BY_SIDE)) setPadding(pad, pad, pad, pad)
        else if (PadSettings.int(PadSettings.TAB_POS) == 1) setPadding(pad, pad, pad, near) else setPadding(pad, near, pad, pad)

        // Each group of buttons sits on its own faint backing; the number block's is the strongest.
        // The page area shows one page of buttons at a time (see [showPage]).
        pageHost = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, pageWeight())
        }
        populatePage()
        modifierGroup = group(vertical = false, strong = false).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.7f * PadSettings.int(PadSettings.MOD_HEIGHT) / 100f).apply { setMargins(0, dp(3), 0, dp(3)) }
            visibility = GONE
        }
        val numberGroup = group(vertical = true, strong = true).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 4f).apply { setMargins(0, dp(3), 0, dp(3)) }
            orderedSlots(GROUP_NUMBERS, ACTION_SLOTS).chunked(3).forEach {
                addView(keyRow(it, textSp = 26f, weight = 1f, numbers = true, canHide = false))
            }
            if (!PadSettings.bool(PadSettings.SEC_NUMBERS)) visibility = GONE
        }
        // The optional Extra Buttons panel: up to ten buttons, in one row or split over two.
        val extraGroup: LinearLayout? = if (PadSettings.bool(PadSettings.EXTRA_ON)) {
            val slots = orderedSlots(GROUP_EXTRAS, EXTRA_SLOTS).take(PadSettings.int(PadSettings.EXTRA_N).coerceIn(1, 10))
                // Isolated buttons ignore armed modifiers; with the option on, the extras take them like the numbers do.
                .map { it.copy(isolated = !PadSettings.bool(PadSettings.EXTRA_MODS)) }
            val twoRows = PadSettings.bool(PadSettings.EXTRA_TWO_ROWS) && slots.size > 1
            val rows = if (twoRows) listOf(slots.take((slots.size + 1) / 2), slots.drop((slots.size + 1) / 2)) else listOf(slots)
            group(vertical = true, strong = false).apply {
                val height = 0.7f * PadSettings.int(PadSettings.EXTRA_HEIGHT) / 100f * rows.size
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, height).apply { setMargins(0, dp(3), 0, dp(3)) }
                rows.forEach { addView(keyRow(it, textSp = 14f, weight = 1f, muted = true, wrap = true, canHide = false)) }
            }
        } else null
        // The optional Chat Buttons panel: Chat, Slash, Reply and Close in one row.
        val chatGroup: LinearLayout? = if (PadSettings.bool(PadSettings.CHAT_ON)) {
            group(vertical = true, strong = false).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.7f * PadSettings.int(PadSettings.CHAT_HEIGHT) / 100f).apply { setMargins(0, dp(3), 0, dp(3)) }
                addView(keyRow(orderedSlots(GROUP_CHAT, CHAT_SLOTS), textSp = 14f, weight = 1f, muted = true, wrap = true, canHide = false))
            }
        } else null
        // The panels stack in the order chosen in settings (a movable list, see [PANEL_LABELS]).
        rightColumn = column(RIGHT_WEIGHT).apply {
            PadSettings.ordered(PANELS, PANEL_LABELS).forEach { panel ->
                when (panel) {
                    "Numbers" -> addView(numberGroup)
                    "Modifiers" -> addView(modifierGroup)
                    "Extras" -> extraGroup?.let { addView(it) }
                    "Chat" -> chatGroup?.let { addView(it) }
                }
            }
        }
        // "Swap sides" puts the page area on the right.
        if (PadSettings.bool(PadSettings.SWAP_SIDES)) {
            addView(rightColumn)
            addView(pageHost)
        } else {
            addView(pageHost)
            addView(rightColumn)
        }
    }

    /** Shows page [index] of [PAGES]. Lets go of any pressed button first, since its view is about to go. */
    fun showPage(index: Int) {
        releaseAllKeys()
        pageIndex = index.coerceIn(PAGES.indices)
        populatePage()
    }

    /** Fills the page area: one column of buttons, or two when the page has more than [SINGLE_COLUMN_MAX]. */
    private fun populatePage() {
        pageHost.removeAllViews()
        if (PadSettings.bool(PadSettings.SIDE_BY_SIDE)) {
            // All pages at once, one column each.
            sidePages().forEach { page ->
                // Hidden buttons do not count; only the first 10 of the rest fit in a column.
                val slots = orderedSlots(page.id, page.slots).filter { !PadSettings.isHidden(it.label) }.take(SIDE_BY_SIDE_MAX)
                pageHost.addView(
                    group(vertical = true, strong = false).apply {
                        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                        slots.forEach { addView(keyRow(listOf(it), textSp = 13f, weight = 1f, muted = true, wrap = true)) }
                    },
                )
            }
            (pageHost.layoutParams as LayoutParams).weight = pageWeight()
            pageHost.requestLayout()
            return
        }
        val page = PAGES[pageIndex]
        val slots = orderedSlots(page.id, page.slots).filter { !PadSettings.isHidden(it.label) }
        val columns = if (slots.size > SINGLE_COLUMN_MAX) slots.chunked((slots.size + 1) / 2) else listOf(slots)
        val split = columns.size > 1
        columns.forEach { column ->
            pageHost.addView(
                group(vertical = true, strong = false).apply {
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                    column.forEach { addView(keyRow(listOf(it), textSp = if (split) 13f else 15f, weight = 1f, muted = true, wrap = split)) }
                },
            )
        }
        (pageHost.layoutParams as LayoutParams).weight = pageWeight()
        pageHost.requestLayout()
    }

    private fun pageWidthScale() = PadSettings.int(PadSettings.PAGE_WIDTH) / 100f

    private fun pageWeight() = pageWeightNow()

    /** Makes the buttons armed modifiers apply to glow in [color] ([intensity] 10 to 100), or stop glowing when null. */
    fun setModifierGlow(color: Int?, intensity: Int) {
        val density = resources.displayMetrics.density
        modifierAffected.forEach { view -> applyGlow(view, color, intensity, density) }
    }

    /** Side by side: puts the Settings, Trackpad and Keyboard buttons in a row at the top of the right column. */
    fun setModeRow(row: View) {
        (row.parent as? android.view.ViewGroup)?.removeView(row)
        rightColumn.addView(row, 0)
    }

    /** Puts the modifier buttons above the action buttons. */
    fun setModifierRow(row: View) {
        (row.parent as? android.view.ViewGroup)?.removeView(row)
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        modifierGroup.addView(row)
    }

    private fun group(vertical: Boolean, strong: Boolean): LinearLayout = Group(context).apply {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        isMotionEventSplittingEnabled = true
        background = theme.groupBackground(resources.displayMetrics.density, strong)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun column(weight: Float) = LinearLayout(context).apply {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
    }

    /** [muted] dims the button trim; the action buttons (1-9, 0, -, =) are left at full strength. */
    private fun keyRow(
        slots: List<Slot>,
        textSp: Float,
        weight: Float,
        muted: Boolean = false,
        numbers: Boolean = false,
        wrap: Boolean = false,
        canHide: Boolean = true,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            // Rows share the column's height by weight, so everything always fits.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, weight)
            slots.forEach { slot ->
                val button = createButton(slot, textSp, muted, numbers, wrap)
                if (canHide && PadSettings.isHidden(slot.label)) {
                    button.visibility = INVISIBLE
                    button.isEnabled = false
                    button.setOnTouchListener(null)
                }
                addView(button)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createButton(slot: Slot, textSp: Float, muted: Boolean, numbers: Boolean, wrap: Boolean = false): View {
        return GlowTextView(context).apply {
            text = if (wrap) wrapLabel(PadSettings.displayName(slot.label)) else PadSettings.displayName(slot.label)
            contentDescription = slot.label
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp * PadSettings.labelScale)
            typeface = theme.typeface
            if (wrap) {
                // Narrow two-column buttons may wrap a label onto a second line, shrinking only if it still will not fit.
                maxLines = 2
                setAutoSizeTextTypeUniformWithConfiguration(8, (textSp * PadSettings.labelScale).toInt().coerceAtLeast(9), 1, TypedValue.COMPLEX_UNIT_SP)
            } else {
                maxLines = 1
            }
            val accent = PadSettings.colorOf(slot.label)
            background = createKeyBackground(muted, numbers, accent)
            if (accent != null) setTextColor(accent)
            // Chat Buttons show a drawn icon instead of text, until the user gives the button a name of its own.
            if (ChatIconDrawable.has(slot.label) && PadSettings.renamed(slot.label) == null) {
                text = ""
                foreground = ChatIconDrawable(slot.label, accent ?: theme.text, theme.textPressed)
            }
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                val margin = dp(3)
                setMargins(margin, margin, margin, margin)
            }
            // Only buttons that take an armed modifier glow: not the page buttons, nor ones remapped to a combo.
            if (!slot.isolated && (comboFor(slot).mods.isEmpty() || mergesModifiers(slot))) modifierAffected.add(this)
            // The key this button put down, so the release always matches the press even if the
            // button is remapped in between.
            var pressedKey: XKeycode? = null
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        PadSettings.haptic(view)
                        val combo = comboFor(slot)
                        pressedKey = combo.key
                        pressKey(combo, isolated = slot.isolated, mergeModifiers = mergesModifiers(slot))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        releaseKey(pressedKey ?: comboFor(slot).key)
                        pressedKey = null
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            (slot.label == "Chat" || slot.label == "Slash" || slot.label == "Reply")
                        ) onChatTyping()
                    }
                }
                true
            }
        }
    }

    /**
     * In the narrow two-column pages a long, multi-word label drops its last word onto a second line,
     * so "Chat page up" and "Chat page down" break the same way. Short labels and ones with a "/" stay as they are.
     */
    private fun wrapLabel(name: String): String {
        val cut = name.lastIndexOf(' ')
        return if (name.length > 8 && cut > 0 && '/' !in name) name.substring(0, cut) + "\n" + name.substring(cut + 1) else name
    }

    /** What a button sends: the user's remap if there is one, otherwise the default. */
    private fun comboFor(slot: Slot): KeyCombo =
        PadSettings.remapped(slot.label)?.let { KeyCombo.parse(it) } ?: KeyCombo(slot.key, slot.mods)

    /** Ex6 to Ex10 (with the modifier option on) take armed Shift, Ctrl and Alt like the other extras, on top of their own Shift. */
    private fun mergesModifiers(slot: Slot) = !slot.isolated && slot.label.removePrefix("Ex").toIntOrNull() in 6..10 && slot.label.startsWith("Ex")

    private fun pressKey(combo: KeyCombo, isolated: Boolean = false, mergeModifiers: Boolean = false) {
        // The key is already down: another button sends the same key (System and Close are both
        // Escape), or an earlier touch never delivered its release. Ignoring the press here would
        // leave the button dead until the pad is rebuilt, so let go of the key and press it again.
        if (downKeys.containsKey(combo.key)) releaseKey(combo.key)
        // A button with its own modifiers, or an isolated one, must not pick up Shift, Ctrl or Alt held on the pad,
        // unless it is meant to add its own on top of them.
        val isolate = isolated || (combo.mods.isNotEmpty() && !mergeModifiers)
        // When merging, a modifier already held on the pad is left alone, so letting go does not drop it.
        val toPress = if (mergeModifiers) combo.mods.filter { !xServer.keyboard.modifiersMask.isSet(modifierMask(it)) } else combo.mods
        downKeys[combo.key] = Down(combo, isolate, toPress)
        if (isolate && isolatedDown++ == 0) suspendedModifiers = onSuspendModifiers()
        toPress.forEach { xServer.injectKeyPress(it) }
        xServer.injectKeyPress(combo.key)
    }

    private fun modifierMask(key: XKeycode): Long = when (key) {
        XKeycode.KEY_SHIFT_L, XKeycode.KEY_SHIFT_R -> 1L
        XKeycode.KEY_CTRL_L, XKeycode.KEY_CTRL_R -> 4L
        else -> 8L
    }

    private fun releaseKey(key: XKeycode) {
        val down = downKeys.remove(key) ?: return
        xServer.injectKeyRelease(key)
        down.pressed.asReversed().forEach { xServer.injectKeyRelease(it) }
        onKeyTapped()
        if (down.isolated && --isolatedDown == 0) {
            onResumeModifiers(suspendedModifiers)
            suspendedModifiers = emptyList()
        }
    }

    private fun createKeyBackground(muted: Boolean, numbers: Boolean, accent: Int? = null) =
        theme.buttonStates(resources.displayMetrics.density, 10f, muted, numbers, accent)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Lets go of every pressed button, and gives back any modifier held aside for an isolated one. */
    private fun releaseAllKeys() {
        downKeys.values.toList().forEach { d ->
            xServer.injectKeyRelease(d.combo.key)
            d.pressed.forEach { xServer.injectKeyRelease(it) }
        }
        downKeys.clear()
        if (isolatedDown > 0) onResumeModifiers(suspendedModifiers)
        isolatedDown = 0
        suspendedModifiers = emptyList()
    }

    /** Never leave a key stuck down if the display goes away or the game closes mid-press. */
    override fun onDetachedFromWindow() {
        releaseAllKeys()
        super.onDetachedFromWindow()
    }

    private fun orderedSlots(group: String, defaults: List<Slot>): List<Slot> {
        val byLabel = defaults.associateBy { it.label }
        return PadSettings.ordered(group, defaults.map { it.label }).mapNotNull { byLabel[it] }
    }

    companion object {
        const val GROUP_NUMBERS = "numbers"
        const val GROUP_EXTRAS = "extras"

        /** The right column's panels, in their default top-to-bottom order. The order is saved under [PANELS]. */
        val PANEL_LABELS = listOf("Numbers", "Modifiers", "Extras", "Chat")
        const val PANELS = "panels"
        const val GROUP_CHAT = "chat"

        /** The page ids, in tab order: "interface", "target", "utility". */
        val PAGE_IDS: List<String> get() = PAGES.map { it.id }

        /** The page tab titles, in tab order. */
        val PAGE_TITLES: List<String> get() = PAGES.map { it.title }

        /** A group's name for the settings: a page's title, or the number block. */
        fun groupTitle(group: String): String = PAGES.firstOrNull { it.id == group }?.title?.let { "the $it page" } ?: when (group) { GROUP_EXTRAS -> "the extra buttons"; GROUP_CHAT -> "the chat buttons"; else -> "the number block" }

        /** The group a pad button belongs to (a page id or the number block), or null for a label that is not a pad button. */
        fun groupOf(label: String): String? =
            PAGES.firstOrNull { page -> page.slots.any { it.label == label } }?.id
                ?: GROUP_NUMBERS.takeIf { ACTION_SLOTS.any { it.label == label } }
                ?: GROUP_EXTRAS.takeIf { EXTRA_SLOTS.any { it.label == label } }
                ?: GROUP_CHAT.takeIf { CHAT_SLOTS.any { it.label == label } }

        /** A group's button labels in the default order. */
        fun defaultLabels(group: String): List<String> =
            (PAGES.firstOrNull { it.id == group }?.slots ?: when (group) { GROUP_EXTRAS -> EXTRA_SLOTS; GROUP_CHAT -> CHAT_SLOTS; else -> ACTION_SLOTS }).map { it.label }

        /** Every remappable pad button: its label and what it sends by default. */
        fun remappableButtons(): List<Pair<String, KeyCombo>> =
            (PAGES.flatMap { it.slots } + ACTION_SLOTS + EXTRA_SLOTS + CHAT_SLOTS).map { it.label to KeyCombo(it.key, it.mods) }

        /** The page area's share of the width, against 1 for the right column. Side by side is wider, to fit three panels. */
        fun pageWeightNow(): Float =
            LEFT_WEIGHT * PadSettings.int(PadSettings.PAGE_WIDTH) / 100f *
                (if (PadSettings.bool(PadSettings.SIDE_BY_SIDE)) SIDE_BY_SIDE_WIDEN[sidePages().size.coerceIn(1, 3)] else 1f)

        /** Page area width factor for 1, 2 or 3 side-by-side panels (index = count). */
        private val SIDE_BY_SIDE_WIDEN = floatArrayOf(1f, 1f, 1.2f, 1.5f)

        /** The pages shown side by side: those switched on in settings, or all of them if none is. */
        private fun sidePages(): List<Page> {
            val on = PAGES.filter { page ->
                when (page.id) {
                    "interface" -> PadSettings.bool(PadSettings.SIDE_SHOW_INTERFACE)
                    "target" -> PadSettings.bool(PadSettings.SIDE_SHOW_TARGET)
                    else -> PadSettings.bool(PadSettings.SIDE_SHOW_UTILITY)
                }
            }
            return on.ifEmpty { PAGES }
        }

        /** Most buttons one side-by-side column shows. */
        private const val SIDE_BY_SIDE_MAX = 10

        // Width split between the page area and the number/modifier block.
        // A smaller RIGHT_WEIGHT squeezes that block toward the right edge, within reach of a right thumb.
        private const val LEFT_WEIGHT = 1f
        private const val RIGHT_WEIGHT = 1f

        // Up to this many buttons on a page stay in one column; more split it in two, a little wider overall.
        private const val SINGLE_COLUMN_MAX = 8

        /** A page button. It sends its own modifiers and ignores any the user holds on the pad. */
        private fun page(label: String, key: XKeycode, vararg mods: XKeycode) = Slot(label, key, mods.toList(), isolated = true)

        private class Page(val id: String, val title: String, val slots: List<Slot>)

        // WoW's default bindings. A page holds at most 12 buttons.
        private val PAGES = listOf(
            Page(
                "interface", "Interface",
                listOf(
                    page("Character", XKeycode.KEY_C),
                    page("Spellbook", XKeycode.KEY_P),
                    page("Talents", XKeycode.KEY_N),
                    page("Quest Log", XKeycode.KEY_L),
                    page("Map", XKeycode.KEY_M),
                    page("Social", XKeycode.KEY_O),
                    page("Guild", XKeycode.KEY_J),
                    page("Group Finder", XKeycode.KEY_I),
                    page("Achievements", XKeycode.KEY_Y),
                    page("Professions", XKeycode.KEY_K),
                    page("Bags", XKeycode.KEY_B),
                    // Escape opens the game menu ("System") when nothing else is open.
                    page("System", XKeycode.KEY_ESC),
                ),
            ),
            Page(
                "target", "Target",
                listOf(
                    page("Self", XKeycode.KEY_F1),
                    page("Party 1", XKeycode.KEY_F2),
                    page("Party 2", XKeycode.KEY_F3),
                    page("Party 3", XKeycode.KEY_F4),
                    page("Party 4", XKeycode.KEY_F5),
                    page("Next enemy", XKeycode.KEY_TAB),
                    page("Previous enemy", XKeycode.KEY_TAB, XKeycode.KEY_SHIFT_L),
                ),
            ),
            Page(
                "utility", "Utility",
                listOf(
                    page("Sheathe weapons", XKeycode.KEY_Z),
                    page("Auto-run", XKeycode.KEY_NUM_LOCK),
                    page("Walk / run", XKeycode.KEY_KP_DIVIDE),
                    page("Sit / stand", XKeycode.KEY_X),
                    page("Hide UI", XKeycode.KEY_Z, XKeycode.KEY_ALT_L),
                    page("Enemy nameplates", XKeycode.KEY_V),
                    page("Friendly nameplates", XKeycode.KEY_V, XKeycode.KEY_SHIFT_L),
                    page("Chat page up", XKeycode.KEY_PRIOR),
                    page("Chat page down", XKeycode.KEY_NEXT),
                    page("Screenshot", XKeycode.KEY_PRTSCN),
                ),
            ),
        )

        // The Chat Buttons panel: open chat, start a slash command, reply to the last whisper, close chat.
        private val CHAT_SLOTS = listOf(
            page("Chat", XKeycode.KEY_ENTER),
            page("Slash", XKeycode.KEY_SLASH),
            page("Reply", XKeycode.KEY_R),
            page("Close", XKeycode.KEY_ESC),
        )

        // The Extra Buttons panel. They start on F8 to F12; remap them to whatever is needed.
        private val EXTRA_SLOTS = listOf(
            Slot("Ex1", XKeycode.KEY_F8),
            Slot("Ex2", XKeycode.KEY_F9),
            Slot("Ex3", XKeycode.KEY_F10),
            Slot("Ex4", XKeycode.KEY_F11),
            Slot("Ex5", XKeycode.KEY_F12),
            // Ex6 to Ex10 start on Shift+F8 to Shift+F12.
            Slot("Ex6", XKeycode.KEY_F8, listOf(XKeycode.KEY_SHIFT_L)),
            Slot("Ex7", XKeycode.KEY_F9, listOf(XKeycode.KEY_SHIFT_L)),
            Slot("Ex8", XKeycode.KEY_F10, listOf(XKeycode.KEY_SHIFT_L)),
            Slot("Ex9", XKeycode.KEY_F11, listOf(XKeycode.KEY_SHIFT_L)),
            Slot("Ex10", XKeycode.KEY_F12, listOf(XKeycode.KEY_SHIFT_L)),
        )

        // WoW's default bindings for Action Bar 1, slots 1 to 12.
        private val ACTION_SLOTS = listOf(
            Slot("1", XKeycode.KEY_1),
            Slot("2", XKeycode.KEY_2),
            Slot("3", XKeycode.KEY_3),
            Slot("4", XKeycode.KEY_4),
            Slot("5", XKeycode.KEY_5),
            Slot("6", XKeycode.KEY_6),
            Slot("7", XKeycode.KEY_7),
            Slot("8", XKeycode.KEY_8),
            Slot("9", XKeycode.KEY_9),
            Slot("0", XKeycode.KEY_0),
            Slot("-", XKeycode.KEY_MINUS),
            Slot("=", XKeycode.KEY_EQUAL),
        )
    }
}
