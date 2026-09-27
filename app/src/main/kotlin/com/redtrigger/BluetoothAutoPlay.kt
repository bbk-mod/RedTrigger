package com.redtrigger

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Starts a chosen app's playback when Bluetooth headphones connect.
 *
 * Detection uses AudioManager's audio-device callback rather than the Bluetooth
 * ACTION_CONNECTION_STATE_CHANGED broadcast: it fires when the audio route
 * actually becomes available (so playback is routed to the headphones from the
 * first sample), needs no BLUETOOTH_CONNECT runtime permission, and is the
 * approach Google documents for exactly this use case.
 *
 * The callback only lives while TriggerService runs, so this works as long as
 * triggers are enabled. It is not a wake-from-dead-process hook, and it
 * deliberately ignores devices that were already connected before the service
 * started.
 */
object BluetoothAutoPlay {
    private const val TAG = "AutoPlay"
    private const val PREFS = "RedTriggerPrefs"
    private const val KEY_ENABLED = "autoplay_bt_enabled"
    private const val KEY_PACKAGE = "autoplay_bt_package"
    private const val KEY_COMPONENT = "autoplay_bt_component"

    /** A2DP and SCO arriving back to back are one headset connecting, not two. */
    private const val DEBOUNCE_MS = 2_000L

    @Volatile
    private var lastAttempt = 0L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        DebugLog.log(TAG, "Auto-play on Bluetooth connect ${if (enabled) "ON" else "OFF"}")
    }

    /** The configured app as (packageName, component), or null when unset. */
    fun app(context: Context): Pair<String, String>? {
        val prefs = prefs(context)
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        val component = prefs.getString(KEY_COMPONENT, null) ?: return null
        return packageName to component
    }

    fun setApp(context: Context, packageName: String, component: String) {
        prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_COMPONENT, component)
            .apply()
        DebugLog.log(TAG, "Auto-play app set to $packageName")
    }

    /**
     * Callback to hand to `AudioManager.registerAudioDeviceCallback`. It reads
     * the current setting on every event, so toggling the setting or changing the
     * app takes effect without restarting the service.
     */
    fun callback(context: Context): AudioDeviceCallback {
        val appContext = context.applicationContext
        return object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (!isBluetoothHeadphones(addedDevices)) return

                // One headset can add A2DP and SCO in quick succession.
                val now = SystemClock.uptimeMillis()
                if (now - lastAttempt < DEBOUNCE_MS) return
                lastAttempt = now

                start(appContext)
            }
        }
    }

    private fun isBluetoothHeadphones(devices: Array<out AudioDeviceInfo>?): Boolean =
        devices.orEmpty().any { device ->
            device.isSink && when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> true
                else -> false
            }
        }

    private fun start(context: Context) {
        if (!isEnabled(context)) return

        val app = app(context)
        if (app == null) {
            DebugLog.log(TAG, "Bluetooth headphones connected, but no app is chosen")
            return
        }

        val (packageName, component) = app
        DebugLog.log(TAG, "Bluetooth headphones connected, starting $packageName")
        thread(name = "autoplay", isDaemon = true) {
            ActionDispatcher.startApp(context, packageName, component)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
