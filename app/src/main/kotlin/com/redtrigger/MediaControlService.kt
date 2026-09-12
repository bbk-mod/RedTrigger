package com.redtrigger

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Notification listener that exists for one reason: it is the only way for an
 * ordinary app to reach *other* apps' MediaSessions.
 *
 * `MediaSessionManager.getActiveSessions()` requires either the signature-level
 * MEDIA_CONTENT_CONTROL permission (not grantable to third-party apps) or an
 * enabled NotificationListenerService. The shell uid does not hold
 * MEDIA_CONTENT_CONTROL either, so this cannot be done from InputService.
 *
 * With this enabled we can address one specific app's session by package
 * instead of dispatching a global media key to whichever session happens to be
 * active — which is the whole point, since a global key would pause YouTube
 * when we asked for Deezer.
 *
 * Threading: MediaSessionManager and MediaController both build Handlers
 * internally, so they must be touched from a thread that has a Looper. Every
 * framework call here is marshalled onto the main thread; the waiting is done on
 * the caller's thread. A caller parked on the latch therefore never holds up the
 * delivery of the callback it is waiting for.
 *
 * Note: this service does not read or act on notification content.
 */
class MediaControlService : NotificationListenerService() {

    companion object {
        private const val TAG = "Media"

        /**
         * Bound on a marshalled main-thread call. Deliberately short: this covers a
         * binder round-trip, not user-visible work, and a wedged main thread must not
         * be able to hang a trigger action indefinitely.
         */
        private const val MAIN_THREAD_TIMEOUT_MS = 2_000L

        @Volatile
        private var instance: MediaControlService? = null

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** True when the user has granted notification access to this app. */
        fun isEnabled(context: Context): Boolean = try {
            NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
                .contains(context.applicationContext.packageName)
        } catch (_: Exception) {
            false
        }

        private fun component(context: Context) =
            ComponentName(context.applicationContext, MediaControlService::class.java)

        /** Runs [block] on a looper thread, blocking the caller for the result. */
        private fun <T> onMain(block: () -> T): T? {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()

            val latch = CountDownLatch(1)
            val result = AtomicReference<T?>(null)

            val posted = mainHandler.post {
                try {
                    result.set(block())
                } catch (e: Exception) {
                    DebugLog.log(TAG, "main-thread work failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
            if (!posted) return null

            return if (latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result.get() else null
        }

        /** Must run on the main thread. */
        private fun activeSessionsOnMain(context: Context, packageName: String): List<MediaController> {
            val service = instance ?: return emptyList()

            return try {
                val manager = service.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                manager.getActiveSessions(component(context))
                    .orEmpty()
                    .filter { it.packageName == packageName }
            } catch (e: Exception) {
                DebugLog.log(TAG, "getActiveSessions failed: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        }

        /**
         * All sessions belonging to [packageName]. Empty when notification access is
         * not granted, when the service is not connected, or when the app has no
         * live session.
         */
        fun controllersFor(context: Context, packageName: String): List<MediaController> =
            onMain { activeSessionsOnMain(context, packageName) }.orEmpty()

        /**
         * Wait until [packageName] has a session, up to [timeoutMs].
         *
         * Returns immediately if a session already exists. Uses the
         * active-sessions callback rather than polling, so a fast app costs
         * milliseconds and the timeout is only ever paid in full when the app
         * genuinely never shows up.
         *
         * The callback is registered against [mainHandler] rather than the calling
         * thread's looper, because the calling thread is parked on the latch below.
         *
         * Blocking; call off the main thread.
         */
        fun awaitSession(context: Context, packageName: String, timeoutMs: Long): MediaController? {
            controllersFor(context, packageName).firstOrNull()?.let { return it }
            if (instance == null) return null

            val latch = CountDownLatch(1)
            val found = AtomicReference<MediaController?>(null)

            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                val match = controllers.orEmpty().firstOrNull { it.packageName == packageName }
                if (match != null && found.compareAndSet(null, match)) {
                    latch.countDown()
                }
            }

            val registered = onMain {
                val service = instance
                if (service == null) {
                    false
                } else {
                    try {
                        val manager = service.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                        manager.addOnActiveSessionsChangedListener(listener, component(context), mainHandler)
                        true
                    } catch (e: Exception) {
                        DebugLog.log(TAG, "addOnActiveSessionsChangedListener failed: ${e.message}")
                        false
                    }
                }
            } ?: false

            if (!registered) return null

            return try {
                if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) found.get()
                else controllersFor(context, packageName).firstOrNull()
            } catch (e: Exception) {
                DebugLog.log(TAG, "awaitSession failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            } finally {
                onMain<Unit> {
                    try {
                        val service = instance
                        if (service != null) {
                            val manager = service.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                            manager.removeOnActiveSessionsChangedListener(listener)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        fun isPlaying(controller: MediaController): Boolean =
            controller.playbackState?.state == PlaybackState.STATE_PLAYING

        /**
         * Transport calls are marshalled to the same thread the controller was
         * created on, so we never depend on MediaController's internal threading.
         */
        fun play(controller: MediaController) {
            onMain { controller.transportControls.play() }
        }

        fun pause(controller: MediaController) {
            onMain { controller.transportControls.pause() }
        }
    }

    override fun onListenerConnected() {
        instance = this
        DebugLog.log(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        DebugLog.log(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
