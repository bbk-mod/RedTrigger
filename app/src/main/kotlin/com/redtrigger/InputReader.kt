package com.redtrigger

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Manages the Shizuku UserService that reads shoulder trigger input events.
 *
 * The UserService (InputService) runs in Shizuku's process with shell
 * permissions, giving it access to /dev/input/eventX devices.
 * Events are delivered back via AIDL callback.
 */
object InputReader {
    enum class Trigger { LEFT, RIGHT }
    enum class State { STOPPED, STARTING, RUNNING }

    /** Callback for trigger events */
    var onTrigger: ((trigger: Trigger, isDown: Boolean) -> Unit)? = null

    @Volatile var state: State = State.STOPPED; private set

    /** Convenience check for backwards compat */
    val isRunning: Boolean get() = state == State.RUNNING

    @Volatile var lastEvent: String = "No events"; private set
    @Volatile var eventCount = 0; private set

    /** Whether key injection (remap to gamepad) is enabled */
    @Volatile var injectionEnabled = true

    /** App context for re-applying settings after Shizuku grants permission */
    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private var inputService: IInputService? = null

    /** Shizuku listeners are registered once per start/stop cycle */
    private var listenersRegistered = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.redtrigger", InputService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("input")
        .debuggable(BuildConfig.DEBUG)
        // Bump whenever IInputService changes: Shizuku uses this to decide whether an
        // already-running service (loaded from the previous APK) must be recycled.
        .version(2)

    /**
     * Update injection state and propagate to the running InputService.
     */
    fun setInjectionEnabledLive(enabled: Boolean) {
        injectionEnabled = enabled
        try {
            inputService?.setInjectionEnabled(enabled)
            DebugLog.log("InputReader", "Injection ${if (enabled) "ON" else "OFF"}")
        } catch (e: Exception) {
            DebugLog.log("InputReader", "Failed to propagate injection state: ${e.message}")
        }
    }

    /**
     * Run a command as the shell uid via the UserService. Returns combined
     * stdout+stderr, or null when the service is not connected.
     *
     * Blocks the calling thread until the command exits (bounded by the
     * service-side timeout), so call this off the main thread.
     */
    fun runShellCommand(vararg argv: String): String? {
        val service = inputService
        if (service == null) {
            DebugLog.log("Shell", "Not connected, cannot run: ${argv.joinToString(" ")}")
            return null
        }

        return try {
            service.runShellCommand(arrayOf(*argv))
        } catch (e: Exception) {
            DebugLog.log("Shell", "ERROR: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            inputService = IInputService.Stub.asInterface(service)
            state = State.RUNNING
            DebugLog.log("InputReader", "Shizuku UserService connected")

            try {
                // Self-grant WRITE_SECURE_SETTINGS via shell uid so user doesn't need ADB
                inputService?.grantPermission("android.permission.WRITE_SECURE_SETTINGS")

                // Now that permission is granted, apply trigger settings (handles first-run
                // where enableTriggers() couldn't write because permission wasn't granted yet)
                appContext?.let { TriggerManager.applyTriggerSettings(it) }

                // startReading() detects devices itself and logs the result
                inputService?.setInjectionEnabled(injectionEnabled)
                DebugLog.log("InputReader", "Config: inject=$injectionEnabled")

                inputService?.startReading(triggerCallback)
                DebugLog.log("InputReader", "Reading started")
            } catch (e: Exception) {
                DebugLog.log("InputReader", "ERROR starting: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inputService = null
            state = State.STOPPED
            DebugLog.log("InputReader", "UserService disconnected")
        }

        override fun onNullBinding(name: ComponentName?) {
            inputService = null
            state = State.STOPPED
            DebugLog.log("InputReader", "UserService returned null binding")
        }

        override fun onBindingDied(name: ComponentName?) {
            inputService = null
            state = State.STOPPED
            DebugLog.log("InputReader", "UserService binding died")
        }
    }

    private val triggerCallback = object : ITriggerCallback.Stub() {
        override fun onTriggerEvent(trigger: Int, isDown: Boolean) {
            val t = if (trigger == 0) Trigger.LEFT else Trigger.RIGHT
            val keyName = if (trigger == 0) "KEY_F7" else "KEY_F8"
            eventCount++
            lastEvent = "${t.name} ${if (isDown) "DOWN" else "UP"}"
            DebugLog.log("Shizuku", "Key $keyName (${t.name}) ${if (isDown) "DOWN" else "UP"} [#$eventCount]")
            onTrigger?.invoke(t, isDown)
        }

        override fun onRawKeyEvent(keyName: String?, isDown: Boolean) {
            DebugLog.log("Shizuku", "Raw key: ${keyName ?: "?"} ${if (isDown) "DOWN" else "UP"}")
        }

        override fun onDebugMessage(tag: String?, message: String?) {
            DebugLog.log(tag ?: "Shizuku", message ?: "")
        }
    }

    private val permissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DebugLog.log("InputReader", "Shizuku permission granted, retrying start")
            start()
        }
    }

    private val binderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        DebugLog.log("InputReader", "Shizuku binder received, retrying start")
        start()
    }

    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        DebugLog.log("InputReader", "Shizuku binder died, waiting for restart")
        inputService = null
        state = State.STOPPED
    }

    /**
     * Kept registered for the whole start/stop cycle so the reader recovers when
     * Shizuku starts or restarts. Re-registering is a no-op.
     */
    private fun registerListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        try { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) } catch (_: Exception) {}
        try { Shizuku.addBinderDeadListener(binderDeadListener) } catch (_: Exception) {}
    }

    private fun unregisterListeners() {
        if (!listenersRegistered) return
        listenersRegistered = false
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Exception) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Exception) {}
    }

    fun start() {
        if (state == State.RUNNING || state == State.STARTING) {
            DebugLog.log("InputReader", "Already ${state.name.lowercase()}")
            return
        }

        state = State.STARTING
        registerListeners()

        try {
            val ping = Shizuku.pingBinder()
            DebugLog.log("InputReader", "Shizuku.pingBinder=$ping")
            if (!ping) {
                DebugLog.log("InputReader", "Shizuku not running yet, waiting for binder")
                state = State.STOPPED
                return
            }

            val perm = Shizuku.checkSelfPermission()
            DebugLog.log("InputReader", "Shizuku permission=$perm (0=granted)")
            if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                DebugLog.log("InputReader", "Missing Shizuku permission, waiting for user to grant")
                state = State.STOPPED
                try { Shizuku.addRequestPermissionResultListener(permissionListener) } catch (_: Exception) {}
                return
            }

            try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Exception) {}

            DebugLog.log("InputReader", "Binding UserService...")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (e: Exception) {
            state = State.STOPPED
            DebugLog.log("InputReader", "ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        try { inputService?.stopReading() } catch (_: Exception) {}
        try { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) } catch (_: Exception) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Exception) {}
        unregisterListeners()
        inputService = null
        state = State.STOPPED
        DebugLog.log("InputReader", "Stopped")
    }
}
