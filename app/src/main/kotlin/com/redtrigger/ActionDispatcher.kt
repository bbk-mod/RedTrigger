package com.redtrigger

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the action bound to a shoulder trigger.
 *
 * Presses arrive on a binder thread and may be rapid; each action runs on its
 * own thread behind a per-trigger in-flight guard, so holding or mashing the
 * trigger cannot queue up a backlog of launches.
 */
object ActionDispatcher {
    private const val TAG = "Action"

    /**
     * Ceiling on waiting for an app's MediaSession to appear after we launch it.
     * The wait is event-driven (see [MediaControlService.awaitSession]), so a
     * fast app costs milliseconds — this is only paid in full when the app never
     * publishes a session at all.
     */
    private const val MEDIA_SESSION_TIMEOUT_MS = 5_000L

    /** Global media key, used only when a targeted session never appears. */
    private const val KEYCODE_MEDIA_PLAY_PAUSE = 85

    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

    fun handle(context: Context, trigger: InputReader.Trigger, gesture: TriggerGesture) {
        val action = TriggerAction.load(context, trigger, gesture)
        if (action == TriggerAction.None) return

        val key = "${trigger.name}_${gesture.name}"
        val guard = inFlight.getOrPut(key) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) {
            DebugLog.log(TAG, "$key: still running, ignoring press")
            return
        }

        val appContext = context.applicationContext
        thread(name = "action-$key", isDaemon = true) {
            try {
                execute(appContext, action)
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                guard.set(false)
            }
        }
    }

    private fun execute(context: Context, action: TriggerAction) {
        when (action) {
            TriggerAction.None -> Unit

            TriggerAction.QuickSwitch -> quickSwitch()

            is TriggerAction.MediaPlayPause -> toggleMedia(context, action.packageName, action.component)

            is TriggerAction.LaunchApp -> launchApp(action.component)

            is TriggerAction.ShellCommand -> shell(action.command)
        }
    }

    /**
     * Double-tap Overview. The platform's own quick-switch gesture.
     *
     * Both key events are sent from a single `input` invocation on purpose: each
     * `input` call spawns a process (~100-300 ms), which would blow past
     * ViewConfiguration's 300 ms double-tap window if sent separately.
     */
    private fun quickSwitch() {
        InputReader.runShellCommand("input", "keyevent", "187", "187")
        DebugLog.log(TAG, "Quick switch sent")
    }

    private fun launchApp(component: String) {
        InputReader.runShellCommand("am", "start", "-n", component)
    }

    private fun shell(command: String) {
        if (command.isBlank()) return
        InputReader.runShellCommand("sh", "-c", command)
    }

    /**
     * Play/pause one specific app.
     *
     * 1. If the app already has a MediaSession, toggle it. Nothing becomes
     *    visible and no other app's playback is touched.
     * 2. Otherwise launch it and wait (event-driven, capped) for a session.
     * 3. If a session appears, start playback explicitly — a freshly launched
     *    app is not guaranteed to resume on its own; the platform has no
     *    mechanism that restores playback after process death.
     * 4. If it still never appears, fall back to the global media key and say so.
     *    This fallback is NOT targeted: it hits whatever holds the active
     *    session, which is exactly what we set out to avoid, so it is logged
     *    loudly rather than silently.
     */
    private fun toggleMedia(context: Context, packageName: String, component: String) {
        if (!MediaControlService.isEnabled(context)) {
            DebugLog.log(TAG, "Notification access not granted, using global media key")
            InputReader.runShellCommand("input", "keyevent", KEYCODE_MEDIA_PLAY_PAUSE.toString())
            return
        }

        val existing = MediaControlService.controllersFor(context, packageName).firstOrNull()
        if (existing != null) {
            if (MediaControlService.isPlaying(existing)) {
                MediaControlService.pause(existing)
                DebugLog.log(TAG, "Paused $packageName")
            } else {
                MediaControlService.play(existing)
                DebugLog.log(TAG, "Playing $packageName")
            }
            return
        }

        DebugLog.log(TAG, "$packageName has no session, launching")
        launchApp(component)

        val launched = MediaControlService.awaitSession(
            context,
            packageName,
            MEDIA_SESSION_TIMEOUT_MS
        )

        if (launched != null) {
            MediaControlService.play(launched)
            DebugLog.log(TAG, "Playing $packageName after launch")
            return
        }

        DebugLog.log(
            TAG,
            "FAIL: $packageName gave no session after ${MEDIA_SESSION_TIMEOUT_MS}ms, " +
                "falling back to the GLOBAL media key (may hit another app)"
        )
        InputReader.runShellCommand("input", "keyevent", KEYCODE_MEDIA_PLAY_PAUSE.toString())
    }
}
