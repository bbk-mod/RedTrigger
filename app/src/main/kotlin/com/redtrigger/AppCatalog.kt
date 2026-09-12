package com.redtrigger

import android.content.Context
import android.content.Intent

/**
 * Lists apps that can be launched, for the action picker.
 */
object AppCatalog {

    data class Entry(
        val label: String,
        val packageName: String,
        /** "package/ActivityName", the form `am start -n` wants. */
        val component: String
    )

    fun launchable(context: Context): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return try {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    Entry(
                        label = info.loadLabel(pm).toString(),
                        packageName = activity.packageName,
                        component = "${activity.packageName}/${activity.name}"
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            DebugLog.log("AppCatalog", "Failed to list apps: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }
}
