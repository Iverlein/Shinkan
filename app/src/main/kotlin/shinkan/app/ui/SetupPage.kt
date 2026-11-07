package shinkan.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.iverlein.shinkan.R
import shinkan.app.admin.SelfHold

/**
 * Shown while Shinkan is not device owner (SPEC §8.3): the hold taken here,
 * through Wireless debugging (§3.3), on Android 11 and later, the code typed
 * into Shinkan's notification, or into the page while it shares the screen
 * with Settings; then the adb command of §3.1 and the QR way of §3.2.
 */
@Composable
fun SetupPage(command: String) = Column(Modifier.verticalScroll(rememberScrollState())) {
    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Spacer(Modifier.height(16.dp))
        SelfHoldSteps()
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.setup_computer), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_body))
    Spacer(Modifier.height(16.dp))
    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_after))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_qr))
    Spacer(Modifier.height(24.dp))
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun SelfHoldSteps() {
    val context = LocalContext.current
    val stage by SelfHold.stage.collectAsState()
    var code by remember { mutableStateOf("") }
    // The code is typed into the notification; the page's own field only makes sense while
    // Settings stays on screen beside Shinkan, since leaving Settings closes its code.
    val split = rememberMultiWindow()
    var noticesOn by remember { mutableStateOf(notificationsOn(context)) }
    var refused by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        noticesOn = notificationsOn(context)
        if (noticesOn) refused = false
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        noticesOn = notificationsOn(context)
        if (granted && noticesOn) SelfHold.ask(context) else refused = true
    }
    fun open(action: String) = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Text(stringResource(R.string.selfhold_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.selfhold_body))
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.selfhold_step_developer))
    OutlinedButton(onClick = { open(Settings.ACTION_DEVICE_INFO_SETTINGS) }) { Text(stringResource(R.string.selfhold_open_about)) }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.selfhold_step_wireless))
    OutlinedButton(onClick = { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) {
        Text(stringResource(R.string.selfhold_open_developer))
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.selfhold_step_code))
    OutlinedButton(onClick = {
        noticesOn = notificationsOn(context)
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        when {
            noticesOn -> SelfHold.ask(context)
            // Asked once by the system; after a second refusal it no longer asks, and says no at once.
            !granted -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Allowed, but switched off in Settings, for the app or for this notification.
            else -> refused = true
        }
    }) { Text(stringResource(R.string.selfhold_notify)) }
    if (refused && !noticesOn) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.selfhold_notifications_off), color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text(stringResource(R.string.selfhold_notification_settings)) }
    }
    if (split) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.selfhold_step_here))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.selfhold_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.width(180.dp),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = code.length == 6 && stage != SelfHold.Stage.Working,
            onClick = { SelfHold.start(context, code) },
        ) { Text(stringResource(R.string.selfhold_take)) }
    }
    Spacer(Modifier.height(8.dp))
    when (val s = stage) {
        SelfHold.Stage.Idle -> {}
        SelfHold.Stage.Working -> Text(stringResource(R.string.selfhold_working))
        SelfHold.Stage.Done -> Text(stringResource(R.string.selfhold_done))
        is SelfHold.Stage.Failed -> {
            Text(stringResource(SelfHold.whyText(s.why)), color = MaterialTheme.colorScheme.primary)
            s.detail?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Whether Shinkan shares the screen (split screen, a free window), followed as it changes. */
@Composable
private fun rememberMultiWindow(): Boolean {
    val activity = LocalContext.current as? ComponentActivity ?: return false
    var multi by remember { mutableStateOf(activity.isInMultiWindowMode) }
    DisposableEffect(activity) {
        val listener = Consumer<MultiWindowModeChangedInfo> { multi = it.isInMultiWindowMode }
        activity.addOnMultiWindowModeChangedListener(listener)
        onDispose { activity.removeOnMultiWindowModeChangedListener(listener) }
    }
    return multi
}

/** Whether Shinkan's notification of the code can show: the app's notifications and its channel both on. */
private fun notificationsOn(context: Context): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) return false
    val channel = nm.getNotificationChannel(SelfHold.CHANNEL) ?: return true
    return channel.importance != NotificationManager.IMPORTANCE_NONE
}
