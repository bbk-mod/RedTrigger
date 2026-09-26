package com.redtrigger

import android.content.Context

/** How a trigger was pressed. */
enum class TriggerGesture {
    TAP,
    DOUBLE_TAP,
    TRIPLE_TAP,
    LONG_PRESS,
    TAP_HOLD;

    val label: String
        get() = when (this) {
            TAP -> "Tap"
            DOUBLE_TAP -> "Double-tap"
            TRIPLE_TAP -> "Triple-tap"
            LONG_PRESS -> "Hold"
            TAP_HOLD -> "Tap, then hold"
        }
}

/**
 * What a shoulder trigger does when pressed a particular way.
 *
 * Persisted as a single string per (trigger, gesture) in SharedPreferences so an
 * action can be swapped with one write. Format: `<kind>` or `<kind>|<field>|...`
 * for the kinds that carry a target.
 */
sealed interface TriggerAction {

    data object None : TriggerAction

    /** Double-tap Overview: the platform's own "back to the previous app". */
    data object QuickSwitch : TriggerAction

    /** Play/pause one specific app, by package, regardless of the active session. */
    data class MediaPlayPause(val packageName: String, val component: String) : TriggerAction

    /** Bring an app to the front. */
    data class LaunchApp(val packageName: String, val component: String) : TriggerAction

    /** Raw shell line, run via `sh -c` as the shell uid. */
    data class ShellCommand(val command: String) : TriggerAction

    /**
     * Lock the device and drop biometric/trust-agent unlock until the primary
     * credential is entered. Equivalent to AOSP's power-menu Lockdown.
     */
    data object Lockdown : TriggerAction

    companion object {
        private const val PREFS = "RedTriggerPrefs"

        private const val KIND_NONE = "none"
        private const val KIND_QUICK_SWITCH = "quick_switch"
        private const val KIND_MEDIA = "media"
        private const val KIND_LAUNCH = "launch"
        private const val KIND_SHELL = "shell"
        private const val KIND_LOCKDOWN = "lockdown"

        private const val SEPARATOR = "|"
        private const val SEPARATOR_CHAR = '|'

        fun prefKey(trigger: InputReader.Trigger, gesture: TriggerGesture): String =
            "action_${trigger.name.lowercase()}_${gesture.name.lowercase()}"

        /** Key used before gestures existed; carried over as the plain-tap action. */
        private fun legacyPrefKey(trigger: InputReader.Trigger): String =
            "action_${trigger.name.lowercase()}"

        fun load(
            context: Context,
            trigger: InputReader.Trigger,
            gesture: TriggerGesture
        ): TriggerAction {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            prefs.getString(prefKey(trigger, gesture), null)?.let { return parse(it) }

            // A pre-gesture binding applies to a plain tap.
            if (gesture == TriggerGesture.TAP) {
                prefs.getString(legacyPrefKey(trigger), null)?.let { return parse(it) }
            }

            return None
        }

        fun isBound(context: Context, trigger: InputReader.Trigger, gesture: TriggerGesture): Boolean =
            load(context, trigger, gesture) != None

        fun save(
            context: Context,
            trigger: InputReader.Trigger,
            gesture: TriggerGesture,
            action: TriggerAction
        ) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(prefKey(trigger, gesture), serialize(action))
                .apply()
            DebugLog.log("Action", "${trigger.name} ${gesture.label} = ${describe(action)}")
        }

        fun parse(raw: String?): TriggerAction {
            if (raw.isNullOrBlank()) return None

            // Char delimiters, not String: String.split(String) is the Java regex overload
            // and "|" is an empty alternation there, which would split into single characters.
            val parts = raw.split(SEPARATOR_CHAR)

            return when (parts[0]) {
                KIND_QUICK_SWITCH -> QuickSwitch
                KIND_LOCKDOWN -> Lockdown
                KIND_MEDIA -> if (parts.size >= 3) MediaPlayPause(parts[1], parts[2]) else None
                KIND_LAUNCH -> if (parts.size >= 3) LaunchApp(parts[1], parts[2]) else None
                KIND_SHELL -> if (parts.size >= 2) ShellCommand(parts.drop(1).joinToString(SEPARATOR)) else None
                else -> None
            }
        }

        fun serialize(action: TriggerAction): String = when (action) {
            None -> KIND_NONE
            QuickSwitch -> KIND_QUICK_SWITCH
            Lockdown -> KIND_LOCKDOWN
            is MediaPlayPause -> listOf(KIND_MEDIA, action.packageName, action.component).joinToString(SEPARATOR)
            is LaunchApp -> listOf(KIND_LAUNCH, action.packageName, action.component).joinToString(SEPARATOR)
            is ShellCommand -> KIND_SHELL + SEPARATOR + action.command
        }

        /** Short human-readable label for the UI. */
        fun describe(action: TriggerAction): String = when (action) {
            None -> "None"
            QuickSwitch -> "Switch to previous app"
            Lockdown -> "Lockdown (disable biometrics)"
            is MediaPlayPause -> "Play/pause ${action.packageName}"
            is LaunchApp -> "Open ${action.packageName}"
            is ShellCommand -> "Shell: ${action.command}"
        }
    }
}
