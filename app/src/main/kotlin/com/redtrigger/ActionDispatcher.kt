package com.redtrigger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.session.MediaController
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
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
    private const val MEDIA_SESSION_TIMEOUT_MS = 1_500L

    /** Global media key, used only when a targeted session never appears. */
    private const val KEYCODE_MEDIA_PLAY_PAUSE = 85

    /** Media key that explicitly starts playback (whatever it reaches is not playing). */
    private const val KEYCODE_MEDIA_PLAY = 126

    /** Key event actions, paired for a real button press. */
    private const val ACTION_KEY_DOWN = 0
    private const val ACTION_KEY_UP = 1

    /** Shell command that triggers lockdown; argv form, so it runs without a shell. */
    private val LOCKDOWN_ARGV = arrayOf(
        "locksettings",
        "require-strong-auth",
        "STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN"
    )

    /**
     * A repeated media action this soon after the last one is contact bounce from the
     * SAR sensor, not a second press: the capacitive trigger can report several
     * down/up pairs per touch, and each extra pair toggles playback again. Matches
     * ViewConfiguration's double-tap window, so it cannot eat a gesture the detector
     * itself tells apart.
     */
    private const val MEDIA_DEBOUNCE_MS = 300L

    /**
     * A play command needs this long to surface in the session state. A tap that
     * arrives inside it may read the stale pre-play state, repeat play, and then a
     * RACE: the late-arriving state flip makes a third tap misfire pause. Extends the
     * debounce for play specifically, so a second press only counts once the app's
     * state is known to be settled. Covers cold-starts too: the same window allows
     * the revived player to report playing before the next toggle is accepted.
     */
    private const val PLAY_SETTLE_MS = 1_500L
    private const val PLAY_POLL_MS = 150L

    /**
     * How long a freshly issued play gets to surface as playing before the attempt
     * is considered swallowed. Deliberately generous: re-sending play while the app
     * is still loading can toggle a `play`-as-`playPause` player straight back off.
     */
    private const val PLAY_START_TIMEOUT_MS = 1_200L

    /**
     * How long playback must stay up once seen, before it is trusted. Some apps
     * start, then a late restore or audio-focus change settles them back to paused a
     * beat later — the lockscreen widget flashes "playing" and then stops. A drop
     * inside this window triggers another play.
     */
    private const val PLAY_STABILITY_MS = 1_200L

    /** Max play attempts before giving up. */
    private const val PLAY_MAX_RETRIES = 3

    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

    /** Last accepted media dispatch per trigger; refreshed by ignored ones too. */
    private val lastMediaDispatch = ConcurrentHashMap<InputReader.Trigger, Long>()

    /** Direction of the last accepted media dispatch, per trigger. */
    private val lastMediaDirection = ConcurrentHashMap<InputReader.Trigger, Boolean>()

    fun handle(context: Context, trigger: InputReader.Trigger, gesture: TriggerGesture) {
        val action = TriggerAction.load(context, trigger, gesture)
        if (action == TriggerAction.None) return

        if (action is TriggerAction.MediaPlayPause) {
            val now = SystemClock.uptimeMillis()
            if (now - (lastMediaDispatch[trigger] ?: 0L) < MEDIA_DEBOUNCE_MS) {
                lastMediaDispatch[trigger] = now
                DebugLog.log(TAG, "${trigger.name}: media action repeated, treating as bounce")
                return
            }

            if (lastMediaDirection[trigger] == true &&
                now - (lastMediaDispatch[trigger] ?: 0L) < PLAY_SETTLE_MS
            ) {
                DebugLog.log(TAG, "${trigger.name}: play still settling, ignoring press")
                return
            }

            lastMediaDispatch[trigger] = now
        }

        val key = "${trigger.name}_${gesture.name}"
        val guard = inFlight.getOrPut(key) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) {
            DebugLog.log(TAG, "$key: still running, ignoring press")
            return
        }

        val appContext = context.applicationContext
        buzz(appContext)
        thread(name = "action-$key", isDaemon = true) {
            try {
                execute(appContext, trigger, action)
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                guard.set(false)
            }
        }
    }

    private fun execute(context: Context, trigger: InputReader.Trigger, action: TriggerAction) {
        when (action) {
            TriggerAction.None -> Unit

            TriggerAction.QuickSwitch -> quickSwitch()

            TriggerAction.Lockdown -> runLockdown()

            is TriggerAction.MediaPlayPause -> toggleMedia(context, trigger, action.packageName, action.component)

            is TriggerAction.LaunchApp -> launchApp(action.component)

            is TriggerAction.ShellCommand -> shell(action.command)
        }
    }

    /**
     * Lock the device and require the primary credential, disabling biometric and
     * trust-agent unlock until it is entered.
     *
     * There is no public app API for this — AOSP's power-menu Lockdown reaches an
     * internal `requireStrongAuth` call — so it is driven through the shell
     * `locksettings` command, which runs as the shell uid via Shizuku and needs no
     * credential itself.
     */
    fun runLockdown(): String? {
        val output = InputReader.runShellCommand(*LOCKDOWN_ARGV)
        DebugLog.log(TAG, if (output == null) "Lockdown: Shizuku not connected" else "Lockdown sent")
        return output
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

    /** Gentle confirmation pulse when a trigger fires. */
    private fun buzz(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return

            val effect = VibrationEffect.createOneShot(30, 50)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Aim one PLAY key at [receiver] only. Unlike `input keyevent`, this never
     * touches the system's media-key routing: an explicit component means only
     * that app's receiver wakes, so nothing else can steal or double-handle it.
     */
    private fun explicitMediaKey(context: Context, receiver: ComponentName, keyCode: Int) {
        val now = SystemClock.uptimeMillis()

        for (action in intArrayOf(ACTION_KEY_DOWN, ACTION_KEY_UP)) {
            val key = KeyEvent(now, now, action, keyCode, 0)

            context.applicationContext.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON, null).apply {
                    component = receiver
                    putExtra(Intent.EXTRA_KEY_EVENT, key)
                }
            )
        }

        DebugLog.log(TAG, "Sent explicit PLAY to ${receiver.flattenToShortString()}")
    }

    /**
     * Play/pause one specific app.
     *
     * 1. If the app already has a MediaSession, toggle it. Nothing becomes
     *    visible and no other app's playback is touched.
     * 2. If not, but the system still routes media keys to this app — its last
     *    MediaButtonReceiver, which survives process death — send a global PLAY:
     *    the receiver revives playback without launching any UI.
     * 3. Otherwise launch it and wait (event-driven, capped) for a session.
     * 4. If a session appears, start playback explicitly — a freshly launched
     *    app is not guaranteed to resume on its own; the platform has no
     *    mechanism that restores playback after process death.
     * 5. If it still never appears, fall back to the global media key and say so.
     *    This fallback is NOT targeted: it hits whatever holds the active
     *    session, which is exactly what we set out to avoid, so it is logged
     *    loudly rather than silently.
     */
    private fun toggleMedia(
        context: Context,
        trigger: InputReader.Trigger,
        packageName: String,
        component: String
    ) {
        if (!MediaControlService.isEnabled(context)) {
            DebugLog.log(TAG, "Notification access not granted, using global media key")
            InputReader.runShellCommand("input", "keyevent", KEYCODE_MEDIA_PLAY_PAUSE.toString())
            return
        }

        val existing = MediaControlService.controllersFor(context, packageName).firstOrNull()
        if (existing != null) {
            if (MediaControlService.isPlaying(existing)) {
                MediaControlService.pause(existing)
                lastMediaDirection[trigger] = false
                DebugLog.log(TAG, "Paused $packageName")
            } else {
                lastMediaDirection[trigger] = true
                confirmPlaying(context, trigger, packageName, existing, "existing session")
            }
            return
        }

        if (MediaControlService.mediaKeyEventTarget() == packageName) {
            val receiver = AppInspector.mediaButtonReceivers(context, packageName).firstOrNull()
            if (receiver != null) {
                explicitMediaKey(context, receiver, KeyEvent.KEYCODE_MEDIA_PLAY)
            } else {
                // No exported MEDIA_BUTTON receiver found: fall back to the
                // global key, which this check just proved routes here anyway.
                DebugLog.log(TAG, "$packageName owns media key routing, sending global play")
                InputReader.runShellCommand("input", "keyevent", KEYCODE_MEDIA_PLAY.toString())
            }
            lastMediaDirection[trigger] = true

            // The receiver revives the app, but its player is still coming up and its
            // own restore can land after our key, settling it paused. Re-assert play
            // against the live session — the same command a tap on the system media
            // card issues, which is known to work once the app has settled.
            val revived = MediaControlService.awaitSession(context, packageName, MEDIA_SESSION_TIMEOUT_MS)
            if (revived != null) {
                confirmPlaying(context, trigger, packageName, revived, "revived via media key")
                return
            }
            DebugLog.log(TAG, "media key revived nothing, launching $packageName")
        } else {
            DebugLog.log(TAG, "$packageName has no session, launching")
        }

        launchApp(component)

        val launched = MediaControlService.awaitSession(
            context,
            packageName,
            MEDIA_SESSION_TIMEOUT_MS
        )

        if (launched != null) {
            lastMediaDirection[trigger] = true
            confirmPlaying(context, trigger, packageName, launched, "launched app")
            return
        }

        DebugLog.log(
            TAG,
            "FAIL: $packageName gave no session after ${MEDIA_SESSION_TIMEOUT_MS}ms, " +
                "falling back to the GLOBAL media key (may hit another app)"
        )
        InputReader.runShellCommand("input", "keyevent", KEYCODE_MEDIA_PLAY_PAUSE.toString())
    }

    /**
     * Start playback and keep it started.
     *
     * A single play is not enough to trust. Some apps swallow it while the player is
     * still coming up, and some start, then settle themselves back to paused a beat
     * later (a late restore, audio-focus churn) — the lockscreen widget flashes
     * "playing" and stops. So each attempt:
     *
     * 1. Sends play and waits up to [PLAY_START_TIMEOUT_MS] for the session to report
     *    playing, without re-sending in the meantime: a second play while the app is
     *    still loading toggles a `play`-as-`playPause` player straight back off.
     * 2. Once playing, keeps watching for [PLAY_STABILITY_MS]. A session that drops
     *    back to paused in that window gets another play.
     * 3. Retries up to [PLAY_MAX_RETRIES] attempts before giving up.
     *
     * Blocking; runs on the action's own daemon thread.
     */
    private fun confirmPlaying(
        context: Context,
        trigger: InputReader.Trigger,
        packageName: String,
        controller: MediaController,
        path: String,
        label: String = "Playing $packageName",
    ) {
        val diagnostics = MediaDiagnostics.isEnabled(context)
        if (diagnostics) {
            MediaDiagnostics.begin(context, packageName, path)
            MediaDiagnostics.record("initial: ${MediaDiagnostics.describe(controller.playbackState)}")
        }

        var target = controller
        var dropped = false
        for (attempt in 1..PLAY_MAX_RETRIES) {
            MediaControlService.play(target)
            lastMediaDispatch[trigger] = SystemClock.uptimeMillis()
            DebugLog.log(TAG, "$label (attempt $attempt)")

            val playStartedAt = SystemClock.uptimeMillis()
            var playingSince = 0L
            var lastLogged = ""
            var deadline = playStartedAt + PLAY_START_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val live = MediaControlService.controllersFor(context, packageName).firstOrNull()
                val playing = live != null && MediaControlService.isPlaying(live)

                if (diagnostics) {
                    val description = MediaDiagnostics.describe(live?.playbackState)
                    if (description != lastLogged) {
                        lastLogged = description
                        MediaDiagnostics.record(
                            "t+${SystemClock.uptimeMillis() - playStartedAt}ms " +
                                "(attempt $attempt): $description"
                        )
                    }
                }

                if (playing) {
                    val now = SystemClock.uptimeMillis()
                    if (playingSince == 0L) {
                        // Seen it start: now it has to stay up for the stability window.
                        // Margin one poll so the loop's `now < deadline` cannot skip the
                        // success check on the final tick.
                        playingSince = now
                        deadline = now + PLAY_STABILITY_MS + PLAY_POLL_MS
                    } else if (now - playingSince >= PLAY_STABILITY_MS) {
                        lastMediaDispatch[trigger] = now
                        DebugLog.log(TAG, "$packageName confirmed playing")
                        if (diagnostics) {
                            MediaDiagnostics.finish(context, packageName, "confirmed playing (attempt $attempt)")
                        }
                        return
                    }
                } else if (playingSince != 0L) {
                    dropped = true
                    DebugLog.log(TAG, "$packageName dropped back to paused, re-asserting play")
                    break
                }

                Thread.sleep(PLAY_POLL_MS)
            }

            target = MediaControlService.controllersFor(context, packageName).firstOrNull() ?: target
            if (attempt < PLAY_MAX_RETRIES) {
                DebugLog.log(TAG, "$packageName not confirmed playing, retrying")
            }
        }
        DebugLog.log(TAG, "$packageName did not report playing after $PLAY_MAX_RETRIES attempts, next press is live")
        if (diagnostics) {
            val outcome = if (dropped) "play dropped back to paused" else "never reported playing"
            MediaDiagnostics.finish(context, packageName, outcome)
        }
    }
}
