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
 * a tap, a double-tap, a triple-tap or a hold, and dispatches the action bound to
 * that gesture.
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
        var pressStartTime = 0L
        var lastDownTime = 0L
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
        val wantsMultiTap = TriggerAction.isBound(context, trigger, TriggerGesture.DOUBLE_TAP) ||
            TriggerAction.isBound(context, trigger, TriggerGesture.TRIPLE_TAP)

        if (isDown) {
            // A new press means the previous sequence did not end in a finalize.
            state.finalizeRunnable?.let { handler.removeCallbacks(it) }
            state.finalizeRunnable = null
            state.longPressRunnable?.let { handler.removeCallbacks(it) }
            state.longPressRunnable = null

            val now = SystemClock.uptimeMillis()

            // Guard against a lost release: if the previous sequence is long over, the
            // stale tap count must not fold into this press and resolve as a double tap.
            if (state.tapCount > 0 && now - state.lastDownTime > doubleTapTimeout) {
                state.tapCount = 0
            }

            state.lastDownTime = now
            state.pressStartTime = now
            state.tapCount++
            state.longPressFired = false

            if (wantsHold) {
                val runnable = Runnable {
                    state.longPressFired = true
                    state.tapCount = 0
                    dispatch(context, trigger, TriggerGesture.LONG_PRESS)
                }
                state.longPressRunnable = runnable
                handler.postDelayed(runnable, longPressTimeout)
            }

            return
        }

        // Release
        state.longPressRunnable?.let { handler.removeCallbacks(it) }
        state.longPressRunnable = null

        if (state.longPressFired) {
            // The hold already dispatched; this release must not also count as a tap.
            state.longPressFired = false
            state.tapCount = 0
            return
        }

        if (!wantsMultiTap) {
            // Nothing to disambiguate, so duration is irrelevant: fire on release.
            state.tapCount = 0
            dispatch(context, trigger, TriggerGesture.TAP)
            return
        }

        // Multi-tap is bound, so a press held past the long-press threshold must not
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
