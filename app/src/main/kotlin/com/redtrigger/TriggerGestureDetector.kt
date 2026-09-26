package com.redtrigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration

/**
 * Turns raw press/release edges from a shoulder trigger into gestures.
 *
 * InputService reports a plain down/up pair; this class decides whether that was
 * a tap, a double-tap, a triple-tap, a hold, or a tap-then-hold, and dispatches
 * the action bound to that gesture.
 *
 * Latency tradeoff: telling a single tap apart from the start of a double-tap
 * requires waiting one double-tap window after the release. That delay is only
 * paid when a multi-tap gesture is actually bound — if a trigger only has TAP (and
 * optionally HOLD) configured, the action fires the moment the trigger is
 * released, exactly as it did before gestures existed.
 *
 * A hold fires as soon as the long-press threshold elapses, while the trigger is
 * still down, rather than on release, so holding feels immediate.
 *
 * Every state transition happens on the main thread; the binder callback only
 * posts.
 */
object TriggerGestureDetector {
    private const val TAG = "Gesture"

    /** Beyond this, a fourth tap starts a fresh sequence. */
    private const val MAX_TAPS = 3

    private val handler = Handler(Looper.getMainLooper())
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    private class State {
        var tapCount = 0
        var longPressFired = false
        var followsTap = false
        var pressStartTime = 0L
        var lastReleaseTime = 0L
        var longPressRunnable: Runnable? = null
        var finalizeRunnable: Runnable? = null
    }

    private val states = HashMap<InputReader.Trigger, State>()

    fun onEvent(context: Context, trigger: InputReader.Trigger, isDown: Boolean) {
        val appContext = context.applicationContext
        handler.post { handleOnMain(appContext, trigger, isDown) }
    }

    private fun handleOnMain(context: Context, trigger: InputReader.Trigger, isDown: Boolean) {
        val state = states.getOrPut(trigger) { State() }

        val wantsHold = TriggerAction.isBound(context, trigger, TriggerGesture.LONG_PRESS)
        val wantsTapHold = TriggerAction.isBound(context, trigger, TriggerGesture.TAP_HOLD)
        val wantsMultiTap = TriggerAction.isBound(context, trigger, TriggerGesture.DOUBLE_TAP) ||
            TriggerAction.isBound(context, trigger, TriggerGesture.TRIPLE_TAP)
        // Tap-then-hold also relies on the gap after a tap, so a lone tap must be
        // held back until the second press has had its chance to arrive.
        val wantsSequence = wantsMultiTap || wantsTapHold

        if (isDown) {
            // A new press means the previous sequence did not end in a finalize.
            state.finalizeRunnable?.let { handler.removeCallbacks(it) }
            state.finalizeRunnable = null
            state.longPressRunnable?.let { handler.removeCallbacks(it) }
            state.longPressRunnable = null

            val now = SystemClock.uptimeMillis()

            // Guard against a lost release: if the previous sequence is long over, the
            // stale tap count must not fold into this press and resolve as a double tap.
            // Measured from the last release, not the last press — the double-tap window
            // is release-to-press, so a long first tap must not look like a stale
            // sequence (that would drop the TAP and break tap-then-hold).
            if (state.tapCount > 0 && now - state.lastReleaseTime > doubleTapTimeout) {
                state.tapCount = 0
            }

            state.pressStartTime = now
            state.tapCount++
            state.longPressFired = false
            // A press continuing an in-flight sequence is the hold half of tap-then-hold.
            state.followsTap = state.tapCount >= 2

            if (wantsHold || wantsTapHold) {
                val runnable = Runnable {
                    state.longPressFired = true
                    state.tapCount = 0
                    when {
                        wantsTapHold && state.followsTap ->
                            dispatch(context, trigger, TriggerGesture.TAP_HOLD)
                        wantsHold ->
                            dispatch(context, trigger, TriggerGesture.LONG_PRESS)
                    }
                }
                state.longPressRunnable = runnable
                handler.postDelayed(runnable, longPressTimeout)
            }

            return
        }

        // Release
        state.lastReleaseTime = SystemClock.uptimeMillis()
        state.longPressRunnable?.let { handler.removeCallbacks(it) }
        state.longPressRunnable = null

        if (state.longPressFired) {
            // The hold already dispatched; this release must not also count as a tap.
            state.longPressFired = false
            state.tapCount = 0
            return
        }

        if (!wantsSequence) {
            // Nothing to disambiguate, so duration is irrelevant: fire on release.
            state.tapCount = 0
            dispatch(context, trigger, TriggerGesture.TAP)
            return
        }

        // A sequence is bound, so a press held past the long-press threshold must not
        // count as a tap even when no HOLD action is bound — otherwise holding would
        // leave a phantom tap behind, and a hold followed by a tap would resolve as a
        // double tap the user never asked for.
        if (SystemClock.uptimeMillis() - state.pressStartTime >= longPressTimeout) {
            state.tapCount = 0
            return
        }

        if (state.tapCount >= MAX_TAPS) {
            state.tapCount = 0
            dispatch(context, trigger, TriggerGesture.TRIPLE_TAP)
            return
        }

        // Wait out the window in case another tap follows.
        val runnable = Runnable {
            val taps = state.tapCount
            state.tapCount = 0
            state.finalizeRunnable = null
            dispatch(
                context,
                trigger,
                if (taps >= 2) TriggerGesture.DOUBLE_TAP else TriggerGesture.TAP
            )
        }
        state.finalizeRunnable = runnable
        handler.postDelayed(runnable, doubleTapTimeout)
    }

    private fun dispatch(context: Context, trigger: InputReader.Trigger, gesture: TriggerGesture) {
        if (TriggerAction.load(context, trigger, gesture) == TriggerAction.None) return
        DebugLog.log(TAG, "${trigger.name} ${gesture.label}")
        ActionDispatcher.handle(context, trigger, gesture)
    }

    /** Drop any pending timers, e.g. when the trigger service stops. */
    fun reset() {
        handler.post {
            states.values.forEach { state ->
                state.longPressRunnable?.let { handler.removeCallbacks(it) }
                state.finalizeRunnable?.let { handler.removeCallbacks(it) }
            }
            states.clear()
        }
    }
}
