package com.redtrigger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.redtrigger.AppCatalog
import com.redtrigger.BootReceiver
import com.redtrigger.DebugLog
import com.redtrigger.InputReader
import com.redtrigger.MediaControlService
import com.redtrigger.MediaDiagnostics
import com.redtrigger.TriggerAction
import com.redtrigger.TriggerGesture
import com.redtrigger.TriggerManager
import com.redtrigger.TriggerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val GITHUB_URL = "https://github.com/lzampier/RedTrigger"

/** Top-level nav: main screen vs about */
enum class Screen { Main, About }

@Composable
fun MainScreen() {
    var currentScreen by remember { mutableStateOf(Screen.Main) }

    when (currentScreen) {
        Screen.Main -> MainContent(
            onNavigate = { currentScreen = it }
        )
        Screen.About -> AboutScreen(
            onBack = { currentScreen = Screen.Main }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(onNavigate: (Screen) -> Unit) {
    var triggersEnabled by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermission by remember { mutableStateOf(false) }
    var keyMapperInstalled by remember { mutableStateOf(false) }
    var autoEnableOnBoot by remember { mutableStateOf(false) }
    var notificationAccess by remember { mutableStateOf(false) }
    var statusTick by remember { mutableStateOf(0L) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Action picker state
    var gestureTarget by remember { mutableStateOf<InputReader.Trigger?>(null) }
    var actionTarget by remember { mutableStateOf<ActionTarget?>(null) }
    var appPickRequest by remember { mutableStateOf<AppPickRequest?>(null) }
    var shellTarget by remember { mutableStateOf<ActionTarget?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    val refreshState: () -> Unit = {
        triggersEnabled = TriggerManager.isTriggersEnabled(context)
        keyMapperInstalled = TriggerManager.isKeyMapperInstalled(context)
        autoEnableOnBoot = BootReceiver.isAutoEnableEnabled(context)
        shizukuInstalled = TriggerManager.isShizukuInstalled(context)
        shizukuRunning = TriggerManager.isShizukuRunning()
        shizukuPermission = TriggerManager.isShizukuPermission()
        notificationAccess = MediaControlService.isEnabled(context)
    }

    val shizukuOk = shizukuInstalled && shizukuRunning && shizukuPermission
    val prerequisitesMet = shizukuOk  // WRITE_SECURE_SETTINGS is auto-granted via Shizuku

    LaunchedEffect(shizukuOk, triggersEnabled) {
        // If the user grants Shizuku permission while triggers are already enabled in the system,
        // we should start the service to ensure the watchdog and reader are actually running.
        if (shizukuOk && triggersEnabled && !TriggerService.isRunning) {
            DebugLog.log("MainScreen", "Shizuku became ready while triggers were enabled. Starting service.")
            try {
                context.startForegroundService(Intent(context, TriggerService::class.java))
            } catch (e: Exception) {
                DebugLog.log("MainScreen", "Failed to start service: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    // Poll at 5s normally, 1s while prerequisites aren't met (user is actively fixing things)
    LaunchedEffect(Unit) {
        while (true) {
            val interval = if (shizukuInstalled && shizukuRunning && shizukuPermission) 5000L else 1000L
            delay(interval)
            statusTick++
            refreshState()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar with overflow menu ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 16.dp)
            ) {
                // Title area
                Column(
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(
                        text = "🎮 RedTrigger",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val versionName = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                    } catch (_: Exception) { "?" }
                    Text(
                        text = "v$versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }

                // Three-dot menu
                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuExpanded = false
                                onNavigate(Screen.About)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // ── Prerequisites Card ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Prerequisites",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        // Shizuku (single row, smart fix)
                        PrerequisiteRow(
                            label = "Shizuku",
                            status = shizukuOk,
                            fixLabel = when {
                                !shizukuInstalled -> "Install"
                                !shizukuRunning -> "Open"
                                !shizukuPermission -> "Grant"
                                else -> ""
                            },
                            subtitle = when {
                                !shizukuInstalled -> "Not installed"
                                !shizukuRunning -> "Not running"
                                !shizukuPermission -> "Permission needed"
                                else -> null
                            },
                            onFix = {
                                when {
                                    !shizukuInstalled -> {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            data = Uri.parse("market://details?id=$SHIZUKU_PACKAGE")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                                                data = Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
                                            }
                                            context.startActivity(webIntent)
                                        }
                                    }
                                    !shizukuRunning -> {
                                        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                                        if (intent != null) {
                                            context.startActivity(intent)
                                        } else {
                                            Toast.makeText(context, "Can't open Shizuku", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    !shizukuPermission -> {
                                        try {
                                            rikka.shizuku.Shizuku.requestPermission(0)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        )

                        // Notification access — required only for per-app media actions
                        PrerequisiteRow(
                            label = "Notification access",
                            status = notificationAccess,
                            fixLabel = "Grant",
                            subtitle = "Needed to target one app's playback",
                            onFix = {
                                try {
                                    context.startActivity(
                                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                // ── Main Toggle ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            !prerequisitesMet -> MaterialTheme.colorScheme.surfaceVariant
                            triggersEnabled -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable Triggers",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (prerequisitesMet)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )

                        Switch(
                            checked = triggersEnabled,
                            enabled = prerequisitesMet,
                            onCheckedChange = { enabled ->
                                val success = if (enabled) {
                                    TriggerManager.enableTriggers(context)
                                } else {
                                    TriggerManager.disableTriggers(context)
                                }
                                if (success) {
                                    triggersEnabled = enabled
                                    Toast.makeText(
                                        context,
                                        if (enabled) "Triggers enabled!" else "Triggers disabled",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(context, "Failed to toggle triggers", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                // ── Runtime Status (only when active) ──
                AnimatedVisibility(
                    visible = triggersEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Runtime",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            val watchdogRunning = remember(statusTick) { TriggerService.isRunning }
                            val readerState = remember(statusTick) { InputReader.state }

                            StatusRow("Watchdog Running", watchdogRunning)
                            StatusRowTriState(
                                label = "Reader Active",
                                state = readerState
                            )
                        }
                    }
                }

                // ── Settings ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        val prefs = context.getSharedPreferences("RedTriggerPrefs", Context.MODE_PRIVATE)
                        var injectEnabled by remember { mutableStateOf(prefs.getBoolean("capture_inject", true)) }
                        var showRemapInfo by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            InputReader.injectionEnabled = injectEnabled
                        }

                        // Remap to Gamepad toggle with info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Remap to Gamepad",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = { showRemapInfo = !showRemapInfo },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Switch(
                                checked = injectEnabled,
                                onCheckedChange = { enabled ->
                                    injectEnabled = enabled
                                    prefs.edit().putBoolean("capture_inject", enabled).apply()
                                    InputReader.setInjectionEnabledLive(enabled)
                                    DebugLog.log("Config", "Key injection ${if (enabled) "ON" else "OFF"}")
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = showRemapInfo,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "How remapping works",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "ON: Triggers appear as standard L1/R1 gamepad buttons to all apps. " +
                                            "Games and emulators detect them automatically with no extra configuration.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "OFF: Triggers remain as raw F7/F8 keyboard keys. Most apps won't see them. " +
                                            "Apps like KeyMapper can still detect them in expert mode, but will require " +
                                            "Shizuku or root permissions on their end too.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Recommended: Keep ON unless you have a specific reason to use raw keys.",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        ToggleRow("Auto-enable on boot", autoEnableOnBoot) { enabled ->
                            BootReceiver.setAutoEnable(context, enabled)
                            autoEnableOnBoot = enabled
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Text(
                            text = "Trigger actions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "What each shoulder trigger does when pressed. " +
                                "Leave on None to use the triggers purely as gamepad buttons.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        ActionRow(
                            label = "Left trigger (F7)",
                            trigger = InputReader.Trigger.LEFT,
                            refreshKey = statusTick,
                            onClick = { gestureTarget = InputReader.Trigger.LEFT }
                        )

                        ActionRow(
                            label = "Right trigger (F8)",
                            trigger = InputReader.Trigger.RIGHT,
                            refreshKey = statusTick,
                            onClick = { gestureTarget = InputReader.Trigger.RIGHT }
                        )
                    }
                }

                // ── Tools ──
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Tools",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "KeyMapper lets you assign custom actions to the shoulder triggers " +
                                "(e.g. volume control, screenshots, app shortcuts). " +
                                "It works with RedTrigger both with remapping on (detects L1/R1 gamepad buttons) " +
                                "and off (detects raw F7/F8 keys in expert mode).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!keyMapperInstalled) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse("market://details?id=io.github.sds100.keymapper")
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install KeyMapper")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val intent = context.packageManager.getLaunchIntentForPackage("io.github.sds100.keymapper")
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        Toast.makeText(context, "KeyMapper not found", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open KeyMapper")
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        var diagnosticsEnabled by remember { mutableStateOf(MediaDiagnostics.isEnabled(context)) }

                        ToggleRow("Media diagnostics", diagnosticsEnabled) { enabled ->
                            diagnosticsEnabled = enabled
                            MediaDiagnostics.setEnabled(context, enabled)
                            DebugLog.log("Config", "Media diagnostics ${if (enabled) "ON" else "OFF"}")
                        }

                        Text(
                            text = "While on, each play action records its playback-state timing and " +
                                "platform dumps (media session, audio focus, foreground service, logcat). " +
                                "Run the trigger, then open the report.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val diagnosticsReport = remember(statusTick) { MediaDiagnostics.lastReport() }

                        OutlinedButton(
                            onClick = { showDiagnostics = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (diagnosticsReport.isBlank()) "Show media diagnostics" else "Show media diagnostics (report ready)")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // ── Action picker dialogs ──

        gestureTarget?.let { trigger ->
            GesturePickerDialog(
                trigger = trigger,
                onDismiss = { gestureTarget = null },
                onPick = { gesture ->
                    gestureTarget = null
                    actionTarget = ActionTarget(trigger, gesture)
                }
            )
        }

        actionTarget?.let { target ->
            ActionTypeDialog(
                target = target,
                onDismiss = { actionTarget = null },
                onPick = { kind ->
                    actionTarget = null
                    when (kind) {
                        ActionKind.None -> {
                            TriggerAction.save(context, target.trigger, target.gesture, TriggerAction.None)
                            statusTick++
                        }
                        ActionKind.QuickSwitch -> {
                            TriggerAction.save(context, target.trigger, target.gesture, TriggerAction.QuickSwitch)
                            statusTick++
                        }
                        ActionKind.Media -> appPickRequest = AppPickRequest(target, isMedia = true)
                        ActionKind.Launch -> appPickRequest = AppPickRequest(target, isMedia = false)
                        ActionKind.Shell -> shellTarget = target
                    }
                }
            )
        }

        appPickRequest?.let { request ->
            AppPickerDialog(
                onDismiss = { appPickRequest = null },
                onPick = { entry ->
                    appPickRequest = null
                    val action = if (request.isMedia) {
                        TriggerAction.MediaPlayPause(entry.packageName, entry.component)
                    } else {
                        TriggerAction.LaunchApp(entry.packageName, entry.component)
                    }
                    TriggerAction.save(context, request.target.trigger, request.target.gesture, action)
                    statusTick++
                }
            )
        }

        shellTarget?.let { target ->
            ShellCommandDialog(
                onDismiss = { shellTarget = null },
                onPick = { command ->
                    shellTarget = null
                    TriggerAction.save(context, target.trigger, target.gesture, TriggerAction.ShellCommand(command))
                    statusTick++
                }
            )
        }

        if (showDiagnostics) {
            MediaDiagnosticsDialog(
                report = MediaDiagnostics.lastReport(),
                onDismiss = { showDiagnostics = false }
            )
        }
    }
}

@Composable
private fun MediaDiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Media diagnostics") },
        text = {
            if (report.isBlank()) {
                Text(
                    text = "No report yet. Turn on Media diagnostics, run a media trigger, then reopen this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("RedTrigger media diagnostics", report))
                },
                enabled = report.isNotBlank()
            ) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// ── About Screen ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    BackHandler(onBack = onBack)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "🎮",
                    style = MaterialTheme.typography.displayLarge
                )

                Text(
                    text = "RedTrigger",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "v$versionName",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "System-wide shoulder trigger enabler for Nubia/RedMagic devices. Maps capacitive triggers to virtual gamepad buttons via Shizuku + uinput.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(GITHUB_URL)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(0.7f)
                ) {
                    Text("View on GitHub")
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Made by Lucas Zampieri",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ── Shared Composables ──

@Composable
fun PrerequisiteRow(
    label: String,
    status: Boolean,
    fixLabel: String,
    subtitle: String? = null,
    showFix: Boolean = !status,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (status) "✓" else "✗",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (status) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!status && subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showFix && fixLabel.isNotEmpty()) {
            OutlinedButton(
                onClick = onFix,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = fixLabel,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

@Composable
fun StatusRow(label: String, status: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (status) "✓" else "✗",
            style = MaterialTheme.typography.bodyLarge,
            color = if (status) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun StatusRowTriState(label: String, state: InputReader.State) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        when (state) {
            InputReader.State.RUNNING -> Text(
                text = "✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            InputReader.State.STARTING -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary
            )
            InputReader.State.STOPPED -> Text(
                text = "✗",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ── Trigger action pickers ──

enum class ActionKind { None, QuickSwitch, Media, Launch, Shell }

private data class ActionTarget(val trigger: InputReader.Trigger, val gesture: TriggerGesture)

private data class AppPickRequest(val target: ActionTarget, val isMedia: Boolean)

/** Summary lines for a trigger, one per bound gesture. */
private fun boundGestures(context: Context, trigger: InputReader.Trigger): List<Pair<TriggerGesture, TriggerAction>> =
    TriggerGesture.entries.mapNotNull { gesture ->
        val action = TriggerAction.load(context, trigger, gesture)
        if (action == TriggerAction.None) null else gesture to action
    }

@Composable
fun ActionRow(
    label: String,
    trigger: InputReader.Trigger,
    refreshKey: Long,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bound = remember(trigger, refreshKey) { boundGestures(context, trigger) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (bound.isEmpty()) {
                Text(
                    text = "No action",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                bound.forEach { (gesture, action) ->
                    Text(
                        text = "${gesture.label}: ${TriggerAction.describe(action)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Text(
            text = "Change",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun GesturePickerDialog(
    trigger: InputReader.Trigger,
    onDismiss: () -> Unit,
    onPick: (TriggerGesture) -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${trigger.name.lowercase().replaceFirstChar { it.uppercase() }} trigger") },
        text = {
            Column {
                Text(
                    text = "Choose which press to configure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                TriggerGesture.entries.forEach { gesture ->
                    val action = TriggerAction.load(context, trigger, gesture)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(gesture) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = gesture.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = TriggerAction.describe(action),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (action == TriggerAction.None)
                                MaterialTheme.colorScheme.onSurfaceVariant
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ActionTypeDialog(
    target: ActionTarget,
    onDismiss: () -> Unit,
    onPick: (ActionKind) -> Unit
) {
    val options = listOf(
        ActionKind.None to "None",
        ActionKind.QuickSwitch to "Switch to previous app",
        ActionKind.Media to "Play/pause a specific app",
        ActionKind.Launch to "Open an app",
        ActionKind.Shell to "Shell command"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.gesture.label} — ${target.trigger.name.lowercase()}") },
        text = {
            Column {
                options.forEach { (kind, title) ->
                    Text(
                        text = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(kind) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onPick: (AppCatalog.Entry) -> Unit) {
    val context = LocalContext.current
    // Listing apps resolves every launcher package's label, which is far too slow
    // for the main thread on a device with many apps.
    val apps by produceState(initialValue = emptyList<AppCatalog.Entry>()) {
        value = withContext(Dispatchers.IO) { AppCatalog.launchable(context) }
    }
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (filtered.isEmpty()) {
                    Text(
                        text = if (apps.isEmpty()) "Loading apps…" else "No apps found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(entry) }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = entry.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ShellCommandDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var command by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shell command") },
        text = {
            Column {
                Text(
                    text = "Run as the shell user, via sh -c. Whatever you type here runs with " +
                        "shell privileges, so only enter commands you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command") },
                    placeholder = { Text("input keyevent KEYCODE_MEDIA_NEXT") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(command) },
                enabled = command.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
