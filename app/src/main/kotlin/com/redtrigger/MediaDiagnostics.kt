package com.redtrigger

import android.content.Context
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Records why a controller-driven play stops within a second.
 *
 * The candidates are: Android 12+ background foreground-service start restrictions,
 * the target app's own cold-start restore settling paused, a media resumption stub,
 * audio-focus loss, media-key double delivery, and (on Nubia) the game service
 * turning the hardware trigger into a screen tap that hits the pause control.
 *
 * A run keeps the playback timeline and then shells out for the platform dumps that
 * tell those apart. Opt-in: the dumps spawn shell commands, so nothing runs until
 * the user enables it.
 */
object MediaDiagnostics {
    private const val TAG = "MediaDiag"
    private const val PREFS = "RedTriggerPrefs"
    const val KEY_ENABLED = "media_diagnostics"

    private const val MAX_LINES_PER_DUMP = 40
    private const val MAX_CHARS_PER_DUMP = 4_000

    private val report = StringBuilder()

    @Volatile
    private var lastReportText: String = ""

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastReport(): String = lastReportText

    fun clear() {
        synchronized(report) { report.setLength(0) }
        lastReportText = ""
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts a run, discarding the previous one. */
    fun begin(context: Context, packageName: String, path: String) {
        if (!isEnabled(context)) return
        synchronized(report) {
            report.setLength(0)
            append("=== media diagnostics ===")
            append("package: $packageName")
            append("dispatch path: $path")
        }
        lastReportText = report.toString()
    }

    /** Adds one timeline line, also mirrored to logcat through [DebugLog]. */
    fun record(line: String) {
        synchronized(report) { append(line) }
        lastReportText = report.toString()
        DebugLog.log(TAG, line)
    }

    private fun append(text: String) {
        report.append(text).append('\n')
    }

    /** Playback state in one line; `speed=0.0` while PLAYING marks a resumption stub. */
    fun describe(state: PlaybackState?): String {
        if (state == null) return "state=<none>"

        val name = when (state.state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            else -> "STATE_${state.state}"
        }

        val error = state.errorMessage?.let { " err='$it'" }.orEmpty()
        return "state=$name pos=${state.position} speed=${state.playbackSpeed} " +
            "actions=0x${state.actions.toString(16)}$error"
    }

    /**
     * Runs the platform checks that separate the candidate causes. Blocking (shell
     * round-trips); call from the action's own thread once the timeline is captured.
     */
    fun finish(context: Context, packageName: String, outcome: String) {
        if (!isEnabled(context)) return

        record("outcome: $outcome")
        record("--- platform checks ---")
        record("qs_media_resumption: ${shell("settings get system qs_media_resumption")}")
        record("media_session:\n${shell("dumpsys media_session | grep -F -A 40 '$packageName'")}")
        record("audio_focus:\n${shell("dumpsys audio | grep -i -A 30 'focus'")}")
        record("app_services:\n${shell("dumpsys activity services '$packageName' | grep -iE 'foreground|startRequested|app=|ServiceRecord'")}")
        record("app_process:\n${shell("dumpsys activity processes '$packageName' | grep -iE 'importance|oom|ProcessRecord'")}")
        record(
            "recent_logcat:\n" + shell(
                "logcat -d -t 400 -v time | grep -iE " +
                    "'$packageName|ForegroundServiceStartNotAllowed|KEYCODE_MEDIA|MediaSession|nubia|SystemMgr'"
            )
        )
    }

    private fun shell(command: String): String {
        val out = InputReader.runShellCommand("sh", "-c", command)
            ?: return "<unavailable: Shizuku reader not connected>"

        val trimmed = out.trim()
        if (trimmed.isEmpty()) return "<empty>"

        val lines = trimmed.lines()
        val capped = lines.take(MAX_LINES_PER_DUMP).joinToString("\n")
        val text = if (lines.size > MAX_LINES_PER_DUMP) {
            "$capped\n… ${lines.size - MAX_LINES_PER_DUMP} more lines"
        } else {
            capped
        }

        return if (text.length > MAX_CHARS_PER_DUMP) {
            text.take(MAX_CHARS_PER_DUMP) + "…"
        } else {
            text
        }
    }
}
