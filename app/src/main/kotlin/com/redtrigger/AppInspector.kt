package com.redtrigger

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Reads another app's manifest details without any SDK tooling.
 */
object AppInspector {

    /**
     * Receivers of the installed [packageName] that listen for
     * `android.intent.action.MEDIA_BUTTON`, as (component, priority) pairs.
     * Used so a media key can be aimed at one app instead of relying on the
     * system's global routing. Empty when none is found.
     */
    fun mediaButtonReceivers(context: Context, packageName: String): List<ComponentName> {
        if (packageName.isBlank()) return emptyList()

        return try {
            val pm = context.applicationContext.packageManager
            val probe = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName)
            pm.queryBroadcastReceivers(probe, 0)
                .mapNotNull { it.activityInfo }
                .filter { it.exported && it.packageName == packageName }
                .map { ComponentName(it.packageName, it.name) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
